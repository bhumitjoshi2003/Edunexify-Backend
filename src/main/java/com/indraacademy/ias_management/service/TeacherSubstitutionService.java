package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Date-specific teacher substitution (cover). It never changes the permanent timetable: a cover
 * is a separate record for one date and one timetable period.
 *
 * <p>Phase 2: a substitute must be free <i>and</i> available that day (not on approved leave, not
 * ABSENT / ON_LEAVE); suggestions are ranked by fixed, explainable rules; holidays and closed days
 * need no cover; past dates can't be assigned or changed; covers whose original teacher is
 * available again are shown as "no longer needed" (kept active until an admin removes them); and
 * cancelling records who cancelled without overwriting who assigned.
 */
@Service
public class TeacherSubstitutionService {

    private static final Logger log = LoggerFactory.getLogger(TeacherSubstitutionService.class);
    /** Reuses the existing TIMETABLE_EDIT permission — substitution assignment is, in effect,
     *  editing who teaches a timetable slot. SUB_ADMIN is NOT granted this by default (see
     *  PermissionSeeder), so an unmodified SUB_ADMIN can view uncovered periods/free teachers
     *  (covered by their default TIMETABLE_VIEW) but cannot assign/change/cancel substitutions
     *  unless a school admin has explicitly granted TIMETABLE_EDIT via the role-permission
     *  matrix — the smallest dedicated authorization, not a blanket SUB_ADMIN grant. */
    private static final String TIMETABLE_EDIT_PERMISSION = "TIMETABLE_EDIT";
    static final String NEEDS_SUBSTITUTE = "NEEDS_SUBSTITUTE";
    static final String COVERED = "COVERED";
    static final String NO_LONGER_NEEDED = "NO_LONGER_NEEDED";
    private static final List<String> DEFAULT_WORKING_DAYS =
            List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY");

    private final TeacherSubstitutionRepository substitutions;
    private final TimetableRepository timetables;
    private final TeacherRepository teachers;
    private final TeacherLeaveRepository leaves;
    private final TeacherAttendanceRepository attendance;
    private final TimetableSessionAccessService sessions;
    private final SecurityUtil security;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PermissionService permissions;
    // Phase 2 collaborators — optional only so narrow unit tests can build this service.
    @Autowired(required = false) private SchoolRepository schools;
    @Autowired(required = false) private SchoolHolidayRepository holidays;
    @Autowired(required = false) private Clock clock;
    @Autowired(required = false) private PlatformTransactionManager transactionManager;

    public TeacherSubstitutionService(TeacherSubstitutionRepository substitutions, TimetableRepository timetables,
            TeacherRepository teachers, TeacherLeaveRepository leaves, TeacherAttendanceRepository attendance,
            TimetableSessionAccessService sessions, SecurityUtil security, AuditService audit,
            ApplicationEventPublisher events, PermissionService permissions) {
        this.substitutions = substitutions;
        this.timetables = timetables;
        this.teachers = teachers;
        this.leaves = leaves;
        this.attendance = attendance;
        this.sessions = sessions;
        this.security = security;
        this.audit = audit;
        this.events = events;
        this.permissions = permissions;
    }

