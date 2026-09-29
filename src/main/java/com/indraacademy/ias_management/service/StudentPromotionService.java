package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.indraacademy.ias_management.dto.PromotionPreviewDTO.*;

/** E2 preview and batch coordinator. StudentYearEndService remains the mutation authority. */
@Service
public class StudentPromotionService {
    private static final Logger log = LoggerFactory.getLogger(StudentPromotionService.class);

    private final StudentRepository students;
    private final StudentEnrollmentRepository enrollments;
    private final AcademicSessionRepository sessions;
    private final SchoolClassRepository classes;
    private final SectionRepository sections;
    private final SchoolRepository schools;
    private final StudentYearEndWorker worker;
    private final SecurityUtil security;
    private final Clock clock;
    /** Optional collaborators (absent in narrow unit/slice tests): the year-end exit path, the
     *  rollover run record. Without them TRANSFER is rejected and no run is recorded. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StudentYearEndExitWorker exitWorker;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.indraacademy.ias_management.repository.StudentRolloverRunRepository runs;

    public StudentPromotionService(
            StudentRepository students, StudentEnrollmentRepository enrollments,
            AcademicSessionRepository sessions, SchoolClassRepository classes,
            SectionRepository sections, SchoolRepository schools,
            StudentYearEndWorker worker, SecurityUtil security, Clock clock) {
        this.students = students;
        this.enrollments = enrollments;
        this.sessions = sessions;
        this.classes = classes;
        this.sections = sections;
        this.schools = schools;
        this.worker = worker;
        this.security = security;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PromotionPreviewDTO getPromotionPreview(
            Long sourceSessionId, Long targetSessionId, Long classId, String studentId) {
        Long schoolId = security.getSchoolId();
        List<Issue> globalErrors = validateSessionPair(schoolId, sourceSessionId, targetSessionId);
        if (!globalErrors.isEmpty()) {
            return new PromotionPreviewDTO(sourceSessionId, targetSessionId, false,
                    globalErrors, List.of(), uncoveredForFilter(schoolId, studentId, Set.of()));
        }

        AcademicSession targetSession = sessions.findByIdAndSchoolId(targetSessionId, schoolId).orElseThrow();
        School school = schools.findById(schoolId).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        StudentEnrollmentStatus proposedStatus = today.isBefore(targetSession.getStartDate())
                ? StudentEnrollmentStatus.PLANNED : StudentEnrollmentStatus.ACTIVE;
        List<SchoolClass> sequence = classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true);

        Map<String,List<StudentEnrollment>> sourceByStudent = enrollments
                .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, sourceSessionId)
                .stream().filter(e -> classId == null || Objects.equals(e.getClassId(), classId))
                .filter(e -> studentId == null || studentId.isBlank() || e.getStudentId().equals(studentId))
                .collect(Collectors.groupingBy(StudentEnrollment::getStudentId, LinkedHashMap::new, Collectors.toList()));
        Map<String,List<StudentEnrollment>> targetByStudent = enrollments
                .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, targetSessionId)
                .stream().collect(Collectors.groupingBy(StudentEnrollment::getStudentId));
        Map<String,Student> studentById = students.findByStudentIdInAndSchoolId(
                        new ArrayList<>(sourceByStudent.keySet()), schoolId).stream()
                .collect(Collectors.toMap(Student::getStudentId, Function.identity()));

        List<Candidate> candidates = sourceByStudent.entrySet().stream()
                .map(entry -> previewCandidate(entry.getValue(), targetByStudent.getOrDefault(entry.getKey(), List.of()),
                        studentById.get(entry.getKey()), sequence, schoolId, proposedStatus))
                .sorted(Comparator.comparing(Candidate::sourceClassName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Candidate::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        return new PromotionPreviewDTO(sourceSessionId, targetSessionId, true, List.of(), candidates,
                uncoveredForFilter(schoolId, studentId, sourceByStudent.keySet()));
    }

    /**
     * Intentionally non-transactional: each worker invocation owns REQUIRES_NEW, and the rollover
     * run record is written before and after the batch (so a partially applied batch is still
     * recorded with accurate counts).
     */
    public PromotionResultDTO executePromotion(PromotionDecisionRequest batch, HttpServletRequest request) {
        Long schoolId = security.getSchoolId();
        StudentYearEndDecision.AuditContext actor = new StudentYearEndDecision.AuditContext(
                security.getUsername(), security.getRole(), request.getRemoteAddr());
        StudentRolloverRun run = startRun(schoolId, batch, actor.username());
        List<PromotionResultDTO.StudentOutcome> outcomes = new ArrayList<>();
        try {
            applyDecisions(schoolId, batch, request, actor, outcomes);
        } catch (RuntimeException e) {
            finishRun(run, outcomes, StudentRolloverRun.Status.FAILED);
            throw e;
        }
        Map<String,Long> summary = outcomes.stream().collect(Collectors.groupingBy(
                PromotionResultDTO.StudentOutcome::code, LinkedHashMap::new, Collectors.counting()));
        StudentRolloverRun finished = finishRun(run, outcomes, null);
        return new PromotionResultDTO(outcomes.size(), summary, outcomes, runSummary(finished));
    }

