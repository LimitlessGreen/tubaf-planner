package de.tubaf.planner.service.scraping

import de.tubaf.planner.model.Course
import de.tubaf.planner.model.CourseType
import de.tubaf.planner.model.Lecturer
import de.tubaf.planner.model.Room
import de.tubaf.planner.model.RoomPlanSlot
import de.tubaf.planner.model.ScheduleEntry
import de.tubaf.planner.model.Semester
import de.tubaf.planner.repository.CourseRepository
import de.tubaf.planner.repository.CourseTypeRepository
import de.tubaf.planner.repository.LecturerRepository
import de.tubaf.planner.repository.RoomRepository
import de.tubaf.planner.repository.RoomPlanSlotRepository
import de.tubaf.planner.repository.ScheduleEntryRepository
import de.tubaf.planner.repository.StudyProgramRepository
import de.tubaf.planner.service.ChangeTrackingService
import de.tubaf.planner.service.SemesterService
import jakarta.annotation.PreDestroy
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
open class TubafScrapingService(
    private val changeTrackingService: ChangeTrackingService,
    private val semesterService: SemesterService,
    private val courseRepository: CourseRepository,
    private val courseTypeRepository: CourseTypeRepository,
    private val lecturerRepository: LecturerRepository,
    private val roomRepository: RoomRepository,
    private val roomPlanSlotRepository: RoomPlanSlotRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val studyProgramRepository: StudyProgramRepository,
    @Value("\${tubaf.scraper.base-url:https://evlvz.hrz.tu-freiberg.de/~vover}")
    private val baseUrl: String,
    @Value(
        "\${tubaf.scraper.user-agent:Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}"
    )
    private val userAgent: String,
) {
    private val logger = LoggerFactory.getLogger(TubafScrapingService::class.java)
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val roomPlanDateFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")
    private val progressTracker = ScrapingProgressTracker()
    private val httpProxy: Proxy = detectProxy()
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scraping-worker").apply { isDaemon = true }
        }
    private val activeJob = AtomicReference<Future<*>?>(null)
    private val jobLock = Any()
    @Volatile private var cancellationMessage: String? = null

    companion object {
        private const val DEFAULT_PROXY_PORT = 8080
    }

    fun isJobRunning(): Boolean {
        val current = activeJob.get()
        return current != null && !current.isDone && !current.isCancelled
    }

    fun startDiscoveryJob(): Boolean {
        return submitJob("Starte Discovery-Scraping") { discoverAndScrapeAvailableSemesters() }
    }

    fun startRemoteScrapingJob(semesterIdentifiers: List<String>): Boolean {
        if (semesterIdentifiers.isEmpty()) {
            throw IllegalArgumentException("Es wurden keine Semester angegeben")
        }
        return submitJob("Starte Scraping für ausgewählte Semester") {
            scrapeRemoteSemesters(semesterIdentifiers)
        }
    }

    fun startLocalScrapingJob(semesterId: Long): Boolean {
        val semester =
            semesterService.getAllSemesters().find { it.id == semesterId }
                ?: throw IllegalArgumentException("Semester mit ID $semesterId nicht gefunden")
        return submitJob("Starte Scraping für ${semester.name}") { scrapeSemesterData(semester) }
    }

    @PreDestroy
    fun shutdownExecutor() {
        executor.shutdownNow()
    }

    fun discoverAndScrapeAvailableSemesters(): List<ScrapingResult> {
        val session = TubafScraperSession()
        val semesterOptions = session.fetchSemesterOptions()

        progressTracker.start(
            totalCount = semesterOptions.size,
            task = "Discovery",
            message = "Starte Discovery für ${semesterOptions.size} Semester"
        )

        if (semesterOptions.isEmpty()) {
            logger.warn(
                "Keine Semester auf der TUBAF-Seite gefunden – verwende vorhandene Semester aus der Datenbank"
            )
            progressTracker.finish("Keine Semester auf der Webseite gefunden")
            return semesterService.getAllSemesters().map { scrapeSemesterData(it) }
        }

        val results = mutableListOf<ScrapingResult>()
        try {
            for ((index, option) in semesterOptions.withIndex()) {
                ensureNotCancelled()
                logger.info(
                    "[{} / {}] Entdeckt: {}",
                    index + 1,
                    semesterOptions.size,
                    option.displayName
                )
                progressTracker.log("INFO", "Scraping ${option.displayName}")

                session.selectSemester(option)
                val semester = getOrCreateSemester(option)
                val semesterResult =
                    scrapeSemesterDataWithSession(session, semester, trackProgress = false)
                results += semesterResult

                progressTracker.update(
                    task = "Scraping ${option.displayName}",
                    processed = index + 1,
                    total = semesterOptions.size,
                    message =
                        "${option.displayName}: ${semesterResult.totalEntries} Einträge (neu ${semesterResult.newEntries}, aktualisiert ${semesterResult.updatedEntries})"
                )
            }

            progressTracker.finish("Discovery abgeschlossen: ${results.size} Semester verarbeitet")
            return results
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: Exception) {
            progressTracker.fail("Discovery fehlgeschlagen: ${ex.message}")
            throw ex
        }
    }

    fun getAvailableRemoteSemesters(): List<RemoteSemesterDescriptor> {
        val session = TubafScraperSession()
        val options = session.fetchSemesterOptions()

        return options.map { option ->
            RemoteSemesterDescriptor(
                displayName = option.displayName,
                shortName = buildShortName(option.displayName)
            )
        }
    }

    fun getProgressSnapshot(): ScrapingProgressSnapshot = progressTracker.snapshot()

    fun pauseScraping(message: String = "Scraping pausiert") {
        progressTracker.pause(message)
    }

    fun stopScraping(message: String = "Scraping gestoppt") {
        cancellationMessage = message
        val future = activeJob.getAndSet(null)
        if (future != null && !future.isDone) {
            future.cancel(true)
        } else {
            cancellationMessage = null
            progressTracker.reset(message)
        }
    }

    fun scrapeRemoteSemesters(semesterIdentifiers: List<String>): List<ScrapingResult> {
        if (semesterIdentifiers.isEmpty()) {
            return emptyList()
        }

        val session = TubafScraperSession()
        val options = session.fetchSemesterOptions()
        val lookup = buildSemesterLookup(options)

        logger.debug("Gefundene Remote-Semester: {}", options.map { it.displayName })
        logger.debug("Verfügbare Schlüssel: {}", lookup.keys)

        val matchedOptions = linkedMapOf<String, SemesterOption>()
        semesterIdentifiers.forEach { identifier ->
            val normalizedIdentifier = normalize(identifier)
            logger.debug("Prüfe Semester '{}' mit Schlüssel '{}'", identifier, normalizedIdentifier)
            val option =
                lookup[normalizedIdentifier]
                    ?: throw IllegalArgumentException(
                        "Semester '$identifier' wurde auf der TUBAF-Seite nicht gefunden"
                    )
            matchedOptions.putIfAbsent(option.displayName, option)
        }

        progressTracker.start(
            totalCount = matchedOptions.size,
            task = "Scraping ausgewählter Semester",
            message = "Starte Scraping für ${matchedOptions.size} Semester"
        )

        val results = mutableListOf<ScrapingResult>()
        try {
            matchedOptions.values.forEachIndexed { index, option ->
                ensureNotCancelled()
                logger.info("Starte Scraping für erkanntes Semester: {}", option.displayName)
                progressTracker.log("INFO", "Scraping ${option.displayName}")

                session.selectSemester(option)
                val semester = getOrCreateSemester(option)
                val result = scrapeSemesterDataWithSession(session, semester, trackProgress = false)
                results += result

                progressTracker.update(
                    task = "Scraping ${option.displayName}",
                    processed = index + 1,
                    total = matchedOptions.size,
                    message =
                        "${option.displayName}: ${result.totalEntries} Einträge (neu ${result.newEntries}, aktualisiert ${result.updatedEntries})"
                )
            }

            progressTracker.finish("Scraping abgeschlossen: ${results.size} Semester verarbeitet")
            return results
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: Exception) {
            progressTracker.fail("Scraping fehlgeschlagen: ${ex.message}")
            throw ex
        }
    }

    fun scrapeSemesterData(semester: Semester): ScrapingResult {
        logger.info("Starte Scraping für Semester {}", semester.name)
        requireNotNull(semester.id) { "Semester muss vor dem Scraping persistiert sein" }

        val session = TubafScraperSession()
        val semesterOption =
            session.fetchSemesterOptions().find { matchesSemesterOption(it, semester) }
                ?: throw IllegalArgumentException(
                    "Semester '${semester.name}' wurde auf der TUBAF-Seite nicht gefunden"
                )

        session.selectSemester(semesterOption)
        return scrapeSemesterDataWithSession(session, semester, trackProgress = true)
    }

    protected open fun scrapeSemesterDataWithSession(
        session: TubafScraperSession,
        semester: Semester,
        trackProgress: Boolean,
    ): ScrapingResult {
        val scrapingRun = changeTrackingService.startScrapingRun(semester.id!!, baseUrl)
        val stats = ScrapeStats()

        return try {
            val programs = session.fetchStudyPrograms()
            logger.info("Gefundene Studiengänge für {}: {}", semester.name, programs.size)
            progressTracker.log(
                "INFO",
                "📚 ${programs.size} Studiengänge gefunden für ${semester.name}"
            )

            if (trackProgress) {
                progressTracker.start(
                    totalCount = programs.size,
                    task = "Scraping ${semester.shortName ?: semester.name}",
                    message = "Starte Scraping für ${semester.name}"
                )
            }

            programs.forEachIndexed { index, program ->
                ensureNotCancelled()
                logger.info(
                    "   [{} / {}] Verarbeite Studiengang {} ({})",
                    index + 1,
                    programs.size,
                    program.code,
                    program.displayName,
                )

                val programStats = scrapeStudyProgram(session, semester, scrapingRun.id!!, program)
                stats.totalEntries += programStats.totalEntries
                stats.newEntries += programStats.newEntries
                stats.updatedEntries += programStats.updatedEntries
                stats.studyProgramsProcessed += programStats.studyProgramsProcessed

                if (trackProgress) {
                    progressTracker.update(
                        task = "${semester.shortName ?: semester.name}: ${program.code}",
                        processed = index + 1,
                        total = programs.size,
                        message = "${program.displayName}: ${programStats.totalEntries} Einträge"
                    )
                }
            }

            scrapeRoomPlans(session, semester, scrapingRun.id!!, stats)

            changeTrackingService.completeScrapingRun(
                scrapingRun.id!!,
                stats.totalEntries + stats.roomPlanEntries,
                stats.newEntries + stats.roomPlanEntriesNew,
                stats.updatedEntries + stats.roomPlanEntriesUpdated + stats.roomPlanEntriesDeactivated,
            )

            logger.info(
                "Scraping erfolgreich abgeschlossen – Kurse: {}, neu: {}, aktualisiert: {}, Raumpläne: {} (Slots neu {}, aktualisiert {}, deaktiviert {})",
                stats.totalEntries,
                stats.newEntries,
                stats.updatedEntries,
                stats.roomPlansProcessed,
                stats.roomPlanEntriesNew,
                stats.roomPlanEntriesUpdated,
                stats.roomPlanEntriesDeactivated,
            )

            if (trackProgress) {
                progressTracker.finish("Scraping ${semester.name} abgeschlossen")
            }
            stats.toResult()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: Exception) {
            changeTrackingService.failScrapingRun(
                scrapingRun.id!!,
                ex.message ?: ex.javaClass.simpleName
            )
            if (trackProgress) {
                progressTracker.fail("Scraping ${semester.name} fehlgeschlagen: ${ex.message}")
            }
            throw ex
        }
    }

    private fun scrapeStudyProgram(
        session: TubafScraperSession,
        semester: Semester,
        scrapingRunId: Long,
        program: StudyProgramOption,
    ): ScrapeStats {
        ensureNotCancelled()
        val stats = ScrapeStats()

        val initialDoc = session.openProgram(program)
        val fachSemesterOptions = parseFachSemesterOptions(initialDoc)

        if (fachSemesterOptions.isEmpty()) {
            val rows = parseScheduleRows(initialDoc, program, "")
            persistRows(rows, semester, scrapingRunId, stats)
            stats.studyProgramsProcessed += 1
            return stats
        }

        for ((index, fachSemester) in fachSemesterOptions.withIndex()) {
            ensureNotCancelled()
            val document =
                if (fachSemester.isPostRequired) {
                    session.openProgramSemester(program, fachSemester.value)
                } else {
                    initialDoc
                }

            val rows = parseScheduleRows(document, program, fachSemester.value)
            if (rows.isNotEmpty()) {
                logger.debug(
                    "      [{} / {}] {} – {} Einträge gefunden",
                    index + 1,
                    fachSemesterOptions.size,
                    fachSemester.value,
                    rows.size,
                )
            }
            persistRows(rows, semester, scrapingRunId, stats)
        }

        stats.studyProgramsProcessed += 1

        return stats
    }

    private fun persistRows(
        rows: List<ScrapedRow>,
        semester: Semester,
        scrapingRunId: Long,
        stats: ScrapeStats,
    ) {
        for (row in rows) {
            val dayOfWeek = parseDayOfWeek(row.day)
            val timeRange = parseTimeRange(row.time)

            if (dayOfWeek == null || timeRange == null) {
                logger.debug(
                    "      Überspringe Zeile wegen ungültiger Zeit/Tag: {} - {}",
                    row.day,
                    row.time
                )
                continue
            }

            val courseType = getOrCreateCourseType(row.courseType, scrapingRunId)
            val lecturer = getOrCreateLecturer(row.lecturer, scrapingRunId)
            val room = getOrCreateRoom(row.room, scrapingRunId)
            val course =
                getOrCreateCourse(row.courseTitle, semester, lecturer, courseType, scrapingRunId)
            attachCourseToStudyProgram(course, row.studyProgram, row.fachSemester)

            // Lade Course mit scheduleEntries, um LazyInitializationException zu vermeiden
            val courseWithEntries =
                courseRepository.findByIdWithScheduleEntries(course.id!!) ?: course

            val existingEntry =
                courseWithEntries.scheduleEntries.firstOrNull {
                    it.dayOfWeek == dayOfWeek &&
                        it.startTime == timeRange.first &&
                        it.endTime == timeRange.second &&
                        it.room.code.equals(room.code, ignoreCase = true)
                }

            val noteParts = mutableListOf<String>()
            if (row.category.isNotBlank()) noteParts += row.category
            if (row.group.isNotBlank()) noteParts += row.group
            if (row.fachSemester.isNotBlank()) noteParts += row.fachSemester
            if (row.infoId.isNotBlank()) noteParts += "Info ${row.infoId}"
            val notesText = noteParts.joinToString(" | ")

            if (existingEntry != null) {
                var changed = false
                if (existingEntry.weekPattern != row.weekPattern) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "ScheduleEntry",
                        existingEntry.id!!,
                        "weekPattern",
                        existingEntry.weekPattern,
                        row.weekPattern,
                    )
                    existingEntry.weekPattern = row.weekPattern
                    changed = true
                }

                if (notesText.isNotBlank() && existingEntry.notes != notesText) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "ScheduleEntry",
                        existingEntry.id!!,
                        "notes",
                        existingEntry.notes,
                        notesText,
                    )
                    existingEntry.notes = notesText
                    changed = true
                }

                if (changed) {
                    scheduleEntryRepository.save(existingEntry)
                    stats.updatedEntries += 1
                }
            } else {
                val newEntry =
                    ScheduleEntry(
                            course = course,
                            room = room,
                            dayOfWeek = dayOfWeek,
                            startTime = timeRange.first,
                            endTime = timeRange.second,
                        )
                        .apply {
                            weekPattern = row.weekPattern.ifBlank { null }
                            notes = notesText.ifBlank { null }
                        }

                val saved = scheduleEntryRepository.save(newEntry)
                courseWithEntries.scheduleEntries.add(saved)
                changeTrackingService.logEntityCreated(scrapingRunId, "ScheduleEntry", saved.id!!)
                stats.newEntries += 1
            }

            stats.totalEntries += 1
        }
    }

    private fun scrapeRoomPlans(
        session: TubafScraperSession,
        semester: Semester,
        scrapingRunId: Long,
        stats: ScrapeStats,
    ) {
        val options = session.fetchRoomPlanOptions()
        if (options.isEmpty()) {
            logger.info("Keine Raumpläne für {} gefunden", semester.name)
            progressTracker.log("INFO", "🏢 Keine Raumpläne gefunden")
            return
        }

        progressTracker.log("INFO", "🏢 ${options.size} Raumpläne gefunden")

        options.forEachIndexed { index, option ->
            ensureNotCancelled()
            try {
                val document = session.fetchRoomPlan(option)
                val parsed = parseRoomPlan(document, option)
                val room = getOrCreateRoom(parsed.code, scrapingRunId)
                if (updateRoomFromPlan(room, parsed, scrapingRunId)) {
                    stats.roomsUpdatedFromPlans += 1
                }

                val entryStats =
                    persistRoomPlanEntries(
                        room,
                        semester,
                        scrapingRunId,
                        parsed.entries
                    )
                stats.roomPlansProcessed += 1
                stats.roomPlanEntries += entryStats.total
                stats.roomPlanEntriesNew += entryStats.created
                stats.roomPlanEntriesUpdated += entryStats.updated
                stats.roomPlanEntriesDeactivated += entryStats.deactivated

                progressTracker.update(
                    task = "Raumpläne",
                    processed = index + 1,
                    total = options.size,
                    message =
                        "${parsed.code}: ${entryStats.total} Einträge (neu ${entryStats.created}, aktualisiert ${entryStats.updated}, deaktiviert ${entryStats.deactivated})"
                )
            } catch (ex: Exception) {
                logger.warn("Fehler beim Verarbeiten des Raumplans {}: {}", option.code, ex.message)
                progressTracker.log(
                    "WARN",
                    "⚠️ Raumplan ${option.code} konnte nicht geladen werden: ${ex.message}"
                )
            }
        }
    }

    private fun parseRoomPlan(document: Document, option: RoomPlanOption): ParsedRoomPlan {
        val headerSpan =
            document.selectFirst("span[style*=color:blue]")
                ?: document.select("span").firstOrNull { it.text().contains("Raum", ignoreCase = true) }
        val headerText = headerSpan?.text()?.trim().orEmpty()

        val headerRegex =
            Regex(
                "Raum\\s+([^\\s]+)\\s+-\\s+([\\d.]+)\\s+Plätze\\s+-\\s+(.*)",
                RegexOption.IGNORE_CASE
            )
        val headerMatch = headerRegex.find(headerText)
        val code = headerMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { option.code }
        val capacity =
            headerMatch?.groupValues?.getOrNull(2)?.replace(".", "")?.toIntOrNull()
                ?: Regex("(\\d[\\d.]*)").find(headerText)?.groupValues?.getOrNull(1)?.replace(".", "")?.toIntOrNull()
        val locationDescription = headerMatch?.groupValues?.getOrNull(3)?.trim().orEmpty().ifBlank {
            headerText.substringAfterLast('-').trim().ifBlank { null }
        }

        val planDate =
            document.select("span").mapNotNull { span ->
                val text = span.text()
                if (text.contains("Datum:", ignoreCase = true)) {
                    parsePlanDate(text)
                } else {
                    null
                }
            }.firstOrNull()

        val scheduleTable = document.selectFirst("table[rules=cols]")
        if (scheduleTable == null) {
            logger.debug("Keine Raumplan-Tabelle für {} gefunden", code)
            return ParsedRoomPlan(code, capacity, locationDescription, planDate, emptyList())
        }

        val headerRow = scheduleTable.selectFirst("tr:has(th)")
        val dayHeaders = headerRow?.select("th")?.map { it.text().trim() } ?: emptyList()
        val dayMapping = dayHeaders.map { parseDayOfWeek(it) }

        val entries = mutableListOf<RoomPlanEntryData>()
        val dataRows = scheduleTable.select("tr").drop(1)
        for (row in dataRows) {
            val cells = row.select("> td")
            if (cells.isEmpty()) {
                continue
            }

            cells.forEachIndexed { index, cell ->
                val dayOfWeek = dayMapping.getOrNull(index) ?: return@forEachIndexed
                val blockTables = cell.select("> table")
                if (blockTables.isEmpty()) {
                    return@forEachIndexed
                }

                for (block in blockTables) {
                    val blockRows = block.select("> tr")
                    if (blockRows.size < 4) {
                        continue
                    }

                    val timeRange = parseTimeRange(blockRows[0].text()) ?: continue
                    val weekPattern = blockRows.getOrNull(1)?.text()?.trim()?.ifBlank { null }
                    val courseType = blockRows.getOrNull(2)?.text()?.trim()?.ifBlank { null }
                    val titleCell = blockRows.getOrNull(3)?.selectFirst("td")
                    val courseTitle = titleCell?.text()?.trim()?.ifBlank { null } ?: continue
                    val infoId = titleCell?.let { extractInfoId(it) }?.ifBlank { null }
                    val lecturer = blockRows.getOrNull(4)?.text()?.trim()?.ifBlank { null }

                    entries +=
                        RoomPlanEntryData(
                            dayOfWeek = dayOfWeek,
                            startTime = timeRange.first,
                            endTime = timeRange.second,
                            courseTitle = courseTitle,
                            courseType = courseType,
                            lecturer = lecturer,
                            weekPattern = weekPattern,
                            infoId = infoId,
                        )
                }
            }
        }

        return ParsedRoomPlan(code, capacity, locationDescription, planDate, entries)
    }

    private fun updateRoomFromPlan(
        room: Room,
        plan: ParsedRoomPlan,
        scrapingRunId: Long,
    ): Boolean {
        var changed = false

        if (plan.capacity != null && room.capacity != plan.capacity) {
            changeTrackingService.logEntityUpdated(
                scrapingRunId,
                "Room",
                room.id!!,
                "capacity",
                room.capacity?.toString(),
                plan.capacity.toString()
            )
            room.capacity = plan.capacity
            changed = true
        }

        if (plan.locationDescription != null && room.locationDescription != plan.locationDescription) {
            changeTrackingService.logEntityUpdated(
                scrapingRunId,
                "Room",
                room.id!!,
                "locationDescription",
                room.locationDescription,
                plan.locationDescription
            )
            room.locationDescription = plan.locationDescription
            changed = true
        }

        if (plan.planDate != null && room.planUpdatedAt != plan.planDate) {
            changeTrackingService.logEntityUpdated(
                scrapingRunId,
                "Room",
                room.id!!,
                "planUpdatedAt",
                room.planUpdatedAt?.toString(),
                plan.planDate.toString()
            )
            room.planUpdatedAt = plan.planDate
            changed = true
        }

        if (changed) {
            roomRepository.save(room)
        }

        return changed
    }

    private fun persistRoomPlanEntries(
        room: Room,
        semester: Semester,
        scrapingRunId: Long,
        entries: List<RoomPlanEntryData>,
    ): RoomPlanEntryPersistStats {
        if (room.id == null) {
            return RoomPlanEntryPersistStats(entries.size, 0, 0, 0)
        }

        val existing = roomPlanSlotRepository.findByRoomIdAndSemesterId(room.id!!, semester.id!!)
        val existingMap =
            existing.associateBy { slot ->
                RoomPlanSlotKey(
                    slot.dayOfWeek,
                    slot.startTime,
                    slot.endTime,
                    slot.courseTitle.trim().lowercase(Locale.GERMAN)
                )
            }
        val processedKeys = mutableSetOf<RoomPlanSlotKey>()

        var created = 0
        var updated = 0

        for (entry in entries) {
            val key =
                RoomPlanSlotKey(
                    entry.dayOfWeek,
                    entry.startTime,
                    entry.endTime,
                    entry.courseTitle.trim().lowercase(Locale.GERMAN)
                )
            val existingSlot = existingMap[key]
            if (existingSlot != null) {
                processedKeys += key
                var changed = false

                if (!existingSlot.active) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "RoomPlanSlot",
                        existingSlot.id!!,
                        "active",
                        "false",
                        "true"
                    )
                    existingSlot.active = true
                    changed = true
                }

                if (entry.courseType != existingSlot.courseType) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "RoomPlanSlot",
                        existingSlot.id!!,
                        "courseType",
                        existingSlot.courseType,
                        entry.courseType
                    )
                    existingSlot.courseType = entry.courseType
                    changed = true
                }

                if (entry.weekPattern != existingSlot.weekPattern) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "RoomPlanSlot",
                        existingSlot.id!!,
                        "weekPattern",
                        existingSlot.weekPattern,
                        entry.weekPattern
                    )
                    existingSlot.weekPattern = entry.weekPattern
                    changed = true
                }

                if (entry.lecturer != existingSlot.lecturers) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "RoomPlanSlot",
                        existingSlot.id!!,
                        "lecturers",
                        existingSlot.lecturers,
                        entry.lecturer
                    )
                    existingSlot.lecturers = entry.lecturer
                    changed = true
                }

                if (entry.infoId != existingSlot.infoId) {
                    changeTrackingService.logEntityUpdated(
                        scrapingRunId,
                        "RoomPlanSlot",
                        existingSlot.id!!,
                        "infoId",
                        existingSlot.infoId,
                        entry.infoId
                    )
                    existingSlot.infoId = entry.infoId
                    changed = true
                }

                if (changed) {
                    roomPlanSlotRepository.save(existingSlot)
                    updated += 1
                }
            } else {
                val slot =
                    RoomPlanSlot(
                        room = room,
                        semester = semester,
                        dayOfWeek = entry.dayOfWeek,
                        startTime = entry.startTime,
                        endTime = entry.endTime,
                        courseTitle = entry.courseTitle,
                        courseType = entry.courseType,
                        lecturers = entry.lecturer,
                        weekPattern = entry.weekPattern,
                        infoId = entry.infoId,
                    )
                val saved = roomPlanSlotRepository.save(slot)
                changeTrackingService.logEntityCreated(scrapingRunId, "RoomPlanSlot", saved.id!!)
                created += 1
                processedKeys += key
            }
        }

        var deactivated = 0
        existing.forEach { slot ->
            val key =
                RoomPlanSlotKey(
                    slot.dayOfWeek,
                    slot.startTime,
                    slot.endTime,
                    slot.courseTitle.trim().lowercase(Locale.GERMAN)
                )
            if (key !in processedKeys && slot.active) {
                slot.active = false
                roomPlanSlotRepository.save(slot)
                changeTrackingService.logEntityUpdated(
                    scrapingRunId,
                    "RoomPlanSlot",
                    slot.id!!,
                    "active",
                    "true",
                    "false"
                )
                deactivated += 1
            }
        }

        return RoomPlanEntryPersistStats(entries.size, created, updated, deactivated)
    }

    private fun parsePlanDate(value: String): LocalDate? {
        val match = Regex("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})").find(value)
        val dateText = match?.groupValues?.getOrNull(1) ?: return null
        return try {
            LocalDate.parse(dateText, roomPlanDateFormatter)
        } catch (ex: DateTimeParseException) {
            null
        }
    }

    private fun attachCourseToStudyProgram(
        course: Course,
        option: StudyProgramOption,
        fachSemester: String
    ) {
        val studyProgram =
            studyProgramRepository.findByCode(option.code)
                ?: studyProgramRepository
                    .findByNameContainingIgnoreCase(option.displayName)
                    .firstOrNull()
                ?: return

        val alreadyLinked = course.courseStudyPrograms.any { it.studyProgram.id == studyProgram.id }
        if (!alreadyLinked) {
            course.addStudyProgram(studyProgram, parseFachSemesterNumber(fachSemester))
            courseRepository.save(course)
        }
    }

    private fun parseFachSemesterNumber(value: String): Int? {
        return value.substringBefore('.').toIntOrNull()
    }

    private fun parseScheduleRows(
        document: Document,
        program: StudyProgramOption,
        fachSemester: String,
    ): List<ScrapedRow> {
        val scheduleTable =
            document.select("table").firstOrNull { table ->
                val headers = table.select("th").map { it.text().lowercase(Locale.GERMAN) }
                headers.contains("titel") && headers.contains("zeit")
            }

        if (scheduleTable == null) {
            val msg =
                "🔍 Keine Tabelle mit 'Titel' und 'Zeit' Headers für ${program.code} ($fachSemester)"
            logger.info(msg)
            progressTracker.log("WARN", msg)
            progressTracker.log("INFO", "🔍 Verfügbare Tabellen: ${document.select("table").size}")
            document.select("table").forEachIndexed { index, table ->
                val headers = table.select("th").map { it.text() }
                if (headers.isNotEmpty()) {
                    progressTracker.log("INFO", "  Tabelle $index: Headers = $headers")
                }
            }
            return emptyList()
        }

        logger.info(
            "📋 Gefundene Schedule-Tabelle für {} ({}), starte Parsing...",
            program.code,
            fachSemester
        )
        progressTracker.log("INFO", "📋 Parse Tabelle für ${program.code} ($fachSemester)")

        val rows = mutableListOf<ScrapedRow>()
        var currentCategory = ""
        var currentGroup = ""
        var skippedRows = 0

        for (row in scheduleTable.select("tr")) {
            val cells = row.select("td")
            if (cells.isEmpty()) {
                continue
            }

            if (cells.size == 1) {
                val text = cells.first().text().trim()
                val colspan = cells.first().attr("colspan")
                if (colspan == "9") {
                    currentCategory = text
                } else if (colspan == "8") {
                    currentGroup = text
                }
                continue
            }

            if (cells.size < 8) {
                continue
            }

            val courseType = cells[0].text().trim()
            val courseTitle = cells[1].text().trim()
            val lecturer = cells[2].text().trim()
            val day = cells[3].text().trim()
            val time = cells[4].text().trim()
            val room = cells[5].text().trim()
            val weekPattern = cells[6].text().trim()
            val infoId = extractInfoId(cells[7])

            if (courseTitle.isBlank()) {
                logger.trace(
                    "⏭️  Überspringe Zeile ohne Titel: {} Zellen, Type={}, Dozent={}, Tag={}",
                    cells.size,
                    courseType,
                    lecturer,
                    day
                )
                skippedRows++
                continue
            }

            rows +=
                ScrapedRow(
                    studyProgram = program,
                    fachSemester = fachSemester,
                    category = currentCategory,
                    group = currentGroup,
                    courseType = courseType,
                    courseTitle = courseTitle,
                    lecturer = lecturer,
                    day = day,
                    time = time,
                    room = room,
                    weekPattern = weekPattern,
                    infoId = infoId,
                )
        }

        if (rows.isEmpty() && skippedRows > 0) {
            progressTracker.log(
                "WARN",
                "⚠️  ${program.code} ($fachSemester): $skippedRows Zeilen ohne Titel übersprungen"
            )
        }
        if (rows.isNotEmpty()) {
            progressTracker.log(
                "INFO",
                "✅ ${program.code} ($fachSemester): ${rows.size} Kurse gefunden"
            )
        }

        return rows
    }

    private fun extractInfoId(cell: Element): String {
        val link = cell.selectFirst("a[href*=satz=]")
        return link?.attr("href")?.substringAfter("satz=")?.substringBefore('&')?.trim() ?: ""
    }

    private fun parseFachSemesterOptions(document: Document): List<FachSemesterOption> {
        val select = document.selectFirst("select[name=semest]") ?: return emptyList()
        val options = select.select("option")
        val result = mutableListOf<FachSemesterOption>()
        for (option in options) {
            val value = option.text().trim()
            if (value.equals("Auswahl...", ignoreCase = true)) {
                continue
            }
            val isSelected = option.hasAttr("selected")
            result += FachSemesterOption(value, !isSelected)
        }

        return result
    }

    private fun getOrCreateCourse(
        title: String,
        semester: Semester,
        lecturer: Lecturer,
        courseType: CourseType,
        scrapingRunId: Long,
    ): Course {
        val normalizedTitle = title.trim()
        val existing =
            courseRepository.findBySemesterAndActive(semester).firstOrNull {
                it.name.equals(normalizedTitle, ignoreCase = true)
            }

        if (existing != null) {
            var updated = false
            if (existing.lecturer.id != lecturer.id) {
                existing.lecturer = lecturer
                updated = true
            }
            if (existing.courseType.id != courseType.id) {
                existing.courseType = courseType
                updated = true
            }
            if (updated) {
                courseRepository.save(existing)
            }
            // Lade das Course-Objekt neu mit scheduleEntries, um LazyInitializationException zu
            // vermeiden
            return courseRepository.findByIdWithScheduleEntries(existing.id!!) ?: existing
        }

        val course =
            Course(
                name = normalizedTitle,
                semester = semester,
                lecturer = lecturer,
                courseType = courseType,
            )
        val saved = courseRepository.save(course)
        // scheduleEntries ist bereits initialisiert (leere Collection bei neuem Course)
        changeTrackingService.logEntityCreated(scrapingRunId, "Course", saved.id!!)
        return saved
    }

    private fun getOrCreateCourseType(typeText: String, scrapingRunId: Long): CourseType {
        val normalized = typeText.trim().uppercase(Locale.GERMAN)
        val code =
            when {
                normalized.startsWith("V") -> "V"
                normalized.startsWith("Ü") ||
                    normalized.startsWith("UE") ||
                    normalized.startsWith("U") -> "Ü"
                normalized.startsWith("S") -> "S"
                normalized.startsWith("P") || normalized.startsWith("PR") -> "P"
                normalized.startsWith("B") -> "B"
                else -> normalized.take(1).ifBlank { "V" }
            }

        courseTypeRepository.findByCode(code)?.let {
            return it
        }

        val newType = CourseType(code = code, name = typeText.ifBlank { code })
        val saved = courseTypeRepository.save(newType)
        changeTrackingService.logEntityCreated(scrapingRunId, "CourseType", saved.id!!)
        return saved
    }

    private fun getOrCreateLecturer(rawText: String, scrapingRunId: Long): Lecturer {
        val text = rawText.trim().ifBlank { "N.N." }
        val normalized = text.take(200)
        if (normalized.length < text.length) {
            logger.warn(
                "Kürze Dozierendenamen von {} auf {} Zeichen",
                text.length,
                normalized.length
            )
        }
        lecturerRepository.findByNameContainingIgnoreCaseAndActive(normalized).firstOrNull()?.let {
            return it
        }

        val lecturer = Lecturer(name = normalized)
        val saved = lecturerRepository.save(lecturer)
        changeTrackingService.logEntityCreated(scrapingRunId, "Lecturer", saved.id!!)
        return saved
    }

    private fun getOrCreateRoom(rawText: String, scrapingRunId: Long): Room {
        val value = rawText.trim().ifBlank { "TBD" }
        roomRepository.findByCode(value)?.let {
            return it
        }

        val (building, number) = parseRoomCode(value)
        val room = Room(code = value, building = building, roomNumber = number)
        val saved = roomRepository.save(room)
        changeTrackingService.logEntityCreated(scrapingRunId, "Room", saved.id!!)
        return saved
    }

    private fun parseRoomCode(value: String): Pair<String, String> {
        val delimiters = listOf("/", "-", " ", "_")
        for (delimiter in delimiters) {
            if (value.contains(delimiter)) {
                val parts = value.split(delimiter).filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    return parts[0] to parts[1]
                }
            }
        }

        return value to value
    }

    private fun parseDayOfWeek(value: String): DayOfWeek? {
        val normalized = value.trim().lowercase(Locale.GERMAN)
        return when {
            normalized.startsWith("mo") -> DayOfWeek.MONDAY
            normalized.startsWith("di") -> DayOfWeek.TUESDAY
            normalized.startsWith("mi") -> DayOfWeek.WEDNESDAY
            normalized.startsWith("do") -> DayOfWeek.THURSDAY
            normalized.startsWith("fr") -> DayOfWeek.FRIDAY
            normalized.startsWith("sa") -> DayOfWeek.SATURDAY
            normalized.startsWith("so") -> DayOfWeek.SUNDAY
            else -> null
        }
    }

    private fun parseTimeRange(value: String): Pair<LocalTime, LocalTime>? {
        val sanitized = value.replace(" ", "")
        val parts = sanitized.split('-')
        if (parts.size != 2) {
            return null
        }

        return try {
            val start = LocalTime.parse(parts[0], timeFormatter)
            val end = LocalTime.parse(parts[1], timeFormatter)
            start to end
        } catch (ex: Exception) {
            null
        }
    }

    private fun matchesSemesterOption(option: SemesterOption, semester: Semester): Boolean {
        val normalizedOption = normalize(option.displayName)
        val normalizedSemester = normalize(semester.name)
        if (normalizedOption == normalizedSemester) {
            return true
        }

        val possibleShort = buildPossibleShortNames(option.displayName)
        val semesterShort = semester.shortName.uppercase(Locale.GERMAN)
        if (possibleShort.contains(semesterShort.uppercase(Locale.GERMAN))) {
            return true
        }

        return false
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.GERMAN)
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("/", "")
            .replace("_", "")
    }

    private fun buildPossibleShortNames(displayName: String): Set<String> {
        val normalized = displayName.lowercase(Locale.GERMAN)
        val prefix =
            when {
                normalized.contains("winter") -> "WS"
                normalized.contains("sommer") -> "SS"
                else -> displayName.take(2).uppercase(Locale.GERMAN)
            }

        val numbers = Regex("\\d{4}").findAll(displayName).map { it.value }.toList()
        val variants = mutableSetOf<String>()

        if (numbers.isEmpty()) {
            variants += prefix
        } else {
            val twoDigitParts = numbers.map { it.takeLast(2) }
            val fourDigitParts = numbers.map { it.takeLast(4) }

            variants += prefix + numbers.first()
            variants += prefix + numbers.last()
            variants += prefix + numbers.joinToString("")
            variants += prefix + numbers.joinToString("/")
            variants += prefix + twoDigitParts.joinToString("")
            variants += prefix + fourDigitParts.joinToString("")
            variants += prefix + twoDigitParts.first()
            variants += prefix + twoDigitParts.last()
        }

        variants += displayName.uppercase(Locale.GERMAN)
        variants += displayName.replace(" ", "").uppercase(Locale.GERMAN)
        variants += buildShortName(displayName).uppercase(Locale.GERMAN)

        return variants.map { it.replace("/", "").replace("-", "") }.toSet()
    }

    private fun getOrCreateSemester(option: SemesterOption): Semester {
        return semesterService.getAllSemesters().firstOrNull { matchesSemesterOption(option, it) }
            ?: createSemesterFromOption(option)
    }

    private fun createSemesterFromOption(option: SemesterOption): Semester {
        val (start, end) = mapSemesterDates(option.displayName)
        val shortName = buildShortName(option.displayName)
        return semesterService.createSemester(
            option.displayName,
            shortName,
            start,
            end,
            active = false
        )
    }

    private fun buildShortName(displayName: String): String {
        val trimmed = displayName.trim().lowercase(Locale.GERMAN)
        return when {
            trimmed.startsWith("winter") -> "WS" + trimmed.filter { it.isDigit() }.takeLast(2)
            trimmed.startsWith("sommer") -> "SS" + trimmed.filter { it.isDigit() }.takeLast(2)
            else -> displayName.take(6).uppercase(Locale.GERMAN)
        }
    }

    private fun mapSemesterDates(displayName: String): Pair<LocalDate, LocalDate> {
        val normalized = displayName.lowercase(Locale.GERMAN)
        val currentYear = LocalDate.now().year
        return when {
            normalized.contains("winter") -> {
                val year = extractYear(displayName, currentYear)
                LocalDate.of(year, 10, 1) to LocalDate.of(year + 1, 3, 31)
            }
            normalized.contains("sommer") -> {
                val year = extractYear(displayName, currentYear)
                LocalDate.of(year, 4, 1) to LocalDate.of(year, 9, 30)
            }
            else -> LocalDate.of(currentYear, 4, 1) to LocalDate.of(currentYear, 9, 30)
        }
    }

    private fun extractYear(value: String, fallback: Int): Int {
        val match = Regex("20\\d{2}").find(value)
        return match?.value?.toInt() ?: fallback
    }

    private fun buildSemesterLookup(options: List<SemesterOption>): Map<String, SemesterOption> {
        val map = mutableMapOf<String, SemesterOption>()
        options.forEach { option ->
            val keys = mutableSetOf<String>()
            keys += normalize(option.displayName)
            keys += normalize(option.displayName.replace("Semester", "", ignoreCase = true))
            keys += normalize(buildShortName(option.displayName))
            buildPossibleShortNames(option.displayName).forEach { variant ->
                keys += normalize(variant)
            }
            keys.forEach { key -> map.putIfAbsent(key, option) }
        }
        return map
    }

    private fun submitJob(initialMessage: String, task: () -> Unit): Boolean {
        synchronized(jobLock) {
            val current = activeJob.get()
            if (current != null && !current.isDone && !current.isCancelled) {
                progressTracker.log("WARN", "Es läuft bereits ein Scraping-Prozess")
                return false
            }

            cancellationMessage = null
            progressTracker.reset(initialMessage)

            val future =
                executor.submit {
                    try {
                        task()
                    } catch (ex: InterruptedException) {
                        Thread.currentThread().interrupt()
                        val message = cancellationMessage
                        cancellationMessage = null
                        if (message != null) {
                            progressTracker.reset(message)
                        } else {
                            val snapshot = progressTracker.snapshot()
                            if (snapshot.status == ScrapingStatus.RUNNING) {
                                progressTracker.fail("Scraping abgebrochen")
                            }
                        }
                    } catch (ex: Exception) {
                        logger.error("Scraping-Thread fehlgeschlagen", ex)
                        progressTracker.fail("Scraping fehlgeschlagen: ${ex.message}")
                    } finally {
                        activeJob.set(null)
                    }
                }

            activeJob.set(future)
            return true
        }
    }

    private fun ensureNotCancelled() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Scraping job wurde abgebrochen")
        }
    }

    private fun detectProxy(): Proxy {
        val propertyEntry =
            sequenceOf(
                    System.getProperty("https.proxyHost") to System.getProperty("https.proxyPort"),
                    System.getProperty("http.proxyHost") to System.getProperty("http.proxyPort")
                )
                .firstOrNull { !it.first.isNullOrBlank() }

        val hostAndPort =
            when {
                propertyEntry != null -> {
                    val host = propertyEntry.first!!.trim()
                    val port = propertyEntry.second?.toIntOrNull() ?: DEFAULT_PROXY_PORT
                    host to port
                }
                else -> {
                    sequenceOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")
                        .mapNotNull { System.getenv(it) }
                        .mapNotNull { parseProxyUrl(it) }
                        .firstOrNull()
                }
            }

        if (hostAndPort == null) {
            logger.info("Kein Proxy konfiguriert – direkte Verbindung wird genutzt")
            return Proxy.NO_PROXY
        }

        logger.info("Nutze Proxy {}:{} für HTTP-Anfragen", hostAndPort.first, hostAndPort.second)
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(hostAndPort.first, hostAndPort.second))
    }

    private fun parseProxyUrl(value: String): Pair<String, Int>? {
        if (value.isBlank()) return null
        return try {
            val normalized = if (value.contains("://")) value else "http://$value"
            val uri = URI(normalized)
            val host = uri.host ?: return null
            val port = if (uri.port != -1) uri.port else DEFAULT_PROXY_PORT
            host to port
        } catch (ex: Exception) {
            logger.warn("Konnte Proxy-URL nicht parsen: {}", value, ex)
            null
        }
    }

    protected inner class TubafScraperSession {
        private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private val httpClient =
            OkHttpClient.Builder()
                .proxy(httpProxy)
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            val results = Dns.SYSTEM.lookup(hostname)
                            val ipv4Addresses = results.filterIsInstance<Inet4Address>()
                            return if (ipv4Addresses.isNotEmpty()) ipv4Addresses else results
                        }
                    }
                )
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(JavaNetCookieJar(cookieManager))
                .build()

        private val trimmedBase = baseUrl.trimEnd('/')

        fun fetchSemesterOptions(): List<SemesterOption> {
            val document = request("GET", "index.html")
            val select = document.selectFirst("select[name=sem_wahl]") ?: return emptyList()
            return select.select("option").map { SemesterOption(it.text().trim()) }
        }

        fun selectSemester(option: SemesterOption) {
            logger.info("🔄 Wechsle zu Semester: {}", option.displayName)
            progressTracker.log("INFO", "🔄 Wechsle zu: ${option.displayName}")

            // Log Cookies vor dem Request
            val cookiesBefore = cookieManager.cookieStore.cookies.size
            logger.info("🍪 Cookies vor selectSemester: {}", cookiesBefore)

            val formData =
                mapOf(
                    "sem_wahl" to option.displayName,
                    "wechsel" to "4",
                    "senden" to "Auswählen",
                )
            val response = request("POST", "index.html", formData, referer = url("index.html"))

            // Log Cookies nach dem Request
            val cookiesAfter = cookieManager.cookieStore.cookies.size
            logger.info("🍪 Cookies nach selectSemester: {}", cookiesAfter)
            progressTracker.log("INFO", "🍪 Cookies: $cookiesAfter")

            // Prüfe, welches Semester jetzt aktiv ist
            val activeOption = response.selectFirst("select[name=sem_wahl] option[selected]")
            val activeSemester = activeOption?.text()?.trim() ?: "unbekannt"
            logger.info("✓ Aktives Semester nach Wechsel: {}", activeSemester)
            progressTracker.log("INFO", "✓ Aktiv: $activeSemester")
        }

        fun fetchStudyPrograms(): List<StudyProgramOption> {
            // Log Cookies vor dem Request
            val cookiesBefore = cookieManager.cookieStore.cookies.size
            logger.info("🍪 Cookies vor fetchStudyPrograms: {}", cookiesBefore)
            progressTracker.log("INFO", "🍪 Cookies vor verz.html: $cookiesBefore")

            val document = request("GET", "verz.html", referer = url("index.html"))

            // Prüfe das aktive Semester auf verz.html
            val activeOption = document.selectFirst("select[name=sem_wahl] option[selected]")
            val activeSemester = activeOption?.text()?.trim() ?: "unbekannt"
            logger.info("📅 Semester auf verz.html: {}", activeSemester)
            progressTracker.log("INFO", "📅 Semester: $activeSemester")

            val tableCount = document.select("table").size
            logger.info("🔎 fetchStudyPrograms: Lade verz.html, Tabellen gefunden: {}", tableCount)
            progressTracker.log("INFO", "🔎 verz.html geladen: $tableCount Tabellen gefunden")

            // Die Studiengänge-Tabelle ist die, die Links zu stgvrz.html enthält
            val table =
                document.select("table").firstOrNull {
                    it.select("a[href^=stgvrz.html]").isNotEmpty()
                }

            if (table == null) {
                logger.warn("⚠️  Keine Tabelle mit stgvrz.html Links gefunden!")
                progressTracker.log("WARN", "⚠️  Keine passende Tabelle gefunden!")
                logger.info("📄 Verfügbare Tabellen-Texte:")
                progressTracker.log("INFO", "📄 Vorhandene Tabellen:")
                document.select("table").take(5).forEachIndexed { index, t ->
                    val text = t.text().take(100)
                    logger.info("  Tabelle {}: {}", index, text)
                    progressTracker.log("INFO", "  Tab $index: $text")
                }
                return emptyList()
            }

            logger.info("✅ Tabelle gefunden! Text: {}", table.text().take(200))
            progressTracker.log("INFO", "✅ Tabelle gefunden: ${table.text().take(100)}")

            val programs = mutableListOf<StudyProgramOption>()
            var currentFaculty = ""

            val rowCount = table.select("tr").size
            logger.info("📊 Zeilen in Tabelle: {}", rowCount)
            progressTracker.log("INFO", "📊 $rowCount Zeilen in Tabelle")

            for (row in table.select("tr")) {
                if (row.select("b u").isNotEmpty()) {
                    currentFaculty = row.text().trim()
                    logger.debug("📁 Fakultät: {}", currentFaculty)
                    continue
                }

                val link = row.selectFirst("a[href^=stgvrz.html]")
                if (link == null) {
                    logger.debug(
                        "⏭️  Überspringe Zeile ohne stgvrz.html Link: {}",
                        row.text().take(50)
                    )
                    continue
                }

                val href = link.attr("href")
                val codeCell = row.selectFirst("td")
                val code = codeCell?.text()?.trim().orEmpty()
                val displayName = link.text().trim()

                val queryParams = parseQueryParameters(href)
                val stdg = queryParams["stdg"] ?: code
                val stdgName = queryParams["stdg1"] ?: displayName

                programs +=
                    StudyProgramOption(
                        code = stdg,
                        displayName = stdgName,
                        faculty = currentFaculty,
                        href = href,
                    )
                logger.debug("➕ Studiengang: {} ({})", displayName, code)
            }

            logger.info("✨ Insgesamt {} Studiengänge gefunden", programs.size)
            progressTracker.log("INFO", "✨ ${programs.size} Studiengänge gefunden")

            return programs
        }

        fun openProgram(program: StudyProgramOption): Document {
            return request("GET", program.href, referer = url("verz.html"))
        }

        fun openProgramSemester(program: StudyProgramOption, fachSemester: String): Document {
            val formData =
                mapOf(
                    "stdg" to program.code,
                    "stdg1" to program.displayName,
                    "semest" to fachSemester,
                    "popup3" to "",
                )
            return request("POST", "stgvrz.html", formData, referer = url(program.href))
        }

        fun fetchRoomPlanOptions(): List<RoomPlanOption> {
            val document = request("GET", "plaene.html", referer = url("index.html"))
            val select = document.selectFirst("select[name=raum]") ?: return emptyList()

            return select.select("option")
                .mapNotNull { option ->
                    val value = option.attr("value").ifBlank { option.text() }.trim()
                    if (value.isBlank()) {
                        null
                    } else {
                        RoomPlanOption(code = value, displayName = option.text().trim().ifBlank { value })
                    }
                }
        }

        fun fetchRoomPlan(option: RoomPlanOption): Document {
            val encoded = URLEncoder.encode(option.code, StandardCharsets.UTF_8)
            val path = "druck_html.html?art=raumplan&raum=$encoded"
            return request("GET", path, referer = url("plaene.html"))
        }

        private fun request(
            method: String,
            path: String,
            formData: Map<String, String>? = null,
            referer: String? = null,
        ): Document {
            val targetUrl = url(path)
            val builder =
                Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", userAgent)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.6")
                    .header("Connection", "keep-alive")

            if (referer != null) {
                builder.header("Referer", referer)
            }

            if (method.equals("POST", ignoreCase = true) && formData != null) {
                val formBuilder = FormBody.Builder(StandardCharsets.UTF_8)
                formData.forEach { (key, value) -> formBuilder.add(key, value) }
                builder.post(formBuilder.build())
            } else {
                builder.get()
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                ensureSuccess(response)
                val body =
                    response.body?.string()
                        ?: throw IllegalStateException("Leere Antwort vom Server")
                return Jsoup.parse(body, targetUrl)
            }
        }

        private fun ensureSuccess(response: Response) {
            if (!response.isSuccessful) {
                val bodySnippet = response.body?.string()?.take(200)
                throw IllegalStateException(
                    "HTTP ${response.code} für ${response.request.url} – ${bodySnippet ?: ""}"
                )
            }
        }

        private fun url(path: String): String {
            if (path.startsWith("http", ignoreCase = true)) {
                return path
            }
            val sanitizedPath = path.trimStart('/')
            return "$trimmedBase/$sanitizedPath"
        }
    }

    private fun parseQueryParameters(href: String): Map<String, String> {
        val query = href.substringAfter('?', "")
        if (query.isBlank()) return emptyMap()

        return query
            .split('&')
            .mapNotNull { pair ->
                if (!pair.contains('=')) return@mapNotNull null
                val (key, value) = pair.split('=', limit = 2)
                key to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
            .toMap()
    }

    protected data class SemesterOption(val displayName: String)

    private data class FachSemesterOption(val value: String, val isPostRequired: Boolean)

    protected data class StudyProgramOption(
        val code: String,
        val displayName: String,
        val faculty: String,
        val href: String,
    ) {
        val defaultFachSemester: String = "1.Semester"
    }

    protected data class RoomPlanOption(
        val code: String,
        val displayName: String,
    )

    private data class ScrapedRow(
        val studyProgram: StudyProgramOption,
        val fachSemester: String,
        val category: String,
        val group: String,
        val courseType: String,
        val courseTitle: String,
        val lecturer: String,
        val day: String,
        val time: String,
        val room: String,
        val weekPattern: String,
        val infoId: String,
    )

    private data class ParsedRoomPlan(
        val code: String,
        val capacity: Int?,
        val locationDescription: String?,
        val planDate: LocalDate?,
        val entries: List<RoomPlanEntryData>,
    )

    private data class RoomPlanEntryData(
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime,
        val courseTitle: String,
        val courseType: String?,
        val lecturer: String?,
        val weekPattern: String?,
        val infoId: String?,
    )

    private data class RoomPlanEntryPersistStats(
        val total: Int,
        val created: Int,
        val updated: Int,
        val deactivated: Int,
    )

    private data class RoomPlanSlotKey(
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime,
        val courseTitleKey: String,
    )

    private data class ScrapeStats(
        var totalEntries: Int = 0,
        var newEntries: Int = 0,
        var updatedEntries: Int = 0,
        var studyProgramsProcessed: Int = 0,
        var roomPlansProcessed: Int = 0,
        var roomPlanEntries: Int = 0,
        var roomPlanEntriesNew: Int = 0,
        var roomPlanEntriesUpdated: Int = 0,
        var roomPlanEntriesDeactivated: Int = 0,
        var roomsUpdatedFromPlans: Int = 0,
    ) {
        fun toResult(): ScrapingResult {
            return ScrapingResult(
                totalEntries = totalEntries + roomPlanEntries,
                newEntries = newEntries + roomPlanEntriesNew,
                updatedEntries =
                    updatedEntries + roomPlanEntriesUpdated + roomPlanEntriesDeactivated,
                studyProgramsProcessed = studyProgramsProcessed,
                roomPlansProcessed = roomPlansProcessed,
                roomPlanEntries = roomPlanEntries,
                roomPlanEntriesNew = roomPlanEntriesNew,
                roomPlanEntriesUpdated = roomPlanEntriesUpdated,
                roomPlanEntriesDeactivated = roomPlanEntriesDeactivated,
                roomsUpdatedFromPlans = roomsUpdatedFromPlans,
            )
        }
    }
}

