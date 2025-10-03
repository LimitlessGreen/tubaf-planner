package de.tubaf.planner.model

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "scraping_runs")
class ScrapingRun(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_id", nullable = false)
    var semester: Semester,
    @Column(name = "start_time", nullable = false)
    var startTime: LocalDateTime = LocalDateTime.now(),
    @Column(name = "end_time") var endTime: LocalDateTime? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ScrapingStatus = ScrapingStatus.RUNNING,
    @Column(name = "total_entries") var totalEntries: Int? = null,
    @Column(name = "new_entries") var newEntries: Int? = null,
    @Column(name = "updated_entries") var updatedEntries: Int? = null,
    @Column(name = "error_message", columnDefinition = "TEXT") var errorMessage: String? = null,
    @Column(name = "source_url", length = 500) var sourceUrl: String? = null,
) : BaseEntity() {

    @OneToMany(mappedBy = "scrapingRun", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var changeLogs: MutableSet<ChangeLog> = mutableSetOf()

    val duration: Long?
        get() =
            if (endTime != null) {
                java.time.Duration.between(startTime, endTime).toMinutes()
            } else {
                null
            }

    fun markCompleted(totalEntries: Int, newEntries: Int, updatedEntries: Int) {
        this.endTime = LocalDateTime.now()
        this.status = ScrapingStatus.COMPLETED
        this.totalEntries = totalEntries
        this.newEntries = newEntries
        this.updatedEntries = updatedEntries
    }

    fun markFailed(errorMessage: String) {
        this.endTime = LocalDateTime.now()
        this.status = ScrapingStatus.FAILED
        this.errorMessage = errorMessage
    }

    override fun toString(): String = "ScrapingRun(id=$id, semester=${semester.shortName}, status=$status, startTime=$startTime)"
}

enum class ScrapingStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
