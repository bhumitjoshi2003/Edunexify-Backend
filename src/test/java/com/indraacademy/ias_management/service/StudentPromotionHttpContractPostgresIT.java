package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Thin HTTP-contract regression for {@code POST /api/students/promotion/execute}, added after the
 * PASS_OUT defect (fixed in {@link StudentPromotionService}'s {@code executePromotion}) was found
 * specifically at the controller/API boundary: {@code StudentController.executePromotion}'s
 * {@code @Valid PromotionDecisionRequest} parameter always carries one shared, batch-level,
 * {@code @NotNull targetSessionId} regardless of what mix of actions the batch contains, and that
 * controller method is a pure 3-line pass-through with no logic of its own — {@code int count =
 * ...; log.warn(...); return ResponseEntity.ok(studentPromotionService.executePromotion(request,
 * httpRequest));}. So the meaningful "HTTP boundary" contract to prove is (a) that a real,
 * wire-shaped batch payload a web/Android client would actually send for a PASS_OUT decision
 * ({@code targetClassId}/{@code targetSectionId} both omitted — {@code Decision} has no
 * {@code @NotNull} on either) passes Bean Validation exactly as the controller enforces it, and
 * (b) that same validated request, handed unmodified to {@code StudentPromotionService
 * .executePromotion} — the literal call the controller makes — reaches real, Postgres-backed
 * orchestration correctly.
 *
 * <p>Deliberately NOT a full {@code @SpringBootTest} + MockMvc: that would boot the entire
 * application context, including every {@code @Scheduled} job ({@link
 * com.indraacademy.ias_management.scheduler.StudentStatusScheduler} iterates every active school
 * in the database on a timer) — running those for real against the shared DEV Neon database this
 * suite runs against is an unacceptable, unbounded blast-radius risk this whole verification phase
 * has deliberately avoided. This test instead follows the codebase's own established
 * {@code @DataJpaTest} + real-service-{@code @Import} PostgresIT idiom, which never boots
 * scheduling, security filters, or the web layer, and validates the DTO's {@code @Valid} contract
 * directly via a standalone {@link Validator} instead.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentPromotionService.class, StudentYearEndWorker.class, StudentYearEndService.class,
        com.indraacademy.ias_management.config.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentPromotionHttpContractPostgresIT {

    private static final long SCHOOL = -130001L;
    private static final long SOURCE_SESSION = -130101L, TARGET_SESSION = -130102L;
    private static final String SOURCE_SESSION_LABEL = "2033-2034", TARGET_SESSION_LABEL = "2034-2035";
    private static final long CLASS_A = -130201L, CLASS_B = -130202L; // CLASS_B is the terminal class
    private static final String S_PASSOUT = "HTTP-PASSOUT";
    private static final String S_PROMOTE = "HTTP-PROMOTE";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Autowired JdbcTemplate jdbc;
    @Autowired StudentPromotionService studentPromotionService;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean ParentPortalService parentPortalService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void mocksAndFixture() {
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        cleanupFixture();
        seed();
        // StudentYearEndWorker/StudentYearEndService run each decision in its own REQUIRES_NEW
        // sub-transaction — it cannot see this @BeforeEach's fixture rows until they are actually
        // committed, not merely visible within the ambient @DataJpaTest test-transaction.
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @AfterEach
    void cleanup() {
        cleanupFixture();
        TestTransaction.start();
    }

    @Test
    void passOutOnlyBatch_passesValidation_reachesRealOrchestration_createsNoTargetEnrollment() {
        long passoutEnrollmentId = sourceEnrollmentId(S_PASSOUT);

        PromotionDecisionRequest.Decision decision = new PromotionDecisionRequest.Decision();
        decision.setStudentId(S_PASSOUT);
        decision.setAction(StudentYearEndDecision.Action.PASS_OUT);
        decision.setExpectedSourceEnrollmentId(passoutEnrollmentId);
        decision.setExpectedSourceClassId(CLASS_B);
        // targetClassId/targetSectionId intentionally left null: exactly what a real web/Android
        // client sends for a PASS_OUT decision.

        PromotionDecisionRequest batch = new PromotionDecisionRequest();
        batch.setSourceSessionId(SOURCE_SESSION);
        batch.setTargetSessionId(TARGET_SESSION); // legitimate, @NotNull batch-level target, shared regardless of action
        batch.setDecisions(List.of(decision));

        // "Passes controller validation" — the exact @Valid contract StudentController.executePromotion enforces.
        Set<ConstraintViolation<PromotionDecisionRequest>> violations = validator.validate(batch);
        assertThat(violations).as("a legitimate PASS_OUT batch payload violates no Bean Validation constraint").isEmpty();

        // "Reaches the real promotion orchestration" — the literal call StudentController.executePromotion makes.
        PromotionResultDTO result = studentPromotionService.executePromotion(batch, mock(HttpServletRequest.class));
        assertThat(result.outcomes()).hasSize(1);
        var outcome = result.outcomes().get(0);
        assertThat(outcome.studentId()).isEqualTo(S_PASSOUT);
        assertThat(outcome.code()).isEqualTo("PASSED_OUT");
        assertThat(outcome.targetEnrollmentId()).isNull();
        assertThat(outcome.lifecycleFinalizationPending()).isTrue(); // source session end date is far in the future

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, TARGET_SESSION))
                .as("PASS_OUT must create no target-session enrollment").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                String.class, SCHOOL, S_PASSOUT, SOURCE_SESSION))
                .as("source enrollment closed, graduation recorded").isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM student WHERE school_id=? AND student_id=?", String.class, SCHOOL, S_PASSOUT))
                .as("graduation still pending — Student.status untouched until StudentStatusScheduler finalizes it")
                .isEqualTo("ACTIVE");
    }

    @Test
    void mixedPromoteAndPassOutBatch_bothOutcomesSucceedIndependently() {
        long promoteEnrollmentId = sourceEnrollmentId(S_PROMOTE);
        long passoutEnrollmentId = sourceEnrollmentId(S_PASSOUT);

        PromotionDecisionRequest.Decision promoteDecision = new PromotionDecisionRequest.Decision();
        promoteDecision.setStudentId(S_PROMOTE);
        promoteDecision.setAction(StudentYearEndDecision.Action.PROMOTE);
        promoteDecision.setExpectedSourceEnrollmentId(promoteEnrollmentId);
        promoteDecision.setExpectedSourceClassId(CLASS_A);
        promoteDecision.setTargetClassId(CLASS_B);
        // CLASS_B has zero active sections — targetSectionId correctly stays null.

        PromotionDecisionRequest.Decision passOutDecision = new PromotionDecisionRequest.Decision();
        passOutDecision.setStudentId(S_PASSOUT);
        passOutDecision.setAction(StudentYearEndDecision.Action.PASS_OUT);
        passOutDecision.setExpectedSourceEnrollmentId(passoutEnrollmentId);
        passOutDecision.setExpectedSourceClassId(CLASS_B);

        PromotionDecisionRequest batch = new PromotionDecisionRequest();
        batch.setSourceSessionId(SOURCE_SESSION);
        batch.setTargetSessionId(TARGET_SESSION); // the SAME batch-level target for both decisions
        batch.setDecisions(List.of(promoteDecision, passOutDecision));

        Set<ConstraintViolation<PromotionDecisionRequest>> violations = validator.validate(batch);
        assertThat(violations).as("a legitimate mixed PROMOTE + PASS_OUT batch violates no Bean Validation constraint").isEmpty();

        PromotionResultDTO result = studentPromotionService.executePromotion(batch, mock(HttpServletRequest.class));
        var outcomeById = result.outcomes().stream()
                .collect(java.util.stream.Collectors.toMap(PromotionResultDTO.StudentOutcome::studentId, o -> o));

        assertThat(outcomeById.get(S_PROMOTE).code()).isEqualTo("PROMOTED");
        assertThat(outcomeById.get(S_PROMOTE).targetEnrollmentId()).isNotNull();
        assertThat(outcomeById.get(S_PASSOUT).code()).isEqualTo("PASSED_OUT");
        assertThat(outcomeById.get(S_PASSOUT).targetEnrollmentId())
                .as("PASS_OUT creates no target enrollment even in a mixed batch sharing one target session with a PROMOTE decision")
                .isNull();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PROMOTE, TARGET_SESSION)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, S_PASSOUT, TARGET_SESSION)).isZero();
    }

    private long sourceEnrollmentId(String studentId) {
        return jdbc.queryForObject(
                "SELECT id FROM student_enrollment WHERE school_id=? AND student_id=? AND academic_session_id=?",
                Long.class, SCHOOL, studentId, SOURCE_SESSION);
    }

    private void seed() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,'HTTP Contract IT','TRIAL','http-contract-it',4,8,'Asia/Kolkata')", SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) " +
                "VALUES (?,?,?,DATE '2033-04-01',DATE '2034-03-31',true,CURRENT_TIMESTAMP)", SOURCE_SESSION, SCHOOL, SOURCE_SESSION_LABEL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) " +
                "VALUES (?,?,?,DATE '2034-04-01',DATE '2035-03-31',false,CURRENT_TIMESTAMP)", TARGET_SESSION, SCHOOL, TARGET_SESSION_LABEL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'9',true,1,false)", CLASS_A, SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'10',true,2,false)", CLASS_B, SCHOOL);
        // CLASS_B intentionally has zero active sections.

        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,'9',DATE '2033-04-01')", S_PROMOTE, SCHOOL, "Student " + S_PROMOTE, CLASS_A);
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,'10',DATE '2033-04-01')", S_PASSOUT, SCHOOL, "Student " + S_PASSOUT, CLASS_B);

        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                "status,effective_from) VALUES (?,?,?,?,'9','ACTIVE',DATE '2033-04-01')", SCHOOL, S_PROMOTE, SOURCE_SESSION, CLASS_A);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                "status,effective_from) VALUES (?,?,?,?,'10','ACTIVE',DATE '2033-04-01')", SCHOOL, S_PASSOUT, SOURCE_SESSION, CLASS_B);
    }

    private void cleanupFixture() {
        jdbc.update("DELETE FROM student_rollover_run WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id=?", SCHOOL);
    }
}
