package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.ClockConfig;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentService.class, StudentEnrollmentService.class, ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentAdmissionPostgresIT {
    private static final long SCHOOL = -97001, SESSION = -97002, CLASS_ID = -97003;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    @Autowired StudentService service;
    @Autowired StudentRepository students;
    @Autowired StudentEnrollmentRepository enrollments;
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

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @BeforeEach
    void fixtures() throws Exception {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'Admission IT','TRIAL','admission-it',4,8,'Asia/Kolkata')", SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?, '2026-2027',DATE '2026-04-01',DATE '2027-03-31',false,CURRENT_TIMESTAMP)", SESSION, SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'10',true,false)", CLASS_ID, SCHOOL);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        when(securityUtil.getUsername()).thenReturn("admin");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    void activeAdmissionPersistsStudentAndEnrollmentWithoutFinancialRows() {
        Student saved = service.addStudent(student("PG-ACTIVE", TODAY), request);

        assertThat(saved.getStatus().name()).isEqualTo("ACTIVE");
        assertThat(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL, "PG-ACTIVE", SESSION)).singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.ACTIVE);
                    assertThat(e.getEffectiveFrom()).isEqualTo(TODAY);
                    assertThat(e.getClassId()).isEqualTo(CLASS_ID);
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=?",
                Integer.class, SCHOOL, "PG-ACTIVE")).isZero();
    }

    @Test
    void upcomingAdmissionPersistsPlannedEnrollmentAndKeepsCompatibilityProjection() {
        LocalDate joining = TODAY.plusDays(10);
        Student saved = service.addStudent(student("PG-UPCOMING", joining), request);

        assertThat(saved.getStatus().name()).isEqualTo("UPCOMING");
        assertThat(saved.getClassId()).isEqualTo(CLASS_ID);
        assertThat(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL, "PG-UPCOMING", SESSION)).singleElement()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(StudentEnrollmentStatus.PLANNED);
                    assertThat(e.getEffectiveFrom()).isEqualTo(joining);
                });
    }

    @Test
    void enrollmentInsertFailureRollsBackStudentInsert() {
        jdbc.execute("CREATE FUNCTION phase_c_reject_enrollment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic enrollment failure'; END $$");
        jdbc.execute("CREATE TRIGGER phase_c_reject_enrollment BEFORE INSERT ON student_enrollment FOR EACH ROW EXECUTE FUNCTION phase_c_reject_enrollment()");
        // Commit only these negative-ID synthetic fixtures so addStudent opens its real,
        // independent production transaction. PostgreSQL aborts the failed transaction;
        // the assertions therefore run afterward in a clean transaction.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            assertThatThrownBy(() -> service.addStudent(student("PG-ROLLBACK", TODAY), request))
                    .isInstanceOf(RuntimeException.class);
            assertThat(students.findByStudentIdAndSchoolId("PG-ROLLBACK", SCHOOL)).isEmpty();
            assertThat(enrollments.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                    SCHOOL, "PG-ROLLBACK", SESSION)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS phase_c_reject_enrollment ON student_enrollment");
            jdbc.execute("DROP FUNCTION IF EXISTS phase_c_reject_enrollment()");
            jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
            jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
            jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
            jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
            jdbc.update("DELETE FROM school WHERE id=?", SCHOOL);
        }
    }

    private Student student(String id, LocalDate joiningDate) {
        Student student = new Student();
        student.setStudentId(id);
        student.setName("Postgres Fixture");
        student.setEmail("fixture@example.com");
        student.setDob(LocalDate.of(2010, 1, 1));
        student.setClassName("10");
        student.setJoiningDate(joiningDate);
        return student;
    }
}
