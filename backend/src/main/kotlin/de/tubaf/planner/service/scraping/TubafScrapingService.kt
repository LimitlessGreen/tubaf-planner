package de.tubaf.planner.service.scraping

import de.tubaf.planner.config.ScrapingConfiguration
import de.tubaf.planner.model.Course
import de.tubaf.planner.model.CourseType
import de.tubaf.planner.model.Lecturer
import de.tubaf.planner.model.Room
import de.tubaf.planner.model.ScheduleEntry
import de.tubaf.planner.model.Semester
import de.tubaf.planner.repository.CourseRepository
import de.tubaf.planner.repository.CourseTypeRepository
import de.tubaf.planner.repository.LecturerRepository
import de.tubaf.planner.repository.RoomRepository
import de.tubaf.planner.repository.ScheduleEntryRepository
import de.tubaf.planner.repository.StudyProgramRepository
import de.tubaf.planner.service.ChangeTrackingService
import de.tubaf.planner.service.SemesterService
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PreDestroy
import jakarta.persistence.EntityManager
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Service
open class TubafScrapingService(
    private val changeTrackingService: ChangeTrackingService,
    private val semesterService: SemesterService,
    private val courseRepository: CourseRepository,
    private val courseTypeRepository: CourseTypeRepository,
    private val lecturerRepository: LecturerRepository,
    private val roomRepository: RoomRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val studyProgramRepository: StudyProgramRepository,
    private val scrapingConfiguration: ScrapingConfiguration,
    private val transactionManager: PlatformTransactionManager,
    private val meterRegistry: MeterRegistry,
    private val entityManager: EntityManager,
    @Value("\${tubaf.scraper.base-url:https://evlvz.hrz.tu-freiberg.de/~vover}")
    private val baseUrl: String,
    @Value("\${tubaf.scraper.encoding.fix-legacy:true}")
    private val enableLegacyEncodingFix: Boolean,
    @Value(
        "\${tubaf.scraper.user-agent:Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36}",
    )
    private val userAgent: String,
) {
    private val logger = LoggerFactory.getLogger(TubafScrapingService::class.java)
    private val timeFormatter = DateTimeFormatter.ofPattern("H:mm")
    private val progressTracker = ScrapingProgressTracker()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scraping-worker").apply { isDaemon = true }
    }
    private val activeJob = AtomicReference<Future<*>?>(null)
    private val jobLock = Any()

    @Volatile private var cancellationMessage: String? = null
    private val txTemplate = TransactionTemplate(transactionManager)
    private val semesterTimer: Timer = Timer.builder(
        "scraping.semester.duration",
    ).description("Dauer kompletter Semester-Scrapes").register(meterRegistry)
    private val programTimer: Timer = Timer.builder(
        "scraping.program.duration",
    ).description("Dauer einzelner Studiengang-Scrapes").register(meterRegistry)
    private val rowPersistTimer: Timer = Timer.builder(
        "scraping.row.persist.duration",
    ).description("Persist-Dauer pro Zeile").register(meterRegistry)
    private val errorCounter = meterRegistry.counter("scraping.errors.total")

    // ---------------- Parallel Scraping (Variante B) ----------------
    private inner class SessionWrapper(val id: Int) {
        @Volatile
        var session: TubafScraperSession = TubafScraperSession()
            private set

        @Volatile
        var consecutiveFailures: Int = 0

        fun markFailure() {
            consecutiveFailures += 1
        }

        fun resetFailureCounter() {
            consecutiveFailures = 0
        }

        fun rebuildSession() {
            session = TubafScraperSession()
            consecutiveFailures = 0
        }
    }

    private fun createSessionPool(size: Int): BlockingQueue<SessionWrapper> {
        val queue = ArrayBlockingQueue<SessionWrapper>(size, true)
        repeat(size) { index ->
            queue.add(SessionWrapper(index + 1))
        }
        return queue
    }

    private fun acquireSession(pool: BlockingQueue<SessionWrapper>, timeoutMillis: Long = 30_000): SessionWrapper {
        ensureNotCancelled()
        return try {
            pool.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("Kein freier Scraping-Session-Slot innerhalb von ${timeoutMillis}ms verfügbar")
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ie
        }
    }

    private fun releaseSession(pool: BlockingQueue<SessionWrapper>, wrapper: SessionWrapper) {
        pool.offer(wrapper)
    }

    private fun prepareSessionForSemester(wrapper: SessionWrapper, semester: Semester) {
        try {
            val option = wrapper.session.fetchSemesterOptions().find { matchesSemesterOption(it, semester) }
                ?: throw IllegalStateException("Semester '${semester.name}' wurde auf der TUBAF-Seite nicht gefunden")
            wrapper.session.selectSemester(option)
            wrapper.resetFailureCounter()
        } catch (ex: Exception) {
            wrapper.markFailure()
            logger.warn("Session {} konnte nicht vorbereitet werden: {}", wrapper.id, ex.message)
            progressTracker.log(
                ScrapingLogLevel.WARN,
                "Session ${wrapper.id} konnte nicht vorbereitet werden: ${ex.message}",
                ex.stackTraceToString(),
            )
            throw ex
        }
    }

    private fun refreshSession(wrapper: SessionWrapper, semester: Semester) {
        runCatching {
            wrapper.rebuildSession()
            prepareSessionForSemester(wrapper, semester)
            logger.debug("Session {} wurde neu initialisiert", wrapper.id)
            progressTracker.log(
                ScrapingLogLevel.INFO,
                "Session ${wrapper.id} wurde nach Fehler neu initialisiert",
            )
        }.onFailure { ex ->
            logger.error("Neustart der Session {} fehlgeschlagen", wrapper.id, ex)
            progressTracker.log(
                ScrapingLogLevel.ERROR,
                "Neustart der Session ${wrapper.id} fehlgeschlagen: ${ex.message}",
                ex.stackTraceToString(),
            )
        }
    }

    private fun parallelScrapePrograms(
        semester: Semester,
        scrapingRunId: Long,
        programs: List<StudyProgramOption>,
        stats: ScrapeStats,
        subTaskId: String?,
        trackProgress: Boolean,
    ) {
        if (programs.isEmpty()) return

        val maxWorkers = scrapingConfiguration.parallelMaxWorkers.coerceAtLeast(1)
        val poolSize = scrapingConfiguration.parallelSessionPoolSize.coerceAtLeast(1).coerceAtMost(maxWorkers)
        val interDelay = scrapingConfiguration.parallelInterTaskDelay

        progressTracker.log(
            ScrapingLogLevel.INFO,
            "Parallelmodus aktiv – Worker: $maxWorkers, Sessions: $poolSize",
        )
        logger.info(
            "Starte paralleles Scraping für {}: Programme={}, Worker={}, Sessions={}",
            semester.name,
            programs.size,
            maxWorkers,
            poolSize,
        )

        val sessionPool = createSessionPool(poolSize)
        sessionPool.forEach { wrapper ->
            runCatching { prepareSessionForSemester(wrapper, semester) }
                .onFailure { refreshSession(wrapper, semester) }
        }

        val workerExecutor: ExecutorService = Executors.newFixedThreadPool(maxWorkers) { r ->
            Thread(r, "scrape-par-worker").apply { isDaemon = true }
        }

        val completed = AtomicInteger(0)
        val errors = CopyOnWriteArrayList<Pair<StudyProgramOption, Throwable>>()

        val futures = programs.map { program ->
            workerExecutor.submit(
                Callable {
                    ensureNotCancelled()
                    val sessionWrapper = acquireSession(sessionPool)
                    var progressMessage = ""
                    var shouldResetSession = false
                    try {
                        val programStats = programTimer.recordCallable {
                            scrapeStudyProgram(sessionWrapper.session, semester, scrapingRunId, program)
                        } ?: ScrapeStats()
                        synchronized(stats) {
                            stats.totalEntries += programStats.totalEntries
                            stats.newEntries += programStats.newEntries
                            stats.updatedEntries += programStats.updatedEntries
                            stats.studyProgramsProcessed += programStats.studyProgramsProcessed
                        }
                        sessionWrapper.resetFailureCounter()
                        progressMessage = buildProgramResultMessage(semester, program, programStats)
                        logger.info(progressMessage)
                    } catch (t: Throwable) {
                        shouldResetSession = true
                        sessionWrapper.markFailure()
                        val errorMessage = "Fehler bei ${program.code}: ${t.message}"
                        progressMessage = errorMessage
                        logger.error(errorMessage, t)
                        progressTracker.log(
                            ScrapingLogLevel.ERROR,
                            errorMessage,
                            t.stackTraceToString(),
                        )
                        errors.add(program to t)
                    } finally {
                        if (shouldResetSession || sessionWrapper.consecutiveFailures > 0) {
                            refreshSession(sessionWrapper, semester)
                        }
                        releaseSession(sessionPool, sessionWrapper)
                        val done = completed.incrementAndGet()
                        val updateMsg =
                            if (progressMessage.isBlank()) {
                                "${program.code} abgeschlossen ($done/${programs.size})"
                            } else {
                                "$progressMessage ($done/${programs.size})"
                            }
                        if (trackProgress) {
                            progressTracker.update(
                                task = "Parallel " + semester.shortName,
                                processed = done,
                                total = programs.size,
                                message = updateMsg,
                            )
                        }
                        if (subTaskId != null) {
                            progressTracker.updateSubTask(
                                id = subTaskId,
                                processed = done,
                                total = programs.size,
                                message = updateMsg,
                            )
                        }
                        if (interDelay > 0) {
                            try {
                                Thread.sleep(interDelay)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                    }
                },
            )
        }

        workerExecutor.shutdown()
        try {
            val finishedOk = workerExecutor.awaitTermination(60, TimeUnit.MINUTES)
            if (!finishedOk) {
                workerExecutor.shutdownNow()
                throw IllegalStateException("Timeout beim parallelen Scraping")
            }
        } catch (ie: InterruptedException) {
            workerExecutor.shutdownNow()
            throw ie
        }

        futures.forEach { future ->
            try {
                future.get()
            } catch (_: ExecutionException) {
                // Bereits im Fehlerprotokoll enthalten
            }
        }

        if (errors.isNotEmpty()) {
            val (program, throwable) = errors.first()
            val aggregatedMessage =
                if (errors.size == 1) {
                    "Fehler im parallelen Scraping für ${program.code}: ${throwable.message}"
                } else {
                    "${errors.size} Fehler im parallelen Scraping – erster in ${program.code}: ${throwable.message}"
                }
            throw IllegalStateException(aggregatedMessage, throwable)
        }
    }

    fun isJobRunning(): Boolean {
        val current = activeJob.get()
        return current != null && !current.isDone && !current.isCancelled
    }

    fun startDiscoveryJob(): Boolean = submitJob("Starte Discovery-Scraping") {
        discoverAndScrapeAvailableSemesters()
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
        return submitJob("Starte Scraping für ${semester.name}") {
            scrapeSemesterData(semester)
        }
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
            message = "Starte Discovery für ${semesterOptions.size} Semester",
        )

        if (semesterOptions.isEmpty()) {
            logger.warn("Keine Semester auf der TUBAF-Seite gefunden – verwende vorhandene Semester aus der Datenbank")
            progressTracker.finish("Keine Semester auf der Webseite gefunden")
            return semesterService.getAllSemesters().map { scrapeSemesterData(it) }
        }

        val results = mutableListOf<ScrapingResult>()
        // SubTasks initialisieren (ein SubTask pro Semesteroption)
        progressTracker.initSubTasks(
            semesterOptions.mapIndexed { idx, opt -> "sem-$idx" },
            semesterOptions.map { it.displayName },
        )
        try {
            for ((index, option) in semesterOptions.withIndex()) {
                ensureNotCancelled()
                logger.info(
                    "[{} / {}] Entdeckt: {}",
                    index + 1,
                    semesterOptions.size,
                    option.displayName,
                )
                progressTracker.log(ScrapingLogLevel.INFO, "Scraping ${option.displayName}")
                val subId = "sem-$index"
                progressTracker.startSubTask(subId, total = 0)

                session.selectSemester(option)
                val semester = getOrCreateSemester(option)
                val semesterResult =
                    scrapeSemesterDataWithSession(
                        session,
                        semester,
                        trackProgress = false,
                        subTaskId = subId,
                    )
                results += semesterResult

                progressTracker.updateSubTask(
                    id = subId,
                    processed = 100,
                    message = (
                        "${semesterResult.totalEntries} Einträge (" +
                            "neu ${semesterResult.newEntries}, " +
                            "aktualisiert ${semesterResult.updatedEntries})"
                        ),
                )
                progressTracker.completeSubTask(subId)
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
                shortName = buildShortName(option.displayName),
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

        val matchedOptions = linkedMapOf<String, SemesterOption>()
        semesterIdentifiers.forEach { identifier ->
            val normalizedIdentifier = normalize(identifier)
            val option = lookup[normalizedIdentifier]
                ?: throw IllegalArgumentException("Semester '$identifier' wurde auf der TUBAF-Seite nicht gefunden")
            matchedOptions.putIfAbsent(option.displayName, option)
        }

        progressTracker.start(
            totalCount = matchedOptions.size,
            task = "Scraping ausgewählter Semester",
            message = "Starte Scraping für ${matchedOptions.size} Semester",
        )

        val results = mutableListOf<ScrapingResult>()
        val ordered = matchedOptions.values.toList()
        progressTracker.initSubTasks(
            ordered.mapIndexed { idx, opt -> "sem-$idx" },
            ordered.map { it.displayName },
        )
        try {
            ordered.forEachIndexed { index, option ->
                ensureNotCancelled()
                logger.info("Starte Scraping für erkanntes Semester: {}", option.displayName)
                progressTracker.log(ScrapingLogLevel.INFO, "Scraping ${option.displayName}")

                session.selectSemester(option)
                val semester = getOrCreateSemester(option)
                val subId = "sem-$index"
                progressTracker.startSubTask(subId, total = 0)
                val result =
                    scrapeSemesterDataWithSession(
                        session,
                        semester,
                        trackProgress = false,
                        subTaskId = subId,
                    )
                results += result

                progressTracker.updateSubTask(
                    id = subId,
                    processed = 100,
                    message = "${result.totalEntries} Einträge (neu ${result.newEntries}, aktualisiert ${result.updatedEntries})",
                )
                progressTracker.completeSubTask(subId)
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
        // Eigenen SubTask für Einzel-Semester anlegen
        progressTracker.reset("Einzel-Semester Scrape")
        progressTracker.initSubTasks(listOf("sem-0"), listOf(semester.name))
        progressTracker.startSubTask("sem-0", total = 0)

        val session = TubafScraperSession()
        val semesterOption = session.fetchSemesterOptions().find { matchesSemesterOption(it, semester) }
            ?: throw IllegalArgumentException("Semester '${semester.name}' wurde auf der TUBAF-Seite nicht gefunden")

        session.selectSemester(semesterOption)
        val result =
            scrapeSemesterDataWithSession(
                session,
                semester,
                trackProgress = true,
                subTaskId = "sem-0",
            )
        progressTracker.updateSubTask(
            "sem-0",
            processed = 100,
            message = "${result.totalEntries} Einträge (neu ${result.newEntries}, aktualisiert ${result.updatedEntries})",
        )
        progressTracker.completeSubTask("sem-0")
        return result
    }

    private fun scrapeSemesterDataWithSession(
        session: TubafScraperSession,
        semester: Semester,
        trackProgress: Boolean,
        subTaskId: String? = null,
    ): ScrapingResult {
        val scrapingRun = changeTrackingService.startScrapingRun(semester.id!!, baseUrl)
        val stats = ScrapeStats()
        return try {
            // Messe komplette Semester-Scrape Dauer (ohne Overload-Ambiguität)
            val semesterSample = Timer.start(meterRegistry)
            try {
                val programs = session.fetchStudyPrograms()
                logger.info("Gefundene Studiengänge für {}: {}", semester.name, programs.size)
                progressTracker.log(
                    ScrapingLogLevel.INFO,
                    "${programs.size} Studiengänge für ${semester.name} gefunden",
                )

                if (trackProgress) {
                    progressTracker.start(
                        totalCount = programs.size,
                        task = if (scrapingConfiguration.parallelEnabled) {
                            ("Parallel Scraping " + semester.shortName)
                        } else {
                            (
                                "Scraping " +
                                    semester.shortName
                                )
                        },
                        message = "Starte ${if (scrapingConfiguration.parallelEnabled) "paralleles " else ""}Scraping für ${semester.name}",
                    )
                }

                if (subTaskId != null) {
                    progressTracker.updateSubTask(
                        id = subTaskId,
                        processed = 0,
                        total = programs.size,
                        message = "${programs.size} Studiengänge gefunden",
                    )
                }

                if (scrapingConfiguration.parallelEnabled) {
                    parallelScrapePrograms(
                        semester,
                        scrapingRun.id!!,
                        programs,
                        stats,
                        subTaskId,
                        trackProgress,
                    )
                } else {
                    programs.forEachIndexed { index, program ->
                        ensureNotCancelled()
                        logger.info(
                            "   [{} / {}] Verarbeite Studiengang {} ({})",
                            index + 1,
                            programs.size,
                            program.code,
                            program.displayName,
                        )
                        val programStats = programTimer.recordCallable {
                            scrapeStudyProgram(session, semester, scrapingRun.id!!, program)
                        } ?: ScrapeStats()
                        stats.totalEntries += programStats.totalEntries
                        stats.newEntries += programStats.newEntries
                        stats.updatedEntries += programStats.updatedEntries
                        stats.studyProgramsProcessed += programStats.studyProgramsProcessed

                        val msg = buildProgramResultMessage(semester, program, programStats)
                        logger.info(msg)
                        if (trackProgress) {
                            progressTracker.update(
                                task = semester.shortName + ": " + program.code,
                                processed = index + 1,
                                total = programs.size,
                                message = msg,
                            )
                        }
                        if (subTaskId != null) {
                            progressTracker.updateSubTask(
                                id = subTaskId,
                                processed = index + 1,
                                total = programs.size,
                                message = msg,
                            )
                        }
                    }
                }

                changeTrackingService.completeScrapingRun(
                    scrapingRun.id!!,
                    stats.totalEntries,
                    stats.newEntries,
                    stats.updatedEntries,
                )

                logger.info(
                    "Scraping abgeschlossen für {} – Einträge={}, neu={}, aktualisiert={}",
                    semester.name,
                    stats.totalEntries,
                    stats.newEntries,
                    stats.updatedEntries,
                )

                val summaryMessage = buildScrapingSummary(semester, stats)
                if (trackProgress) {
                    progressTracker.finish(summaryMessage)
                } else {
                    progressTracker.log(ScrapingLogLevel.INFO, summaryMessage)
                }
            } finally {
                semesterSample.stop(semesterTimer)
            }
            stats.toResult()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: Exception) {
            errorCounter.increment()
            changeTrackingService.failScrapingRun(scrapingRun.id!!, ex.message ?: ex.javaClass.simpleName)
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
            val document = if (fachSemester.isPostRequired) {
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

    private fun buildProgramResultMessage(semester: Semester, program: StudyProgramOption, stats: ScrapeStats, disabled: Int = 0): String {
        val semShort = semester.shortName
        return (
            "$semShort:${program.code}: ${stats.totalEntries} Einträge (" +
                "neu ${stats.newEntries}, " +
                "aktualisiert ${stats.updatedEntries}, " +
                "deaktiviert $disabled)"
            )
    }

    private fun buildScrapingSummary(semester: Semester, stats: ScrapeStats): String = (
        "Scraping für ${semester.name} abgeschlossen – " +
            "${stats.totalEntries} Einträge (neu ${stats.newEntries}, aktualisiert ${stats.updatedEntries})"
        )

    private fun persistRows(rows: List<ScrapedRow>, semester: Semester, scrapingRunId: Long, stats: ScrapeStats) {
        for (row in rows) {
            val dayOfWeek = parseDayOfWeek(row.day)
            val timeRange = parseTimeRange(row.time)

            if (dayOfWeek == null || timeRange == null) {
                logger.debug(
                    "      Überspringe Zeile wegen ungültiger Zeit/Tag: {} - {}",
                    row.day,
                    row.time,
                )
                continue
            }

            // Kapsel DB-Arbeit je Zeile in eigene Transaktion um lange Locks zu vermeiden
            rowPersistTimer.record(
                Runnable {
                    txTemplate.execute {
                        val courseType = getOrCreateCourseType(row.courseType, scrapingRunId)
                        val lecturer = getOrCreateLecturer(row.lecturer, scrapingRunId)
                        val room = getOrCreateRoom(row.room, scrapingRunId)
                        val course = getOrCreateCourse(row.courseTitle, semester, lecturer, courseType, scrapingRunId)
                        attachCourseToStudyProgram(course, row.studyProgram, row.fachSemester)

                        // Lade Course mit scheduleEntries, um LazyInitializationException zu vermeiden
                        val courseWithEntries = courseRepository.findByIdWithScheduleEntries(course.id!!) ?: course

                        val existingEntry =
                            courseWithEntries.scheduleEntries.firstOrNull { se ->
                                se.dayOfWeek == dayOfWeek &&
                                    se.startTime == timeRange.first &&
                                    se.endTime == timeRange.second &&
                                    se.room.code.equals(room.code, ignoreCase = true)
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
                                    course = courseWithEntries, // Verwende courseWithEntries statt course (managed entity)
                                    room = room,
                                    dayOfWeek = dayOfWeek,
                                    startTime = timeRange.first,
                                    endTime = timeRange.second,
                                ).apply {
                                    weekPattern = row.weekPattern.ifBlank { null }
                                    notes = notesText.ifBlank { null }
                                }

                            val saved = scheduleEntryRepository.save(newEntry)
                            // Keine manuelle Collection-Manipulation nötig - bidirektionale Beziehung via course.scheduleEntries wird automatisch gepflegt
                            changeTrackingService.logEntityCreated(scrapingRunId, "ScheduleEntry", saved.id!!)
                            stats.newEntries += 1
                        }

                        stats.totalEntries += 1
                    }
                },
            )
        }
    }

    private fun attachCourseToStudyProgram(course: Course, option: StudyProgramOption, fachSemester: String) {
        val studyProgram =
            studyProgramRepository.findByCode(option.code)
                ?: studyProgramRepository.findByNameContainingIgnoreCase(option.displayName).firstOrNull()
                ?: return

        if (studyProgram.id == null) {
            logger.warn(
                "Überspringe Verknüpfung für Kurs {} mit Studiengang '{}' weil die ID fehlt",
                course.id,
                option.code,
            )
            return
        }

        // Lade Course mit allen Relations um sicherzustellen dass die Entity im Persistence Context ist
        val managedCourse = courseRepository.findById(course.id!!).orElse(null) ?: return

        val alreadyLinked = managedCourse.courseStudyPrograms.any { it.studyProgram.id == studyProgram.id }
        if (!alreadyLinked) {
            try {
                managedCourse.addStudyProgram(studyProgram, parseFachSemesterNumber(fachSemester))
                courseRepository.save(managedCourse)
            } catch (ex: DataIntegrityViolationException) {
                // Race condition: Ein anderer Thread hat bereits die Verknüpfung erstellt
                logger.debug("Parallel link creation for course {} and studyProgram {}", course.id, studyProgram.id, ex)
                // Nicht kritisch - Verknüpfung existiert bereits
            }
        }
    }

    private fun parseFachSemesterNumber(value: String): Int? = value.substringBefore('.').toIntOrNull()

    private fun parseScheduleRows(document: Document, program: StudyProgramOption, fachSemester: String): List<ScrapedRow> {
        val scheduleTable =
            document.select("table")
                .firstOrNull { table ->
                    val headers = table.select("th").map { it.text().lowercase(Locale.GERMAN) }
                    headers.contains("titel") && headers.contains("zeit")
                }

        if (scheduleTable == null) {
            val msg = "Keine Tabelle mit 'Titel' und 'Zeit' Überschriften für ${program.code} ($fachSemester)"
            logger.info(msg)
            progressTracker.log(ScrapingLogLevel.WARN, msg)
            progressTracker.log(
                ScrapingLogLevel.INFO,
                "Verfügbare Tabellen: ${document.select("table").size}",
            )
            document.select("table").forEachIndexed { index, table ->
                val headers = table.select("th").map { it.text() }
                if (headers.isNotEmpty()) {
                    progressTracker.log(
                        ScrapingLogLevel.DEBUG,
                        "  Tabelle $index: Headers = $headers",
                    )
                }
            }
            return emptyList()
        }

        logger.info("Gefundene Schedule-Tabelle für {} ({}), starte Parsing", program.code, fachSemester)
        progressTracker.log(
            ScrapingLogLevel.INFO,
            "Verarbeite Tabelle für ${program.code} ($fachSemester)",
        )

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
                val first = cells.firstOrNull()
                val text = first?.text()?.trim().orEmpty()
                val colspan = first?.attr("colspan") ?: ""
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
                    "Überspringe Zeile ohne Titel: {} Zellen, Type={}, Dozent={}, Tag={}",
                    cells.size,
                    courseType,
                    lecturer,
                    day,
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
                ScrapingLogLevel.WARN,
                "⚠️  ${program.code} ($fachSemester): $skippedRows Zeilen ohne Titel übersprungen",
            )
        }
        if (rows.isNotEmpty()) {
            progressTracker.log(
                ScrapingLogLevel.INFO,
                "✅ ${program.code} ($fachSemester): ${rows.size} Kurse gefunden",
            )
        }

        return rows
    }

    private fun extractInfoId(cell: Element): String {
        val link = cell.selectFirst("a[href*=satz=]")
        return link
            ?.attr("href")
            ?.substringAfter("satz=")
            ?.substringBefore('&')
            ?.trim()
            ?: ""
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
            // Lade das Course-Objekt neu mit scheduleEntries, um LazyInitializationException zu vermeiden
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
                normalized.startsWith("Ü") || normalized.startsWith("UE") || normalized.startsWith("U") -> "Ü"
                normalized.startsWith("S") -> "S"
                normalized.startsWith("P") || normalized.startsWith("PR") -> "P"
                normalized.startsWith("B") -> "B"
                else -> normalized.take(1).ifBlank { "V" }
            }

        courseTypeRepository.findByCode(code)?.let { return it }

        val newType = CourseType(code = code, name = typeText.ifBlank { code })
        return try {
            val saved = courseTypeRepository.save(newType)
            changeTrackingService.logEntityCreated(scrapingRunId, "CourseType", saved.id!!)
            saved
        } catch (ex: DataIntegrityViolationException) {
            logger.debug("Parallel creation race for course type {}", code, ex)
            entityManager.detach(newType)
            courseTypeRepository.findByCode(code)
                ?: throw ex
        }
    }

    private fun getOrCreateLecturer(rawText: String, scrapingRunId: Long): Lecturer {
        val parsed = parseLecturerIdentity(rawText)

        // 1) Falls Email extrahiert wurde: direkter Lookup nach Email (präziser als Name)
        if (!parsed.email.isNullOrBlank()) {
            lecturerRepository.findByEmailIgnoreCase(parsed.email!!)?.let { existing ->
                var changed = false
                if (parsed.title != null && existing.title.isNullOrBlank()) {
                    existing.title = parsed.title.take(50)
                    changed = true
                }
                if (parsed.name.isNotBlank() && !existing.name.equals(parsed.name, ignoreCase = true)) {
                    // Wir überschreiben den Namen nicht direkt (er könnte kuratiert sein), nur loggen.
                    logger.debug(
                        "Email match für Lecturer id={} email={} aber Name differiert scraped='{}' stored='{}'",
                        existing.id,
                        parsed.email,
                        parsed.name,
                        existing.name,
                    )
                }
                if (changed) lecturerRepository.save(existing)
                return existing
            }
        }

        // 2) Fallback: Namensbasierte Suche (bestehendes Verhalten)
        lecturerRepository.findByNameContainingIgnoreCaseAndActive(parsed.name).firstOrNull()?.let { existing ->
            var updated = false
            if (parsed.email != null && existing.email.isNullOrBlank()) {
                existing.email = parsed.email
                updated = true
            }
            if (parsed.title != null && existing.title.isNullOrBlank()) {
                existing.title = parsed.title.take(50)
                updated = true
            }
            if (updated) lecturerRepository.save(existing)
            return existing
        }

        // 3) Neu anlegen
        val lecturer = Lecturer(
            name = parsed.name,
            email = parsed.email,
            title = parsed.title?.take(50),
        )
        val saved = try {
            lecturerRepository.save(lecturer)
        } catch (ex: DataIntegrityViolationException) {
            logger.debug("Parallel creation race for lecturer '{}'", parsed.name, ex)
            entityManager.detach(lecturer)
            // Versuche nochmal zu finden - entweder nach Email oder Name
            parsed.email?.let { lecturerRepository.findByEmailIgnoreCase(it) }
                ?: lecturerRepository.findByNameContainingIgnoreCaseAndActive(parsed.name).firstOrNull()
                ?: throw ex
        }
        if (saved.id != null) {
            changeTrackingService.logEntityCreated(scrapingRunId, "Lecturer", saved.id!!)
        }
        if (parsed.modified || parsed.truncated) {
            val flags = buildList {
                if (parsed.modified) add("normalized")
                if (parsed.truncated) add("truncated")
                if (parsed.email != null) add("email-extracted")
                if (!parsed.title.isNullOrBlank()) add("title-extracted")
            }.joinToString(",")
            val logMsg = (
                "Lecturer sanitized [$flags]: raw='${rawText.take(120)}' -> " +
                    "title='${parsed.title}' name='${parsed.name}' email='${parsed.email}'"
                )
            if (logMsg.length > 140) {
                logger.info(logMsg.take(137) + "...")
            } else {
                logger.info(logMsg)
            }
        }
        return saved
    }

    private data class ParsedLecturer(
        val name: String,
        val email: String?,
        val title: String?,
        val modified: Boolean,
        val truncated: Boolean,
    )

    private fun parseLecturerIdentity(raw: String): ParsedLecturer {
        val original = raw
        var value = raw.trim()
        var email: String? = null
        var title: String? = null
        var modified = false
        var truncated = false

        if (value.isBlank()) {
            return ParsedLecturer(name = "N.N.", email = null, title = null, modified = original != "N.N.", truncated = false)
        }

        // Collapse whitespace
        val collapsed = Regex("\\s+").replace(value.replace('\n', ' ').replace('\r', ' '), " ")
        if (collapsed != value) {
            value = collapsed
            modified = true
        }

        // Extract email (first occurrence) if present
        val emailRegex = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        val emailMatch = emailRegex.find(value)
        if (emailMatch != null) {
            email = emailMatch.value.lowercase()
            // Remove surrounding constructs like <email> or (email)
            val before = value.substring(0, emailMatch.range.first)
            val after = value.substring(emailMatch.range.last + 1)
            value = (before + after)
                .replace("<", " ")
                .replace(">", " ")
                .replace("(", " ")
                .replace(")", " ")
                .trim()
            modified = true
        }

        // Extract leading academic titles (sequence) before stripping punctuation.
        run {
            val titleTokens = mutableListOf<String>()
            var remaining = value
            var progressed = true
            val tokenRegex = Regex("^(?:(?:[A-Za-zÄÖÜäöü]{1,8}\\.)|(?:[A-Za-zÄÖÜäöü]{2,15}))(?=\\s|$)")
            val known = setOf(
                // Akademische Titel/Kürzel (häufige Varianten + Punkte)
                "prof", "prof.", "dr", "dr.",
                "dipl.-ing.", "dipl.-ing", "dipl.ing.",
                "msc", "bsc", "mag", "mag.",
                "pd", "apl", "apl.",
                "jun.-prof.", "jun.-prof", "priv.-doz.", "priv.-doz", "habil.",
            )
            while (progressed) {
                progressed = false
                val m = tokenRegex.find(remaining.trimStart())
                if (m != null) {
                    val token = m.value
                    val norm = token.lowercase()
                    if (norm in known || (norm.endsWith('.') && norm.dropLast(1).all { it.isLetter() } && norm.length <= 6)) {
                        titleTokens += token.trimEnd(',')
                        remaining = remaining.trimStart().substring(m.range.last + 1).trimStart()
                        progressed = true
                    }
                }
            }
            if (titleTokens.isNotEmpty()) {
                title = titleTokens.joinToString(" ").take(50)
                value = remaining
                modified = true
            }
        }

        // Strip leading/trailing punctuation
        val stripped = value.trim { it == '-' || it == ';' || it == ',' || it.isWhitespace() }
        if (stripped != value) {
            value = stripped
            modified = true
        }

        // Long multi-part names: keep only first segment by delimiters if exceedingly long (>200)
        if (value.length > 200) {
            for (d in listOf(';', '/', '|')) {
                if (value.contains(d)) {
                    val first = value.split(d).first().trim()
                    if (first.isNotBlank()) {
                        value = first
                        truncated = true
                        modified = true
                        break
                    }
                }
            }
        }

        if (value.length > 200) {
            value = value.take(200)
            truncated = true
        }

        if (value.isBlank()) value = "N.N."

        // Ensure email length constraint (150)
        if (email != null && email.length > 150) {
            email = email.take(150)
            truncated = true
        }

        return ParsedLecturer(name = value, email = email, title = title, modified = modified, truncated = truncated)
    }

    private fun getOrCreateRoom(rawText: String, scrapingRunId: Long): Room {
        val value = rawText.trim().ifBlank { "TBD" }
        roomRepository.findByCode(value)?.let { return it }

        val (building, number) = parseRoomCode(value)
        val room = Room(code = value, building = building, roomNumber = number)

        var created = true
        val saved = try {
            roomRepository.save(room)
        } catch (ex: org.springframework.dao.DataIntegrityViolationException) {
            logger.debug("Room with code {} already persisted concurrently: {}", value, ex.message)
            entityManager.detach(room)
            created = false
            roomRepository.findByCode(value) ?: throw ex
        }

        if (created && saved.id != null) {
            changeTrackingService.logEntityCreated(scrapingRunId, "Room", saved.id!!)
        }
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

    private fun normalize(value: String): String = value.lowercase(Locale.GERMAN)
        .replace(" ", "")
        .replace("-", "")
        .replace("/", "")
        .replace("_", "")

    private fun buildPossibleShortNames(displayName: String): Set<String> {
        val normalized = displayName.lowercase(Locale.GERMAN)
        val prefix = when {
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

    private fun getOrCreateSemester(option: SemesterOption): Semester = semesterService.getAllSemesters().firstOrNull {
        matchesSemesterOption(option, it)
    }
        ?: createSemesterFromOption(option)

    private fun createSemesterFromOption(option: SemesterOption): Semester {
        val (start, end) = mapSemesterDates(option.displayName)
        val shortName = buildShortName(option.displayName)
        return semesterService.createSemester(option.displayName, shortName, start, end, active = false)
    }

    private fun buildShortName(displayName: String): String {
        val trimmed = displayName.trim()
        val normalized = trimmed.lowercase(Locale.GERMAN)
        val years = Regex("(19|20)\\d{2}").findAll(trimmed).map { it.value }.toList()
        val prefix =
            when {
                normalized.startsWith("winter") -> "WS"
                normalized.startsWith("sommer") -> "SS"
                else -> trimmed.take(2).uppercase(Locale.GERMAN)
            }

        if (prefix == "WS") {
            if (years.size >= 2) {
                return prefix + years[0].takeLast(2) + years[1].takeLast(2)
            }
            if (years.size == 1) {
                return prefix + years[0].takeLast(2)
            }
        }

        if (prefix == "SS") {
            if (years.isNotEmpty()) {
                return prefix + years.first().takeLast(2)
            }
        }

        if (years.isNotEmpty()) {
            return prefix + years.joinToString("") { it.takeLast(2) }
        }

        return trimmed.take(6).uppercase(Locale.GERMAN)
    }

    private fun mapSemesterDates(displayName: String): Pair<LocalDate, LocalDate> {
        val normalized = displayName.lowercase(Locale.GERMAN)
        val years = Regex("(19|20)\\d{2}").findAll(displayName).map { it.value.toInt() }.toList()
        val currentYear = LocalDate.now().year

        return when {
            normalized.contains("winter") -> {
                val startYear = years.minOrNull() ?: currentYear
                val endYear = when {
                    years.size >= 2 -> years.maxOrNull() ?: (startYear + 1)
                    else -> startYear + 1
                }
                LocalDate.of(startYear, 10, 1) to LocalDate.of(endYear, 3, 31)
            }

            normalized.contains("sommer") -> {
                val year = years.firstOrNull() ?: currentYear
                LocalDate.of(year, 4, 1) to LocalDate.of(year, 9, 30)
            }

            else -> LocalDate.of(currentYear, 4, 1) to LocalDate.of(currentYear, 9, 30)
        }
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
                progressTracker.log(
                    ScrapingLogLevel.WARN,
                    "Es läuft bereits ein Scraping-Prozess",
                )
                return false
            }

            cancellationMessage = null
            progressTracker.reset(initialMessage)

            val future = executor.submit {
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

    private inner class TubafScraperSession {
        private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private val httpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(JavaNetCookieJar(cookieManager))
            .retryOnConnectionFailure(true)
            .callTimeout(Duration.ofMillis(scrapingConfiguration.timeout))
            .connectTimeout(Duration.ofMillis(scrapingConfiguration.timeout))
            .readTimeout(Duration.ofMillis(scrapingConfiguration.timeout))
            .writeTimeout(Duration.ofMillis(scrapingConfiguration.timeout))
            .build()

        private val trimmedBase = baseUrl.trimEnd('/')

        fun fetchSemesterOptions(): List<SemesterOption> {
            val document = request("GET", "index.html")
            val select = document.selectFirst("select[name=sem_wahl]") ?: return emptyList()
            return select.select("option").map { SemesterOption(it.text().trim()) }
        }

        fun selectSemester(option: SemesterOption) {
            logger.info("Wechsle zu Semester: {}", option.displayName)
            progressTracker.log(ScrapingLogLevel.INFO, "Wechsel zu: ${option.displayName}")

            // Log Cookies vor dem Request
            val cookiesBefore = cookieManager.cookieStore.cookies.size
            logger.info("Cookies vor selectSemester: {}", cookiesBefore)

            val formData = mapOf(
                "sem_wahl" to option.displayName,
                "wechsel" to "4",
                "senden" to "Auswählen",
            )
            val response = request("POST", "index.html", formData, referer = url("index.html"))

            // Log Cookies nach dem Request
            val cookiesAfter = cookieManager.cookieStore.cookies.size
            logger.info("Cookies nach selectSemester: {}", cookiesAfter)
            progressTracker.log(ScrapingLogLevel.DEBUG, "Cookies nach Wechsel: $cookiesAfter")

            // Prüfe, welches Semester jetzt aktiv ist
            val activeOption = response.selectFirst("select[name=sem_wahl] option[selected]")
            val activeSemester = activeOption?.text()?.trim() ?: "unbekannt"
            logger.info("Aktives Semester nach Wechsel: {}", activeSemester)
            progressTracker.log(ScrapingLogLevel.INFO, "Aktives Semester: $activeSemester")
        }

        fun fetchStudyPrograms(): List<StudyProgramOption> {
            // Log Cookies vor dem Request
            val cookiesBefore = cookieManager.cookieStore.cookies.size
            logger.info("Cookies vor fetchStudyPrograms: {}", cookiesBefore)
            progressTracker.log(ScrapingLogLevel.DEBUG, "Cookies vor verz.html: $cookiesBefore")

            val document = request("GET", "verz.html", referer = url("index.html"))

            // Prüfe das aktive Semester auf verz.html
            val activeOption = document.selectFirst("select[name=sem_wahl] option[selected]")
            val activeSemester = activeOption?.text()?.trim() ?: "unbekannt"
            logger.info("Aktives Semester auf verz.html: {}", activeSemester)
            progressTracker.log(ScrapingLogLevel.INFO, "Semester laut verz.html: $activeSemester")

            val tableCount = document.select("table").size
            logger.info("fetchStudyPrograms: Lade verz.html, Tabellen gefunden: {}", tableCount)
            progressTracker.log(ScrapingLogLevel.INFO, "verz.html geladen: $tableCount Tabellen gefunden")

            // Die Studiengänge-Tabelle ist die, die Links zu stgvrz.html enthält
            val table = document.select("table").firstOrNull { it.select("a[href^=stgvrz.html]").isNotEmpty() }

            if (table == null) {
                logger.warn("Keine Tabelle mit stgvrz.html Links gefunden")
                progressTracker.log(ScrapingLogLevel.WARN, "Keine passende Tabelle gefunden")
                logger.info("Verfügbare Tabellen-Texte:")
                progressTracker.log(ScrapingLogLevel.INFO, "Vorhandene Tabellen:")
                document.select("table").take(5).forEachIndexed { index, t ->
                    val text = t.text().take(100)
                    logger.info("  Tabelle {}: {}", index, text)
                    progressTracker.log(ScrapingLogLevel.DEBUG, "  Tab $index: $text")
                }
                return emptyList()
            }

            logger.info("Tabelle mit Studiengängen gefunden. Text: {}", table.text().take(200))
            progressTracker.log(ScrapingLogLevel.INFO, "Tabelle gefunden: ${table.text().take(100)}")

            val programs = mutableListOf<StudyProgramOption>()
            var currentFaculty = ""

            val rowCount = table.select("tr").size
            logger.info("Zeilen in Tabelle: {}", rowCount)
            progressTracker.log(ScrapingLogLevel.INFO, "$rowCount Zeilen in Tabelle")

            for (row in table.select("tr")) {
                if (row.select("b u").isNotEmpty()) {
                    currentFaculty = row.text().trim()
                    logger.debug("Fakultät: {}", currentFaculty)
                    continue
                }

                val link = row.selectFirst("a[href^=stgvrz.html]")
                if (link == null) {
                    logger.debug("Überspringe Zeile ohne stgvrz.html Link: {}", row.text().take(50))
                    continue
                }

                val href = link.attr("href")
                val codeCell = row.selectFirst("td")
                val code = codeCell?.text()?.trim().orEmpty()
                val displayName = link.text().trim()

                val queryParams = parseQueryParameters(href)
                val stdg = (queryParams["stdg"] ?: code).trim()
                val stdgName = (queryParams["stdg1"] ?: displayName).trim()

                // Debug-Ausgabe für potentielle Encoding-Probleme (z.B. BGÖK -> BG�K)
                if (stdg.contains('\uFFFD') || stdgName.contains('\uFFFD') || stdg.any { it.code > 127 }) {
                    logger.warn(
                        "Studiengang Encoding Verdacht: rawHref='{}' stdg='{}' stdgName='{}' stdgHex={} stdgCP={} stdgNameHex={} stdgNameCP={}",
                        href.take(160),
                        stdg,
                        stdgName,
                        stdg.toByteArray(StandardCharsets.UTF_8).joinToString(" ") { b -> "%02X".format(b) },
                        stdg.codePoints().toArray().joinToString(",") { "U+" + it.toString(16).uppercase() },
                        stdgName.toByteArray(StandardCharsets.UTF_8).joinToString(" ") { b -> "%02X".format(b) },
                        stdgName.codePoints().toArray().joinToString(",") { "U+" + it.toString(16).uppercase() },
                    )
                }

                programs +=
                    StudyProgramOption(
                        code = stdg,
                        displayName = stdgName,
                        faculty = currentFaculty,
                        href = href,
                    )
                logger.debug("Studiengang aufgenommen: {} ({})", displayName, code)
            }

            logger.info("Insgesamt {} Studiengänge gefunden", programs.size)
            progressTracker.log(ScrapingLogLevel.INFO, "${programs.size} Studiengänge gefunden")

            return programs
        }

        fun openProgram(program: StudyProgramOption): Document {
            // Absicherung bei Nicht-ASCII Zeichen im Code (z.B. Ö) – href kommt bereits von der Seite.
            return request("GET", program.href, referer = url("verz.html"))
        }

        fun openProgramSemester(program: StudyProgramOption, fachSemester: String): Document {
            val formData = mapOf(
                "stdg" to program.code,
                "stdg1" to program.displayName,
                "semest" to fachSemester,
                "popup3" to "",
            )
            // Baue Referer konservativ mit URLEncoder – logge zur Diagnose die verwendeten Werte
            val encodedCode = java.net.URLEncoder.encode(program.code, StandardCharsets.UTF_8)
            val refererUrl = url("stgvrz.html?stdg=$encodedCode")
            if (program.code.contains('\uFFFD') || program.code.any { it.code > 127 }) {
                logger.warn(
                    "POST openProgramSemester Encoding Check: code='{}' encoded='{}' codeCP={} display='{}' displayCP={}",
                    program.code,
                    encodedCode,
                    program.code.codePoints().toArray().joinToString(",") { "U+" + it.toString(16).uppercase() },
                    program.displayName,
                    program.displayName.codePoints().toArray().joinToString(",") { "U+" + it.toString(16).uppercase() },
                )
            }
            return request("POST", "stgvrz.html", formData, referer = refererUrl)
        }

        private fun request(method: String, path: String, formData: Map<String, String>? = null, referer: String? = null): Document {
            val targetUrl = url(path)
            val operationName = "HTTP ${method.uppercase(Locale.ROOT)} $targetUrl"
            val builder =
                Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.6")
                    .header("Connection", "keep-alive")

            if (referer != null) {
                builder.header("Referer", referer)
            }

            if (method.equals("POST", ignoreCase = true) && formData != null) {
                val formBuilder = FormBody.Builder(StandardCharsets.UTF_8)
                formData.forEach { (key, value) ->
                    formBuilder.add(key, value)
                }
                builder.post(formBuilder.build())
            } else {
                builder.get()
            }

            return executeWithRetry(operationName) {
                httpClient.newCall(builder.build()).execute().use { response ->
                    val bodyString = response.body?.string()

                    if (!response.isSuccessful) {
                        val message = "HTTP ${response.code} für ${response.request.url}"
                        if (shouldRetryStatus(response.code)) {
                            throw RetryableHttpException(response.code, message)
                        }
                        val bodySnippet = bodyString?.take(200)
                        throw IllegalStateException("$message – ${bodySnippet ?: ""}")
                    }

                    val body = bodyString ?: throw IOException("Leere Antwort vom Server")
                    Jsoup.parse(body, targetUrl)
                }
            }
        }

        private fun shouldRetryStatus(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode >= 500

        private fun <T> executeWithRetry(operationName: String, block: () -> T): T {
            val maxAttempts = scrapingConfiguration.maxRetries.coerceAtLeast(1)
            val baseDelay = scrapingConfiguration.retryDelay.coerceAtLeast(0)
            var attempt = 1
            var lastException: Exception? = null

            while (attempt <= maxAttempts) {
                ensureNotCancelled()
                try {
                    return block()
                } catch (ex: Exception) {
                    if (ex is InterruptedException) {
                        throw ex
                    }

                    lastException = ex
                    val retryable = isRetryableException(ex)
                    logger.warn("{} fehlgeschlagen (Versuch {}/{}): {}", operationName, attempt, maxAttempts, ex.message)
                    progressTracker.log(
                        ScrapingLogLevel.WARN,
                        "$operationName fehlgeschlagen (Versuch $attempt/$maxAttempts): ${ex.message}",
                    )

                    if (!retryable || attempt == maxAttempts) {
                        if (retryable && attempt == maxAttempts) {
                            progressTracker.log(
                                ScrapingLogLevel.ERROR,
                                "$operationName scheiterte nach $maxAttempts Versuchen: ${ex.message}",
                                ex.stackTraceToString(),
                            )
                            throw IllegalStateException(
                                "$operationName scheiterte nach $maxAttempts Versuchen",
                                ex,
                            )
                        }
                        throw ex
                    }

                    val delay = baseDelay * attempt.toLong()
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw ie
                        }
                    }

                    attempt += 1
                }
            }

            throw lastException ?: IllegalStateException("$operationName schlug fehl")
        }

        private fun isRetryableException(exception: Exception): Boolean {
            if (exception is RetryableHttpException) {
                return true
            }

            if (exception is SocketTimeoutException || exception is ConnectException) {
                return true
            }

            if (exception is SocketException || exception is InterruptedIOException) {
                return true
            }

            if (exception is IOException) {
                val message = exception.message?.lowercase(Locale.getDefault()) ?: return false
                return message.contains("timeout") || message.contains("connection") || message.contains("network")
            }

            return false
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
        return query.split('&')
            .mapNotNull { pair ->
                if (!pair.contains('=')) return@mapNotNull null
                val (key, value) = pair.split('=', limit = 2)
                key to decodeQueryValue(value)
            }.toMap()
    }

    private fun decodeQueryValue(value: String): String {
        val decodedUtf8 = URLDecoder.decode(value, StandardCharsets.UTF_8)
        if (!enableLegacyEncodingFix) {
            return decodedUtf8
        }

        val repairedUtf8 = maybeRepairUmlauts(decodedUtf8)
        if (!repairedUtf8.contains('\uFFFD')) {
            return repairedUtf8
        }

        val decodedIso = URLDecoder.decode(value, StandardCharsets.ISO_8859_1)
        val repairedIso = maybeRepairUmlauts(decodedIso)
        val isoHasNoReplacement = !repairedIso.contains('\uFFFD')
        val isoHasMoreUmlauts = repairedIso.count { it in umlautChars } > repairedUtf8.count { it in umlautChars }
        if (isoHasNoReplacement && (decodedUtf8.contains('\uFFFD') || isoHasMoreUmlauts)) {
            if (repairedIso != decodedUtf8) {
                logger.warn("ISO-8859-1 Fallback für Query-Wert angewendet: '{}' -> '{}'", decodedUtf8, repairedIso)
            }
            return repairedIso
        }

        return repairedUtf8
    }

    private val umlautChars = setOf('Ä', 'Ö', 'Ü', 'ä', 'ö', 'ü', 'ß')

    private fun maybeRepairUmlauts(input: String): String {
        if (input.isEmpty()) return input
        // Falls Replacement Character vorhanden, versuchen wir heuristische Reparatur.
        var value = input
        val suspicious = value.contains('\uFFFD') || value.contains("Ã")
        if (!suspicious) return value

        // Schritt 1: Spezifische häufige UTF-8-doppelt-decoding Artefakte ersetzen.
        val map = mapOf(
            "Ã„" to "Ä",
            "Ã–" to "Ö",
            "Ãœ" to "Ü",
            "Ã¤" to "ä",
            "Ã¶" to "ö",
            "Ã¼" to "ü",
            "ÃŸ" to "ß",
        )
        for ((bad, good) in map) {
            if (value.contains(bad)) value = value.replace(bad, good)
        }

        // Schritt 2: Falls weiterhin Replacement Characters existieren → Versuch Reinterpretation (ISO-8859-1 Bytes als UTF-8 lesen)
        if (value.contains('\uFFFD')) {
            // Wir versuchen eine Roundtrip-Heuristik nur wenn ursprünglicher String ASCII + Replacement war
            val asciiLike = input.all { it.code < 128 || it == '\uFFFD' }
            if (asciiLike) {
                // Reinterpretiere ursprüngliche Bytes (so wie sie jetzt im JVM String gespeichert sind) als ISO-8859-1
                // Das ist eine grobe Heuristik – wenn dadurch mehr gültige Umlaute entstehen, übernehmen.
                val bytes = input.toByteArray(StandardCharsets.ISO_8859_1)
                val candidate = String(bytes, StandardCharsets.UTF_8)
                val gain = candidate.count { it in umlautChars } - input.count { it in umlautChars }
                if (gain > 0 && !candidate.contains('\uFFFD')) {
                    value = candidate
                }
            }
        }
        return value
    }

    private data class SemesterOption(val displayName: String)

    private data class FachSemesterOption(val value: String, val isPostRequired: Boolean)

    private data class StudyProgramOption(val code: String, val displayName: String, val faculty: String, val href: String) {
        val defaultFachSemester: String = "1.Semester"
    }

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

    private data class ScrapeStats(
        var totalEntries: Int = 0,
        var newEntries: Int = 0,
        var updatedEntries: Int = 0,
        var studyProgramsProcessed: Int = 0,
    ) {
        fun toResult(): ScrapingResult = ScrapingResult(
            totalEntries = totalEntries,
            newEntries = newEntries,
            updatedEntries = updatedEntries,
            studyProgramsProcessed = studyProgramsProcessed,
        )
    }
}

data class ScrapingResult(val totalEntries: Int, val newEntries: Int, val updatedEntries: Int, val studyProgramsProcessed: Int)

data class RemoteSemesterDescriptor(val displayName: String, val shortName: String)

data class ScrapingProgressSnapshot(
    val status: ScrapingStatus = ScrapingStatus.IDLE,
    val currentTask: String = "Bereit",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val progress: Int = 0,
    val message: String? = null,
    val logs: List<ScrapingLogEntry> = emptyList(),
    val subTasks: List<ScrapingSubTask> = emptyList(),
    val logCounts: Map<ScrapingLogLevel, Int> = emptyMap(),
)

data class ScrapingLogEntry(
    val id: Long,
    val level: ScrapingLogLevel,
    val message: String,
    val detail: String? = null,
    val timestamp: Instant = Instant.now(),
)

enum class ScrapingStatus {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
}

enum class ScrapingLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

private class RetryableHttpException(val statusCode: Int, message: String, cause: Throwable? = null) : IOException(message, cause)

data class ScrapingSubTask(
    val id: String,
    var label: String,
    var status: ScrapingStatus = ScrapingStatus.IDLE,
    var processed: Int = 0,
    var total: Int = 0,
    var progress: Int = 0,
    var message: String? = null,
    var startedAt: Instant? = null,
)

private class ScrapingProgressTracker {
    private val lock = Any()
    private var state = ScrapingProgressSnapshot()
    private val subTaskMap = linkedMapOf<String, ScrapingSubTask>()
    private var logSequence: Long = 0
    private val logCounters = EnumMap<ScrapingLogLevel, Int>(ScrapingLogLevel::class.java)

    init {
        resetLogStateLocked(resetSequence = true)
    }

    fun snapshot(): ScrapingProgressSnapshot = synchronized(lock) {
        state.copy(
            logs = state.logs.takeLast(MAX_LOGS),
            subTasks = subTaskMap.values.map { it.copy() },
            logCounts = snapshotLogCountsLocked(),
        )
    }

    fun start(totalCount: Int, task: String, message: String) {
        synchronized(lock) {
            resetLogStateLocked(resetSequence = true)
            subTaskMap.clear()
            val sanitizedTotal = if (totalCount < 0) 0 else totalCount
            val logs = appendLog(emptyList(), ScrapingLogLevel.INFO, message)
            state = ScrapingProgressSnapshot(
                status = ScrapingStatus.RUNNING,
                currentTask = task,
                processedCount = 0,
                totalCount = sanitizedTotal,
                progress = computeProgress(0, sanitizedTotal),
                message = message,
                logs = logs,
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun update(task: String?, processed: Int, total: Int?, message: String?) {
        synchronized(lock) {
            val newTotal = total ?: state.totalCount
            val newProgress = computeProgress(processed, newTotal)
            val updatedLogs =
                if (!message.isNullOrBlank()) appendLog(state.logs, ScrapingLogLevel.INFO, message) else state.logs
            state = state.copy(
                currentTask = task ?: state.currentTask,
                processedCount = processed,
                totalCount = newTotal,
                progress = newProgress,
                message = message ?: state.message,
                logs = updatedLogs,
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun log(level: ScrapingLogLevel, message: String, detail: String? = null) {
        synchronized(lock) {
            state = state.copy(
                message = message,
                logs = appendLog(state.logs, level, message, detail),
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun finish(message: String) {
        synchronized(lock) {
            state = state.copy(
                status = ScrapingStatus.COMPLETED,
                currentTask = "Bereit",
                processedCount = state.totalCount,
                progress = 100,
                message = message,
                logs = appendLog(state.logs, ScrapingLogLevel.INFO, message),
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun fail(message: String) {
        synchronized(lock) {
            state = state.copy(
                status = ScrapingStatus.FAILED,
                message = message,
                logs = appendLog(state.logs, ScrapingLogLevel.ERROR, message),
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun pause(message: String) {
        synchronized(lock) {
            state = state.copy(
                status = ScrapingStatus.PAUSED,
                message = message,
                logs = appendLog(state.logs, ScrapingLogLevel.WARN, message),
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    fun reset(message: String? = null) {
        synchronized(lock) {
            resetLogStateLocked(resetSequence = true)
            subTaskMap.clear()
            val updatedLogs =
                if (!message.isNullOrBlank()) {
                    appendLog(emptyList(), ScrapingLogLevel.INFO, message)
                } else {
                    emptyList()
                }
            state = ScrapingProgressSnapshot(
                status = ScrapingStatus.IDLE,
                currentTask = "Bereit",
                processedCount = 0,
                totalCount = 0,
                progress = 0,
                message = message,
                logs = updatedLogs,
                logCounts = snapshotLogCountsLocked(),
            )
        }
    }

    // ---- SubTask Handling ----
    fun initSubTasks(idsInOrder: List<String>, labels: List<String>) {
        synchronized(lock) {
            subTaskMap.clear()
            idsInOrder.forEachIndexed { idx, id ->
                val label = labels.getOrNull(idx) ?: id
                subTaskMap[id] = ScrapingSubTask(id = id, label = label)
            }
            updateAggregatedProgressLocked()
        }
    }

    fun startSubTask(id: String, total: Int) {
        synchronized(lock) {
            val st = subTaskMap[id] ?: return
            st.status = ScrapingStatus.RUNNING
            st.total = total
            st.startedAt = Instant.now()
            updateAggregatedProgressLocked()
        }
    }

    fun updateSubTask(id: String, processed: Int, total: Int? = null, message: String? = null) {
        synchronized(lock) {
            val st = subTaskMap[id] ?: return
            st.processed = processed
            if (total != null) {
                st.total = total
            }
            st.progress = computeProgress(st.processed, st.total)
            if (message != null) st.message = message
            updateAggregatedProgressLocked()
        }
    }

    fun completeSubTask(id: String, message: String? = null) {
        synchronized(lock) {
            val st = subTaskMap[id] ?: return
            st.processed = st.total
            st.progress = 100
            st.status = ScrapingStatus.COMPLETED
            if (message != null) st.message = message
            updateAggregatedProgressLocked()
        }
    }

    fun failSubTask(id: String, message: String?) {
        synchronized(lock) {
            val st = subTaskMap[id] ?: return
            st.status = ScrapingStatus.FAILED
            st.message = message
            updateAggregatedProgressLocked()
        }
    }

    private fun updateAggregatedProgressLocked() {
        if (subTaskMap.isEmpty()) return
        val list = subTaskMap.values.toList()
        val avgProgress = list.map { it.progress }.average().toInt()
        val processedSum = list.sumOf { it.processed }
        val totalSum = list.sumOf { it.total.coerceAtLeast(0) }
        val overallProgress =
            if (totalSum > 0) {
                ((processedSum.toDouble() / totalSum.toDouble()) * 100).toInt().coerceIn(0, 100)
            } else {
                avgProgress
            }
        state = state.copy(
            progress = overallProgress,
            processedCount = processedSum,
            totalCount = totalSum,
            logCounts = snapshotLogCountsLocked(),
        )
    }

    private fun appendLog(
        current: List<ScrapingLogEntry>,
        level: ScrapingLogLevel,
        message: String,
        detail: String? = null,
    ): List<ScrapingLogEntry> {
        incrementLogCounter(level)
        val sanitizedDetail = detail?.takeIf { it.isNotBlank() }?.let {
            if (it.length > MAX_LOG_DETAIL) it.take(MAX_LOG_DETAIL) + "…" else it
        }
        val entry = ScrapingLogEntry(
            id = ++logSequence,
            level = level,
            message = message,
            detail = sanitizedDetail,
        )
        return (current + entry).takeLast(MAX_LOGS)
    }

    private fun incrementLogCounter(level: ScrapingLogLevel) {
        val current = logCounters[level] ?: 0
        logCounters[level] = current + 1
    }

    private fun resetLogStateLocked(resetSequence: Boolean) {
        if (resetSequence) {
            logSequence = 0
        }
        logCounters.clear()
        enumValues<ScrapingLogLevel>().forEach { logCounters[it] = 0 }
    }

    private fun snapshotLogCountsLocked(): Map<ScrapingLogLevel, Int> =
        enumValues<ScrapingLogLevel>().associateWith { level -> logCounters[level] ?: 0 }

    companion object {
        private const val MAX_LOGS = 100
        private const val MAX_LOG_DETAIL = 5000

        private fun computeProgress(processed: Int, total: Int): Int {
            if (total <= 0) return 0
            val clamped = processed.coerceIn(0, total)
            return ((clamped.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
        }
    }
}
