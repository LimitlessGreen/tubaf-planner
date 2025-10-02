package de.tubaf.planner.service

import de.tubaf.planner.model.Room
import de.tubaf.planner.model.RoomType
import de.tubaf.planner.repository.RoomRepository
import de.tubaf.planner.repository.ScheduleEntryRepository
import java.time.DayOfWeek
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class RoomService(
    private val roomRepository: RoomRepository,
    private val scheduleEntryRepository: ScheduleEntryRepository
) {

    /** Erstellt einen neuen Raum */
    fun createRoom(
        code: String,
        building: String,
        roomNumber: String,
        capacity: Int?,
        roomType: RoomType?,
        equipment: String?
    ): Room {
        // Prüfe auf doppelten Code
        roomRepository.findByCode(code)?.let {
            throw IllegalArgumentException("Room with code '$code' already exists")
        }

        val room =
            Room(
                code = code,
                building = building,
                roomNumber = roomNumber,
                capacity = capacity,
                roomType = roomType,
                equipment = equipment
            )

        return roomRepository.save(room)
    }

    /** Gibt alle aktiven Räume zurück */
    @Transactional(readOnly = true)
    fun getActiveRooms(): List<Room> {
        return roomRepository.findByActive(true)
    }

    /** Gibt Räume nach Gebäude zurück */
    @Transactional(readOnly = true)
    fun getRoomsByBuilding(building: String): List<Room> {
        return roomRepository.findByBuildingAndActive(building, true)
    }

    /** Gibt Räume nach Typ zurück */
    @Transactional(readOnly = true)
    fun getRoomsByType(roomType: RoomType): List<Room> {
        return roomRepository.findByRoomTypeAndActive(roomType, true)
    }

    /** Sucht Räume nach Mindestkapazität */
    @Transactional(readOnly = true)
    fun getRoomsByMinCapacity(minCapacity: Int): List<Room> {
        return roomRepository.findByCapacityGreaterThanEqualAndActive(minCapacity, true)
    }

    /** Sucht verfügbare Räume mit erweiterten Filtern */
    @Transactional(readOnly = true)
    fun findAvailableRooms(
        minCapacity: Int?,
        roomType: RoomType?,
        building: String? = null
    ): List<Room> {
        var rooms =
            if (minCapacity != null) {
                roomRepository.findAvailableRooms(minCapacity, roomType)
            } else {
                roomType?.let { roomRepository.findByRoomTypeAndActive(it, true) }
                    ?: roomRepository.findByActive(true)
            }

        building?.let { b -> rooms = rooms.filter { it.building.equals(b, ignoreCase = true) } }

        return rooms
    }

    /** Gibt die Auslastung eines Raumes zurück */
    @Transactional(readOnly = true)
    fun getRoomUtilization(roomId: Long, semesterId: Long): RoomUtilization {
        val room =
            roomRepository.findByIdOrNull(roomId)
                ?: throw IllegalArgumentException("Room with ID $roomId not found")

        val totalSlots = scheduleEntryRepository.countByRoomAndSemester(roomId, semesterId)

        // Berechne Auslastung pro Wochentag
        val utilizationByDay =
            DayOfWeek.entries.associateWith { dayOfWeek ->
                scheduleEntryRepository.findByRoomAndDayOfWeek(roomId, dayOfWeek).size
            }

        return RoomUtilization(
            roomCode = room.code,
            totalBookedSlots = totalSlots.toInt(),
            utilizationByDay = utilizationByDay
        )
    }

    /** Aktiviert oder deaktiviert einen Raum */
    fun setRoomActive(roomId: Long, active: Boolean): Room {
        val room =
            roomRepository.findByIdOrNull(roomId)
                ?: throw IllegalArgumentException("Room with ID $roomId not found")

        room.active = active
        return roomRepository.save(room)
    }

    /** Aktualisiert einen Raum */
    fun updateRoom(roomId: Long, updates: RoomUpdateRequest): Room {
        val room =
            roomRepository.findByIdOrNull(roomId)
                ?: throw IllegalArgumentException("Room with ID $roomId not found")

        updates.code?.let { code ->
            roomRepository.findByCode(code)?.let {
                if (it.id != roomId) {
                    throw IllegalArgumentException("Room with code '$code' already exists")
                }
            }
            room.code = code
        }

        updates.building?.let { room.building = it }
        updates.roomNumber?.let { room.roomNumber = it }
        updates.capacity?.let { room.capacity = it }
        updates.roomType?.let { room.roomType = it }
        updates.equipment?.let { room.equipment = it }
        updates.active?.let { room.active = it }

        return roomRepository.save(room)
    }

    /** Gibt Statistiken für alle Räume zurück */
    @Transactional(readOnly = true)
    fun getRoomStatistics(): RoomStatistics {
        val all = roomRepository.findAll()
        val active = all.filter { it.active }
        val byType = active.groupBy { it.roomType }
        val byBuilding = active.groupBy { it.building }
        val totalCapacity = active.mapNotNull { it.capacity }.sum()
        val avgCapacity =
            if (active.isNotEmpty()) {
                active.mapNotNull { it.capacity }.average()
            } else 0.0

        return RoomStatistics(
            totalRooms = all.size,
            activeRooms = active.size,
            inactiveRooms = all.size - active.size,
            roomsByType = byType.mapValues { it.value.size },
            roomsByBuilding = byBuilding.mapValues { it.value.size },
            totalCapacity = totalCapacity,
            averageCapacity = avgCapacity
        )
    }
}

/** Request-Klasse für Room Updates */
data class RoomUpdateRequest(
    val code: String? = null,
    val building: String? = null,
    val roomNumber: String? = null,
    val capacity: Int? = null,
    val roomType: RoomType? = null,
    val equipment: String? = null,
    val active: Boolean? = null
)

/** Auslastung eines Raumes */
data class RoomUtilization(
    val roomCode: String,
    val totalBookedSlots: Int,
    val utilizationByDay: Map<DayOfWeek, Int>
)

/** Statistiken für alle Räume */
data class RoomStatistics(
    val totalRooms: Int,
    val activeRooms: Int,
    val inactiveRooms: Int,
    val roomsByType: Map<RoomType?, Int>,
    val roomsByBuilding: Map<String, Int>,
    val totalCapacity: Int,
    val averageCapacity: Double
)
