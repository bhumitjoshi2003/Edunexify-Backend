package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "teacher_substitution")
public class TeacherSubstitution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Version @Column(nullable = false)
    private long revision;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;
    @Column(name = "academic_session_id", nullable = false)
    private Long academicSessionId;
    @Column(name = "substitution_date", nullable = false)
    private LocalDate date;
    @Column(name = "timetable_entry_id", nullable = false)
    private Long timetableEntryId;
    @Column(name = "original_teacher_id", nullable = false)
    private String originalTeacherId;
    @Column(name = "original_teacher_name", nullable = false)
    private String originalTeacherName;
    @Column(name = "substitute_teacher_id", nullable = false)
    private String substituteTeacherId;
    @Column(name = "substitute_teacher_name", nullable = false)
    private String substituteTeacherName;
    @Column(name = "class_name", nullable = false)
    private String className;
    @Column(name = "section_name")
    private String sectionName;
    @Column(name = "subject_name", nullable = false)
    private String subjectName;
    @Column(name = "period_number", nullable = false)
    private Integer periodNumber;
    @Column(name = "start_time", nullable = false)
    private String startTime;
    @Column(name = "end_time", nullable = false)
    private String endTime;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private TeacherSubstitutionStatus status = TeacherSubstitutionStatus.ACTIVE;
    @Column(name = "assigned_by", nullable = false)
    private String assignedBy;
    @CreationTimestamp @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Who removed the cover and when (assigned_by keeps who assigned it). */
    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** Optional admin note for the substitute (≤ 300 characters). */
    @Column(name = "note", length = 300)
    private String note;

    /** Why the original teacher was unavailable when the cover was assigned: LEAVE or ABSENCE. */
    @Column(name = "reason_source", length = 20)
    private String reasonSource;
}
