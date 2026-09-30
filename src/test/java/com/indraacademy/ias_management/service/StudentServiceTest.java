package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.dto.StudentExitRequest;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentMarkRepository;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * SchoolClass is meant to be the single source of truth for which classes exist for a
 * school. These tests lock in that invariant for student create/update: a student can
 * never be saved against a free-text className with no matching SchoolClass row, and a
 * supplied sectionId must belong to both the resolved school and the resolved class.
 * This is the fix for the E2E audit finding where fee rules and a student were created
 * against class "10" with zero SchoolClass rows ever having been configured.
 */
@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private StudentFeesService studentFeesService;
    @Mock private SchoolRepository schoolRepository;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private EntitlementService entitlementService;
    @Mock private StudentAttendanceRepository studentAttendanceRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private LeaveRepository leaveRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private IdGeneratorService idGeneratorService;
    @Mock private AcademicSessionRepository academicSessionRepository;
    @Mock private StudentEnrollmentService studentEnrollmentService;
    @Mock private ParentPortalService parentPortalService;
    @Mock private StudentLoginService studentLoginService;
    @Mock private StudentMarkRepository studentMarkRepository;
    @Mock private StudentEnrollmentRepository studentEnrollmentRepository;
    @Mock private HttpServletRequest request;

    private StudentService service;

    private static final Long SCHOOL_ID = 1L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    @BeforeEach
    void setUp() {
        service = new StudentService();
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "studentFeesService", studentFeesService);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "userDetailsService", userDetailsService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "entitlementService", entitlementService);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "studentAttendanceRepository", studentAttendanceRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "leaveRepository", leaveRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "idGeneratorService", idGeneratorService);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        ReflectionTestUtils.setField(service, "studentEnrollmentService", studentEnrollmentService);
        ReflectionTestUtils.setField(service, "parentPortalService", parentPortalService);
        ReflectionTestUtils.setField(service, "studentLoginService", studentLoginService);
        ReflectionTestUtils.setField(service, "studentMarkRepository", studentMarkRepository);
        ReflectionTestUtils.setField(service, "studentEnrollmentRepository", studentEnrollmentRepository);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-06T06:00:00Z"), ZoneOffset.UTC));

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(studentRepository.saveAndFlush(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        School school = new School(); school.setId(SCHOOL_ID); school.setTimezone("Asia/Kolkata");
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        AcademicSession academicSession = new AcademicSession();
        academicSession.setId(50L); academicSession.setSchoolId(SCHOOL_ID);
        academicSession.setStartDate(LocalDate.of(2026, 4, 1));
        academicSession.setEndDate(LocalDate.of(2027, 3, 31));
        lenient().when(academicSessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(academicSession));
    }

    private SchoolClass schoolClass(Long id, Long schoolId, String name) {
        SchoolClass sc = new SchoolClass();
        sc.setId(id);
        sc.setSchoolId(schoolId);
        sc.setName(name);
        sc.setActive(true);
        return sc;
    }

    private Section section(Long id, Long schoolId, Long classId, String name) {
        Section s = new Section();
        s.setId(id);
        s.setSchoolId(schoolId);
        s.setClassId(classId);
        s.setName(name);
        s.setActive(true);
        return s;
    }

    private Student newStudent(String studentId, String className, Long sectionId) {
        Student s = new Student();
        s.setStudentId(studentId);
        s.setName("Test Student");
        s.setClassName(className);
        s.setSectionId(sectionId);
        s.setJoiningDate(TODAY);
        s.setDob(LocalDate.of(2010, 5, 15));
        return s;
    }

    private void stubNewStudentId(String studentId) {
        when(studentRepository.findByStudentIdAndSchoolId(studentId, SCHOOL_ID)).thenReturn(Optional.empty());
        when(studentRepository.existsById(studentId)).thenReturn(false);
    }

    // ─── addStudent ─────────────────────────────────────────────────────────

    @Test
    void studentCreationWithValidConfiguredClassSucceeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");

        Student saved = service.addStudent(newStudent("S1", "10", null), request);

        assertThat(saved.getClassId()).isEqualTo(10L);
        assertThat(saved.getClassName()).isEqualTo("10");
        verify(studentRepository).saveAndFlush(any(Student.class));
        verify(studentEnrollmentService).createActiveEnrollment(
                SCHOOL_ID, "S1", 50L, 10L, null, TODAY);
    }

    @Test
    void nonexistentClassIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");

        verify(studentRepository, never()).save(any());
    }

    @Test
    void classBelongingToAnotherSchoolIsRejected() {
        // findBySchoolIdAndName is itself scoped by schoolId, so a class row that exists
        // only under a different school never matches here — proves the lookup can't be
        // satisfied by a same-named class belonging to someone else's school.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void validSectionClassCombinationSucceeds() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(sectionRepository.findByIdAndSchoolId(100L, SCHOOL_ID))
                .thenReturn(Optional.of(section(100L, SCHOOL_ID, 10L, "10-A")));
        stubNewStudentId("S1");

        Student saved = service.addStudent(newStudent("S1", "10", 100L), request);

        assertThat(saved.getSectionId()).isEqualTo(100L);
        assertThat(saved.getSectionName()).isEqualTo("10-A");
    }

    @Test
    void sectionBelongingToAnotherClassIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        // Section 200 genuinely exists for this school, but under class 99, not 10.
        when(sectionRepository.findByIdAndSchoolId(200L, SCHOOL_ID))
                .thenReturn(Optional.of(section(200L, SCHOOL_ID, 99L, "9-A")));
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", 200L), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to class");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void sectionBelongingToAnotherSchoolIsRejected() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        // findByIdAndSchoolId is scoped by schoolId — a section belonging to a different
        // school never resolves here, regardless of whether the id itself exists.
        when(sectionRepository.findByIdAndSchoolId(300L, SCHOOL_ID)).thenReturn(Optional.empty());
        stubNewStudentId("S1");

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", 300L), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Section not found");
        verify(studentRepository, never()).save(any());
    }

    @Test
    void studentCreationWithoutDobIsRejected() {
        // The DOB check fails before any repository lookup is reached — no stubs needed.
        Student noDob = newStudent("S1", "10", null);
        noDob.setDob(null);

        assertThatThrownBy(() -> service.addStudent(noDob, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date of birth is required");

        verify(studentRepository, never()).save(any());
    }

    // ─── System-generated Student ID ───────────────────────────────────────

    @Test
    void newStudentWithNoIdSuppliedGetsAGeneratedId() {
        when(idGeneratorService.generateStudentId()).thenReturn("stu_26010042");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        Student blankId = newStudent(null, "10", null);
        when(studentRepository.findByStudentIdAndSchoolId("stu_26010042", SCHOOL_ID)).thenReturn(Optional.empty());
        when(studentRepository.existsById("stu_26010042")).thenReturn(false);

        Student saved = service.addStudent(blankId, request);

        assertThat(saved.getStudentId()).isEqualTo("stu_26010042");
        verify(idGeneratorService).generateStudentId();
    }

    @Test
    void existingSuppliedStudentIdIsNeverOverwritten_backwardCompatibility() {
        // Any caller that already supplies a non-blank studentId — including, hypothetically,
        // any future internal path re-using this method for a pre-existing legacy ID — keeps
        // that exact ID untouched. The generator must never fire in that case.
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("EMP123_LEGACY");

        Student saved = service.addStudent(newStudent("EMP123_LEGACY", "10", null), request);

        assertThat(saved.getStudentId()).isEqualTo("EMP123_LEGACY");
        verify(idGeneratorService, never()).generateStudentId();
    }

    @Test
    void futureAdmissionCreatesPlannedEnrollmentFromContainingSession() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");
        Student future = newStudent("S1", "10", null);
        future.setJoiningDate(TODAY.plusDays(10));

        Student saved = service.addStudent(future, request);

        assertThat(saved.getStatus()).isEqualTo(StudentStatus.UPCOMING);
        verify(studentEnrollmentService).createPlannedEnrollment(
                SCHOOL_ID, "S1", 50L, 10L, null, TODAY.plusDays(10));
        verify(studentEnrollmentService, never()).createActiveEnrollment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void admissionStatusUsesSchoolLocalDateRatherThanUtcDate() {
        School school = new School(); school.setId(SCHOOL_ID); school.setTimezone("America/Los_Angeles");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-09-06T01:00:00Z"), ZoneOffset.UTC));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");

        Student saved = service.addStudent(newStudent("S1", "10", null), request);

        assertThat(saved.getStatus()).isEqualTo(StudentStatus.UPCOMING);
        verify(studentEnrollmentService).createPlannedEnrollment(
                SCHOOL_ID, "S1", 50L, 10L, null, TODAY);
    }

    @Test
    void admissionWithoutCoveringSessionFailsBeforeStudentSave() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");
        when(academicSessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(SCHOOL_ID, TODAY, TODAY))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No configured academic session");
        verify(studentRepository, never()).saveAndFlush(any());
        verify(studentEnrollmentService, never()).createActiveEnrollment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void enrollmentFailurePropagatesSoTransactionalStudentInsertRollsBack() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");
        doThrow(new IllegalStateException("enrollment failed")).when(studentEnrollmentService)
                .createActiveEnrollment(SCHOOL_ID, "S1", 50L, 10L, null, TODAY);

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("enrollment failed");
        verify(studentRepository).saveAndFlush(any(Student.class));
    }

    @Test
    void studentInsertFailureNeverAttemptsEnrollment() {
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        stubNewStudentId("S1");
        when(studentRepository.saveAndFlush(any(Student.class))).thenThrow(new RuntimeException("insert failed"));

        assertThatThrownBy(() -> service.addStudent(newStudent("S1", "10", null), request))
                .isInstanceOf(RuntimeException.class);
        verify(studentEnrollmentService, never()).createActiveEnrollment(any(), any(), any(), any(), any(), any());
        verify(studentEnrollmentService, never()).createPlannedEnrollment(any(), any(), any(), any(), any(), any());
    }

    // ─── updateStudent ──────────────────────────────────────────────────────

    @Test
    void updateStudentWithValidConfiguredClassSucceeds() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID);
        existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));

        Student updated = service.updateStudent("S1", newStudent("S1", "10", null), null, request);

        assertThat(updated.getClassId()).isEqualTo(10L);
        assertThat(updated.getClassName()).isEqualTo("10");
    }

    @Test
    void updateStudentRejectsNonexistentClass() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID);
        existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStudent("S1", newStudent("S1", "99", null), null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured for this school");
        verify(studentRepository, never()).save(any());
        // The class-change fee side effect must not fire for an invalid target class either.
        verify(studentFeesService, never()).updateStudentFeesForClassChange(any(), any());
    }

    @Test
    void coveredUnchangedMembershipAndNonMembershipEditAreEnrollmentNeutral() {
        Student existing = newStudent("S1", "10", 100L);
        existing.setSchoolId(SCHOOL_ID); existing.setClassId(10L); existing.setSectionName("A");
        existing.setEmail("old@test.com"); existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(sectionRepository.findByIdAndSchoolId(100L, SCHOOL_ID))
                .thenReturn(Optional.of(section(100L, SCHOOL_ID, 10L, "A")));
        when(studentEnrollmentService.findEffectiveEnrollment(SCHOOL_ID, "S1", 50L, TODAY))
                .thenReturn(Optional.of(enrollment(10L, 100L, "10", "A")));
        Student update = newStudent("S1", "10", 100L); update.setEmail("new@test.com");

        service.updateStudent("S1", update, null, request);

        verify(studentEnrollmentService, never()).transitionClass(any(), any(), any(), any(), any(), any());
        verify(studentEnrollmentService, never()).transitionSection(any(), any(), any(), any(), any());
        verify(studentFeesService, never()).updateStudentFeesForClassChange(any(), any());
    }

    @Test
    void coveredCurrentClassChangeUsesEnrollmentAndDoesNotMutateFees() {
        Student existing = newStudent("S1", "9", 90L);
        existing.setSchoolId(SCHOOL_ID); existing.setClassId(9L); existing.setSectionName("A"); existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(sectionRepository.findByIdAndSchoolId(100L, SCHOOL_ID))
                .thenReturn(Optional.of(section(100L, SCHOOL_ID, 10L, "B")));
        when(studentEnrollmentService.findEffectiveEnrollment(SCHOOL_ID, "S1", 50L, TODAY))
                .thenReturn(Optional.of(enrollment(9L, 90L, "9", "A")));
        StudentEnrollment replacement = enrollment(10L, 100L, "10", "B");
        when(studentEnrollmentService.transitionClass(SCHOOL_ID, "S1", 50L, 10L, 100L, TODAY))
                .thenReturn(new StudentEnrollmentService.EnrollmentTransition(null, replacement));

        Student saved = service.updateStudent("S1", newStudent("S1", "10", 100L), null, request);

        assertThat(saved.getClassId()).isEqualTo(10L);
        assertThat(saved.getSectionId()).isEqualTo(100L);
        verify(studentFeesService, never()).updateStudentFeesForClassChange(any(), any());
    }

    @Test
    void coveredCurrentSectionChangeUsesEnrollmentReplacementProjection() {
        Student existing = newStudent("S1", "10", 100L);
        existing.setSchoolId(SCHOOL_ID); existing.setClassId(10L); existing.setSectionName("A"); existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(sectionRepository.findByIdAndSchoolId(101L, SCHOOL_ID))
                .thenReturn(Optional.of(section(101L, SCHOOL_ID, 10L, "B")));
        when(studentEnrollmentService.findEffectiveEnrollment(SCHOOL_ID, "S1", 50L, TODAY))
                .thenReturn(Optional.of(enrollment(10L, 100L, "10", "A")));
        StudentEnrollment replacement = enrollment(10L, 101L, "10", "B");
        when(studentEnrollmentService.transitionSection(SCHOOL_ID, "S1", 50L, 101L, TODAY))
                .thenReturn(new StudentEnrollmentService.EnrollmentTransition(null, replacement));

        Student saved = service.updateStudent("S1", newStudent("S1", "10", 101L), null, request);

        assertThat(saved.getSectionId()).isEqualTo(101L);
        assertThat(saved.getSectionName()).isEqualTo("B");
        verify(studentEnrollmentService).transitionSection(SCHOOL_ID, "S1", 50L, 101L, TODAY);
    }

    @Test
    void invalidCoveredTransitionDoesNotSaveDetachedStudentOrTouchFees() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID); existing.setClassId(9L); existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10"))
                .thenReturn(Optional.of(schoolClass(10L, SCHOOL_ID, "10")));
        when(studentEnrollmentService.findEffectiveEnrollment(SCHOOL_ID, "S1", 50L, TODAY))
                .thenReturn(Optional.of(enrollment(9L, null, "9", null)));
        when(studentEnrollmentService.transitionClass(SCHOOL_ID, "S1", 50L, 10L, null, TODAY))
                .thenThrow(new IllegalArgumentException("invalid transition"));

        assertThatThrownBy(() -> service.updateStudent("S1", newStudent("S1", "10", null), null, request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid transition");
        verify(studentRepository, never()).save(any(Student.class));
        verify(studentFeesService, never()).updateStudentFeesForClassChange(any(), any());
    }

    @Test
    void anActiveJoiningDateIsNotCorrectedOnceMarksExist() {
        Student existing = newStudent("S1", "9", null);
        existing.setSchoolId(SCHOOL_ID); existing.setClassId(9L); existing.setStatus(StudentStatus.ACTIVE);
        existing.setJoiningDate(TODAY.minusDays(1)); existing.markAsExisting();
        when(studentRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "9"))
                .thenReturn(Optional.of(schoolClass(9L, SCHOOL_ID, "9")));
        when(studentEnrollmentService.findEffectiveEnrollment(SCHOOL_ID, "S1", 50L, TODAY))
                .thenReturn(Optional.of(enrollment(9L, null, "9", null)));
        com.indraacademy.ias_management.entity.StudentMark mark = new com.indraacademy.ias_management.entity.StudentMark();
        when(studentMarkRepository.findByStudentIdAndSchoolId("S1", SCHOOL_ID)).thenReturn(java.util.List.of(mark));
        Student update = newStudent("S1", "9", null);
        update.setJoiningDate(TODAY.minusDays(5));

        assertThatThrownBy(() -> service.updateStudent("S1", update, null, request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attendance or marks");
        verify(studentEnrollmentService, never()).correctActiveStartDate(any(), any(), any());
        verify(studentRepository, never()).save(any(Student.class));
    }

    private StudentEnrollment enrollment(Long classId, Long sectionId, String className, String sectionName) {
        StudentEnrollment e = new StudentEnrollment();
        e.setSchoolId(SCHOOL_ID); e.setStudentId("S1"); e.setAcademicSessionId(50L);
        e.setClassId(classId); e.setClassNameSnapshot(className);
        e.setSectionId(sectionId); e.setSectionNameSnapshot(sectionName);
        e.setStatus(StudentEnrollmentStatus.ACTIVE); e.setEffectiveFrom(TODAY.minusDays(1));
        return e;
    }

    @Test
    void explicitExitUsesEnrollmentLifecycleAndPreservesExistingSideEffects() {
        Student student = newStudent("S1", "9", null);
        student.setSchoolId(SCHOOL_ID); student.setClassId(9L); student.setStatus(StudentStatus.ACTIVE);
        StudentExitRequest exit = new StudentExitRequest();
        exit.setExitType("WITHDRAWN"); exit.setLeavingDate(TODAY); exit.setReasonForLeaving("Relocation");
        when(studentEnrollmentService.closeForExplicitExit(
                SCHOOL_ID,"S1",50L,TODAY,
                com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason.WITHDRAWN))
                .thenReturn(new StudentEnrollmentService.LifecycleMutation(student,enrollment(9L,null,"9",null),false,null));

        Student saved=service.exitStudent("S1",exit,request);

        assertThat(saved.getStatus()).isEqualTo(StudentStatus.WITHDRAWN);
        assertThat(saved.getClassId()).isEqualTo(9L);
        verify(parentPortalService).endRelationshipsForExitedStudent(SCHOOL_ID,"S1",TODAY);
        verifyNoInteractions(studentFeesService,studentFeesRepository,paymentRepository,leaveRepository,userRepository);
    }

    @Test
    void explicitReadmissionUsesTodayAndPreservedProjection() {
        Student student = newStudent("S1", "9", 90L);
        student.setSchoolId(SCHOOL_ID); student.setClassId(9L); student.setStatus(StudentStatus.WITHDRAWN);
        when(studentRepository.findByStudentIdAndSchoolId("S1",SCHOOL_ID)).thenReturn(Optional.of(student));
        when(studentEnrollmentService.createForExplicitReadmission(SCHOOL_ID,"S1",50L,9L,90L,TODAY))
                .thenReturn(new StudentEnrollmentService.LifecycleMutation(student,enrollment(9L,90L,"9","A"),false,null));

        Student saved=service.readmitStudent("S1",request);

        assertThat(saved.getStatus()).isEqualTo(StudentStatus.ACTIVE);
        assertThat(saved.getLeavingDate()).isNull();
        verifyNoInteractions(studentFeesService,studentFeesRepository,paymentRepository,leaveRepository,userRepository);
    }

    @Test
    void correctPlannedEnrollmentDelegatesToEnrollmentServiceAndAudits() {
        StudentEnrollment corrected = enrollment(10L, 31L, "10", "B");
        when(studentEnrollmentService.correctPlannedEnrollment(SCHOOL_ID, "S1", 200L, 10L, 31L))
                .thenReturn(corrected);

        StudentEnrollment result = service.correctPlannedEnrollment("S1", 200L, 10L, 31L, request);

        assertThat(result).isSameAs(corrected);
        verify(auditService).log(eq("admin"), eq("ADMIN"), eq("CORRECT_PLANNED_ENROLLMENT"),
                eq("StudentEnrollment"), eq("200"), isNull(), anyString(), any());
        verifyNoInteractions(studentFeesService,studentFeesRepository,paymentRepository,leaveRepository,userRepository);
    }
}
