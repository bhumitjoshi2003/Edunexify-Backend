package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers updateLeaveStatus's hardening: the locked read and the same-status guard.
 *
 * <p>The rule under test is deliberately narrower than "a decided leave is final" — the product
 * has a real, existing reversal feature (APPROVED ↔ REJECTED, ViewLeavesComponent.editLeaveStatus)
 * that this must not break. What's blocked is only a request whose target already matches the
 * leave's current status — a repeat, not a reversal.
 */
@ExtendWith(MockitoExtension.class)
class LeaveServiceTest {

    @Mock private LeaveRepository leaveRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private BusinessNotificationService businessNotifications;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private TeacherRepository teacherRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private HttpServletRequest request;

    private LeaveService service;

    private static final Long SCHOOL_ID = 2L;
    private static final Long LEAVE_ID = 1L;
    private static final Long CLASS_12_ID = 12L;
    private static final Long SCIENCE_ID = 100L;
    private static final Long COMMERCE_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new LeaveService();
        ReflectionTestUtils.setField(service, "leaveRepository", leaveRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules());

        TeacherClassScopeService scopeService = new TeacherClassScopeService();
        ReflectionTestUtils.setField(scopeService, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(scopeService, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(scopeService, "sectionRepository", sectionRepository);
        ReflectionTestUtils.setField(service, "teacherClassScopeService", scopeService);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(securityUtil.getUsername()).thenReturn("tester");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
    }

    private Leave leave(LeaveStatus status) {
        Leave l = new Leave();
        l.setId(LEAVE_ID);
        l.setSchoolId(SCHOOL_ID);
        l.setStudentId("S1");
        l.setStudentName("Test Student");
        l.setLeaveDate("2026-08-20");
        l.setClassName("10");
        l.setReason("Test reason");
        l.setStatus(status);
        return l;
    }

    @Test
    void pendingToApproved_succeeds_theOrdinaryFirstDecision() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        Leave updated = service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        verify(businessNotifications).studentAndParents(eq(SCHOOL_ID), eq("S1"),
                eq(com.indraacademy.ias_management.notification.NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS),
                eq(com.indraacademy.ias_management.notification.NotificationEventCode.LEAVE_APPROVED),
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anySet());
    }

