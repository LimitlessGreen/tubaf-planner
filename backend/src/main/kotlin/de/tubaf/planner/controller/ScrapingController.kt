package de.tubaf.planner.controller

import de.tubaf.planner.service.ChangeTrackingService
import de.tubaf.planner.service.SemesterService
import de.tubaf.planner.service.scraping.TubafScrapingService
import de.tubaf.planner.service.scraping.RemoteSemesterDescriptor
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/scraping")
@Tag(name = "Scraping", description = "TUBAF Web Scraping Management")
class ScrapingController(
    private val tubafScrapingService: TubafScrapingService,
    private val semesterService: SemesterService,
    private val changeTrackingService: ChangeTrackingService,
) {

    private val logger = LoggerFactory.getLogger(ScrapingController::class.java)

    @GetMapping
    @Operation(summary = "Overview of local and remote scraping information")
    fun scrapingOverview(): ResponseEntity<Map<String, Any>> {
        val activeSemesters = semesterService.getActiveSemesters()
        val remoteSemesters = runCatching { tubafScrapingService.getAvailableRemoteSemesters() }.getOrElse { emptyList() }
        val runs = activeSemesters.associate { semester ->
            semester.id!! to changeTrackingService.getLastSuccessfulRun(semester.id!!)
        }

        val response = mutableMapOf<String, Any>()
        response["success"] = true
        response["activeSemesterCount"] = activeSemesters.size
        response["activeSemesters"] = activeSemesters.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "shortName" to it.shortName,
                "startDate" to it.startDate,
                "endDate" to it.endDate,
                "active" to it.active
            )
        }
        response["remoteSemesters"] = remoteSemesters
        response["lastRuns"] = runs.mapValues { entry ->
            entry.value?.let {
                mapOf(
                    "id" to it.id,
                    "status" to it.status,
                    "startTime" to it.startTime,
                    "endTime" to it.endTime,
                    "totalEntries" to it.totalEntries,
                    "newEntries" to it.newEntries,
                    "updatedEntries" to it.updatedEntries
                )
            }
        }

        return ResponseEntity.ok(response)
    }

    @PostMapping("/semester/{semesterId}/scrape")
    @Operation(summary = "Scrape TUBAF data for specific semester")
    fun scrapeSemester(
        @Parameter(description = "Semester ID to scrape") @PathVariable semesterId: Long
    ): ResponseEntity<Map<String, Any>> {
        logger.info("Manual scraping requested for semester ID: $semesterId")

        semesterService.getAllSemesters().find { it.id == semesterId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("success" to false, "message" to "Semester wurde nicht gefunden")
            )

        return try {
            val started = tubafScrapingService.startLocalScrapingJob(semesterId)
            if (!started) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf("success" to false, "message" to "Bereits laufender Scraping-Prozess")
                )
            } else {
                ResponseEntity.ok(mapOf("success" to true, "message" to "Scraping gestartet"))
            }
        } catch (e: Exception) {
            logger.error("Scraping failed for semester $semesterId", e)
            ResponseEntity.internalServerError().body(
                mapOf("success" to false, "message" to (e.message ?: "Unbekannter Fehler"))
            )
        }
    }

    @PostMapping("/scrape-all")
    @Operation(summary = "Scrape TUBAF data for all active semesters")
    fun scrapeAllSemesters(): ResponseEntity<Map<String, Any>> {
        logger.info("Manual scraping requested for all active semesters")

        return try {
            val started = tubafScrapingService.startDiscoveryJob()
            if (!started) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf("success" to false, "message" to "Bereits laufender Scraping-Prozess")
                )
            } else {
                ResponseEntity.ok(mapOf("success" to true, "message" to "Scraping gestartet"))
            }
        } catch (e: Exception) {
            logger.error("Scraping failed for all semesters", e)
            ResponseEntity.internalServerError().body(
                mapOf("success" to false, "message" to (e.message ?: "Unbekannter Fehler"))
            )
        }
    }

    @GetMapping("/semester/{semesterId}/runs")
    @Operation(summary = "Get scraping runs for semester")
    fun getScrapingRuns(@Parameter(description = "Semester ID") @PathVariable semesterId: Long) =
        ResponseEntity.ok(changeTrackingService.getScrapingRunsForSemester(semesterId))

    @GetMapping("/runs/{runId}/changes")
    @Operation(summary = "Get changes for scraping run")
    fun getScrapingRunChanges(
        @Parameter(description = "Scraping run ID") @PathVariable runId: Long
    ) = ResponseEntity.ok(changeTrackingService.getChangesForScrapingRun(runId))

    @GetMapping("/runs/{runId}/statistics")
    @Operation(summary = "Get change statistics for scraping run")
    fun getScrapingRunStatistics(
        @Parameter(description = "Scraping run ID") @PathVariable runId: Long
    ) = ResponseEntity.ok(changeTrackingService.getChangeStatistics(runId))

    @GetMapping("/semester/{semesterId}/last-successful")
    @Operation(summary = "Get last successful scraping run for semester")
    fun getLastSuccessfulRun(
        @Parameter(description = "Semester ID") @PathVariable semesterId: Long
    ) = ResponseEntity.ok(changeTrackingService.getLastSuccessfulRun(semesterId))

    @GetMapping("/remote-semesters")
    @Operation(summary = "Get semesters available on the TUBAF website")
    fun getRemoteSemesters(): ResponseEntity<List<RemoteSemesterDescriptor>> {
        return try {
            ResponseEntity.ok(tubafScrapingService.getAvailableRemoteSemesters())
        } catch (e: Exception) {
            logger.error("Fehler beim Laden der verfügbaren Semester", e)
            ResponseEntity.internalServerError().build()
        }
    }

    @PostMapping("/scrape-remote")
    @Operation(summary = "Scrape specific semesters discovered on the TUBAF website")
    fun scrapeRemoteSemesters(@RequestBody request: RemoteScrapeRequest): ResponseEntity<Map<String, Any>> {
        if (request.semesters.isEmpty()) {
            return ResponseEntity.badRequest().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to "Es wurden keine Semester angegeben"
                )
            )
        }

        return try {
            if (request.semesters.isNullOrEmpty()) {
                return ResponseEntity.badRequest().body(
                    mapOf<String, Any>(
                        "success" to false,
                        "message" to "Es wurden keine Semester angegeben"
                    )
                )
            }

            val started = tubafScrapingService.startRemoteScrapingJob(request.semesters)
            if (!started) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf<String, Any>(
                        "success" to false,
                        "message" to "Bereits laufender Scraping-Prozess"
                    )
                )
            } else {
                ResponseEntity.ok(mapOf<String, Any>("success" to true, "message" to "Scraping gestartet"))
            }
        } catch (e: IllegalArgumentException) {
            logger.warn("Ungültige Semesterangabe beim Remote-Scraping", e)
            ResponseEntity.badRequest().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to (e.message ?: "Ungültige Semesterangabe")
                )
            )
        } catch (e: Exception) {
            logger.error("Fehler beim Remote-Scraping", e)
            ResponseEntity.internalServerError().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to (e.message ?: "Unbekannter Fehler")
                )
            )
        }
    }

    @PostMapping("/discover-and-scrape")
    @Operation(summary = "Discover available semesters and scrape TUBAF data automatically")
    fun discoverAndScrape(): ResponseEntity<Map<String, Any>> {
        logger.info("🧪 Discovery and scraping started - will find and scrape all available semesters")
        
        return try {
            val started = tubafScrapingService.startDiscoveryJob()
            if (!started) {
                ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf<String, Any>(
                        "success" to false,
                        "message" to "Bereits laufender Scraping-Prozess"
                    )
                )
            } else {
                ResponseEntity.ok(mapOf<String, Any>("success" to true, "message" to "Scraping gestartet"))
            }
        } catch (e: Exception) {
            logger.error("❌ Discovery and scraping failed", e)
            ResponseEntity.internalServerError().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to "Discovery and scraping failed: ${e.message}"
                )
            )
        }
    }
}

data class RemoteScrapeRequest(val semesters: List<String> = emptyList())
