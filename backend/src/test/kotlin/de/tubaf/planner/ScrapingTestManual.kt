package de.tubaf.planner

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import de.tubaf.planner.service.ChangeTrackingService
import java.time.DayOfWeek
import java.time.LocalTime
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Einfacher manueller Test für das Scraping-System ohne Playwright Erstellt Test-Daten um zu
 * zeigen, dass das System funktioniert
 */
@Component
@Profile("manual-test")
class ScrapingTestManual(
    private val semesterRepository: SemesterRepository,
    private val courseRepository: CourseRepository,
    private val lecturerRepository: LecturerRepository,
    private val roomRepository: RoomRepository,
    private val courseTypeRepository: CourseTypeRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val changeTrackingService: ChangeTrackingService
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(ScrapingTestManual::class.java)

    override fun run(vararg args: String?) {
        logger.info("🎯 Starting manual scraping test...")

        val semester = semesterRepository.findAll().firstOrNull { it.active == true }

        if (semester == null) {
            logger.warn("❌ No active semester found!")
            return
        }

        logger.info("✅ Found active semester: ${semester.shortName}")

        // Starte einen Test-Scraping-Run
        val scrapingRun = changeTrackingService.startScrapingRun(semester.id!!, "manual-test")

        try {
            // Erstelle Test-Daten als würden sie gescrapt werden
            createTestScrapingData(semester, scrapingRun.id!!)

            // Beende den Scraping-Run erfolgreich
            changeTrackingService.completeScrapingRun(
                scrapingRun.id!!,
                newEntries = 3,
                updatedEntries = 0,
                totalEntries = 3
            )

            logger.info("🎉 Manual scraping test completed successfully!")
            logger.info("📊 Created 3 test courses with schedule entries")
        } catch (e: Exception) {
            logger.error("💥 Manual scraping test failed", e)
            changeTrackingService.failScrapingRun(scrapingRun.id!!, e.message ?: "Unknown error")
        }
    }

    private fun createTestScrapingData(semester: Semester, scrapingRunId: Long) {
        logger.info("📝 Creating test scraping data...")

        // Erstelle Test-Kurstypen
        val vorlesung =
            courseTypeRepository.save(
                CourseType(code = "V", name = "Vorlesung", description = "Vorlesung")
            )

        val uebung =
            courseTypeRepository.save(CourseType(code = "Ü", name = "Übung", description = "Übung"))

        // Erstelle Test-Dozenten
        val prof1 =
            lecturerRepository.save(
                Lecturer(
                    name = "Prof. Dr. Max Mustermann",
                    title = "Prof. Dr.",
                    department = "Fakultät für Mathematik und Informatik",
                    email = "max.mustermann@tu-freiberg.de"
                )
            )

        val prof2 =
            lecturerRepository.save(
                Lecturer(
                    name = "Dr. Anna Schmidt",
                    title = "Dr.",
                    department = "Fakultät für Chemie und Physik",
                    email = "anna.schmidt@tu-freiberg.de"
                )
            )

        // Erstelle Test-Räume
        val room1 =
            roomRepository.save(
                Room(
                    code = "MIB-1001",
                    building = "MIB",
                    roomNumber = "1001",
                    roomType = RoomType.LECTURE_HALL,
                    capacity = 150
                )
            )

        val room2 =
            roomRepository.save(
                Room(
                    code = "CHE-2001",
                    building = "CHE",
                    roomNumber = "2001",
                    roomType = RoomType.SEMINAR_ROOM,
                    capacity = 30
                )
            )

        // Erstelle Test-Kurse
        val kurs1 =
            courseRepository.save(
                Course(
                    name = "Algorithmen und Datenstrukturen",
                    courseNumber = "320301",
                    semester = semester,
                    lecturer = prof1,
                    courseType = vorlesung,
                    sws = 4,
                    ectsCredits = 6.0
                )
            )

        val kurs2 =
            courseRepository.save(
                Course(
                    name = "Übung Algorithmen und Datenstrukturen",
                    courseNumber = "320302",
                    semester = semester,
                    lecturer = prof1,
                    courseType = uebung,
                    sws = 2,
                    ectsCredits = 0.0
                )
            )

        val kurs3 =
            courseRepository.save(
                Course(
                    name = "Einführung in die Chemie",
                    courseNumber = "150101",
                    semester = semester,
                    lecturer = prof2,
                    courseType = vorlesung,
                    sws = 3,
                    ectsCredits = 4.0
                )
            )

        // Erstelle Termineinträge
        scheduleEntryRepository.save(
            ScheduleEntry(
                course = kurs1,
                room = room1,
                dayOfWeek = DayOfWeek.TUESDAY,
                startTime = LocalTime.of(8, 0),
                endTime = LocalTime.of(10, 0),
                weekPattern = "wöchentlich"
            )
        )

        scheduleEntryRepository.save(
            ScheduleEntry(
                course = kurs2,
                room = room2,
                dayOfWeek = DayOfWeek.FRIDAY,
                startTime = LocalTime.of(10, 0),
                endTime = LocalTime.of(12, 0),
                weekPattern = "wöchentlich"
            )
        )

        scheduleEntryRepository.save(
            ScheduleEntry(
                course = kurs3,
                room = room1,
                dayOfWeek = DayOfWeek.MONDAY,
                startTime = LocalTime.of(14, 0),
                endTime = LocalTime.of(16, 0),
                weekPattern = "14-täglich"
            )
        )

        // Tracke die Änderungen
        changeTrackingService.logEntityCreated(scrapingRunId, "Course", kurs1.id!!, "Test course 1")
        changeTrackingService.logEntityCreated(scrapingRunId, "Course", kurs2.id!!, "Test course 2")
        changeTrackingService.logEntityCreated(scrapingRunId, "Course", kurs3.id!!, "Test course 3")

        logger.info("✅ Test data created successfully")
    }
}
