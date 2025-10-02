package de.tubaf.planner

import de.tubaf.planner.service.scraping.TubafScrapingService
import de.tubaf.planner.service.SemesterService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ScrapingTest {

    @Autowired
    private lateinit var tubafScrapingService: TubafScrapingService

    @Autowired  
    private lateinit var semesterService: SemesterService

    @Test
    fun testTubafScraping() {
        println("🔍 Testing TUBAF Scraping...")
        
        // Finde das erste verfügbare Semester
        val semesters = semesterService.getAllSemesters()
        println("📅 Available semesters: ${semesters.map { it.shortName }}")
        
        if (semesters.isNotEmpty()) {
            val testSemester = semesters.first()
            println("🎯 Testing scraping for semester: ${testSemester.shortName}")
            
            try {
                val result = tubafScrapingService.scrapeSemesterData(testSemester)
                println("✅ Scraping completed successfully!")
                println("   - Total entries: ${result.totalEntries}")
                println("   - New entries: ${result.newEntries}")
                println("   - Updated entries: ${result.updatedEntries}")
                println("   - Study programs processed: ${result.studyProgramsProcessed}")
            } catch (e: Exception) {
                println("❌ Scraping failed: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("⚠️ No semesters found for testing")
        }
    }
}