package de.tubaf.planner.controller

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import de.tubaf.planner.service.ChangeTrackingService
import de.tubaf.planner.service.scraping.ScrapingResult
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.DayOfWeek
import java.time.LocalTime

@RestController
@RequestMapping("/api/test")
class TestController(
    private val semesterRepository: SemesterRepository,
    private val courseRepository: CourseRepository,
    private val lecturerRepository: LecturerRepository,
    private val roomRepository: RoomRepository,
    private val courseTypeRepository: CourseTypeRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val changeTrackingService: ChangeTrackingService,
) {

    private val logger = LoggerFactory.getLogger(TestController::class.java)

    @PostMapping("/create-sample-data/{semesterId}")
    fun createSampleData(@PathVariable semesterId: Long): ResponseEntity<ScrapingResult> {
        logger.info("🧪 Creating sample scraping data for semester $semesterId")

        val semester =
            semesterRepository.findById(semesterId).orElse(null)
                ?: return ResponseEntity.notFound().build()

        try {
            val scrapingRun = changeTrackingService.startScrapingRun(semesterId, "test-manual")

            // Erstelle Test-Kurstypen
            val vorlesung =
                courseTypeRepository.save(
                    CourseType(code = "V", name = "Vorlesung", description = "Vorlesung"),
                )

            val uebung =
                courseTypeRepository.save(
                    CourseType(code = "Ü", name = "Übung", description = "Übung"),
                )

            val praktikum =
                courseTypeRepository.save(
                    CourseType(code = "P", name = "Praktikum", description = "Praktikum"),
                )

            // Erstelle Test-Dozenten
            val prof1 =
                lecturerRepository.save(
                    Lecturer(
                        name = "Prof. Dr. Hans Algorithmus",
                        title = "Prof. Dr.",
                        department = "Institut für Informatik",
                        email = "hans.algorithmus@tu-freiberg.de",
                    ),
                )

            val prof2 =
                lecturerRepository.save(
                    Lecturer(
                        name = "Dr. Maria Matrix",
                        title = "Dr.",
                        department = "Institut für Mathematik",
                        email = "maria.matrix@tu-freiberg.de",
                    ),
                )

            val prof3 =
                lecturerRepository.save(
                    Lecturer(
                        name = "Prof. Dr. Otto Physikus",
                        title = "Prof. Dr.",
                        department = "Institut für Physik",
                        email = "otto.physikus@tu-freiberg.de",
                    ),
                )

            // Erstelle Test-Räume
            val room1 =
                roomRepository.save(
                    Room(
                        code = "MIB-1001",
                        building = "MIB",
                        roomNumber = "1001",
                        roomType = RoomType.LECTURE_HALL,
                        capacity = 150,
                        equipment = "Beamer, Whiteboard, Lautsprecher",
                    ),
                )

            val room2 =
                roomRepository.save(
                    Room(
                        code = "MIB-2012",
                        building = "MIB",
                        roomNumber = "2012",
                        roomType = RoomType.COMPUTER_ROOM,
                        capacity = 30,
                        equipment = "30 Computer, Beamer",
                    ),
                )

            val room3 =
                roomRepository.save(
                    Room(
                        code = "PHY-0101",
                        building = "PHY",
                        roomNumber = "0101",
                        roomType = RoomType.LAB,
                        capacity = 20,
                        equipment = "Laborausstattung, Mikroskope",
                    ),
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
                        ectsCredits = 6.0,
                        description =
                        "Grundlagen der Informatik: Sortieralgorithmen, Bäume, Graphen",
                    ),
                )

            val kurs2 =
                courseRepository.save(
                    Course(
                        name = "Übung zu Algorithmen und Datenstrukturen",
                        courseNumber = "320302",
                        semester = semester,
                        lecturer = prof1,
                        courseType = uebung,
                        sws = 2,
                        ectsCredits = 0.0,
                        description = "Praktische Übungen zu Algorithmen",
                    ),
                )

            val kurs3 =
                courseRepository.save(
                    Course(
                        name = "Lineare Algebra I",
                        courseNumber = "110201",
                        semester = semester,
                        lecturer = prof2,
                        courseType = vorlesung,
                        sws = 4,
                        ectsCredits = 8.0,
                        description = "Vektorräume, Matrizen, lineare Gleichungssysteme",
                    ),
                )

            val kurs4 =
                courseRepository.save(
                    Course(
                        name = "Physik I - Mechanik",
                        courseNumber = "130101",
                        semester = semester,
                        lecturer = prof3,
                        courseType = vorlesung,
                        sws = 3,
                        ectsCredits = 5.0,
                        description = "Klassische Mechanik, Newton'sche Gesetze",
                    ),
                )

            val kurs5 =
                courseRepository.save(
                    Course(
                        name = "Praktikum Physik I",
                        courseNumber = "130102",
                        semester = semester,
                        lecturer = prof3,
                        courseType = praktikum,
                        sws = 2,
                        ectsCredits = 2.0,
                        description = "Experimente zur Mechanik",
                    ),
                )

            // Erstelle Termineinträge
            val termine =
                listOf(
                    // Algorithmen Vorlesung: Di 8:00-10:00, Fr 10:00-12:00
                    ScheduleEntry(
                        course = kurs1,
                        room = room1,
                        dayOfWeek = DayOfWeek.TUESDAY,
                        startTime = LocalTime.of(8, 0),
                        endTime = LocalTime.of(10, 0),
                        weekPattern = "wöchentlich",
                    ),
                    ScheduleEntry(
                        course = kurs1,
                        room = room1,
                        dayOfWeek = DayOfWeek.FRIDAY,
                        startTime = LocalTime.of(10, 0),
                        endTime = LocalTime.of(12, 0),
                        weekPattern = "wöchentlich",
                    ),

                    // Algorithmen Übung: Do 14:00-16:00
                    ScheduleEntry(
                        course = kurs2,
                        room = room2,
                        dayOfWeek = DayOfWeek.THURSDAY,
                        startTime = LocalTime.of(14, 0),
                        endTime = LocalTime.of(16, 0),
                        weekPattern = "wöchentlich",
                    ),

                    // Lineare Algebra: Mo 10:00-12:00, Mi 8:00-10:00
                    ScheduleEntry(
                        course = kurs3,
                        room = room1,
                        dayOfWeek = DayOfWeek.MONDAY,
                        startTime = LocalTime.of(10, 0),
                        endTime = LocalTime.of(12, 0),
                        weekPattern = "wöchentlich",
                    ),
                    ScheduleEntry(
                        course = kurs3,
                        room = room1,
                        dayOfWeek = DayOfWeek.WEDNESDAY,
                        startTime = LocalTime.of(8, 0),
                        endTime = LocalTime.of(10, 0),
                        weekPattern = "wöchentlich",
                    ),

                    // Physik Vorlesung: Mi 12:00-14:00
                    ScheduleEntry(
                        course = kurs4,
                        room = room1,
                        dayOfWeek = DayOfWeek.WEDNESDAY,
                        startTime = LocalTime.of(12, 0),
                        endTime = LocalTime.of(14, 0),
                        weekPattern = "wöchentlich",
                        notes = "Anwesenheitspflicht",
                    ),

                    // Physik Praktikum: Fr 14:00-16:00 (14-täglich)
                    ScheduleEntry(
                        course = kurs5,
                        room = room3,
                        dayOfWeek = DayOfWeek.FRIDAY,
                        startTime = LocalTime.of(14, 0),
                        endTime = LocalTime.of(16, 0),
                        weekPattern = "14-täglich",
                        notes = "Labormantel erforderlich",
                    ),
                )

            termine.forEach { scheduleEntryRepository.save(it) }

            // Tracke die Änderungen
            listOf(kurs1, kurs2, kurs3, kurs4, kurs5).forEach {
                changeTrackingService.logEntityCreated(
                    scrapingRun.id!!,
                    "Course",
                    it.id!!,
                    "Test course created",
                )
            }

            // Beende den Scraping-Run erfolgreich
            changeTrackingService.completeScrapingRun(
                scrapingRun.id!!,
                totalEntries = 5,
                newEntries = 5,
                updatedEntries = 0,
            )

            logger.info("✅ Sample data created: 5 courses, ${termine.size} schedule entries")

            return ResponseEntity.ok(
                ScrapingResult(
                    totalEntries = 5,
                    newEntries = 5,
                    updatedEntries = 0,
                    studyProgramsProcessed = 1,
                ),
            )
        } catch (e: Exception) {
            logger.error("❌ Failed to create sample data", e)
            return ResponseEntity.internalServerError().build()
        }
    }
}
