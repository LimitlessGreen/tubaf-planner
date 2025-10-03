package de.tubaf.planner.service.scraping

import de.tubaf.planner.model.ScrapingStatus
import de.tubaf.planner.repository.ScrapingRunRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
@ConditionalOnClass(MeterRegistry::class)
class ScrapingMetricsService(private val meterRegistry: MeterRegistry, private val scrapingRunRepository: ScrapingRunRepository) {

    private val scrapingCounter: Counter =
        Counter.builder("tubaf.scraping.runs.total")
            .description("Total number of scraping runs")
            .register(meterRegistry)

    private val scrapingSuccessCounter: Counter =
        Counter.builder("tubaf.scraping.runs.success")
            .description("Number of successful scraping runs")
            .register(meterRegistry)

    private val scrapingFailureCounter: Counter =
        Counter.builder("tubaf.scraping.runs.failure")
            .description("Number of failed scraping runs")
            .register(meterRegistry)

    private val scrapingTimer: Timer =
        Timer.builder("tubaf.scraping.duration")
            .description("Duration of scraping runs")
            .register(meterRegistry)

    fun recordScrapingStart() {
        scrapingCounter.increment()
    }

    fun recordScrapingSuccess(durationMillis: Long) {
        scrapingSuccessCounter.increment()
        scrapingTimer.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun recordScrapingFailure(durationMillis: Long) {
        scrapingFailureCounter.increment()
        scrapingTimer.record(durationMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Gibt aktuelle Scraping-Metriken zurück */
    fun getScrapingMetrics(): ScrapingMetrics {
        val since = LocalDateTime.now().minusDays(30)
        val recentRuns =
            scrapingRunRepository.findByStatusesAndStartTimeAfter(
                listOf(ScrapingStatus.COMPLETED, ScrapingStatus.FAILED),
                since,
            )

        val successful = recentRuns.count { it.status == ScrapingStatus.COMPLETED }
        val failed = recentRuns.count { it.status == ScrapingStatus.FAILED }
        val totalRuns = recentRuns.size

        val avgDuration =
            recentRuns
                .filter { it.endTime != null }
                .map { ChronoUnit.MILLIS.between(it.startTime, it.endTime) }
                .average()
                .takeIf { !it.isNaN() } ?: 0.0

        return ScrapingMetrics(
            totalRuns = totalRuns,
            successfulRuns = successful,
            failedRuns = failed,
            successRate = if (totalRuns > 0) successful.toDouble() / totalRuns else 0.0,
            averageDurationMs = avgDuration.toLong(),
        )
    }
}

/** Scraping-Metriken DTO */
data class ScrapingMetrics(
    val totalRuns: Int,
    val successfulRuns: Int,
    val failedRuns: Int,
    val successRate: Double,
    val averageDurationMs: Long,
)
