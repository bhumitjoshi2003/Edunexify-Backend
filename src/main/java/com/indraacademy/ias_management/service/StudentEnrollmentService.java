package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.indraacademy.ias_management.util.SchoolTimeUtil;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

@Service
public class StudentEnrollmentService {
    private static final Logger log = LoggerFactory.getLogger(StudentEnrollmentService.class);

    private final SchoolRepository schoolRepository;
    private final StudentRepository studentRepository;
    private final AcademicSessionRepository sessionRepository;
    private final SchoolClassRepository classRepository;
    private final SectionRepository sectionRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    @Autowired
    public StudentEnrollmentService(
            SchoolRepository schoolRepository,
            StudentRepository studentRepository,
            AcademicSessionRepository sessionRepository,
            SchoolClassRepository classRepository,
            SectionRepository sectionRepository,
            StudentEnrollmentRepository enrollmentRepository,
            Clock clock) {
        this.schoolRepository = schoolRepository;
        this.studentRepository = studentRepository;
        this.sessionRepository = sessionRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<StudentEnrollment> findEffectiveEnrollment(
            Long schoolId, String studentId, Long sessionId, LocalDate date) {
        requireInputs(schoolId, studentId, sessionId, date);
        return enrollmentRepository.findEffectiveEnrollment(schoolId, studentId, sessionId, date);
    }

    @Transactional(readOnly = true)
    public Optional<StudentEnrollment> findOpenEnrollment(
            Long schoolId, String studentId, Long sessionId) {
        requireInputs(schoolId, studentId, sessionId, schoolToday(schoolId));
        return enrollmentRepository
                .findBySchoolIdAndStudentIdAndAcademicSessionIdAndStatusInAndEffectiveUntilIsNull(
                        schoolId, studentId, sessionId,
                        List.of(StudentEnrollmentStatus.PLANNED, StudentEnrollmentStatus.ACTIVE));
    }

    @Transactional(readOnly = true)
    public List<String> findEligiblePlannedStudentIds(Long schoolId, LocalDate schoolLocalToday) {
        if (schoolId == null || schoolLocalToday == null) {
            throw new IllegalArgumentException("schoolId and schoolLocalToday are required");
        }
        return enrollmentRepository
                .findBySchoolIdAndStatusAndEffectiveFromLessThanEqualOrderByStudentIdAscEffectiveFromAsc(
                        schoolId, StudentEnrollmentStatus.PLANNED, schoolLocalToday)
                .stream().map(StudentEnrollment::getStudentId).distinct().toList();
    }

    @Transactional(readOnly = true)
    public List<StudentEnrollment> findDueGraduationEnrollments(Long schoolId, LocalDate schoolLocalToday) {
        if (schoolId == null || schoolLocalToday == null) {
            throw new IllegalArgumentException("schoolId and schoolLocalToday are required");
        }
        List<StudentEnrollment> due = enrollmentRepository
                .findBySchoolIdAndStatusAndClosureReasonAndEffectiveUntilLessThanEqualOrderByStudentIdAscEffectiveUntilAsc(
                        schoolId, StudentEnrollmentStatus.CLOSED,
                        StudentEnrollmentClosureReason.GRADUATED, schoolLocalToday);
        if (due.isEmpty()) {
            return due;
        }
        // Excludes graduations already finalized (Student.status GRADUATED with a matching
        // leavingDate) so a school's full alumni history isn't re-fetched and re-evaluated by
        // finalizeGraduation on every single nightly run, forever. There is no dedicated
        // "finalized" flag on student_enrollment; Student's own settled state is authoritative
        // evidence here, cross-checked against the exact effectiveUntil this row would finalize
        // to (a re-graduated-after-readmission row would have a mismatched leavingDate and stays
        // in the candidate set, to be reported as CONFLICT by finalizeGraduation).
        List<String> studentIds = due.stream().map(StudentEnrollment::getStudentId).distinct().toList();
        java.util.Map<String, Student> byStudentId = studentRepository
                .findByStudentIdInAndSchoolId(studentIds, schoolId).stream()
                .collect(java.util.stream.Collectors.toMap(Student::getStudentId, s -> s, (a, b) -> a));
        return due.stream()
                .filter(e -> {
                    Student s = byStudentId.get(e.getStudentId());
                    return s == null || s.getStatus() != StudentStatus.GRADUATED
                            || !Objects.equals(s.getLeavingDate(), e.getEffectiveUntil());
                })
                .toList();
    }

    /**
     * Year-end TRANSFER decisions recorded ahead of their source session's end (the
     * enrollment closed at exactly the session end with that reason) that are now due and not yet
     * finalized — the student is still ACTIVE and has no later enrollment. A normal exit is never
     * returned: it changes the student's status at once.
     */
    @Transactional(readOnly = true)
    public List<StudentEnrollment> findDueYearEndExitEnrollments(Long schoolId, LocalDate schoolLocalToday) {
        if (schoolId == null || schoolLocalToday == null) {
            throw new IllegalArgumentException("schoolId and schoolLocalToday are required");
        }
        List<StudentEnrollment> due = new java.util.ArrayList<>();
        for (StudentEnrollmentClosureReason reason : List.of(StudentEnrollmentClosureReason.TRANSFERRED)) {
            due.addAll(enrollmentRepository
                    .findBySchoolIdAndStatusAndClosureReasonAndEffectiveUntilLessThanEqualOrderByStudentIdAscEffectiveUntilAsc(
                            schoolId, StudentEnrollmentStatus.CLOSED, reason, schoolLocalToday));
        }
        if (due.isEmpty()) {
            return due;
        }
        java.util.Map<String, Student> byStudentId = studentRepository
                .findByStudentIdInAndSchoolId(due.stream().map(StudentEnrollment::getStudentId).distinct().toList(), schoolId)
                .stream().collect(java.util.stream.Collectors.toMap(Student::getStudentId, s -> s, (a, b) -> a));
        java.util.Map<Long, LocalDate> sessionEnds = new java.util.HashMap<>();
        return due.stream()
                .filter(e -> {
                    Student s = byStudentId.get(e.getStudentId());
                    return s != null && s.getStatus() == StudentStatus.ACTIVE;
                })
                .filter(e -> Objects.equals(e.getEffectiveUntil(), sessionEnds.computeIfAbsent(e.getAcademicSessionId(),
                        id -> sessionRepository.findByIdAndSchoolId(id, schoolId)
                                .map(AcademicSession::getEndDate).orElse(null))))
                .filter(e -> enrollmentRepository.findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(
                                schoolId, e.getStudentId()).stream()
                        .noneMatch(o -> !Objects.equals(o.getId(), e.getId())
                                && o.getStatus() != StudentEnrollmentStatus.CANCELLED
                                && o.getEffectiveFrom().isAfter(e.getEffectiveUntil())))
                .toList();
    }

    @Transactional
    public StudentEnrollment createActiveEnrollment(
            Long schoolId, String studentId, Long sessionId,
            Long classId, Long sectionId, LocalDate effectiveFrom) {
        LockedContext context = lockAndValidate(
                schoolId, studentId, sessionId, classId, sectionId, effectiveFrom, true);
        if (effectiveFrom.isAfter(schoolToday(schoolId))) {
            throw new IllegalArgumentException("An active enrollment cannot begin in the future");
        }
        ensureNoEnrollmentConflict(context.history(), effectiveFrom, null);
        StudentEnrollment active = enrollmentRepository.saveAndFlush(
                newEnrollment(context, StudentEnrollmentStatus.ACTIVE, effectiveFrom));
        synchronizeProjection(context.student(), active);
        studentRepository.save(context.student());
        return active;
    }

    @Transactional
    public StudentEnrollment createPlannedEnrollment(
            Long schoolId, String studentId, Long sessionId,
            Long classId, Long sectionId, LocalDate effectiveFrom) {
        LockedContext context = lockAndValidate(
                schoolId, studentId, sessionId, classId, sectionId, effectiveFrom, false);
        if (effectiveFrom.isBefore(schoolToday(schoolId))) {
            throw new IllegalArgumentException("A planned enrollment cannot begin in the past");
        }
        ensureNoEnrollmentConflict(context.history(), effectiveFrom, null);
        StudentEnrollment planned = newEnrollment(context, StudentEnrollmentStatus.PLANNED, effectiveFrom);
        return enrollmentRepository.saveAndFlush(planned);
    }

    @Transactional
    public StudentEnrollment activatePlannedEnrollment(
            Long schoolId, String studentId, Long sessionId,
            Long enrollmentId, LocalDate activationDate) {
        Student student = lockStudent(schoolId, studentId, true);
        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, activationDate);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        StudentEnrollment planned = history.stream()
                .filter(e -> Objects.equals(e.getId(), enrollmentId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Enrollment not found for student and school"));

        if (activationDate.isBefore(planned.getEffectiveFrom())) {
            throw new IllegalStateException("Planned enrollment cannot be activated before its effective date");
        }
        if (planned.getStatus() == StudentEnrollmentStatus.ACTIVE) {
            synchronizeProjection(student, planned);
            student.setStatus(StudentStatus.ACTIVE);
            studentRepository.save(student);
            return planned;
        }
        if (planned.getStatus() != StudentEnrollmentStatus.PLANNED) {
            throw new IllegalStateException("Only a PLANNED enrollment can be activated");
        }
        validateMembership(schoolId, planned.getClassId(), planned.getSectionId());
        boolean anotherActive = history.stream().anyMatch(e -> !Objects.equals(e.getId(), enrollmentId)
                && e.getStatus() == StudentEnrollmentStatus.ACTIVE
                && rangesContain(e, activationDate));
        if (anotherActive) {
            throw new IllegalStateException("Another ACTIVE enrollment conflicts on the activation date");
        }

        planned.setStatus(StudentEnrollmentStatus.ACTIVE);
        StudentEnrollment activated = enrollmentRepository.saveAndFlush(planned);
        synchronizeProjection(student, activated);
        student.setStatus(StudentStatus.ACTIVE);
        studentRepository.save(student);
        return activated;
    }

    /** Canonical scheduler entry point. Each invocation is an independent transaction. */
    @Transactional
    public ScheduledActivation activateEligiblePlannedEnrollment(
            Long schoolId, String studentId, LocalDate schoolLocalToday) {
        Student student = lockStudent(schoolId, studentId, false);
        List<StudentEnrollment> history = enrollmentRepository.findAllHistoryForUpdate(schoolId, studentId);

        Optional<StudentEnrollment> effectiveActive = history.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE)
                .filter(e -> rangesContain(e, schoolLocalToday))
                .findFirst();
        if (student.getStatus() == StudentStatus.ACTIVE && effectiveActive.isPresent()) {
            StudentEnrollment active = effectiveActive.orElseThrow();
            if (!projectionMatches(student, active)) {
                synchronizeProjection(student, active);
                studentRepository.save(student);
            }
            return new ScheduledActivation(ScheduledActivationOutcome.ALREADY_ACTIVE, active.getId());
        }
        boolean admission = student.getStatus() == StudentStatus.UPCOMING;
        boolean continuing = student.getStatus() == StudentStatus.ACTIVE;
        if (!admission && !continuing) {
            // A discovered candidate (e.g. a stale PLANNED row left behind on an exited
            // student) that is neither an UPCOMING admission nor a continuing ACTIVE student
            // is reported, not thrown — one such row must never block the rest of the batch.
            return new ScheduledActivation(ScheduledActivationOutcome.NOT_ELIGIBLE, null);
        }

        List<StudentEnrollment> eligible = history.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.PLANNED)
                .filter(e -> !e.getEffectiveFrom().isAfter(schoolLocalToday))
                .toList();
        if (eligible.isEmpty()) {
            return new ScheduledActivation(ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT, null);
        }
        if (eligible.size() > 1) {
            throw new IllegalStateException("Multiple eligible PLANNED enrollments conflict");
        }

        StudentEnrollment planned = eligible.getFirst();
        AcademicSession session = requireSession(schoolId, planned.getAcademicSessionId());
        requireDateInSession(session, planned.getEffectiveFrom());
        if (schoolLocalToday.isAfter(session.getEndDate())) {
            // Late activation is allowed anywhere inside the target session; once the session
            // itself has ended there is nothing safe to activate into — report it instead of
            // throwing so it reads as an expected, actionable outcome rather than a failure.
            return new ScheduledActivation(ScheduledActivationOutcome.EXPIRED_TARGET_SESSION, planned.getId());
        }
        if (continuing) validateContinuingActivation(schoolId, history, planned, session);
        validateMembership(schoolId, planned.getClassId(), planned.getSectionId());
        boolean conflictingActive = history.stream()
                .filter(e -> !Objects.equals(e.getId(), planned.getId()))
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE)
                .anyMatch(e -> rangesContain(e, schoolLocalToday));
        if (conflictingActive) {
            throw new IllegalStateException("Another ACTIVE enrollment conflicts on the activation date");
        }

