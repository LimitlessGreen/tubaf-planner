package de.tubaf.planner.service.scraping

import de.tubaf.planner.service.SemesterService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    name = ["tubaf.scraper.scheduling.enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class ScrapingSchedulerService(
    private val tubafScrapingService: TubafScrapingService,
    private val semesterService: SemesterService
) {

    private val logger = LoggerFactory.getLogger(ScrapingSchedulerService::class.java)

    /** Täglicher Scraping-Job um 02:00 Uhr */
    @Scheduled(cron = "0 0 2 * * *")
    fun scheduledDailyScraping() {
        logger.info("Starting scheduled daily scraping")

        try {
            val activeSemester = semesterService.getActiveSemester()
            if (activeSemester != null) {
                val result = tubafScrapingService.scrapeSemesterData(activeSemester)
                logger.info("Scheduled scraping completed: $result")
            } else {
                logger.warn("No active semester found for scheduled scraping")
            }
        } catch (e: Exception) {
            logger.error("Scheduled scraping failed", e)
        }
    }

    /** Wöchentlicher Vollscan am Sonntag um 01:00 Uhr */
    @Scheduled(cron = "0 0 1 * * SUN")
    fun scheduledWeeklyScraping() {
        logger.info("Starting scheduled weekly full scraping")

        try {
            val activeSemesters = semesterService.getActiveSemesters()
            for (semester in activeSemesters) {
                logger.info("Scraping semester: ${semester.shortName}")
                val result = tubafScrapingService.scrapeSemesterData(semester)
                logger.info("Weekly scraping for ${semester.shortName} completed: $result")

                // Delay between semesters
                Thread.sleep(5000)
            }
        } catch (e: Exception) {
            logger.error("Scheduled weekly scraping failed", e)
        }
    }

    /** Manueller Scraping-Trigger für alle aktiven Semester */
    fun triggerManualScraping(): List<ScrapingResult> {
        logger.info("Manual scraping triggered")

        val results = mutableListOf<ScrapingResult>()
        val activeSemesters = semesterService.getActiveSemesters()

        for (semester in activeSemesters) {
            try {
                logger.info("Manually scraping semester: ${semester.shortName}")
                val result = tubafScrapingService.scrapeSemesterData(semester)
                results.add(result)
                logger.info("Manual scraping for ${semester.shortName} completed: $result")
            } catch (e: Exception) {
                logger.error("Manual scraping failed for semester ${semester.shortName}", e)
            }
        }

        return results
    }
}
