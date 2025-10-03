package de.tubaf.planner.controller.web

import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/")
class HomeController(
    private val semesterService: SemesterService,
    private val courseService: CourseService,
    private val scheduleService: ScheduleService,
    private val changeTrackingService: ChangeTrackingService,
) {

    @GetMapping
    fun dashboard(model: Model): String {
        // Dashboard-Statistiken
        val activeSemesters = semesterService.getActiveSemesters()
        val currentSemester = semesterService.getActiveSemester()

        model.addAttribute("activeSemesters", activeSemesters)
        model.addAttribute("currentSemester", currentSemester)

        // Schnelle Statistiken
        currentSemester?.id?.let { semesterId ->
            val courses = courseService.getCoursesBySemester(semesterId)
            val recentChanges =
                changeTrackingService.getRecentChanges(java.time.LocalDateTime.now().minusDays(7))

            model.addAttribute("totalCourses", courses.size)
            model.addAttribute("recentChanges", recentChanges.size)
            model.addAttribute(
                "lastScrapingRun",
                changeTrackingService.getLastSuccessfulRun(semesterId),
            )
        }

        return "dashboard"
    }

    @GetMapping("/about")
    fun about(): String = "about"
}
