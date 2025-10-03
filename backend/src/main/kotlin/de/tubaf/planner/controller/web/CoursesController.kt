package de.tubaf.planner.controller.web

import de.tubaf.planner.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/courses")
class CoursesController(
    private val courseService: CourseService,
    private val semesterService: SemesterService,
    private val studyProgramService: StudyProgramService,
) {

    @GetMapping
    fun coursesList(
        @RequestParam(required = false) semesterId: Long?,
        @RequestParam(required = false) studyProgramId: Long?,
        @RequestParam(required = false) search: String?,
        model: Model,
    ): String {
        val activeSemesters = semesterService.getActiveSemesters()
        val currentSemester =
            semesterId?.let { id -> activeSemesters.find { it.id == id } }
                ?: activeSemesters.firstOrNull()

        val courses =
            when {
                currentSemester == null -> emptyList()
                studyProgramId != null ->
                    courseService.getCoursesByStudyProgramAndSemester(
                        studyProgramId,
                        currentSemester.id!!,
                    )
                search != null && search.isNotBlank() ->
                    courseService.searchCourses(search, currentSemester.id!!)
                else -> courseService.getCoursesBySemester(currentSemester.id!!)
            }

        model.addAttribute("courses", courses)
        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("currentSemester", currentSemester)
        model.addAttribute("studyPrograms", studyProgramService.getActiveStudyPrograms())
        model.addAttribute("selectedStudyProgram", studyProgramId)
        model.addAttribute("search", search)

        return "courses/list"
    }

    @GetMapping("/{courseId}")
    fun courseDetail(@PathVariable courseId: Long, model: Model): String {
        val courses = courseService.getCoursesBySemester(1) // Temp - need to get by courseId
        val course = courses.find { it.id == courseId } ?: return "redirect:/courses"

        model.addAttribute("course", course)

        return "courses/detail"
    }

    @GetMapping("/stats")
    fun courseStats(model: Model): String {
        val activeSemesters = semesterService.getActiveSemesters()
        val studyProgramStats = studyProgramService.getStudyProgramStatistics()

        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("studyProgramStats", studyProgramStats)

        return "courses/stats"
    }
}
