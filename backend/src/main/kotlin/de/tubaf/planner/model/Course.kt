package de.tubaf.planner.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Entity
@Table(name = "courses")
class Course(
    @Column(name = "name", nullable = false, length = 200)
    @field:NotBlank
    @field:Size(max = 200)
    var name: String,
    @Column(name = "course_number", length = 20)
    @field:Size(max = 20)
    var courseNumber: String? = null,
    @Column(name = "description", columnDefinition = "TEXT") var description: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "semester_id", nullable = false)
    var semester: Semester,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lecturer_id", nullable = false)
    var lecturer: Lecturer,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_type_id", nullable = false)
    var courseType: CourseType,
    @Column(name = "sws") var sws: Int? = null,
    @Column(name = "ects_credits") var ectsCredits: Double? = null,
    @Column(name = "active") var active: Boolean = true
) : BaseEntity() {

    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var scheduleEntries: MutableSet<ScheduleEntry> = mutableSetOf()

    @OneToMany(
        mappedBy = "course",
        cascade = [CascadeType.ALL],
        fetch = FetchType.LAZY,
        orphanRemoval = true
    )
    var courseStudyPrograms: MutableSet<CourseStudyProgram> = mutableSetOf()

    fun addStudyProgram(studyProgram: StudyProgram, semester: Int? = null) {
        val courseStudyProgram = CourseStudyProgram(this, studyProgram, semester)
        courseStudyPrograms.add(courseStudyProgram)
        studyProgram.courseStudyPrograms.add(courseStudyProgram)
    }

    fun removeStudyProgram(studyProgram: StudyProgram) {
        courseStudyPrograms.removeIf { it.studyProgram == studyProgram }
        studyProgram.courseStudyPrograms.removeIf { it.course == this }
    }

    override fun toString(): String {
        return "Course(id=$id, name='$name', courseNumber='$courseNumber', lecturer=${lecturer.name})"
    }
}
