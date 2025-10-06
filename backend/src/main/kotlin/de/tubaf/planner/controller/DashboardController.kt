package de.tubaf.planner.controller

import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/dashboard")
class DashboardController(
    private val semesterService: SemesterService,
    private val courseService: CourseService,
    private val studyProgramService: StudyProgramService,
    private val roomService: RoomService,
    private val changeTrackingService: ChangeTrackingService,
) {

    @GetMapping
    fun dashboard(model: Model): String {
        val activeSemester = semesterService.getActiveSemester()
        val allSemesters = semesterService.getAllSemesters()

        // Statistics
        val studyProgramStats = studyProgramService.getStudyProgramStatistics()
        val roomStats = roomService.getRoomStatistics()

        model.addAttribute("activeSemester", activeSemester)
        model.addAttribute("allSemesters", allSemesters)
        model.addAttribute("studyProgramStats", studyProgramStats)
        model.addAttribute("roomStats", roomStats)
        model.addAttribute("activePage", "dashboard")

        // Recent changes
        activeSemester?.let { semester ->
            val semesterId = semester.id ?: return@let
            val recentRuns = changeTrackingService.getScrapingRunsForSemester(semesterId).take(5)
            model.addAttribute("recentScrapingRuns", recentRuns)

            val courses = courseService.getCoursesBySemester(semesterId)
            model.addAttribute("totalCourses", courses.size)
        }

        return "dashboard"
    }
}
