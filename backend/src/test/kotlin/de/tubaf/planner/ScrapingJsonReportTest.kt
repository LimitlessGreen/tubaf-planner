package de.tubaf.planner

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tubaf.planner.service.SemesterService
import de.tubaf.planner.service.scraping.TubafScrapingService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
class ScrapingJsonReportTest {

    @Autowired private lateinit var tubafScrapingService: TubafScrapingService

    @Autowired private lateinit var semesterService: SemesterService

    @Value("\${tubaf.scraper.base-url:https://evlvz.hrz.tu-freiberg.de/~vover/}")
    private lateinit var baseUrl: String

    private val objectMapper =
        jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun generateScrapingReport() {
        val semesters = semesterService.getAllSemesters()
        val semesterReports = mutableListOf<Map<String, Any?>>()
        val failures = mutableListOf<String>()

        semesters.forEach { semester ->
            val start = Instant.now()
            val semesterReport =
                mutableMapOf<String, Any?>(
                    "id" to semester.id,
                    "name" to semester.name,
                    "shortName" to semester.shortName,
                    "startedAt" to start,
                )

            try {
                val result = tubafScrapingService.scrapeSemesterData(semester)
                val finishedAt = Instant.now()
                semesterReport +=
                    mapOf(
                        "status" to "SUCCESS",
                        "finishedAt" to finishedAt,
                        "durationMillis" to (finishedAt.toEpochMilli() - start.toEpochMilli()),
                        "result" to result,
                    )
            } catch (ex: Exception) {
                val finishedAt = Instant.now()
                semesterReport +=
                    mapOf(
                        "status" to "FAILED",
                        "finishedAt" to finishedAt,
                        "durationMillis" to (finishedAt.toEpochMilli() - start.toEpochMilli()),
                        "error" to
                            mapOf(
                                "type" to ex::class.qualifiedName,
                                "message" to (ex.message ?: "Unknown error"),
                            ),
                    )
                failures += "${semester.shortName}: ${ex.message ?: ex::class.qualifiedName}".trim()
            }

            semesterReports += semesterReport
        }

        val report =
            mapOf(
                "generatedAt" to Instant.now(),
                "baseUrl" to baseUrl,
                "semesterCount" to semesters.size,
                "semesters" to semesterReports,
            )

        val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)
        println("SCRAPING_REPORT_START")
        println(json)
        println("SCRAPING_REPORT_END")

        if (failures.isNotEmpty()) {
            fail("Scraping failed for semesters: ${failures.joinToString(", ")}")
        }
    }
}
