package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.StudentLeaveApplyRequest;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);
    /** Leave for today must be applied before this school-local time (the rule the app showed). */
    static final LocalTime SAME_DAY_CUTOFF = LocalTime.of(6, 0);
    static final int MAX_REASON_LENGTH = 500;
    private static final List<LeaveStatus> ACTIVE = List.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);
    private static final String VIEW_LEAVES_ROUTE = "/dashboard/view-leaves";

    @Autowired private LeaveRepository leaveRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private AuditService auditService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    // Leave Phase 1 collaborators — optional only so narrow slice tests can build this service.
    @Autowired(required = false) private SchoolRepository schoolRepository;
    @Autowired(required = false) private SchoolHolidayRepository schoolHolidayRepository;
    @Autowired(required = false) private AcademicSessionRepository academicSessionRepository;
    @Autowired(required = false) private StudentEnrollmentService studentEnrollmentService;
    @Autowired(required = false) private TeacherRepository teacherRepository;
    @Autowired(required = false) private Clock clock;

    // ── Apply ────────────────────────────────────────────────────────────

    /**
     * A student's (or an authorised parent's) leave request. Only the date and the reason come
     * from the caller; the student, their name, class and school come from the server, and the
     * status is always PENDING — the request can never approve itself or touch another row.
     * The date rules are enforced here, not only in the app.
     */
    @Transactional
    public Leave applyLeave(String studentId, StudentLeaveApplyRequest req, HttpServletRequest request) {
        if (studentId == null || studentId.isBlank()) throw new IllegalArgumentException("A student must be selected.");
        if (req == null || req.getLeaveDate() == null) throw new IllegalArgumentException("A leave date is required.");
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.isEmpty()) throw new IllegalArgumentException("A reason is required.");
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason can be at most " + MAX_REASON_LENGTH + " characters.");
        }
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found."));
        if (student.getStatus() != StudentStatus.ACTIVE) {
            throw new IllegalArgumentException("Leave can only be applied for an active student.");
        }
        School school = schoolRepository.findById(schoolId).orElseThrow(() -> new NoSuchElementException("School not found."));
        LocalDate date = req.getLeaveDate();
        AcademicSession session = validateLeaveDate(school, date);

        String className = student.getClassName();
        Long classId = student.getClassId();
        if (studentEnrollmentService != null) {
            Optional<StudentEnrollment> enrollment =
                    studentEnrollmentService.findEffectiveEnrollment(schoolId, studentId, session.getId(), date);
            if (enrollment.isPresent()) {
                className = enrollment.get().getClassNameSnapshot();
                classId = enrollment.get().getClassId();
            }
        }
        String leaveDate = date.toString();
        if (!leaveRepository.findByStudentIdAndLeaveDateAndSchoolIdAndStatusIn(studentId, leaveDate, schoolId, ACTIVE).isEmpty()) {
            throw new IllegalStateException("Leave has already been applied for " + leaveDate + ".");
        }

        Leave leave = new Leave();
        leave.setSchoolId(schoolId);
        leave.setStudentId(studentId);
        leave.setStudentName(student.getName() != null ? student.getName() : studentId);
        leave.setClassName(className);
        leave.setClassId(classId);
        leave.setLeaveDate(leaveDate);
        leave.setReason(reason);
        leave.setStatus(LeaveStatus.PENDING);
        leave.setAppliedDate(LocalDateTime.now());
        Leave saved;
        try {
            saved = leaveRepository.saveAndFlush(leave);
        } catch (DataIntegrityViolationException e) {
            // uq_leaves_active_student_date: a concurrent request for the same day won the race.
            throw new IllegalStateException("Leave has already been applied for " + leaveDate + ".");
        }
        audit("APPLY_LEAVE", saved, null, request);
        businessNotifications.studentAndParents(schoolId, studentId,
                NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS, NotificationEventCode.LEAVE_SUBMITTED,
                NotificationCategory.LEAVE, "Leave Applied",
                String.format("Your leave application for %s has been submitted.", leaveDate), "Leave",
                String.valueOf(saved.getId()), "/dashboard/apply-leave", securityUtil.getUsername(),
                "student-leave:" + saved.getId() + ":submitted", Set.of(ExternalDeliveryChannel.PUSH));
        notifyApprovers(saved, student);
        return saved;
    }

    /** Server-side date rules for a student leave; returns the academic session the date is in. */
    AcademicSession validateLeaveDate(School school, LocalDate date) {
        java.time.ZoneId zone = SchoolTimeUtil.zoneId(school);
        Clock base = clock != null ? clock : Clock.systemUTC();
        LocalDate today = LocalDate.now(base.withZone(zone));
        if (date.isBefore(today)) throw new IllegalArgumentException("Leave cannot be applied for a past date.");
        if (date.equals(today) && !java.time.LocalTime.now(base.withZone(zone)).isBefore(SAME_DAY_CUTOFF)) {
            throw new IllegalArgumentException("Leave for today must be applied before 6:00 AM.");
        }
        if (!workingDays(school.getWorkingDays()).contains(date.getDayOfWeek().name())) {
            throw new IllegalArgumentException("The school is closed on " + titleCase(date.getDayOfWeek().name())
                    + "s. Please choose a working day.");
        }
        if (schoolHolidayRepository != null && !schoolHolidayRepository.findOverlapping(school.getId(), date, date).isEmpty()) {
            throw new IllegalArgumentException(date + " is a school holiday.");
        }
        return academicSessionRepository.findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        school.getId(), date, date).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(date + " is not inside any academic session."));
    }

    private static Set<String> workingDays(String configured) {
        Set<String> days = new LinkedHashSet<>();
        if (configured != null) {
            for (String d : configured.split(",")) if (!d.isBlank()) days.add(d.trim().toUpperCase(Locale.ROOT));
        }
        // Schools that never configured working days keep the rule the app always applied (no Sundays).
        if (days.isEmpty()) days.addAll(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"));
        return days;
    }

    private static String titleCase(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * New request → the student's class teacher(s) (current class + section). When the class has
     * no class teacher, the school's admins are told instead, so the request never goes unseen.
     */
    private void notifyApprovers(Leave leave, Student student) {
        String message = String.format("%s (%s) applied for leave on %s.", leave.getStudentName(),
                leave.getStudentId(), leave.getLeaveDate());
        List<String> classTeachers = teacherRepository == null ? List.of() : (student.getSectionId() != null
                ? teacherRepository.findByClassTeacherAndClassTeacherSectionIdAndSchoolId(leave.getClassName(), student.getSectionId(), leave.getSchoolId())
                : teacherRepository.findByClassTeacherAndClassTeacherSectionIdIsNullAndSchoolId(leave.getClassName(), leave.getSchoolId()))
                .stream().map(Teacher::getTeacherId).filter(Objects::nonNull).distinct().toList();
        try {
            if (!classTeachers.isEmpty()) {
                for (String teacherId : classTeachers) {
                    businessNotifications.direct(leave.getSchoolId(), teacherId, NotificationEventCode.LEAVE_SUBMITTED,
                            NotificationCategory.LEAVE, "Leave request to review", message, "Leave",
                            String.valueOf(leave.getId()), VIEW_LEAVES_ROUTE, securityUtil.getUsername(),
                            "student-leave:" + leave.getId() + ":submitted:teacher:" + teacherId,
                            Set.of(ExternalDeliveryChannel.PUSH));
                }
            } else {
                businessNotifications.publish(leave.getSchoolId(), NotificationEventCode.LEAVE_SUBMITTED,
                        NotificationCategory.LEAVE, "Leave request to review", message,
                        new NotificationAudience(NotificationAudienceType.ROLE, Role.ADMIN), "Leave",
                        String.valueOf(leave.getId()), VIEW_LEAVES_ROUTE, securityUtil.getUsername(),
                        "student-leave:" + leave.getId() + ":submitted:admins", Set.of(ExternalDeliveryChannel.PUSH));
            }
        } catch (RuntimeException e) {
            // The request itself is saved; a notification problem must not undo it.
            log.warn("Could not notify approvers of leave {}: {}", leave.getId(), e.getMessage());
        }
    }

    // ── Decide / reverse ─────────────────────────────────────────────────

    /** First decision on a PENDING request (no reason). Kept for existing callers. */
    @Transactional
    public Leave updateLeaveStatus(Long leaveId, LeaveStatus status, HttpServletRequest request) {
        return decide(leaveId, status, null, request);
    }

    /**
     * The first decision on a request: PENDING → APPROVED or REJECTED only. A decision can't set a
     * request back to PENDING, and a decided or cancelled request can't be decided again — use
     * {@link #reverse} to change a decision.
     */
    @Transactional
    public Leave decide(Long leaveId, LeaveStatus status, String reason, HttpServletRequest request) {
        if (leaveId == null) throw new IllegalArgumentException("Leave ID must be provided.");
        if (status != LeaveStatus.APPROVED && status != LeaveStatus.REJECTED) {
            throw new IllegalArgumentException("A leave request can only be approved or rejected.");
        }
        Leave leave = lockAndAuthorize(leaveId);
        if (leave.getStatus() == status) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status);
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status,
                    "This leave request is already " + leave.getStatus() + ". Use \"Change decision\" to reverse a decision.");
        }
        return applyDecision(leave, status, reason, "UPDATE_LEAVE_STATUS", request);
    }

    /**
     * Explicit reversal of a decision: APPROVED → REJECTED or REJECTED → APPROVED, with a reason.
     * Attendance records are never changed; only the approved-leave indicator (and with it the
     * absence-charge waiver for that day) follows the new decision.
     */
    @Transactional
    public Leave reverse(Long leaveId, String reason, HttpServletRequest request) {
        if (leaveId == null) throw new IllegalArgumentException("Leave ID must be provided.");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required to change a decision.");
        Leave leave = lockAndAuthorize(leaveId);
        LeaveStatus target;
        if (leave.getStatus() == LeaveStatus.APPROVED) target = LeaveStatus.REJECTED;
        else if (leave.getStatus() == LeaveStatus.REJECTED) target = LeaveStatus.APPROVED;
        else throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), leave.getStatus(),
                    "Only an approved or rejected leave can be reversed (this one is " + leave.getStatus() + ").");
        if (target == LeaveStatus.APPROVED && leaveRepository.findByStudentIdAndLeaveDateAndSchoolIdAndStatusIn(
                        leave.getStudentId(), leave.getLeaveDate(), leave.getSchoolId(), ACTIVE).stream()
                .anyMatch(other -> !other.getId().equals(leave.getId()))) {
            throw new IllegalStateException("Another active leave already exists for " + leave.getLeaveDate() + ".");
        }
        return applyDecision(leave, target, reason, "REVERSE_LEAVE_DECISION", request);
    }

    private Leave applyDecision(Leave leave, LeaveStatus status, String reason, String auditAction, HttpServletRequest request) {
        LeaveStatus previousStatus = leave.getStatus();
        String oldValue = json(leave);
        leave.setStatus(status);
        leave.setDecidedBy(securityUtil.getUsername());
        leave.setDecidedAt(LocalDateTime.now());
        leave.setDecisionReason(trimReason(reason));
        Leave updated = leaveRepository.save(leave);
        audit(auditAction, updated, oldValue, request);
        String studentMessage = String.format("Your leave application for %s has been %s.", updated.getLeaveDate(),
                status.name().toLowerCase())
                + (updated.getDecisionReason() != null ? " Reason: " + updated.getDecisionReason() : "");
        businessNotifications.studentAndParents(updated.getSchoolId(), updated.getStudentId(),
                NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS,
                status == LeaveStatus.APPROVED ? NotificationEventCode.LEAVE_APPROVED : NotificationEventCode.LEAVE_REJECTED,
                NotificationCategory.LEAVE, "Leave Status Updated", studentMessage, "Leave",
                String.valueOf(updated.getId()), "/dashboard/apply-leave", securityUtil.getUsername(),
                "student-leave:" + updated.getId() + ":decision:" + previousStatus + ":" + status,
                Set.of(ExternalDeliveryChannel.PUSH));
        return updated;
    }

    /** Locked read (serializes racing decisions), school check and teacher class/section scope. */
    private Leave lockAndAuthorize(Long leaveId) {
        Long schoolId = securityUtil.getSchoolId();
        Leave leave = leaveRepository.findByIdForUpdate(leaveId)
                .orElseThrow(() -> new NoSuchElementException("Leave not found with ID " + leaveId));
        if (!schoolId.equals(leave.getSchoolId())) {
            throw new SecurityException("Access denied: leave does not belong to your school.");
        }
        if (Role.TEACHER.equals(securityUtil.getRole())) {
            Long studentSectionId = studentRepository.findByStudentIdAndSchoolId(leave.getStudentId(), schoolId)
                    .map(Student::getSectionId).orElse(null);
            TeacherClassScopeService.ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    securityUtil.getRole(), securityUtil.getUsername(), schoolId, leave.getClassName(), studentSectionId);
            if (!access.allowed()) throw new SecurityException(access.errorMessage());
        }
        return leave;
    }

    // ── Cancel (history is kept, never deleted) ──────────────────────────

    /**
     * Student / parent (or admin) cancelling the request for one day. A student or parent can
     * cancel only a PENDING request; an admin follows {@link #cancelById}'s rules.
     */
    @Transactional
    public Leave cancelForStudentDate(String studentId, String leaveDate, String reason, HttpServletRequest request) {
        if (studentId == null || studentId.isBlank() || leaveDate == null || leaveDate.isBlank()) {
            throw new IllegalArgumentException("Student ID and leave date must be provided.");
        }
        Long schoolId = securityUtil.getSchoolId();
        Leave active = leaveRepository.findByStudentIdAndLeaveDateAndSchoolIdAndStatusIn(studentId, leaveDate, schoolId, ACTIVE)
                .stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("No active leave request found for " + leaveDate + "."));
        return cancelById(active.getId(), reason, request);
    }

    /**
     * Cancels a request and keeps it as history. Students and parents: their own PENDING request
     * only. Admins: any PENDING request, or an APPROVED one with a reason — cancelling approved
     * leave removes that day's absence-charge waiver, so it must be deliberate and recorded.
     */
    @Transactional
    public Leave cancelById(Long leaveId, String reason, HttpServletRequest request) {
        if (leaveId == null) throw new IllegalArgumentException("Leave ID must be provided.");
        Long schoolId = securityUtil.getSchoolId();
        Leave leave = leaveRepository.findByIdForUpdate(leaveId)
                .orElseThrow(() -> new NoSuchElementException("Leave not found with ID " + leaveId));
        if (!schoolId.equals(leave.getSchoolId())) {
            throw new SecurityException("Access denied: leave does not belong to your school.");
        }
        String role = securityUtil.getRole();
        boolean admin = Role.ADMIN.equals(role);
        if (leave.getStatus() == LeaveStatus.CANCELLED) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), LeaveStatus.CANCELLED);
        }
        if (leave.getStatus() == LeaveStatus.REJECTED) {
            throw new IllegalStateException("A rejected leave request can't be cancelled.");
        }
        if (leave.getStatus() == LeaveStatus.APPROVED) {
            if (!admin) throw new IllegalStateException("An approved leave can't be cancelled here. Please contact the school.");
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("A reason is required to cancel an approved leave.");
            }
        }
        String oldValue = json(leave);
        leave.setStatus(LeaveStatus.CANCELLED);
        leave.setCancelledBy(securityUtil.getUsername());
        leave.setCancelledAt(LocalDateTime.now());
        leave.setCancellationReason(trimReason(reason));
        Leave saved = leaveRepository.save(leave);
        audit("CANCEL_LEAVE", saved, oldValue, request);
        businessNotifications.studentAndParents(saved.getSchoolId(), saved.getStudentId(),
                NotificationAudienceType.STUDENT_WITH_LEAVE_PARENTS, NotificationEventCode.LEAVE_CANCELLED,
                NotificationCategory.LEAVE, "Leave Cancelled",
                String.format("Your leave application for %s has been cancelled.", saved.getLeaveDate()), "Leave",
                String.valueOf(saved.getId()), "/dashboard/apply-leave", securityUtil.getUsername(),
                "student-leave:" + saved.getId() + ":cancelled", Set.of(ExternalDeliveryChannel.PUSH));
        return saved;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Leave> getLeaveById(Long leaveId) {
        if (leaveId == null) return Optional.empty();
        Long schoolId = securityUtil.getSchoolId();
        return leaveRepository.findById(leaveId).filter(l -> schoolId.equals(l.getSchoolId()));
    }

    @Transactional(readOnly = true)
    public Page<Leave> getLeavesFiltered(String className, String studentId, String date, LeaveStatus status, Pageable pageable) {
        return getLeavesFiltered(className, studentId, date, status, null, pageable);
    }

    /** Staff search (partial student-ID match is intended here). */
    @Transactional(readOnly = true)
    public Page<Leave> getLeavesFiltered(String className, String studentId, String date, LeaveStatus status, Long sectionId, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        try {
            return leaveRepository.findFilteredForManagement(schoolId, className, studentId, date, status, sectionId, pageable);
        } catch (DataAccessException e) {
            throw new RuntimeException("Could not retrieve filtered leaves due to data access issue", e);
        }
    }

    /** One student's own leave history — an EXACT student-ID match (never a partial search). */
    @Transactional(readOnly = true)
    public Page<Leave> getLeavesByStudentId(String studentId, Pageable pageable) {
        if (studentId == null || studentId.trim().isEmpty()) return Page.empty(pageable);
        return leaveRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId(), pageable);
    }

    @Transactional(readOnly = true)
    public List<String> getLeavesByDateAndClass(String date, String className) {
        return getLeavesByDateAndClass(date, className, null);
    }

    /** Students with APPROVED leave on a date in a class (Attendance V2: only approved leave counts). */
    @Transactional(readOnly = true)
    public List<String> getLeavesByDateAndClass(String date, String className, Long sectionId) {
        if (date == null || date.trim().isEmpty() || className == null || className.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return leaveRepository.findApprovedByLeaveDateAndClassNameAndSchoolId(date, className, securityUtil.getSchoolId(), sectionId);
    }

    /**
     * Leave requests awaiting a decision, for the AI Copilot's read-only tools and its decision
     * workflow. Oldest first, capped. {@code className} confines a TEACHER to their own class.
     */
    @Transactional(readOnly = true)
    public List<Leave> getLeavesForReview(String className, String studentId, int limit) {
        return getLeavesForReview(className, studentId, limit, null);
    }

    @Transactional(readOnly = true)
    public List<Leave> getLeavesForReview(String className, String studentId, int limit, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        int capped = Math.max(1, Math.min(limit, 100));
        return leaveRepository.findForReview(
                LeaveStatus.PENDING, schoolId,
                (className != null && !className.isBlank()) ? className : null,
                (studentId != null && !studentId.isBlank()) ? studentId : null,
                sectionId,
                PageRequest.of(0, capped));
    }

    @Transactional(readOnly = true)
    public List<Leave> getLeavesByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return leaveRepository.findByIdInAndSchoolId(ids, securityUtil.getSchoolId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String r = reason.trim();
        return r.length() > MAX_REASON_LENGTH ? r.substring(0, MAX_REASON_LENGTH) : r;
    }

    private String json(Leave leave) {
        try {
            return objectMapper.writeValueAsString(leave);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void audit(String action, Leave leave, String oldValue, HttpServletRequest request) {
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), action, "Leave",
                String.valueOf(leave.getId()), oldValue, json(leave), request == null ? null : request.getRemoteAddr());
    }
}
