package de.tubaf.planner.service

import de.tubaf.planner.model.Semester
import de.tubaf.planner.repository.SemesterRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional
class SemesterService(private val semesterRepository: SemesterRepository) {

    /** Erstellt ein neues Semester */
    fun createSemester(name: String, shortName: String, startDate: LocalDate, endDate: LocalDate, active: Boolean = false): Semester {
        // Prüfe auf doppelte Namen
        semesterRepository.findByName(name)?.let {
            throw IllegalArgumentException("Semester with name '$name' already exists")
        }
        semesterRepository.findByShortName(shortName)?.let {
            throw IllegalArgumentException("Semester with short name '$shortName' already exists")
        }

        // Validiere Datumsbereich
        if (startDate >= endDate) {
            throw IllegalArgumentException("Start date must be before end date")
        }

        val semester =
            Semester(
                name = name,
                shortName = shortName,
                startDate = startDate,
                endDate = endDate,
                active = active,
            )

        return semesterRepository.save(semester)
    }

    /** Aktiviert ein Semester und deaktiviert alle anderen */
    fun activateSemester(semesterId: Long): Semester {
        val semester =
            semesterRepository.findByIdOrNull(semesterId)
                ?: throw IllegalArgumentException("Semester with ID $semesterId not found")

        // Deaktiviere alle anderen Semester
        val activeSemesters = semesterRepository.findByActive(true)
        activeSemesters.forEach {
            it.active = false
            semesterRepository.save(it)
        }

        // Aktiviere das gewählte Semester
        semester.active = true
        return semesterRepository.save(semester)
    }

    /** Gibt das aktuell aktive Semester zurück */
    @Transactional(readOnly = true)
    fun getActiveSemester(): Semester? = semesterRepository.findActiveSemesters().firstOrNull()

    /** Gibt das Semester zurück, das zu einem bestimmten Datum aktiv ist */
    @Transactional(readOnly = true)
    fun getCurrentSemester(date: LocalDate = LocalDate.now()): Semester? = semesterRepository.findCurrentSemester(date)

    /** Gibt alle Semester zurück, sortiert nach Startdatum */
    @Transactional(readOnly = true)
    fun getAllSemesters(): List<Semester> = semesterRepository.findAll().sortedByDescending { it.startDate }

    /** Gibt alle aktiven Semester zurück */
    @Transactional(readOnly = true)
    fun getActiveSemesters(): List<Semester> = semesterRepository.findActiveSemesters()

    /** Aktualisiert ein Semester */
    fun updateSemester(semesterId: Long, updates: SemesterUpdateRequest): Semester {
        val semester =
            semesterRepository.findByIdOrNull(semesterId)
                ?: throw IllegalArgumentException("Semester with ID $semesterId not found")

        updates.name?.let { name ->
            semesterRepository.findByName(name)?.let {
                if (it.id != semesterId) {
                    throw IllegalArgumentException("Semester with name '$name' already exists")
                }
            }
            semester.name = name
        }

        updates.shortName?.let { shortName ->
            semesterRepository.findByShortName(shortName)?.let {
                if (it.id != semesterId) {
                    throw IllegalArgumentException(
                        "Semester with short name '$shortName' already exists",
                    )
                }
            }
            semester.shortName = shortName
        }

        updates.startDate?.let { startDate ->
            if (startDate >= (updates.endDate ?: semester.endDate)) {
                throw IllegalArgumentException("Start date must be before end date")
            }
            semester.startDate = startDate
        }

        updates.endDate?.let { endDate ->
            if ((updates.startDate ?: semester.startDate) >= endDate) {
                throw IllegalArgumentException("Start date must be before end date")
            }
            semester.endDate = endDate
        }

        return semesterRepository.save(semester)
    }
}

/** Request-Klasse für Semester Updates */
data class SemesterUpdateRequest(
    val name: String? = null,
    val shortName: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)
