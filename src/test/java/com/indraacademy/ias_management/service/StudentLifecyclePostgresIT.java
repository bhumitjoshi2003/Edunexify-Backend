package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.StudentExitRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentService.class, StudentEnrollmentService.class, StudentLifecyclePostgresIT.FixedClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentLifecyclePostgresIT {
    static final long SCHOOL=-98001, OTHER_SCHOOL=-98002, SESSION=-98003;
    static final long CLASS_ID=-98004, SECTION_ID=-98005, OTHER_CLASS=-98006, OTHER_SECTION=-98007;
    static final String STUDENT="LIFECYCLE-PG";
    static final LocalDate TODAY=LocalDate.of(2026,9,6), START=LocalDate.of(2026,9,1);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            ZoneId schoolZone = ZoneId.of("Asia/Kolkata");
            return Clock.fixed(TODAY.atStartOfDay(schoolZone).toInstant(), schoolZone);
        }
    }

    @Autowired StudentService studentService;
    @Autowired StudentEnrollmentService enrollmentService;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired StudentRepository students;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;

    @MockBean StudentFeesService studentFeesService;
    @MockBean UserDetailsServiceImpl userDetailsService;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean EntitlementService entitlementService;
    @MockBean ParentPortalService parentPortalService;
    @MockBean StudentLoginService studentLoginService;
    @MockBean IdGeneratorService idGeneratorService;
    @MockBean ObjectMapper objectMapper;
    final HttpServletRequest request=mock(HttpServletRequest.class);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("DB_URL"));
        registry.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
    }

    @BeforeEach void fixtures() throws Exception {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'Lifecycle IT','TRIAL','lifecycle-it',4,8,'Asia/Kolkata'),(?,true,CURRENT_TIMESTAMP,'Other Lifecycle IT','TRIAL','other-lifecycle-it',4,8,'Asia/Kolkata')",SCHOOL,OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',false,CURRENT_TIMESTAMP)",SESSION,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'9',true,false),(?,?,'X',true,false)",CLASS_ID,SCHOOL,OTHER_CLASS,OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true)",SECTION_ID,SCHOOL,CLASS_ID);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'X',true)",OTHER_SECTION,OTHER_SCHOOL,OTHER_CLASS);
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'9',?,'A',?)",STUDENT,SCHOOL,CLASS_ID,SECTION_ID,START);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'A','ACTIVE',?)",SCHOOL,STUDENT,SESSION,CLASS_ID,"9",SECTION_ID,START);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        when(securityUtil.getUsername()).thenReturn("admin");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test void activeExitClosesInclusiveWithReasonAndPreservesProjectionAndIsolation() {
        Student saved=studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request);
        StudentEnrollment closed=history().getFirst();
        assertThat(saved.getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
        assertThat(saved.getClassId()).isEqualTo(CLASS_ID);
        assertThat(saved.getSectionId()).isEqualTo(SECTION_ID);
        assertThat(closed.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(closed.getEffectiveUntil()).isEqualTo(TODAY);
        assertThat(closed.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.WITHDRAWN);
        verify(parentPortalService).endRelationshipsForExitedStudent(SCHOOL,STUDENT,TODAY);
        assertFinancialIsolation();
    }

    @Test void exitOnFirstDayKeepsAOneDayHistoricalSegment() {
        studentService.exitStudent(STUDENT,exit("TRANSFERRED",START),request);
        assertThat(history()).singleElement().satisfies(e->{
            assertThat(e.getEffectiveFrom()).isEqualTo(START);
            assertThat(e.getEffectiveUntil()).isEqualTo(START);
            assertThat(e.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.TRANSFERRED);
        });
    }

    @Test void sameSessionGapReadmissionCreatesNewSegmentWithoutChangingOldOne() {
        LocalDate exitDate=TODAY.minusDays(2);
        studentService.exitStudent(STUDENT,exit("WITHDRAWN",exitDate),request);
        StudentEnrollment old=history().getFirst();
        Long oldId=old.getId();
        studentService.readmitStudent(STUDENT,request);
        List<StudentEnrollment> history=history();
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().getId()).isEqualTo(oldId);
        assertThat(history.getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(history.getFirst().getEffectiveUntil()).isEqualTo(exitDate);
        assertThat(history.getLast().getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(history.getLast().getEffectiveFrom()).isEqualTo(TODAY);
        Student projected=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(projected.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(projected.getClassId()).isEqualTo(CLASS_ID);
        assertThat(projected.getSectionId()).isEqualTo(SECTION_ID);
        assertFinancialIsolation();
    }

    @Test void overlapInvalidDatesRepeatedRequestsAndTenantTargetsAreRejected() {
        assertThatThrownBy(()->studentService.exitStudent(STUDENT,exit("WITHDRAWN",START.minusDays(1)),request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->studentService.exitStudent(STUDENT,exit("WITHDRAWN",LocalDate.of(2027,4,1)),request))
                .isInstanceOf(IllegalArgumentException.class);
        studentService.exitStudent(STUDENT,exit("GRADUATED",TODAY),request);
        assertThatThrownBy(()->studentService.exitStudent(STUDENT,exit("GRADUATED",TODAY),request))
                .isInstanceOf(IllegalStateException.class);
        // Phase 1: a readmission must start after the leaving date (here the same day).
        assertThatThrownBy(()->studentService.readmitStudent(STUDENT,request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("after the leaving date");
        assertThatThrownBy(()->enrollmentService.createForExplicitReadmission(
                SCHOOL,STUDENT,SESSION,OTHER_CLASS,null,TODAY))
                .isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("Class");
        assertThatThrownBy(()->enrollmentService.createForExplicitReadmission(
                SCHOOL,STUDENT,SESSION,CLASS_ID,OTHER_SECTION,TODAY))
                .isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("Section");
    }

    @Test void lifecycleFailureRollsBackStudentAndEnrollmentTogether() {
        TestTransaction.flagForCommit(); TestTransaction.end();
        try {
            doThrow(new RuntimeException("synthetic parent cutoff failure")).when(parentPortalService)
                    .endRelationshipsForExitedStudent(SCHOOL,STUDENT,TODAY);
            assertThatThrownBy(()->studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request))
                    .isInstanceOf(RuntimeException.class);
            Student persisted=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(StudentStatus.ACTIVE);
            assertThat(history()).singleElement().satisfies(e->{
                assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
                assertThat(e.getEffectiveUntil()).isNull();
            });
        } finally {
            jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM section WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);
            jdbc.update("DELETE FROM school_class WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);
            jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);
            jdbc.update("DELETE FROM school WHERE id IN (?,?)",SCHOOL,OTHER_SCHOOL);
        }
    }

    // ─── E5B0: explicit exit auto-cancels a future PLANNED enrollment ───────────────────

    static final long NEXT_SESSION=-98008;

    private void insertNextSession(){
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,'2027-2028',DATE '2027-04-01',DATE '2028-03-31',false,CURRENT_TIMESTAMP)",NEXT_SESSION,SCHOOL);
    }
    private void insertFuturePlanned(String id,long sessionId,LocalDate from){
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'A','PLANNED',?)",SCHOOL,id,sessionId,CLASS_ID,"9",SECTION_ID,from);
    }

    @Test void explicitExitAutoCancelsFutureUnstartedPlannedEnrollmentWithCorrectReason() {
        insertNextSession();
        insertFuturePlanned(STUDENT,NEXT_SESSION,TODAY.plusDays(30));

        Student saved=studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request);

        assertThat(saved.getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
        StudentEnrollment currentClosed=history().getFirst();
        assertThat(currentClosed.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        List<StudentEnrollment> nextSessionHistory=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,NEXT_SESSION);
        assertThat(nextSessionHistory).singleElement().satisfies(e->{
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.CANCELLED);
            assertThat(e.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CANCELLED_BEFORE_START);
            assertThat(e.getEffectiveUntil()).isEqualTo(e.getEffectiveFrom());
        });
        verify(parentPortalService).endRelationshipsForExitedStudent(SCHOOL,STUDENT,TODAY);
        assertFinancialIsolation();
    }

    @Test void explicitExitNeverCancelsAnAlreadyEffectiveLaterSegmentAndFailsSafely() {
        insertNextSession();
        // Corrupt/unsupported shape: an ACTIVE (not PLANNED) later segment.
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,'A','ACTIVE',?)",SCHOOL,STUDENT,NEXT_SESSION,CLASS_ID,"9",SECTION_ID,TODAY.plusDays(5));

        assertThatThrownBy(()->studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("later enrollment segment");
        Student persisted=students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(history()).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
        List<StudentEnrollment> nextSessionHistory=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,NEXT_SESSION);
        assertThat(nextSessionHistory).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
    }

    @Test void concurrentCorrectionDuringExitEitherAppliesThenCancelsOrFailsCleanly() throws Exception {
        insertNextSession();
        insertFuturePlanned(STUDENT,NEXT_SESSION,TODAY.plusDays(30));
        Long futureId=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,NEXT_SESSION).getFirst().getId();
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Object> exitCall=()->{ start.await(10,TimeUnit.SECONDS); return studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request); };
            Callable<Object> correctCall=()->{
                start.await(10,TimeUnit.SECONDS);
                try { return enrollmentService.correctPlannedEnrollment(SCHOOL,STUDENT,futureId,CLASS_ID,SECTION_ID); }
                catch (IllegalStateException e) { return e; }
            };
            Future<Object> fe=pool.submit(exitCall), fc=pool.submit(correctCall);
            start.countDown();
            fe.get(20,TimeUnit.SECONDS); fc.get(20,TimeUnit.SECONDS);

            StudentEnrollment finalFutureRow=enrollments.findById(futureId).orElseThrow();
            assertThat(finalFutureRow.getStatus()).isEqualTo(StudentEnrollmentStatus.CANCELLED);
            assertThat(finalFutureRow.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CANCELLED_BEFORE_START);
            assertThat(finalFutureRow.getEffectiveUntil()).isEqualTo(finalFutureRow.getEffectiveFrom());
            assertThat(students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow().getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
            assertThat(history()).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED));
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    @Test void concurrentDuplicateExitIsOneLogicalExit() throws Exception {
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Object> call=()->{
                start.await(10,TimeUnit.SECONDS);
                try { return studentService.exitStudent(STUDENT,exit("WITHDRAWN",TODAY),request); }
                catch (IllegalStateException e) { return e; }
            };
            Future<Object> a=pool.submit(call), b=pool.submit(call);
            start.countDown();
            Object ra=a.get(20,TimeUnit.SECONDS), rb=b.get(20,TimeUnit.SECONDS);
            long successes=Stream.of(ra,rb).filter(r->r instanceof Student).count();
            long failures=Stream.of(ra,rb).filter(r->r instanceof IllegalStateException).count();
            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);
            assertThat(students.findByStudentIdAndSchoolId(STUDENT,SCHOOL).orElseThrow().getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
            assertThat(history()).singleElement().satisfies(e->assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED));
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    @Test void concurrentExitAndDueActivationOnDifferentSessionsSerializeWithoutCorruption() throws Exception {
        insertNextSession();
        // Due today for activation AND strictly later than an exit dated yesterday, so the two
        // operations race for the same student lock over the same row.
        LocalDate exitDate=TODAY.minusDays(1);
        insertFuturePlanned(STUDENT,NEXT_SESSION,TODAY);
        Long futureId=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,NEXT_SESSION).getFirst().getId();
        TestTransaction.flagForCommit(); TestTransaction.end();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Object> exitCall=()->{
                start.await(10,TimeUnit.SECONDS);
                try { return studentService.exitStudent(STUDENT,exit("WITHDRAWN",exitDate),request); }
                catch (IllegalStateException e) { return e; }
            };
            Callable<Object> activateCall=()->{ start.await(10,TimeUnit.SECONDS); return enrollmentService.activateEligiblePlannedEnrollment(SCHOOL,STUDENT,TODAY); };
            Future<Object> fe=pool.submit(exitCall), fa=pool.submit(activateCall);
            start.countDown();
            fe.get(20,TimeUnit.SECONDS); fa.get(20,TimeUnit.SECONDS);

            // Whichever won the race, the row must end up in exactly one clean terminal state —
            // never both ACTIVE and CANCELLED, never duplicated.
            StudentEnrollment finalFutureRow=enrollments.findById(futureId).orElseThrow();
            assertThat(finalFutureRow.getStatus()).isIn(StudentEnrollmentStatus.ACTIVE,StudentEnrollmentStatus.CANCELLED);
            List<StudentEnrollment> nextSessionHistory=enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,NEXT_SESSION);
            assertThat(nextSessionHistory).hasSize(1);
        } finally {
            pool.shutdownNow(); cleanupCommittedFixtures();
        }
    }

    // ── Fix A: student deletion must preserve financial history ────────────────────────────

    /** A generated-but-unpaid StudentFees row is still accounting/history state — deletion
     * must be cleanly rejected before any destructive cleanup, not merely once a FK happens
     * to be hit. */
    @Test void deleteStudent_withUnpaidGeneratedFees_isCleanlyRejected_studentAndFeesSurvive() {
        insertStudentFees(SCHOOL, STUDENT, SESSION, 1, false);

        assertThatThrownBy(() -> studentService.deleteStudent(STUDENT, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("financial records exist");

        assertThat(students.findByStudentIdAndSchoolId(STUDENT, SCHOOL)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?", Integer.class, SCHOOL, STUDENT))
                .isEqualTo(1);
    }

    /** Paid history with a real Payment + allocation must also be rejected — and rejected
     * BEFORE any destructive cleanup runs, never by letting a FK violation be the control
     * mechanism (a raw DataIntegrityViolationException would leak as a confusing 500). */
    @Test void deleteStudent_withPaidAllocationHistory_isCleanlyRejected_beforeAnyDestructiveCleanup() {
        Long studentFeesId = insertStudentFees(SCHOOL, STUDENT, SESSION, 1, true);
        Payment payment = new Payment(STUDENT, "Lifecycle Student", "9", "2026-2027", "100000000000",
                100000, "PAY-" + STUDENT, "ORDER-" + STUDENT, java.time.LocalDateTime.now(), "success",
                0, 100000, 0, 0, 0, 0, false, 100000, 0, 0);
        payment.setSchoolId(SCHOOL);
        payment.setAcademicSessionId(SESSION);
        payment.setRazorpaySignature("TEST-SIGNATURE");
        payment = payments.save(payment);
        jdbc.update("INSERT INTO payment_student_fees_allocation " +
                        "(payment_id, student_fees_id, school_id, student_id, session, month, amount_paise, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                payment.getId(), studentFeesId, SCHOOL, STUDENT, "2026-2027", 1, 100000L);
        // Also seed attendance/leave rows the pre-guard cleanup would otherwise have deleted —
        // if the guard's ordering were wrong (destructive cleanup before the check), these
        // would already be gone by the time we assert.
        insertAttendanceRow();

        assertThatThrownBy(() -> studentService.deleteStudent(STUDENT, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("financial records exist");

        assertThat(students.findByStudentIdAndSchoolId(STUDENT, SCHOOL)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE id=?", Integer.class, studentFeesId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE id=?", Integer.class, payment.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_student_fees_allocation WHERE payment_id=?", Integer.class, payment.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_attendance a JOIN attendance_session s ON s.id=a.attendance_session_id " +
                "WHERE s.school_id=? AND a.student_id=?", Integer.class, SCHOOL, STUDENT))
                .isEqualTo(1);
    }

    /** One explicit Attendance V2 row for STUDENT (its class/section submission on TODAY). */
    private void insertAttendanceRow() {
        Long submission = jdbc.queryForObject("INSERT INTO attendance_session (school_id,academic_session_id,class_id,section_id," +
                "attendance_date,marked_by_user_id,marked_at,updated_at) VALUES (?,?,?,?,?,'admin',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) " +
                "RETURNING id", Long.class, SCHOOL, SESSION, CLASS_ID, SECTION_ID, TODAY);
        jdbc.update("INSERT INTO student_attendance (attendance_session_id,student_id,status,created_at,updated_at) " +
                "VALUES (?,?,'PRESENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", submission, STUDENT);
    }

    /** The realistic case: an ordinarily-admitted student (the shared fixture() @BeforeEach
     * always creates a StudentEnrollment row, exactly like real admission does) has NO
     * financial history at all. Hard deletion must still be cleanly rejected — enrollment
     * history is retained the same way financial history is — recommending the exit workflow
     * instead. Nothing destructive may run before this rejection. */
    @Test void deleteStudent_ordinaryEnrolledStudent_isCleanlyRejected_recommendsExitWorkflow() {
        insertAttendanceRow();

        assertThatThrownBy(() -> studentService.deleteStudent(STUDENT, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enrollment")
                .hasMessageContaining("exit workflow");

        assertThat(students.findByStudentIdAndSchoolId(STUDENT, SCHOOL)).isPresent();
        assertThat(history()).isNotEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_attendance a JOIN attendance_session s ON s.id=a.attendance_session_id " +
                "WHERE s.school_id=? AND a.student_id=?", Integer.class, SCHOOL, STUDENT))
                .isEqualTo(1);
    }

    /** True empty-student hard delete: a student that was created but never actually
     * enrolled (no StudentEnrollment row — e.g. a provisional/mistakenly-created record) and
     * has no financial history either. Proves the two guards together do not disable hard
     * deletion globally — the legitimately dependent records (attendance) still get cleaned
     * up exactly as before. */
    @Test void deleteStudent_trulyEmptyStudent_hardDeleteSucceeds() {
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=? AND student_id=?", SCHOOL, STUDENT);
        insertAttendanceRow();

        studentService.deleteStudent(STUDENT, request);

        assertThat(students.findByStudentIdAndSchoolId(STUDENT, SCHOOL)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_attendance a JOIN attendance_session s ON s.id=a.attendance_session_id " +
                "WHERE s.school_id=? AND a.student_id=?", Integer.class, SCHOOL, STUDENT))
                .isZero();
    }

    /** Tenant isolation for the FINANCIAL guard specifically: another school's financial
     * history for a same-named studentId must never block deletion of THIS school's student,
     * who (like {@link #deleteStudent_trulyEmptyStudent_hardDeleteSucceeds}) has neither
     * financial nor enrollment history of its own. */
    @Test void deleteStudent_anotherSchoolsFinancialHistory_neverBlocksThisSchoolsStudent() {
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=? AND student_id=?", SCHOOL, STUDENT);
        insertStudentFees(OTHER_SCHOOL, STUDENT, null, 1, false);

        studentService.deleteStudent(STUDENT, request);

        assertThat(students.findByStudentIdAndSchoolId(STUDENT, SCHOOL)).isEmpty();
        // The other school's row must remain completely untouched.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?", Integer.class, OTHER_SCHOOL, STUDENT))
                .isEqualTo(1);
        jdbc.update("DELETE FROM student_fees WHERE school_id=? AND student_id=?", OTHER_SCHOOL, STUDENT);
    }

    // Note on enrollment-guard tenant scoping (Task 8): student.student_id is this schema's
    // actual PRIMARY KEY ("student_pkey") — globally unique across every school, not
    // composite with school_id — confirmed by attempting exactly this scenario, which fails
    // with "duplicate key value violates unique constraint student_pkey" before even reaching
    // student_enrollment. Two schools can therefore never share the same studentId in real
    // data, making a genuine cross-school collision on the enrollment guard impossible to
    // construct (unlike student_fees above, which carries no FK back to a real student row
    // and so tolerates a synthetic same-id row for that narrower proof). The guard itself —
    // studentEnrollmentRepository.existsByStudentIdAndSchoolId(studentId, schoolId) — is still
    // correctly scoped by both fields, mechanically guaranteed by Spring Data's derived-query
    // translation (WHERE student_id = ? AND school_id = ?), matching the same tenant-safe
    // shape as fk_student_enrollment_student's own composite (school_id, student_id) FK.

    private Long insertStudentFees(long schoolId, String studentId, Long academicSessionId, int month, boolean paid) {
        return jdbc.queryForObject(
                "INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '9', ?, ?, false, '2026-2027', ?, " +
                        "0, false, 1000, 1000, 0, 0, 'COMPUTED') RETURNING id",
                Long.class, schoolId, studentId, month, paid, academicSessionId);
    }

    private void cleanupCommittedFixtures(){
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id IN (?,?)",SCHOOL,OTHER_SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?,?)",SCHOOL,OTHER_SCHOOL);
    }

    private StudentExitRequest exit(String type,LocalDate date){
        StudentExitRequest r=new StudentExitRequest(); r.setExitType(type); r.setLeavingDate(date);
        r.setReasonForLeaving("Synthetic lifecycle test"); return r;
    }
    private List<StudentEnrollment> history(){
        return enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(SCHOOL,STUDENT,SESSION);
    }
    private void assertFinancialIsolation(){
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,STUDENT)).isZero();
    }
}
