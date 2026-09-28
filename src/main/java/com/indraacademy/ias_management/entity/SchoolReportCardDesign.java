package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Report Card V2: the school's report-card look (one per school). Logo, name, address, board,
 * affiliation number, school code and header image stay on {@link School}.
 */
@Entity
@Table(name = "school_report_card_design")
@Data
public class SchoolReportCardDesign {

    public enum WatermarkMode { NONE, TEXT, LOGO }

    @Id
    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "motto", length = 150)
    private String motto;

    @Column(name = "footer_text", length = 300)
    private String footerText;

    @Enumerated(EnumType.STRING)
    @Column(name = "watermark_mode", nullable = false, length = 10)
    private WatermarkMode watermarkMode = WatermarkMode.NONE;

    @Column(name = "watermark_text", length = 40)
    private String watermarkText;

    @Column(name = "show_photo", nullable = false)
    private boolean showPhoto = true;

    @Column(name = "show_qr", nullable = false)
    private boolean showQr = true;

    @Column(name = "show_attendance", nullable = false)
    private boolean showAttendance = true;

    @Column(name = "show_co_scholastic", nullable = false)
    private boolean showCoScholastic = true;

    @Column(name = "show_teacher_remark", nullable = false)
    private boolean showTeacherRemark = true;

    @Column(name = "show_principal_remark", nullable = false)
    private boolean showPrincipalRemark = true;

    @Column(name = "show_rank", nullable = false)
    private boolean showRank = true;

    @Column(name = "show_promotion", nullable = false)
    private boolean showPromotion = false;

    @Column(name = "teacher_signature_label", nullable = false, length = 60)
    private String teacherSignatureLabel = "Class Teacher";

    @Column(name = "principal_signature_label", nullable = false, length = 60)
    private String principalSignatureLabel = "Principal";

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "revision", nullable = false)
    private long revision;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** The design a school gets before it saves its own. */
    public static SchoolReportCardDesign defaults(Long schoolId) {
        SchoolReportCardDesign d = new SchoolReportCardDesign();
        d.setSchoolId(schoolId);
        return d;
    }
}
