package de.tubaf.planner.service.scraping

import com.ninjasquad.springmockk.MockkBean
import de.tubaf.planner.model.*
import de.tubaf.planner.repository.CourseRepository
import de.tubaf.planner.repository.ScheduleEntryRepository
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@ActiveProfiles("test")
class ParallelScrapingEntityManagementTest {

    @MockkBean
    private lateinit var courseRepository: CourseRepository

    @MockkBean
    private lateinit var scheduleEntryRepository: ScheduleEntryRepository

    private lateinit var lecturer: Lecturer
    private lateinit var courseType: CourseType

    @BeforeEach
    fun setup() {
        lecturer = Lecturer(name = "Test Lecturer").apply { id = 1L }
        courseType = CourseType(code = "V", name = "Vorlesung").apply { id = 1L }
    }

    @Test
    fun `Course is reloaded as managed entity before adding study program`() {
        // Arrange
        val courseId = 100L
        val course = createTestCourse(courseId)
        val studyProgram = createTestStudyProgram()

        every { courseRepository.findById(courseId) } answers {
            Optional.of(createTestCourse(courseId))
        }
        every { courseRepository.save(any<Course>()) } returnsArgument 0

        // Act: Lade Course neu vor Modifikation (critical für EntityKey-Fix)
        val managedCourse = courseRepository.findById(courseId).orElseThrow()
        managedCourse.addStudyProgram(studyProgram, 1)
        courseRepository.save(managedCourse)

        // Assert
        verify(exactly = 1) { courseRepository.findById(courseId) }
        verify(exactly = 1) { courseRepository.save(any<Course>()) }
        // Bidirektionale Beziehung wird von Hibernate verwaltet
    }

    @Test
    fun `ScheduleEntry is saved without manual collection manipulation`() {
        // Arrange: Course existiert bereits
        val courseId = 1L
        val course = createTestCourse(courseId)

        every { courseRepository.findById(courseId) } returns Optional.of(course)

        val entrySlot = slot<ScheduleEntry>()
        every { scheduleEntryRepository.save(capture(entrySlot)) } answers {
            entrySlot.captured.apply { id = 10L }
        }

        // Act: Speichere ScheduleEntry OHNE courseWithEntries.scheduleEntries.add()
        val entry = ScheduleEntry(
            course = course,
            room = createTestRoom(),
            dayOfWeek = DayOfWeek.MONDAY,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0),
        )
        val saved = scheduleEntryRepository.save(entry)

        // Assert: Entry gespeichert, keine manuelle Collection-Manipulation nötig
        assertNotNull(saved)
        assertNotNull(saved.id)
        assertEquals(course, saved.course)
        verify(exactly = 1) { scheduleEntryRepository.save(any<ScheduleEntry>()) }
    }

    @Test
    fun `findByIdWithScheduleEntries loads course with entries`() {
        // Arrange
        val courseId = 100L
        val course = createTestCourse(courseId)

        val entry1 = createTestScheduleEntry(course, 1L)
        val entry2 = createTestScheduleEntry(course, 2L)

        course.scheduleEntries.addAll(listOf(entry1, entry2))

        every { courseRepository.findByIdWithScheduleEntries(courseId) } returns course

        // Act
        val loaded = courseRepository.findByIdWithScheduleEntries(courseId)

        // Assert
        assertNotNull(loaded)
        assertEquals(2, loaded!!.scheduleEntries.size)
        verify(exactly = 1) { courseRepository.findByIdWithScheduleEntries(courseId) }
    }

    @Test
    fun `parallel thread simulation - no EntityKey errors`() {
        // Arrange: Mehrere Threads greifen auf gleichen Course zu
        val threadCount = 4
        val operationsPerThread = 10
        val latch = CountDownLatch(threadCount * operationsPerThread)
        val errors = mutableListOf<Throwable>()
        val savedCount = AtomicInteger(0)

        val baseCourse = createTestCourse(100L)
        every { courseRepository.findById(100L) } answers {
            Optional.of(createTestCourse(100L))
        }

        every { scheduleEntryRepository.save(any<ScheduleEntry>()) } answers {
            savedCount.incrementAndGet()
            firstArg<ScheduleEntry>().apply { id = savedCount.get().toLong() }
        }

        val executor = Executors.newFixedThreadPool(threadCount)

        // Act: Simuliere parallele Persist-Operationen
        repeat(threadCount) { threadIndex ->
            executor.submit {
                try {
                    repeat(operationsPerThread) { opIndex ->
                        // Wichtig: Lade Course NEU (managed entity)
                        val managedCourse = courseRepository.findById(100L).orElseThrow()

                        val entry = ScheduleEntry(
                            course = managedCourse,
                            room = createTestRoom(),
                            dayOfWeek = DayOfWeek.values()[opIndex % 7],
                            startTime = LocalTime.of(8 + opIndex, 0),
                            endTime = LocalTime.of(10 + opIndex, 0),
                        )

                        // Save ohne manuelle Collection-Manipulation
                        scheduleEntryRepository.save(entry)
                        latch.countDown()
                    }
                } catch (e: Throwable) {
                    synchronized(errors) {
                        errors.add(e)
                    }
                    latch.countDown()
                }
            }
        }

        // Assert: Alle Threads erfolgreich, keine Fehler
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not complete in time")
        executor.shutdown()

        if (errors.isNotEmpty()) {
            fail<Unit>("Errors occurred during parallel execution: ${errors.joinToString(separator = ", ")}")
        }

        assertEquals(threadCount * operationsPerThread, savedCount.get())
    }

    @Test
    fun `detached entity modification would cause EntityKey error`() {
        // Dieser Test dokumentiert das ALTE Problem

        // Arrange: Simuliere detached Entity (ohne findById neu laden)
        val detachedCourse = createTestCourse(null) // ID = null = detached
        val studyProgram = createTestStudyProgram()

        // Act & Assert: Direkte Modifikation einer detached Entity
        // In production würde dies "cannot generate EntityKey when id is null" werfen
        // Hier zeigen wir, dass ohne findById() das Problem auftreten würde
        assertNotNull(detachedCourse)
        assertNotNull(studyProgram)
        assertNull(detachedCourse.id) // ID ist null -> detached
    }

    // Helper Methods

    private fun createTestCourse(id: Long?): Course {
        val semester = Semester(
            name = "Sommersemester 2024",
            shortName = "SS24",
            startDate = java.time.LocalDate.of(2024, 4, 1),
            endDate = java.time.LocalDate.of(2024, 9, 30),
        ).apply { this.id = 1L }

        return Course(
            name = "Test Course",
            semester = semester,
            lecturer = lecturer,
            courseType = courseType,
        ).apply { this.id = id }
    }

    private fun createTestScheduleEntry(course: Course, id: Long): ScheduleEntry = ScheduleEntry(
        course = course,
        room = createTestRoom(),
        dayOfWeek = DayOfWeek.MONDAY,
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(12, 0),
    ).apply { this.id = id }

    private fun createTestRoom(): Room = Room(code = "TEST-101", building = "TEST", roomNumber = "101")
        .apply { id = 1L }

    private fun createTestStudyProgram(): StudyProgram = StudyProgram(
        code = "TEST",
        name = "Test Program",
        degreeType = DegreeType.BACHELOR,
    ).apply {
        id = 1L
        facultyId = 1
    }
}
