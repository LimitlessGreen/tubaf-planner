package de.tubaf.planner.service

import de.tubaf.planner.model.DegreeType
import de.tubaf.planner.model.StudyProgram
import de.tubaf.planner.repository.StudyProgramRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class StudyProgramService(private val studyProgramRepository: StudyProgramRepository) {

    /** Erstellt einen neuen Studiengang */
    fun createStudyProgram(code: String, name: String, degreeType: DegreeType, facultyId: Int? = null): StudyProgram {
        // Prüfe auf doppelten Code
        studyProgramRepository.findByCode(code)?.let {
            throw IllegalArgumentException("StudyProgram with code '$code' already exists")
        }

        val studyProgram =
            StudyProgram(code = code, name = name, degreeType = degreeType, facultyId = facultyId)

        return studyProgramRepository.save(studyProgram)
    }

    /** Gibt alle aktiven Studiengänge zurück */
    @Transactional(readOnly = true)
    fun getActiveStudyPrograms(): List<StudyProgram> = studyProgramRepository.findByActive(true)

    /** Gibt Studiengänge nach Degree Type zurück */
    @Transactional(readOnly = true)
    fun getStudyProgramsByDegreeType(degreeType: DegreeType): List<StudyProgram> =
        studyProgramRepository.findByDegreeTypeAndActive(degreeType, true)

    /** Sucht Studiengänge nach Namen */
    @Transactional(readOnly = true)
    fun searchStudyPrograms(searchTerm: String): List<StudyProgram> = studyProgramRepository.findByNameContainingIgnoreCase(searchTerm)

    /** Gibt Studiengänge für ein Semester zurück */
    @Transactional(readOnly = true)
    fun getStudyProgramsBySemester(semesterId: Long): List<StudyProgram> = studyProgramRepository.findBySemesterIdAndActive(semesterId)

    /** Aktiviert oder deaktiviert einen Studiengang */
    fun setStudyProgramActive(studyProgramId: Long, active: Boolean): StudyProgram {
        val studyProgram =
            studyProgramRepository.findByIdOrNull(studyProgramId)
                ?: throw IllegalArgumentException("StudyProgram with ID $studyProgramId not found")

        studyProgram.active = active
        return studyProgramRepository.save(studyProgram)
    }

    /** Aktualisiert einen Studiengang */
    fun updateStudyProgram(studyProgramId: Long, updates: StudyProgramUpdateRequest): StudyProgram {
        val studyProgram =
            studyProgramRepository.findByIdOrNull(studyProgramId)
                ?: throw IllegalArgumentException("StudyProgram with ID $studyProgramId not found")

        updates.code?.let { code ->
            studyProgramRepository.findByCode(code)?.let {
                if (it.id != studyProgramId) {
                    throw IllegalArgumentException("StudyProgram with code '$code' already exists")
                }
            }
            studyProgram.code = code
        }

        updates.name?.let { studyProgram.name = it }
        updates.degreeType?.let { studyProgram.degreeType = it }
        updates.facultyId?.let { studyProgram.facultyId = it }
        updates.active?.let { studyProgram.active = it }

        return studyProgramRepository.save(studyProgram)
    }

    /** Gibt Statistiken für Studiengänge zurück */
    @Transactional(readOnly = true)
    fun getStudyProgramStatistics(): StudyProgramStatistics {
        val all = studyProgramRepository.findAll()
        val active = all.filter { it.active }
        val byDegreeType = active.groupBy { it.degreeType }

        return StudyProgramStatistics(
            totalPrograms = all.size,
            activePrograms = active.size,
            inactivePrograms = all.size - active.size,
            programsByDegreeType = byDegreeType.mapValues { it.value.size },
        )
    }
}

/** Request-Klasse für StudyProgram Updates */
data class StudyProgramUpdateRequest(
    val code: String? = null,
    val name: String? = null,
    val degreeType: DegreeType? = null,
    val facultyId: Int? = null,
    val active: Boolean? = null,
)

/** Statistiken für Studiengänge */
data class StudyProgramStatistics(
    val totalPrograms: Int,
    val activePrograms: Int,
    val inactivePrograms: Int,
    val programsByDegreeType: Map<DegreeType, Int>,
)
