package de.tubaf.planner.model

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalTime

@Entity
@Table(name = "schedule_entries")
class ScheduleEntry(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    var course: Course,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: Room,
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false)
    var dayOfWeek: DayOfWeek,
    @Column(name = "start_time", nullable = false) var startTime: LocalTime,
    @Column(name = "end_time", nullable = false) var endTime: LocalTime,
    @Column(name = "week_pattern", length = 20)
    var weekPattern: String? = null, // z.B. "alle", "ungerade", "gerade", "14-täglich"
    @Column(name = "notes", columnDefinition = "TEXT") var notes: String? = null,
    @Column(name = "active") var active: Boolean = true,
) : BaseEntity() {

    val duration: Long
        get() = java.time.Duration.between(startTime, endTime).toMinutes()

    fun overlaps(other: ScheduleEntry): Boolean = this.dayOfWeek == other.dayOfWeek &&
        this.room.id == other.room.id &&
        this.startTime < other.endTime &&
        other.startTime < this.endTime

    override fun toString(): String = "ScheduleEntry(id=$id, course=${course.name}, room=${room.code}, $dayOfWeek $startTime-$endTime)"
}
