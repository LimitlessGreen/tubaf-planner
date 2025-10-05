package de.tubaf.planner.controller.web

import de.tubaf.planner.service.*
import de.tubaf.planner.service.scraping.ScrapingStatus
import de.tubaf.planner.service.scraping.TubafScrapingService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/data")
class DataController(
    private val semesterService: SemesterService,
    private val changeTrackingService: ChangeTrackingService,
    private val tubafScrapingService: TubafScrapingService,
    private val studyProgramService: StudyProgramService,
    private val roomService: RoomService,
) {

    @GetMapping
    fun dataOverview(model: Model): String {
        val activeSemesters = semesterService.getActiveSemesters()

        model.addAttribute("semesters", activeSemesters)

        // Letzte Scraping-Läufe für jedes Semester
        val lastRuns =
            activeSemesters.associateWith { semester ->
                changeTrackingService.getLastSuccessfulRun(semester.id!!)
            }
        model.addAttribute("lastRuns", lastRuns)

        return "data/overview"
    }

    @GetMapping("/scraping")
    fun scrapingManagement(@RequestParam(required = false) semesterId: Long?, model: Model): String {
        val activeSemesters = semesterService.getActiveSemesters()
        val currentSemester =
            semesterId?.let { id -> activeSemesters.find { it.id == id } }
                ?: activeSemesters.firstOrNull()

        val scrapingRuns =
            if (currentSemester != null) {
                changeTrackingService.getScrapingRunsForSemester(currentSemester.id!!)
            } else {
                emptyList()
            }

        val progress = tubafScrapingService.getProgressSnapshot()

        // Statistiken für alle Semester berechnen
        val allSemesters = semesterService.getAllSemesters()
        val allRuns = allSemesters.mapNotNull { semester ->
            changeTrackingService.getLastSuccessfulRun(semester.id!!)
        }
        val totalEntries = allRuns.sumOf { it.totalEntries ?: 0 }
        val globalLastRun = allRuns.maxByOrNull { it.startTime }

        model.addAttribute("semesters", activeSemesters)
        model.addAttribute("availableSemesters", allSemesters)
        model.addAttribute("currentSemester", currentSemester)
        model.addAttribute("scrapingRuns", scrapingRuns)
        model.addAttribute("logs", progress.logs)
        model.addAttribute("scrapingActive", progress.status == ScrapingStatus.RUNNING)

        // Statistik-Karten Werte
        model.addAttribute("totalSemesters", allSemesters.size)
        model.addAttribute("successfulRuns", allRuns.size)
        model.addAttribute("totalEntries", totalEntries)
        model.addAttribute("lastRun", globalLastRun)

        // Bisherige Detail-Statistiken für aktuelles Semester
        model.addAttribute("failedRuns", scrapingRuns.count { it.status.name == "FAILED" })
        model.addAttribute("overallProgress", progress.progress)
        model.addAttribute("currentTask", progress.message ?: progress.currentTask)
        model.addAttribute("processedCount", progress.processedCount)
        model.addAttribute("totalCount", progress.totalCount)

        return "data/scraping"
    }

    @PostMapping("/scraping/run/{semesterId}")
    fun runScraping(@PathVariable semesterId: Long, redirectAttributes: RedirectAttributes): String = try {
        val semester =
            semesterService.getAllSemesters().find { it.id == semesterId }
                ?: throw IllegalArgumentException("Semester not found")

        val result = tubafScrapingService.scrapeSemesterData(semester)

        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Scraping erfolgreich: ${result.totalEntries} Einträge verarbeitet (${result.newEntries} neu, ${result.updatedEntries} aktualisiert)",
        )

        "redirect:/data/scraping?semesterId=$semesterId"
    } catch (e: Exception) {
        redirectAttributes.addFlashAttribute(
            "errorMessage",
            "Scraping fehlgeschlagen: ${e.message}",
        )
        "redirect:/data/scraping"
    }

    @GetMapping("/scraping/run/{runId}")
    fun scrapingRunDetail(@PathVariable runId: Long, model: Model): String {
        val run = changeTrackingService.getScrapingRun(runId)
        val changes = changeTrackingService.getChangesForScrapingRun(runId)
        val statistics = changeTrackingService.getChangeStatistics(runId)

        model.addAttribute("run", run)
        model.addAttribute("semester", run.semester)
        model.addAttribute("changes", changes)
        model.addAttribute("changeStatistics", statistics)

        return "data/scraping-detail"
    }

    @GetMapping("/semesters")
    fun semesterManagement(model: Model): String {
        val allSemesters = semesterService.getAllSemesters()

        model.addAttribute("semesters", allSemesters)

        return "data/semesters"
    }

    @GetMapping("/study-programs")
    fun studyProgramManagement(model: Model): String {
        val studyPrograms = studyProgramService.getActiveStudyPrograms()
        val stats = studyProgramService.getStudyProgramStatistics()

        model.addAttribute("studyPrograms", studyPrograms)
        model.addAttribute("statistics", stats)

        return "data/study-programs"
    }

    @GetMapping("/rooms")
    fun roomManagement(model: Model): String {
        val rooms = roomService.getActiveRooms()
        val stats = roomService.getRoomStatistics()

        model.addAttribute("rooms", rooms)
        model.addAttribute("statistics", stats)

        return "data/rooms"
    }
}