    private void applyDecisions(Long schoolId, PromotionDecisionRequest batch, HttpServletRequest request,
                                StudentYearEndDecision.AuditContext actor,
                                List<PromotionResultDTO.StudentOutcome> outcomes) {
        Set<String> seen = new HashSet<>();
        for (PromotionDecisionRequest.Decision decision : batch.getDecisions()) {
            if (!seen.add(decision.getStudentId())) {
                outcomes.add(validationOutcome(decision.getStudentId(), "Duplicate student decision in batch"));
                continue;
            }
            if (decision.getAction() == StudentYearEndDecision.Action.PENDING) {
                // Explicit "decide later": no enrollment change at all, but counted and reported.
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(), OUTCOME_PENDING,
                        "Kept pending — no change recorded", decision.getExpectedSourceEnrollmentId(), null, null, false));
                continue;
            }
            if (decision.getAction() == StudentYearEndDecision.Action.TRANSFER) {
                outcomes.add(applyExit(schoolId, batch, decision, request));
                continue;
            }
            // The external batch always carries one explicit targetSessionId (it represents the
            // source-session -> target-session year-end transition as a whole and stays
            // @NotNull/@Valid at the controller). PASS_OUT, however, creates no target enrollment
            // at all, and StudentYearEndService.applyPassOut correctly rejects any target
            // session/class/section as meaningless for it — so the coordinator must adapt the
            // batch-level contract to the action-specific internal one here, never forwarding a
            // target for PASS_OUT regardless of what the batch or the individual decision carries.
            boolean passOut = decision.getAction() == StudentYearEndDecision.Action.PASS_OUT;
            StudentYearEndDecision.Request command = new StudentYearEndDecision.Request(
                    schoolId, decision.getStudentId(), batch.getSourceSessionId(),
                    passOut ? null : batch.getTargetSessionId(), decision.getExpectedSourceEnrollmentId(),
                    decision.getExpectedSourceClassId(), decision.getAction(),
                    passOut ? null : decision.getTargetClassId(), passOut ? null : decision.getTargetSectionId(), actor);
            try {
                StudentYearEndDecision.Result result = worker.apply(command);
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        result.outcome().name(), result.message(), result.sourceEnrollmentId(),
                        result.targetEnrollmentId(), result.targetEnrollmentStatus() == null
                                ? null : result.targetEnrollmentStatus().name(),
                        result.lifecycleFinalizationPending()));
            } catch (IllegalArgumentException | NoSuchElementException e) {
                outcomes.add(validationOutcome(decision.getStudentId(), e.getMessage()));
            } catch (IllegalStateException e) {
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        StudentYearEndDecision.Outcome.CONFLICT.name(), e.getMessage(),
                        decision.getExpectedSourceEnrollmentId(), null, null, false));
            } catch (Exception e) {
                log.error("Year-end decision failed: schoolId={}, studentId={}, type={}",
                        schoolId, decision.getStudentId(), e.getClass().getSimpleName());
                outcomes.add(new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                        "VALIDATION_ERROR", "Decision could not be applied",
                        decision.getExpectedSourceEnrollmentId(), null, null, false));
            }
        }
    }

    static final String OUTCOME_PENDING = "PENDING";

    /** TRANSFER through the year-end exit path, one transaction per student. */
    private PromotionResultDTO.StudentOutcome applyExit(Long schoolId, PromotionDecisionRequest batch,
                                                        PromotionDecisionRequest.Decision decision,
                                                        HttpServletRequest request) {
        if (exitWorker == null) {
            return validationOutcome(decision.getStudentId(), "Transfer is not available");
        }
        try {
            return exitWorker.apply(schoolId, batch.getSourceSessionId(), decision, request);
        } catch (IllegalArgumentException | NoSuchElementException e) {
            return validationOutcome(decision.getStudentId(), e.getMessage());
        } catch (IllegalStateException e) {
            return new PromotionResultDTO.StudentOutcome(decision.getStudentId(),
                    StudentYearEndDecision.Outcome.CONFLICT.name(), e.getMessage(),
                    decision.getExpectedSourceEnrollmentId(), null, null, false);
        } catch (Exception e) {
            log.error("Year-end exit failed: schoolId={}, studentId={}, type={}",
                    schoolId, decision.getStudentId(), e.getClass().getSimpleName());
            return new PromotionResultDTO.StudentOutcome(decision.getStudentId(), "VALIDATION_ERROR",
                    "Decision could not be applied", decision.getExpectedSourceEnrollmentId(), null, null, false);
        }
    }

    // ── Rollover run record (audit/reporting only) ──────────────────────────

    private StudentRolloverRun startRun(Long schoolId, PromotionDecisionRequest batch, String startedBy) {
        if (runs == null) return null;
        if (sessions.findByIdAndSchoolId(batch.getSourceSessionId(), schoolId).isEmpty()
                || sessions.findByIdAndSchoolId(batch.getTargetSessionId(), schoolId).isEmpty()) {
            throw new NoSuchElementException("Source or target session not found for school");
        }
        if (Objects.equals(batch.getSourceSessionId(), batch.getTargetSessionId())) {
            throw new IllegalArgumentException("Source and target sessions must differ");
        }
        if (batch.getClassId() != null && classes.findByIdAndSchoolId(batch.getClassId(), schoolId).isEmpty()) {
            throw new NoSuchElementException("Class not found for school");
        }
        StudentRolloverRun run = new StudentRolloverRun();
        run.setSchoolId(schoolId);
        run.setSourceSessionId(batch.getSourceSessionId());
        run.setTargetSessionId(batch.getTargetSessionId());
        run.setClassId(batch.getClassId());
        run.setStartedBy(startedBy);
        run.setStartedAt(java.time.LocalDateTime.now());
        run.setStatus(StudentRolloverRun.Status.RUNNING);
        run.setTotalStudents(batch.getDecisions() == null ? 0 : batch.getDecisions().size());
        return runs.saveAndFlush(run);
    }

    private StudentRolloverRun finishRun(StudentRolloverRun run, List<PromotionResultDTO.StudentOutcome> outcomes,
                                         StudentRolloverRun.Status forced) {
        if (run == null) return null;
        Map<String, Long> byCode = outcomes.stream().collect(Collectors.groupingBy(
                PromotionResultDTO.StudentOutcome::code, Collectors.counting()));
        java.util.function.ToIntFunction<String> n = code -> byCode.getOrDefault(code, 0L).intValue();
        run.setPromotedCount(n.applyAsInt(StudentYearEndDecision.Outcome.PROMOTED.name()));
        run.setDetainedCount(n.applyAsInt(StudentYearEndDecision.Outcome.DETAINED.name()));
        run.setPassOutCount(n.applyAsInt(StudentYearEndDecision.Outcome.PASSED_OUT.name()));
        run.setTransferCount(n.applyAsInt("TRANSFERRED"));
        run.setPendingCount(n.applyAsInt(OUTCOME_PENDING));
        run.setAlreadyAppliedCount(n.applyAsInt(StudentYearEndDecision.Outcome.ALREADY_APPLIED.name()));
        int failed = n.applyAsInt(StudentYearEndDecision.Outcome.CONFLICT.name())
                + n.applyAsInt(StudentYearEndDecision.Outcome.INVALID_SOURCE.name())
                + n.applyAsInt("VALIDATION_ERROR");
        run.setFailedCount(failed);
        run.setFinishedAt(java.time.LocalDateTime.now());
        run.setStatus(forced != null ? forced
                : failed > 0 ? StudentRolloverRun.Status.COMPLETED_WITH_ERRORS : StudentRolloverRun.Status.COMPLETED);
        try {
            return runs.saveAndFlush(run);
        } catch (RuntimeException e) {
            // The decisions are already applied; a failure to update the audit row must not hide them.
            log.error("Could not finalize rollover run {}: {}", run.getId(), e.getMessage());
            return run;
        }
    }

    static PromotionResultDTO.RunSummary runSummary(StudentRolloverRun r) {
        if (r == null) return null;
        return new PromotionResultDTO.RunSummary(r.getId(), r.getStatus().name(), r.getSourceSessionId(),
                r.getTargetSessionId(), r.getClassId(), r.getStartedBy(), r.getStartedAt(), r.getFinishedAt(),
                r.getTotalStudents(), r.getPromotedCount(), r.getDetainedCount(), r.getPassOutCount(),
                r.getTransferCount(), r.getPendingCount(), r.getAlreadyAppliedCount(),
                r.getFailedCount());
    }

    /** Recent rollover runs into a target session (newest first). */
    @Transactional(readOnly = true)
    public List<PromotionResultDTO.RunSummary> recentRuns(Long targetSessionId) {
        if (runs == null) return List.of();
        Long schoolId = security.getSchoolId();
        return runs.findTop20BySchoolIdAndTargetSessionIdOrderByStartedAtDescIdDesc(schoolId, targetSessionId)
                .stream().map(StudentPromotionService::runSummary).toList();
    }

    @Transactional
    public int fixOrphanedSections() {
        return students.clearOrphanedSections(security.getSchoolId());
    }

    private Candidate previewCandidate(
            List<StudentEnrollment> sourceHistory, List<StudentEnrollment> targetHistory,
            Student student, List<SchoolClass> sequence, Long schoolId,
            StudentEnrollmentStatus proposedStatus) {
        StudentEnrollment source = selectYearEndSource(sourceHistory);
        List<Issue> errors = new ArrayList<>();
        List<Issue> warnings = new ArrayList<>();
        if (student == null) errors.add(issue("STUDENT_NOT_FOUND", "Student row does not exist in this school"));

        int index = -1;
        for (int i=0;i<sequence.size();i++) if (Objects.equals(sequence.get(i).getId(), source.getClassId())) index=i;
        boolean finalClass = index >= 0 && index == sequence.size()-1;
        SchoolClass sourceClass = index < 0 ? null : sequence.get(index);
        SchoolClass promoteTarget = index >= 0 && !finalClass ? sequence.get(index+1) : null;
        if (sourceClass == null) errors.add(issue("INVALID_SOURCE_CLASS", "Source class is not in the active class sequence"));

        boolean initial = source.getStatus() == StudentEnrollmentStatus.ACTIVE && source.getEffectiveUntil() == null;
        String appliedState = appliedState(source, targetHistory);
        if (!initial && "NOT_APPLIED".equals(appliedState)) {
            errors.add(issue("INVALID_SOURCE", "Source enrollment is not open and ACTIVE"));
        }
        if (initial && !targetHistory.isEmpty()) {
            errors.add(issue("CONFLICT", "Target-session enrollment already exists"));
            appliedState = "CONFLICT";
        }

        boolean promoteSectionRequired = promoteTarget != null && !sections
                .findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(schoolId, promoteTarget.getId(), true)
                .isEmpty();
        if (promoteSectionRequired && initial) {
            warnings.add(issue("TARGET_SECTION_SELECTION_REQUIRED",
                    "PROMOTE requires an explicit target section"));
        }
        Long detainSection = null;
        if (sourceClass != null && source.getSectionId() != null) {
            detainSection = sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(
                            schoolId, sourceClass.getId(), true).stream()
                    .anyMatch(s -> Objects.equals(s.getId(), source.getSectionId()))
                    ? source.getSectionId() : null;
        }
        if (student != null && (!Objects.equals(student.getClassId(), source.getClassId())
                || !Objects.equals(student.getSectionId(), source.getSectionId()))) {
            warnings.add(issue("PROJECTION_DIFFERS", "Student projection differs from authoritative source enrollment"));
        }

        List<StudentYearEndDecision.Action> available = finalClass
                ? List.of(StudentYearEndDecision.Action.DETAIN, StudentYearEndDecision.Action.PASS_OUT)
                : List.of(StudentYearEndDecision.Action.PROMOTE, StudentYearEndDecision.Action.DETAIN);
        return new Candidate(source.getStudentId(), student == null ? null : student.getName(),
                source.getId(), source.getAcademicSessionId(), source.getClassId(), source.getClassNameSnapshot(),
                source.getSectionId(), source.getSectionNameSnapshot(), available,
                finalClass ? StudentYearEndDecision.Action.PASS_OUT : StudentYearEndDecision.Action.PROMOTE,
                promoteTarget == null ? null : promoteTarget.getId(),
                promoteTarget == null ? null : promoteTarget.getName(),
                sourceClass == null ? null : sourceClass.getId(), source.getClassNameSnapshot(),
                promoteSectionRequired, null, detainSection, proposedStatus,
                List.copyOf(errors), List.copyOf(warnings), appliedState, null);
    }

    private StudentEnrollment selectYearEndSource(List<StudentEnrollment> history) {
        return history.stream().filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE
                        && e.getEffectiveUntil() == null).findFirst()
                .orElseGet(() -> history.stream()
                        .filter(e -> e.getStatus() == StudentEnrollmentStatus.CLOSED)
                        .filter(e -> e.getClosureReason() == StudentEnrollmentClosureReason.SESSION_COMPLETED
                                || e.getClosureReason() == StudentEnrollmentClosureReason.GRADUATED
                                || e.getClosureReason() == StudentEnrollmentClosureReason.TRANSFERRED
                                || e.getClosureReason() == StudentEnrollmentClosureReason.WITHDRAWN)
                        .findFirst().orElse(history.getLast()));
    }

    private String appliedState(StudentEnrollment source, List<StudentEnrollment> targetHistory) {
        if (source.getStatus() != StudentEnrollmentStatus.CLOSED) return "NOT_APPLIED";
        if (source.getClosureReason() == StudentEnrollmentClosureReason.GRADUATED) return "ALREADY_APPLIED:PASS_OUT";
        // Year-end (or earlier) exits: shown as recorded rather than as a conflict.
        if (source.getClosureReason() == StudentEnrollmentClosureReason.TRANSFERRED) return "ALREADY_APPLIED:TRANSFER";
        if (source.getClosureReason() == StudentEnrollmentClosureReason.WITHDRAWN) return "ALREADY_APPLIED:WITHDRAW";
        if (source.getClosureReason() != StudentEnrollmentClosureReason.SESSION_COMPLETED || targetHistory.size() != 1)
            return "CONFLICT";
        return Objects.equals(source.getClassId(), targetHistory.getFirst().getClassId())
                ? "ALREADY_APPLIED:DETAIN" : "ALREADY_APPLIED:PROMOTE";
    }

    private List<Issue> validateSessionPair(Long schoolId, Long sourceId, Long targetId) {
        List<Issue> errors = new ArrayList<>();
        if (sourceId == null) errors.add(issue("SOURCE_SESSION_REQUIRED", "sourceSessionId is required"));
        if (targetId == null) errors.add(issue("TARGET_SESSION_REQUIRED", "targetSessionId is required"));
        if (!errors.isEmpty()) return errors;
        Optional<AcademicSession> source = sessions.findByIdAndSchoolId(sourceId, schoolId);
        Optional<AcademicSession> target = sessions.findByIdAndSchoolId(targetId, schoolId);
        if (source.isEmpty()) errors.add(issue("SOURCE_SESSION_NOT_FOUND", "Source session not found for school"));
        if (target.isEmpty()) errors.add(issue("TARGET_SESSION_NOT_FOUND", "Target session not found for school"));
        if (!errors.isEmpty()) return errors;
        if (Objects.equals(sourceId, targetId)) errors.add(issue("SESSIONS_MUST_DIFFER", "Source and target sessions must differ"));
        if (!target.orElseThrow().getStartDate().equals(source.orElseThrow().getEndDate().plusDays(1)))
            errors.add(issue("SESSIONS_NOT_CONTIGUOUS", "Target session must start the day after source session ends"));
        School school = schools.findById(schoolId).orElse(null);
        if (school == null) errors.add(issue("SCHOOL_NOT_FOUND", "School not found"));
        else if (LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school))).isAfter(target.orElseThrow().getEndDate()))
            errors.add(issue("TARGET_SESSION_ENDED", "Target session has already ended"));
        return errors;
    }

    private List<UncoveredStudent> uncoveredForFilter(Long schoolId, String studentId, Set<String> covered) {
        if (studentId == null || studentId.isBlank() || covered.contains(studentId)) return List.of();
        return students.findByStudentIdAndSchoolId(studentId, schoolId)
                .map(s -> List.of(new UncoveredStudent(s.getStudentId(), s.getName(),
                        "INVALID_SOURCE", "No authoritative source enrollment exists for the selected session")))
                .orElse(List.of());
    }

    private Issue issue(String code, String message) { return new Issue(code, message); }
    private PromotionResultDTO.StudentOutcome validationOutcome(String studentId, String message) {
        return new PromotionResultDTO.StudentOutcome(studentId, "VALIDATION_ERROR",
                message == null ? "Validation failed" : message, null, null, null, false);
    }
}
