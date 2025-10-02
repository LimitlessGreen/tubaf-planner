package de.tubaf.planner.repository

import de.tubaf.planner.model.Course
import de.tubaf.planner.model.CourseType
import de.tubaf.planner.model.Lecturer
import de.tubaf.planner.model.Semester
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, Long> {

    fun findByActive(active: Boolean): List<Course>

    fun findBySemesterAndActive(semester: Semester, active: Boolean = true): List<Course>

    fun findByLecturerAndActive(lecturer: Lecturer, active: Boolean = true): List<Course>

    fun findByCourseTypeAndActive(courseType: CourseType, active: Boolean = true): List<Course>

    fun findByNameContainingIgnoreCaseAndActive(name: String, active: Boolean = true): List<Course>

    fun findByCourseNumber(courseNumber: String): Course?

    @Query(
        """
        SELECT c FROM Course c 
        WHERE c.semester.id = :semesterId 
        AND c.active = true
        ORDER BY c.name
        """
    )
    fun findBySemesterId(@Param("semesterId") semesterId: Long): List<Course>

    @Query(
        """
        SELECT DISTINCT c FROM Course c 
        JOIN c.courseStudyPrograms csp 
        WHERE csp.studyProgram.id = :studyProgramId 
        AND c.semester.id = :semesterId 
        AND c.active = true
        ORDER BY c.name
        """
    )
    fun findByStudyProgramAndSemester(
        @Param("studyProgramId") studyProgramId: Long,
        @Param("semesterId") semesterId: Long
    ): List<Course>

    @Query(
        """
        SELECT c FROM Course c 
        WHERE c.lecturer.id = :lecturerId 
        AND c.semester.id = :semesterId 
        AND c.active = true
        ORDER BY c.courseType.code, c.name
        """
    )
    fun findByLecturerAndSemester(
        @Param("lecturerId") lecturerId: Long,
        @Param("semesterId") semesterId: Long
    ): List<Course>

    @Query(
        """
        SELECT c FROM Course c 
        WHERE c.active = true 
        AND c.semester.id = :semesterId
        AND (LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(c.description) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(c.courseNumber) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        ORDER BY c.name
        """
    )
    fun searchInSemester(
        @Param("searchTerm") searchTerm: String,
        @Param("semesterId") semesterId: Long
    ): List<Course>
}
