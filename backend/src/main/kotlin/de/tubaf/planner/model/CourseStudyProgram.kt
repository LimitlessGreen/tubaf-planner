package de.tubaf.planner.model

import jakarta.persistence.*

@Entity
@Table(
    name = "course_study_programs",
    uniqueConstraints = [UniqueConstraint(columnNames = ["course_id", "study_program_id"])]
)
class CourseStudyProgram(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    var course: Course,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "study_program_id", nullable = false)
    var studyProgram: StudyProgram,
    @Column(name = "semester") var semester: Int? = null
) : BaseEntity() {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourseStudyProgram) return false

        return course.id == other.course.id && studyProgram.id == other.studyProgram.id
    }

    override fun hashCode(): Int {
        var result = course.id?.hashCode() ?: 0
        result = 31 * result + (studyProgram.id?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "CourseStudyProgram(course=${course.name}, studyProgram=${studyProgram.code}, semester=$semester)"
    }
}
