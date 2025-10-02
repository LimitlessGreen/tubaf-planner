package de.tubaf.planner.repository

import de.tubaf.planner.model.Semester
import java.time.LocalDate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface SemesterRepository : JpaRepository<Semester, Long> {

    fun findByName(name: String): Semester?

    fun findByShortName(shortName: String): Semester?

    fun findByActive(active: Boolean): List<Semester>

    @Query(
        """
        SELECT s FROM Semester s 
        WHERE s.active = true 
        ORDER BY s.startDate DESC
        """
    )
    fun findActiveSemesters(): List<Semester>

    @Query(
        """
        SELECT s FROM Semester s 
        WHERE :date BETWEEN s.startDate AND s.endDate
        """
    )
    fun findByDate(date: LocalDate): List<Semester>

    @Query(
        """
        SELECT s FROM Semester s 
        WHERE s.active = true 
        AND :date BETWEEN s.startDate AND s.endDate
        """
    )
    fun findCurrentSemester(date: LocalDate = LocalDate.now()): Semester?

    @Query(
        """
        SELECT s FROM Semester s 
        ORDER BY s.startDate DESC
        LIMIT 1
        """
    )
    fun findLatestSemester(): Semester?
}
