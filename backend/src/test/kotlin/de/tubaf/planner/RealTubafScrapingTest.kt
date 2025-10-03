package de.tubaf.planner

import de.tubaf.planner.service.SemesterService
import de.tubaf.planner.service.scraping.TubafScrapingService
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
class RealTubafScrapingTest {

    @Autowired private lateinit var tubafScrapingService: TubafScrapingService

    @Autowired private lateinit var semesterService: SemesterService

    @Disabled("Requires access to the live TUBAF website")
    @Test
    fun testRealTubafScraping() {
        println("🔍 Testing REAL TUBAF Scraping against live website...")

        // Erstelle ein aktuelles Semester für den Test (ohne DevDataInitializer)
        val savedSemester =
            semesterService.createSemester(
                name = "Wintersemester 2025 / 2026",
                shortName = "WS2526",
                startDate = LocalDate.of(2025, 10, 1),
                endDate = LocalDate.of(2026, 3, 31),
                active = true,
            )
        println("📅 Created test semester: ${savedSemester.shortName}")

        try {
            println("🌐 Starting scraping against REAL TUBAF website...")
            val result = tubafScrapingService.scrapeSemesterData(savedSemester)

            println("✅ REAL Scraping completed successfully!")
            println("   📊 Results:")
            println("   - Total entries processed: ${result.totalEntries}")
            println("   - New entries created: ${result.newEntries}")
            println("   - Existing entries updated: ${result.updatedEntries}")
            println("   - Study programs processed: ${result.studyProgramsProcessed}")

            // Validiere dass echte Daten gefunden wurden
            if (result.totalEntries > 0) {
                println("🎉 SUCCESS: Found real data from TUBAF website!")
                println("   - Courses found: ${result.totalEntries}")
            } else {
                println("⚠️ WARNING: No data found - check if TUBAF website is accessible")
            }
        } catch (e: Exception) {
            println("❌ REAL Scraping failed: ${e.message}")
            println("   💡 This might indicate:")
            println("   - Kein Zugriff auf die TUBAF-Website möglich")
            println("   - Website-Struktur könnte sich geändert haben")
            println("   - oder es liegen Netzwerkprobleme vor")
            e.printStackTrace()
            throw e // Re-throw to fail the test if scraping fails
        }
    }

    @Disabled("Requires access to the live TUBAF website")
    @Test
    fun testTubafWebsiteAccessibility() {
        println("🌐 Testing TUBAF website accessibility...")

        try {
            // Teste nur den Zugriff auf die Website ohne Daten zu scrapen
            // Verwende ein Semester mit realistischem Namen, damit der Aufruf funktioniert
            val savedSemester =
                semesterService.createSemester(
                    name = "Wintersemester 2025 / 2026",
                    shortName = "WS2526",
                    startDate = LocalDate.of(2025, 10, 1),
                    endDate = LocalDate.of(2026, 3, 31),
                    active = false,
                )

            val result = tubafScrapingService.discoverAndScrapeAvailableSemesters()

            println("✅ Website is accessible!")
            println("   - Semesters discovered: ${result.size}")
            println("   - Total entries scraped: ${result.sumOf { it.totalEntries }}")
        } catch (e: Exception) {
            println("❌ Website accessibility test failed: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
