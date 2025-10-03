package de.tubaf.planner.repository

import de.tubaf.planner.model.Room
import de.tubaf.planner.model.RoomType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalTime

@Repository
interface RoomRepository : JpaRepository<Room, Long> {

    fun findByCode(code: String): Room?

    fun findByActive(active: Boolean): List<Room>

    fun findByBuildingAndActive(building: String, active: Boolean = true): List<Room>

    fun findByRoomTypeAndActive(roomType: RoomType, active: Boolean = true): List<Room>

    fun findByCapacityGreaterThanEqualAndActive(capacity: Int, active: Boolean = true): List<Room>

    @Query(
        """
        SELECT r FROM Room r 
        WHERE r.active = true 
        AND r.building = :building 
        AND r.roomType IN :roomTypes
        ORDER BY r.roomNumber
        """,
    )
    fun findByBuildingAndRoomTypes(@Param("building") building: String, @Param("roomTypes") roomTypes: List<RoomType>): List<Room>

    @Query(
        """
        SELECT r FROM Room r 
        WHERE r.active = true 
        AND r.capacity >= :minCapacity
        AND (:roomType IS NULL OR r.roomType = :roomType)
        ORDER BY r.capacity, r.building, r.roomNumber
        """,
    )
    fun findAvailableRooms(@Param("minCapacity") minCapacity: Int, @Param("roomType") roomType: RoomType?): List<Room>

    @Query(
        """
        SELECT r FROM Room r 
        WHERE r.active = true 
        AND r.id NOT IN (
            SELECT se.room.id FROM ScheduleEntry se 
            WHERE se.dayOfWeek = :dayOfWeek 
            AND se.startTime < :endTime 
            AND se.endTime > :startTime
            AND se.active = true
        )
        ORDER BY r.building, r.roomNumber
        """,
    )
    fun findAvailableRoomsAtTime(
        @Param("dayOfWeek") dayOfWeek: DayOfWeek,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
    ): List<Room>
}
