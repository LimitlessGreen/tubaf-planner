package de.tubaf.planner.controller.web

import de.tubaf.planner.model.ScheduleEntry
import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.time.DayOfWeek
import java.time.LocalTime

@Controller
@RequestMapping("/schedule")
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val semesterService: SemesterService,
    private val studyProgramService: StudyProgramService,
    private val roomService: RoomService,
) {

    @GetMapping
    fun scheduleOverview(model: Model): String {
        model.addAttribute("semesters", semesterService.getActiveSemesters())
        model.addAttribute("studyPrograms", studyProgramService.getActiveStudyPrograms())
        model.addAttribute("activePage", "schedule")
        return "schedule/overview"
    }

    @GetMapping("/semester/{semesterId}")
    fun semesterSchedule(
        @PathVariable semesterId: Long,
        @RequestParam(required = false) studyProgramId: Long?,
        @RequestParam(required = false) courseType: String?,
        @RequestParam(required = false) daysOfWeek: List<String>?,
        @RequestParam(required = false) timeFrom: String?,
        @RequestParam(required = false) timeTo: String?,
        model: Model,
    ): String {
        val semester =
            semesterService.getAllSemesters().find { it.id == semesterId }
                ?: return "redirect:/schedule"

        val availableStudyPrograms = studyProgramService.getActiveStudyPrograms()
        val studyProgram = studyProgramId?.let { id -> availableStudyPrograms.find { it.id == id } }
        val baseEntries =
            if (studyProgram != null) {
                scheduleService.getScheduleForStudyProgram(studyProgram.id!!, semesterId)
            } else {
                scheduleService.getScheduleForSemester(semesterId)
            }

        val selectedDays = daysOfWeek?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet()
        val fromTime = parseTime(timeFrom)
        val toTime = parseTime(timeTo)

        val filteredEntries =
            baseEntries.filter { entry ->
                val matchesCourseType = courseType.isNullOrBlank() || entry.course.courseType.code.equals(courseType, true)
                val matchesDay = selectedDays.isNullOrEmpty() || selectedDays.contains(entry.dayOfWeek)
                val startTime = normalizeTime(entry.startTime)
                val endTime = normalizeTime(entry.endTime)
                val matchesFrom = fromTime == null || !startTime.isBefore(fromTime)
                val matchesTo = toTime == null || !endTime.isAfter(toTime)

                matchesCourseType && matchesDay && matchesFrom && matchesTo
            }

        val sortedEntries =
            filteredEntries.sortedWith(compareBy<ScheduleEntry> { it.dayOfWeek.value }.thenBy { it.startTime }.thenBy { it.course.name })

        val timeSlots = buildTimeSlots(sortedEntries)
        val scheduleGrid = buildScheduleGrid(sortedEntries)
        val conflictingEntryIds = findConflictingEntryIds(sortedEntries)
        val scheduleStats = calculateScheduleStats(sortedEntries, conflictingEntryIds)

        model.addAttribute("semester", semester)
        model.addAttribute("studyProgram", studyProgram)
        model.addAttribute("selectedStudyProgram", studyProgramId)
        model.addAttribute("availableStudyPrograms", availableStudyPrograms)
        model.addAttribute("scheduleEntries", sortedEntries)
        model.addAttribute("timeSlots", timeSlots)
        model.addAttribute("scheduleGrid", scheduleGrid)
        model.addAttribute("scheduleStats", scheduleStats)
        model.addAttribute("conflictingEntryIds", conflictingEntryIds)
        model.addAttribute("studyProgramNotFound", studyProgramId != null && studyProgram == null)
        model.addAttribute("activePage", "schedule")

        return "schedule/semester"
    }

    @GetMapping("/room/{roomId}")
    fun roomSchedule(@PathVariable roomId: Long, @RequestParam(required = false) semesterId: Long?, model: Model): String {
        val rooms = roomService.getActiveRooms()
        val room = rooms.find { it.id == roomId } ?: return "redirect:/schedule"

        val activeSemesters = semesterService.getActiveSemesters()
        val currentSemester =
            semesterId?.let { id -> activeSemesters.find { it.id == id } }
                ?: activeSemesters.firstOrNull()

        val utilization =
            currentSemester?.id?.let { currentSemesterId ->
                roomService.getRoomUtilization(roomId, currentSemesterId)
            }

        // Wochentage für Detailansicht
        val daySchedules =
            if (currentSemester != null) {
                DayOfWeek.entries.associateWith { day ->
                    scheduleService.getRoomScheduleForDay(roomId, day)
                }
            } else {
                emptyMap()
            }

        model.addAttribute("room", room)
        model.addAttribute("rooms", rooms)
        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("currentSemester", currentSemester)
        model.addAttribute("utilization", utilization)
        model.addAttribute("daySchedules", daySchedules)
        model.addAttribute("activePage", "schedule")

        return "schedule/room"
    }

    @GetMapping("/lecturer/{lecturerId}")
    fun lecturerSchedule(@PathVariable lecturerId: Long, @RequestParam(required = false) semesterId: Long?, model: Model): String {
        // Ähnlich wie roomSchedule, aber für Dozenten
        val activeSemesters = semesterService.getActiveSemesters()
        val currentSemester =
            semesterId?.let { id -> activeSemesters.find { it.id == id } }
                ?: activeSemesters.firstOrNull()

        currentSemester?.id?.let { currentSemesterId ->
            val lecturerSchedule =
                scheduleService.getScheduleForLecturer(lecturerId, currentSemesterId)
            val conflicts = scheduleService.checkLecturerConflicts(lecturerId, currentSemesterId)

            model.addAttribute("lecturerSchedule", lecturerSchedule)
            model.addAttribute("conflicts", conflicts)
        }

        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("currentSemester", currentSemester)
        model.addAttribute("lecturerId", lecturerId)
        model.addAttribute("activePage", "schedule")

        return "schedule/lecturer"
    }
}

