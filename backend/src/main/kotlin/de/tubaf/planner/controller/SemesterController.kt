package de.tubaf.planner.controller

import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/semesters")
class SemesterController(
    private val semesterService: SemesterService,
    private val courseService: CourseService,
    private val scheduleService: ScheduleService,
    private val changeTrackingService: ChangeTrackingService,
) {

    @GetMapping
    fun listSemesters(model: Model): String {
        model.addAttribute("semesters", semesterService.getAllSemesters())
        return "semesters/list"
    }

    @GetMapping("/{id}")
    fun semesterDetail(@PathVariable id: Long, model: Model): String {
        val semesters = semesterService.getAllSemesters()
        val semester =
            semesters.find { it.id == id } ?: throw IllegalArgumentException("Semester not found")

        val courses = courseService.getCoursesBySemester(id)
        val scheduleEntries = scheduleService.getScheduleBySemester(id)
        val scrapingRuns = changeTrackingService.getScrapingRunsForSemester(id)

        // Group schedule entries by day
        val scheduleByDay = scheduleEntries.groupBy { it.dayOfWeek }

        // Statistics
        val coursesByType = courses.groupBy { it.courseType.code }
        val totalSws = courses.mapNotNull { it.sws }.sum()

        model.addAttribute("semester", semester)
        model.addAttribute("courses", courses)
        model.addAttribute("scheduleByDay", scheduleByDay)
        model.addAttribute("scrapingRuns", scrapingRuns)
        model.addAttribute("coursesByType", coursesByType)
        model.addAttribute("totalSws", totalSws)

        return "semesters/detail"
    }

    @PostMapping("/{id}/activate")
    fun activateSemester(@PathVariable id: Long, redirectAttributes: RedirectAttributes): String {
        try {
            semesterService.activateSemester(id)
            redirectAttributes.addFlashAttribute("success", "Semester erfolgreich aktiviert")
        } catch (e: Exception) {
            redirectAttributes.addFlashAttribute("error", "Fehler beim Aktivieren: ${e.message}")
        }
        return "redirect:/semesters/$id"
    }
}
