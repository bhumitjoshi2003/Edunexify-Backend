package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationPreviewResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationState;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.SessionActivationOutcome;
import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.DriftStatus;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.GenerationDecision;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.GenerationOutcome;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.GenerationRequest;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.StudentGenerationResult;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.StudentPreviewRow;
import com.indraacademy.ias_management.dto.FeeGenerationTargetDtos.TargetDriftRow;
import com.indraacademy.ias_management.dto.FeeStructureRuleDto;
import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.TimetableDtos.CopySessionResult;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Phase H — full academic-year-rollover end-to-end proof, 2026-2027 &rarr; 2027-2028, for one
 * dedicated synthetic school. Unlike the existing per-module PostgresIT suites (which prove each
 * mechanism correct in isolation, several via hand-inserted enrollment rows rather than the real
 * promotion/activation APIs), this class chains the REAL service layer end-to-end against one
 * shared fixture: prepare target session &rarr; promote/detain/pass-out &rarr; generate target fees
 * &rarr; make current &rarr; verify operational + historical-isolation invariants hold together.
 *
 * <p>Implemented incrementally, stage by stage, across separate review/approval turns:
 * <ul>
 *   <li>H1 (this stage): seed the isolated 2026-2027 baseline and prove it is internally correct
 *       and that every later-needed read path (enrollment, live projection, teacher scope,
 *       timetable, attendance, marks, report cards, fee ledger) already resolves it correctly.</li>
 *   <li>H2-H7 (future stages): to be added as further {@code @Test} methods in this same class,
 *       each re-seeding this identical baseline via {@link #seedBaseline()} before building on
 *       top of it and cleaning up after itself — see the isolation contract below.</li>
 * </ul>
 *
 * <p><b>Isolation contract</b> (school_id = {@value #SCHOOL}, a sentinel block distinct from every
 * other PostgresIT fixture in this repo — fees: -97xxx, session-activation: -98xxx,
 * promotion-coordinator: -99xxx, E6F cross-module: -101xxx/-102xxx):
 * every table this test touches carries {@code school_id} directly, so {@link #cleanupSentinelFixture()}
 * can always scope every DELETE to it alone, in dependency order, regardless of which stage created
 * the row. It runs both BEFORE seeding (crash/interrupted-previous-run safety — asserted via
 * {@link #assertSentinelFixtureIsAbsent()} immediately after) and in a {@code finally} block AFTER
 * every stage (normal-completion hygiene), so this file is safe to re-run any number of times
 * against the shared DEV database without ever touching real school/PROD data.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceService.class, MarkService.class, ReportCardDataAssembler.class,
        StudentTemporalMembershipResolver.class, AcademicSessionService.class,
        ReportCardTemplateService.class, WeightageCalculationEngine.class,
        ReportCardPublicationService.class,
        TimetableSessionCopyService.class, TimetableSessionCopyWorker.class,
        TimetableSessionAccessService.class,
        ClassTeacherResponsibilityService.class, FeeRuleService.class, TeacherClassScopeService.class,
        StudentPromotionService.class, StudentYearEndWorker.class, StudentYearEndService.class,
        FeeGenerationTargetService.class, FeeCalculationService.class,
        ClassTeacherActivationService.class, AcademicSessionActivationService.class,
        TimetableService.class, StudentFeesService.class, StudentEnrollmentService.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        AcademicYearRolloverE2EPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AcademicYearRolloverE2EPostgresIT {

    // ── Sentinel fixture identifiers ──────────────────────────────────────
    private static final long SCHOOL = -120001L;
    private static final long SESSION_2026 = -120101L;
    private static final String SESSION_2026_LABEL = "2026-2027";
    private static final long CLASS_8 = -120201L, CLASS_9 = -120202L, CLASS_10 = -120203L, CLASS_12 = -120204L;
    private static final long SECTION_9A = -120301L, SECTION_9B = -120302L;
    private static final long SECTION_10A = -120303L, SECTION_10B = -120304L;
    private static final String TEACHER_8 = "H-TEACHER-8";
    private static final String TEACHER_9A = "H-TEACHER-9A";
    private static final String TEACHER_9A_2 = "H-TEACHER-9A-2";
    private static final String S_PROMOTE = "H-PROMOTE";
    private static final String S_PROMOTE_SEC = "H-PROMOTE-SEC";
    private static final String S_DETAIN = "H-DETAIN";
    private static final String S_PASSOUT = "H-PASSOUT";
    private static final String S_HISTORY = "H-HISTORY";
    /** Never included in the main promotion batch — exists solely so H3's stale/conflict
     *  protection tests exercise a real INVALID_SOURCE path without depending on (or being
     *  confused with) the main batch's already-applied state. */
    private static final String S_STALE = "H-STALE";
    private static final long FEE_HEAD = -120401L;
    private static final long FEE_RULE_8 = -120411L, FEE_RULE_9 = -120412L, FEE_RULE_12 = -120413L;
    private static final long ASSESSMENT_GROUP = -120501L;
    private static final long TEMPLATE = -120502L;
    private static final long PAYMENT_ID = -120601L;
    private static final long ALLOCATION_ID = -120602L;
    private static final String SESSION_2027_LABEL = "2027-2028";
    private static final String TEACHER_9A_NEXT = "H-TEACHER-9A-NEXT";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceService attendanceService;
    @Autowired MarkService markService;
    @Autowired ReportCardDataAssembler assembler;
    @Autowired ReportCardPublicationService publicationService;
    @Autowired StudentRepository studentRepository;
    @Autowired AcademicSessionService academicSessionService;
    @Autowired TimetableSessionCopyService timetableSessionCopyService;
    @Autowired ClassTeacherResponsibilityService responsibilityService;
    @Autowired FeeRuleService feeRuleService;
    @Autowired TeacherClassScopeService teacherClassScopeService;
    @Autowired StudentPromotionService studentPromotionService;
    @Autowired FeeGenerationTargetService feeGenerationTargetService;
    @Autowired ClassTeacherActivationService classTeacherActivationService;
    @Autowired AcademicSessionActivationService academicSessionActivationService;
    @Autowired TimetableService timetableService;
    @Autowired StudentFeesService studentFeesService;
    @Autowired StudentEnrollmentService enrollmentService;
    @MockBean ParentPortalService parentPortal;
    @MockBean ObjectStorageService objectStorageService; // ReportCardDataAssembler's logo lookup
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean StudentService studentService;
    @MockBean com.indraacademy.ias_management.service.BusinessNotificationService businessNotificationService;
    @MockBean ReportCardEmailBlastService reportCardEmailBlastService;
    @MockBean RemarksService remarksService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void mocks() {
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
    }

    // ══════════════════════════════════════════════════════════════════════
    // H1 — baseline
    // ══════════════════════════════════════════════════════════════════════

    /**
     * H1 seeds and verifies the 2026-2027 baseline; H2 (this same continuous run — see class
     * Javadoc's isolation contract) then prepares 2027-2028 through the REAL
     * AcademicSessionService/TimetableSessionCopyService/ClassTeacherResponsibilityService/
     * FeeRuleService and re-proves every H1 invariant still holds untouched. Committing once
     * (after H1's seed) and letting H2's real @Transactional service calls manage their own
     * transactions — exactly like production — means this is one lifecycle, not two independent
     * fixtures, while cleanup remains a single dependency-safe, school_id-scoped sweep that always
     * runs, pass or fail.
     */
    @Test
    void h1h2_baselineAndTargetSessionPreparation() {
        cleanupSentinelFixture();
        assertSentinelFixtureIsAbsent();

        seedBaseline();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        try {
            // ── H1 ──────────────────────────────────────────────────────────
            assertExactlyOneCurrentSession();
            assertEnrollments();
            assertLiveProjectionsMatchEnrollment();
            assertTeacherLiveProjectionMatchesResponsibilities();
            assertTimetableBaseline();
            assertHistoricalReadsResolveCorrectly();
            assertFinancialLedgerIsInternallyConsistent();

            TableSignature baseline = signature();
            System.out.println("H1 baseline signature: " + baseline);
            assertThat(baseline.students()).isEqualTo(6); // includes H-STALE, added for H3's negative-path test
            assertThat(baseline.enrollments()).isEqualTo(6);
            assertThat(baseline.timetable()).isEqualTo(3);
            assertThat(baseline.responsibilities()).isEqualTo(2);
            assertThat(baseline.feeRules()).isEqualTo(3);
            assertThat(baseline.studentFees()).isEqualTo(5);
            assertThat(baseline.payments()).isEqualTo(1);
            assertThat(baseline.allocations()).isEqualTo(1);
            assertThat(baseline.attendance()).isEqualTo(5); // H-HISTORY's explicit rows for 5 submitted days
            assertThat(baseline.examConfigs()).isEqualTo(1);
            assertThat(baseline.marks()).isEqualTo(1);
            assertThat(baseline.publications()).isEqualTo(1);

            // ── H2 ──────────────────────────────────────────────────────────
            long target = h2_createTargetSession();
            h2_copyTimetable(target);
            h2_configureTargetResponsibilities(target);
            h2_configureTargetFeeRules(target);
            h2_assertNoPrematureLiveEffects(target);

            // ── H3 ──────────────────────────────────────────────────────────
            h3_promotionPreviewAndExecute(target);

            // ── H4 ──────────────────────────────────────────────────────────
            h4_targetEnrollmentDrivenFeeGeneration(target);

            // ── H5 ──────────────────────────────────────────────────────────
            h5_makeCurrentAndActivate(target);

            // ── H6 ──────────────────────────────────────────────────────────
            h6_postCutoverOperationalVerification(target);

            // ── H7 ──────────────────────────────────────────────────────────
            h7_historicalIsolationAndFinalInvariants(target, baseline);
        } finally {
            cleanupSentinelFixture();
            TestTransaction.start();
        }
    }

    // ── H1 assertions ────────────────────────────────────────────────────

    private void assertExactlyOneCurrentSession() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT label FROM academic_session WHERE school_id=? AND is_current=true", String.class, SCHOOL))
                .isEqualTo(SESSION_2026_LABEL);
    }

    private void assertEnrollments() {
        record Expected(String studentId, long classId, Long sectionId) {}
        List<Expected> expected = List.of(
                new Expected(S_PROMOTE, CLASS_8, null),
                new Expected(S_PROMOTE_SEC, CLASS_9, SECTION_9A),
                new Expected(S_DETAIN, CLASS_9, null),
                new Expected(S_PASSOUT, CLASS_12, null),
                new Expected(S_HISTORY, CLASS_8, null),
                new Expected(S_STALE, CLASS_8, null)
        );
        for (Expected e : expected) {
            var rows = jdbc.queryForList(
                    "SELECT class_id, section_id, status FROM student_enrollment " +
                            "WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    SCHOOL, e.studentId(), SESSION_2026);
            assertThat(rows).as("enrollment for %s", e.studentId()).hasSize(1);
            var row = rows.get(0);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(((Number) row.get("class_id")).longValue()).isEqualTo(e.classId());
            Object sectionId = row.get("section_id");
            assertThat(sectionId == null ? null : ((Number) sectionId).longValue()).isEqualTo(e.sectionId());
        }
    }

    private void assertLiveProjectionsMatchEnrollment() {
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_PASSOUT, S_HISTORY, S_STALE)) {
            var studentRow = jdbc.queryForMap(
                    "SELECT class_id, section_id FROM student WHERE school_id=? AND student_id=?", SCHOOL, studentId);
            var enrollmentRow = jdbc.queryForMap(
                    "SELECT class_id, section_id FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    SCHOOL, studentId, SESSION_2026);
            assertThat(studentRow.get("class_id")).as("live projection classId for %s", studentId)
                    .isEqualTo(enrollmentRow.get("class_id"));
            assertThat(studentRow.get("section_id")).as("live projection sectionId for %s", studentId)
                    .isEqualTo(enrollmentRow.get("section_id"));
        }
    }

    private void assertTeacherLiveProjectionMatchesResponsibilities() {
        var t8 = jdbc.queryForMap("SELECT class_teacher, class_teacher_section_id FROM teacher WHERE teacher_id=? AND school_id=?", TEACHER_8, SCHOOL);
        assertThat(t8.get("class_teacher")).isEqualTo("8");
        assertThat(t8.get("class_teacher_section_id")).isNull();

        var t9a = jdbc.queryForMap("SELECT class_teacher, class_teacher_section_id FROM teacher WHERE teacher_id=? AND school_id=?", TEACHER_9A, SCHOOL);
        assertThat(t9a.get("class_teacher")).isEqualTo("9");
        assertThat(((Number) t9a.get("class_teacher_section_id")).longValue()).isEqualTo(SECTION_9A);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND teacher_id=? AND section_id IS NULL",
                Integer.class, SCHOOL, SESSION_2026, CLASS_8, TEACHER_8)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND section_id=? AND teacher_id=?",
                Integer.class, SCHOOL, SESSION_2026, CLASS_9, SECTION_9A, TEACHER_9A)).isEqualTo(1);
    }

    private void assertTimetableBaseline() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);

        // The non-simultaneous Class 8 period.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND class_id=? AND simultaneous_group IS NULL",
                Integer.class, SCHOOL, CLASS_8)).isEqualTo(1);

        // The Class 9 / Section A simultaneous pair.
        var simulRows = jdbc.queryForList(
                "SELECT subject_name, simultaneous_group, day, period_number FROM timetable_entry " +
                        "WHERE school_id=? AND class_id=? AND section_id=? AND simultaneous_group IS NOT NULL",
                SCHOOL, CLASS_9, SECTION_9A);
        assertThat(simulRows).hasSize(2);
        assertThat(simulRows).extracting(r -> r.get("simultaneous_group")).containsOnly("H_GROUP");
        assertThat(simulRows).extracting(r -> r.get("day")).containsOnly("MONDAY");
        assertThat(simulRows).extracting(r -> r.get("period_number")).containsOnly(1);
        assertThat(simulRows).extracting(r -> r.get("subject_name")).containsExactlyInAnyOrder("Math", "Biology");
    }

    private void assertHistoricalReadsResolveCorrectly() {
        AttendanceSummaryDTO attendance = attendanceService.getStudentSummary(S_HISTORY, "year", null, null, SESSION_2026_LABEL);
        assertThat(attendance.getClassName()).isEqualTo("8");
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(5);
        assertThat(attendance.getDaysAbsent()).isEqualTo(1);

        List<ExamResultDTO> results = markService.getStudentResults(S_HISTORY, SESSION_2026_LABEL);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("8");
        assertThat(results.get(0).getTotalMarksObtained()).isEqualTo(78.0);

        var context = assembler.resolveHistoricalContext(S_HISTORY, SESSION_2026_LABEL, null);
        assertThat(context.className()).isEqualTo("8");
        assertThat(context.sectionId()).isNull();

        ReportCardDataDTO dto = assembler.assemble(S_HISTORY, TEMPLATE, SESSION_2026_LABEL);
        assertThat(dto.getClassName()).isEqualTo("8");
        assertThat(publicationService.isPublished(TEMPLATE, SESSION_2026_LABEL, "8")).isTrue();
    }

    private void assertFinancialLedgerIsInternallyConsistent() {
        var historyFee = jdbc.queryForMap(
                "SELECT id, paid, amount_paid FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                SCHOOL, S_HISTORY, SESSION_2026_LABEL);
        assertThat(historyFee.get("paid")).isEqualTo(true);
        long studentFeesId = ((Number) historyFee.get("id")).longValue();

        var payment = jdbc.queryForMap("SELECT id, amount, student_id FROM payment WHERE school_id=? AND id=?", SCHOOL, PAYMENT_ID);
        assertThat(payment.get("student_id")).isEqualTo(S_HISTORY);

        var allocation = jdbc.queryForMap(
                "SELECT payment_id, student_fees_id, amount_paise FROM payment_student_fees_allocation WHERE id=?", ALLOCATION_ID);
        assertThat(((Number) allocation.get("payment_id")).longValue()).isEqualTo(PAYMENT_ID);
        assertThat(((Number) allocation.get("student_fees_id")).longValue()).isEqualTo(studentFeesId);
        assertThat(((Number) allocation.get("amount_paise")).longValue())
                .isEqualTo(((Number) payment.get("amount")).longValue());

        // Every other student's baseline fee row is unpaid — only H-HISTORY carries ledger history.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_PASSOUT)) {
            var fee = jdbc.queryForMap(
                    "SELECT paid FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                    SCHOOL, studentId, SESSION_2026_LABEL);
            assertThat(fee.get("paid")).as("%s should be unpaid in the baseline", studentId).isEqualTo(false);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // H2 — prepare target session 2027-2028 (no promotion, no activation)
    // ══════════════════════════════════════════════════════════════════════

    /** Creates 2027-2028 via the real {@link AcademicSessionService}, strictly contiguous with
     *  2026-2027, and verifies it grants no premature live effect on its own. */
    private long h2_createTargetSession() {
        AcademicSessionDto created = academicSessionService.createSession(new AcademicSessionDto(
                null, SESSION_2027_LABEL, LocalDate.of(2027, 4, 1), LocalDate.of(2028, 3, 31), false));
        long target = created.getId();

        assertThat(created.isCurrent()).isFalse();
        assertExactlyOneCurrentSession(); // still 2026-2027
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND label=? AND is_current=false",
                Integer.class, SCHOOL, SESSION_2027_LABEL)).isEqualTo(1);

        var sourceEnd = jdbc.queryForObject("SELECT end_date FROM academic_session WHERE id=?", java.sql.Date.class, SESSION_2026);
        var targetStart = jdbc.queryForObject("SELECT start_date FROM academic_session WHERE id=?", java.sql.Date.class, target);
        assertThat(targetStart.toLocalDate()).isEqualTo(sourceEnd.toLocalDate().plusDays(1));

        assertLiveProjectionsMatchEnrollment(); // unchanged by merely creating a future session
        assertTeacherLiveProjectionMatchesResponsibilities();
        return target;
    }

    /** Copies the 2026-2027 timetable into 2027-2028 via the real
     *  {@link TimetableSessionCopyService}, then repeats the identical copy to prove idempotency. */
    private void h2_copyTimetable(long target) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        CopySessionResult first = timetableSessionCopyService.copy(SESSION_2026, target, false, req);
        assertThat(first.scanned()).isEqualTo(3);
        assertThat(first.copied()).isEqualTo(3);
        assertThat(first.failures()).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(3);
        // Source rows untouched: still 3, still tagged to 2026-2027.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);

        var targetSimul = jdbc.queryForList(
                "SELECT subject_name, simultaneous_group, day, period_number, teacher_id FROM timetable_entry " +
                        "WHERE school_id=? AND academic_session_id=? AND class_id=? AND section_id=? AND simultaneous_group IS NOT NULL",
                SCHOOL, target, CLASS_9, SECTION_9A);
        assertThat(targetSimul).hasSize(2);
        assertThat(targetSimul).extracting(r -> r.get("simultaneous_group")).containsOnly("H_GROUP");
        assertThat(targetSimul).extracting(r -> r.get("subject_name")).containsExactlyInAnyOrder("Math", "Biology");
        assertThat(targetSimul).extracting(r -> r.get("teacher_id")).containsExactlyInAnyOrder(TEACHER_9A, TEACHER_9A_2);

        var targetPlain = jdbc.queryForMap(
                "SELECT day, period_number, start_time, end_time, subject_name, teacher_id FROM timetable_entry " +
                        "WHERE school_id=? AND academic_session_id=? AND class_id=? AND section_id IS NULL",
                SCHOOL, target, CLASS_8);
        assertThat(targetPlain.get("subject_name")).isEqualTo("English");
        assertThat(targetPlain.get("teacher_id")).isEqualTo(TEACHER_8);

        // New row identities — never the same primary keys as the source rows.
        List<Long> sourceIds = jdbc.query("SELECT id FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                (rs, i) -> rs.getLong("id"), SCHOOL, SESSION_2026);
        List<Long> targetIds = jdbc.query("SELECT id FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                (rs, i) -> rs.getLong("id"), SCHOOL, target);
        assertThat(targetIds).doesNotContainAnyElementsOf(sourceIds);

        // Idempotency: repeating the identical copy creates no duplicates.
        CopySessionResult second = timetableSessionCopyService.copy(SESSION_2026, target, false, req);
        assertThat(second.copied()).isZero();
        assertThat(second.alreadyCopied()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(3);
    }

    /** Configures 2027-2028 class-teacher responsibilities via the real
     *  {@link ClassTeacherResponsibilityService}: one row RETAINS the 2026-2027 teacher (Class 8,
     *  the "unchanged" case for H5), one row assigns a DIFFERENT teacher (Class 9 / Section A, the
     *  "changing + clearing + becoming-live" case for H5) — the smallest fixture that exercises
     *  both activation outcomes later. Neither may grant any CURRENT authority yet. */
    private void h2_configureTargetResponsibilities(long target) {
        insertTeacher(TEACHER_9A_NEXT, null, null); // ACTIVE, no live class-teacher assignment at all

        responsibilityService.create(
                new ClassTeacherResponsibilityDtos.Request(target, CLASS_8, null, TEACHER_8), mock(HttpServletRequest.class));
        responsibilityService.create(
                new ClassTeacherResponsibilityDtos.Request(target, CLASS_9, SECTION_9A, TEACHER_9A_NEXT), mock(HttpServletRequest.class));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND teacher_id=? AND section_id IS NULL",
                Integer.class, SCHOOL, target, CLASS_8, TEACHER_8)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND section_id=? AND teacher_id=?",
                Integer.class, SCHOOL, target, CLASS_9, SECTION_9A, TEACHER_9A_NEXT)).isEqualTo(1);

        // 2026-2027 responsibilities untouched, and live projection still reflects them, not the
        // (unactivated) 2027-2028 configuration.
        assertTeacherLiveProjectionMatchesResponsibilities();

        // The real authorization path: a future-only responsibility grants NO current authority.
        var nextScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A_NEXT, SCHOOL);
        assertThat(nextScope.hasClassResponsibility()).isFalse();
        // TEACHER_9A (2026-2027's occupant) still shows its current, unrevoked authority.
        var currentScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A, SCHOOL);
        assertThat(currentScope.className()).isEqualTo("9");
        assertThat(currentScope.sectionId()).isEqualTo(SECTION_9A);
    }

    /** Configures 2027-2028 {@code FeeStructureRule}s via the real {@link FeeRuleService} for the
     *  eventual target classes of H-PROMOTE (Class 9) and H-PROMOTE-SEC/H-DETAIN (Class 9, Class
     *  10) — no generation, no {@code StudentFees} rows, at this stage. */
    private void h2_configureTargetFeeRules(long target) {
        feeRuleService.saveRulesForClass(target, "9", List.of(feeRuleDto(target, "9", 650000L)), mock(HttpServletRequest.class));
        feeRuleService.saveRulesForClass(target, "10", List.of(feeRuleDto(target, "10", 700000L)), mock(HttpServletRequest.class));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fee_structure_rule WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(2);
        // Source rules unchanged: still the 3 seeded for classes 8/9/12.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM fee_structure_rule WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM fee_structure_rule WHERE school_id=? AND academic_session_id=? AND class_id=?",
                Long.class, SCHOOL, SESSION_2026, CLASS_9)).isEqualTo(600000L);

        // No fee generation at H2 — zero StudentFees for the target session, for anyone,
        // including (especially) H-PASSOUT.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2027_LABEL)).isZero();
    }

    private FeeStructureRuleDto feeRuleDto(long sessionId, String className, long amount) {
        FeeStructureRuleDto dto = new FeeStructureRuleDto();
        dto.setFeeHeadId(FEE_HEAD);
        dto.setAcademicSessionId(sessionId);
        dto.setClassName(className);
        dto.setAmount(amount);
        dto.setEffectiveFrom(LocalDate.of(2027, 4, 1));
        return dto;
    }

    /** The central H2 invariant: re-runs every H1 historical/live-projection assertion after all
     *  of H2's target-session preparation, proving none of it altered 2026-2027 in any way. */
    private void h2_assertNoPrematureLiveEffects(long target) {
        assertExactlyOneCurrentSession();
        assertEnrollments();
        assertLiveProjectionsMatchEnrollment();
        assertTeacherLiveProjectionMatchesResponsibilities();
        assertHistoricalReadsResolveCorrectly();
        assertFinancialLedgerIsInternallyConsistent();

        // Session-scoped counts for 2026-2027 specifically — unaffected by 2027-2028's legitimate
        // new rows, which the raw table-wide TableSignature would otherwise conflate with drift.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2026_LABEL)).isEqualTo(5);
        assertThat(AttendanceV2Fixtures.countRows(jdbc, SCHOOL)).isEqualTo(5);
        assertThat(count("exam_config")).isEqualTo(1);
        assertThat(count("student_mark")).isEqualTo(1);
        assertThat(count("report_card_publication")).isEqualTo(1);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(countAllocations()).isEqualTo(1);

        System.out.println("H2 target session prepared: id=" + target + ", label=" + SESSION_2027_LABEL
                + " — 2026-2027 historical invariants all confirmed unchanged.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // H3 — promotion / year-end transition
    // ══════════════════════════════════════════════════════════════════════

    /** Real StudentPromotionService preview + execute, exactly as the actual controller
     *  (POST /api/students/promotion/execute) would drive it — no direct enrollment
     *  manipulation, no workaround around what the DTO contract actually allows. */
    private void h3_promotionPreviewAndExecute(long target) {
        // ── 1. PREVIEW (read-only) ──────────────────────────────────────
        PromotionPreviewDTO preview = studentPromotionService.getPromotionPreview(SESSION_2026, target, null, null);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.errors()).isEmpty();

        var byId = preview.candidates().stream()
                .collect(Collectors.toMap(PromotionPreviewDTO.Candidate::studentId, c -> c));
        assertThat(byId.keySet()).contains(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_PASSOUT, S_HISTORY, S_STALE);

        var promote = byId.get(S_PROMOTE);
        assertThat(promote.sourceClassId()).isEqualTo(CLASS_8);
        assertThat(promote.availableDecisions()).contains(StudentYearEndDecision.Action.PROMOTE);
        assertThat(promote.recommendedDecision()).isEqualTo(StudentYearEndDecision.Action.PROMOTE);
        assertThat(promote.promoteTargetClassId()).isEqualTo(CLASS_9);
        // Class 9 has active sections configured, so — traced, not assumed — EVERY promotion
        // into it (not just the scenario deliberately designed to need one) requires an explicit
        // target section; this is universal on "does the target class have sections", not scoped
        // to whichever candidate a UI happens to label "the sectioned one".
        assertThat(promote.promoteTargetSectionRequired()).isTrue();

        var promoteSec = byId.get(S_PROMOTE_SEC);
        assertThat(promoteSec.sourceClassId()).isEqualTo(CLASS_9);
        assertThat(promoteSec.sourceSectionId()).isEqualTo(SECTION_9A);
        assertThat(promoteSec.availableDecisions()).contains(StudentYearEndDecision.Action.PROMOTE);
        assertThat(promoteSec.promoteTargetClassId()).isEqualTo(CLASS_10);
        assertThat(promoteSec.promoteTargetSectionRequired()).isTrue();

        var detain = byId.get(S_DETAIN);
        assertThat(detain.sourceClassId()).isEqualTo(CLASS_9);
        assertThat(detain.availableDecisions()).contains(StudentYearEndDecision.Action.DETAIN);

        var passout = byId.get(S_PASSOUT);
        assertThat(passout.sourceClassId()).isEqualTo(CLASS_12);
        assertThat(passout.availableDecisions()).containsExactlyInAnyOrder(
                StudentYearEndDecision.Action.DETAIN, StudentYearEndDecision.Action.PASS_OUT);
        assertThat(passout.recommendedDecision()).isEqualTo(StudentYearEndDecision.Action.PASS_OUT);
        assertThat(passout.promoteTargetClassId()).isNull();

        var history = byId.get(S_HISTORY);
        assertThat(history.sourceClassId()).isEqualTo(CLASS_8);
        assertThat(history.availableDecisions()).contains(StudentYearEndDecision.Action.PROMOTE);

        // Preview performed zero writes.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isZero();
        assertLiveProjectionsMatchEnrollment();

        // ── 2. EXECUTE — the real batch, exactly as the DTO contract requires ──
        // PromotionDecisionRequest.targetSessionId is one @NotNull field shared by the WHOLE
        // batch (enforced by @Valid at the controller) — there is no way to submit a per-decision
        // override, so every decision in this batch — PASS_OUT included — carries target=2027-2028.
        PromotionDecisionRequest batch = new PromotionDecisionRequest();
        batch.setSourceSessionId(SESSION_2026);
        batch.setTargetSessionId(target);
        batch.setDecisions(List.of(
                decision(promote, StudentYearEndDecision.Action.PROMOTE, CLASS_9, SECTION_9A),
                decision(promoteSec, StudentYearEndDecision.Action.PROMOTE, CLASS_10, SECTION_10A),
                decision(detain, StudentYearEndDecision.Action.DETAIN, CLASS_9, SECTION_9B),
                decision(passout, StudentYearEndDecision.Action.PASS_OUT, null, null),
                decision(history, StudentYearEndDecision.Action.PROMOTE, CLASS_9, SECTION_9B)
        ));
        PromotionResultDTO result = studentPromotionService.executePromotion(batch, mock(HttpServletRequest.class));
        var outcomeById = result.outcomes().stream()
                .collect(Collectors.toMap(PromotionResultDTO.StudentOutcome::studentId, o -> o));
        System.out.println("H3 execution outcomes: " + outcomeById);

        assertThat(outcomeById.get(S_PROMOTE).code()).isEqualTo("PROMOTED");
        assertThat(outcomeById.get(S_PROMOTE_SEC).code()).isEqualTo("PROMOTED");
        assertThat(outcomeById.get(S_DETAIN).code()).isEqualTo("DETAINED");
        assertThat(outcomeById.get(S_HISTORY).code()).isEqualTo("PROMOTED");

        // ── H-PASSOUT — now fixed: PASS_OUT succeeds through the real batch API ───
        // StudentPromotionService now omits targetSessionId/targetClassId/targetSectionId from
        // the internal command for PASS_OUT decisions, regardless of the batch's own
        // targetSessionId — see the fix in executePromotion.
        var passOutOutcome = outcomeById.get(S_PASSOUT);
        System.out.println("H-PASSOUT outcome (fix verification): " + passOutOutcome);
        assertThat(passOutOutcome.code()).isEqualTo("PASSED_OUT");
        assertThat(passOutOutcome.targetEnrollmentId()).isNull();
        // Today (real clock) is before the SOURCE session's own end date (2027-03-31), so the
        // graduation is recorded but its finalization is deliberately deferred — traced from
        // StudentYearEndService.applyPassOut's `pending = today.isBefore(sourceSession.getEndDate())`.
        assertThat(passOutOutcome.lifecycleFinalizationPending()).isTrue();

        // ── 3/4. Source/target enrollments for all five decisions ──────────
        record Source(String studentId, String closureReason) {}
        for (Source s : List.of(
                new Source(S_PROMOTE, "SESSION_COMPLETED"), new Source(S_PROMOTE_SEC, "SESSION_COMPLETED"),
                new Source(S_DETAIN, "SESSION_COMPLETED"), new Source(S_HISTORY, "SESSION_COMPLETED"),
                new Source(S_PASSOUT, "GRADUATED"))) {
            var row = jdbc.queryForMap(
                    "SELECT status, effective_until, closure_reason FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    SCHOOL, s.studentId(), SESSION_2026);
            assertThat(row.get("status")).as("source status for %s", s.studentId()).isEqualTo("CLOSED");
            assertThat(((java.sql.Date) row.get("effective_until")).toLocalDate())
                    .as("source effective_until for %s", s.studentId()).isEqualTo(LocalDate.of(2027, 3, 31));
            assertThat(row.get("closure_reason")).as("source closure_reason for %s", s.studentId())
                    .isEqualTo(s.closureReason());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    Integer.class, SCHOOL, s.studentId(), SESSION_2026)).as("no duplicate source row for %s", s.studentId())
                    .isEqualTo(1);
        }
        // H-PASSOUT: zero target enrollment (PASS_OUT creates none, by design).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, target)).isZero();
        // Graduation pending, not finalized: Student.status stays ACTIVE until the source session
        // actually ends — traced from applyPassOut's `if (!pending) finalizeStudentGraduation(...)`.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student WHERE school_id=? AND student_id=?", String.class, SCHOOL, S_PASSOUT))
                .isEqualTo("ACTIVE");

        record Target(String studentId, long classId, Long sectionId) {}
        for (Target t : List.of(
                new Target(S_PROMOTE, CLASS_9, SECTION_9A), new Target(S_PROMOTE_SEC, CLASS_10, SECTION_10A),
                new Target(S_DETAIN, CLASS_9, SECTION_9B), new Target(S_HISTORY, CLASS_9, SECTION_9B))) {
            var rows = jdbc.queryForList(
                    "SELECT class_id, section_id, status, effective_from FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    SCHOOL, t.studentId(), target);
            assertThat(rows).as("target enrollment for %s", t.studentId()).hasSize(1);
            var row = rows.get(0);
            assertThat(((Number) row.get("class_id")).longValue()).isEqualTo(t.classId());
            assertThat(((Number) row.get("section_id")).longValue()).isEqualTo(t.sectionId());
            // Real clock: "today" is well before 2027-04-01, so every target enrollment is
            // PLANNED, never ACTIVE — traced from StudentYearEndService.applyContinuing line 195.
            assertThat(row.get("status")).as("target status for %s", t.studentId()).isEqualTo("PLANNED");
            assertThat(((java.sql.Date) row.get("effective_from")).toLocalDate())
                    .as("target effective_from for %s", t.studentId()).isEqualTo(LocalDate.of(2027, 4, 1));
        }

        // ── 5. Live projection before Make Current — traced, not assumed ───
        // Because every target enrollment above is PLANNED (not ACTIVE), applyContinuing's
        // "if (targetStatus == ACTIVE) synchronizeProjection(...)" branch never runs for any of
        // them — Student.classId/sectionId/status stay exactly as they were before promotion.
        record LiveProjection(String studentId, long classId, Long sectionId, String status) {}
        for (LiveProjection p : List.of(
                new LiveProjection(S_PROMOTE, CLASS_8, null, "ACTIVE"),
                new LiveProjection(S_PROMOTE_SEC, CLASS_9, SECTION_9A, "ACTIVE"),
                new LiveProjection(S_DETAIN, CLASS_9, null, "ACTIVE"),
                new LiveProjection(S_HISTORY, CLASS_8, null, "ACTIVE"),
                new LiveProjection(S_PASSOUT, CLASS_12, null, "ACTIVE"))) {
            var row = jdbc.queryForMap(
                    "SELECT class_id, section_id, status FROM student WHERE school_id=? AND student_id=?", SCHOOL, p.studentId());
            assertThat(((Number) row.get("class_id")).longValue()).as("live classId for %s", p.studentId()).isEqualTo(p.classId());
            Object sec = row.get("section_id");
            assertThat(sec == null ? null : ((Number) sec).longValue()).as("live sectionId for %s", p.studentId()).isEqualTo(p.sectionId());
            assertThat(row.get("status")).as("live status for %s", p.studentId()).isEqualTo(p.status());
        }
        assertExactlyOneCurrentSession(); // still 2026-2027 — H5 has not happened
        assertTeacherLiveProjectionMatchesResponsibilities(); // still 2026-2027's teachers

        // ── 8. H-HISTORY's 2026-2027 historical facts, untouched by its own promotion ───
        assertHistoricalReadsResolveCorrectly();
        assertFinancialLedgerIsInternallyConsistent();

        // ── 9. Target fees must still be zero — H4 has not happened ───────
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2027_LABEL)).isZero();

        // ── 6. Idempotent re-run — the IDENTICAL batch, byte-for-byte ──────
        PromotionResultDTO rerun = studentPromotionService.executePromotion(batch, mock(HttpServletRequest.class));
        var rerunById = rerun.outcomes().stream()
                .collect(Collectors.toMap(PromotionResultDTO.StudentOutcome::studentId, o -> o));
        System.out.println("H3 idempotent re-run outcomes: " + rerunById);
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY, S_PASSOUT)) {
            assertThat(rerunById.get(studentId).code()).as("re-run outcome for %s", studentId).isEqualTo("ALREADY_APPLIED");
        }
        assertThat(rerunById.get(S_PASSOUT).lifecycleFinalizationPending()).isTrue();
        // No duplicate target enrollments, no second source closure, no corruption.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    Integer.class, SCHOOL, studentId, target)).as("no duplicate target enrollment for %s", studentId).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    Integer.class, SCHOOL, studentId, SESSION_2026)).as("no second source closure for %s", studentId).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, target)).as("PASS_OUT re-run still creates no target enrollment").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, SESSION_2026)).as("PASS_OUT re-run does not duplicate source closure").isEqualTo(1);

        // ── 7. Stale / conflict protection — dedicated H-STALE candidate ───
        var staleSource = jdbc.queryForMap(
                "SELECT id, class_id FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                SCHOOL, S_STALE, SESSION_2026);
        long realEnrollmentId = ((Number) staleSource.get("id")).longValue();
        long realClassId = ((Number) staleSource.get("class_id")).longValue();
        assertThat(realClassId).isEqualTo(CLASS_8);

        // A. wrong expectedSourceEnrollmentId (correct class id).
        var wrongEnrollmentDecision = new PromotionDecisionRequest.Decision();
        wrongEnrollmentDecision.setStudentId(S_STALE);
        wrongEnrollmentDecision.setAction(StudentYearEndDecision.Action.PROMOTE);
        wrongEnrollmentDecision.setExpectedSourceEnrollmentId(realEnrollmentId - 999999L); // deliberately wrong
        wrongEnrollmentDecision.setExpectedSourceClassId(realClassId);
        wrongEnrollmentDecision.setTargetClassId(CLASS_9);
        wrongEnrollmentDecision.setTargetSectionId(SECTION_9A);
        PromotionDecisionRequest wrongEnrollmentBatch = new PromotionDecisionRequest();
        wrongEnrollmentBatch.setSourceSessionId(SESSION_2026);
        wrongEnrollmentBatch.setTargetSessionId(target);
        wrongEnrollmentBatch.setDecisions(List.of(wrongEnrollmentDecision));
        var wrongEnrollmentResult = studentPromotionService.executePromotion(wrongEnrollmentBatch, mock(HttpServletRequest.class));
        String wrongEnrollmentCode = wrongEnrollmentResult.outcomes().get(0).code();
        System.out.println("H3.7A stale expectedSourceEnrollmentId outcome: " + wrongEnrollmentCode);
        assertThat(wrongEnrollmentCode).isEqualTo("INVALID_SOURCE");

        // B. wrong expectedSourceClassId (correct, real enrollment id).
        var wrongClassDecision = new PromotionDecisionRequest.Decision();
        wrongClassDecision.setStudentId(S_STALE);
        wrongClassDecision.setAction(StudentYearEndDecision.Action.PROMOTE);
        wrongClassDecision.setExpectedSourceEnrollmentId(realEnrollmentId);
        wrongClassDecision.setExpectedSourceClassId(CLASS_9); // deliberately wrong — real is CLASS_8
        wrongClassDecision.setTargetClassId(CLASS_9);
        wrongClassDecision.setTargetSectionId(SECTION_9A);
        PromotionDecisionRequest wrongClassBatch = new PromotionDecisionRequest();
        wrongClassBatch.setSourceSessionId(SESSION_2026);
        wrongClassBatch.setTargetSessionId(target);
        wrongClassBatch.setDecisions(List.of(wrongClassDecision));
        var wrongClassResult = studentPromotionService.executePromotion(wrongClassBatch, mock(HttpServletRequest.class));
        String wrongClassCode = wrongClassResult.outcomes().get(0).code();
        System.out.println("H3.7B stale expectedSourceClassId outcome: " + wrongClassCode);
        assertThat(wrongClassCode).isEqualTo("INVALID_SOURCE");

        // Zero mutation from either negative case: H-STALE's own enrollment untouched, no target
        // enrollment created, and no collateral effect on any other student's already-verified state.
        var staleAfter = jdbc.queryForMap(
                "SELECT status, class_id FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                SCHOOL, S_STALE, SESSION_2026);
        assertThat(staleAfter.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) staleAfter.get("class_id")).longValue()).isEqualTo(CLASS_8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_STALE, target)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student WHERE school_id=? AND student_id=?", String.class, SCHOOL, S_STALE))
                .isEqualTo("ACTIVE");
        // Collateral check: another student's already-established target enrollment is untouched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PROMOTE, target)).isEqualTo(1);

        System.out.println("H3 complete: PASS_OUT fix verified, idempotent re-run and stale/conflict "
                + "protection all confirmed against real DEV data.");
    }

    private PromotionDecisionRequest.Decision decision(
            PromotionPreviewDTO.Candidate c, StudentYearEndDecision.Action action, Long targetClassId, Long targetSectionId) {
        var d = new PromotionDecisionRequest.Decision();
        d.setStudentId(c.studentId());
        d.setAction(action);
        d.setExpectedSourceEnrollmentId(c.sourceEnrollmentId());
        d.setExpectedSourceClassId(c.sourceClassId());
        d.setTargetClassId(targetClassId);
        d.setTargetSectionId(targetSectionId);
        return d;
    }

    // ══════════════════════════════════════════════════════════════════════
    // H4 — target-enrollment-driven fee generation
    // ══════════════════════════════════════════════════════════════════════

    private void h4_targetEnrollmentDrivenFeeGeneration(long target) {
        // ── 1. PRE-GENERATION PREVIEW (read-only) ──────────────────────────
        long studentFeesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?", Long.class, SCHOOL, SESSION_2027_LABEL);
        List<StudentPreviewRow> preview = feeGenerationTargetService.preview(target, null, null);
        var previewById = preview.stream().collect(Collectors.toMap(StudentPreviewRow::studentId, r -> r));
        System.out.println("H4 pre-generation preview: " + previewById.keySet());

        record ExpectedTarget(String studentId, long classId) {}
        for (ExpectedTarget e : List.of(
                new ExpectedTarget(S_PROMOTE, CLASS_9), new ExpectedTarget(S_PROMOTE_SEC, CLASS_10),
                new ExpectedTarget(S_DETAIN, CLASS_9), new ExpectedTarget(S_HISTORY, CLASS_9))) {
            var row = previewById.get(e.studentId());
            assertThat(row).as("preview row for %s", e.studentId()).isNotNull();
            // Eligibility/class here comes from the target StudentEnrollment (PLANNED), never from
            // Student.classId, which at this point still shows the 2026-2027 class for every one
            // of these students (traced and asserted already in H3's live-projection checks).
            assertThat(row.targetClassId()).as("preview targetClassId for %s", e.studentId()).isEqualTo(e.classId());
            assertThat(row.targetEnrollmentStatus()).as("preview targetEnrollmentStatus for %s", e.studentId()).isEqualTo("PLANNED");
            assertThat(row.eligible()).as("preview eligible for %s", e.studentId()).isTrue();
            assertThat(row.blockingErrors()).as("preview blockingErrors for %s", e.studentId()).isEmpty();
        }
        // H-PASSOUT has no target-session enrollment at all, so it is structurally never a
        // candidate — it does not appear in the preview, not even as an ineligible row.
        assertThat(previewById).as("H-PASSOUT must not appear in the target-session preview").doesNotContainKey(S_PASSOUT);

        // Preview performed zero writes.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?", Long.class, SCHOOL, SESSION_2027_LABEL))
                .isEqualTo(studentFeesBefore);

        // ── targetDrift BEFORE generation — traced, not assumed ────────────
        // targetDrift only reports on students who already have a student_fees row for this
        // session label; with zero rows generated yet, there is nothing to report at all — an
        // empty list, not a "MISSING"/"PENDING" status (no such status exists — see DriftStatus).
        List<TargetDriftRow> driftBefore = feeGenerationTargetService.targetDrift(target, null, null);
        System.out.println("H4 targetDrift before generation: " + driftBefore);
        assertThat(driftBefore).isEmpty();

        // ── 2. GENERATE — server-authoritative values from preview only ────
        List<GenerationDecision> decisions = List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY).stream()
                .map(studentId -> {
                    var row = previewById.get(studentId);
                    return new GenerationDecision(studentId, row.targetEnrollmentId(), row.targetClassId());
                }).toList();
        GenerationRequest request = new GenerationRequest(target, decisions);
        List<StudentGenerationResult> results = feeGenerationTargetService.generate(request, "127.0.0.1");
        var resultById = results.stream().collect(Collectors.toMap(StudentGenerationResult::studentId, r -> r));
        System.out.println("H4 generation results: " + resultById);

        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            var r = resultById.get(studentId);
            assertThat(r.outcome()).as("generation outcome for %s", studentId).isEqualTo(GenerationOutcome.GENERATED);
            assertThat(r.generatedMonths()).as("generated months for %s", studentId).isEqualTo(12);
            assertThat(r.skippedMonths()).as("skipped months for %s", studentId).isZero();
        }

        record ExpectedFees(String studentId, long classId, String className) {}
        java.math.BigDecimal class9Amount = null, class10Amount = null;
        for (ExpectedFees e : List.of(
                new ExpectedFees(S_PROMOTE, CLASS_9, "9"), new ExpectedFees(S_PROMOTE_SEC, CLASS_10, "10"),
                new ExpectedFees(S_DETAIN, CLASS_9, "9"), new ExpectedFees(S_HISTORY, CLASS_9, "9"))) {
            var rows = jdbc.queryForList(
                    "SELECT class_id, class_name, month, base_amount_due FROM student_fees WHERE school_id=? AND student_id=? AND year=? ORDER BY month",
                    SCHOOL, e.studentId(), SESSION_2027_LABEL);
            assertThat(rows).as("2027-2028 fee rows for %s", e.studentId()).hasSize(12);
            for (var row : rows) {
                assertThat(((Number) row.get("class_id")).longValue()).as("fee class_id for %s", e.studentId()).isEqualTo(e.classId());
                assertThat(row.get("class_name")).as("fee class_name for %s", e.studentId()).isEqualTo(e.className());
            }
            var amounts = rows.stream().map(r -> (java.math.BigDecimal) r.get("base_amount_due")).distinct().toList();
            assertThat(amounts).as("uniform monthly amount for %s", e.studentId()).hasSize(1);
            assertThat(amounts.get(0)).as("nonzero monthly amount for %s", e.studentId()).isGreaterThan(java.math.BigDecimal.ZERO);
            if (e.classId() == CLASS_9) class9Amount = amounts.get(0); else class10Amount = amounts.get(0);
        }
        // Class-specific fee rules actually took effect: Class 9's and Class 10's per-month
        // amounts differ (650000 vs 700000 paise configured in H2), proving generation is driven
        // by each student's own target CLASS, not a single shared amount.
        assertThat(class9Amount).isNotEqualTo(class10Amount);

        // H-PASSOUT: zero target-session fees, exactly as required.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                Integer.class, SCHOOL, S_PASSOUT, SESSION_2027_LABEL)).isZero();

        // ── targetDrift AFTER generation ────────────────────────────────────
        List<TargetDriftRow> driftAfter = feeGenerationTargetService.targetDrift(target, null, null);
        var driftAfterById = driftAfter.stream().collect(Collectors.toMap(TargetDriftRow::studentId, d -> d));
        System.out.println("H4 targetDrift after generation: " + driftAfterById);
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(driftAfterById.get(studentId).driftStatus()).as("drift status for %s", studentId).isEqualTo(DriftStatus.CLEAN);
            assertThat(driftAfterById.get(studentId).missingMonths()).as("missing months for %s", studentId).isEmpty();
        }
        assertThat(driftAfterById).doesNotContainKey(S_PASSOUT);

        // Prove targetDrift (both calls) was read-only: identical row counts before vs. now.
        long studentFeesAfterDrift = jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?", Long.class, SCHOOL, SESSION_2027_LABEL);
        assertThat(studentFeesAfterDrift).isEqualTo(48L); // 4 students * 12 months, unchanged by either targetDrift call

        // ── 3. IDEMPOTENCY — identical re-run ──────────────────────────────
        List<StudentGenerationResult> rerun = feeGenerationTargetService.generate(request, "127.0.0.1");
        var rerunById = rerun.stream().collect(Collectors.toMap(StudentGenerationResult::studentId, r -> r));
        System.out.println("H4 idempotent re-run results: " + rerunById);
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            var r = rerunById.get(studentId);
            assertThat(r.outcome()).as("re-run outcome for %s", studentId).isEqualTo(GenerationOutcome.ALREADY_GENERATED);
            assertThat(r.generatedMonths()).as("re-run generated months for %s", studentId).isZero();
            assertThat(r.skippedMonths()).as("re-run skipped months for %s", studentId).isEqualTo(12);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?", Long.class, SCHOOL, SESSION_2027_LABEL))
                .as("no duplicate rows from idempotent re-run").isEqualTo(48L);

        // ── 4. Stale authority protection ───────────────────────────────────
        // generateForStudent validates enrollment-existence-by-id, then status, then classId
        // match — all BEFORE ever checking whether a given month's fee row already exists — so
        // these negative cases are safe to run against already-fully-generated real candidates;
        // no dedicated extra fixture student is needed (verified from the traced code, not
        // assumed): the mismatch is caught at the very first gate, well before touching any row.
        var promoteRow = previewById.get(S_PROMOTE);
        var detainRow = previewById.get(S_DETAIN);

        // A. wrong expectedTargetEnrollmentId (correct class id) — H-PROMOTE.
        var wrongEnrollment = new GenerationDecision(S_PROMOTE, promoteRow.targetEnrollmentId() - 999999L, promoteRow.targetClassId());
        var wrongEnrollmentResult = feeGenerationTargetService.generate(
                new GenerationRequest(target, List.of(wrongEnrollment)), "127.0.0.1").get(0);
        System.out.println("H4.4A stale expectedTargetEnrollmentId outcome: " + wrongEnrollmentResult);
        assertThat(wrongEnrollmentResult.outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);

        // B. wrong expectedTargetClassId (correct, real enrollment id) — H-DETAIN.
        var wrongClass = new GenerationDecision(S_DETAIN, detainRow.targetEnrollmentId(), CLASS_10); // real target is CLASS_9
        var wrongClassResult = feeGenerationTargetService.generate(
                new GenerationRequest(target, List.of(wrongClass)), "127.0.0.1").get(0);
        System.out.println("H4.4B stale expectedTargetClassId outcome: " + wrongClassResult);
        assertThat(wrongClassResult.outcome()).isEqualTo(GenerationOutcome.ENROLLMENT_CHANGED);

        // Zero unintended mutation from either negative case: row counts for both students and
        // the whole target session unchanged, and no historical (2026-2027) fee touched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                Integer.class, SCHOOL, S_PROMOTE, SESSION_2027_LABEL)).isEqualTo(12);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                Integer.class, SCHOOL, S_DETAIN, SESSION_2027_LABEL)).isEqualTo(12);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?", Long.class, SCHOOL, SESSION_2027_LABEL))
                .isEqualTo(48L);

        // ── 6. Historical financial immutability — compare against H1/H3 ──
        assertFinancialLedgerIsInternallyConsistent(); // H-HISTORY's paid 2026-2027 row + payment + allocation
        assertThat(count("payment")).as("2026-2027 payment count unchanged").isEqualTo(1);
        assertThat(countAllocations()).as("2026-2027 allocation count unchanged").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2026_LABEL)).as("2026-2027 StudentFees count unchanged").isEqualTo(5);

        // ── 7. Annual automation safety — hard-gated, not merely flag-off ──
        // StudentFeesGenerationService.hasAuthoritativeEnrollmentForAutomaticGeneration is a
        // hardcoded `return false` (not a runtime config check) — the monthly scheduler that
        // reads SchoolFeeSettings.automaticAnnualGeneration therefore contributes zero rows
        // regardless of that flag's value. This test never invokes that service or touches
        // SchoolFeeSettings at all — every one of the 48 rows above came from
        // FeeGenerationTargetService.generate, traceable via the audit call it makes
        // ("GENERATE_TARGET_ENROLLMENT_FEES") and via there being no other fee-writing service
        // in this test's @Import list.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=?", Long.class, SCHOOL))
                .as("every student_fees row is accounted for by baseline (5) + target generation (48)")
                .isEqualTo(53L);

        // ── 8. Current-session invariants — unaffected by fee generation ──
        assertExactlyOneCurrentSession(); // still 2026-2027
        assertTeacherLiveProjectionMatchesResponsibilities(); // still 2026-2027's teachers
        assertLiveProjectionsMatchEnrollment(); // Student projections still follow H3's semantics
        assertThat(jdbc.queryForObject(
                "SELECT is_current FROM academic_session WHERE id=?", Boolean.class, target)).isFalse();

        System.out.println("H4 complete: target-enrollment-driven fee generation, idempotency, "
                + "stale-authority protection, and targetDrift all verified against real DEV data.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // H5 — Make 2027-2028 current, atomically activating its class-teacher config
    // ══════════════════════════════════════════════════════════════════════

    private void h5_makeCurrentAndActivate(long target) {
        // ── 1. ACTIVATION PREVIEW (read-only, before Make Current) ─────────
        // H2 configured: Class 8 -> H-TEACHER-8 (same teacher already live there — UNCHANGED);
        // Class 9/A -> H-TEACHER-9A-NEXT, replacing H-TEACHER-9A who is CURRENTLY live for that
        // exact slot (CHANGING, not a separate becomingLive) — and H-TEACHER-9A's own live
        // assignment, no longer backed by any target-session row, is what gets CLEARED. Traced
        // from ClassTeacherActivationService.computeDiff, not assumed.
        ActivationPreviewResult preview = classTeacherActivationService.previewForSession(target);
        System.out.println("H5 pre-activation preview: " + preview);
        assertThat(preview.academicSessionId()).isEqualTo(target);
        assertThat(preview.configuredCount()).isEqualTo(2);
        assertThat(preview.unchanged()).isEqualTo(1);
        assertThat(preview.changing()).isEqualTo(1);
        assertThat(preview.becomingLive()).isZero();
        assertThat(preview.clearing()).isEqualTo(1);
        assertThat(preview.ineligibleTeacher()).isZero();
        assertThat(preview.invalidClassOrSection()).isZero();
        assertThat(preview.hasIssues()).isFalse();
        assertThat(preview.inSync()).isFalse(); // becomingLive+changing+clearing = 0+1+1 != 0
        assertThat(preview.activationState()).isEqualTo(ActivationState.NEVER_APPLIED);
        assertThat(preview.lastAppliedAt()).isNull();
        assertThat(preview.lastAppliedBy()).isNull();

        // Preview is read-only.
        assertExactlyOneCurrentSession(); // still 2026-2027
        assertTeacherLiveProjectionMatchesResponsibilities(); // unchanged by merely previewing
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isZero();

        // ── 2. MAKE CURRENT — real atomic lifecycle, no standalone Resync ──
        SessionActivationOutcome outcome =
                academicSessionActivationService.setCurrentSessionAndActivate(target, mock(HttpServletRequest.class));
        System.out.println("H5 Make Current outcome: activationPerformed=" + outcome.activationPerformed()
                + ", session=" + outcome.session().getLabel() + " current=" + outcome.session().isCurrent()
                + ", activation=" + outcome.activation());
        assertThat(outcome.activationPerformed()).isTrue();
        assertThat(outcome.session().getId()).isEqualTo(target);
        assertThat(outcome.session().isCurrent()).isTrue();
        assertThat(outcome.activation()).isNotNull();
        assertThat(outcome.activation().applied()).isEqualTo(1);   // H-TEACHER-9A-NEXT newly set live
        assertThat(outcome.activation().cleared()).isEqualTo(1);   // H-TEACHER-9A's stale live assignment nulled
        assertThat(outcome.activation().unchanged()).isEqualTo(1); // H-TEACHER-8 already correct
        assertThat(outcome.activation().activationState()).isEqualTo(ActivationState.APPLIED_IN_SYNC);

        // Exactly one current session, atomically switched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_current FROM academic_session WHERE id=?", Boolean.class, target)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_current FROM academic_session WHERE id=?", Boolean.class, SESSION_2026)).isFalse();

        // Activation provenance recorded for the TARGET session, not the outgoing one.
        var provenance = jdbc.queryForMap(
                "SELECT academic_session_id, applied_by FROM class_teacher_activation WHERE school_id=?", SCHOOL);
        assertThat(((Number) provenance.get("academic_session_id")).longValue()).isEqualTo(target);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_activation WHERE school_id=?", Integer.class, SCHOOL)).isEqualTo(1);

        // ── 3. TEACHER LIVE AUTHORITY (real TeacherClassScopeService) ──────
        var teacher8Scope = teacherClassScopeService.resolveOwnScope(TEACHER_8, SCHOOL);
        assertThat(teacher8Scope.className()).isEqualTo("8");
        assertThat(teacher8Scope.sectionId()).isNull();

        var teacher9aScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A, SCHOOL);
        assertThat(teacher9aScope.hasClassResponsibility()).as("H-TEACHER-9A must lose current authority").isFalse();

        var teacher9aNextScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A_NEXT, SCHOOL);
        assertThat(teacher9aNextScope.className()).as("H-TEACHER-9A-NEXT must gain current authority").isEqualTo("9");
        assertThat(teacher9aNextScope.sectionId()).isEqualTo(SECTION_9A);

        // Historical 2026-2027 responsibility rows untouched — only the live projection moved.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND teacher_id=? AND section_id IS NULL",
                Integer.class, SCHOOL, SESSION_2026, CLASS_8, TEACHER_8)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=? AND section_id=? AND teacher_id=?",
                Integer.class, SCHOOL, SESSION_2026, CLASS_9, SECTION_9A, TEACHER_9A)).isEqualTo(1);

        // ── 4. Student enrollment / live projection after cutover — traced, not assumed ──
        // Neither AcademicSessionActivationService nor ClassTeacherActivationService reference
        // StudentEnrollment or Student anywhere (confirmed by reading both classes in full) — only
        // AcademicSession.isCurrent and Teacher.classTeacher*. So Make Current activates NEITHER
        // the four PLANNED target enrollments NOR H-PASSOUT's pending graduation. Both remain
        // strictly date-driven, handled entirely elsewhere:
        //   - PLANNED -> ACTIVE realization: StudentEnrollmentService.activateEligiblePlannedEnrollment
        //   - pending PASS_OUT finalization: StudentYearEndService.finalizeGraduation
        // both invoked only by StudentStatusScheduler's nightly @Scheduled(cron="0 0 4 * * *")
        // job (StudentStatusScheduler.java) — a completely separate, real-calendar-date-driven
        // mechanism this test never exercises (the real system clock is used throughout and is
        // nowhere near 2027-04-01 or 2027-03-31).
        record LiveProjection(String studentId, long classId, Long sectionId, String status) {}
        for (LiveProjection p : List.of(
                new LiveProjection(S_PROMOTE, CLASS_8, null, "ACTIVE"),
                new LiveProjection(S_PROMOTE_SEC, CLASS_9, SECTION_9A, "ACTIVE"),
                new LiveProjection(S_DETAIN, CLASS_9, null, "ACTIVE"),
                new LiveProjection(S_HISTORY, CLASS_8, null, "ACTIVE"),
                new LiveProjection(S_PASSOUT, CLASS_12, null, "ACTIVE"))) {
            var row = jdbc.queryForMap(
                    "SELECT class_id, section_id, status FROM student WHERE school_id=? AND student_id=?", SCHOOL, p.studentId());
            assertThat(((Number) row.get("class_id")).longValue()).as("live classId for %s after Make Current", p.studentId()).isEqualTo(p.classId());
            Object sec = row.get("section_id");
            assertThat(sec == null ? null : ((Number) sec).longValue()).as("live sectionId for %s after Make Current", p.studentId()).isEqualTo(p.sectionId());
            assertThat(row.get("status")).as("live status for %s after Make Current", p.studentId()).isEqualTo(p.status());
        }
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    String.class, SCHOOL, studentId, target)).as("%s target enrollment still PLANNED after Make Current", studentId)
                    .isEqualTo("PLANNED");
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                String.class, SCHOOL, S_PASSOUT, SESSION_2026)).as("H-PASSOUT source enrollment still just CLOSED (graduation still pending)")
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, target)).as("H-PASSOUT still has no target enrollment").isZero();

        // ── 5. POST-ACTIVATION PREVIEW ──────────────────────────────────────
        ActivationPreviewResult postPreview = classTeacherActivationService.previewForSession(target);
        System.out.println("H5 post-activation preview: " + postPreview);
        assertThat(postPreview.inSync()).isTrue();
        assertThat(postPreview.activationState()).isEqualTo(ActivationState.APPLIED_IN_SYNC);
        assertThat(postPreview.unchanged()).isEqualTo(2); // both slots now match live exactly
        assertThat(postPreview.changing()).isZero();
        assertThat(postPreview.becomingLive()).isZero();
        assertThat(postPreview.clearing()).isZero();
        assertThat(postPreview.lastAppliedAt()).isNotNull();
        assertThat(postPreview.lastAppliedBy()).isEqualTo("admin");

        // ── 6. IDEMPOTENT MAKE CURRENT — already-current session, genuine no-op ──
        SessionActivationOutcome rerunOutcome =
                academicSessionActivationService.setCurrentSessionAndActivate(target, mock(HttpServletRequest.class));
        System.out.println("H5 idempotent Make Current outcome: activationPerformed=" + rerunOutcome.activationPerformed());
        assertThat(rerunOutcome.activationPerformed()).as("already-current target must be a genuine no-op").isFalse();
        assertThat(rerunOutcome.activation()).isNull();
        assertThat(rerunOutcome.session().getId()).isEqualTo(target);
        assertThat(rerunOutcome.session().isCurrent()).isTrue();
        // No duplicate provenance, no unnecessary Teacher writes, still current, still inSync.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_activation WHERE school_id=?", Integer.class, SCHOOL)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL)).isEqualTo(1);
        assertThat(classTeacherActivationService.previewForSession(target).inSync()).isTrue();

        // ── 7. Historical immutability after cutover ────────────────────────
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);
        assertHistoricalReadsResolveCorrectly(); // H-HISTORY: attendance/marks/report card/publication, 2026-2027
        assertFinancialLedgerIsInternallyConsistent(); // 2026-2027 StudentFees/payment/allocation
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2026_LABEL)).isEqualTo(5);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(countAllocations()).isEqualTo(1);

        System.out.println("H5 complete: Make Current atomically switched the current session and activated "
                + "2027-2028's class-teacher configuration; PLANNED enrollments and pending PASS_OUT remain "
                + "untouched, correctly deferred to StudentStatusScheduler's nightly date-driven job; "
                + "2026-2027 history fully intact.");
    }

    // ══════════════════════════════════════════════════════════════════════
    // H6 — post-cutover operational verification
    // ══════════════════════════════════════════════════════════════════════

    private void h6_postCutoverOperationalVerification(long target) {
        h6_1_exactlyOneCurrentSessionIsTarget(target);
        h6_2_teacherAuthorizationPersistsAfterCutover();
        h6_3_timetableCurrentVsHistoricalReads(target);
        h6_4_feeReadsResolveCorrectSession(target);
        h6_5_studentLifecycleSchedulerBoundary(target);
        System.out.println("H6 complete: current-session resolution, teacher authorization, timetable and fee "
                + "operational reads, and the scheduler/service lifecycle boundary all verified post-cutover.");
    }

    /** H6.1 — exactly one current session, and it is now 2027-2028 (the mirror image of
     *  {@link #assertExactlyOneCurrentSession()}, which is hardcoded to the pre-cutover label). */
    private void h6_1_exactlyOneCurrentSessionIsTarget(long target) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM academic_session WHERE school_id=? AND is_current=true", Long.class, SCHOOL))
                .isEqualTo(target);
        assertThat(jdbc.queryForObject(
                "SELECT label FROM academic_session WHERE school_id=? AND is_current=true", String.class, SCHOOL))
                .isEqualTo(SESSION_2027_LABEL);
        assertThat(academicSessionService.getCurrentSession().getLabel()).isEqualTo(SESSION_2027_LABEL);
    }

    /** H6.2 — re-confirms H5's authorization outcome still holds unchanged; not a new mechanism,
     *  just proving cutover didn't require a second write or silently drift afterward. */
    private void h6_2_teacherAuthorizationPersistsAfterCutover() {
        var teacher8Scope = teacherClassScopeService.resolveOwnScope(TEACHER_8, SCHOOL);
        assertThat(teacher8Scope.className()).as("H-TEACHER-8 unchanged post-cutover").isEqualTo("8");
        assertThat(teacher8Scope.sectionId()).isNull();

        var teacher9aNextScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A_NEXT, SCHOOL);
        assertThat(teacher9aNextScope.className()).as("H-TEACHER-9A-NEXT holds Class 9/A post-cutover").isEqualTo("9");
        assertThat(teacher9aNextScope.sectionId()).isEqualTo(SECTION_9A);

        var teacher9aScope = teacherClassScopeService.resolveOwnScope(TEACHER_9A, SCHOOL);
        assertThat(teacher9aScope.hasClassResponsibility())
                .as("H-TEACHER-9A no longer holds any class responsibility post-cutover").isFalse();
    }

    /** H6.3 — {@link TimetableService}'s current-session operational reads now auto-resolve
     *  2027-2028 (never exercised before H5's cutover — H1-H5 only ever read via explicit
     *  session id); the explicit/historical read pair still resolves 2026-2027 unchanged, with
     *  its own distinct row identities (the H2 copy, not the same rows). Timetable teacher
     *  assignment is a wholly separate concern from class-teacher responsibility — traced from
     *  H2's copy (which retained TEACHER_9A/TEACHER_9A_2 as the periods' own teacher_id, never
     *  TEACHER_9A_NEXT) — so TEACHER_9A still owns periods on the timetable even though it lost
     *  class-teacher authority in H5. */
    private void h6_3_timetableCurrentVsHistoricalReads(long target) {
        List<TimetableEntry> currentSimul = timetableService.getByClass("9", SECTION_9A);
        var currentGroup = currentSimul.stream().filter(e -> "H_GROUP".equals(e.getSimultaneousGroup())).toList();
        assertThat(currentGroup).hasSize(2);
        assertThat(currentGroup).extracting(TimetableEntry::getAcademicSessionId).containsOnly(target);
        assertThat(currentGroup).extracting(TimetableEntry::getSubjectName).containsExactlyInAnyOrder("Math", "Biology");
        assertThat(currentGroup).extracting(TimetableEntry::getTeacherId).containsExactlyInAnyOrder(TEACHER_9A, TEACHER_9A_2);

        List<TimetableEntry> currentPlain = timetableService.getByClass("8", null);
        assertThat(currentPlain).hasSize(1);
        assertThat(currentPlain.get(0).getAcademicSessionId()).isEqualTo(target);
        assertThat(currentPlain.get(0).getSubjectName()).isEqualTo("English");
        assertThat(currentPlain.get(0).getTeacherId()).isEqualTo(TEACHER_8);

        List<TimetableEntry> historicalGroup = timetableService.getByClassForSession("9", SECTION_9A, SESSION_2026)
                .stream().filter(e -> "H_GROUP".equals(e.getSimultaneousGroup())).toList();
        assertThat(historicalGroup).hasSize(2);
        assertThat(historicalGroup).extracting(TimetableEntry::getAcademicSessionId).containsOnly(SESSION_2026);
        assertThat(historicalGroup).extracting(TimetableEntry::getSubjectName).containsExactlyInAnyOrder("Math", "Biology");
        assertThat(historicalGroup).extracting(TimetableEntry::getId)
                .as("historical rows are the H2-copy's SOURCE, never the same identities as the target copy")
                .doesNotContainAnyElementsOf(currentGroup.stream().map(TimetableEntry::getId).toList());

        // Teacher-scoped reads: TEACHER_9A still teaches (timetable assignment), independent of
        // having lost class-teacher responsibility.
        List<TimetableEntry> teacher9aCurrent = timetableService.getByTeacher(TEACHER_9A);
        assertThat(teacher9aCurrent).hasSize(1);
        assertThat(teacher9aCurrent.get(0).getAcademicSessionId()).isEqualTo(target);

        List<TimetableEntry> teacher9aHistorical = timetableService.getByTeacherForSession(TEACHER_9A, SESSION_2026);
        assertThat(teacher9aHistorical).hasSize(1);
        assertThat(teacher9aHistorical.get(0).getAcademicSessionId()).isEqualTo(SESSION_2026);
        assertThat(teacher9aHistorical.get(0).getId()).isNotEqualTo(teacher9aCurrent.get(0).getId());

        // TEACHER_9A_NEXT was only ever granted class-teacher (homeroom) responsibility — it has
        // never been assigned to teach any period, in either session.
        assertThat(timetableService.getByTeacher(TEACHER_9A_NEXT)).isEmpty();
        assertThat(timetableService.getByTeacherForSession(TEACHER_9A_NEXT, SESSION_2026)).isEmpty();
    }

    /** H6.4 — {@link StudentFeesService#getStudentFees} takes an explicit session-label string
     *  (the client resolves "current" via {@link AcademicSessionService#getCurrentSession()}
     *  first); this proves that end-to-end path resolves the 2027-2028 obligations H4 generated,
     *  that H-PASSOUT correctly has none, and that explicit 2026-2027 financial history remains
     *  fully reachable, unaffected by cutover. */
    private void h6_4_feeReadsResolveCorrectSession(long target) {
        String currentLabel = academicSessionService.getCurrentSession().getLabel();
        assertThat(currentLabel).isEqualTo(SESSION_2027_LABEL);

        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            var fees = studentFeesService.getStudentFees(studentId, currentLabel);
            assertThat(fees).as("current-session (2027-2028) fee read for %s", studentId).hasSize(12);
        }
        assertThat(studentFeesService.getStudentFees(S_PASSOUT, currentLabel))
                .as("H-PASSOUT has no 2027-2028 fee obligations").isEmpty();

        // Explicit 2026-2027 read remains reachable — unaffected by which session is "current".
        var historyFeesLastYear = studentFeesService.getStudentFees(S_HISTORY, SESSION_2026_LABEL);
        assertThat(historyFeesLastYear).hasSize(1);
        assertThat(historyFeesLastYear.get(0).getPaid()).as("H-HISTORY's paid 2026-2027 fee is still readable").isTrue();
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_PASSOUT)) {
            assertThat(studentFeesService.getStudentFees(studentId, SESSION_2026_LABEL))
                    .as("explicit 2026-2027 fee read for %s", studentId).hasSize(1);
        }
    }

    /** H6.5 — proves, empirically rather than only via static trace, that the exact repository
     *  eligibility gates {@link StudentEnrollmentService} exposes to {@code StudentStatusScheduler}
     *  correctly stay silent under today's real date and correctly identify the right candidates
     *  once queried with the real 2027-04-01/2027-03-31 boundary dates the scheduler itself would
     *  compute — using the SAME explicit {@code LocalDate} parameter the production methods
     *  already take, not a mocked or advanced system clock. {@code activateEligiblePlannedEnrollment}
     *  is deliberately NOT invoked here (it would mutate these shared sentinel fixtures ahead of
     *  H7's invariant checks for no reason this phase requires); a read-only proof of the gating
     *  query is sufficient. {@code finalizeGraduation} reads {@code Clock} directly inside itself
     *  (unlike the two methods below, which take an explicit date) — exercising its "already past
     *  end date" branch here would require overriding the SAME Clock bean every earlier H1-H5 stage
     *  already depended on under the real system clock, an unacceptable blast-radius change for a
     *  verification-only phase; that branch is already covered in isolation by
     *  StudentYearEndServicePostgresIT's own dedicated {@code @MockBean Clock} tests. */
    private void h6_5_studentLifecycleSchedulerBoundary(long target) {
        List<String> eligibleNow = enrollmentService.findEligiblePlannedStudentIds(SCHOOL, LocalDate.now());
        assertThat(eligibleNow).as("no target enrollment is eligible under the real current date")
                .doesNotContainAnyElementsOf(List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY));

        List<StudentEnrollment> dueNow = enrollmentService.findDueGraduationEnrollments(SCHOOL, LocalDate.now());
        assertThat(dueNow).extracting(StudentEnrollment::getStudentId)
                .as("H-PASSOUT's graduation is not yet due under the real current date").doesNotContain(S_PASSOUT);

        List<String> eligibleAtCutover = enrollmentService.findEligiblePlannedStudentIds(SCHOOL, LocalDate.of(2027, 4, 1));
        assertThat(eligibleAtCutover)
                .as("exactly the four continuing target enrollments become eligible on 2027-04-01")
                .containsExactlyInAnyOrder(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY);

        List<StudentEnrollment> dueAtSessionEnd = enrollmentService.findDueGraduationEnrollments(SCHOOL, LocalDate.of(2027, 3, 31));
        assertThat(dueAtSessionEnd).extracting(StudentEnrollment::getStudentId)
                .as("H-PASSOUT's graduation becomes due exactly on the source session's own end date")
                .contains(S_PASSOUT);
        List<StudentEnrollment> dueOneDayEarly = enrollmentService.findDueGraduationEnrollments(SCHOOL, LocalDate.of(2027, 3, 30));
        assertThat(dueOneDayEarly).extracting(StudentEnrollment::getStudentId)
                .as("not due even one day before the source session's own end date").doesNotContain(S_PASSOUT);

        // Purely read-only: neither query, nor Make Current (H5, already executed), mutated anything.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    String.class, SCHOOL, studentId, target))
                    .as("%s target enrollment still PLANNED — scheduler-boundary check performed no mutation", studentId)
                    .isEqualTo("PLANNED");
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student WHERE school_id=? AND student_id=?", String.class, SCHOOL, S_PASSOUT))
                .as("H-PASSOUT still ACTIVE — graduation finalization intentionally not forced here").isEqualTo("ACTIVE");
    }

    // ══════════════════════════════════════════════════════════════════════
    // H7 — historical isolation / final invariants
    // ══════════════════════════════════════════════════════════════════════

    private void h7_historicalIsolationAndFinalInvariants(long target, TableSignature baseline) {
        // ── 1. Historical comparison against the H1 baseline ───────────────
        // Re-runs the exact H1 historical assertions unchanged, plus the session-scoped
        // 2026-2027 counts H2 already isolated from the target session's legitimate new rows.
        assertHistoricalReadsResolveCorrectly();
        assertFinancialLedgerIsInternallyConsistent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_2026)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND year=?",
                Integer.class, SCHOOL, SESSION_2026_LABEL)).isEqualTo(5);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(countAllocations()).isEqualTo(1);
        assertThat(AttendanceV2Fixtures.countRows(jdbc, SCHOOL)).isEqualTo(5);
        assertThat(count("exam_config")).isEqualTo(1);
        assertThat(count("student_mark")).isEqualTo(1);
        assertThat(count("report_card_publication")).isEqualTo(1);

        // The ONLY expected lifecycle mutation vs. H1 baseline: H3 closed five 2026-2027 source
        // enrollments (SESSION_COMPLETED x4, GRADUATED x1) — the rows still exist (status CLOSED),
        // never deleted or rewritten beyond that one transition.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY, S_PASSOUT)) {
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    String.class, SCHOOL, studentId, SESSION_2026)).as("%s source enrollment closed, not deleted", studentId)
                    .isEqualTo("CLOSED");
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                String.class, SCHOOL, S_STALE, SESSION_2026)).as("H-STALE's source enrollment untouched — never part of the batch")
                .isEqualTo("ACTIVE");

        // ── 2. Final structural invariants ──────────────────────────────────
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL))
                .as("exactly one current session").isEqualTo(1);

        // No overlapping enrollments: every continuing student has exactly one row per session,
        // and no student has two enrollments both covering the same academic_session_id.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                    Integer.class, SCHOOL, studentId, target)).as("no duplicate target enrollment for %s", studentId).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(DISTINCT academic_session_id) FROM student_enrollment WHERE school_id=? AND student_id=?",
                    Integer.class, SCHOOL, studentId)).as("no overlapping/duplicate session membership for %s", studentId).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, target)).as("H-PASSOUT has no target enrollment").isZero();

        // No duplicate target StudentFees/month obligations.
        for (String studentId : List.of(S_PROMOTE, S_PROMOTE_SEC, S_DETAIN, S_HISTORY)) {
            assertThat(jdbc.queryForObject(
                    "SELECT count(DISTINCT month) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                    Integer.class, SCHOOL, studentId, SESSION_2027_LABEL)).as("12 distinct months, no duplicates, for %s", studentId).isEqualTo(12);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                    Integer.class, SCHOOL, studentId, SESSION_2027_LABEL)).as("no duplicate rows at all for %s", studentId).isEqualTo(12);
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                Integer.class, SCHOOL, S_PASSOUT, SESSION_2027_LABEL)).as("H-PASSOUT has no target fees").isZero();

        // No duplicate timetable rows from copy/idempotency (H2 already proved the idempotent
        // re-copy adds zero; this re-confirms the final resting count for both sessions).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT id) FROM timetable_entry WHERE school_id=?", Integer.class, SCHOOL)).isEqualTo(6);

        // Exactly one activation provenance row for the target session.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM class_teacher_activation WHERE school_id=?", Integer.class, SCHOOL)).isEqualTo(1);

        // Target teacher configuration is inSync.
        assertThat(classTeacherActivationService.previewForSession(target).inSync()).isTrue();

        // ── 3. Baseline-vs-final signature comparison ───────────────────────
        TableSignature finalSignature = signature();
        System.out.println("H7 baseline vs. final signature: baseline=" + baseline + " final=" + finalSignature);

        // Immutable historical facts: identical to the H1 baseline, untouched by H2-H6.
        assertThat(finalSignature.payments()).as("payments: immutable").isEqualTo(baseline.payments());
        assertThat(finalSignature.allocations()).as("allocations: immutable").isEqualTo(baseline.allocations());
        assertThat(finalSignature.attendance()).as("attendance: immutable").isEqualTo(baseline.attendance());
        assertThat(finalSignature.examConfigs()).as("examConfigs: immutable").isEqualTo(baseline.examConfigs());
        assertThat(finalSignature.marks()).as("marks: immutable").isEqualTo(baseline.marks());
        assertThat(finalSignature.publications()).as("publications: immutable").isEqualTo(baseline.publications());
        assertThat(finalSignature.students()).as("students: no new/deleted students, only lifecycle-status changes")
                .isEqualTo(baseline.students());

        // Expected 2027-2028 additions, on top of the immutable 2026-2027 baseline counts.
        assertThat(finalSignature.timetable()).as("timetable: +3 target-session copy").isEqualTo(baseline.timetable() + 3);
        assertThat(finalSignature.responsibilities()).as("responsibilities: +2 target-session config")
                .isEqualTo(baseline.responsibilities() + 2);
        assertThat(finalSignature.feeRules()).as("feeRules: +2 target-session config").isEqualTo(baseline.feeRules() + 2);
        assertThat(finalSignature.studentFees()).as("studentFees: +48 target-session generation (4 students x 12 months)")
                .isEqualTo(baseline.studentFees() + 48);

        // Expected source-enrollment closures: enrollments grew by +4 (four continuing target
        // rows; H-PASSOUT's PASS_OUT creates none), never by replacing/removing the five closed
        // 2026-2027 source rows.
        assertThat(finalSignature.enrollments()).as("enrollments: +4 target-session rows (continuing only), source rows retained")
                .isEqualTo(baseline.enrollments() + 4);

        // ── Sentinel cleanup will run in the caller's `finally` block; assertSentinelFixtureIsAbsent()
        //    at the START of the next invocation is what actually proves it "still leaves DEV DB
        //    clean" — nothing further to assert here.

        System.out.println("H7 complete: 2026-2027 history byte-for-byte unchanged except the five expected "
                + "H3 source-enrollment closures; final structural invariants (single current session, no "
                + "overlapping/duplicate enrollments, no duplicate fees/timetable rows, single activation "
                + "provenance row, inSync target config, H-PASSOUT fully absent from the target session) all hold.");
    }

    // ── Table signature (H7 will reuse this shape) ──────────────────────

    private record TableSignature(long students, long enrollments, long timetable, long responsibilities,
                                  long feeRules, long studentFees, long payments, long allocations,
                                  long attendance, long examConfigs, long marks, long publications) {}

    private TableSignature signature() {
        return new TableSignature(
                count("student"), count("student_enrollment"), count("timetable_entry"),
                count("class_teacher_responsibility"), count("fee_structure_rule"), count("student_fees"),
                count("payment"), countAllocations(), AttendanceV2Fixtures.countRows(jdbc, SCHOOL), count("exam_config"),
                count("student_mark"), count("report_card_publication"));
    }

    private long count(String table) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE school_id=?", Long.class, SCHOOL);
        return c == null ? 0 : c;
    }

    private long countAllocations() {
        Long c = jdbc.queryForObject(
                "SELECT count(*) FROM payment_student_fees_allocation WHERE school_id=?", Long.class, SCHOOL);
        return c == null ? 0 : c;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fixture construction
    // ══════════════════════════════════════════════════════════════════════

    private void seedBaseline() {
        insertSchool();
        insertSession();
        insertClass(CLASS_8, "8", 1);
        insertClass(CLASS_9, "9", 2);
        insertClass(CLASS_10, "10", 3);
        insertClass(CLASS_12, "12", 4); // highest displayOrder among active classes = the terminal class
        insertSection(SECTION_9A, CLASS_9, "A");
        insertSection(SECTION_9B, CLASS_9, "B");
        insertSection(SECTION_10A, CLASS_10, "A");
        insertSection(SECTION_10B, CLASS_10, "B");

        insertTeacher(TEACHER_8, "8", null);
        insertTeacher(TEACHER_9A, "9", SECTION_9A);
        insertTeacher(TEACHER_9A_2, null, null);
        insertResponsibility(CLASS_8, null, TEACHER_8);
        insertResponsibility(CLASS_9, SECTION_9A, TEACHER_9A);

        insertTimetableEntry(CLASS_8, "8", null, "MONDAY", 1, "English", TEACHER_8, null);
        insertTimetableEntry(CLASS_9, "9", SECTION_9A, "MONDAY", 1, "Math", TEACHER_9A, "H_GROUP");
        insertTimetableEntry(CLASS_9, "9", SECTION_9A, "MONDAY", 1, "Biology", TEACHER_9A_2, "H_GROUP");

        insertStudent(S_PROMOTE, "8", CLASS_8, null);
        insertStudent(S_PROMOTE_SEC, "9", CLASS_9, SECTION_9A);
        insertStudent(S_DETAIN, "9", CLASS_9, null);
        insertStudent(S_PASSOUT, "12", CLASS_12, null);
        insertStudent(S_HISTORY, "8", CLASS_8, null);
        insertStudent(S_STALE, "8", CLASS_8, null);

        insertActiveEnrollment(S_PROMOTE, CLASS_8, "8", null);
        insertActiveEnrollment(S_PROMOTE_SEC, CLASS_9, "9", SECTION_9A);
        insertActiveEnrollment(S_DETAIN, CLASS_9, "9", null);
        insertActiveEnrollment(S_PASSOUT, CLASS_12, "12", null);
        insertActiveEnrollment(S_HISTORY, CLASS_8, "8", null);
        insertActiveEnrollment(S_STALE, CLASS_8, "8", null);

        insertFeeHead();
        insertFeeStructureRule(FEE_RULE_8, CLASS_8, "8", 500000);
        insertFeeStructureRule(FEE_RULE_9, CLASS_9, "9", 600000);
        insertFeeStructureRule(FEE_RULE_12, CLASS_12, "12", 800000);

        insertStudentFees(S_PROMOTE, CLASS_8, "8", 5000.00, false, null);
        insertStudentFees(S_PROMOTE_SEC, CLASS_9, "9", 6000.00, false, null);
        insertStudentFees(S_DETAIN, CLASS_9, "9", 6000.00, false, null);
        insertStudentFees(S_PASSOUT, CLASS_12, "12", 8000.00, false, null);
        long historyFeeId = insertStudentFees(S_HISTORY, CLASS_8, "8", 5000.00, true, 5000.00);
        insertPayment(historyFeeId);

        // H-HISTORY's explicit Attendance V2 rows for five Class 8 submissions in August 2026.
        for (int d = 3; d <= 7; d++) {
            AttendanceV2Fixtures.mark(jdbc, SCHOOL, S_HISTORY, CLASS_8, null, LocalDate.of(2026, 8, d),
                    d == 5 ? "ABSENT" : "PRESENT");
        }

        long examConfig = insertExamConfig("8", "Half Yearly");
        long subjectEntry = insertSubjectEntry(examConfig, "Math", 100);
        insertMark(S_HISTORY, subjectEntry, 78.0);

        jdbc.update("INSERT INTO assessment_group (id,school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,?,?,?,?,?,0)", ASSESSMENT_GROUP, SCHOOL, SESSION_2026_LABEL, "8", "Annual", "EXAM_BASED");
        jdbc.update("INSERT INTO report_card_template (id,school_id,name,assessment_group_id,is_default,is_active) " +
                "VALUES (?,?,?,?,false,true)", TEMPLATE, SCHOOL, "Standard", ASSESSMENT_GROUP);
        jdbc.update("INSERT INTO report_card_template_section (id,template_id,section_type,enabled,display_order) " +
                "VALUES (?,?,?,true,0)", TEMPLATE - 1, TEMPLATE, "ATTENDANCE");
        publicationService.publish(TEMPLATE, SESSION_2026_LABEL, "8");
    }

    private void insertSchool() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,'Phase H Rollover IT','TRIAL','phase-h-rollover-it',4,8,'Asia/Kolkata')", SCHOOL);
    }

    private void insertSession() {
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) " +
                "VALUES (?,?,?,DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION_2026, SCHOOL, SESSION_2026_LABEL);
    }

    private void insertClass(long id, String name, int displayOrder) {
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,?,true,?,false)",
                id, SCHOOL, name, displayOrder);
    }

    private void insertSection(long id, long classId, String name) {
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,?,true)", id, SCHOOL, classId, name);
    }

    private void insertTeacher(String teacherId, String classTeacherClassName, Long classTeacherSectionId) {
        jdbc.update("INSERT INTO teacher (teacher_id,school_id,name,status,class_teacher,class_teacher_section_id) " +
                "VALUES (?,?,?,'ACTIVE',?,?)", teacherId, SCHOOL, "Teacher " + teacherId, classTeacherClassName, classTeacherSectionId);
    }

    private void insertResponsibility(long classId, Long sectionId, String teacherId) {
        jdbc.update("INSERT INTO class_teacher_responsibility (school_id,academic_session_id,class_id,section_id,teacher_id) " +
                "VALUES (?,?,?,?,?)", SCHOOL, SESSION_2026, classId, sectionId, teacherId);
    }

    private void insertTimetableEntry(long classId, String className, Long sectionId, String day, int period, String subject, String teacherId, String simultaneousGroup) {
        jdbc.update("INSERT INTO timetable_entry (school_id,academic_session_id,class_id,class_name,section_id,day," +
                "period_number,start_time,end_time,subject_name,teacher_id,simultaneous_group) " +
                "VALUES (?,?,?,?,?,?,?,'09:00','09:40',?,?,?)",
                SCHOOL, SESSION_2026, classId, className, sectionId, day, period, subject, teacherId, simultaneousGroup);
    }

    private void insertStudent(String studentId, String className, long classId, Long sectionId) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,?,?,DATE '2026-04-01')", studentId, SCHOOL, "Student " + studentId, classId, className, sectionId);
    }

    private void insertActiveEnrollment(String studentId, long classId, String className, Long sectionId) {
        String sectionName = sectionId == null ? null : (sectionId.equals(SECTION_9A) ? "A" : "B");
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                "section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, studentId, SESSION_2026, classId, className, sectionId, sectionName);
    }

    private void insertFeeHead() {
        // due_months left at the full-12 "let frequency decide" default (FeeCalculationService.
        // appliesThisAcademicMonth) so a MONTHLY head applies identically every academic month —
        // H1-H3 never exercise FeeCalculationService at all (baseline student_fees rows are
        // hand-inserted), so this only matters starting at H4's real generation.
        jdbc.update("INSERT INTO fee_head (id,active,code,created_at,display_order,due_months,frequency,name,is_optional,is_refundable,school_id) " +
                "VALUES (?,true,'TUITION',CURRENT_TIMESTAMP,1,'[1,2,3,4,5,6,7,8,9,10,11,12]','MONTHLY','Tuition Fee',false,false,?)", FEE_HEAD, SCHOOL);
    }

    private void insertFeeStructureRule(long id, long classId, String className, long amountPaise) {
        jdbc.update("INSERT INTO fee_structure_rule (id,amount,class_name,created_at,effective_from,school_id,academic_session_id,fee_head_id,class_id) " +
                "VALUES (?,?,?,CURRENT_TIMESTAMP,DATE '2026-04-01',?,?,?,?)",
                id, amountPaise, className, SCHOOL, SESSION_2026, FEE_HEAD, classId);
    }

    private long studentFeesSeq = -120701L;

    private long insertStudentFees(String studentId, long classId, String className, double baseAmountDue, boolean paid, Double amountPaid) {
        long id = studentFeesSeq--;
        jdbc.update("INSERT INTO student_fees (id,school_id,student_id,class_id,class_name,month,year,paid,manually_paid," +
                "takes_bus,distance,base_amount_due,bus_fee_due,discount_amount,amount_paid) " +
                "VALUES (?,?,?,?,?,4,?,?,false,false,0,?,0,0,?)",
                id, SCHOOL, studentId, classId, className, SESSION_2026_LABEL, paid, baseAmountDue, amountPaid);
        return id;
    }

    private void insertPayment(long studentFeesId) {
        jdbc.update("INSERT INTO payment (id,school_id,student_id,student_name,class_name,class_id,session,month,amount," +
                "payment_id,order_id,payment_date,status,bus_fee,tuition_fee,annual_charges,lab_charges,eca_project," +
                "examination_fee,additional_charges,paid_manually,amount_paid,late_fees,platform_fee,razorpay_signature) " +
                "VALUES (?,?,?,?,?,?,?,'4',500000,?,?,CURRENT_TIMESTAMP,'SUCCESS',0,0,0,0,0,0,0,false,0,0,0,'H-RAZORPAY-SIGNATURE-1')",
                PAYMENT_ID, SCHOOL, S_HISTORY, "Student " + S_HISTORY, "8", CLASS_8, SESSION_2026_LABEL,
                "H-RAZORPAY-PAY-1", "H-RAZORPAY-ORDER-1");
        jdbc.update("INSERT INTO payment_student_fees_allocation (id,payment_id,student_fees_id,school_id,student_id,session,month,amount_paise,created_at) " +
                "VALUES (?,?,?,?,?,?,4,500000,CURRENT_TIMESTAMP)",
                ALLOCATION_ID, PAYMENT_ID, studentFeesId, SCHOOL, S_HISTORY, SESSION_2026_LABEL);
    }

    private long examConfigSeq = -120801L;

    private long insertExamConfig(String className, String examName) {
        long id = examConfigSeq--;
        jdbc.update("INSERT INTO exam_config (id,school_id,session,class_name,exam_name,result_status) VALUES (?,?,?,?,?,'PUBLISHED')",
                id, SCHOOL, SESSION_2026_LABEL, className, examName);
        return id;
    }

    private long entrySeq = -120901L;

    private long insertSubjectEntry(long examConfigId, String subjectName, int maxMarks) {
        long id = entrySeq--;
        jdbc.update("INSERT INTO exam_subject_entry (id,school_id,exam_config_id,subject_name,max_marks) VALUES (?,?,?,?,?)",
                id, SCHOOL, examConfigId, subjectName, maxMarks);
        return id;
    }

    private long markSeq = -121001L;

    private void insertMark(String studentId, long examSubjectEntryId, double marksObtained) {
        long id = markSeq--;
        jdbc.update("INSERT INTO student_mark (id,school_id,student_id,exam_subject_entry_id,created_at,updated_at,marks_obtained) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, SCHOOL, studentId, examSubjectEntryId, marksObtained);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cleanup — dependency-safe, always scoped to the sentinel school
    // ══════════════════════════════════════════════════════════════════════

    /** Idempotent: safe to call whether or not any sentinel rows exist. Every statement is scoped
     *  to {@link #SCHOOL} (or joins through a table that itself carries {@code school_id}), and
     *  never touches any other school_id — including real production data. Ordered child-to-parent
     *  so foreign keys never block a step. */
    private void cleanupSentinelFixture() {
        jdbc.update("DELETE FROM student_mark WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM exam_subject_entry WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM exam_config WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM report_card_remark WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM report_card_publication WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM report_card_template_section WHERE template_id IN " +
                "(SELECT id FROM report_card_template WHERE school_id=?)", SCHOOL);
        jdbc.update("DELETE FROM report_card_template WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM assessment_group WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM attendance_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM allocation_refund WHERE student_fees_id IN " +
                "(SELECT id FROM student_fees WHERE school_id=?)", SCHOOL);
        jdbc.update("DELETE FROM refund WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM payment_student_fees_allocation WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM payment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_fees_line_item WHERE school_id=?", SCHOOL); // H4: FeeGenerationTargetService.generate writes one row per fee-head charge
        jdbc.update("DELETE FROM student_fees WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_structure_rule WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_head WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM class_teacher_activation WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM class_teacher_responsibility WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM timetable_entry WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_rollover_run WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id=?", SCHOOL);
    }

    private void assertSentinelFixtureIsAbsent() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM school WHERE id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM academic_session WHERE school_id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student WHERE school_id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timetable_entry WHERE school_id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=?", Integer.class, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=?", Integer.class, SCHOOL)).isZero();
    }
}
