package de.tubaf.planner.repository

import de.tubaf.planner.model.RoomPlanSlot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RoomPlanSlotRepository : JpaRepository<RoomPlanSlot, Long> {

    fun findByRoomIdAndSemesterId(roomId: Long, semesterId: Long): List<RoomPlanSlot>
}
