package de.tubaf.planner.service

import de.tubaf.planner.model.*
import de.tubaf.planner.repository.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalTime

@Service
@Transactional
class ScheduleService(
    private val scheduleEntryRepository: ScheduleEntryRepository,
    private val courseRepository: CourseRepository,
    private val roomRepository: RoomRepository,
    private val semesterRepository: SemesterRepository,
    private val studyProgramRepository: StudyProgramRepository,
) {

    /** Erstellt einen neuen Stundenplan-Eintrag nach Konfliktprüfung */
    fun createScheduleEntry(
        courseId: Long,
        roomId: Long,
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime,
        weekPattern: String? = null,
        notes: String? = null,
    ): ScheduleEntry {
        val course =
            courseRepository.findByIdOrNull(courseId)
                ?: throw IllegalArgumentException("Course with ID $courseId not found")
        val room =
            roomRepository.findByIdOrNull(roomId)
                ?: throw IllegalArgumentException("Room with ID $roomId not found")

        // Konfliktprüfung
        val conflicts =
            scheduleEntryRepository.findConflictingEntries(roomId, dayOfWeek, startTime, endTime)
        if (conflicts.isNotEmpty()) {
            val conflictDetails =
                conflicts.joinToString(", ") { "${it.course.name} (${it.startTime}-${it.endTime})" }
            throw IllegalStateException("Room conflict detected with: $conflictDetails")
        }

        val scheduleEntry =
            ScheduleEntry(
                course = course,
                room = room,
                dayOfWeek = dayOfWeek,
                startTime = startTime,
                endTime = endTime,
                weekPattern = weekPattern,
                notes = notes,
            )

        return scheduleEntryRepository.save(scheduleEntry)
    }

    /** Gibt den Stundenplan für ein Studienprogramm in einem Semester zurück */
    @Transactional(readOnly = true)
    fun getScheduleForStudyProgram(studyProgramId: Long, semesterId: Long): List<ScheduleEntry> =
        scheduleEntryRepository.findByStudyProgramAndSemester(studyProgramId, semesterId)

    /** Gibt den gesamten Stundenplan für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getScheduleForSemester(semesterId: Long): List<ScheduleEntry> = scheduleEntryRepository.findBySemesterId(semesterId)

    /** Gibt den Stundenplan für einen Dozenten in einem Semester zurück */
    @Transactional(readOnly = true)
    fun getScheduleForLecturer(lecturerId: Long, semesterId: Long): List<ScheduleEntry> =
        scheduleEntryRepository.findByLecturerAndSemester(lecturerId, semesterId)

    /** Gibt die Raumbelegung für einen spezifischen Wochentag zurück */
    @Transactional(readOnly = true)
    fun getRoomScheduleForDay(roomId: Long, dayOfWeek: DayOfWeek): List<ScheduleEntry> =
        scheduleEntryRepository.findByRoomAndDayOfWeek(roomId, dayOfWeek)

    /** Findet verfügbare Räume zu einer bestimmten Zeit */
    @Transactional(readOnly = true)
    fun findAvailableRooms(
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime,
        minCapacity: Int? = null,
        roomType: RoomType? = null,
    ): List<Room> {
        val availableRooms = roomRepository.findAvailableRoomsAtTime(dayOfWeek, startTime, endTime)

        return availableRooms.filter { room ->
            (minCapacity == null || (room.capacity ?: 0) >= minCapacity) &&
                (roomType == null || room.roomType == roomType)
        }
    }

    /** Prüft auf Terminüberschneidungen für einen Dozenten */
    @Transactional(readOnly = true)
    fun checkLecturerConflicts(lecturerId: Long, semesterId: Long): List<ScheduleEntry> {
        val lecturerSchedule = getScheduleForLecturer(lecturerId, semesterId)
        val conflicts = mutableListOf<ScheduleEntry>()

        for (i in lecturerSchedule.indices) {
            for (j in i + 1 until lecturerSchedule.size) {
                val entry1 = lecturerSchedule[i]
                val entry2 = lecturerSchedule[j]
                if (entry1.overlaps(entry2)) {
                    conflicts.addAll(listOf(entry1, entry2))
                }
            }
        }

        return conflicts.distinct()
    }

    /** Gibt alle Stundenplan-Einträge für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getScheduleBySemester(semesterId: Long): List<ScheduleEntry> = scheduleEntryRepository.findBySemesterId(semesterId)

    /** Aktualisiert einen Stundenplan-Eintrag */
    fun updateScheduleEntry(entryId: Long, updates: ScheduleEntryUpdateRequest): ScheduleEntry {
        val entry =
            scheduleEntryRepository.findByIdOrNull(entryId)
                ?: throw IllegalArgumentException("Schedule entry with ID $entryId not found")

        // Bei Änderung von Zeit oder Raum: Konfliktprüfung
        val newRoomId = updates.roomId ?: entry.room.id!!
        val newDayOfWeek = updates.dayOfWeek ?: entry.dayOfWeek
        val newStartTime = updates.startTime ?: entry.startTime
        val newEndTime = updates.endTime ?: entry.endTime

        if (
            newRoomId != entry.room.id ||
            newDayOfWeek != entry.dayOfWeek ||
            newStartTime != entry.startTime ||
            newEndTime != entry.endTime
        ) {
            val conflicts =
                scheduleEntryRepository
                    .findConflictingEntries(newRoomId, newDayOfWeek, newStartTime, newEndTime)
                    .filter { it.id != entryId } // Exclude current entry

            if (conflicts.isNotEmpty()) {
                throw IllegalStateException("Update would create conflicts")
            }
        }

        // Apply updates
        updates.roomId?.let { roomId ->
            entry.room =
                roomRepository.findByIdOrNull(roomId)
                    ?: throw IllegalArgumentException("Room with ID $roomId not found")
        }
        updates.dayOfWeek?.let { entry.dayOfWeek = it }
        updates.startTime?.let { entry.startTime = it }
        updates.endTime?.let { entry.endTime = it }
        updates.weekPattern?.let { entry.weekPattern = it }
        updates.notes?.let { entry.notes = it }
        updates.active?.let { entry.active = it }

        return scheduleEntryRepository.save(entry)
    }
}

/** Request-Klasse für Schedule Entry Updates */
data class ScheduleEntryUpdateRequest(
    val roomId: Long? = null,
    val dayOfWeek: DayOfWeek? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val weekPattern: String? = null,
    val notes: String? = null,
    val active: Boolean? = null,
)
