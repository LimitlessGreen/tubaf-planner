package de.tubaf.planner.repository

import de.tubaf.planner.model.Lecturer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LecturerRepository : JpaRepository<Lecturer, Long> {

    fun findByActive(active: Boolean): List<Lecturer>

    fun findByNameContainingIgnoreCaseAndActive(name: String, active: Boolean = true): List<Lecturer>

    fun findByDepartmentAndActive(department: String, active: Boolean = true): List<Lecturer>

    fun findByEmailIgnoreCase(email: String): Lecturer?

    @Query(
        """
        SELECT l FROM Lecturer l 
        WHERE l.active = true 
        AND (LOWER(l.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(l.department) LIKE LOWER(CONCAT('%', :searchTerm, '%'))
             OR LOWER(l.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        ORDER BY l.name
        """,
    )
    fun searchByNameOrDepartmentOrTitle(@Param("searchTerm") searchTerm: String): List<Lecturer>

    @Query(
        """
        SELECT DISTINCT l FROM Lecturer l 
        JOIN l.courses c 
        WHERE c.semester.id = :semesterId 
        AND l.active = true
        ORDER BY l.name
        """,
    )
    fun findBySemesterId(@Param("semesterId") semesterId: Long): List<Lecturer>

    @Query(
        """
        SELECT COUNT(c) FROM Course c 
        WHERE c.lecturer.id = :lecturerId 
        AND c.semester.id = :semesterId
        """,
    )
    fun countCoursesBySemester(@Param("lecturerId") lecturerId: Long, @Param("semesterId") semesterId: Long): Long
}
