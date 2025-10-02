package de.tubaf.planner.controller.api

import de.tubaf.planner.model.ScheduleEntry
import de.tubaf.planner.service.*
import de.tubaf.planner.service.scraping.TubafScrapingService
import de.tubaf.planner.service.scraping.ScrapingProgressSnapshot
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class ApiController(
    private val scheduleService: ScheduleService,
    private val scrapingService: TubafScrapingService,
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

    @PostMapping("/scraping/start")
    fun startScraping(
        @RequestParam(required = false) semesterId: Long?,
        @RequestParam(defaultValue = "incremental") mode: String,
        @RequestParam(defaultValue = "3") maxRetries: Int,
        @RequestParam(defaultValue = "true") enableNotifications: Boolean,
        @RequestParam(defaultValue = "true") saveChanges: Boolean
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val started = when {
                semesterId != null -> scrapingService.startLocalScrapingJob(semesterId)
                else -> scrapingService.startDiscoveryJob()
            }

            if (!started) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf("success" to false, "message" to "Bereits laufender Scraping-Prozess")
                )
            } else {
                ResponseEntity.ok(
                    mapOf(
                        "success" to true,
                        "message" to "Scraping gestartet"
                    )
                )
            }
        } catch (e: Exception) {
            ResponseEntity.ok(
                mapOf(
                    "success" to false,
                    "message" to (e.message ?: "Fehler beim Starten des Scrapings")
                )
            )
        }
    }

    @PostMapping("/scraping/pause")
    fun pauseScraping(): ResponseEntity<ScrapingProgressSnapshot> {
        scrapingService.pauseScraping()
        return ResponseEntity.ok(scrapingService.getProgressSnapshot())
    }

    @PostMapping("/scraping/stop")
    fun stopScraping(): ResponseEntity<ScrapingProgressSnapshot> {
        scrapingService.stopScraping()
        return ResponseEntity.ok(scrapingService.getProgressSnapshot())
    }

    @GetMapping("/scraping/status")
    fun getScrapingStatus(): ResponseEntity<ScrapingProgressSnapshot> {
        return ResponseEntity.ok(scrapingService.getProgressSnapshot())
    }

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
