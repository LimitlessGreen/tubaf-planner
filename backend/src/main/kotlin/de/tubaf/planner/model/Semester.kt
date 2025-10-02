package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "semesters")
class Semester(
    @Column(name = "name", nullable = false, unique = true, length = 50)
    @field:NotBlank
    @field:Size(max = 50)
    var name: String,
    @Column(name = "short_name", nullable = false, length = 10)
    @field:NotBlank
    @field:Size(max = 10)
    var shortName: String,
    @Column(name = "start_date", nullable = false) var startDate: LocalDate,
    @Column(name = "end_date", nullable = false) var endDate: LocalDate,
    @Column(name = "active") var active: Boolean = false
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null

    @OneToMany(mappedBy = "semester", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var courses: MutableSet<Course> = mutableSetOf()

    @OneToMany(mappedBy = "semester", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var scrapingRuns: MutableSet<ScrapingRun> = mutableSetOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Semester) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String {
        return "Semester(id=$id, name='$name', shortName='$shortName', active=$active)"
    }
}
