package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Report Card V2: the class teacher's and principal's remarks for one student on one card. */
@Entity
@Table(name = "report_card_remark_v2",
        uniqueConstraints = @UniqueConstraint(name = "uq_report_card_remark_v2_setup_student",
                columnNames = {"setup_id", "student_id"}))
@Data
public class ReportCardRemarkV2 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "setup_id", nullable = false)
    private Long setupId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "teacher_remark", columnDefinition = "TEXT")
    private String teacherRemark;

    @Column(name = "teacher_updated_by")
    private String teacherUpdatedBy;

    @Column(name = "teacher_updated_at")
    private LocalDateTime teacherUpdatedAt;

    @Column(name = "principal_remark", columnDefinition = "TEXT")
    private String principalRemark;

    @Column(name = "principal_updated_by")
    private String principalUpdatedBy;

    @Column(name = "principal_updated_at")
    private LocalDateTime principalUpdatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
