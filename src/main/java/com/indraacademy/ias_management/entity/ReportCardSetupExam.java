package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/** Report Card V2: an existing Results Phase 1 exam that is part of a setup. */
@Entity
@Table(name = "report_card_setup_exam",
        uniqueConstraints = @UniqueConstraint(name = "uq_report_card_setup_exam_setup_exam",
                columnNames = {"setup_id", "exam_config_id"}))
@Data
public class ReportCardSetupExam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "setup_id", nullable = false)
    private Long setupId;

    @Column(name = "exam_config_id", nullable = false)
    private Long examConfigId;

    @Column(name = "term_id")
    private Long termId;

    /** Fraction within its term (or of the final result without terms); WEIGHTED setups only. */
    @Column(name = "weight", precision = 7, scale = 4)
    private BigDecimal weight;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;
}
