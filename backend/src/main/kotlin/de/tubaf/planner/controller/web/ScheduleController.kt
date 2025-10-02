package de.tubaf.planner.controller.web

import de.tubaf.planner.service.*
import java.time.DayOfWeek
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/schedule")
class ScheduleController(
    private val scheduleService: ScheduleService,
    private val semesterService: SemesterService,
    private val studyProgramService: StudyProgramService,
    private val roomService: RoomService
) {

    @GetMapping
    fun scheduleOverview(model: Model): String {
        model.addAttribute("semesters", semesterService.getActiveSemesters())
        model.addAttribute("studyPrograms", studyProgramService.getActiveStudyPrograms())
        return "schedule/overview"
    }

    @GetMapping("/semester/{semesterId}")
    fun semesterSchedule(
        @PathVariable semesterId: Long,
        @RequestParam(required = false) studyProgramId: Long?,
        model: Model
    ): String {
        val semester =
            semesterService.getAllSemesters().find { it.id == semesterId }
                ?: return "redirect:/schedule"

        val scheduleEntries =
            if (studyProgramId != null) {
                scheduleService.getScheduleForStudyProgram(studyProgramId, semesterId)
            } else {
                scheduleService.getScheduleForSemester(semesterId)
            }

        // Gruppiere nach Wochentag für Tabellenansicht
        val scheduleByDay = scheduleEntries.groupBy { it.dayOfWeek }
        val sortedDays = DayOfWeek.entries

        model.addAttribute("semester", semester)
        model.addAttribute("scheduleByDay", scheduleByDay)
        model.addAttribute("sortedDays", sortedDays)
        model.addAttribute("studyPrograms", studyProgramService.getActiveStudyPrograms())
        model.addAttribute("selectedStudyProgram", studyProgramId)

        return "schedule/semester"
    }

    @GetMapping("/room/{roomId}")
    fun roomSchedule(
        @PathVariable roomId: Long,
        @RequestParam(required = false) semesterId: Long?,
        model: Model
    ): String {
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
            } else emptyMap()

        model.addAttribute("room", room)
        model.addAttribute("rooms", rooms)
        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("currentSemester", currentSemester)
        model.addAttribute("utilization", utilization)
        model.addAttribute("daySchedules", daySchedules)

        return "schedule/room"
    }

    @GetMapping("/lecturer/{lecturerId}")
    fun lecturerSchedule(
        @PathVariable lecturerId: Long,
        @RequestParam(required = false) semesterId: Long?,
        model: Model
    ): String {
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

        return "schedule/lecturer"
    }
}
