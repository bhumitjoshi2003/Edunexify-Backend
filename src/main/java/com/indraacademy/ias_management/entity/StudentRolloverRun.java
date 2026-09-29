package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Audit/reporting record of one executed year-end batch (Student Promotion). Individual decisions
 * are still applied per student by the existing year-end and exit logic; this row only records
 * who ran the batch, when, for which sessions/class, and the outcome counts.
 */
@Entity
@Table(name = "student_rollover_run")
@Data
public class StudentRolloverRun {

    public enum Status { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "source_session_id", nullable = false)
    private Long sourceSessionId;

    @Column(name = "target_session_id", nullable = false)
    private Long targetSessionId;

    /** Null when the batch covered the whole school. */
    @Column(name = "class_id")
    private Long classId;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status;

    @Column(name = "total_students", nullable = false)
    private int totalStudents;

    @Column(name = "promoted_count", nullable = false)
    private int promotedCount;

    @Column(name = "detained_count", nullable = false)
    private int detainedCount;

    @Column(name = "pass_out_count", nullable = false)
    private int passOutCount;

    @Column(name = "transfer_count", nullable = false)
    private int transferCount;

    /** Kept only for schema compatibility (V85): Withdraw is no longer a year-end decision, so
     *  this is always 0 for new runs. */
    @Column(name = "withdraw_count", nullable = false)
    private int withdrawCount;

    @Column(name = "pending_count", nullable = false)
    private int pendingCount;

    @Column(name = "already_applied_count", nullable = false)
    private int alreadyAppliedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;
}
