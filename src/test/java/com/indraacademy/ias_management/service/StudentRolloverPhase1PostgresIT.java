package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Student Promotion Phase 1 on real PostgreSQL: TRANSFER (the only year-end leaving decision) through the existing exit
 * workflow, explicit PENDING, PROMOTE / DETAIN / PASS_OUT unchanged, the student_rollover_run
 * record, per-student partial failure, idempotent re-execution, cross-school rejection and the
 * session readiness counts. Workers use REQUIRES_NEW, so fixtures are committed and cleaned up.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentPromotionService.class, StudentYearEndWorker.class, StudentYearEndService.class,
        StudentYearEndExitWorker.class, StudentService.class, StudentEnrollmentService.class,
        SessionReadinessService.class, com.indraacademy.ias_management.scheduler.StudentStatusScheduler.class,
        StudentRolloverPhase1PostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentRolloverPhase1PostgresIT {

    static final long SCHOOL = -95101, OTHER_SCHOOL = -95102, SOURCE = -95103, TARGET = -95104, OTHER_SESSION = -95105;
    static final long CLASS_9 = -95110, CLASS_10 = -95111;
    static final long SEC_9A = -95120, SEC_10A = -95121, SEC_10B = -95122;
    static final String P1 = "RO-P1", D1 = "RO-D1", G1 = "RO-G1", T1 = "RO-T1", W1 = "RO-W1", N1 = "RO-N1", X1 = "RO-X1";

    static final Instant MID_MARCH = Instant.parse("2027-03-15T06:00:00Z");

    /** A clock the test can move forward (the nightly scheduler finalizes scheduled exits). */
    static class MovableClock extends Clock {
        volatile Instant now = MID_MARCH;
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean MovableClock clock() { return new MovableClock(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired StudentPromotionService coordinator;
    @Autowired com.indraacademy.ias_management.scheduler.StudentStatusScheduler scheduler;
    @Autowired StudentService studentService;
    @Autowired MovableClock clock;
    @Autowired SessionReadinessService readiness;
    @Autowired JdbcTemplate jdbc;
    @MockBean SecurityUtil security;
    @MockBean AuditService auditService;
    @MockBean ParentPortalService parentPortal;
    @MockBean StudentFeesService studentFeesService;
    @MockBean UserDetailsServiceImpl userDetailsService;
    @MockBean EntitlementService entitlementService;
    @MockBean IdGeneratorService idGeneratorService;
    @MockBean ObjectMapper objectMapper;

    final HttpServletRequest http = mock(HttpServletRequest.class);
    final Map<String, Long> source = new java.util.HashMap<>();

    @BeforeEach
    void fixtures() {
        clock.now = MID_MARCH;
        asAdmin(SCHOOL);
        when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES "
                + "(?,true,CURRENT_TIMESTAMP,'Rollover IT','TRIAL','rollover-it',4,8,'Asia/Kolkata'),"
                + "(?,true,CURRENT_TIMESTAMP,'Rollover Other','TRIAL','rollover-other',4,8,'Asia/Kolkata')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES "
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),"
                + "(?,?,'2027-2028',DATE '2027-04-01',DATE '2028-03-31',false,CURRENT_TIMESTAMP),"
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)",
                SOURCE, SCHOOL, TARGET, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES "
                + "(?,?,'9',true,1,false),(?,?,'10',true,2,false)", CLASS_9, SCHOOL, CLASS_10, SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active,display_order) VALUES "
                + "(?,?,?,'A',true,1),(?,?,?,'A',true,1),(?,?,?,'B',true,2)",
                SEC_9A, SCHOOL, CLASS_9, SEC_10A, SCHOOL, CLASS_10, SEC_10B, SCHOOL, CLASS_10);
        for (String id : List.of(P1, D1, T1, W1, N1, X1)) student(id, CLASS_9, "9", SEC_9A);
        student(G1, CLASS_10, "10", SEC_10A);
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM student_rollover_run WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?,?)", SCHOOL, OTHER_SCHOOL);
        TestTransaction.start();
    }

    @Test
    void fullYearEndBatchAppliesEveryDecisionAndRecordsTheRun() {
        try {
            PromotionResultDTO result = coordinator.executePromotion(batch(), http);
            Map<String, String> codes = codes(result);
            assertThat(codes).containsEntry(P1, "PROMOTED").containsEntry(D1, "DETAINED").containsEntry(G1, "PASSED_OUT")
                    .containsEntry(T1, "TRANSFERRED").containsEntry(W1, "TRANSFERRED").containsEntry(N1, "PENDING")
                    .containsEntry(X1, "INVALID_SOURCE");

            // PROMOTE: source closed at session end, PLANNED target in the future session, projection untouched.
            assertThat(enrollment(P1, SOURCE)).containsEntry("status", "CLOSED").containsEntry("closure_reason", "SESSION_COMPLETED")
                    .containsEntry("effective_until", java.sql.Date.valueOf("2027-03-31"));
            assertThat(enrollment(P1, TARGET)).containsEntry("status", "PLANNED").containsEntry("class_id", CLASS_10)
                    .containsEntry("section_id", SEC_10B);
            assertThat(jdbc.queryForObject("SELECT class_id FROM student WHERE student_id=?", Long.class, P1)).isEqualTo(CLASS_9);
            // DETAIN keeps the class and section.
            assertThat(enrollment(D1, TARGET)).containsEntry("class_id", CLASS_9).containsEntry("section_id", SEC_9A);
            // PASS_OUT is recorded, graduation waits for the session end.
            assertThat(enrollment(G1, SOURCE)).containsEntry("closure_reason", "GRADUATED");
            assertThat(status(G1)).isEqualTo("ACTIVE");
            // TRANSFER before the session ends (today = 2027-03-15): recorded for the session
            // end — the enrollment stays effective until 31 Mar, the student stays ACTIVE with parent access.
            assertThat(enrollment(T1, SOURCE)).containsEntry("status", "CLOSED").containsEntry("closure_reason", "TRANSFERRED")
                    .containsEntry("effective_until", java.sql.Date.valueOf("2027-03-31"));
            assertThat(enrollment(W1, SOURCE)).containsEntry("closure_reason", "TRANSFERRED")
                    .containsEntry("effective_until", java.sql.Date.valueOf("2027-03-31"));
            assertThat(status(T1)).isEqualTo("ACTIVE");
            assertThat(status(W1)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT leaving_date FROM student WHERE student_id=?", java.sql.Date.class, T1)).isNull();
            assertThat(jdbc.queryForObject("SELECT reason_for_leaving FROM student WHERE student_id=?", String.class, T1))
                    .isEqualTo("Moved to another city");
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", T1)).isOne();   // no target enrollment
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", W1)).isOne();
            verify(parentPortal, never()).endRelationshipsForExitedStudent(eq(SCHOOL), eq(T1), any());
            verify(parentPortal, never()).endRelationshipsForExitedStudent(eq(SCHOOL), eq(W1), any());
            assertThat(result.outcomes()).filteredOn(o -> o.studentId().equals(T1))
                    .singleElement().satisfies(o -> assertThat(o.lifecycleFinalizationPending()).isTrue());
            // PENDING changes nothing; the failed student is untouched.
            assertThat(enrollment(N1, SOURCE)).containsEntry("status", "ACTIVE");
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", N1)).isOne();
            assertThat(enrollment(X1, SOURCE)).containsEntry("status", "ACTIVE");

            // The run record.
            PromotionResultDTO.RunSummary run = result.run();
            assertThat(run).isNotNull();
            assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
            assertThat(run.totalStudents()).isEqualTo(7);
            assertThat(List.of(run.promoted(), run.detained(), run.passOut(), run.transferred(),
                    run.pending(), run.alreadyApplied(), run.failed())).containsExactly(1, 1, 1, 2, 1, 0, 1);
            assertThat(run.classId()).isEqualTo(CLASS_9);
            assertThat(run.startedBy()).isEqualTo("admin");
            assertThat(run.finishedAt()).isNotNull();
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM student_rollover_run WHERE id=?", run.id());
            assertThat(row).containsEntry("status", "COMPLETED_WITH_ERRORS").containsEntry("transfer_count", 2).containsEntry("withdraw_count", 0)
                    .containsEntry("pending_count", 1).containsEntry("failed_count", 1).containsEntry("school_id", SCHOOL);
            assertThat(coordinator.recentRuns(TARGET)).extracting(PromotionResultDTO.RunSummary::id).containsExactly(run.id());
        } finally {
            cleanup();
        }
    }

    @Test
    void reExecutingIsIdempotentAndADifferentExitConflicts() {
        try {
            coordinator.executePromotion(batch(), http);
            PromotionResultDTO again = coordinator.executePromotion(batch(), http);
            Map<String, String> codes = codes(again);
            for (String id : List.of(P1, D1, G1, T1, W1)) assertThat(codes).containsEntry(id, "ALREADY_APPLIED");
            assertThat(codes).containsEntry(N1, "PENDING");
            assertThat(again.run().alreadyApplied()).isEqualTo(5);
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", P1)).isEqualTo(2);
            assertThat(again.outcomes()).filteredOn(o -> o.studentId().equals(T1))
                    .singleElement().satisfies(o -> assertThat(o.lifecycleFinalizationPending()).isTrue());
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", T1)).isOne();

            PromotionDecisionRequest switched = request(List.of(decision(T1, "DETAIN", CLASS_9, null), decision(W1, "PROMOTE", CLASS_10, SEC_10B)));
            assertThat(codes(coordinator.executePromotion(switched, http))).containsEntry(T1, "CONFLICT").containsEntry(W1, "CONFLICT");
            assertThat(enrollment(T1, SOURCE)).containsEntry("closure_reason", "TRANSFERRED");
            assertThat(status(T1)).isEqualTo("ACTIVE");
            assertThat(coordinator.recentRuns(TARGET)).hasSize(3);
        } finally {
            cleanup();
        }
    }

    @Test
    void exitDatesAreValidatedAndOtherSchoolsAreRejected() {
        try {
            // No arbitrary dates in year-end rollover: only the session end is accepted.
            PromotionResultDTO chosen = coordinator.executePromotion(request(List.of(
                    exit(T1, "TRANSFER", LocalDate.of(2027, 3, 20), "x"),
                    exit(W1, "TRANSFER", LocalDate.of(2027, 3, 31), "x"))), http);
            assertThat(codes(chosen)).containsEntry(T1, "VALIDATION_ERROR").containsEntry(W1, "TRANSFERRED");
            assertThat(chosen.outcomes()).extracting(PromotionResultDTO.StudentOutcome::message)
                    .anyMatch(m -> m.contains("a different leaving date can't be chosen"));
            assertThat(enrollment(T1, SOURCE)).containsEntry("status", "ACTIVE");

            // The normal Student Details exit still rejects future dates.
            com.indraacademy.ias_management.dto.StudentExitRequest normal = new com.indraacademy.ias_management.dto.StudentExitRequest();
            normal.setExitType("TRANSFERRED");
            normal.setReasonForLeaving("x");
            normal.setLeavingDate(LocalDate.of(2027, 3, 31));
            assertThatThrownBy(() -> studentService.exitStudent(T1, normal, http)).hasMessageContaining("cannot be in the future");

            // Another school's admin: the sessions and class don't resolve, nothing is applied or recorded.
            asAdmin(OTHER_SCHOOL);
            assertThatThrownBy(() -> coordinator.executePromotion(batch(), http)).hasMessageContaining("not found for school");
            assertThat(enrollment(P1, SOURCE)).containsEntry("status", "ACTIVE");
            assertThat(count("SELECT count(*) FROM student_rollover_run WHERE school_id=?", OTHER_SCHOOL)).isZero();
            PromotionDecisionRequest ownSessionsForeignClass = request(List.of(exit(T1, "PENDING", null, null)));
            ownSessionsForeignClass.setSourceSessionId(OTHER_SESSION);
            ownSessionsForeignClass.setTargetSessionId(OTHER_SESSION);
            assertThatThrownBy(() -> coordinator.executePromotion(ownSessionsForeignClass, http)).isInstanceOf(RuntimeException.class);
            asAdmin(SCHOOL);
        } finally {
            cleanup();
        }
    }

    @Test
    void scheduledExitsBecomeEffectiveAtTheSessionEnd() {
        try {
            coordinator.executePromotion(request(List.of(exit(T1, "TRANSFER", null, "New city"), exit(W1, "TRANSFER", null, null))), http);

            clock.now = Instant.parse("2027-03-30T06:00:00Z");     // still inside the session
            scheduler.updateStudentStatuses();
            assertThat(status(T1)).isEqualTo("ACTIVE");
            verify(parentPortal, never()).endRelationshipsForExitedStudent(eq(SCHOOL), eq(T1), any());

            clock.now = Instant.parse("2027-03-31T06:00:00Z");     // the effective date
            scheduler.updateStudentStatuses();
            assertThat(status(T1)).isEqualTo("TRANSFERRED");
            assertThat(status(W1)).isEqualTo("TRANSFERRED");
            assertThat(jdbc.queryForObject("SELECT leaving_date FROM student WHERE student_id=?", java.sql.Date.class, T1))
                    .isEqualTo(java.sql.Date.valueOf("2027-03-31"));
            assertThat(jdbc.queryForObject("SELECT reason_for_leaving FROM student WHERE student_id=?", String.class, T1)).isEqualTo("New city");
            assertThat(jdbc.queryForObject("SELECT reason_for_leaving FROM student WHERE student_id=?", String.class, W1)).isEqualTo("Transferred at year end");
            verify(parentPortal).endRelationshipsForExitedStudent(SCHOOL, T1, LocalDate.of(2027, 3, 31));
            verify(parentPortal).endRelationshipsForExitedStudent(SCHOOL, W1, LocalDate.of(2027, 3, 31));
            assertThat(count("SELECT count(*) FROM student_enrollment WHERE student_id=?", T1)).isOne();

            // Running again is a no-op; re-submitting the decision reports it as already applied.
            scheduler.updateStudentStatuses();
            verify(parentPortal, times(1)).endRelationshipsForExitedStudent(SCHOOL, T1, LocalDate.of(2027, 3, 31));
            assertThat(codes(coordinator.executePromotion(request(List.of(exit(T1, "TRANSFER", null, null))), http)))
                    .containsEntry(T1, "ALREADY_APPLIED");
        } finally {
            cleanup();
        }
    }

    @Test
    void afterTheSessionEndedTheExitAppliesAtOnceAsOfTheSessionEnd() {
        try {
            clock.now = Instant.parse("2027-04-05T06:00:00Z");
            PromotionResultDTO result = coordinator.executePromotion(request(List.of(exit(T1, "TRANSFER", null, "Left"))), http);
            assertThat(codes(result)).containsEntry(T1, "TRANSFERRED");
            assertThat(result.outcomes().getFirst().lifecycleFinalizationPending()).isFalse();
            assertThat(status(T1)).isEqualTo("TRANSFERRED");
            assertThat(enrollment(T1, SOURCE)).containsEntry("closure_reason", "TRANSFERRED")
                    .containsEntry("effective_until", java.sql.Date.valueOf("2027-03-31"));
            verify(parentPortal).endRelationshipsForExitedStudent(SCHOOL, T1, LocalDate.of(2027, 3, 31));
            assertThat(result.run().transferred()).isOne();
            assertThat(result.run().pending()).isZero();
        } finally {
            cleanup();
        }
    }

    @Test
    void readinessCountsUndecidedPlannedTimetableAndFees() {
        try {
            coordinator.executePromotion(batch(), http);
            SessionReadinessService.Readiness r = readiness.readiness(TARGET);
            assertThat(r.previousSessionId()).isEqualTo(SOURCE);
            assertThat(r.pendingStudents()).isEqualTo(2);                   // N1 (pending) and X1 (failed)
            assertThat(r.pendingSample()).extracting(SessionReadinessService.PendingStudent::studentId).containsExactlyInAnyOrder(N1, X1);
            assertThat(r.plannedEnrollments()).isEqualTo(2);                // P1 and D1
            assertThat(r.plannedDueNow()).isZero();
            assertThat(r.targetEnrolled()).isEqualTo(2);
            assertThat(r.timetableEntries()).isZero();
            assertThat(r.studentsWithoutFees()).isEqualTo(2);
            assertThat(r.warnings()).anyMatch(w -> w.contains("2 student(s) from 2026-2027 have no year-end decision"))
                    .anyMatch(w -> w.contains("No timetable")).anyMatch(w -> w.contains("no fees generated"));

            asAdmin(OTHER_SCHOOL);
            assertThatThrownBy(() -> readiness.readiness(TARGET)).hasMessageContaining("Session not found");
            asAdmin(SCHOOL);
        } finally {
            cleanup();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private PromotionDecisionRequest batch() {
        return request(List.of(
                decision(P1, "PROMOTE", CLASS_10, SEC_10B),
                decision(D1, "DETAIN", CLASS_9, null),
                gradDecision(),
                exit(T1, "TRANSFER", null, "Moved to another city"),
                exit(W1, "TRANSFER", null, null),
                exit(N1, "PENDING", null, null),
                wrongSource()));
    }

    private PromotionDecisionRequest request(List<PromotionDecisionRequest.Decision> decisions) {
        PromotionDecisionRequest r = new PromotionDecisionRequest();
        r.setSourceSessionId(SOURCE);
        r.setTargetSessionId(TARGET);
        r.setClassId(CLASS_9);
        r.setDecisions(decisions);
        return r;
    }

    private PromotionDecisionRequest.Decision decision(String id, String action, Long targetClass, Long targetSection) {
        PromotionDecisionRequest.Decision d = new PromotionDecisionRequest.Decision();
        d.setStudentId(id);
        d.setAction(StudentYearEndDecision.Action.valueOf(action));
        d.setExpectedSourceEnrollmentId(sourceId(id));
        d.setExpectedSourceClassId(id.equals(G1) ? CLASS_10 : CLASS_9);
        d.setTargetClassId(targetClass);
        d.setTargetSectionId(targetSection);
        return d;
    }

    private PromotionDecisionRequest.Decision gradDecision() { return decision(G1, "PASS_OUT", null, null); }

    private PromotionDecisionRequest.Decision exit(String id, String action, LocalDate date, String reason) {
        PromotionDecisionRequest.Decision d = decision(id, action, null, null);
        d.setLeavingDate(date);
        d.setReason(reason);
        return d;
    }

    private PromotionDecisionRequest.Decision wrongSource() {
        PromotionDecisionRequest.Decision d = decision(X1, "PROMOTE", CLASS_10, SEC_10A);
        d.setExpectedSourceEnrollmentId(sourceId(P1));
        return d;
    }

    private long sourceId(String id) {
        return source.computeIfAbsent(id, k -> jdbc.queryForObject(
                "SELECT id FROM student_enrollment WHERE student_id=? AND academic_session_id=?", Long.class, k, SOURCE));
    }

    private static Map<String, String> codes(PromotionResultDTO result) {
        return result.outcomes().stream().collect(Collectors.toMap(PromotionResultDTO.StudentOutcome::studentId,
                PromotionResultDTO.StudentOutcome::code, (a, b) -> a));
    }

    private Map<String, Object> enrollment(String id, long session) {
        return jdbc.queryForMap("SELECT * FROM student_enrollment WHERE student_id=? AND academic_session_id=?", id, session);
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM student WHERE student_id=?", String.class, id);
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private void student(String id, long classId, String className, long sectionId) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) "
                + "VALUES (?,?,?,'ACTIVE',?,?,?,'A',DATE '2026-04-01')", id, SCHOOL, "Student " + id, classId, className, sectionId);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,"
                + "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'A','ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SOURCE, classId, className, sectionId);
    }

    private void asAdmin(long school) {
        when(security.getSchoolId()).thenReturn(school);
        when(security.getUsername()).thenReturn("admin");
        when(security.getRole()).thenReturn("ADMIN");
    }
}
