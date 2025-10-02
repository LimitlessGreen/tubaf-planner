package de.tubaf.planner.service

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class CourseService(
    private val courseRepository: CourseRepository,
    private val semesterRepository: SemesterRepository,
    private val lecturerRepository: LecturerRepository,
    private val courseTypeRepository: CourseTypeRepository,
    private val studyProgramRepository: StudyProgramRepository
) {

    /** Erstellt einen neuen Kurs */
    fun createCourse(
        name: String,
        courseNumber: String?,
        description: String?,
        semesterId: Long,
        lecturerId: Long,
        courseTypeCode: String,
        sws: Int?,
        ectsCredits: Double?
    ): Course {
        val semester =
            semesterRepository.findByIdOrNull(semesterId)
                ?: throw IllegalArgumentException("Semester with ID $semesterId not found")
        val lecturer =
            lecturerRepository.findByIdOrNull(lecturerId)
                ?: throw IllegalArgumentException("Lecturer with ID $lecturerId not found")
        val courseType =
            courseTypeRepository.findByCode(courseTypeCode)
                ?: throw IllegalArgumentException("CourseType with code $courseTypeCode not found")

        // Prüfe auf doppelte Kursnummer
        courseNumber?.let { number ->
            courseRepository.findByCourseNumber(number)?.let {
                throw IllegalArgumentException("Course with number $number already exists")
            }
        }

        val course =
            Course(
                name = name,
                courseNumber = courseNumber,
                description = description,
                semester = semester,
                lecturer = lecturer,
                courseType = courseType,
                sws = sws,
                ectsCredits = ectsCredits
            )

        return courseRepository.save(course)
    }

    /** Fügt einen Kurs zu einem Studiengang hinzu */
    fun addCourseToStudyProgram(
        courseId: Long,
        studyProgramId: Long,
        semester: Int? = null
    ): Course {
        val course =
            courseRepository.findByIdOrNull(courseId)
                ?: throw IllegalArgumentException("Course with ID $courseId not found")
        val studyProgram =
            studyProgramRepository.findByIdOrNull(studyProgramId)
                ?: throw IllegalArgumentException("StudyProgram with ID $studyProgramId not found")

        course.addStudyProgram(studyProgram, semester)
        return courseRepository.save(course)
    }

    /** Entfernt einen Kurs aus einem Studiengang */
    fun removeCourseFromStudyProgram(courseId: Long, studyProgramId: Long): Course {
        val course =
            courseRepository.findByIdOrNull(courseId)
                ?: throw IllegalArgumentException("Course with ID $courseId not found")
        val studyProgram =
            studyProgramRepository.findByIdOrNull(studyProgramId)
                ?: throw IllegalArgumentException("StudyProgram with ID $studyProgramId not found")

        course.removeStudyProgram(studyProgram)
        return courseRepository.save(course)
    }

    /** Gibt alle Kurse für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getCoursesBySemester(semesterId: Long): List<Course> {
        return courseRepository.findBySemesterId(semesterId)
    }

    /** Gibt alle Kurse für einen Studiengang in einem Semester zurück */
    @Transactional(readOnly = true)
    fun getCoursesByStudyProgramAndSemester(studyProgramId: Long, semesterId: Long): List<Course> {
        return courseRepository.findByStudyProgramAndSemester(studyProgramId, semesterId)
    }

    /** Gibt alle Kurse für einen Dozenten in einem Semester zurück */
    @Transactional(readOnly = true)
    fun getCoursesByLecturerAndSemester(lecturerId: Long, semesterId: Long): List<Course> {
        return courseRepository.findByLecturerAndSemester(lecturerId, semesterId)
    }

    /** Sucht Kurse nach Begriffen */
    @Transactional(readOnly = true)
    fun searchCourses(searchTerm: String, semesterId: Long): List<Course> {
        return courseRepository.searchInSemester(searchTerm, semesterId)
    }

    /** Aktualisiert einen Kurs */
    fun updateCourse(courseId: Long, updates: CourseUpdateRequest): Course {
        val course =
            courseRepository.findByIdOrNull(courseId)
                ?: throw IllegalArgumentException("Course with ID $courseId not found")

        updates.name?.let { course.name = it }
        updates.courseNumber?.let { number ->
            // Prüfe auf doppelte Kursnummer (außer der aktuellen)
            courseRepository.findByCourseNumber(number)?.let {
                if (it.id != courseId) {
                    throw IllegalArgumentException("Course with number $number already exists")
                }
            }
            course.courseNumber = number
        }
        updates.description?.let { course.description = it }
        updates.lecturerId?.let { lecturerId ->
            course.lecturer =
                lecturerRepository.findByIdOrNull(lecturerId)
                    ?: throw IllegalArgumentException("Lecturer with ID $lecturerId not found")
        }
        updates.courseTypeCode?.let { code ->
            course.courseType =
                courseTypeRepository.findByCode(code)
                    ?: throw IllegalArgumentException("CourseType with code $code not found")
        }
        updates.sws?.let { course.sws = it }
        updates.ectsCredits?.let { course.ectsCredits = it }
        updates.active?.let { course.active = it }

        return courseRepository.save(course)
    }

    /** Gibt Statistiken für einen Dozenten zurück */
    @Transactional(readOnly = true)
    fun getLecturerStats(lecturerId: Long, semesterId: Long): LecturerStats {
        val courses = getCoursesByLecturerAndSemester(lecturerId, semesterId)
        val totalSws = courses.mapNotNull { it.sws }.sum()
        val totalEcts = courses.mapNotNull { it.ectsCredits }.sum()
        val coursesByType = courses.groupBy { it.courseType.code }

        return LecturerStats(
            totalCourses = courses.size,
            totalSws = totalSws,
            totalEcts = totalEcts,
            coursesByType = coursesByType.mapValues { it.value.size }
        )
    }
}

/** Request-Klasse für Course Updates */
data class CourseUpdateRequest(
    val name: String? = null,
    val courseNumber: String? = null,
    val description: String? = null,
    val lecturerId: Long? = null,
    val courseTypeCode: String? = null,
    val sws: Int? = null,
    val ectsCredits: Double? = null,
    val active: Boolean? = null
)

/** Statistiken für einen Dozenten */
data class LecturerStats(
    val totalCourses: Int,
    val totalSws: Int,
    val totalEcts: Double,
    val coursesByType: Map<String, Int>
)
