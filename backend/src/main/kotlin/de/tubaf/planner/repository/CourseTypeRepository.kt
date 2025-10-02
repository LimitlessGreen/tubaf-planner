package de.tubaf.planner.repository

import de.tubaf.planner.model.CourseType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseTypeRepository : JpaRepository<CourseType, Long> {

    fun findByCode(code: String): CourseType?

    fun findByName(name: String): CourseType?

    fun findAllByOrderByCode(): List<CourseType>
}
