package de.tubaf.planner.service

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class ChangeTrackingService(
    private val scrapingRunRepository: ScrapingRunRepository,
    private val changeLogRepository: ChangeLogRepository,
    private val semesterRepository: SemesterRepository,
) {

    /** Startet einen neuen Scraping-Lauf */
    fun startScrapingRun(semesterId: Long, sourceUrl: String? = null): ScrapingRun {
        val semester =
            semesterRepository.findByIdOrNull(semesterId)
                ?: throw IllegalArgumentException("Semester with ID $semesterId not found")

        val scrapingRun = ScrapingRun(semester = semester, sourceUrl = sourceUrl)

        return scrapingRunRepository.save(scrapingRun)
    }

    /** Markiert einen Scraping-Lauf als erfolgreich abgeschlossen */
    fun completeScrapingRun(scrapingRunId: Long, totalEntries: Int, newEntries: Int, updatedEntries: Int): ScrapingRun {
        val scrapingRun =
            scrapingRunRepository.findByIdOrNull(scrapingRunId)
                ?: throw IllegalArgumentException("ScrapingRun with ID $scrapingRunId not found")

        scrapingRun.markCompleted(totalEntries, newEntries, updatedEntries)
        return scrapingRunRepository.save(scrapingRun)
    }

    /** Markiert einen Scraping-Lauf als fehlgeschlagen */
    fun failScrapingRun(scrapingRunId: Long, errorMessage: String): ScrapingRun {
        val scrapingRun =
            scrapingRunRepository.findByIdOrNull(scrapingRunId)
                ?: throw IllegalArgumentException("ScrapingRun with ID $scrapingRunId not found")

        scrapingRun.markFailed(errorMessage)
        return scrapingRunRepository.save(scrapingRun)
    }

    /** Protokolliert die Erstellung einer neuen Entity */
    fun logEntityCreated(scrapingRunId: Long, entityType: String, entityId: Long, description: String? = null) {
        val scrapingRun = getScrapingRun(scrapingRunId)
        val changeLog = ChangeLog.created(scrapingRun, entityType, entityId, description)
        changeLogRepository.save(changeLog)
    }

    /** Protokolliert die Aktualisierung einer Entity */
    fun logEntityUpdated(scrapingRunId: Long, entityType: String, entityId: Long, fieldName: String, oldValue: String?, newValue: String?) {
        val scrapingRun = getScrapingRun(scrapingRunId)
        val changeLog =
            ChangeLog.updated(scrapingRun, entityType, entityId, fieldName, oldValue, newValue)
        changeLogRepository.save(changeLog)
    }

    /** Protokolliert die Löschung einer Entity */
    fun logEntityDeleted(scrapingRunId: Long, entityType: String, entityId: Long, description: String? = null) {
        val scrapingRun = getScrapingRun(scrapingRunId)
        val changeLog = ChangeLog.deleted(scrapingRun, entityType, entityId, description)
        changeLogRepository.save(changeLog)
    }

    /** Gibt alle Änderungen für einen Scraping-Lauf zurück */
    @Transactional(readOnly = true)
    fun getChangesForScrapingRun(scrapingRunId: Long): List<ChangeLog> = changeLogRepository.findByScrapingRunId(scrapingRunId)

    /** Gibt die Änderungshistorie für eine spezifische Entity zurück */
    @Transactional(readOnly = true)
    fun getEntityHistory(entityType: String, entityId: Long): List<ChangeLog> =
        changeLogRepository.findByEntityTypeAndEntityId(entityType, entityId)

    /** Gibt aktuelle Änderungen seit einem bestimmten Zeitpunkt zurück */
    @Transactional(readOnly = true)
    fun getRecentChanges(since: LocalDateTime, entityType: String? = null): List<ChangeLog> =
        changeLogRepository.findRecentChanges(since, entityType)

    /** Gibt Änderungsstatistiken für einen Scraping-Lauf zurück */
    @Transactional(readOnly = true)
    fun getChangeStatistics(scrapingRunId: Long): ChangeStatistics {
        val stats = changeLogRepository.getChangeStatsByScrapingRun(scrapingRunId)
        val created = stats.find { it[0] == ChangeType.CREATED }?.get(1) as Long? ?: 0L
        val updated = stats.find { it[0] == ChangeType.UPDATED }?.get(1) as Long? ?: 0L
        val deleted = stats.find { it[0] == ChangeType.DELETED }?.get(1) as Long? ?: 0L

        return ChangeStatistics(
            created = created.toInt(),
            updated = updated.toInt(),
            deleted = deleted.toInt(),
            total = (created + updated + deleted).toInt(),
        )
    }

    /** Gibt alle Scraping-Läufe für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getScrapingRunsForSemester(semesterId: Long): List<ScrapingRun> =
        scrapingRunRepository.findBySemesterIdOrderByStartTimeDesc(semesterId)

    /** Gibt den letzten erfolgreichen Scraping-Lauf für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getLastSuccessfulRun(semesterId: Long): ScrapingRun? = scrapingRunRepository.findLastSuccessfulRun(semesterId)

    private fun getScrapingRun(scrapingRunId: Long): ScrapingRun = scrapingRunRepository.findByIdOrNull(scrapingRunId)
        ?: throw IllegalArgumentException("ScrapingRun with ID $scrapingRunId not found")
}

/** Änderungsstatistiken für einen Scraping-Lauf */
data class ChangeStatistics(val created: Int, val updated: Int, val deleted: Int, val total: Int)
