package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.StudentExitRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Year-end TRANSFER for one student, in its own transaction (like
 * {@link StudentYearEndWorker}). The effective exit date is always the source session's last day:
 * <ul>
 *   <li>On or after that day, the exit is applied at once through the existing
 *       {@link StudentService#exitStudent} workflow (enrollment closure, status, parent access,
 *       audit) with the session end as leaving date.</li>
 *   <li>Before it, the decision is recorded the same way a PASS_OUT is: the source enrollment is
 *       closed at the session end with the TRANSFERRED reason (so it stays effective
 *       until then), while the student stays ACTIVE with parent access. The nightly
 *       {@code StudentStatusScheduler} then finalizes it on the date via
 *       {@link StudentYearEndService#finalizeYearEndExit}.</li>
 * </ul>
 * The normal Student Details exit is unchanged and still rejects future dates.
 */
@Service
public class StudentYearEndExitWorker {

    private final StudentRepository students;
    private final StudentEnrollmentRepository enrollments;
    private final AcademicSessionRepository sessions;
    private final SchoolRepository schools;
    private final StudentService studentService;
    private final AuditService auditService;
    private final com.indraacademy.ias_management.util.SecurityUtil security;
    private final Clock clock;

    public StudentYearEndExitWorker(StudentRepository students, StudentEnrollmentRepository enrollments,
                                    AcademicSessionRepository sessions, SchoolRepository schools,
                                    StudentService studentService, AuditService auditService,
                                    com.indraacademy.ias_management.util.SecurityUtil security, Clock clock) {
        this.students = students;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.schools = schools;
        this.studentService = studentService;
        this.auditService = auditService;
        this.security = security;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PromotionResultDTO.StudentOutcome apply(Long schoolId, Long sourceSessionId,
                                                   PromotionDecisionRequest.Decision decision,
                                                   HttpServletRequest httpRequest) {
        StudentYearEndDecision.Action action = decision.getAction();
        if (action != StudentYearEndDecision.Action.TRANSFER) {
            throw new IllegalArgumentException("Only TRANSFER can be applied as a year-end exit");
        }
        StudentStatus exitStatus = StudentStatus.TRANSFERRED;
        StudentEnrollmentClosureReason closure = StudentEnrollmentClosureReason.valueOf(exitStatus.name());
        String studentId = decision.getStudentId();

        School school = schools.findById(schoolId).orElseThrow(() -> new NoSuchElementException("School not found"));
        Student student = students.findByStudentIdAndSchoolIdForUpdate(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        AcademicSession sourceSession = sessions.findByIdAndSchoolId(sourceSessionId, schoolId).orElse(null);
        List<StudentEnrollment> history = enrollments.findAllHistoryForUpdate(schoolId, studentId);
        StudentEnrollment source = history.stream()
                .filter(e -> Objects.equals(e.getId(), decision.getExpectedSourceEnrollmentId()))
                .filter(e -> Objects.equals(e.getAcademicSessionId(), sourceSessionId))
                .findFirst().orElse(null);
        if (sourceSession == null || source == null
                || !Objects.equals(source.getClassId(), decision.getExpectedSourceClassId())) {
            return outcome(studentId, StudentYearEndDecision.Outcome.INVALID_SOURCE.name(),
                    "Expected source enrollment/session/class does not match authoritative history",
                    source == null ? null : source.getId());
        }
        if (source.getStatus() == StudentEnrollmentStatus.CLOSED) {
            boolean scheduled = student.getStatus() == StudentStatus.ACTIVE
                    && Objects.equals(source.getEffectiveUntil(), sourceSession.getEndDate());
            boolean same = source.getClosureReason() == closure && (student.getStatus() == exitStatus || scheduled);
            return new PromotionResultDTO.StudentOutcome(studentId,
                    same ? StudentYearEndDecision.Outcome.ALREADY_APPLIED.name() : StudentYearEndDecision.Outcome.CONFLICT.name(),
                    same ? (scheduled ? "This exit is already recorded — effective " + source.getEffectiveUntil()
                                      : "This exit is already recorded")
                         : "A different year-end decision is already recorded",
                    source.getId(), null, null, same && scheduled);
        }
        if (source.getStatus() != StudentEnrollmentStatus.ACTIVE || source.getEffectiveUntil() != null) {
            return outcome(studentId, StudentYearEndDecision.Outcome.INVALID_SOURCE.name(),
                    "Source enrollment must be open and ACTIVE", source.getId());
        }
        if (student.getStatus() != StudentStatus.ACTIVE) {
            return outcome(studentId, StudentYearEndDecision.Outcome.CONFLICT.name(),
                    "Student is no longer ACTIVE (" + student.getStatus() + ")", source.getId());
        }

        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        LocalDate effective = sourceSession.getEndDate();
        if (decision.getLeavingDate() != null && !decision.getLeavingDate().equals(effective)) {
            throw new IllegalArgumentException("In year-end rollover the exit takes effect at the end of "
                    + sourceSession.getLabel() + " (" + effective + "); a different leaving date can't be chosen");
        }
        String reason = decision.getReason() == null ? "" : decision.getReason().trim();
        if (reason.isEmpty()) {
            reason = "Transferred at year end";
        }
        String verb = "Transfer";

        if (!today.isBefore(effective)) {
            // The session has ended: apply now through the normal exit workflow, as of the session end.
            StudentExitRequest exit = new StudentExitRequest();
            exit.setExitType(exitStatus.name());
            exit.setReasonForLeaving(reason);
            exit.setLeavingDate(effective);
            studentService.exitStudent(studentId, exit, httpRequest);
            return outcome(studentId, exitStatus.name(), verb + " recorded as of " + effective, source.getId());
        }

        // Before the session ends: record now, effective on the session's last day.
        boolean laterSegment = history.stream()
                .filter(e -> !Objects.equals(e.getId(), source.getId()))
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .anyMatch(e -> e.getEffectiveFrom().isAfter(source.getEffectiveFrom()));
        if (laterSegment) {
            return outcome(studentId, StudentYearEndDecision.Outcome.CONFLICT.name(),
                    "Student already has a later enrollment", source.getId());
        }
        source.setStatus(StudentEnrollmentStatus.CLOSED);
        source.setEffectiveUntil(effective);
        source.setClosureReason(closure);
        enrollments.saveAndFlush(source);
        // Kept on the student for the record; status, leaving date and parent access change only
        // when the exit becomes effective.
        student.setReasonForLeaving(reason);
        students.saveAndFlush(student);
        auditService.log(security.getUsername(), security.getRole(), "YEAR_END_" + action.name() + "_SCHEDULED", "Student", studentId,
                "ACTIVE", exitStatus.name() + " effective " + effective, httpRequest == null ? null : httpRequest.getRemoteAddr());
        return new PromotionResultDTO.StudentOutcome(studentId, exitStatus.name(),
                verb + " scheduled — the student stays active until " + effective, source.getId(), null, null, true);
    }

    private static PromotionResultDTO.StudentOutcome outcome(String studentId, String code, String message, Long sourceId) {
        return new PromotionResultDTO.StudentOutcome(studentId, code, message, sourceId, null, null, false);
    }
}
