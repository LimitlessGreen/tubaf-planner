package de.tubaf.planner.repository

import de.tubaf.planner.model.ChangeLog
import de.tubaf.planner.model.ChangeType
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ChangeLogRepository : JpaRepository<ChangeLog, Long> {

    @Query(
        """
        SELECT cl FROM ChangeLog cl 
        WHERE cl.scrapingRun.id = :scrapingRunId 
        ORDER BY cl.createdAt DESC
        """
    )
    fun findByScrapingRunId(@Param("scrapingRunId") scrapingRunId: Long): List<ChangeLog>

    @Query(
        """
        SELECT cl FROM ChangeLog cl 
        WHERE cl.entityType = :entityType 
        AND cl.entityId = :entityId 
        ORDER BY cl.createdAt DESC
        """
    )
    fun findByEntityTypeAndEntityId(
        @Param("entityType") entityType: String,
        @Param("entityId") entityId: Long
    ): List<ChangeLog>

    fun findByChangeType(changeType: ChangeType): List<ChangeLog>

    @Query(
        """
        SELECT cl FROM ChangeLog cl 
        WHERE cl.scrapingRun.semester.id = :semesterId 
        AND cl.changeType = :changeType 
        ORDER BY cl.createdAt DESC
        """
    )
    fun findBySemesterAndChangeType(
        @Param("semesterId") semesterId: Long,
        @Param("changeType") changeType: ChangeType
    ): List<ChangeLog>

    @Query(
        """
        SELECT cl FROM ChangeLog cl 
        WHERE cl.createdAt >= :since
        AND (:entityType IS NULL OR cl.entityType = :entityType)
        ORDER BY cl.createdAt DESC
        """
    )
    fun findRecentChanges(
        @Param("since") since: LocalDateTime,
        @Param("entityType") entityType: String? = null
    ): List<ChangeLog>

    @Query(
        """
        SELECT COUNT(cl) FROM ChangeLog cl 
        WHERE cl.scrapingRun.id = :scrapingRunId 
        AND cl.changeType = :changeType
        """
    )
    fun countByScrapingRunAndChangeType(
        @Param("scrapingRunId") scrapingRunId: Long,
        @Param("changeType") changeType: ChangeType
    ): Long

    @Query(
        """
        SELECT cl.changeType, COUNT(cl) 
        FROM ChangeLog cl 
        WHERE cl.scrapingRun.id = :scrapingRunId 
        GROUP BY cl.changeType
        """
    )
    fun getChangeStatsByScrapingRun(@Param("scrapingRunId") scrapingRunId: Long): List<Array<Any>>
}