        planned.setStatus(StudentEnrollmentStatus.ACTIVE);
        StudentEnrollment activated = enrollmentRepository.saveAndFlush(planned);
        synchronizeProjection(student, activated);
        student.setStatus(StudentStatus.ACTIVE);
        studentRepository.save(student);
        return new ScheduledActivation(admission ? ScheduledActivationOutcome.ACTIVATED
                : ScheduledActivationOutcome.CONTINUING_ACTIVATED, activated.getId());
    }

    private void validateContinuingActivation(Long schoolId, List<StudentEnrollment> history,
                                              StudentEnrollment planned,
                                              AcademicSession targetSession) {
        if (!Objects.equals(planned.getEffectiveFrom(), targetSession.getStartDate())) {
            throw new IllegalStateException("Continuing target must begin on target session start");
        }
        List<StudentEnrollment> sources = history.stream()
                .filter(e -> !Objects.equals(e.getId(), planned.getId()))
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.CLOSED)
                .filter(e -> e.getClosureReason() == StudentEnrollmentClosureReason.SESSION_COMPLETED)
                .filter(e -> Objects.equals(e.getEffectiveUntil(), planned.getEffectiveFrom().minusDays(1)))
                .toList();
        if (sources.size() != 1) {
            throw new IllegalStateException("Continuing activation requires exactly one completed source enrollment");
        }
        StudentEnrollment source = sources.getFirst();
        AcademicSession sourceSession = requireSession(source.getSchoolId(), source.getAcademicSessionId());
        if (!Objects.equals(sourceSession.getEndDate(), source.getEffectiveUntil())
                || !Objects.equals(targetSession.getStartDate(), sourceSession.getEndDate().plusDays(1))) {
            throw new IllegalStateException("Continuing activation requires contiguous source and target sessions");
        }
        // Dates alone don't prove this PLANNED row came from a real year-end decision — an
        // arbitrary/corrupt target class would still line up on dates. Mirror
        // StudentYearEndService's own PROMOTE/DETAIN class rule: the target must be either the
        // source's exact class (DETAIN) or its immediate configured successor (PROMOTE).
        validateClassRelationship(schoolId, source.getClassId(), planned.getClassId());
    }

    /** Finds the CLOSED SESSION_COMPLETED enrollment `planned` was produced from by a real
     *  year-end decision — contiguous session, adjacent dates, target starting on the target
     *  session's own start date. Empty when `planned` carries no such provenance (e.g. a plain
     *  admission-flow PLANNED enrollment), in which case no class-sequence relationship applies
     *  to it. Used by {@link #correctPlannedEnrollment} to decide whether a correction's new
     *  target class must still be validated against {@link #validateClassRelationship}. */
    private Optional<StudentEnrollment> findYearEndSource(List<StudentEnrollment> history,
                                                          StudentEnrollment planned, AcademicSession targetSession) {
        if (!Objects.equals(planned.getEffectiveFrom(), targetSession.getStartDate())) {
            return Optional.empty();
        }
        List<StudentEnrollment> sources = history.stream()
                .filter(e -> !Objects.equals(e.getId(), planned.getId()))
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.CLOSED)
                .filter(e -> e.getClosureReason() == StudentEnrollmentClosureReason.SESSION_COMPLETED)
                .filter(e -> Objects.equals(e.getEffectiveUntil(), planned.getEffectiveFrom().minusDays(1)))
                .toList();
        if (sources.size() != 1) {
            return Optional.empty();
        }
        StudentEnrollment source = sources.getFirst();
        AcademicSession sourceSession = requireSession(source.getSchoolId(), source.getAcademicSessionId());
        boolean contiguous = Objects.equals(sourceSession.getEndDate(), source.getEffectiveUntil())
                && Objects.equals(targetSession.getStartDate(), sourceSession.getEndDate().plusDays(1));
        return contiguous ? Optional.of(source) : Optional.empty();
    }

    /** Shared PROMOTE/DETAIN class-sequence rule: a legitimate year-end target class must
     *  equal the source's own class (DETAIN) or be its immediate configured successor
     *  (PROMOTE) — never an arbitrary jump. Used both at scheduled activation time
     *  ({@link #validateContinuingActivation}) and when an admin corrects a still-PLANNED
     *  target that has real year-end provenance ({@link #correctPlannedEnrollment}). */
    private void validateClassRelationship(Long schoolId, Long sourceClassId, Long candidateTargetClassId) {
        List<SchoolClass> sequence = classRepository.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true);
        int sourceIndex = -1;
        for (int i = 0; i < sequence.size(); i++) {
            if (Objects.equals(sequence.get(i).getId(), sourceClassId)) {
                sourceIndex = i;
                break;
            }
        }
        if (sourceIndex < 0) {
            throw new IllegalStateException("Source class is not in the active school class sequence");
        }
        boolean sameClass = Objects.equals(sourceClassId, candidateTargetClassId);
        boolean immediateSuccessor = sourceIndex < sequence.size() - 1
                && Objects.equals(sequence.get(sourceIndex + 1).getId(), candidateTargetClassId);
        if (!sameClass && !immediateSuccessor) {
            throw new IllegalStateException(
                    "Continuing target class must equal the source class (DETAIN) or its immediate successor (PROMOTE)");
        }
    }

    /**
     * Corrects a future PLANNED enrollment's class/section before it becomes effective — e.g.
     * fixing a year-end decision's target class/section, or an admission's planned class, ahead
     * of its start date. Corrects the row IN PLACE (same id, same status/effectiveFrom) rather
     * than closing and replacing it: a PLANNED row has never been effective, so there is no
     * historical period to preserve by closing it — this mirrors {@code transitionLocked}'s own
     * same-day in-place correction branch for an ACTIVE segment corrected from its own start
     * date, extended to the "never yet started at all" case.
     * <p>
     * When {@code planned} was produced by a real year-end decision (a CLOSED SESSION_COMPLETED
     * source enrollment immediately precedes it with a contiguous session), the corrected target
     * class must still be either the source's own class (DETAIN) or its immediate configured
     * successor (PROMOTE) — see {@link #validateClassRelationship}. This is what prevents a
     * correction from turning a DETAIN into an arbitrary PROMOTE, or a PROMOTE into a multi-grade
     * jump. A PLANNED row with no such provenance (e.g. a plain admission) carries no such
     * constraint — any valid class/section in the school is acceptable.
     * <p>
     * The Student projection is never touched — nothing here is effective yet. Every other row
     * in history, including the preceding CLOSED source, is read-only evidence and is never
     * mutated. No fee/payment repository is read or written. An exact repeat (identical target
     * class and section on an already-corrected row) is a no-op success.
     */
    @Transactional
    public StudentEnrollment correctPlannedEnrollment(
            Long schoolId, String studentId, Long enrollmentId,
            Long targetClassId, Long targetSectionId) {
        Student student = lockStudent(schoolId, studentId, false);
        List<StudentEnrollment> history = enrollmentRepository.findAllHistoryForUpdate(schoolId, studentId);
        StudentEnrollment planned = history.stream()
                .filter(e -> Objects.equals(e.getId(), enrollmentId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Enrollment not found for student and school"));

        if (planned.getStatus() == StudentEnrollmentStatus.PLANNED
                && Objects.equals(planned.getClassId(), targetClassId)
                && Objects.equals(planned.getSectionId(), targetSectionId)) {
            return planned;
        }
        if (planned.getStatus() != StudentEnrollmentStatus.PLANNED) {
            throw new IllegalStateException("Only a PLANNED enrollment can be corrected");
        }
        LocalDate today = schoolToday(schoolId);
        if (!planned.getEffectiveFrom().isAfter(today)) {
            throw new IllegalStateException("Only a future PLANNED enrollment can be corrected");
        }
        AcademicSession targetSession = requireSession(schoolId, planned.getAcademicSessionId());

        findYearEndSource(history, planned, targetSession)
                .ifPresent(source -> validateClassRelationship(schoolId, source.getClassId(), targetClassId));

        ValidatedMembership membership = validateCorrectionMembership(schoolId, targetClassId, targetSectionId);
        planned.setClassId(membership.schoolClass().getId());
        planned.setClassNameSnapshot(membership.schoolClass().getName());
        if (membership.section() != null) {
            planned.setSectionId(membership.section().getId());
            planned.setSectionNameSnapshot(membership.section().getName());
        } else {
            planned.setSectionId(null);
            planned.setSectionNameSnapshot(null);
        }
        return enrollmentRepository.saveAndFlush(planned);
    }

    /** Same class/tenant validation as {@link #validateMembership}, plus the "established
     *  enrollment rule" this operation must additionally enforce: when the target class has any
     *  active section, an explicit, valid section is required; when it has none, the section
     *  must be null. Mirrors {@code StudentYearEndService.resolveTargetSection} — the same rule
     *  a year-end PROMOTE/DETAIN decision already applies — but never silently defaults to a
     *  source section the way DETAIN's own preview does, since a correction's caller must be
     *  explicit about what they're choosing. */
    private ValidatedMembership validateCorrectionMembership(Long schoolId, Long classId, Long sectionId) {
        if (classId == null) throw new IllegalArgumentException("classId is required");
        SchoolClass schoolClass = classRepository.findByIdAndSchoolId(classId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class not found for school"));
        List<Section> configured = sectionRepository
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, classId, true);
        if (configured.isEmpty()) {
            if (sectionId != null) {
                throw new IllegalArgumentException("Target section must be null when the target class has no sections");
            }
            return new ValidatedMembership(schoolClass, null);
        }
        if (sectionId == null) {
            throw new IllegalArgumentException("An explicit target section is required");
        }
        Section section = configured.stream().filter(s -> Objects.equals(s.getId(), sectionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target section is inactive, belongs to another class, or belongs to another school"));
        return new ValidatedMembership(schoolClass, section);
    }

    @Transactional
    public EnrollmentTransition transitionClass(
            Long schoolId, String studentId, Long sessionId,
            Long targetClassId, Long targetSectionId, LocalDate effectiveFrom) {
        return transition(schoolId, studentId, sessionId, targetClassId, targetSectionId,
                effectiveFrom, StudentEnrollmentClosureReason.CLASS_CHANGE);
    }

    @Transactional
    public EnrollmentTransition transitionSection(
            Long schoolId, String studentId, Long sessionId,
            Long targetSectionId, LocalDate effectiveFrom) {
        Student student = lockStudent(schoolId, studentId, true);
        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, effectiveFrom);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        StudentEnrollment current = requireOpenActive(history);
        return transitionLocked(student, session, history, current, current.getClassId(),
                targetSectionId, effectiveFrom, StudentEnrollmentClosureReason.SECTION_CHANGE);
    }

    @Transactional
    public StudentEnrollment closeEnrollment(
            Long schoolId, String studentId, Long sessionId, Long enrollmentId,
            LocalDate finalEffectiveDate, StudentEnrollmentClosureReason reason,
            ProjectionOnClose projectionBehavior) {
        if (reason == null || reason == StudentEnrollmentClosureReason.CANCELLED_BEFORE_START) {
            throw new IllegalArgumentException("Closing requires a non-cancellation closure reason");
        }
        if (projectionBehavior == null) {
            throw new IllegalArgumentException("Projection behavior must be explicit");
        }
        Student student = lockStudent(schoolId, studentId, false);
        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, finalEffectiveDate);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        StudentEnrollment enrollment = history.stream()
                .filter(e -> Objects.equals(e.getId(), enrollmentId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Enrollment not found for student and school"));
        if (enrollment.getStatus() == StudentEnrollmentStatus.CLOSED
                && Objects.equals(enrollment.getEffectiveUntil(), finalEffectiveDate)
                && enrollment.getClosureReason() == reason) {
            return enrollment;
        }
        if (enrollment.getStatus() != StudentEnrollmentStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE enrollment can be closed");
        }
        if (finalEffectiveDate.isBefore(enrollment.getEffectiveFrom())) {
            throw new IllegalArgumentException("Closure date precedes enrollment effective date");
        }
        enrollment.setStatus(StudentEnrollmentStatus.CLOSED);
        enrollment.setEffectiveUntil(finalEffectiveDate);
        enrollment.setClosureReason(reason);
        StudentEnrollment closed = enrollmentRepository.saveAndFlush(enrollment);

        if (finalEffectiveDate.isBefore(LocalDate.now(clock))
                && projectionBehavior == ProjectionOnClose.CLEAR_IF_NO_LONGER_EFFECTIVE) {
            clearProjection(student);
            studentRepository.save(student);
        }
        return closed;
    }

    /**
     * Closes the enrollment that authoritatively covers an explicit exit date. The
     * Student row is locked before every enrollment row, matching transition locking.
     * A student with no enrollment history is returned as a legacy fallback; callers
     * may preserve the pre-enrollment status-only workflow without fabricating history.
     */
    @Transactional
    public LifecycleMutation closeForExplicitExit(
            Long schoolId, String studentId, Long sessionId, LocalDate exitDate,
            StudentEnrollmentClosureReason reason) {
        if (reason != StudentEnrollmentClosureReason.GRADUATED
                && reason != StudentEnrollmentClosureReason.TRANSFERRED
                && reason != StudentEnrollmentClosureReason.WITHDRAWN) {
            throw new IllegalArgumentException("Explicit exit requires GRADUATED, TRANSFERRED, or WITHDRAWN");
        }
        Student student = lockStudent(schoolId, studentId, false);
        if (student.getStatus() != StudentStatus.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE students can be exited. Current status: " + student.getStatus());
        }
        List<StudentEnrollment> allHistory = enrollmentRepository
                .findAllHistoryForUpdate(schoolId, studentId);
        if (allHistory.isEmpty()) {
            return new LifecycleMutation(student, null, true, null);
        }

        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, exitDate);
        StudentEnrollment effective = allHistory.stream()
                .filter(e -> Objects.equals(e.getAcademicSessionId(), sessionId))
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .filter(e -> rangesContain(e, exitDate))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No effective enrollment exists on the requested exit date"));
        if (effective.getStatus() != StudentEnrollmentStatus.ACTIVE
                || effective.getEffectiveUntil() != null) {
            throw new IllegalStateException("Only the current open ACTIVE enrollment can be exited");
        }

        // A student promoted/detained ahead of time may already have a future PLANNED target
        // enrollment for the next session. That row has never been effective, so exiting now
        // legitimately voids it rather than blocking the exit — but any OTHER later-segment
        // shape (more than one, or one that isn't PLANNED) is not something this method
        // understands, so it fails safely rather than guessing which row to touch.
        List<StudentEnrollment> laterSegments = allHistory.stream()
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .filter(e -> e.getEffectiveFrom().isAfter(exitDate))
                .toList();
        StudentEnrollment futurePlannedToCancel = null;
        if (!laterSegments.isEmpty()) {
            if (laterSegments.size() > 1 || laterSegments.getFirst().getStatus() != StudentEnrollmentStatus.PLANNED) {
                throw new IllegalStateException("Exit date precedes a later enrollment segment");
            }
            futurePlannedToCancel = laterSegments.getFirst();
        }

        if (futurePlannedToCancel != null) {
            futurePlannedToCancel.setStatus(StudentEnrollmentStatus.CANCELLED);
            futurePlannedToCancel.setEffectiveUntil(futurePlannedToCancel.getEffectiveFrom());
            futurePlannedToCancel.setClosureReason(StudentEnrollmentClosureReason.CANCELLED_BEFORE_START);
            enrollmentRepository.saveAndFlush(futurePlannedToCancel);
            log.info("Cancelled future PLANNED enrollment on explicit exit: schoolId={}, studentId={}, "
                            + "cancelledEnrollmentId={}, cancelledEnrollmentSessionId={}, cancelledEffectiveFrom={}, "
                            + "exitDate={}, exitReason={}",
                    schoolId, studentId, futurePlannedToCancel.getId(), futurePlannedToCancel.getAcademicSessionId(),
                    futurePlannedToCancel.getEffectiveFrom(), exitDate, reason);
        }

        effective.setStatus(StudentEnrollmentStatus.CLOSED);
        effective.setEffectiveUntil(exitDate);
        effective.setClosureReason(reason);
        StudentEnrollment closedEffective = enrollmentRepository.saveAndFlush(effective);
        return new LifecycleMutation(student, closedEffective, false,
                futurePlannedToCancel == null ? null : futurePlannedToCancel.getId());
    }

    /** Creates a new membership segment for an explicit readmission; CLOSED history is immutable. */
    @Transactional
    public LifecycleMutation createForExplicitReadmission(
            Long schoolId, String studentId, Long sessionId, Long classId,
            Long sectionId, LocalDate readmissionDate) {
        Student student = lockStudent(schoolId, studentId, false);
        if (student.getStatus() == null || !student.getStatus().isExitStatus()) {
            throw new IllegalStateException(
                    "Only exited students can be re-admitted. Current status: " + student.getStatus());
        }
        List<StudentEnrollment> allHistory = enrollmentRepository
                .findAllHistoryForUpdate(schoolId, studentId);
        if (allHistory.isEmpty()) {
            return new LifecycleMutation(student, null, true, null);
        }

        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, readmissionDate);
        if (readmissionDate.isAfter(schoolToday(schoolId))) {
            throw new IllegalArgumentException("The current readmission operation cannot be future-dated");
        }
        ValidatedMembership membership = validateMembership(schoolId, classId, sectionId);
        List<StudentEnrollment> sessionHistory = allHistory.stream()
                .filter(e -> Objects.equals(e.getAcademicSessionId(), sessionId))
                .toList();
        ensureNoEnrollmentConflict(sessionHistory, readmissionDate, null);

        LockedContext context = new LockedContext(student, session, membership.schoolClass(),
                membership.section(), sessionHistory);
        StudentEnrollment active = enrollmentRepository.saveAndFlush(
                newEnrollment(context, StudentEnrollmentStatus.ACTIVE, readmissionDate));
        synchronizeProjection(student, active);
        studentRepository.save(student);
        return new LifecycleMutation(student, active, false, null);
    }

    @Transactional(readOnly = true)
    public ProjectionConsistency checkProjectionConsistency(
            Long schoolId, String studentId, Long sessionId) {
        LocalDate date = LocalDate.now(clock);
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        Optional<StudentEnrollment> effective = findEffectiveEnrollment(schoolId, studentId, sessionId, date);
        boolean consistent = effective.isPresent() && projectionMatches(student, effective.get());
        return new ProjectionConsistency(consistent, studentId, date,
                effective.map(StudentEnrollment::getId).orElse(null),
                consistent ? "Projection matches effective enrollment"
                        : effective.isEmpty() ? "No authoritative enrollment covers the requested date"
                        : "Student projection differs from authoritative enrollment");
    }

    @Transactional
    public ProjectionConsistency repairProjectionFromEnrollment(
            Long schoolId, String studentId, Long sessionId) {
        LocalDate date = LocalDate.now(clock);
        Student student = lockStudent(schoolId, studentId, false);
        requireSession(schoolId, sessionId);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        StudentEnrollment effective = history.stream()
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED && rangesContain(e, date))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No authoritative enrollment covers the repair date; historical absence is not repaired"));
        if (!projectionMatches(student, effective)) {
            log.warn("Repairing Student projection from enrollment: schoolId={}, studentId={}, enrollmentId={}",
                    schoolId, studentId, effective.getId());
            synchronizeProjection(student, effective);
            studentRepository.save(student);
        }
        return new ProjectionConsistency(true, studentId, date, effective.getId(),
                "Projection matches effective enrollment after explicit repair");
    }

    private EnrollmentTransition transition(
            Long schoolId, String studentId, Long sessionId,
            Long classId, Long sectionId, LocalDate effectiveFrom,
            StudentEnrollmentClosureReason reason) {
        Student student = lockStudent(schoolId, studentId, true);
        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, effectiveFrom);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        StudentEnrollment current = requireOpenActive(history);
        return transitionLocked(student, session, history, current, classId, sectionId,
                effectiveFrom, reason);
    }

    private EnrollmentTransition transitionLocked(
            Student student, AcademicSession session, List<StudentEnrollment> history,
            StudentEnrollment current, Long classId, Long sectionId, LocalDate effectiveFrom,
            StudentEnrollmentClosureReason reason) {
        if (effectiveFrom.isBefore(current.getEffectiveFrom())) {
            throw new IllegalArgumentException(
                    "Transition date cannot precede the current segment's effective date");
        }
        ValidatedMembership membership = validateMembership(
                student.getSchoolId(), classId, sectionId);
        if (effectiveFrom.equals(current.getEffectiveFrom())) {
            current.setClassId(membership.schoolClass().getId());
            current.setClassNameSnapshot(membership.schoolClass().getName());
            current.setSectionId(membership.section() != null ? membership.section().getId() : null);
            current.setSectionNameSnapshot(membership.section() != null ? membership.section().getName() : null);
            StudentEnrollment corrected = enrollmentRepository.saveAndFlush(current);
            synchronizeProjection(student, corrected);
            studentRepository.save(student);
            return new EnrollmentTransition(null, corrected);
        }
        LocalDate oldEnd = effectiveFrom.minusDays(1);
        current.setStatus(StudentEnrollmentStatus.CLOSED);
        current.setEffectiveUntil(oldEnd);
        current.setClosureReason(reason);
        enrollmentRepository.saveAndFlush(current);

        StudentEnrollmentStatus replacementStatus = effectiveFrom.isAfter(schoolToday(student.getSchoolId()))
                ? StudentEnrollmentStatus.PLANNED : StudentEnrollmentStatus.ACTIVE;
        LockedContext context = new LockedContext(student, session, membership.schoolClass(),
                membership.section(), history);
        StudentEnrollment replacement = newEnrollment(context, replacementStatus, effectiveFrom);
        replacement = enrollmentRepository.saveAndFlush(replacement);
        if (replacementStatus == StudentEnrollmentStatus.ACTIVE) {
            synchronizeProjection(student, replacement);
            studentRepository.save(student);
        }
        return new EnrollmentTransition(current, replacement);
    }

    private LockedContext lockAndValidate(
            Long schoolId, String studentId, Long sessionId, Long classId,
            Long sectionId, LocalDate date, boolean rejectExitStatus) {
        Student student = lockStudent(schoolId, studentId, rejectExitStatus);
        AcademicSession session = requireSession(schoolId, sessionId);
        requireDateInSession(session, date);
        ValidatedMembership membership = validateMembership(schoolId, classId, sectionId);
        List<StudentEnrollment> history = enrollmentRepository
                .findHistoryForUpdate(schoolId, studentId, sessionId);
        return new LockedContext(student, session, membership.schoolClass(), membership.section(), history);
    }

    private Student lockStudent(Long schoolId, String studentId, boolean rejectExitStatus) {
        if (schoolId == null || studentId == null || studentId.isBlank()) {
            throw new IllegalArgumentException("schoolId and studentId are required");
        }
        if (!schoolRepository.existsById(schoolId)) {
            throw new NoSuchElementException("School not found");
        }
        Student student = studentRepository.findByStudentIdAndSchoolIdForUpdate(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found for school"));
        if (rejectExitStatus && student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new IllegalStateException("Exited students cannot receive or activate membership transitions");
        }
        return student;
    }

    private AcademicSession requireSession(Long schoolId, Long sessionId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId is required");
        return sessionRepository.findByIdAndSchoolId(sessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Academic session not found for school"));
    }

    private ValidatedMembership validateMembership(Long schoolId, Long classId, Long sectionId) {
        if (classId == null) throw new IllegalArgumentException("classId is required");
        SchoolClass schoolClass = classRepository.findByIdAndSchoolId(classId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Class not found for school"));
        Section section = null;
        if (sectionId != null) {
            section = sectionRepository.findByIdAndSchoolId(sectionId, schoolId)
                    .orElseThrow(() -> new NoSuchElementException("Section not found for school"));
            if (!Objects.equals(section.getClassId(), classId)) {
                throw new IllegalArgumentException("Section does not belong to the selected class");
            }
        }
        return new ValidatedMembership(schoolClass, section);
    }

    private void requireDateInSession(AcademicSession session, LocalDate date) {
        if (date == null || date.isBefore(session.getStartDate()) || date.isAfter(session.getEndDate())) {
            throw new IllegalArgumentException("Effective date must be inside the academic session");
        }
    }

    private StudentEnrollment requireOpenActive(List<StudentEnrollment> history) {
        return history.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE
                        && e.getEffectiveUntil() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No open ACTIVE enrollment exists"));
    }

    private void ensureNoEnrollmentConflict(
            List<StudentEnrollment> history, LocalDate from, LocalDate until) {
        boolean conflict = history.stream()
                .filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .anyMatch(e -> rangesOverlap(e.getEffectiveFrom(), e.getEffectiveUntil(), from, until));
        if (conflict) throw new IllegalStateException("Enrollment conflicts with an existing segment");
    }

    private StudentEnrollment newEnrollment(
            LockedContext context, StudentEnrollmentStatus status, LocalDate effectiveFrom) {
        StudentEnrollment enrollment = new StudentEnrollment();
        enrollment.setSchoolId(context.student().getSchoolId());
        enrollment.setStudentId(context.student().getStudentId());
        enrollment.setAcademicSessionId(context.session().getId());
        enrollment.setClassId(context.schoolClass().getId());
        enrollment.setClassNameSnapshot(context.schoolClass().getName());
        if (context.section() != null) {
            enrollment.setSectionId(context.section().getId());
            enrollment.setSectionNameSnapshot(context.section().getName());
        }
        enrollment.setStatus(status);
        enrollment.setEffectiveFrom(effectiveFrom);
        return enrollment;
    }

    private boolean projectionMatches(Student student, StudentEnrollment enrollment) {
        return Objects.equals(student.getClassId(), enrollment.getClassId())
                && Objects.equals(student.getClassName(), enrollment.getClassNameSnapshot())
                && Objects.equals(student.getSectionId(), enrollment.getSectionId())
                && Objects.equals(student.getSectionName(), enrollment.getSectionNameSnapshot());
    }

    private void synchronizeProjection(Student student, StudentEnrollment enrollment) {
        student.setClassId(enrollment.getClassId());
        student.setClassName(enrollment.getClassNameSnapshot());
        student.setSectionId(enrollment.getSectionId());
        student.setSectionName(enrollment.getSectionNameSnapshot());
    }

    private void clearProjection(Student student) {
        student.setClassId(null);
        student.setClassName(null);
        student.setSectionId(null);
        student.setSectionName(null);
    }

    private boolean rangesContain(StudentEnrollment enrollment, LocalDate date) {
        return !enrollment.getEffectiveFrom().isAfter(date)
                && (enrollment.getEffectiveUntil() == null
                || !enrollment.getEffectiveUntil().isBefore(date));
    }

    private boolean rangesOverlap(LocalDate aFrom, LocalDate aUntil, LocalDate bFrom, LocalDate bUntil) {
        return (aUntil == null || !aUntil.isBefore(bFrom))
                && (bUntil == null || !bUntil.isBefore(aFrom));
    }

    private void requireInputs(Long schoolId, String studentId, Long sessionId, LocalDate date) {
        if (schoolId == null || studentId == null || studentId.isBlank()
                || sessionId == null || date == null) {
            throw new IllegalArgumentException("schoolId, studentId, sessionId and date are required");
        }
    }

    private LocalDate schoolToday(Long schoolId) {
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        return LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
    }

    public enum ProjectionOnClose {
        PRESERVE,
        CLEAR_IF_NO_LONGER_EFFECTIVE
    }

    public record EnrollmentTransition(
            StudentEnrollment closedSegment,
            StudentEnrollment replacementSegment) {}

    public record ProjectionConsistency(
            boolean consistent,
            String studentId,
            LocalDate checkedDate,
            Long authoritativeEnrollmentId,
                String detail) {}

    public record LifecycleMutation(
            Student student,
            StudentEnrollment enrollment,
            boolean legacyUncovered,
            /** Non-null only when this exit also auto-cancelled a future PLANNED enrollment
             *  that hadn't started yet — the id of that now-CANCELLED row, so a caller (or a
             *  later E5C drift check) can see exactly what was voided alongside the exit. */
            Long cancelledFuturePlannedEnrollmentId) {}

    public enum ScheduledActivationOutcome {
        ACTIVATED,
        CONTINUING_ACTIVATED,
        ALREADY_ACTIVE,
        NO_PLANNED_ENROLLMENT,
        /** The due PLANNED enrollment's target session has already ended — late activation is
         *  only allowed while today is still within the target session. Reported, not thrown,
         *  so it is distinguishable from a genuine data-integrity failure. */
        EXPIRED_TARGET_SESSION,
        /** Candidate was discovered (e.g. a stale PLANNED row) but the student's current
         *  status is neither UPCOMING nor continuing ACTIVE — an exited student, for
         *  instance. Reported rather than thrown so one such row never blocks the batch. */
        NOT_ELIGIBLE
    }

    public record ScheduledActivation(
            ScheduledActivationOutcome outcome,
            Long enrollmentId) {}

    private record ValidatedMembership(SchoolClass schoolClass, Section section) {}
    private record LockedContext(
            Student student,
            AcademicSession session,
            SchoolClass schoolClass,
            Section section,
            List<StudentEnrollment> history) {}
}
