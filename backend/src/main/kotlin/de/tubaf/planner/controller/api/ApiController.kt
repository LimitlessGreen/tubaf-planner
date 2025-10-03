package de.tubaf.planner.controller.api

import de.tubaf.planner.model.ScheduleEntry
import de.tubaf.planner.service.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class ApiController(
    private val scheduleService: ScheduleService,
    private val semesterService: SemesterService,
    private val courseService: CourseService
) {

    @GetMapping("/schedule/entry/{id}")
    fun getScheduleEntry(@PathVariable id: Long): ResponseEntity<ScheduleEntry> {
        // TODO: Implement getScheduleEntryById in service
        return ResponseEntity.notFound().build()
    }

    @GetMapping("/schedule/export")
    fun exportSchedule(
        @RequestParam(required = false) semesterId: Long?,
        @RequestParam(required = false) studyProgramId: Long?,
        @RequestParam(defaultValue = "pdf") format: String
    ): ResponseEntity<ByteArray> {
        // TODO: Implement schedule export
        return ResponseEntity.ok()
            .header("Content-Type", "application/pdf")
            .header("Content-Disposition", "attachment; filename=\"schedule.$format\"")
            .body(ByteArray(0))
    }

    // Scraping endpoints moved to ScrapingController for better organization

    @GetMapping("/stats/dashboard")
    fun getDashboardStats(): ResponseEntity<Map<String, Any>> {
        val activeSemesters = semesterService.getActiveSemesters()

        return ResponseEntity.ok(
            mapOf(
                "activeSemesters" to activeSemesters.size,
                "totalCourses" to 0,
                "recentChanges" to 0,
                "lastScrapingRun" to ""
            )
        )
    }
}
