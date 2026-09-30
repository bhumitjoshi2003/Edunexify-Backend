package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.StudentAdmissionDtos;
import com.indraacademy.ias_management.dto.StudentExitRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Student Admission / Enrollment Phase 1 on real PostgreSQL (V88), with real commits so the
 * admission transaction (student + enrollment + login) and every lifecycle rule behave as in
 * production. "Today" is Wednesday 30 Sep 2026 in Asia/Kolkata; sessions 2025-26 (past),
 * 2026-27 (current) and 2027-28 (future) exist.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StudentService.class, StudentEnrollmentService.class, StudentLoginService.class, ParentPortalService.class,
        StudentAdmissionPhase1PostgresIT.Beans.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentAdmissionPhase1PostgresIT {

    static final long SCHOOL = -97401, OTHER_SCHOOL = -97402, PAST = -97403, CURRENT = -97404, FUTURE = -97405;
    static final long CLASS_9 = -97410, CLASS_10 = -97411, SEC_9A = -97420, SEC_10A = -97421, SEC_10B = -97422;
    static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @TestConfiguration
    static class Beans {
        @Bean Clock clock() {
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            return Clock.fixed(TODAY.atTime(9, 0).atZone(zone).toInstant(), zone);
        }
        @Bean ObjectMapper objectMapper() { return Jackson2ObjectMapperBuilder.json().build(); }
        @Bean PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override public String encode(CharSequence raw) { return "enc:" + raw; }
                @Override public boolean matches(CharSequence raw, String encoded) { return encoded.equals("enc:" + raw); }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired StudentService studentService;
    @Autowired StudentEnrollmentService enrollmentService;
    @Autowired StudentRepository students;
    @Autowired StudentEnrollmentRepository enrollments;
    @Autowired UserRepository users;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean EntitlementService entitlementService;
    @MockBean StudentFeesService studentFeesService;
    @MockBean UserDetailsServiceImpl userDetailsService;
    @MockBean IdGeneratorService idGeneratorService;
    @MockBean WelcomeEmailService welcomeEmailService;
    @MockBean UserSessionService userSessionService;
    @MockBean PasswordResetService passwordResetService;

    final HttpServletRequest http = mock(HttpServletRequest.class);
    final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void fixtures() {
        cleanup();
        when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        as(SCHOOL);
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(entitlementService.hasFeature(anyLong(), eq("PARENT_PORTAL"))).thenReturn(true);
        when(idGeneratorService.generateStudentId()).thenAnswer(inv -> "P1S-" + ids.incrementAndGet());
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,working_days) VALUES "
                + "(?,true,CURRENT_TIMESTAMP,'Adm IT','TRIAL','adm-it',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY'),"
                + "(?,true,CURRENT_TIMESTAMP,'Adm Other','TRIAL','adm-other',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY')",
                SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES "
                + "(?,?,'2025-2026',DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP),"
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),"
                + "(?,?,'2027-2028',DATE '2027-04-01',DATE '2028-03-31',false,CURRENT_TIMESTAMP)",
                PAST, SCHOOL, CURRENT, SCHOOL, FUTURE, SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible,display_order) VALUES (?,?,'9',true,false,9),(?,?,'10',true,false,10)",
                CLASS_9, SCHOOL, CLASS_10, SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active,display_order) VALUES (?,?,?,'A',true,1),(?,?,?,'A',true,1),(?,?,?,'B',true,2)",
                SEC_9A, SCHOOL, CLASS_9, SEC_10A, SCHOOL, CLASS_10, SEC_10B, SCHOOL, CLASS_10);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM attendance_session WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM parent_student_relationship WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM parent_account WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM users WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
    }

    private void as(long school) { when(securityUtil.getSchoolId()).thenReturn(school); }

    private Student admit(String name, String className, Long sectionId, LocalDate joining) {
        return studentService.addStudent(new StudentAdmissionDtos.AdmissionRequest(name, name.replace(' ', '.') + "@test.com",
                null, LocalDate.of(2012, 2, 3), className, sectionId, "F", null, null, false, null, joining), http);
    }

    private List<StudentEnrollment> history(String studentId) {
        return enrollments.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(SCHOOL, studentId);
    }

    private Student reload(String studentId) { return students.findByStudentIdAndSchoolId(studentId, SCHOOL).orElseThrow(); }

    private StudentAdmissionDtos.UpdateRequest edit(Student s, String className, Long sectionId, LocalDate joining) {
        return new StudentAdmissionDtos.UpdateRequest(s.getName(), s.getEmail(), s.getPhoneNumber(), s.getDob(), className,
                sectionId, s.getGender(), s.getFatherName(), s.getMotherName(), s.getTakesBus(), s.getDistance(), joining);
    }

    private void recordAttendance(String studentId, long classId, Long sectionId, LocalDate date) {
        Long sessionId = jdbc.queryForObject("INSERT INTO attendance_session (school_id,academic_session_id,class_id,section_id,attendance_date,"
                + "marked_by_user_id,marked_at,updated_at) VALUES (?,?,?,?,?,'t1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL, CURRENT, classId, sectionId, date);
        jdbc.update("INSERT INTO student_attendance (attendance_session_id,student_id,status,created_at,updated_at) "
                + "VALUES (?,?,'PRESENT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sessionId, studentId);
    }

    private StudentExitRequest exit(String type, LocalDate date) {
        StudentExitRequest r = new StudentExitRequest();
        r.setExitType(type);
        r.setLeavingDate(date);
        r.setReasonForLeaving("Family move");
        return r;
    }

    // ── Admission ──

    @Test
    void admissionCreatesStudentEnrollmentAndLoginTogether() {
        Student s = admit("Asha Rao", "9", SEC_9A, TODAY);

        assertThat(reload(s.getStudentId()).getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
            assertThat(e.getAcademicSessionId()).isEqualTo(CURRENT);
            assertThat(e.getEffectiveFrom()).isEqualTo(TODAY);
        });
        User login = users.findByUserId(s.getStudentId()).orElseThrow();
        assertThat(login.getRole()).isEqualTo("STUDENT");
        assertThat(login.isActive()).isTrue();
        assertThat(login.isMustChangePassword()).isTrue();
        assertThat(login.getPassword()).isEqualTo("enc:20120203");
        verify(welcomeEmailService).sendWelcomeEmail(eq(s.getStudentId()), eq("Asha Rao"), eq("STUDENT"), anyString(), eq(SCHOOL));
    }

    @Test
    void aLoginThatCannotBeCreatedRollsBackTheWholeAdmission() {
        User clash = new User();
        clash.setUserId("P1S-1");
        clash.setRole("STUDENT");
        clash.setSchoolId(SCHOOL);
        users.saveAndFlush(clash);

        assertThatThrownBy(() -> admit("Clash Kid", "9", SEC_9A, TODAY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("login already exists");

        assertThat(students.findByStudentIdAndSchoolId("P1S-1", SCHOOL)).isEmpty();
        assertThat(history("P1S-1")).isEmpty();
        verifyNoInteractions(welcomeEmailService);
    }

    @Test
    void aJoiningDateInAPastSessionIsRejected_currentAndFutureAreAllowed() {
        assertThatThrownBy(() -> admit("Old Kid", "9", SEC_9A, LocalDate.of(2025, 6, 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("past academic session (2025-2026)")
                .hasMessageContaining("Historical admissions");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student WHERE school_id = ?", Integer.class, SCHOOL)).isZero();

        Student earlier = admit("Earlier Kid", "9", SEC_9A, LocalDate.of(2026, 6, 1));
        Student future = admit("Future Kid", "9", SEC_9A, LocalDate.of(2027, 4, 5));
        assertThat(history(earlier.getStudentId()).getFirst().getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
        assertThat(history(future.getStudentId()).getFirst()).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
            assertThat(e.getAcademicSessionId()).isEqualTo(FUTURE);
        });
    }

    // ── Mass assignment / photo ──

    @Test
    void anEditNeverPersistsTheSignedPhotoUrlOrAnyLifecycleField() throws Exception {
        Student s = admit("Photo Kid", "9", SEC_9A, TODAY);
        jdbc.update("UPDATE student SET photo_url = 'schools/-97401/students/p.jpg' WHERE student_id = ?", s.getStudentId());

        // Exactly what an older Web/Android build sends back: the whole GET response, including
        // the short-lived signed photo URL — plus forged lifecycle fields.
        String body = "{\"studentDetails\":{\"studentId\":\"HIJACK\",\"schoolId\":" + OTHER_SCHOOL + ",\"name\":\"Photo Kid Renamed\","
                + "\"email\":\"p@test.com\",\"dob\":\"2012-02-03\",\"className\":\"9\",\"sectionId\":" + SEC_9A + ","
                + "\"joiningDate\":\"" + TODAY + "\",\"status\":\"GRADUATED\",\"leavingDate\":\"2026-09-01\","
                + "\"reasonForLeaving\":\"forged\",\"exitRemarks\":\"forged\",\"conductAtLeaving\":\"forged\","
                + "\"photoUrl\":\"https://storage.example/schools/-97401/students/p.jpg?X-Amz-Signature=abc&X-Amz-Expires=900\"},"
                + "\"effectiveFromMonth\":null}";
        StudentAdmissionDtos.UpdateEnvelope envelope = objectMapper.readValue(body, StudentAdmissionDtos.UpdateEnvelope.class);

        studentService.updateStudent(s.getStudentId(), envelope.studentDetails(), envelope.effectiveFromMonth(), http);

        var row = jdbc.queryForMap("SELECT name, photo_url, status, leaving_date, reason_for_leaving, exit_remarks, conduct_at_leaving, school_id "
                + "FROM student WHERE student_id = ?", s.getStudentId());
        assertThat(row.get("name")).isEqualTo("Photo Kid Renamed");
        assertThat(row.get("photo_url")).isEqualTo("schools/-97401/students/p.jpg");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("leaving_date")).isNull();
        assertThat(row.get("reason_for_leaving")).isNull();
        assertThat(row.get("exit_remarks")).isNull();
        assertThat(row.get("conduct_at_leaving")).isNull();
        assertThat(((Number) row.get("school_id")).longValue()).isEqualTo(SCHOOL);
        assertThat(students.findById("HIJACK")).isEmpty();
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE));
    }

    // ── Upcoming admissions ──

    @Test
    void anUpcomingClassAndSectionEditCorrectsThePlannedEnrollment() {
        Student s = admit("Up Kid", "9", SEC_9A, LocalDate.of(2026, 10, 15));

        studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "10", SEC_10B, null), null, http);

        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
            assertThat(e.getClassId()).isEqualTo(CLASS_10);
            assertThat(e.getSectionId()).isEqualTo(SEC_10B);
        });
        Student after = reload(s.getStudentId());
        assertThat(after.getStatus()).isEqualTo(StudentStatus.UPCOMING);
        assertThat(after.getClassId()).isEqualTo(CLASS_10);
        assertThat(after.getSectionId()).isEqualTo(SEC_10B);
        assertThat(after.getSectionName()).isEqualTo("B");
        verifyNoInteractions(studentFeesService);
    }

    @Test
    void anUpcomingJoiningDateMovesThePlannedEnrollment_evenIntoAnotherSession() {
        Student s = admit("Move Kid", "9", SEC_9A, LocalDate.of(2026, 10, 15));

        studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "9", SEC_9A, LocalDate.of(2027, 4, 12)), null, http);
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
            assertThat(e.getAcademicSessionId()).isEqualTo(FUTURE);
            assertThat(e.getEffectiveFrom()).isEqualTo(LocalDate.of(2027, 4, 12));
        });
        assertThat(reload(s.getStudentId()).getJoiningDate()).isEqualTo(LocalDate.of(2027, 4, 12));

        assertThatThrownBy(() -> studentService.updateStudent(s.getStudentId(),
                edit(reload(s.getStudentId()), "9", SEC_9A, LocalDate.of(2026, 1, 5)), null, http))
                .hasMessageContaining("past academic session");

        // Moved to today: the admission starts now — ACTIVE on both sides, never out of step.
        studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "9", SEC_9A, TODAY), null, http);
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
            assertThat(e.getAcademicSessionId()).isEqualTo(CURRENT);
            assertThat(e.getEffectiveFrom()).isEqualTo(TODAY);
        });
        assertThat(reload(s.getStudentId()).getStatus()).isEqualTo(StudentStatus.ACTIVE);
    }

    @Test
    void cancellingAFutureAdmissionKeepsHistory_blocksActivation_andRevokesAccess() {
        Student s = admit("Cancel Kid", "9", SEC_9A, LocalDate.of(2026, 10, 15));
        jdbc.update("INSERT INTO parent_account (parent_id,school_id,name,phone_number,active) VALUES ('P1P-1',?,'Mum','9000000001',true)", SCHOOL);
        jdbc.update("INSERT INTO parent_student_relationship (school_id,parent_id,student_id,relationship_type,active,effective_from) "
                + "VALUES (?,'P1P-1',?,'MOTHER',true,?)", SCHOOL, s.getStudentId(), TODAY);

        Student cancelled = studentService.cancelAdmission(s.getStudentId(), "Family chose another school", http);

        assertThat(cancelled.getStatus()).isEqualTo(StudentStatus.ADMISSION_CANCELLED);
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.CANCELLED);
            assertThat(e.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CANCELLED_BEFORE_START);
            assertThat(e.getEffectiveUntil()).isEqualTo(e.getEffectiveFrom());
        });
        assertThat(users.findByUserId(s.getStudentId()).orElseThrow().isActive()).isFalse();
        verify(userSessionService).revokeAllForUser(s.getStudentId());
        assertThat(jdbc.queryForObject("SELECT active FROM parent_student_relationship WHERE student_id = ?", Boolean.class, s.getStudentId())).isFalse();

        // On the old joining date the scheduler finds nothing to activate.
        LocalDate joiningDay = LocalDate.of(2026, 10, 15);
        assertThat(enrollmentService.findEligiblePlannedStudentIds(SCHOOL, joiningDay)).doesNotContain(s.getStudentId());
        assertThat(enrollmentService.activateEligiblePlannedEnrollment(SCHOOL, s.getStudentId(), joiningDay).outcome())
                .isEqualTo(StudentEnrollmentService.ScheduledActivationOutcome.NOT_ELIGIBLE);
        assertThat(reload(s.getStudentId()).getStatus()).isEqualTo(StudentStatus.ADMISSION_CANCELLED);

        // Listed with students who left; can't be cancelled twice; an active student can't be "cancelled".
        assertThat(studentService.getLeftStudentsByClass("9")).extracting(Student::getStudentId).contains(s.getStudentId());
        assertThatThrownBy(() -> studentService.cancelAdmission(s.getStudentId(), null, http)).isInstanceOf(IllegalStateException.class);
        Student active = admit("Active Kid", "9", SEC_9A, TODAY);
        assertThatThrownBy(() -> studentService.cancelAdmission(active.getStudentId(), null, http))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("has not started");
    }

    // ── Active corrections ──

    @Test
    void sameDayClassCorrectionIsInPlace_butNotOnceAttendanceExists() {
        Student s = admit("Fix Kid", "9", SEC_9A, TODAY);

        studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "10", SEC_10A, null), null, http);
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> {
            assertThat(e.getClassId()).isEqualTo(CLASS_10);
            assertThat(e.getEffectiveFrom()).isEqualTo(TODAY);
        });

        recordAttendance(s.getStudentId(), CLASS_10, SEC_10A, TODAY);
        assertThatThrownBy(() -> studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "10", SEC_10B, null), null, http))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already recorded today")
                .hasMessageContaining("from tomorrow");
        assertThat(history(s.getStudentId())).singleElement().satisfies(e -> assertThat(e.getSectionId()).isEqualTo(SEC_10A));
    }

    @Test
    void aLaterClassChangeClosesThePreviousPeriod_keepingItsRecords() {
        Student s = admit("Split Kid", "9", SEC_9A, LocalDate.of(2026, 9, 1));
        recordAttendance(s.getStudentId(), CLASS_9, SEC_9A, LocalDate.of(2026, 9, 2));

        studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "10", SEC_10A, null), null, http);

        List<StudentEnrollment> h = history(s.getStudentId());
        assertThat(h).hasSize(2);
        assertThat(h.get(0)).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
            assertThat(e.getClassId()).isEqualTo(CLASS_9);
            assertThat(e.getEffectiveUntil()).isEqualTo(TODAY.minusDays(1));
            assertThat(e.getClosureReason()).isEqualTo(StudentEnrollmentClosureReason.CLASS_CHANGE);
        });
        assertThat(h.get(1)).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
            assertThat(e.getClassId()).isEqualTo(CLASS_10);
            assertThat(e.getEffectiveFrom()).isEqualTo(TODAY);
        });
        assertThat(jdbc.queryForObject("SELECT class_id FROM attendance_session s JOIN student_attendance a ON a.attendance_session_id = s.id "
                + "WHERE a.student_id = ?", Long.class, s.getStudentId())).isEqualTo(CLASS_9);
    }

    @Test
    void anActiveJoiningDateIsCorrectedOnlyWhenDemonstrablySafe() {
        Student clean = admit("Clean Kid", "9", SEC_9A, LocalDate.of(2026, 9, 20));
        studentService.updateStudent(clean.getStudentId(), edit(reload(clean.getStudentId()), "9", SEC_9A, LocalDate.of(2026, 9, 15)), null, http);
        assertThat(history(clean.getStudentId()).getFirst().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(reload(clean.getStudentId()).getJoiningDate()).isEqualTo(LocalDate.of(2026, 9, 15));

        assertThatThrownBy(() -> studentService.updateStudent(clean.getStudentId(),
                edit(reload(clean.getStudentId()), "9", SEC_9A, TODAY.plusDays(3)), null, http))
                .hasMessageContaining("cannot be moved into the future");
        assertThatThrownBy(() -> studentService.updateStudent(clean.getStudentId(),
                edit(reload(clean.getStudentId()), "9", SEC_9A, LocalDate.of(2026, 3, 20)), null, http))
                .hasMessageContaining("must stay inside the 2026-2027 academic session");

        Student attended = admit("Attended Kid", "9", SEC_9A, LocalDate.of(2026, 9, 20));
        recordAttendance(attended.getStudentId(), CLASS_9, SEC_9A, LocalDate.of(2026, 9, 21));
        assertThatThrownBy(() -> studentService.updateStudent(attended.getStudentId(),
                edit(reload(attended.getStudentId()), "9", SEC_9A, LocalDate.of(2026, 9, 18)), null, http))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attendance or marks");
        assertThat(history(attended.getStudentId()).getFirst().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 20));
    }

    @Test
    void aLeftStudentsMembershipCanOnlyChangeThroughReadmission() {
        Student s = admit("Left Kid", "9", SEC_9A, LocalDate.of(2026, 9, 1));
        studentService.exitStudent(s.getStudentId(), exit("WITHDRAWN", TODAY.minusDays(5)), http);

        assertThatThrownBy(() -> studentService.updateStudent(s.getStudentId(), edit(reload(s.getStudentId()), "10", SEC_10A, null), null, http))
                .hasMessageContaining("Use Re-admit");
    }

    // ── Exit login policy + readmission ──

    @Test
    void graduatesKeepTheirLogin_transferAndWithdrawalRevokeIt_readmissionRestoresIt() {
        Student grad = admit("Grad Kid", "10", SEC_10A, LocalDate.of(2026, 9, 1));
        Student moved = admit("Moved Kid", "10", SEC_10A, LocalDate.of(2026, 9, 1));
        Student quit = admit("Quit Kid", "10", SEC_10A, LocalDate.of(2026, 9, 1));

        studentService.exitStudent(grad.getStudentId(), exit("GRADUATED", TODAY), http);
        studentService.exitStudent(moved.getStudentId(), exit("TRANSFERRED", TODAY), http);
        studentService.exitStudent(quit.getStudentId(), exit("WITHDRAWN", TODAY), http);

        assertThat(users.findByUserId(grad.getStudentId()).orElseThrow().isActive()).isTrue();
        assertThat(users.findByUserId(moved.getStudentId()).orElseThrow().isActive()).isFalse();
        assertThat(users.findByUserId(quit.getStudentId()).orElseThrow().isActive()).isFalse();
        verify(userSessionService, never()).revokeAllForUser(grad.getStudentId());
        verify(userSessionService).revokeAllForUser(moved.getStudentId());
        verify(userSessionService).revokeAllForUser(quit.getStudentId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE user_id = ?", Integer.class, moved.getStudentId())).isEqualTo(1);
        assertThatThrownBy(() -> studentService.exitStudent(admit("Odd Kid", "9", SEC_9A, TODAY).getStudentId(),
                exit("ADMISSION_CANCELLED", TODAY), http)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readmissionUsesTheChosenClassSectionAndDate_andNeverAltersOldHistory() {
        Student s = admit("Back Kid", "9", SEC_9A, LocalDate.of(2026, 9, 1));
        studentService.exitStudent(s.getStudentId(), exit("WITHDRAWN", LocalDate.of(2026, 9, 20)), http);
        StudentEnrollment closed = history(s.getStudentId()).getFirst();

        assertThatThrownBy(() -> studentService.readmitStudent(s.getStudentId(),
                new StudentAdmissionDtos.ReadmitRequest(CLASS_10, SEC_10B, LocalDate.of(2026, 9, 18)), http))
                .hasMessageContaining("after the leaving date");
        assertThatThrownBy(() -> studentService.readmitStudent(s.getStudentId(),
                new StudentAdmissionDtos.ReadmitRequest(CLASS_10, SEC_10B, TODAY.plusDays(1)), http))
                .hasMessageContaining("cannot be in the future");

        Student back = studentService.readmitStudent(s.getStudentId(),
                new StudentAdmissionDtos.ReadmitRequest(CLASS_10, SEC_10B, LocalDate.of(2026, 9, 25)), http);

        assertThat(back.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(back.getClassId()).isEqualTo(CLASS_10);
        assertThat(back.getSectionId()).isEqualTo(SEC_10B);
        assertThat(users.findByUserId(s.getStudentId()).orElseThrow().isActive()).isTrue();
        List<StudentEnrollment> h = history(s.getStudentId());
        assertThat(h).hasSize(2);
        StudentEnrollment old = h.stream().filter(e -> e.getId().equals(closed.getId())).findFirst().orElseThrow();
        assertThat(old.getStatus()).isEqualTo(StudentEnrollmentStatus.CLOSED);
        assertThat(old.getClassId()).isEqualTo(CLASS_9);
        assertThat(old.getEffectiveUntil()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(old.getUpdatedAt()).isEqualTo(closed.getUpdatedAt());

        List<StudentAdmissionDtos.EnrollmentHistoryItem> view = studentService.enrollmentHistory(s.getStudentId());
        assertThat(view).extracting(StudentAdmissionDtos.EnrollmentHistoryItem::state).containsExactly("CLOSED", "CURRENT");
        assertThat(view.get(0).sessionLabel()).isEqualTo("2026-2027");
        assertThat(view.get(0).closureReason()).isEqualTo(StudentEnrollmentClosureReason.WITHDRAWN);
        assertThat(view.get(1).className()).isEqualTo("10");
        assertThat(view.get(1).sectionName()).isEqualTo("B");
        assertThat(view.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026, 9, 25));
    }

    @Test
    void parentLinksEndedByTheExitAreOfferedAfterReadmission_andRestoredOnlyWhenConfirmed() {
        Student s = admit("Family Kid", "9", SEC_9A, LocalDate.of(2026, 9, 1));
        jdbc.update("INSERT INTO parent_account (parent_id,school_id,name,phone_number,active) VALUES ('P1P-1',?,'Mum','9000000001',true),"
                + "('P1P-2',?,'Removed Uncle','9000000002',true)", SCHOOL, SCHOOL);
        jdbc.update("INSERT INTO parent_student_relationship (school_id,parent_id,student_id,relationship_type,active,effective_from,effective_until) "
                + "VALUES (?,'P1P-1',?,'MOTHER',true,DATE '2026-09-01',NULL),(?,'P1P-2',?,'GUARDIAN',false,DATE '2026-09-01',DATE '2026-09-10')",
                SCHOOL, s.getStudentId(), SCHOOL, s.getStudentId());
        studentService.exitStudent(s.getStudentId(), exit("TRANSFERRED", LocalDate.of(2026, 9, 20)), http);
        assertThat(studentService.restorableParentLinks(s.getStudentId())).isEmpty();   // still exited

        studentService.readmitStudent(s.getStudentId(), new StudentAdmissionDtos.ReadmitRequest(CLASS_9, SEC_9A, TODAY), http);
        // Readmission itself restores nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parent_student_relationship WHERE student_id = ? AND active", Integer.class, s.getStudentId())).isZero();

        List<StudentAdmissionDtos.RestorableParentLink> offered = studentService.restorableParentLinks(s.getStudentId());
        assertThat(offered).singleElement().satisfies(l -> {
            assertThat(l.parentName()).isEqualTo("Mum");
            assertThat(l.endedOn()).isEqualTo(LocalDate.of(2026, 9, 20));
        });
        Long removedUncle = jdbc.queryForObject("SELECT id FROM parent_student_relationship WHERE parent_id = 'P1P-2'", Long.class);
        assertThatThrownBy(() -> studentService.restoreParentLinks(s.getStudentId(), List.of(removedUncle), http))
                .hasMessageContaining("can't be restored");

        assertThat(studentService.restoreParentLinks(s.getStudentId(), List.of(offered.getFirst().relationshipId()), http)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active FROM parent_student_relationship WHERE parent_id = 'P1P-1'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM parent_student_relationship WHERE parent_id = 'P1P-2'", Boolean.class)).isFalse();
    }

    // ── Missing login ──

    @Test
    void createMissingLoginIsRefusedOnceALoginExists() {
        jdbc.update("INSERT INTO student (student_id,school_id,name,email,dob,status,class_id,class_name,section_id,section_name,joining_date) "
                + "VALUES ('P1S-OLD',?,'Half Made','h@test.com',DATE '2011-01-09','ACTIVE',?,'9',?,'A',DATE '2026-09-01')", SCHOOL, CLASS_9, SEC_9A);
        assertThat(studentService.loginStatus("P1S-OLD").exists()).isFalse();

        studentService.createMissingLogin("P1S-OLD", http);

        assertThat(users.findByUserId("P1S-OLD").orElseThrow().getPassword()).isEqualTo("enc:20110109");
        assertThat(studentService.loginStatus("P1S-OLD")).isEqualTo(new StudentAdmissionDtos.LoginStatus(true, true));
        assertThatThrownBy(() -> studentService.createMissingLogin("P1S-OLD", http))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE user_id = 'P1S-OLD'", Integer.class)).isEqualTo(1);
        verify(welcomeEmailService, times(1)).sendWelcomeEmail(eq("P1S-OLD"), any(), any(), any(), any());

        jdbc.update("INSERT INTO student (student_id,school_id,name,dob,status,class_name,joining_date) "
                + "VALUES ('P1S-GONE',?,'Gone','2011-01-09','WITHDRAWN','9',DATE '2026-09-01')", SCHOOL);
        assertThatThrownBy(() -> studentService.createMissingLogin("P1S-GONE", http)).hasMessageContaining("WITHDRAWN");
    }

    // ── Tenancy ──

    @Test
    void anotherSchoolCannotReadOrActOnThisSchoolsStudent() {
        Student s = admit("Mine Kid", "9", SEC_9A, LocalDate.of(2026, 10, 15));
        as(OTHER_SCHOOL);
        assertThatThrownBy(() -> studentService.enrollmentHistory(s.getStudentId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> studentService.loginStatus(s.getStudentId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> studentService.createMissingLogin(s.getStudentId(), http)).hasMessageContaining("not found");
        assertThatThrownBy(() -> studentService.cancelAdmission(s.getStudentId(), null, http)).hasMessageContaining("not found");
        as(SCHOOL);
        assertThat(reload(s.getStudentId()).getStatus()).isEqualTo(StudentStatus.UPCOMING);
    }
}
