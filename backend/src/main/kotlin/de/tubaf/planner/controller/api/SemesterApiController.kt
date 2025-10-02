package de.tubaf.planner.controller.api

import de.tubaf.planner.service.SemesterService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/semesters")
@Tag(name = "Semesters", description = "Semester Management API")
class SemesterApiController(
    private val semesterService: SemesterService
) {

    @GetMapping
    @Operation(summary = "Get all semesters")
    fun getAllSemesters() = semesterService.getAllSemesters()

    @GetMapping("/active")
    @Operation(summary = "Get all active semesters")
    fun getActiveSemesters() = semesterService.getActiveSemesters()

    @GetMapping("/{id}")
    @Operation(summary = "Get semester by ID")
    fun getSemesterById(@PathVariable id: Long) = 
        semesterService.getAllSemesters().find { it.id == id }
            ?: throw IllegalArgumentException("Semester not found")

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate semester")
    fun activateSemester(@PathVariable id: Long) {
        semesterService.activateSemester(id)
    }
}