data class ScrapingResult(
    val totalEntries: Int,
    val newEntries: Int,
    val updatedEntries: Int,
    val studyProgramsProcessed: Int,
    val roomPlansProcessed: Int,
    val roomPlanEntries: Int,
    val roomPlanEntriesNew: Int,
    val roomPlanEntriesUpdated: Int,
    val roomPlanEntriesDeactivated: Int,
    val roomsUpdatedFromPlans: Int,
)

data class RemoteSemesterDescriptor(
    val displayName: String,
    val shortName: String,
)

data class ScrapingProgressSnapshot(
    val status: ScrapingStatus = ScrapingStatus.IDLE,
    val currentTask: String = "Bereit",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val progress: Int = 0,
    val message: String? = null,
    val logs: List<ScrapingLogEntry> = emptyList()
)

data class ScrapingLogEntry(
    val level: String,
    val message: String,
    val timestamp: Instant = Instant.now()
)

enum class ScrapingStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED
}

private class ScrapingProgressTracker {
    private val lock = Any()
    private var state = ScrapingProgressSnapshot()

    fun snapshot(): ScrapingProgressSnapshot =
        synchronized(lock) { state.copy(logs = state.logs.takeLast(MAX_LOGS)) }

    fun start(totalCount: Int, task: String, message: String) {
        synchronized(lock) {
            val sanitizedTotal = if (totalCount < 0) 0 else totalCount
            state =
                ScrapingProgressSnapshot(
                    status = ScrapingStatus.RUNNING,
                    currentTask = task,
                    processedCount = 0,
                    totalCount = sanitizedTotal,
                    progress = computeProgress(0, sanitizedTotal),
                    message = message,
                    logs = appendLog(state.logs, "INFO", message)
                )
        }
    }

