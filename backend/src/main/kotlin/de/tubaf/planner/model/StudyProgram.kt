package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Entity
@Table(name = "study_programs")
class StudyProgram(
    @Column(name = "code", nullable = false, unique = true, length = 10)
    @field:NotBlank
    @field:Size(max = 10)
    var code: String,
    @Column(name = "name", nullable = false, length = 200)
    @field:NotBlank
    @field:Size(max = 200)
    var name: String,
    @Column(name = "faculty_id") var facultyId: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "degree_type", nullable = false, length = 20)
    var degreeType: DegreeType,
    @Column(name = "active") var active: Boolean = true,
) : BaseEntity() {

    @OneToMany(mappedBy = "studyProgram", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var courseStudyPrograms: MutableSet<CourseStudyProgram> = mutableSetOf()

    override fun toString(): String = "StudyProgram(id=$id, code='$code', name='$name', degreeType=$degreeType)"
}

enum class DegreeType {
    BACHELOR,
    MASTER,
    DIPLOMA,
    DOCTORATE,
}
