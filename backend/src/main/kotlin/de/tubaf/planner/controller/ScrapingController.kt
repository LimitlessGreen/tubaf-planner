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
    private val scrapingConfiguration: de.tubaf.planner.config.ScrapingConfiguration,
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

    // ----- Parallel Config Endpoints -----
    data class ScrapingConfigDTO(
        val parallelEnabled: Boolean,
        val parallelMaxWorkers: Int,
        val parallelSessionPoolSize: Int,
        val parallelInterTaskDelay: Long,
    )

    @GetMapping("/config")
    fun getConfig(): ResponseEntity<ScrapingConfigDTO> =
        ResponseEntity.ok(
            ScrapingConfigDTO(
                parallelEnabled = scrapingConfiguration.parallelEnabled,
                parallelMaxWorkers = scrapingConfiguration.parallelMaxWorkers,
                parallelSessionPoolSize = scrapingConfiguration.parallelSessionPoolSize,
                parallelInterTaskDelay = scrapingConfiguration.parallelInterTaskDelay,
            )
        )

    @PostMapping("/config")
    fun updateConfig(@RequestBody req: ScrapingConfigDTO): ResponseEntity<ScrapingConfigDTO> {
        // Einfache Validierung / Begrenzung
        scrapingConfiguration.parallelEnabled = req.parallelEnabled
        scrapingConfiguration.parallelMaxWorkers = req.parallelMaxWorkers.coerceIn(1, 32)
        scrapingConfiguration.parallelSessionPoolSize = req.parallelSessionPoolSize.coerceIn(1, 32)
        scrapingConfiguration.parallelInterTaskDelay = req.parallelInterTaskDelay.coerceAtLeast(0)
        return getConfig()
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

    @GetMapping("/available-semesters")
    @Operation(summary = "Get semesters available on the TUBAF website")
    fun getAvailableSemesters(): ResponseEntity<List<RemoteSemesterDescriptor>> {
        return try {
            ResponseEntity.ok(tubafScrapingService.getAvailableRemoteSemesters())
        } catch (e: Exception) {
            logger.error("Fehler beim Laden der verfügbaren Semester", e)
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/remote-semesters")
    @Operation(summary = "Get semesters available on the TUBAF website (deprecated, use /available-semesters)")
    @Deprecated("Use /available-semesters instead")
    fun getRemoteSemesters(): ResponseEntity<List<RemoteSemesterDescriptor>> {
        return getAvailableSemesters()
    }

    @PostMapping("/scrape-semesters")
    @Operation(summary = "Scrape specific semesters from TUBAF website")
    fun scrapeSemesters(@RequestBody request: SemesterScrapeRequest): ResponseEntity<Map<String, Any>> {
        if (request.semesterIdentifiers.isEmpty()) {
            return ResponseEntity.badRequest().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to "Es wurden keine Semester angegeben"
                )
            )
        }

        return try {
            val started = tubafScrapingService.startRemoteScrapingJob(request.semesterIdentifiers)
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
            logger.warn("Ungültige Semesterangabe beim Scraping", e)
            ResponseEntity.badRequest().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to (e.message ?: "Ungültige Semesterangabe")
                )
            )
        } catch (e: Exception) {
            logger.error("Fehler beim Scraping", e)
            ResponseEntity.internalServerError().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to (e.message ?: "Unbekannter Fehler")
                )
            )
        }
    }

    @PostMapping("/scrape-remote")
    @Operation(summary = "Scrape specific semesters (deprecated, use /scrape-semesters)")
    @Deprecated("Use /scrape-semesters instead")
    fun scrapeRemoteSemesters(@RequestBody request: RemoteScrapeRequest): ResponseEntity<Map<String, Any>> {
        return scrapeSemesters(SemesterScrapeRequest(request.semesters))
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

    @GetMapping("/status")
    @Operation(summary = "Get current scraping status")
    fun getStatus(): ResponseEntity<Map<String, Any>> {
        val isRunning = tubafScrapingService.isJobRunning()
        val progress = tubafScrapingService.getProgressSnapshot()
        
        val response = mutableMapOf<String, Any>()
        response["status"] = if (isRunning) "running" else "idle"
        response["progress"] = progress.progress
        response["message"] = progress.message ?: progress.currentTask
        response["currentTask"] = progress.currentTask
        response["processedCount"] = progress.processedCount
        response["totalCount"] = progress.totalCount
        
        if (progress.logs.isNotEmpty()) {
            response["logs"] = progress.logs.takeLast(20).map {
                mapOf(
                    "level" to it.level,
                    "message" to it.message,
                    "timestamp" to it.timestamp
                )
            }
        }
        if (progress.subTasks.isNotEmpty()) {
            response["subTasks"] = progress.subTasks.map { st ->
                mapOf(
                    "id" to st.id,
                    "label" to st.label,
                    "status" to st.status.name.lowercase(),
                    "processed" to st.processed,
                    "total" to st.total,
                    "progress" to st.progress,
                    "message" to st.message,
                    "startedAt" to st.startedAt
                )
            }
        }
        
        return ResponseEntity.ok(response)
    }

    @PostMapping("/cancel")
    @Operation(summary = "Cancel running scraping job")
    fun cancelScraping(): ResponseEntity<Map<String, Any>> {
        logger.info("Cancellation requested - interrupting scraping thread")
        
        return try {
            // TODO: Implement proper cancellation mechanism in TubafScrapingService
            // Currently relying on Thread.interrupt() mechanism
            ResponseEntity.ok(
                mapOf<String, Any>(
                    "success" to false, 
                    "message" to "Cancellation noch nicht implementiert - bitte Backend erweitern"
                )
            )
        } catch (e: Exception) {
            logger.error("Error cancelling scraping", e)
            ResponseEntity.internalServerError().body(
                mapOf<String, Any>("success" to false, "message" to (e.message ?: "Unbekannter Fehler"))
            )
        }
    }

    @PostMapping("/debug-scrape")
    @Operation(summary = "Debug endpoint: Scrape and wait for completion, then return all logs")
    fun debugScrape(@RequestBody request: SemesterScrapeRequest): ResponseEntity<Map<String, Any>> {
        logger.info("🐛 Debug scraping gestartet für: {}", request.semesterIdentifiers)
        
        if (request.semesterIdentifiers.isEmpty()) {
            return ResponseEntity.badRequest().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to "Es wurden keine Semester angegeben"
                )
            )
        }

        return try {
            val started = tubafScrapingService.startRemoteScrapingJob(request.semesterIdentifiers)
            if (!started) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    mapOf<String, Any>(
                        "success" to false,
                        "message" to "Bereits laufender Scraping-Prozess"
                    )
                )
            }
            
            // Warte auf Completion (max 5 Minuten)
            var waitTime = 0
            val maxWaitTime = 300000 // 5 Minuten in ms
            val pollInterval = 500L // 500ms
            
            while (tubafScrapingService.isJobRunning() && waitTime < maxWaitTime) {
                Thread.sleep(pollInterval)
                waitTime += pollInterval.toInt()
            }
            
            // Hole finalen Status
            val progress = tubafScrapingService.getProgressSnapshot()
            
            val response = mutableMapOf<String, Any>()
            response["success"] = true
            response["completed"] = !tubafScrapingService.isJobRunning()
            response["status"] = progress.status.toString()
            response["progress"] = progress.progress
            response["message"] = progress.message ?: progress.currentTask
            response["currentTask"] = progress.currentTask
            response["processedCount"] = progress.processedCount
            response["totalCount"] = progress.totalCount
            response["allLogs"] = progress.logs.map {
                mapOf(
                    "level" to it.level,
                    "message" to it.message,
                    "timestamp" to it.timestamp
                )
            }
            
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.error("Debug scraping failed", e)
            ResponseEntity.internalServerError().body(
                mapOf<String, Any>(
                    "success" to false,
                    "message" to (e.message ?: "Unbekannter Fehler")
                )
            )
        }
    }
}

data class SemesterScrapeRequest(val semesterIdentifiers: List<String> = emptyList())

data class RemoteScrapeRequest(val semesters: List<String> = emptyList())
