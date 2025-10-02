package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Entity
@Table(name = "rooms")
class Room(
    @Column(name = "code", nullable = false, unique = true, length = 20)
    @field:NotBlank
    @field:Size(max = 20)
    var code: String,
    @Column(name = "building", nullable = false, length = 10)
    @field:NotBlank
    @field:Size(max = 10)
    var building: String,
    @Column(name = "room_number", nullable = false, length = 10)
    @field:NotBlank
    @field:Size(max = 10)
    var roomNumber: String,
    @Column(name = "capacity") var capacity: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 50)
    var roomType: RoomType? = null,
    @Column(name = "equipment", columnDefinition = "TEXT") var equipment: String? = null,
    @Column(name = "active") var active: Boolean = true
) : BaseEntity() {

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    var scheduleEntries: MutableSet<ScheduleEntry> = mutableSetOf()

    override fun toString(): String {
        return "Room(id=$id, code='$code', building='$building', roomNumber='$roomNumber')"
    }
}

enum class RoomType {
    LECTURE_HALL,
    SEMINAR_ROOM,
    LAB,
    COMPUTER_ROOM,
    WORKSHOP,
    OFFICE,
    OTHER
}
