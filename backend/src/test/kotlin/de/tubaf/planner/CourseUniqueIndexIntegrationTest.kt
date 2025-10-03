package de.tubaf.planner

import de.tubaf.planner.model.Course
import de.tubaf.planner.model.CourseType
import de.tubaf.planner.model.Lecturer
import de.tubaf.planner.model.Semester
import de.tubaf.planner.repository.CourseRepository
import de.tubaf.planner.repository.CourseTypeRepository
import de.tubaf.planner.repository.LecturerRepository
import de.tubaf.planner.repository.SemesterRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate

@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres-int")
class CourseUniqueIndexIntegrationTest {

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tubaf_planner_int")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        @DynamicPropertySource
        fun overrideProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var courseRepository: CourseRepository

    @Autowired lateinit var semesterRepository: SemesterRepository

    @Autowired lateinit var lecturerRepository: LecturerRepository

    @Autowired lateinit var courseTypeRepository: CourseTypeRepository

    private lateinit var semester: Semester
    private lateinit var lecturer: Lecturer
    private lateinit var courseType: CourseType

    @BeforeEach
    fun setup() {
        semester = semesterRepository.save(
            Semester(
                name = "Winter 25/26",
                shortName = "Wi25",
                startDate = LocalDate.of(2025, 10, 1),
                endDate = LocalDate.of(2026, 3, 31),
                active = true,
            ),
        )
        lecturer = lecturerRepository.save(Lecturer(name = "Prof. Test"))
        courseType = courseTypeRepository.save(CourseType(code = "VOR", name = "Vorlesung"))
    }

    @Test
    @Transactional
    fun `creating two courses differing only by case should violate unique functional index`() {
        val c1 = Course(
            name = "Lineare Algebra",
            courseNumber = "MATH101",
            semester = semester,
            lecturer = lecturer,
            courseType = courseType,
        )
        courseRepository.saveAndFlush(c1)

        val c2 = Course(
            name = "lineare algebra", // nur Kleinschreibung
            courseNumber = "MATH101B",
            semester = semester,
            lecturer = lecturer,
            courseType = courseType,
        )

        val ex = assertThrows<Exception> { courseRepository.saveAndFlush(c2) }
        // Erwartung: irgendeine Constraint-Verletzung aus der DB (PSQLException) enthalten
        val message = ex.rootCauseMessage()
        assertTrue(message.contains("ux_courses_semester_lower_name", ignoreCase = true) || message.contains("unique", ignoreCase = true))
    }
}

private fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}

private fun Throwable.rootCauseMessage(): String = rootCause().message.orEmpty()
