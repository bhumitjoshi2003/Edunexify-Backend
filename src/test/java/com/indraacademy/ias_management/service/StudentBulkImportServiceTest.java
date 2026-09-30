package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;

/**
 * Date of birth is the sole source of a bulk-imported student's initial login
 * password (yyyyMMdd) — these tests lock in that a row missing it is rejected
 * with a clear, row-level error (not silently defaulted to the studentId, the
 * old fallback), while the partial-success import model is preserved: other
 * valid rows in the same file still succeed.
 *
 * <p>studentService.addStudent is mocked here (this is a unit test of the CSV parsing/
 * orchestration layer, not of ID generation itself — see IdGeneratorServiceTest and
 * StudentServiceTest for that) but its stub simulates the real method's actual behavior:
 * assigning a generated studentId to whatever Student it receives, since that assignment
 * now happens inside StudentService.addStudent(), not in this parser.
 */
@ExtendWith(MockitoExtension.class)
class StudentBulkImportServiceTest {

    @Mock private StudentService studentService;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private HttpServletRequest request;

    private StudentBulkImportService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new StudentBulkImportService();
        ReflectionTestUtils.setField(service, "studentService", studentService);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(schoolClassRepository.findBySchoolIdAndName(any(), anyString())).thenReturn(Optional.empty());
        AtomicInteger seq = new AtomicInteger(1);
        lenient().when(studentService.addStudent(any(Student.class), any())).thenAnswer(inv -> {
            Student s = inv.getArgument(0);
            s.setStudentId(String.format("stu_260100%02d", seq.getAndIncrement()));
            return s;
        });
    }

    /** New-format CSV — no ID column at all, matching the current (post-generation)
     *  TEMPLATE_HEADERS and the header-name-based parser. */
    private MockMultipartFile csv(String... dataRows) {
        StringBuilder sb = new StringBuilder(String.join(",", StudentBulkImportService.TEMPLATE_HEADERS)).append("\n");
        for (String row : dataRows) {
            sb.append(row).append("\n");
        }
        return new MockMultipartFile("file", "students.csv", "text/csv",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rowMissingDobIsRejectedWithClearMessage_otherRowsStillSucceed() {
        MockMultipartFile file = csv(
                "Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,",
                "No Dob Student,s2@test.com,,,10,,,,,,,2024-01-01,"
        );

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getTotalRows()).isEqualTo(2);
        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getReason())
                .isEqualTo("Date of birth is required because it is used as the initial password.");
        assertThat(result.getErrors().get(0).getStudentId()).isEqualTo("No Dob Student");
    }

    @Test
    void loginIsCreatedByTheCanonicalAdmissionNotSeparately() {
        // StudentService.addStudent creates student + enrollment + login in one transaction;
        // the importer only delegates, so there is no second, non-atomic account step.
        BulkImportResultDTO result = service.bulkImport(csv("Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01"), request);

        assertThat(result.getSuccessful()).isEqualTo(1);
        verify(studentService).addStudent(any(Student.class), org.mockito.Mockito.eq(request));
    }

    @Test
    void generatedStudentIdIsReportedInSuccessfulImportResult() {
        MockMultipartFile file = csv("Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,");

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getCreated()).hasSize(1);
        assertThat(result.getCreated().get(0).getName()).isEqualTo("Valid Student");
        assertThat(result.getCreated().get(0).getGeneratedId()).startsWith("stu_26");
        assertThat(result.getNotice()).isNull();
    }

    @Test
    void legacyCsvWithStudentIdColumnIsAcceptedButIgnored_noticeExplainsWhy() {
        // An old CSV still has "Student ID" as the first column. It must not error, and the
        // value in that column must never become the generated account's ID.
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv",
                ("Student ID,Student Name,Email,Phone Number,Date of Birth,Class,Section,Gender,Father Name,Mother Name,Takes Bus,Distance (km),Joining Date,Leaving Date\n"
                        + "LEGACY_ID_123,Valid Student,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,\n")
                        .getBytes(StandardCharsets.UTF_8));

        BulkImportResultDTO result = service.bulkImport(file, request);

        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getCreated().get(0).getGeneratedId()).isNotEqualTo("LEGACY_ID_123");
        assertThat(result.getCreated().get(0).getGeneratedId()).startsWith("stu_26");
        assertThat(result.getNotice()).contains("Student ID").contains("generates the account ID automatically");
    }

    @Test
    void activeAndUpcomingRowsDelegateToCanonicalCreationWithJoiningDatesIntact() {
        MockMultipartFile file = csv(
                "Active Student,a@test.com,,1990-05-23,10,,,,,,,2026-09-06,",
                "Upcoming Student,u@test.com,,1990-05-24,10,,,,,,,2026-10-01,");

        service.bulkImport(file, request);

        ArgumentCaptor<Student> students = ArgumentCaptor.forClass(Student.class);
        verify(studentService, org.mockito.Mockito.times(2)).addStudent(students.capture(), org.mockito.Mockito.eq(request));
        assertThat(students.getAllValues()).extracting(Student::getJoiningDate)
                .containsExactly(LocalDate.of(2026, 9, 6), LocalDate.of(2026, 10, 1));
    }

    @Test
    void canonicalCreationFailureIsRowScoped() {
        doThrow(new IllegalArgumentException("No configured academic session contains joining date"))
                .when(studentService).addStudent(any(Student.class), any());

        BulkImportResultDTO result = service.bulkImport(
                csv("Bad Session,s1@test.com,,1990-05-23,10,,,,,,,2035-01-01"), request);

        assertThat(result.getSuccessful()).isZero();
        assertThat(result.getFailed()).isEqualTo(1);
    }

    @Test
    void aLoginFailureRejectsOnlyThatRow_otherRowsStillImport() {
        AtomicInteger seq = new AtomicInteger(1);
        when(studentService.addStudent(any(Student.class), any())).thenAnswer(inv -> {
            Student s = inv.getArgument(0);
            if (s.getName().equals("Login Clash")) {
                // Thrown inside the admission transaction, which then rolls the whole row back.
                throw new IllegalStateException("A login already exists for student stu_x.");
            }
            s.setStudentId("stu_ok" + seq.getAndIncrement());
            return s;
        });

        BulkImportResultDTO result = service.bulkImport(csv(
                "Login Clash,a@test.com,,1990-05-23,10,,,,,,,2024-01-01",
                "Fine Student,b@test.com,,1991-05-23,10,,,,,,,2024-01-01"), request);

        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0).getReason()).contains("login already exists");
        assertThat(result.getErrors().get(0).getRow()).isEqualTo(2);
    }

    @Test
    void anUnknownSectionIsARowErrorAndIsNeverSilentlyDropped() {
        SchoolClass ten = new SchoolClass();
        ten.setId(10L);
        ten.setName("10");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(ten));
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, 10L, "Z")).thenReturn(Optional.empty());

        BulkImportResultDTO result = service.bulkImport(
                csv("Section Typo,s1@test.com,,1990-05-23,10,Z,,,,,,2024-01-01"), request);

        assertThat(result.getSuccessful()).isZero();
        assertThat(result.getErrors().get(0).getReason()).isEqualTo("Section 'Z' does not exist for class '10'");
        verify(studentService, never()).addStudent(any(Student.class), any());
    }

    @Test
    void aKnownSectionIsAssigned() {
        SchoolClass ten = new SchoolClass();
        ten.setId(10L);
        ten.setName("10");
        Section a = new Section();
        a.setId(101L);
        a.setName("A");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(ten));
        when(sectionRepository.findBySchoolIdAndClassIdAndName(SCHOOL_ID, 10L, "A")).thenReturn(Optional.of(a));

        service.bulkImport(csv("Has Section,s1@test.com,,1990-05-23,10,A,,,,,,2024-01-01"), request);

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentService).addStudent(captor.capture(), any());
        assertThat(captor.getValue().getSectionId()).isEqualTo(101L);
    }

    @Test
    void aLeavingDateIsRejectedForANewAdmission() {
        MockMultipartFile legacyTemplate = new MockMultipartFile("file", "students.csv", "text/csv",
                ("Student Name,Email,Phone Number,Date of Birth,Class,Section,Gender,Father Name,Mother Name,Takes Bus,Distance (km),Joining Date,Leaving Date\n"
                        + "Already Left,s1@test.com,,1990-05-23,10,,,,,,,2024-01-01,2024-06-01\n"
                        + "No Exit,s2@test.com,,1991-05-23,10,,,,,,,2024-01-01,\n")
                        .getBytes(StandardCharsets.UTF_8));

        BulkImportResultDTO result = service.bulkImport(legacyTemplate, request);

        assertThat(result.getSuccessful()).isEqualTo(1);
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getRow()).isEqualTo(2);
            assertThat(e.getReason()).contains("Leaving Date must be empty");
        });
    }

    @Test
    void theTemplateNoLongerOffersALeavingDateColumn() {
        assertThat(StudentBulkImportService.TEMPLATE_HEADERS).doesNotContain("Leaving Date");
    }

    @Test
    void aSecondRowWithTheSameNameAndDobInOneFileIsRejected() {
        BulkImportResultDTO result = service.bulkImport(csv(
                "Asha Rao,a@test.com,,2012-02-03,10,,,,,,,2024-01-01",
                "Ravi Kumar,r@test.com,,2012-02-03,10,,,,,,,2024-01-01",
                "  asha rao ,other@test.com,,2012-02-03,10,,,,,,,2024-01-01"), request);

        assertThat(result.getSuccessful()).isEqualTo(2);
        assertThat(result.getErrors()).singleElement().satisfies(e -> {
            assertThat(e.getRow()).isEqualTo(4);
            assertThat(e.getReason()).isEqualTo("Duplicate of row 2 (same name and date of birth) — not imported");
        });
        verify(studentService, org.mockito.Mockito.times(2)).addStudent(any(Student.class), any());
    }
}
