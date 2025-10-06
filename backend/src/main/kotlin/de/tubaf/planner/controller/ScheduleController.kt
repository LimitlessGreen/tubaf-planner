package de.tubaf.planner.controller

import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.time.DayOfWeek

@Controller("scheduleApiController")
@RequestMapping("/api/schedule")
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val semesterService: SemesterService,
    private val studyProgramService: StudyProgramService,
    private val roomService: RoomService,
) {

    @GetMapping
    fun scheduleOverview(model: Model): String {
        val activeSemester = semesterService.getActiveSemester()
        val allSemesters = semesterService.getAllSemesters()
        val studyPrograms = studyProgramService.getActiveStudyPrograms()

        model.addAttribute("activeSemester", activeSemester)
        model.addAttribute("allSemesters", allSemesters)
        model.addAttribute("studyPrograms", studyPrograms)
        model.addAttribute("activePage", "schedule")

        return "schedule/overview"
    }

    @GetMapping("/semester/{semesterId}")
    fun showSemesterSchedule(@PathVariable semesterId: Long, @RequestParam(required = false) studyProgramId: Long?, model: Model): String {
        val allSemesters = semesterService.getAllSemesters()
        val semester = allSemesters.find { it.id == semesterId } ?: return "redirect:/schedule"

        val scheduleEntries = scheduleService.getScheduleBySemester(semesterId)
        val allStudyPrograms = studyProgramService.getActiveStudyPrograms()
        val studyProgram = studyProgramId?.let { id -> allStudyPrograms.find { it.id == id } }

        model.addAttribute("semester", semester)
        model.addAttribute("studyProgram", studyProgram)
        model.addAttribute("scheduleEntries", scheduleEntries)
        model.addAttribute("allSemesters", allSemesters)
        model.addAttribute("allStudyPrograms", allStudyPrograms)
        model.addAttribute("activePage", "schedule")

        return "schedule/grid"
    }

    @GetMapping("/semester/{semesterId}/studyprogram/{studyProgramId}")
    fun studyProgramSchedule(@PathVariable semesterId: Long, @PathVariable studyProgramId: Long, model: Model): String {
        val semesters = semesterService.getAllSemesters()
        val semester =
            semesters.find { it.id == semesterId }
                ?: throw IllegalArgumentException("Semester not found")

        val studyPrograms = studyProgramService.getActiveStudyPrograms()
        val studyProgram =
            studyPrograms.find { it.id == studyProgramId }
                ?: throw IllegalArgumentException("StudyProgram not found")

        val scheduleEntries = scheduleService.getScheduleForStudyProgram(studyProgramId, semesterId)

        // Create weekly schedule grid
        val scheduleGrid = createScheduleGrid(scheduleEntries)

        model.addAttribute("semester", semester)
        model.addAttribute("studyProgram", studyProgram)
        model.addAttribute("scheduleEntries", scheduleEntries)
        model.addAttribute("scheduleGrid", scheduleGrid)
        model.addAttribute("allSemesters", semesters)
        model.addAttribute("allStudyPrograms", studyPrograms)
        model.addAttribute("activePage", "schedule")

        return "schedule/grid"
    }

    @GetMapping("/rooms")
    fun roomSchedules(@RequestParam(required = false) semester: Long?, model: Model): String {
        val allSemesters = semesterService.getActiveSemesters()
        val selectedSemester =
            semester?.let { id -> allSemesters.find { it.id == id } }
                ?: semesterService.getActiveSemester()

        // Dummy room utilization data for now
        val roomUtilization: List<RoomUtilizationData> = emptyList()

        model.addAttribute("roomUtilization", roomUtilization)
        model.addAttribute("semester", selectedSemester)
        model.addAttribute("allSemesters", allSemesters)
        model.addAttribute("activePage", "schedule")

        return "schedule/rooms"
    }

    private fun createScheduleGrid(scheduleEntries: List<de.tubaf.planner.model.ScheduleEntry>): Map<DayOfWeek, List<ScheduleGridEntry>> {
        val grid = mutableMapOf<DayOfWeek, MutableList<ScheduleGridEntry>>()

        // Initialize empty grid
        DayOfWeek.entries.forEach { day -> grid[day] = mutableListOf() }

        // Fill grid with entries
        scheduleEntries.forEach { entry ->
            grid[entry.dayOfWeek]?.add(
                ScheduleGridEntry(
                    startTime = entry.startTime,
                    endTime = entry.endTime,
                    courseName = entry.course.name,
                    courseType = entry.course.courseType.code,
                    lecturer = entry.course.lecturer.name,
                    room = "${entry.room.building}/${entry.room.roomNumber}",
                ),
            )
        }

        // Sort each day by start time
        grid.values.forEach { dayEntries -> dayEntries.sortBy { it.startTime } }

        return grid
    }
}

data class ScheduleGridEntry(
    val startTime: java.time.LocalTime,
    val endTime: java.time.LocalTime,
    val courseName: String,
    val courseType: String,
    val lecturer: String,
    val room: String,
)

data class RoomUtilizationData(
    val room: de.tubaf.planner.model.Room,
    val totalEntries: Int,
    val totalHours: Int,
    val utilizationPercentage: Double,
    val uniqueCourses: Int,
    val peakHours: List<Int>,
    val mostUsedDay: String?,
)
