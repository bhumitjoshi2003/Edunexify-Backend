package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A teacher's own leave request — a date range, unlike student {@link Leave} (single day). Staff
 * leave is naturally multi-day (sick leave, vacation), and a range is also what
 * TeacherAttendanceService needs to answer "does an approved leave cover this date" without one
 * row per day — see {@code TeacherLeaveRepository.findApprovedOverlapping}, deliberately mirroring
 * {@link SchoolHoliday}'s existing range-overlap pattern rather than Leave's single-day one.
 *
 * <p>{@link LeaveStatus} is reused as-is from the student Leave system — PENDING/APPROVED/REJECTED
 * carries no student-specific meaning, so a second enum would be pure duplication.
 *
 * <p>Kept as its own table rather than folded into {@code leaves}, the same way TeacherAttendance
 * is kept separate from student Attendance rather than made polymorphic — see that entity's
 * Javadoc for the same rationale.
 */
@Entity
@Data
@Table(name = "teacher_leave",
        indexes = {
                @Index(name = "idx_teacher_leave_teacher", columnList = "teacher_id, school_id"),
                @Index(name = "idx_teacher_leave_school_status", columnList = "school_id, status"),
                @Index(name = "idx_teacher_leave_dates", columnList = "school_id, start_date, end_date")
        })
public class TeacherLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "teacher_id", nullable = false)
    private String teacherId;

    @Column(name = "teacher_name", nullable = false)
    private String teacherName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(20)")
    private LeaveStatus status = LeaveStatus.PENDING;

    @CreationTimestamp
    @Column(name = "applied_date", nullable = false, updatable = false)
    private LocalDateTime appliedDate;

    // ── Decision / cancellation history (Leave Phase 1) ───────────────────
    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;
}
