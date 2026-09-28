package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassRemarksDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardPublicationDTO;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase E6E: real-PostgreSQL coverage for enrollment-authoritative historical report cards.
 * Synthetic fixtures use negative identifiers and every test transaction rolls back
 * (@DataJpaTest default), so no cleanup step is required.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReportCardDataAssembler.class, MarkService.class, StudentTemporalMembershipResolver.class,
        AttendanceService.class, AcademicSessionService.class, ReportCardTemplateService.class,
        WeightageCalculationEngine.class, RemarksService.class, ReportCardPublicationService.class, ReportCardPdfGenerator.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        ReportCardHistoricalPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ReportCardHistoricalPostgresIT {

    private static final long SCHOOL = -99601L;
    private static final long OTHER_SCHOOL = -99602L;
    private static final long SESSION_PRIOR = -99701L;
    private static final long SESSION_CURRENT = -99702L;
    private static final long SESSION_OTHER = -99703L;
    private static final long CLASS_9 = -99801L;
    private static final long CLASS_10 = -99802L;
    private static final long CLASS_OTHER = -99803L;
    private static final long SECTION_A = -99901L;
    private static final long SECTION_B = -99902L;
    private static final long SECTION_10A = -99903L;
    private static final long SECTION_10B = -99904L;
    private static final long SECTION_OTHER = -99905L;
    private static final long ASSESSMENT_GROUP = -100001L;
    private static final long TEMPLATE = -100101L;
    private static final String SESSION_PRIOR_LABEL = "2025-2026";
    private static final String SESSION_CURRENT_LABEL = "2026-2027";
    private static final String STUDENT = "E6E-PG-S1";
    private static final String LEGACY_STUDENT = "E6E-PG-LEGACY";
    private static final String OTHER_STUDENT = "E6E-PG-OTHER";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired ReportCardDataAssembler assembler;
    @Autowired RemarksService remarksService;
    @Autowired ReportCardPublicationService publicationService;
    @Autowired StudentRepository studentRepository;
    @Autowired MarkService markService;
    @Autowired ReportCardPdfGenerator pdfGenerator;
    @MockBean ObjectStorageService objectStorageService; // ReportCardDataAssembler's logo lookup
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean StudentService studentService;
    @MockBean BusinessNotificationService businessNotificationService;
    @MockBean ReportCardEmailBlastService reportCardEmailBlastService;
    @MockBean TimetableSessionAccessService timetableSessionAccessService;
    @MockBean TeacherClassScopeService teacherClassScopeService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void fixtures() {
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(studentService.getStudent(anyString()))
                .thenAnswer(inv -> studentRepository.findByStudentIdAndSchoolId(inv.getArgument(0), SCHOOL));
        lenient().when(studentService.getActiveStudentsByClass(anyString()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndStatusAndSchoolId(
                        inv.getArgument(0), StudentStatus.ACTIVE, SCHOOL));
        lenient().when(studentService.getActiveStudentsByClassAndSection(anyString(), anyLong()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(
                        inv.getArgument(0), inv.getArgument(1), StudentStatus.ACTIVE, SCHOOL));

        insertSchool(SCHOOL, "e6e-rc-it");
        insertSchool(OTHER_SCHOOL, "e6e-rc-it-other");
        insertSession(SESSION_PRIOR, SCHOOL, SESSION_PRIOR_LABEL, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertSession(SESSION_CURRENT, SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertSession(SESSION_OTHER, OTHER_SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertClass(CLASS_9, SCHOOL, "9");
        insertClass(CLASS_10, SCHOOL, "10");
        insertClass(CLASS_OTHER, OTHER_SCHOOL, "9");
        insertSection(SECTION_A, SCHOOL, CLASS_9, "A");
        insertSection(SECTION_B, SCHOOL, CLASS_9, "B");
        insertSection(SECTION_10A, SCHOOL, CLASS_10, "A");
        insertSection(SECTION_10B, SCHOOL, CLASS_10, "B");
        insertSection(SECTION_OTHER, OTHER_SCHOOL, CLASS_OTHER, "A");
        insertStudent(STUDENT, SCHOOL, "10", CLASS_10, SECTION_10B);
        insertStudent(LEGACY_STUDENT, SCHOOL, "9", CLASS_9, SECTION_A);
        insertStudent(OTHER_STUDENT, OTHER_SCHOOL, "9", CLASS_OTHER, SECTION_OTHER);

        jdbc.update("INSERT INTO assessment_group (id,school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,?,?,?,?,?,0)", ASSESSMENT_GROUP, SCHOOL, SESSION_CURRENT_LABEL, "9", "Annual", "EXAM_BASED");
        jdbc.update("INSERT INTO report_card_template (id,school_id,name,assessment_group_id,is_default,is_active) " +
                "VALUES (?,?,?,?,false,true)", TEMPLATE, SCHOOL, "Standard", ASSESSMENT_GROUP);
        jdbc.update("INSERT INTO report_card_template_section (id,template_id,section_type,enabled,display_order) " +
                "VALUES (?,?,?,true,0)", TEMPLATE - 1, TEMPLATE, "ATTENDANCE");
    }

    @Test
    void promotedStudentAccessesPriorSessionReportCardWithExistingMarks() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long entry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(STUDENT, entry, 85.0);

        ReportCardDataDTO dto = assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);

        assertThat(dto.getClassName()).isEqualTo("9"); // NOT the live "10"
        assertThat(dto.getStudentId()).isEqualTo(STUDENT);
    }

    @Test
    void configuredButUnmarkedHistoricalReportResolvesViaEnrollment() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        // No marks at all — the class-9 context must still resolve purely from enrollment.
        var context = assembler.resolveHistoricalContext(STUDENT, SESSION_PRIOR_LABEL, null);
        assertThat(context.className()).isEqualTo("9");
    }

    @Test
    void historicalSectionComesFromEnrollmentNotLiveStudent() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");

        var context = assembler.resolveHistoricalContext(STUDENT, SESSION_PRIOR_LABEL, null);

        assertThat(context.className()).isEqualTo("9");
        assertThat(context.sectionId()).isEqualTo(SECTION_A); // NOT the live section 10B
    }

    @Test
    void sectionOnlyTransitionDoesNotCreateASecondClassReport() {
        // Same class ("9") throughout the session, section changes mid-session — must resolve to
        // ONE class context, with the LATEST section shown (documented compatibility rule).
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 8, 31), "SECTION_CHANGE");
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_B,
                LocalDate.of(2025, 9, 1), LocalDate.of(2026, 3, 31), "SECTION_CHANGE");

        var context = assembler.resolveHistoricalContext(STUDENT, SESSION_PRIOR_LABEL, null);

        assertThat(context.className()).isEqualTo("9"); // one class context, not two
        assertThat(context.sectionId()).isEqualTo(SECTION_B); // the later segment's section
    }

    @Test
    void midSessionClassChangeWithMarksInBothClassesIsAmbiguousAndClassIdDisambiguates() {
        // A genuine mid-session class change with EXISTING MARKS recorded under both classes for
        // the same session label — two legitimate report-card contexts.
        long examNine = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "Unit Test");
        long entryNine = insertSubjectEntry(examNine, "Math", 50, null);
        insertMark(STUDENT, entryNine, 40.0);
        long examTen = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "10", "Unit Test");
        long entryTen = insertSubjectEntry(examTen, "Math", 50, null);
        insertMark(STUDENT, entryTen, 45.0);

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT, SESSION_CURRENT_LABEL, null))
                .isInstanceOf(ReportCardDataAssembler.ReportCardContextAmbiguousException.class);

        var resolved = assembler.resolveHistoricalContext(STUDENT, SESSION_CURRENT_LABEL, CLASS_9);
        assertThat(resolved.className()).isEqualTo("9");
    }

    @Test
    void plannedAndCancelledEnrollmentNeverEstablishReportCardMembership() {
        // Establish realized enrollment adoption via a DIFFERENT, earlier session, so the
        // requested session's PLANNED/CANCELLED-only history is a genuine AUTHORITATIVE_GAP
        // (not simply "never adopted" — which would safely fall back to the live class instead).
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from) " +
                        "VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2027-01-01')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_9, "9", SECTION_A, "A");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,'CANCELLED',DATE '2026-09-01',DATE '2026-09-01','CANCELLED_BEFORE_START')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_9, "9", SECTION_A, "A");

        // No marks, no realized enrollment — nothing to build a report card from.
        assertThatThrownBy(() -> assembler.resolveHistoricalContext(STUDENT, SESSION_CURRENT_LABEL, null))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void legacyNoEnrollmentReportRemainsAccessibleFromDurableMarkEvidence() {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long entry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(LEGACY_STUDENT, entry, 60.0);

        ReportCardDataDTO dto = assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);

        assertThat(dto.getClassName()).isEqualTo("9");
    }

    @Test
    void historicalPublicationAccessSurvivesPromotionAndWrongPublicationIsRejected() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        var context = assembler.resolveHistoricalContext(STUDENT, SESSION_PRIOR_LABEL, null);
        assertThat(publicationService.isPublished(TEMPLATE, SESSION_PRIOR_LABEL, context.className())).isTrue();

        // A publication for a DIFFERENT class must never satisfy this student's access check.
        assertThat(publicationService.isPublished(TEMPLATE, SESSION_PRIOR_LABEL, "10")).isFalse();
    }

    @Test
    void subjectAndOverallGradesComeFromTheBackendGradingPolicy() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, ASSESSMENT_GROUP, exam, 1.0);
        insertMark(STUDENT, insertSubjectEntry(exam, "Math", 100, null), 95.0);
        insertMark(STUDENT, insertSubjectEntry(exam, "Science", 100, null), 20.0);

        ReportCardDataDTO dto = assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);

        var rows = dto.getWeightedResult().getMarksTable().getSubjectRows();
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            assertThat(row.getGrade()).isNotBlank()
                    .isEqualTo(GradingPolicy.grade(row.getWeightedPercentage(), dto.getGradingSystem()));
        }
        assertThat(rows).extracting(r -> r.getGrade()).doesNotHaveDuplicates();
        assertThat(dto.getOverallGrade())
                .isEqualTo(GradingPolicy.grade(dto.getWeightedResult().getWeightedPercentage(), dto.getGradingSystem()));
    }

    @Test
    void publicVerificationRefusesACardWhoseExamWasUnpublished() {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Annual Exam");
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, ASSESSMENT_GROUP, exam, 1.0);
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");
        String token = jdbc.queryForObject("SELECT verification_token FROM report_card_publication WHERE school_id = ? AND template_id = ?",
                String.class, SCHOOL, TEMPLATE);

        var valid = publicationService.verifyByToken(token);
        assertThat(valid.isValid()).isTrue();
        assertThat(valid.getClassName()).isEqualTo("9");

        // The public QR endpoint has no school context: the draft check must use the card's own school.
        when(securityUtil.getSchoolId()).thenReturn(null);
        jdbc.update("UPDATE exam_config SET result_status = 'DRAFT' WHERE id = ?", exam);
        entityManager.clear(); // a new request sees the committed row, not this test's cached entity
        var refused = publicationService.verifyByToken(token);
        assertThat(refused.isValid()).isFalse();
        assertThat(refused.getSchoolName()).isNull();
        assertThat(refused.getClassName()).isNull();
        assertThat(refused.getSession()).isNull();
        assertThat(refused.getPublishedBy()).isNull();
        // Indistinguishable from an unknown token — nothing reveals that a draft exists.
        assertThat(refused.getMessage()).isEqualTo(publicationService.verifyByToken("no-such-token").getMessage());

        jdbc.update("UPDATE exam_config SET result_status = 'PUBLISHED' WHERE id = ?", exam);
        entityManager.clear(); // a new request sees the committed row, not this test's cached entity
        assertThat(publicationService.verifyByToken(token).isValid()).isTrue();
    }

    @Test
    void reportCardAttendanceUsesRealAcademicSessionDates() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        markAttendance(STUDENT, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), "PRESENT");
        markAttendance(STUDENT, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 16), "ABSENT");
        // Outside the session's dates — must not leak into the report card.
        markAttendance(STUDENT, CLASS_9, SECTION_A, LocalDate.of(2026, 4, 10), "ABSENT");

        ReportCardDataDTO dto = assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);

        assertThat(dto.getAttendance()).isNotNull();
        assertThat(dto.getAttendance().getWorkingDays()).isEqualTo(2);
        assertThat(dto.getAttendance().getPresentDays()).isEqualTo(1);
    }

    @Test
    void missingAcademicSessionFailsClearlyRatherThanGuessingADateRange() {
        assertThatThrownBy(() -> assembler.assemble(STUDENT, TEMPLATE, "2099-2100"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void historicalRemarksRosterIncludesAPromotedStudentUsingTheirHistoricalSection() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");

        ClassRemarksDTO sectionA = remarksService.getClassRemarks(TEMPLATE, SESSION_PRIOR_LABEL, "9", SECTION_A);
        assertThat(sectionA.getStudents()).extracting(ClassRemarksDTO.StudentRemarksData::getStudentId).contains(STUDENT);

        ClassRemarksDTO sectionB = remarksService.getClassRemarks(TEMPLATE, SESSION_PRIOR_LABEL, "9", SECTION_B);
        assertThat(sectionB.getStudents()).extracting(ClassRemarksDTO.StudentRemarksData::getStudentId).doesNotContain(STUDENT);
    }

    @Test
    void publicationRecipientRosterIsHistoricalAndIncludesAPromotedStudent() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");

        ReportCardPublicationDTO result = publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        assertThat(result.isPublished()).isTrue();
        verify(businessNotificationService).studentAndParents(
                eq(SCHOOL), eq(STUDENT), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        // Report Card Phase 0: the link opens exactly this student's card for this template/session
        // (and the historical class), never a bare /dashboard/report-card or another student.
        org.mockito.ArgumentCaptor<String> route = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(businessNotificationService).studentAndParents(
                eq(SCHOOL), eq(STUDENT), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), route.capture(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        assertThat(route.getValue()).isEqualTo("/dashboard/report-card?studentId=" + STUDENT + "&session=" + SESSION_PRIOR_LABEL
                + "&templateId=" + TEMPLATE + "&classId=" + CLASS_9);
        assertThat(route.getValue()).doesNotContain(LEGACY_STUDENT);
    }

    // ── Report Card Phase 0: canonical rank, grouped cards, results card ────

    @Test
    void reportCardRankIsTheCanonicalSectionRankSharedWithClassResults() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, ASSESSMENT_GROUP, exam, 1.0);
        long math = insertSubjectEntry(exam, "Math", 100, LocalDate.of(2025, 9, 20));
        insertMark(STUDENT, math, 90.0);
        insertMark(LEGACY_STUDENT, math, 60.0);

        ReportCardDataDTO top = assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);
        ReportCardDataDTO second = assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);

        Integer classResultsRank = markService.getStudentResults(STUDENT, SESSION_PRIOR_LABEL, true).get(0).getOverallRank();
        Integer classResultsRank2 = markService.getStudentResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, true).get(0).getOverallRank();
        assertThat(top.getWeightedResult().getRank()).isEqualTo(1).isEqualTo(classResultsRank);
        assertThat(second.getWeightedResult().getRank()).isEqualTo(2).isEqualTo(classResultsRank2);
        // Precomputed once for bulk / email: identical ranks.
        var ranks = assembler.classRanksForTemplate(TEMPLATE, SESSION_PRIOR_LABEL);
        assertThat(assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL, null, ranks).getWeightedResult().getRank()).isEqualTo(1);

        // A student with a mark missing is unranked, exactly like Class Results.
        long science = insertSubjectEntry(exam, "Science", 100, LocalDate.of(2025, 9, 21));
        insertMark(STUDENT, science, 80.0);
        entityManager.clear();
        assertThat(assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL).getWeightedResult().getRank()).isZero();
        assertThat(assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL).getWeightedResult().getRank()).isEqualTo(1);
    }

    @Test
    void groupBasedCardHasSubjectRowsFromItsTerms() {
        long term1 = ASSESSMENT_GROUP - 11, term2 = ASSESSMENT_GROUP - 12, annual = ASSESSMENT_GROUP - 13, annualTemplate = TEMPLATE - 13;
        for (Object[] g : new Object[][]{{term1, "Term 1", "EXAM_BASED"}, {term2, "Term 2", "EXAM_BASED"}, {annual, "Annual", "GROUP_BASED"}}) {
            jdbc.update("INSERT INTO assessment_group (id,school_id,session,class_name,name,group_type,display_order) VALUES (?,?,?,?,?,?,0)",
                    g[0], SCHOOL, SESSION_CURRENT_LABEL, "9", g[1], g[2]);
        }
        jdbc.update("INSERT INTO assessment_group_composition (school_id,parent_group_id,child_group_id,weightage,display_order) VALUES (?,?,?,?,0)", SCHOOL, annual, term1, 0.5);
        jdbc.update("INSERT INTO assessment_group_composition (school_id,parent_group_id,child_group_id,weightage,display_order) VALUES (?,?,?,?,1)", SCHOOL, annual, term2, 0.5);
        jdbc.update("INSERT INTO report_card_template (id,school_id,name,assessment_group_id,is_default,is_active) VALUES (?,?,?,?,false,true)",
                annualTemplate, SCHOOL, "Annual", annual);
        long ex1 = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long ex2 = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Final");
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)", SCHOOL, term1, ex1, 1.0);
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)", SCHOOL, term2, ex2, 1.0);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(ex1, "Math", 100, null), 80.0);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(ex1, "Science", 100, null), 60.0);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(ex2, "Math", 100, null), 60.0);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(ex2, "Science", 100, null), 100.0);

        ReportCardDataDTO dto = assembler.assemble(LEGACY_STUDENT, annualTemplate, SESSION_PRIOR_LABEL);

        var table = dto.getWeightedResult().getMarksTable();
        assertThat(table).isNotNull();
        assertThat(table.getExamColumns()).extracting(c -> c.getExamName()).containsExactly("Half Yearly", "Final");
        assertThat(table.getExamColumns()).extracting(c -> c.getWeightage()).containsExactly(0.5, 0.5);
        assertThat(table.getSubjectRows()).extracting(r -> r.getSubjectName()).containsExactly("Math", "Science");
        assertThat(table.getSubjectRows().get(0).getWeightedPercentage()).isEqualTo(70.0);
        assertThat(table.getSubjectRows().get(1).getWeightedPercentage()).isEqualTo(80.0);
        assertThat(table.getSubjectRows().get(0).getExamMarks()).extracting(m -> m.getObtained()).containsExactly(80.0, 60.0);
        assertThat(table.getSubjectRows()).allSatisfy(r -> assertThat(r.getGrade()).isNotBlank());
        assertThat(table.getExamTotals()).hasSize(2);
        assertThat(dto.getWeightedResult().getWeightedPercentage()).isEqualTo(75.0);
        assertThat(dto.getWeightedResult().getRank()).isEqualTo(1);
    }

    @Test
    void resultsCardWithoutATemplateUsesTheCanonicalResultsAndRanks() {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long math = insertSubjectEntry(exam, "Math", 100, null);
        long sci = insertSubjectEntry(exam, "Science", 50, null);
        insertMark(LEGACY_STUDENT, math, 72.0);
        insertMark(LEGACY_STUDENT, sci, 45.0);

        ReportCardDataDTO single = assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, exam, null, true);
        var result = markService.getStudentResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, true).get(0);
        assertThat(single.getTemplate()).isNull();
        assertThat(single.getClassName()).isEqualTo("9");
        assertThat(single.getWeightedResult().getMarksTable().getExamColumns()).hasSize(1);
        assertThat(single.getWeightedResult().getMarksTable().getSubjectRows()).extracting(r -> r.getSubjectName()).containsExactly("Math", "Science");
        assertThat(single.getWeightedResult().getMarksTable().getSubjectRows()).extracting(r -> r.getGrade())
                .containsExactly(result.getSubjects().get(0).getGrade(), result.getSubjects().get(1).getGrade());
        assertThat(single.getWeightedResult().getWeightedPercentage()).isEqualTo(result.getPercentage());
        assertThat(single.getOverallGrade()).isEqualTo(result.getGrade());
        assertThat(single.getWeightedResult().getRank()).isEqualTo(result.getOverallRank()).isEqualTo(1);

        // Every exam of the session as columns; students only ever see published ones.
        long draft = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Unit Test");
        jdbc.update("UPDATE exam_config SET result_status = 'DRAFT' WHERE id = ?", draft);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(draft, "Math", 20, null), 10.0);
        entityManager.clear();
        ReportCardDataDTO staffAll = assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, null, null, true);
        ReportCardDataDTO studentAll = assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, null, null, false);
        assertThat(staffAll.getWeightedResult().getMarksTable().getExamColumns()).hasSize(2);
        assertThat(studentAll.getWeightedResult().getMarksTable().getExamColumns()).extracting(c -> c.getExamName()).containsExactly("Half Yearly");
        assertThat(staffAll.getWeightedResult().getWeightedPercentage()).isEqualTo(127.0 / 170.0 * 100.0);
        assertThat(staffAll.getWeightedResult().getRank()).isEqualTo(1);
        assertThat(pdfGenerator.generate(staffAll)).isNotEmpty();
    }

    @Test
    void titlesNameTheExamForOneExamAndAnnualForTheSession() {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        insertMark(LEGACY_STUDENT, insertSubjectEntry(exam, "Math", 100, null), 72.0);
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, ASSESSMENT_GROUP, exam, 1.0);

        assertThat(assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, exam, null, true).getReportTitle())
                .isEqualTo("HALF YEARLY \u2014 REPORT CARD");
        assertThat(assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, null, null, true).getReportTitle())
                .isEqualTo("ANNUAL REPORT CARD");
        // Template card: its assessment group ("Annual" in this fixture) or the branding's exam term.
        assertThat(assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL).getReportTitle()).isEqualTo("ANNUAL REPORT CARD");
        jdbc.update("UPDATE report_card_template SET branding_json = ? WHERE id = ?", "{\"examTerm\":\"Half-Yearly\"}", TEMPLATE);
        entityManager.clear();
        assertThat(assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL).getReportTitle()).isEqualTo("HALF-YEARLY \u2014 REPORT CARD");
    }

    @Test
    void onlyAPublishedTemplateCardCarriesTheVerificationQr() throws Exception {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, ASSESSMENT_GROUP, exam, 1.0);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(exam, "Math", 100, null), 72.0);

        // Not yet published: no token, no QR (same lookup the PDF endpoint does).
        assertThat(publicationService.getVerificationToken(TEMPLATE, SESSION_PRIOR_LABEL, "9")).isEmpty();
        ReportCardDataDTO unpublished = assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);
        assertThat(ReportCardPdfGeneratorTest.qrTexts(pdfGenerator.generate(unpublished))).isEmpty();

        // Published: the token exists and the PDF's QR opens the public verification page for it.
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");
        String token = publicationService.getVerificationToken(TEMPLATE, SESSION_PRIOR_LABEL, "9").orElseThrow();
        ReportCardDataDTO published = assembler.assemble(LEGACY_STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);
        published.setVerificationToken(token);
        assertThat(ReportCardPdfGeneratorTest.qrTexts(pdfGenerator.generate(published)))
                .containsExactly("https://edunexify.co.in/verify-rc?token=" + token);
        assertThat(publicationService.verifyByToken(token).isValid()).isTrue();

        // A results-based card is not a publication: never a token, never a QR.
        ReportCardDataDTO results = assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, exam, null, true);
        assertThat(results.getVerificationToken()).isNull();
        assertThat(ReportCardPdfGeneratorTest.qrTexts(pdfGenerator.generate(results))).isEmpty();
    }

    @Test
    void resultsCardWithNoVisibleResultsIsNotFound() {
        long draft = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Unit Test");
        jdbc.update("UPDATE exam_config SET result_status = 'DRAFT' WHERE id = ?", draft);
        insertMark(LEGACY_STUDENT, insertSubjectEntry(draft, "Math", 20, null), 10.0);
        assertThatThrownBy(() -> assembler.assembleFromResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL, null, null, false))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void tenantIsolationHoldsAcrossEnrollmentAndMarkLookups() {
        insertClosedEnrollment(OTHER_SCHOOL, OTHER_STUDENT, SESSION_OTHER, CLASS_OTHER, SECTION_OTHER,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "SESSION_COMPLETED");

        assertThatThrownBy(() -> assembler.resolveHistoricalContext(OTHER_STUDENT, SESSION_CURRENT_LABEL, null))
                .isInstanceOf(java.util.NoSuchElementException.class);

        ClassRemarksDTO remarks = remarksService.getClassRemarks(TEMPLATE, SESSION_CURRENT_LABEL, "9", null);
        assertThat(remarks.getStudents()).extracting(ClassRemarksDTO.StudentRemarksData::getStudentId)
                .doesNotContain(OTHER_STUDENT);
    }

    @Test
    void historicalReadsPerformZeroWritesAndLeaveTableSignaturesUnchanged() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long entry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(STUDENT, entry, 85.0);

        TableSignature before = signatures();

        assembler.assemble(STUDENT, TEMPLATE, SESSION_PRIOR_LABEL);
        assembler.resolveHistoricalContext(STUDENT, SESSION_PRIOR_LABEL, null);
        remarksService.getClassRemarks(TEMPLATE, SESSION_PRIOR_LABEL, "9", null);
        publicationService.isPublished(TEMPLATE, SESSION_PRIOR_LABEL, "9");
        publicationService.getStatus(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        assertThat(signatures()).isEqualTo(before);
    }

    private record TableSignature(long students, long enrollments, long examConfigs,
                                  long marks, long attendance, long publications, long remarks) {}

    private TableSignature signatures() {
        return new TableSignature(count("student"), count("student_enrollment"), count("exam_config"),
                count("student_mark"), count("student_attendance"), count("report_card_publication"), count("report_card_remark"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,?,'TRIAL',?,4,8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label, LocalDate from, LocalDate to) {
        jdbc.update("INSERT INTO academic_session " +
                        "(id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,?,?,?,false,CURRENT_TIMESTAMP)",
                id, schoolId, label, from, to);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,?,true,false)",
                id, schoolId, name);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,?,true)",
                id, schoolId, classId, name);
    }

    private void insertStudent(String id, long schoolId, String className, long classId, long sectionId) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,name) " +
                        "VALUES (?,?,'ACTIVE',?,?,?,?)",
                id, schoolId, classId, className, sectionId, "Student " + id);
    }

    private void insertActiveEnrollment(String studentId, long sessionId, long classId, long sectionId, LocalDate from) {
        insertEnrollment(SCHOOL, studentId, sessionId, classId, sectionId, "ACTIVE", from, null, null);
    }

    private void insertClosedEnrollment(String studentId, long sessionId, long classId, long sectionId,
                                        LocalDate from, LocalDate to, String reason) {
        insertEnrollment(SCHOOL, studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertClosedEnrollment(long schoolId, String studentId, long sessionId, long classId, long sectionId,
                                        LocalDate from, LocalDate to, String reason) {
        insertEnrollment(schoolId, studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertEnrollment(long schoolId, String studentId, long sessionId, long classId, long sectionId,
                                  String status, LocalDate from, LocalDate to, String reason) {
        String className = (classId == CLASS_9 || classId == CLASS_OTHER) ? "9" : "10";
        String sectionName = (sectionId == SECTION_A || sectionId == SECTION_10A) ? "A" : "B";
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                schoolId, studentId, sessionId, classId, className, sectionId, sectionName,
                status, from, to, reason);
    }

    private long examConfigSeq = -100201L;
    private final java.util.Map<Long, Long> examConfigSchool = new java.util.HashMap<>();

    private long insertExamConfig(long schoolId, String session, String className, String examName) {
        long id = examConfigSeq--;
        jdbc.update("INSERT INTO exam_config (id,school_id,session,class_name,exam_name,result_status) VALUES (?,?,?,?,?,'PUBLISHED')",
                id, schoolId, session, className, examName);
        examConfigSchool.put(id, schoolId);
        return id;
    }

    private long entrySeq = -100301L;

    private long insertSubjectEntry(long examConfigId, String subjectName, int maxMarks, LocalDate examDate) {
        long id = entrySeq--;
        long schoolId = examConfigSchool.get(examConfigId);
        jdbc.update("INSERT INTO exam_subject_entry (id,school_id,exam_config_id,subject_name,max_marks,exam_date) VALUES (?,?,?,?,?,?)",
                id, schoolId, examConfigId, subjectName, maxMarks, examDate);
        return id;
    }

    private long markSeq = -100401L;

    private void insertMark(String studentId, long examSubjectEntryId, double marksObtained) {
        long id = markSeq--;
        Long schoolId = jdbc.queryForObject("SELECT school_id FROM exam_subject_entry WHERE id = ?", Long.class, examSubjectEntryId);
        jdbc.update("INSERT INTO student_mark (id,school_id,student_id,exam_subject_entry_id,created_at,updated_at,marks_obtained) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, schoolId, studentId, examSubjectEntryId, marksObtained);
    }

    private void markAttendance(String studentId, long classId, Long sectionId, LocalDate date, String status) {
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, studentId, classId, sectionId, date, status);
    }
}
