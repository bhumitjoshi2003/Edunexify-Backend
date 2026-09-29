package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import static com.indraacademy.ias_management.service.StudentYearEndDecision.*;

/** Applies one authoritative, session-bound year-end decision atomically. */
@Service
public class StudentYearEndService {
    private static final String COMPLETION_REASON = "Completed final year";

    private final SchoolRepository schools;
    private final StudentRepository students;
    private final AcademicSessionRepository sessions;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final StudentEnrollmentRepository enrollments;
    private final ParentPortalService parentPortal;
    private final AuditService auditService;
    private final Clock clock;

    public StudentYearEndService(
            SchoolRepository schools, StudentRepository students,
            AcademicSessionRepository sessions, SchoolClassRepository classes,
            SectionRepository sections, StudentEnrollmentRepository enrollments,
            ParentPortalService parentPortal, AuditService auditService, Clock clock) {
        this.schools = schools;
        this.students = students;
        this.sessions = sessions;
        this.classes = classes;
        this.sections = sections;
        this.enrollments = enrollments;
        this.parentPortal = parentPortal;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public Result apply(Request request) {
        requireRequest(request);
        School school = schools.findById(request.schoolId())
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        Student student = students.findByStudentIdAndSchoolIdForUpdate(request.studentId(), request.schoolId())
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        List<StudentEnrollment> history = enrollments.findAllHistoryForUpdate(
                request.schoolId(), request.studentId());
        if (history.isEmpty()) {
            return result(Outcome.INVALID_SOURCE, "Student has no authoritative enrollment history", null, null, false);
        }

        AcademicSession sourceSession = sessions.findByIdAndSchoolId(
                        request.sourceSessionId(), request.schoolId())
                .orElse(null);
        StudentEnrollment source = history.stream()
                .filter(e -> Objects.equals(e.getId(), request.expectedSourceEnrollmentId()))
                .filter(e -> Objects.equals(e.getAcademicSessionId(), request.sourceSessionId()))
                .findFirst().orElse(null);
        if (sourceSession == null || source == null
                || !Objects.equals(source.getClassId(), request.expectedSourceClassId())) {
            return result(Outcome.INVALID_SOURCE,
                    "Expected source enrollment/session/class does not match authoritative history",
                    source == null ? null : source.getId(), null, false);
        }

        if (source.getStatus() == StudentEnrollmentStatus.CLOSED) {
            return classifyRepeatedDecision(request, student, sourceSession, source, history);
        }
        if (source.getStatus() != StudentEnrollmentStatus.ACTIVE || source.getEffectiveUntil() != null
                || source.getEffectiveFrom().isAfter(sourceSession.getEndDate())) {
            return result(Outcome.INVALID_SOURCE, "Source enrollment must be open and ACTIVE",
                    source.getId(), null, false);
        }
        if (student.getStatus() != StudentStatus.ACTIVE) {
            return result(Outcome.INVALID_SOURCE, "Student must be ACTIVE for a year-end decision",
                    source.getId(), null, false);
        }

        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        return request.action() == Action.PASS_OUT
                ? applyPassOut(request, student, sourceSession, source, today)
                : applyContinuing(request, student, sourceSession, source, history, today);
    }

    /** E3 scheduler seam: finalizes an already-recorded graduation once its date is effective. */
    @Transactional
    public Result finalizeGraduation(Long schoolId, String studentId, Long sourceSessionId,
                                     Long sourceEnrollmentId, AuditContext auditContext) {
        if (schoolId == null || studentId == null || studentId.isBlank()
                || sourceSessionId == null || sourceEnrollmentId == null) {
            throw new IllegalArgumentException("schoolId, studentId, sourceSessionId and sourceEnrollmentId are required");
        }
        School school = schools.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        Student student = students.findByStudentIdAndSchoolIdForUpdate(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        AcademicSession sourceSession = sessions.findByIdAndSchoolId(sourceSessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Source session not found for school"));
        List<StudentEnrollment> history = enrollments.findAllHistoryForUpdate(schoolId, studentId);
        StudentEnrollment source = history.stream()
                .filter(e -> Objects.equals(e.getId(), sourceEnrollmentId))
                .filter(e -> Objects.equals(e.getAcademicSessionId(), sourceSessionId))
                .findFirst().orElse(null);
        if (source == null || source.getStatus() != StudentEnrollmentStatus.CLOSED
                || source.getClosureReason() != StudentEnrollmentClosureReason.GRADUATED
                || !Objects.equals(source.getEffectiveUntil(), sourceSession.getEndDate())) {
            return result(Outcome.INVALID_SOURCE, "No recorded year-end graduation decision exists",
                    sourceEnrollmentId, null, false);
        }
        // A readmission (or any other later membership segment) recorded after this
        // graduation was scheduled means the graduation no longer reflects reality —
        // finalizing it here would silently reverse the readmission (flip the student back
        // to GRADUATED, reset leavingDate, cut off parents) out from under an ACTIVE
        // enrollment. Reject rather than blindly finalizing a stale decision.
        boolean laterSegmentExists = history.stream()
                .filter(e -> !Objects.equals(e.getId(), source.getId()))
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .anyMatch(e -> e.getEffectiveFrom().isAfter(source.getEffectiveUntil()));
        if (laterSegmentExists) {
            return result(Outcome.CONFLICT,
                    "Student has a later enrollment segment (e.g. a readmission) — this scheduled graduation is stale",
                    source.getId(), null, false);
        }
        if (student.getStatus() == StudentStatus.GRADUATED
                && Objects.equals(student.getLeavingDate(), sourceSession.getEndDate())) {
            return result(Outcome.ALREADY_APPLIED, "Graduation is already finalized",
                    source.getId(), null, false);
        }
        if (student.getStatus() != StudentStatus.ACTIVE) {
            return result(Outcome.CONFLICT,
                    "Student lifecycle changed after graduation was scheduled",
                    source.getId(), null, false);
        }
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        if (today.isBefore(sourceSession.getEndDate())) {
            return result(Outcome.ALREADY_APPLIED, "Graduation decision is recorded and awaits its effective date",
                    source.getId(), null, true);
        }
        finalizeStudentGraduation(student, sourceSession.getEndDate());
        parentPortal.endRelationshipsForExitedStudent(schoolId, studentId, sourceSession.getEndDate());
        audit(auditContext, "FINALIZE_YEAR_END_GRADUATION", studentId,
                "ACTIVE", "GRADUATED");
        return result(Outcome.PASSED_OUT, "Graduation finalized", source.getId(), null, false);
    }

    /**
     * Scheduler seam for a year-end TRANSFER recorded before its source session ended
     * (see {@link StudentYearEndExitWorker}): once the session end date is reached, the student's
     * status becomes TRANSFERRED with that leaving date and parent access ends — the
     * same effects as the normal exit, whose enrollment closure was already recorded. Idempotent.
     */
    @Transactional
    public Result finalizeYearEndExit(Long schoolId, String studentId, Long sourceSessionId,
                                      Long sourceEnrollmentId, AuditContext auditContext) {
        if (schoolId == null || studentId == null || studentId.isBlank()
                || sourceSessionId == null || sourceEnrollmentId == null) {
            throw new IllegalArgumentException("schoolId, studentId, sourceSessionId and sourceEnrollmentId are required");
        }
        School school = schools.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        Student student = students.findByStudentIdAndSchoolIdForUpdate(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        AcademicSession sourceSession = sessions.findByIdAndSchoolId(sourceSessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Source session not found for school"));
        List<StudentEnrollment> history = enrollments.findAllHistoryForUpdate(schoolId, studentId);
        StudentEnrollment source = history.stream()
                .filter(e -> Objects.equals(e.getId(), sourceEnrollmentId))
                .filter(e -> Objects.equals(e.getAcademicSessionId(), sourceSessionId))
                .findFirst().orElse(null);
        if (source == null || source.getStatus() != StudentEnrollmentStatus.CLOSED
                || source.getClosureReason() != StudentEnrollmentClosureReason.TRANSFERRED
                || !Objects.equals(source.getEffectiveUntil(), sourceSession.getEndDate())) {
            return result(Outcome.INVALID_SOURCE, "No recorded year-end transfer exists",
                    sourceEnrollmentId, null, false);
        }
        StudentStatus exitStatus = StudentStatus.TRANSFERRED;
        boolean laterSegmentExists = history.stream()
                .filter(e -> !Objects.equals(e.getId(), source.getId()))
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .anyMatch(e -> e.getEffectiveFrom().isAfter(source.getEffectiveUntil()));
        if (laterSegmentExists) {
            return result(Outcome.CONFLICT,
                    "Student has a later enrollment segment — this scheduled exit is stale",
                    source.getId(), null, false);
        }
        if (student.getStatus() == exitStatus && Objects.equals(student.getLeavingDate(), source.getEffectiveUntil())) {
            return result(Outcome.ALREADY_APPLIED, "Exit is already finalized", source.getId(), null, false);
        }
        if (student.getStatus() != StudentStatus.ACTIVE) {
            return result(Outcome.CONFLICT, "Student lifecycle changed after the exit was scheduled",
                    source.getId(), null, false);
        }
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        if (today.isBefore(source.getEffectiveUntil())) {
            return result(Outcome.ALREADY_APPLIED, "Exit is recorded and awaits its effective date",
                    source.getId(), null, true);
        }
        student.setStatus(exitStatus);
        student.setLeavingDate(source.getEffectiveUntil());
        if (student.getReasonForLeaving() == null || student.getReasonForLeaving().isBlank()) {
            student.setReasonForLeaving("Transferred at year end");
        }
        students.saveAndFlush(student);
        parentPortal.endRelationshipsForExitedStudent(schoolId, studentId, source.getEffectiveUntil());
        audit(auditContext, "FINALIZE_YEAR_END_" + exitStatus.name(), studentId, "ACTIVE", exitStatus.name());
        return result(Outcome.TRANSFERRED, "Transfer finalized", source.getId(), null, false);
    }

    private Result applyContinuing(Request request, Student student, AcademicSession sourceSession,
                                   StudentEnrollment source, List<StudentEnrollment> history,
                                   LocalDate today) {
        AcademicSession targetSession = requireContiguousTarget(request, sourceSession);
        if (today.isAfter(targetSession.getEndDate())) {
            throw new IllegalStateException("Year-end decision cannot be applied after the target session ends");
        }
        if (history.stream().anyMatch(e -> Objects.equals(e.getAcademicSessionId(), targetSession.getId()))) {
            return result(Outcome.CONFLICT, "Target-session enrollment already exists",
                    source.getId(), null, false);
        }

        List<SchoolClass> sequence = activeClassSequence(request.schoolId());
        SchoolClass sourceClass = sequence.stream()
                .filter(c -> Objects.equals(c.getId(), source.getClassId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Source class is not in the active school class sequence"));
        SchoolClass targetClass;
        if (request.action() == Action.PROMOTE) {
            int sourceIndex = sequence.indexOf(sourceClass);
            if (sourceIndex < 0 || sourceIndex == sequence.size() - 1) {
                throw new IllegalStateException("Source class has no configured immediate successor");
            }
            targetClass = sequence.get(sourceIndex + 1);
            if (!Objects.equals(targetClass.getId(), request.targetClassId())) {
                throw new IllegalArgumentException("Target class must be the immediate configured successor");
            }
        } else {
            targetClass = sourceClass;
            if (!Objects.equals(sourceClass.getId(), request.targetClassId())) {
                throw new IllegalArgumentException("DETAIN target class must equal the source class");
            }
        }

        Section targetSection = resolveTargetSection(request, source, targetClass);
        source.setStatus(StudentEnrollmentStatus.CLOSED);
        source.setEffectiveUntil(sourceSession.getEndDate());
        source.setClosureReason(StudentEnrollmentClosureReason.SESSION_COMPLETED);
        enrollments.saveAndFlush(source);

        StudentEnrollmentStatus targetStatus = today.isBefore(targetSession.getStartDate())
                ? StudentEnrollmentStatus.PLANNED : StudentEnrollmentStatus.ACTIVE;
        StudentEnrollment target = new StudentEnrollment();
        target.setSchoolId(request.schoolId());
        target.setStudentId(request.studentId());
        target.setAcademicSessionId(targetSession.getId());
        target.setClassId(targetClass.getId());
        target.setClassNameSnapshot(targetClass.getName());
        if (targetSection != null) {
            target.setSectionId(targetSection.getId());
            target.setSectionNameSnapshot(targetSection.getName());
        }
        target.setStatus(targetStatus);
        target.setEffectiveFrom(targetSession.getStartDate());
        target = enrollments.saveAndFlush(target);

        if (targetStatus == StudentEnrollmentStatus.ACTIVE) {
            synchronizeProjection(student, target);
            students.saveAndFlush(student);
        }
        Outcome outcome = request.action() == Action.PROMOTE ? Outcome.PROMOTED : Outcome.DETAINED;
        audit(request.auditContext(), "YEAR_END_" + request.action(), request.studentId(),
                sourceClass.getName(), targetClass.getName());
        return new Result(outcome, "Year-end decision applied", source.getId(), target.getId(),
                targetStatus, false);
    }

    private Result applyPassOut(Request request, Student student, AcademicSession sourceSession,
                                StudentEnrollment source, LocalDate today) {
        if (request.targetSessionId() != null || request.targetClassId() != null
                || request.targetSectionId() != null) {
            throw new IllegalArgumentException("PASS_OUT must not specify target session/class/section");
        }
        List<SchoolClass> sequence = activeClassSequence(request.schoolId());
        if (sequence.isEmpty() || !Objects.equals(sequence.getLast().getId(), source.getClassId())) {
            throw new IllegalStateException("PASS_OUT is allowed only from the final configured class");
        }
        source.setStatus(StudentEnrollmentStatus.CLOSED);
        source.setEffectiveUntil(sourceSession.getEndDate());
        source.setClosureReason(StudentEnrollmentClosureReason.GRADUATED);
        enrollments.saveAndFlush(source);

        boolean pending = today.isBefore(sourceSession.getEndDate());
        if (!pending) {
            finalizeStudentGraduation(student, sourceSession.getEndDate());
            parentPortal.endRelationshipsForExitedStudent(
                    request.schoolId(), request.studentId(), sourceSession.getEndDate());
        }
        audit(request.auditContext(), "YEAR_END_PASS_OUT", request.studentId(),
                "ACTIVE", pending ? "GRADUATION_SCHEDULED" : "GRADUATED");
        return result(Outcome.PASSED_OUT, pending ? "Graduation scheduled" : "Graduation finalized",
                source.getId(), null, pending);
    }

    private Result classifyRepeatedDecision(Request request, Student student, AcademicSession sourceSession,
                                            StudentEnrollment source, List<StudentEnrollment> history) {
        if (!Objects.equals(source.getEffectiveUntil(), sourceSession.getEndDate())) {
            return result(Outcome.CONFLICT, "Source enrollment was closed by another lifecycle operation",
                    source.getId(), null, false);
        }
        if (request.action() == Action.PASS_OUT) {
            boolean exact = source.getClosureReason() == StudentEnrollmentClosureReason.GRADUATED
                    && request.targetSessionId() == null && request.targetClassId() == null
                    && request.targetSectionId() == null;
            return result(exact ? Outcome.ALREADY_APPLIED : Outcome.CONFLICT,
                    exact ? "Graduation decision is already recorded" : "A different year-end decision is already recorded",
                    source.getId(), null, exact && student.getStatus() != StudentStatus.GRADUATED);
        }
        if (source.getClosureReason() != StudentEnrollmentClosureReason.SESSION_COMPLETED
                || request.targetSessionId() == null) {
            return result(Outcome.CONFLICT, "A different year-end decision is already recorded",
                    source.getId(), null, false);
        }
        AcademicSession targetSession = requireContiguousTarget(request, sourceSession);
        Optional<StudentEnrollment> target = history.stream()
                .filter(e -> Objects.equals(e.getAcademicSessionId(), request.targetSessionId()))
                .filter(e -> Objects.equals(e.getEffectiveFrom(), targetSession.getStartDate()))
                .findFirst();
        Long expectedSectionId = request.action() == Action.DETAIN && request.targetSectionId() == null
                ? source.getSectionId() : request.targetSectionId();
        boolean exact = target.isPresent()
                && Objects.equals(target.get().getClassId(), request.targetClassId())
                && Objects.equals(target.get().getSectionId(), expectedSectionId);
        if (exact && request.action() == Action.DETAIN) {
            exact = Objects.equals(target.get().getClassId(), source.getClassId());
        } else if (exact && request.action() == Action.PROMOTE) {
            exact = !Objects.equals(target.get().getClassId(), source.getClassId());
        }
        return new Result(exact ? Outcome.ALREADY_APPLIED : Outcome.CONFLICT,
                exact ? "Year-end decision is already recorded" : "A different year-end decision is already recorded",
                source.getId(), target.map(StudentEnrollment::getId).orElse(null),
                target.map(StudentEnrollment::getStatus).orElse(null), false);
    }

    private AcademicSession requireContiguousTarget(Request request, AcademicSession source) {
        if (request.targetSessionId() == null) {
            throw new IllegalArgumentException("Target session is required for PROMOTE/DETAIN");
        }
        if (Objects.equals(source.getId(), request.targetSessionId())) {
            throw new IllegalArgumentException("Source and target sessions must differ");
        }
        AcademicSession target = sessions.findByIdAndSchoolId(request.targetSessionId(), request.schoolId())
                .orElseThrow(() -> new NoSuchElementException("Target session not found for school"));
        if (!target.getStartDate().equals(source.getEndDate().plusDays(1))) {
            throw new IllegalArgumentException("Target session must start the day after the source session ends");
        }
        return target;
    }

    private Section resolveTargetSection(Request request, StudentEnrollment source, SchoolClass targetClass) {
        List<Section> configured = sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(
                request.schoolId(), targetClass.getId(), true);
        if (configured.isEmpty()) {
            if (request.targetSectionId() != null) {
                throw new IllegalArgumentException("Target section must be null when the target class has no sections");
            }
            return null;
        }
        Long selectedId = request.targetSectionId();
        if (request.action() == Action.DETAIN && selectedId == null) {
            selectedId = source.getSectionId();
        }
        final Long requestedId = selectedId;
        if (requestedId == null) {
            throw new IllegalArgumentException("An explicit target section is required");
        }
        return configured.stream().filter(s -> Objects.equals(s.getId(), requestedId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target section is inactive, belongs to another class, or belongs to another school"));
    }

    private List<SchoolClass> activeClassSequence(Long schoolId) {
        return classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true);
    }

    private void finalizeStudentGraduation(Student student, LocalDate date) {
        student.setStatus(StudentStatus.GRADUATED);
        student.setReasonForLeaving(COMPLETION_REASON);
        student.setLeavingDate(date);
        students.saveAndFlush(student);
    }

    private void synchronizeProjection(Student student, StudentEnrollment enrollment) {
        student.setClassId(enrollment.getClassId());
        student.setClassName(enrollment.getClassNameSnapshot());
        student.setSectionId(enrollment.getSectionId());
        student.setSectionName(enrollment.getSectionNameSnapshot());
        student.setStatus(StudentStatus.ACTIVE);
    }

    private void audit(AuditContext context, String action, String studentId,
                       String oldValue, String newValue) {
        AuditContext actor = context == null ? new AuditContext(null, null, null) : context;
        auditService.log(actor.username(), actor.role(), action, "Student", studentId,
                oldValue, newValue, actor.ipAddress());
    }

    private void requireRequest(Request request) {
        if (request == null || request.schoolId() == null || request.studentId() == null
                || request.studentId().isBlank() || request.sourceSessionId() == null
                || request.expectedSourceEnrollmentId() == null
                || request.expectedSourceClassId() == null || request.action() == null) {
            throw new IllegalArgumentException("Complete year-end source identity and action are required");
        }
        if (!request.action().isYearEndMembershipAction()) {
            throw new IllegalArgumentException("Year-end service applies only PROMOTE, DETAIN or PASS_OUT");
        }
    }

    private Result result(Outcome outcome, String message, Long sourceId, Long targetId,
                          boolean pending) {
        return new Result(outcome, message, sourceId, targetId, null, pending);
    }
}