    fun update(task: String?, processed: Int, total: Int?, message: String?) {
        synchronized(lock) {
            val newTotal = total ?: state.totalCount
            val newProgress = computeProgress(processed, newTotal)
            val updatedLogs =
                if (!message.isNullOrBlank()) appendLog(state.logs, "INFO", message) else state.logs
            state =
                state.copy(
                    currentTask = task ?: state.currentTask,
                    processedCount = processed,
                    totalCount = newTotal,
                    progress = newProgress,
                    message = message ?: state.message,
                    logs = updatedLogs
                )
        }
    }

    fun log(level: String, message: String) {
        synchronized(lock) {
            state = state.copy(message = message, logs = appendLog(state.logs, level, message))
        }
    }

    fun finish(message: String) {
        synchronized(lock) {
            state =
                state.copy(
                    status = ScrapingStatus.COMPLETED,
                    currentTask = "Bereit",
                    processedCount = state.totalCount,
                    progress = 100,
                    message = message,
                    logs = appendLog(state.logs, "INFO", message)
                )
        }
    }

    fun fail(message: String) {
        synchronized(lock) {
            state =
                state.copy(
                    status = ScrapingStatus.FAILED,
                    message = message,
                    logs = appendLog(state.logs, "ERROR", message)
                )
        }
    }

    fun pause(message: String) {
        synchronized(lock) {
            state =
                state.copy(
                    status = ScrapingStatus.PAUSED,
                    message = message,
                    logs = appendLog(state.logs, "WARN", message)
                )
        }
    }

    fun reset(message: String? = null) {
        synchronized(lock) {
            val logs =
                if (!message.isNullOrBlank()) appendLog(state.logs, "INFO", message) else state.logs
            state =
                ScrapingProgressSnapshot(
                    status = ScrapingStatus.IDLE,
                    currentTask = "Bereit",
                    processedCount = 0,
                    totalCount = 0,
                    progress = 0,
                    message = message,
                    logs = logs.takeLast(MAX_LOGS)
                )
        }
    }

    companion object {
        private const val MAX_LOGS = 100

        private fun computeProgress(processed: Int, total: Int): Int {
            if (total <= 0) return 0
            val clamped = processed.coerceIn(0, total)
            return ((clamped.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
        }

        private fun appendLog(
            current: List<ScrapingLogEntry>,
            level: String,
            message: String
        ): List<ScrapingLogEntry> {
            val entry = ScrapingLogEntry(level, message)
            return (current + entry).takeLast(MAX_LOGS)
        }
    }
}
