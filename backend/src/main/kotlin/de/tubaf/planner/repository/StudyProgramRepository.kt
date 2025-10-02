package de.tubaf.planner.repository

import de.tubaf.planner.model.DegreeType
import de.tubaf.planner.model.StudyProgram
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface StudyProgramRepository : JpaRepository<StudyProgram, Long> {

    fun findByCode(code: String): StudyProgram?

    fun findByActive(active: Boolean): List<StudyProgram>

    fun findByDegreeType(degreeType: DegreeType): List<StudyProgram>

    fun findByDegreeTypeAndActive(degreeType: DegreeType, active: Boolean): List<StudyProgram>

    fun findByNameContainingIgnoreCase(name: String): List<StudyProgram>

    @Query(
        """
        SELECT sp FROM StudyProgram sp 
        WHERE sp.active = true 
        AND sp.degreeType IN :degreeTypes
        ORDER BY sp.degreeType, sp.name
        """
    )
    fun findActiveByDegreeTypes(
        @Param("degreeTypes") degreeTypes: List<DegreeType>
    ): List<StudyProgram>

    @Query(
        """
        SELECT DISTINCT sp FROM StudyProgram sp 
        JOIN sp.courseStudyPrograms csp 
        JOIN csp.course c 
        WHERE c.semester.id = :semesterId 
        AND sp.active = true
        """
    )
    fun findBySemesterIdAndActive(@Param("semesterId") semesterId: Long): List<StudyProgram>
}
