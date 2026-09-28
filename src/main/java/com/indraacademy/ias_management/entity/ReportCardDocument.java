package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Report Card V2: one student's frozen report card in one publication version — the snapshot of
 * everything printed, the stored PDF rendered from it and its own verification token. Never
 * recalculated or rewritten after publishing; later versions are new documents.
 */
@Entity
@Table(name = "report_card_document")
@Data
public class ReportCardDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "publication_id", nullable = false)
    private Long publicationId;

    @Column(name = "setup_id", nullable = false)
    private Long setupId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportCardPublicationStatus status;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "verification_token", nullable = false, length = 64, unique = true)
    private String verificationToken;

    @Column(name = "reference", nullable = false, length = 20, unique = true)
    private String reference;

    @Column(name = "school_name", nullable = false)
    private String schoolName;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "session_label", nullable = false, length = 20)
    private String sessionLabel;

    @Column(name = "class_name", nullable = false, length = 100)
    private String className;

    @Column(name = "section_name", length = 50)
    private String sectionName;

    @Column(name = "student_name", nullable = false)
    private String studentName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private String snapshot;

    @Column(name = "snapshot_sha256", nullable = false, length = 64)
    private String snapshotSha256;

    @Column(name = "pdf_object_key", nullable = false, length = 512)
    private String pdfObjectKey;

    @Column(name = "pdf_sha256", nullable = false, length = 64)
    private String pdfSha256;

    @Column(name = "pdf_size", nullable = false)
    private Long pdfSize;

    @Column(name = "renderer_version", nullable = false, length = 60)
    private String rendererVersion;

    @Column(name = "rendered_at", nullable = false)
    private LocalDateTime renderedAt;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