    /** Enforced only on the mutating operations (assign/change/cancel) — uncovered()/freeTeachers()
     *  remain read-only and stay behind the coarse role check alone, matching SUB_ADMIN's default
     *  TIMETABLE_VIEW grant. ADMIN is always allowed. SUB_ADMIN must hold TIMETABLE_EDIT explicitly;
     *  this fails CLOSED — if the permission lookup itself throws, the mutation is denied and the
     *  error is logged, rather than letting an under-permissioned SUB_ADMIN through on an outage. */
    private void requireTimetableEditPermission() {
        String role = security.getRole();
        if ("ADMIN".equals(role)) {
            return;
        }
        try {
            List<String> keys = permissions.getPermissionKeysForRole(role, security.getSchoolId());
            if (keys == null || !keys.contains(TIMETABLE_EDIT_PERMISSION)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Your role does not have permission to manage teacher substitutions.");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not resolve permissions for role {}; denying substitution mutation: {}", role, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role does not have permission to manage teacher substitutions.");
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /** Periods needing cover, covered, or no longer needed (kept for existing callers). */
    @Transactional(readOnly = true)
    public List<UncoveredPeriod> uncovered(LocalDate date) {
        return overview(date).periods();
    }

    /** The admin's day: every affected period with its state, suggestions and the cover workload. */
    @Transactional(readOnly = true)
    public DayOverview overview(LocalDate date) {
        requireDate(date);
        Long schoolId = security.getSchoolId();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        List<TeacherSubstitution> active = new ArrayList<>(
                substitutions.findBySchoolIdAndDateAndStatus(schoolId, date, TeacherSubstitutionStatus.ACTIVE));
        String closed = closedReason(schoolId, date);
        if (session == null) return dayOverview(date, closed, List.of(), active);

        List<TimetableEntry> sessionEntries = timetables.findByAcademicSessionIdAndSchoolId(session.getId(), schoolId);
        Day day = dayOf(date);
        List<TimetableEntry> dayEntries = sessionEntries.stream()
                .filter(e -> e.getDay() == day && e.getTeacherId() != null).toList();
        Map<Long, TimetableEntry> entryById = dayEntries.stream()
                .collect(Collectors.toMap(TimetableEntry::getId, Function.identity(), (a, b) -> a));
        Map<Long, TeacherSubstitution> activeByEntry = active.stream()
                .collect(Collectors.toMap(TeacherSubstitution::getTimetableEntryId, Function.identity(), (a, b) -> a));

        List<UncoveredPeriod> periods = new ArrayList<>();
        if (closed != null) {
            // The school is closed: nothing needs cover; any active cover is shown as no longer needed.
            for (TeacherSubstitution s : active) periods.add(stale(s, entryById.get(s.getTimetableEntryId())));
        } else {
            Map<String, Unavailability> unavailable = unavailability(schoolId, date);
            List<Teacher> eligible = teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId);
            for (TimetableEntry e : dayEntries) {
                Unavailability why = unavailable.get(e.getTeacherId());
                if (why == null) continue;
                TeacherSubstitution assignment = activeByEntry.get(e.getId());
                List<FreeTeacher> free = rankFree(e, eligible, sessionEntries, dayEntries, active, unavailable.keySet(), null);
                periods.add(new UncoveredPeriod(e.getId(), e.getTeacherId(),
                        e.getTeacherName() == null ? e.getTeacherId() : e.getTeacherName(), e.getClassName(),
                        e.getSectionName(), e.getSubjectName(), e.getPeriodNumber(), e.getStartTime(), e.getEndTime(),
                        assignment == null ? null : Assignment.from(assignment), free,
                        assignment == null ? NEEDS_SUBSTITUTE : COVERED, why.reason(), why.leaveStart(), why.leaveEnd(),
                        free.isEmpty() ? null : free.get(0)));
            }
            // Active covers whose original teacher is available again (leave cancelled/reversed,
            // attendance corrected, timetable changed): never removed silently.
            Set<Long> listed = periods.stream().map(UncoveredPeriod::timetableEntryId).collect(Collectors.toSet());
            for (TeacherSubstitution s : active) {
                if (!listed.contains(s.getTimetableEntryId())) periods.add(stale(s, entryById.get(s.getTimetableEntryId())));
            }
        }
        periods.sort(Comparator.comparing(UncoveredPeriod::periodNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(UncoveredPeriod::className, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return dayOverview(date, closed, periods, active);
    }

    private DayOverview dayOverview(LocalDate date, String closed, List<UncoveredPeriod> periods, List<TeacherSubstitution> active) {
        Map<String, WorkloadRow> workload = new LinkedHashMap<>();
        for (TeacherSubstitution s : active) {
            WorkloadRow row = workload.get(s.getSubstituteTeacherId());
            workload.put(s.getSubstituteTeacherId(), new WorkloadRow(s.getSubstituteTeacherId(), s.getSubstituteTeacherName(),
                    row == null ? 1 : row.covers() + 1));
        }
        List<WorkloadRow> rows = workload.values().stream()
                .sorted(Comparator.comparingInt(WorkloadRow::covers).reversed()
                        .thenComparing(WorkloadRow::teacherName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        int needing = (int) periods.stream().filter(p -> NEEDS_SUBSTITUTE.equals(p.state())).count();
        int covered = (int) periods.stream().filter(p -> COVERED.equals(p.state())).count();
        int stale = (int) periods.stream().filter(p -> NO_LONGER_NEEDED.equals(p.state())).count();
        return new DayOverview(date, closed, List.copyOf(periods), rows, needing, covered, stale);
    }

    private static UncoveredPeriod stale(TeacherSubstitution s, TimetableEntry entry) {
        return new UncoveredPeriod(s.getTimetableEntryId(), s.getOriginalTeacherId(), s.getOriginalTeacherName(),
                entry != null ? entry.getClassName() : s.getClassName(), entry != null ? entry.getSectionName() : s.getSectionName(),
                s.getSubjectName(), s.getPeriodNumber(), s.getStartTime(), s.getEndTime(), Assignment.from(s), List.of(),
                NO_LONGER_NEEDED, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<FreeTeacher> freeTeachers(Long timetableEntryId, LocalDate date) {
        requireDate(date);
        Long schoolId = security.getSchoolId();
        TimetableEntry entry = timetables.findByIdAndSchoolId(timetableEntryId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        requireEntryMatchesDate(entry, date);
        List<TimetableEntry> sessionEntries = timetables.findByAcademicSessionIdAndSchoolId(entry.getAcademicSessionId(), schoolId);
        List<TimetableEntry> dayEntries = sessionEntries.stream().filter(e -> e.getDay() == entry.getDay()).toList();
        List<TeacherSubstitution> active = substitutions.findBySchoolIdAndDateAndStatus(
                schoolId, date, TeacherSubstitutionStatus.ACTIVE);
        return rankFree(entry, teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, schoolId), sessionEntries, dayEntries,
                active, unavailability(schoolId, date).keySet(), null);
    }

    /**
     * "Fill all with suggested" preview (nothing is saved): for every period still needing cover,
     * the best-ranked teacher who is not already proposed for another class in the same period.
     * Proposals are made period by period in order, and each proposal counts towards that
     * teacher's cover workload for the ones after it.
     */
    @Transactional(readOnly = true)
    public FillPreview fillPreview(LocalDate date) {
        DayOverview day = overview(date);
        List<FillProposal> proposals = new ArrayList<>();
        List<UncoveredPeriod> unfillable = new ArrayList<>();
        Map<Integer, Set<String>> proposedByPeriod = new HashMap<>();
        Map<String, Integer> extraCovers = new HashMap<>();
        for (UncoveredPeriod p : day.periods()) {
            if (!NEEDS_SUBSTITUTE.equals(p.state())) continue;
            Set<String> taken = proposedByPeriod.computeIfAbsent(p.periodNumber(), k -> new HashSet<>());
            Optional<FreeTeacher> pick = p.freeTeachers().stream()
                    .filter(t -> !taken.contains(t.teacherId()))
                    .min(Comparator.comparing((FreeTeacher t) -> !t.sameSubject())
                            .thenComparing(t -> !t.knowsClass())
                            .thenComparingInt(t -> t.coveringToday() + extraCovers.getOrDefault(t.teacherId(), 0))
                            .thenComparing(FreeTeacher::backToBack)
                            .thenComparing(FreeTeacher::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            if (pick.isEmpty()) {
                unfillable.add(p);
                continue;
            }
            FreeTeacher t = pick.get();
            taken.add(t.teacherId());
            extraCovers.merge(t.teacherId(), 1, Integer::sum);
            proposals.add(new FillProposal(p.timetableEntryId(), p.periodNumber(), p.className(), p.sectionName(),
                    p.subjectName(), p.originalTeacherName(), t.teacherId(), t.name(), t.reasons()));
        }
        return new FillPreview(date, List.copyOf(proposals), List.copyOf(unfillable));
    }

    // ── Mutations ────────────────────────────────────────────────────────

    @Transactional
    public Assignment assign(UpsertRequest request, HttpServletRequest http) {
        requireTimetableEditPermission();
        requireDate(request.date());
        Long schoolId = security.getSchoolId();
        requireAssignableDate(schoolId, request.date());
        TimetableEntry initial = timetables.findByIdAndSchoolId(request.timetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TimetableEntry entry = timetables.lockById(request.timetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Timetable entry not found."));
        requireCurrentSessionEntry(entry, schoolId);
        requireEntryMatchesDate(entry, request.date());
        Map<String, Unavailability> unavailable = unavailability(schoolId, request.date());
        Unavailability why = unavailable.get(entry.getTeacherId());
        if (why == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The original teacher is not unavailable on this date.");
        }
        if (substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(schoolId, request.date(), entry.getId(),
                TeacherSubstitutionStatus.ACTIVE).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This period already has an active substitute.");
        }
        Teacher substitute = requireFreeSubstitute(entry, request.date(), request.substituteTeacherId(), null, unavailable.keySet());
        TeacherSubstitution value = new TeacherSubstitution();
        value.setSchoolId(schoolId);
        value.setAcademicSessionId(entry.getAcademicSessionId());
        value.setDate(request.date());
        value.setTimetableEntryId(entry.getId());
        value.setOriginalTeacherId(entry.getTeacherId());
        value.setOriginalTeacherName(entry.getTeacherName() == null ? entry.getTeacherId() : entry.getTeacherName());
        value.setSubstituteTeacherId(substitute.getTeacherId());
        value.setSubstituteTeacherName(substitute.getName());
        value.setClassName(entry.getClassName());
        value.setSectionName(entry.getSectionName());
        value.setSubjectName(entry.getSubjectName());
        value.setPeriodNumber(entry.getPeriodNumber());
        value.setStartTime(entry.getStartTime());
        value.setEndTime(entry.getEndTime());
        value.setAssignedBy(security.getUsername());
        value.setNote(cleanNote(request.note()));
        value.setReasonSource("APPROVED_LEAVE".equals(why.reason()) ? "LEAVE" : "ABSENCE");
        try {
            value = substitutions.saveAndFlush(value);
        } catch (DataIntegrityViolationException race) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This period was assigned by another administrator.", race);
        }
        audit.log(security.getUsername(), security.getRole(), "ASSIGN_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(value.getId()), null, summary(value), http.getRemoteAddr());
        publish(value, value.getSubstituteTeacherId(), "ASSIGNED");
        publish(value, value.getOriginalTeacherId(), "ORIGINAL_ASSIGNED");
        return Assignment.from(value);
    }

    @Transactional
    public Assignment change(Long id, ChangeRequest request, HttpServletRequest http) {
        requireTimetableEditPermission();
        Long schoolId = security.getSchoolId();
        TeacherSubstitution initial = substitutions.findById(id)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TeacherSubstitution value = substitutions.lockByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        if (value.getStatus() != TeacherSubstitutionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A cancelled substitution cannot be changed.");
        }
        requireAssignableDate(schoolId, value.getDate());
        if (Objects.equals(value.getSubstituteTeacherId(), request.substituteTeacherId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This teacher is already assigned.");
        }
        TimetableEntry entry = timetables.findByIdAndSchoolId(value.getTimetableEntryId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The timetable period no longer exists."));
        Map<String, Unavailability> unavailable = unavailability(schoolId, value.getDate());
        if (!unavailable.containsKey(entry.getTeacherId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The original teacher is available again, so this cover is no longer needed. Remove it instead.");
        }
        Teacher replacement = requireFreeSubstitute(entry, value.getDate(), request.substituteTeacherId(), value.getId(),
                unavailable.keySet());
        String oldTeacher = value.getSubstituteTeacherId();
        String old = summary(value);
        value.setSubstituteTeacherId(replacement.getTeacherId());
        value.setSubstituteTeacherName(replacement.getName());
        value.setAssignedBy(security.getUsername());
        if (request.note() != null) value.setNote(cleanNote(request.note()));
        value = substitutions.saveAndFlush(value);
        audit.log(security.getUsername(), security.getRole(), "CHANGE_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(id), old, summary(value), http.getRemoteAddr());
        publish(value, oldTeacher, "CANCELLED");
        publish(value, value.getSubstituteTeacherId(), "ASSIGNED");
        publish(value, value.getOriginalTeacherId(), "ORIGINAL_ASSIGNED");
        return Assignment.from(value);
    }

    /** Removes a cover. Keeps who assigned it; records who cancelled it and when. */
    @Transactional
    public Assignment cancel(Long id, HttpServletRequest http) {
        requireTimetableEditPermission();
        Long schoolId = security.getSchoolId();
        TeacherSubstitution initial = substitutions.findById(id)
                .filter(s -> schoolId.equals(s.getSchoolId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        sessions.lockOwnedSession(schoolId, initial.getAcademicSessionId());
        TeacherSubstitution value = substitutions.lockByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Substitution not found."));
        if (value.getStatus() != TeacherSubstitutionStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Substitution is already cancelled.");
        }
        if (value.getDate().isBefore(today(schoolId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Past substitutions are read-only.");
        }
        String old = summary(value);
        value.setStatus(TeacherSubstitutionStatus.CANCELLED);
        value.setCancelledBy(security.getUsername());
        value.setCancelledAt(LocalDateTime.now());
        value = substitutions.saveAndFlush(value);
        audit.log(security.getUsername(), security.getRole(), "CANCEL_TEACHER_SUBSTITUTION", "TeacherSubstitution",
                String.valueOf(id), old, summary(value), http.getRemoteAddr());
        publish(value, value.getSubstituteTeacherId(), "CANCELLED");
        return Assignment.from(value);
    }

    /**
     * Saves confirmed "Fill all with suggested" items one by one, each in its own transaction and
     * through the same checks as a single assignment. Every item is reported — assigned, conflict
     * (e.g. someone else just covered it, or the teacher is no longer free) or failed.
     */
    public BulkResult assignMany(BulkRequest request, HttpServletRequest http) {
        requireTimetableEditPermission();
        if (request == null || request.date() == null || request.items() == null || request.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to assign.");
        }
        TransactionTemplate tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (tx != null) tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<BulkOutcome> outcomes = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (BulkItem item : request.items()) {
            if (!seen.add(item.timetableEntryId())) {
                outcomes.add(new BulkOutcome(item.timetableEntryId(), "FAILED", "The same period appears twice.", null));
                continue;
            }
            UpsertRequest single = new UpsertRequest(item.timetableEntryId(), request.date(), item.substituteTeacherId(), item.note());
            try {
                Assignment saved = tx == null ? assign(single, http) : tx.execute(status -> assign(single, http));
                outcomes.add(new BulkOutcome(item.timetableEntryId(), "ASSIGNED", "Assigned", saved));
            } catch (ResponseStatusException e) {
                boolean conflict = e.getStatusCode().value() == HttpStatus.CONFLICT.value();
                outcomes.add(new BulkOutcome(item.timetableEntryId(), conflict ? "CONFLICT" : "FAILED", e.getReason(), null));
            } catch (Exception e) {
                log.error("Bulk substitution item {} failed: {}", item.timetableEntryId(), e.getMessage());
                outcomes.add(new BulkOutcome(item.timetableEntryId(), "FAILED", "Could not be saved.", null));
            }
        }
        int assigned = (int) outcomes.stream().filter(o -> "ASSIGNED".equals(o.status())).count();
        int conflicts = (int) outcomes.stream().filter(o -> "CONFLICT".equals(o.status())).count();
        return new BulkResult(assigned, conflicts, outcomes.size() - assigned - conflicts, List.copyOf(outcomes));
    }

    // ── Teachers ─────────────────────────────────────────────────────────

    /** The caller's own cover periods for a date (the substitute's view). */
    @Transactional(readOnly = true)
    public List<Assignment> mine(LocalDate date) {
        requireDate(date);
        return substitutions.findBySchoolIdAndSubstituteTeacherIdAndDateAndStatusOrderByPeriodNumberAsc(
                security.getSchoolId(), security.getUsername(), date, TeacherSubstitutionStatus.ACTIVE)
                .stream().map(Assignment::from).toList();
    }

    /**
     * Read-only view for the caller's own periods on a day they are unavailable: each period and
     * who (if anyone) is covering it. Only ever the logged-in teacher's own timetable periods.
     */
    @Transactional(readOnly = true)
    public MyCoverage myCoverage(LocalDate date) {
        requireDate(date);
        Long schoolId = security.getSchoolId();
        String me = security.getUsername();
        AcademicSession session = sessions.currentSessionOrNull(schoolId);
        Unavailability why = unavailability(schoolId, date).get(me);
        if (session == null || closedReason(schoolId, date) != null) {
            return new MyCoverage(date, why != null, why == null ? null : why.reason(), List.of());
        }
        Day day = dayOf(date);
        Map<Long, TeacherSubstitution> activeByEntry = substitutions.findBySchoolIdAndDateAndStatus(
                        schoolId, date, TeacherSubstitutionStatus.ACTIVE).stream()
                .collect(Collectors.toMap(TeacherSubstitution::getTimetableEntryId, Function.identity(), (a, b) -> a));
        List<MyCoveragePeriod> periods = timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(session.getId(), me, schoolId)
                .stream().filter(e -> e.getDay() == day)
                .filter(e -> why != null || activeByEntry.containsKey(e.getId()))
                .sorted(Comparator.comparing(TimetableEntry::getPeriodNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> {
                    TeacherSubstitution s = activeByEntry.get(e.getId());
                    return new MyCoveragePeriod(e.getId(), e.getPeriodNumber(), e.getStartTime(), e.getEndTime(),
                            e.getClassName(), e.getSectionName(), e.getSubjectName(), s != null,
                            s == null ? null : s.getSubstituteTeacherName());
                }).toList();
        return new MyCoverage(date, why != null, why == null ? null : why.reason(), periods);
    }

    // ── Rules ────────────────────────────────────────────────────────────

    private Teacher requireFreeSubstitute(TimetableEntry entry, LocalDate date, String teacherId, Long ignoredAssignmentId,
                                          Set<String> unavailable) {
        Long schoolId = security.getSchoolId();
        if (Objects.equals(entry.getTeacherId(), teacherId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The original teacher cannot cover their own period.");
        }
        Teacher teacher = teachers.findByTeacherIdAndSchoolId(teacherId, schoolId)
                .filter(t -> t.getStatus() == TeacherStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Substitute teacher is not active or eligible."));
        if (unavailable.contains(teacherId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The selected teacher is on leave or absent on this date and can't cover a period.");
        }
        boolean scheduled = timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(
                entry.getAcademicSessionId(), teacherId, schoolId).stream()
                .anyMatch(e -> e.getDay() == entry.getDay() && Objects.equals(e.getPeriodNumber(), entry.getPeriodNumber()));
        boolean covering = substitutions.findBySchoolIdAndDateAndStatus(schoolId, date, TeacherSubstitutionStatus.ACTIVE)
                .stream().anyMatch(s -> !Objects.equals(s.getId(), ignoredAssignmentId)
                        && Objects.equals(s.getSubstituteTeacherId(), teacherId)
                        && Objects.equals(s.getPeriodNumber(), entry.getPeriodNumber()));
        if (scheduled || covering) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected teacher is already teaching during this period.");
        }
        return teacher;
    }

    /**
     * Free and available teachers for a period, ranked by fixed rules: same subject first, then
     * already teaches that class/section, then fewer covers already that day, then not back-to-back,
     * then name. Teachers on approved leave or ABSENT / ON_LEAVE that day are never included.
     */
    private List<FreeTeacher> rankFree(TimetableEntry entry, List<Teacher> candidates, List<TimetableEntry> sessionEntries,
                                       List<TimetableEntry> dayEntries, List<TeacherSubstitution> active,
                                       Set<String> unavailable, Long ignoredAssignmentId) {
        Integer period = entry.getPeriodNumber();
        Set<String> busy = dayEntries.stream()
                .filter(e -> Objects.equals(e.getPeriodNumber(), period))
                .map(TimetableEntry::getTeacherId).filter(Objects::nonNull).collect(Collectors.toSet());
        active.stream().filter(s -> Objects.equals(s.getPeriodNumber(), period))
                .filter(s -> !Objects.equals(s.getId(), ignoredAssignmentId))
                .map(TeacherSubstitution::getSubstituteTeacherId).forEach(busy::add);
        busy.add(entry.getTeacherId());

        Map<String, Integer> coversToday = new HashMap<>();
        Map<String, Set<Integer>> busyPeriods = new HashMap<>();
        for (TeacherSubstitution s : active) {
            coversToday.merge(s.getSubstituteTeacherId(), 1, Integer::sum);
            if (s.getPeriodNumber() != null) busyPeriods.computeIfAbsent(s.getSubstituteTeacherId(), k -> new HashSet<>()).add(s.getPeriodNumber());
        }
        for (TimetableEntry e : dayEntries) {
            if (e.getTeacherId() != null && e.getPeriodNumber() != null) {
                busyPeriods.computeIfAbsent(e.getTeacherId(), k -> new HashSet<>()).add(e.getPeriodNumber());
            }
        }
        Set<String> sameSubject = new HashSet<>();
        Set<String> knowsClass = new HashSet<>();
        for (TimetableEntry e : sessionEntries) {
            if (e.getTeacherId() == null) continue;
            if (e.getSubjectName() != null && e.getSubjectName().equalsIgnoreCase(entry.getSubjectName())) sameSubject.add(e.getTeacherId());
            if (Objects.equals(e.getClassName(), entry.getClassName()) && Objects.equals(e.getSectionName(), entry.getSectionName())) {
                knowsClass.add(e.getTeacherId());
            }
        }
        List<FreeTeacher> free = new ArrayList<>();
        for (Teacher t : candidates) {
            String id = t.getTeacherId();
            if (busy.contains(id) || unavailable.contains(id)) continue;
            boolean subject = sameSubject.contains(id);
            boolean known = knowsClass.contains(id);
            int covers = coversToday.getOrDefault(id, 0);
            Set<Integer> theirs = busyPeriods.getOrDefault(id, Set.of());
            boolean backToBack = period != null && (theirs.contains(period - 1) || theirs.contains(period + 1));
            List<String> reasons = new ArrayList<>();
            if (subject) reasons.add("SAME_SUBJECT");
            if (known) reasons.add("KNOWS_CLASS");
            if (covers > 0) reasons.add("COVERING_" + covers);
            if (backToBack) reasons.add("BACK_TO_BACK");
            free.add(new FreeTeacher(id, t.getName(), subject, known, covers, backToBack, List.copyOf(reasons)));
        }
        free.sort(Comparator.comparing((FreeTeacher f) -> !f.sameSubject())
                .thenComparing(f -> !f.knowsClass())
                .thenComparingInt(FreeTeacher::coveringToday)
                .thenComparing(FreeTeacher::backToBack)
                .thenComparing(FreeTeacher::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return List.copyOf(free);
    }

    /** Why a teacher is unavailable on a date. */
    record Unavailability(String reason, LocalDate leaveStart, LocalDate leaveEnd) {}

    /** Teachers unavailable on a date: approved leave first (with its dates), then ABSENT / ON_LEAVE attendance. */
    private Map<String, Unavailability> unavailability(Long schoolId, LocalDate date) {
        Map<String, Unavailability> result = new HashMap<>();
        for (TeacherLeave l : leaves.findApprovedOverlapping(schoolId, date, date)) {
            result.putIfAbsent(l.getTeacherId(), new Unavailability("APPROVED_LEAVE", l.getStartDate(), l.getEndDate()));
        }
        for (TeacherAttendance a : attendance.findBySchoolIdAndDate(schoolId, date)) {
            if ("ABSENT".equals(a.getStatus()) || "ON_LEAVE".equals(a.getStatus())) {
                result.putIfAbsent(a.getTeacherId(), new Unavailability(a.getStatus(), null, null));
            }
        }
        return result;
    }

    /** "School holiday: …" or "The school is closed on …", or null on a working day. */
    private String closedReason(Long schoolId, LocalDate date) {
        if (holidays != null) {
            for (SchoolHoliday h : holidays.findOverlapping(schoolId, date, date)) {
                if (h.isAffectsAll()) return "School holiday" + (h.getName() == null ? "" : ": " + h.getName());
            }
        }
        if (schools != null) {
            School school = schools.findById(schoolId).orElse(null);
            if (school != null && !workingDays(school.getWorkingDays()).contains(date.getDayOfWeek().name())) {
                String d = date.getDayOfWeek().name();
                return "The school is closed on " + d.charAt(0) + d.substring(1).toLowerCase(Locale.ROOT) + "s";
            }
        }
        return null;
    }

    private static Set<String> workingDays(String configured) {
        Set<String> days = new LinkedHashSet<>();
        if (configured != null) {
            for (String d : configured.split(",")) if (!d.isBlank()) days.add(d.trim().toUpperCase(Locale.ROOT));
        }
        if (days.isEmpty()) days.addAll(DEFAULT_WORKING_DAYS);
        return days;
    }

    /** Assign / change only for today or later, and only on a day the school is open. */
    private void requireAssignableDate(Long schoolId, LocalDate date) {
        if (date.isBefore(today(schoolId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Substitutions can't be assigned or changed for a past date.");
        }
        String closed = closedReason(schoolId, date);
        if (closed != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, closed + " — no substitution is needed.");
        }
    }

    private LocalDate today(Long schoolId) {
        Clock base = clock != null ? clock : Clock.systemDefaultZone();
        School school = schools == null ? null : schools.findById(schoolId).orElse(null);
        return LocalDate.now(school == null ? base : base.withZone(SchoolTimeUtil.zoneId(school)));
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) return null;
        String n = note.trim();
        if (n.length() > 300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The note can be at most 300 characters.");
        return n;
    }

    private void requireCurrentSessionEntry(TimetableEntry entry, Long schoolId) {
        AcademicSession current = sessions.currentSessionOrNull(schoolId);
        if (current == null || !Objects.equals(current.getId(), entry.getAcademicSessionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Substitutions require a current-session timetable period.");
        }
    }
    private void requireEntryMatchesDate(TimetableEntry entry, LocalDate date) {
        if (!entry.getDay().name().equals(dayName(date.getDayOfWeek()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timetable period does not occur on the selected date.");
        }
    }
    private void requireDate(LocalDate date) {
        if (date == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date is required.");
    }
    private String dayName(DayOfWeek day) { return day.name(); }
    /** The timetable day for a date, or null when the timetable has no such day (e.g. Sunday). */
    private static Day dayOf(LocalDate date) {
        try {
            return Day.valueOf(date.getDayOfWeek().name());
        } catch (IllegalArgumentException noSuchDay) {
            return null;
        }
    }
    private String summary(TeacherSubstitution s) {
        return "date=" + s.getDate() + ",period=" + s.getPeriodNumber() + ",substitute="
                + s.getSubstituteTeacherId() + ",status=" + s.getStatus()
                + (s.getCancelledBy() != null ? ",cancelledBy=" + s.getCancelledBy() : "");
    }
    private void publish(TeacherSubstitution s, String recipient, String kind) {
        events.publishEvent(new TeacherSubstitutionNotificationEvent(s.getSchoolId(), s.getId(), s.getRevision(),
                recipient, kind, s.getClassName(), s.getSectionName(), s.getSubjectName(), s.getPeriodNumber(),
                s.getStartTime(), s.getEndTime(), s.getOriginalTeacherName(), s.getDate(), security.getUsername(),
                s.getSubstituteTeacherName(), s.getNote()));
    }
}
