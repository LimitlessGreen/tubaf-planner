package de.tubaf.planner.repository

import de.tubaf.planner.model.ScheduleEntry
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.DayOfWeek
import java.time.LocalTime

@Repository
interface ScheduleEntryRepository : JpaRepository<ScheduleEntry, Long> {

    fun findByActive(active: Boolean): List<ScheduleEntry>

    @Query(
        """
        SELECT se FROM ScheduleEntry se 
        WHERE se.course.semester.id = :semesterId 
        AND se.active = true
        ORDER BY se.dayOfWeek, se.startTime
        """,
    )
    fun findBySemesterId(@Param("semesterId") semesterId: Long): List<ScheduleEntry>

    @Query(
        """
        SELECT se FROM ScheduleEntry se 
        JOIN se.course c 
        JOIN c.courseStudyPrograms csp 
        WHERE csp.studyProgram.id = :studyProgramId 
        AND c.semester.id = :semesterId 
        AND se.active = true
        ORDER BY se.dayOfWeek, se.startTime
        """,
    )
    fun findByStudyProgramAndSemester(
        @Param("studyProgramId") studyProgramId: Long,
        @Param("semesterId") semesterId: Long,
    ): List<ScheduleEntry>

    @Query(
        """
        SELECT se FROM ScheduleEntry se 
        WHERE se.room.id = :roomId 
        AND se.dayOfWeek = :dayOfWeek 
        AND se.active = true
        ORDER BY se.startTime
        """,
    )
    fun findByRoomAndDayOfWeek(@Param("roomId") roomId: Long, @Param("dayOfWeek") dayOfWeek: DayOfWeek): List<ScheduleEntry>

    @Query(
        """
        SELECT se FROM ScheduleEntry se 
        WHERE se.room.id = :roomId 
        AND se.dayOfWeek = :dayOfWeek 
        AND se.startTime < :endTime 
        AND se.endTime > :startTime 
        AND se.active = true
        """,
    )
    fun findConflictingEntries(
        @Param("roomId") roomId: Long,
        @Param("dayOfWeek") dayOfWeek: DayOfWeek,
        @Param("startTime") startTime: LocalTime,
        @Param("endTime") endTime: LocalTime,
    ): List<ScheduleEntry>

    @Query(
        """
        SELECT se FROM ScheduleEntry se 
        WHERE se.course.lecturer.id = :lecturerId 
        AND se.course.semester.id = :semesterId 
        AND se.active = true
        ORDER BY se.dayOfWeek, se.startTime
        """,
    )
    fun findByLecturerAndSemester(@Param("lecturerId") lecturerId: Long, @Param("semesterId") semesterId: Long): List<ScheduleEntry>

    @Query(
        """
        SELECT COUNT(se) FROM ScheduleEntry se 
        WHERE se.room.id = :roomId 
        AND se.course.semester.id = :semesterId 
        AND se.active = true
        """,
    )
    fun countByRoomAndSemester(@Param("roomId") roomId: Long, @Param("semesterId") semesterId: Long): Long
}
