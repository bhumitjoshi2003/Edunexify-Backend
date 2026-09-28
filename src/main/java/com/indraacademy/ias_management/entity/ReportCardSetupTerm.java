package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/** Report Card V2: optional grouping of a setup's exams, e.g. Term 1 / Term 2. */
@Entity
@Table(name = "report_card_setup_term")
@Data
public class ReportCardSetupTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "setup_id", nullable = false)
    private Long setupId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    /** Fraction of the final result (0.5 = 50%); WEIGHTED setups only. */
    @Column(name = "weight", precision = 7, scale = 4)
    private BigDecimal weight;
}
