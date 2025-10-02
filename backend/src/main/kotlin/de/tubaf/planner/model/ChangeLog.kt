package de.tubaf.planner.model

import jakarta.persistence.*

@Entity
@Table(name = "change_log")
class ChangeLog(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scraping_run_id", nullable = false)
    var scrapingRun: ScrapingRun,
    @Column(name = "entity_type", nullable = false, length = 100) var entityType: String,
    @Column(name = "entity_id", nullable = false) var entityId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    var changeType: ChangeType,
    @Column(name = "field_name", length = 100) var fieldName: String? = null,
    @Column(name = "old_value", columnDefinition = "TEXT") var oldValue: String? = null,
    @Column(name = "new_value", columnDefinition = "TEXT") var newValue: String? = null,
    @Column(name = "description", columnDefinition = "TEXT") var description: String? = null
) : BaseEntity() {

    companion object {
        fun created(
            scrapingRun: ScrapingRun,
            entityType: String,
            entityId: Long,
            description: String? = null
        ): ChangeLog {
            return ChangeLog(
                scrapingRun = scrapingRun,
                entityType = entityType,
                entityId = entityId,
                changeType = ChangeType.CREATED,
                description = description
            )
        }

        fun updated(
            scrapingRun: ScrapingRun,
            entityType: String,
            entityId: Long,
            fieldName: String,
            oldValue: String?,
            newValue: String?
        ): ChangeLog {
            return ChangeLog(
                scrapingRun = scrapingRun,
                entityType = entityType,
                entityId = entityId,
                changeType = ChangeType.UPDATED,
                fieldName = fieldName,
                oldValue = oldValue,
                newValue = newValue
            )
        }

        fun deleted(
            scrapingRun: ScrapingRun,
            entityType: String,
            entityId: Long,
            description: String? = null
        ): ChangeLog {
            return ChangeLog(
                scrapingRun = scrapingRun,
                entityType = entityType,
                entityId = entityId,
                changeType = ChangeType.DELETED,
                description = description
            )
        }
    }

    override fun toString(): String {
        return "ChangeLog(id=$id, entityType='$entityType', entityId=$entityId, changeType=$changeType)"
    }
}

enum class ChangeType {
    CREATED,
    UPDATED,
    DELETED
}
