package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Entity
@Table(name = "lecturers")
class Lecturer(
    @Column(name = "name", nullable = false, length = 200)
    @field:NotBlank
    @field:Size(max = 200)
    var name: String,
    @Column(name = "title", length = 50) @field:Size(max = 50) var title: String? = null,
    @Column(name = "email", length = 150)
    @field:Email
    @field:Size(max = 150)
    var email: String? = null,
    @Column(name = "department", length = 100)
    @field:Size(max = 100)
    var department: String? = null,
    @Column(name = "active") var active: Boolean = true
) : BaseEntity() {

    @OneToMany(mappedBy = "lecturer", fetch = FetchType.LAZY)
    var courses: MutableSet<Course> = mutableSetOf()

    val fullName: String
        get() = if (title != null) "$title $name" else name

    override fun toString(): String {
        return "Lecturer(id=$id, name='$name', title='$title')"
    }
}
