package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** Report Card V2: one student's grade in one school activity on one card. */
@Entity
@Table(name = "report_card_co_scholastic_v2",
        uniqueConstraints = @UniqueConstraint(name = "uq_report_card_co_scholastic_v2_setup_student_activity",
                columnNames = {"setup_id", "student_id", "activity_id"}))
@Data
public class ReportCardCoScholasticV2 {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "setup_id", nullable = false)
    private Long setupId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "grade", nullable = false, length = 5)
    private String grade;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
