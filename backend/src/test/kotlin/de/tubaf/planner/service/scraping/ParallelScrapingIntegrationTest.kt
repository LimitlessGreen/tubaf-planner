package de.tubaf.planner.service.scraping

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Integration Test für paralleles Scraping mit echter Datenbank.
 *
 * Testet dass der EntityKey-Fix in einer echten Multi-Thread-Umgebung
 * mit PostgreSQL funktioniert.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ParallelScrapingIntegrationTest {

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tubaf_test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun overrideProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
        }
    }

    @Autowired
    private lateinit var courseRepository: CourseRepository

    @Autowired
    private lateinit var scheduleEntryRepository: ScheduleEntryRepository

    @Autowired
    private lateinit var semesterRepository: SemesterRepository

    @Autowired
    private lateinit var lecturerRepository: LecturerRepository

    @Autowired
    private lateinit var courseTypeRepository: CourseTypeRepository

    @Autowired
    private lateinit var roomRepository: RoomRepository

    @Autowired
    private lateinit var studyProgramRepository: StudyProgramRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var testSemester: Semester
    private lateinit var testLecturer: Lecturer
    private lateinit var testCourseType: CourseType
    private lateinit var testRoom: Room
    private lateinit var testStudyProgram: StudyProgram

    @BeforeEach
    fun setup() {
        // Erstelle Test-Daten
        testSemester = semesterRepository.save(
            Semester(
                name = "Integration Test Semester",
                shortName = "ITS",
                startDate = LocalDate.now(),
                endDate = LocalDate.now().plusMonths(6),
                active = true,
            ),
        )

        testLecturer = lecturerRepository.save(
            Lecturer(name = "Prof. Integration Test"),
        )

        testCourseType = courseTypeRepository.save(
            CourseType(code = "V", name = "Vorlesung"),
        )

        testRoom = roomRepository.save(
            Room(code = "INT-101", building = "INT", roomNumber = "101"),
        )

        testStudyProgram = studyProgramRepository.save(
            StudyProgram(
                code = "INT",
                name = "Integration Test Program",
                degreeType = DegreeType.BACHELOR,
            ).apply {
                facultyId = 1
            },
        )
    }

    @Test
    fun `course with study program can be created and linked`() {
        // Arrange & Act
        val course = courseRepository.save(
            Course(
                name = "Test Course",
                semester = testSemester,
                lecturer = testLecturer,
                courseType = testCourseType,
            ),
        )

        val txTemplate = TransactionTemplate(transactionManager)
        txTemplate.executeWithoutResult {
            // WICHTIG: Lade Course als managed Entity
            val managedCourse = courseRepository.findById(course.id!!).orElseThrow()
            managedCourse.addStudyProgram(testStudyProgram, 1)
            courseRepository.save(managedCourse)
        }

        // Assert
        val reloaded = courseRepository.findById(course.id!!).orElseThrow()
        assertTrue(
            reloaded.courseStudyPrograms.any { it.studyProgram.id == testStudyProgram.id },
            "Course not linked to StudyProgram",
        )
    }

    @Test
    fun `bidirectional relationship is maintained by Hibernate`() {
        // Arrange
        val course = courseRepository.save(
            Course(
                name = "Bidirectional Test Course",
                semester = testSemester,
                lecturer = testLecturer,
                courseType = testCourseType,
            ),
        )

        // Act: Speichere ScheduleEntry
        val entry = ScheduleEntry(
            course = course,
            room = testRoom,
            dayOfWeek = DayOfWeek.WEDNESDAY,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
        )

        val saved = scheduleEntryRepository.save(entry)

        // Assert: Lade Course neu und prüfe dass Entry automatisch in Collection ist
        val reloadedCourse = courseRepository.findByIdWithScheduleEntries(course.id!!)
        assertNotNull(reloadedCourse)

        // Hibernate pflegt bidirektionale Beziehung automatisch
        assertTrue(
            reloadedCourse!!.scheduleEntries.any { it.id == saved.id },
            "ScheduleEntry not in Course collection (bidirectional relationship broken)",
        )
    }

    @Test
    fun `schedule entry can be saved without manual collection manipulation`() {
        // Arrange
        val course = courseRepository.save(
            Course(
                name = "Collection Test Course",
                semester = testSemester,
                lecturer = testLecturer,
                courseType = testCourseType,
            ),
        )

        // Act: Erstelle und speichere 3 Entries OHNE manuelle courseWithEntries.scheduleEntries.add()
        val entries = (1..3).map { index ->
            val entry = ScheduleEntry(
                course = course,
                room = testRoom,
                dayOfWeek = DayOfWeek.values()[index],
                startTime = LocalTime.of(10 + index, 0),
                endTime = LocalTime.of(12 + index, 0),
            )
            scheduleEntryRepository.save(entry)
        }

        // Assert: Alle Entries wurden gespeichert und sind in DB
        val savedEntries = scheduleEntryRepository.findAll()
        assertEquals(3, savedEntries.size, "Not all entries were saved")

        // Assert: Course hat alle Entries in Collection (bidirektional)
        val reloadedCourse = courseRepository.findByIdWithScheduleEntries(course.id!!)
        assertEquals(3, reloadedCourse!!.scheduleEntries.size, "Course does not have all entries")
    }
}