    @Test
    void pendingToRejected_succeeds_theOrdinaryFirstDecision() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        Leave updated = service.updateLeaveStatus(LEAVE_ID, LeaveStatus.REJECTED, request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.REJECTED);
    }

    /** The double-click / retried-request / "two people approved the same leave" case. */
    @Test
    void reapprovingAnAlreadyApprovedLeave_isRejected_asARepeat() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(InvalidLeaveStatusTransitionException.class)
                .hasMessageContaining("already")
                .hasMessageContaining("APPROVED");

        verify(leaveRepository, never()).save(any());
        verifyNoInteractions(businessNotifications);
    }

    @Test
    void rerejectingAnAlreadyRejectedLeave_isRejected_asARepeat() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.REJECTED)));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.REJECTED, request))
                .isInstanceOf(InvalidLeaveStatusTransitionException.class);

        verify(leaveRepository, never()).save(any());
    }

    /** The existing product feature (ViewLeavesComponent.editLeaveStatus) — must keep working. */
    @Test
    void approvedToRejected_isAnExplicitReversal_notASecondDecision() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.APPROVED)));
        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.REJECTED, request))
                .isInstanceOf(InvalidLeaveStatusTransitionException.class).hasMessageContaining("Change decision");
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        Leave updated = service.reverse(LEAVE_ID, "Medical note was not genuine", request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.REJECTED);
        assertThat(updated.getDecisionReason()).isEqualTo("Medical note was not genuine");
        assertThat(updated.getDecidedBy()).isEqualTo("tester");
    }

    @Test
    void rejectedToApproved_isAnExplicitReversal_withARequiredReason() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.REJECTED)));
        assertThatThrownBy(() -> service.reverse(LEAVE_ID, " ", request)).isInstanceOf(IllegalArgumentException.class);
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        Leave updated = service.reverse(LEAVE_ID, "Parent brought the certificate", request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    @Test
    void usesTheLockedFinder_notThePlainFindById() {
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(leave(LeaveStatus.PENDING)));
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request);

        verify(leaveRepository).findByIdForUpdate(LEAVE_ID);
        verify(leaveRepository, never()).findById(any());
    }

    @Test
    void tenantMismatch_stillRejectedBeforeTheStatusCheck_unaffectedByThisChange() {
        Leave otherSchoolLeave = leave(LeaveStatus.PENDING);
        otherSchoolLeave.setSchoolId(999L);
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(otherSchoolLeave));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(SecurityException.class);

        verify(leaveRepository, never()).save(any());
    }

    // ─── TEACHER class+section scoping — this endpoint previously had NO such check at all ───

    private void givenClass12HasScienceAndCommerce() {
        SchoolClass c = new SchoolClass();
        c.setId(CLASS_12_ID);
        c.setName("12");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "12")).thenReturn(Optional.of(c));
        Section science = new Section();
        science.setId(SCIENCE_ID); science.setName("Science"); science.setActive(true);
        Section commerce = new Section();
        commerce.setId(COMMERCE_ID); commerce.setName("Commerce"); commerce.setActive(true);
        when(sectionRepository.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(SCHOOL_ID, CLASS_12_ID, true))
                .thenReturn(java.util.List.of(science, commerce));
    }

    private Teacher teacherWithScope(String id, String classTeacher, Long sectionId) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setClassTeacher(classTeacher);
        t.setClassTeacherSectionId(sectionId);
        return t;
    }

    private Leave leaveForClass12(String studentId) {
        Leave l = leave(LeaveStatus.PENDING);
        l.setClassName("12");
        l.setStudentId(studentId);
        return l;
    }

    @Test
    void teacherA_canApprove_ownScienceStudentsLeave() {
        givenClass12HasScienceAndCommerce();
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("TeacherA");
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacherWithScope("TeacherA", "12", SCIENCE_ID)));
        Leave l = leaveForClass12("S-SCI");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(l));
        when(leaveRepository.save(any(Leave.class))).thenAnswer(inv -> inv.getArgument(0));

        Leave updated = service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request);

        assertThat(updated.getStatus()).isEqualTo(LeaveStatus.APPROVED);
    }

    @Test
    void teacherA_cannotApprove_commerceStudentsLeave_evenSameClass() {
        givenClass12HasScienceAndCommerce();
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("TeacherA");
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherA", SCHOOL_ID))
                .thenReturn(Optional.of(teacherWithScope("TeacherA", "12", SCIENCE_ID)));
        Leave l = leaveForClass12("S-COM");
        when(studentRepository.findByStudentIdAndSchoolId("S-COM", SCHOOL_ID))
                .thenReturn(Optional.of(commerceStudent("S-COM")));
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(l));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(SecurityException.class);

        verify(leaveRepository, never()).save(any());
    }

    @Test
    void teacherB_cannotApprove_scienceStudentsLeave() {
        givenClass12HasScienceAndCommerce();
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("TeacherB");
        when(teacherRepository.findByTeacherIdAndSchoolId("TeacherB", SCHOOL_ID))
                .thenReturn(Optional.of(teacherWithScope("TeacherB", "12", COMMERCE_ID)));
        Leave l = leaveForClass12("S-SCI");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(l));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void legacyAmbiguousTeacher_cannotApproveAnyLeave_forClassWithSections() {
        givenClass12HasScienceAndCommerce();
        when(securityUtil.getRole()).thenReturn("TEACHER");
        when(securityUtil.getUsername()).thenReturn("Legacy");
        when(teacherRepository.findByTeacherIdAndSchoolId("Legacy", SCHOOL_ID))
                .thenReturn(Optional.of(teacherWithScope("Legacy", "12", null)));
        Leave l = leaveForClass12("S-SCI");
        when(studentRepository.findByStudentIdAndSchoolId("S-SCI", SCHOOL_ID))
                .thenReturn(Optional.of(scienceStudent("S-SCI")));
        when(leaveRepository.findByIdForUpdate(LEAVE_ID)).thenReturn(Optional.of(l));

        assertThatThrownBy(() -> service.updateLeaveStatus(LEAVE_ID, LeaveStatus.APPROVED, request))
                .isInstanceOf(SecurityException.class)
                .hasMessage(TeacherClassScopeService.SECTION_REQUIRED_MESSAGE);
    }

    private Student scienceStudent(String id) {
        Student s = new Student();
        s.setStudentId(id);
        s.setClassName("12");
        s.setSectionId(SCIENCE_ID);
        return s;
    }

    private Student commerceStudent(String id) {
        Student s = new Student();
        s.setStudentId(id);
        s.setClassName("12");
        s.setSectionId(COMMERCE_ID);
        return s;
    }
}
