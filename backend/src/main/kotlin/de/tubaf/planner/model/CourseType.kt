package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Entity
@Table(name = "course_types")
class CourseType(
    @Column(name = "code", nullable = false, unique = true, length = 1)
    @field:NotBlank
    @field:Size(min = 1, max = 1)
    var code: String,
    @Column(name = "name", nullable = false, length = 50)
    @field:NotBlank
    @field:Size(max = 50)
    var name: String,
    @Column(name = "description", columnDefinition = "TEXT") var description: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @OneToMany(mappedBy = "courseType", fetch = FetchType.LAZY)
    var courses: MutableSet<Course> = mutableSetOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourseType) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String = "CourseType(id=$id, code='$code', name='$name')"
}