private fun parseTime(value: String?): LocalTime? = value
    ?.takeIf { it.isNotBlank() }
    ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    ?.let { normalizeTime(it) }

private fun normalizeTime(time: LocalTime): LocalTime = time.withSecond(0).withNano(0)

private fun buildTimeSlots(entries: List<ScheduleEntry>): List<LocalTime> {
    val defaultSlots =
        generateSequence(LocalTime.of(7, 30)) { it.plusMinutes(30) }
            .takeWhile { it <= LocalTime.of(19, 30) }
            .map { normalizeTime(it) }
            .toMutableSet()

    entries.map { normalizeTime(it.startTime) }.forEach { defaultSlots.add(it) }

    return defaultSlots.toList().sorted()
}

private fun buildScheduleGrid(entries: List<ScheduleEntry>): Map<String, List<ScheduleEntry>> {
    val grid = mutableMapOf<String, MutableList<ScheduleEntry>>()

    entries.forEach { entry ->
        val key = "${entry.dayOfWeek.value}-${normalizeTime(entry.startTime)}"
        grid.computeIfAbsent(key) { mutableListOf() }.add(entry)
    }

    return grid
}

private fun findConflictingEntryIds(entries: List<ScheduleEntry>): Set<Long> {
    val conflictingIds = mutableSetOf<Long>()

    for (i in entries.indices) {
        for (j in i + 1 until entries.size) {
            val first = entries[i]
            val second = entries[j]

            if (first.overlaps(second)) {
                first.id?.let { conflictingIds.add(it) }
                second.id?.let { conflictingIds.add(it) }
            }
        }
    }

    return conflictingIds
}

private fun calculateScheduleStats(entries: List<ScheduleEntry>, conflictingEntryIds: Set<Long>): ScheduleStats {
    val totalEntries = entries.size
    val totalCourses = entries.mapNotNull { it.course.id }.toSet().size
    val totalHours = entries.sumOf { it.duration } / 60.0

    return ScheduleStats(
        totalEntries = totalEntries,
        totalCourses = totalCourses,
        conflicts = conflictingEntryIds.size,
        totalHours = totalHours,
    )
}

data class ScheduleStats(val totalEntries: Int, val totalCourses: Int, val conflicts: Int, val totalHours: Double)
