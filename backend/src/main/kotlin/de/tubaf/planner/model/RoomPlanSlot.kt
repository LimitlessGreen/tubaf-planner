package de.tubaf.planner.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.DayOfWeek
import java.time.LocalTime

@Entity
@Table(
    name = "room_plan_slots",
    uniqueConstraints =
        [
            UniqueConstraint(
                name = "uk_room_plan_slot",
                columnNames =
                    [
                        "room_id",
                        "semester_id",
                        "day_of_week",
                        "start_time",
                        "end_time",
                        "course_title",
                    ]
            ),
        ]
)
class RoomPlanSlot(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    var room: Room,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_id", nullable = false)
    var semester: Semester,

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 20)
    var dayOfWeek: DayOfWeek,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime,

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime,

    @Column(name = "course_title", nullable = false, length = 255)
    var courseTitle: String,

    @Column(name = "course_type", length = 100)
    var courseType: String? = null,

    @Column(name = "lecturers", columnDefinition = "TEXT")
    var lecturers: String? = null,

    @Column(name = "week_pattern", length = 50)
    var weekPattern: String? = null,

    @Column(name = "info_id", length = 20)
    var infoId: String? = null,

    @Column(name = "active")
    var active: Boolean = true,
) : BaseEntity()
