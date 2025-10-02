package de.tubaf.planner.repository

import de.tubaf.planner.model.ScrapingRun
import de.tubaf.planner.model.ScrapingStatus
import de.tubaf.planner.model.Semester
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ScrapingRunRepository : JpaRepository<ScrapingRun, Long> {

    fun findByStatus(status: ScrapingStatus): List<ScrapingRun>

    fun findBySemester(semester: Semester): List<ScrapingRun>

    fun findBySemesterAndStatus(semester: Semester, status: ScrapingStatus): List<ScrapingRun>

    @Query(
        """
        SELECT sr FROM ScrapingRun sr 
        WHERE sr.semester.id = :semesterId 
        ORDER BY sr.startTime DESC
        """
    )
    fun findBySemesterIdOrderByStartTimeDesc(
        @Param("semesterId") semesterId: Long
    ): List<ScrapingRun>

    @Query(
        """
        SELECT sr FROM ScrapingRun sr 
        WHERE sr.semester.id = :semesterId 
        AND sr.status = 'COMPLETED' 
        ORDER BY sr.startTime DESC 
        LIMIT 1
        """
    )
    fun findLastSuccessfulRun(@Param("semesterId") semesterId: Long): ScrapingRun?

    @Query(
        """
        SELECT sr FROM ScrapingRun sr 
        WHERE sr.status IN :statuses 
        AND sr.startTime >= :since
        ORDER BY sr.startTime DESC
        """
    )
    fun findByStatusesAndStartTimeAfter(
        @Param("statuses") statuses: List<ScrapingStatus>,
        @Param("since") since: LocalDateTime
    ): List<ScrapingRun>

    @Query(
        """
        SELECT COUNT(sr) FROM ScrapingRun sr 
        WHERE sr.semester.id = :semesterId 
        AND sr.status = 'COMPLETED'
        """
    )
    fun countSuccessfulRunsForSemester(@Param("semesterId") semesterId: Long): Long
}
