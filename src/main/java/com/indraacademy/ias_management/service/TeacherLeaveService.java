package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherLeaveResponse;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * A teacher's own leave application → admin approval, the missing counterpart to
 * TeacherAttendance's admin-only ON_LEAVE marking.
 *
 * <p>Kept as its own service rather than folded into (or sharing a generic base with)
 * {@link LeaveService} — the same reasoning TeacherAttendanceService already documents for
 * staying separate from student AttendanceService: a parallel, independently-evolvable service
 * per domain, not a shared/polymorphic one. It reuses the actual reusable PIECES from the student
 * Leave hardening instead — {@link LeaveStatus} (no student-specific meaning to duplicate) and
 * {@link InvalidLeaveStatusTransitionException} (already fully generic) — without touching
 * LeaveService's own code, so nothing here can regress it.
 *
 * <p>The same same-status-repeat guard applies here as there: re-approving an already-approved
 * leave, or re-rejecting an already-rejected one, is refused as a no-op rather than silently
 * reapplied — see that exception's Javadoc for why (duplicate audit entries, duplicate
 * notifications). Reversal (APPROVED ↔ REJECTED) remains possible, matching the same product
 * decision made for student leave.
 */
@Service
public class TeacherLeaveService {
    static final String TEACHER_LEAVE_ROUTE = "/dashboard/apply-teacher-leave";
    static final String ADMIN_REVIEW_ROUTE = "/dashboard/teacher-leave-requests";

    private static final Logger log = LoggerFactory.getLogger(TeacherLeaveService.class);

    @Autowired private TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SchoolHolidayRepository schoolHolidayRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;
    @Autowired private BusinessNotificationService businessNotifications;
    @Autowired private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Autowired private Clock clock;

    @Transactional
    public TeacherLeaveResponse applyLeave(TeacherLeaveApplyRequest req, HttpServletRequest request) {
        if (req.getStartDate() == null || req.getEndDate() == null) {
            throw new IllegalArgumentException("Start date and end date are required.");
        }
        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new IllegalArgumentException("End date cannot be before start date.");
        }
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }

        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
        if (req.getStartDate().isBefore(today)) {
            throw new IllegalArgumentException("Leave cannot be applied for a past date.");
        }
        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(
                schoolId, req.getStartDate(), req.getEndDate());
        String teacherId = securityUtil.getUsername();
        long workingLeaveDays = countWorkingLeaveDays(teacherId, schoolId,
                req.getStartDate(), req.getEndDate(), school.getWorkingDays(), holidays);
        if (workingLeaveDays == 0) {
            throw new IllegalArgumentException(
                    "The selected dates contain no working days. Leave cannot be applied on closed days or school holidays.");
        }
        // teacherId is NEVER taken from the request body — always the authenticated caller,
        // the same principle applyLeave (student) already enforces for studentId. The teacher row
        // is locked so two simultaneous applications can't both pass the overlap check.
        Teacher teacher = teacherRepository.lockByTeacherIdAndSchoolId(teacherId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Teacher not found: " + teacherId));
        if (teacherLeaveRepository.existsActiveOverlapping(schoolId, teacherId, req.getStartDate(), req.getEndDate(), null)) {
            throw new IllegalStateException("You already have a pending or approved leave request overlapping these dates.");
        }

        TeacherLeave leave = new TeacherLeave();
        leave.setSchoolId(schoolId);
        leave.setTeacherId(teacherId);
        leave.setTeacherName(teacher.getName());
        leave.setStartDate(req.getStartDate());
        leave.setEndDate(req.getEndDate());
        leave.setReason(req.getReason());
        leave.setStatus(LeaveStatus.PENDING);

        TeacherLeave saved = teacherLeaveRepository.save(leave);
        log.info("Teacher {} applied for leave {} to {} (school {})", teacherId, saved.getStartDate(), saved.getEndDate(), schoolId);

        auditService.log(
                teacherId,
                securityUtil.getRole(),
                "APPLY_TEACHER_LEAVE",
                "TeacherLeave",
                String.valueOf(saved.getId()),
                null,
                "startDate=" + saved.getStartDate() + ",endDate=" + saved.getEndDate() + ",status=PENDING",
                request.getRemoteAddr()
        );

        businessNotifications.direct(schoolId, teacherId, NotificationEventCode.LEAVE_SUBMITTED,
                NotificationCategory.LEAVE, "Leave Applied",
                String.format("Your leave application for %s to %s has been submitted.", saved.getStartDate(), saved.getEndDate()),
                "TeacherLeave", String.valueOf(saved.getId()), TEACHER_LEAVE_ROUTE, teacherId,
                "teacher-leave:" + saved.getId() + ":submitted", Set.of(ExternalDeliveryChannel.PUSH));
        try {
            businessNotifications.publish(schoolId, NotificationEventCode.LEAVE_SUBMITTED, NotificationCategory.LEAVE,
                    "Staff leave request to review",
                    String.format("%s applied for leave from %s to %s.", saved.getTeacherName(), saved.getStartDate(), saved.getEndDate()),
                    new NotificationAudience(NotificationAudienceType.ROLE, Role.ADMIN), "TeacherLeave",
                    String.valueOf(saved.getId()), ADMIN_REVIEW_ROUTE, teacherId,
                    "teacher-leave:" + saved.getId() + ":submitted:admins", Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException e) {
            log.warn("Could not notify admins of teacher leave {}: {}", saved.getId(), e.getMessage());
        }

        return TeacherLeaveResponse.from(saved, workingLeaveDays);
    }

    @Transactional
    public TeacherLeaveResponse updateStatus(Long leaveId, LeaveStatus status, HttpServletRequest request) {
        return updateStatus(leaveId, status, null, request);
    }

    /**
     * First decision on a PENDING request: APPROVED or REJECTED only — never back to PENDING and
     * never a second decision (use {@link #reverse}). Locked read, like LeaveRepository.findByIdForUpdate:
     * two admins deciding the same request at nearly the same moment serialize instead of racing.
     */
    @Transactional
    public TeacherLeaveResponse updateStatus(Long leaveId, LeaveStatus status, String reason, HttpServletRequest request) {
        if (status != LeaveStatus.APPROVED && status != LeaveStatus.REJECTED) {
            throw new IllegalArgumentException("A leave request can only be approved or rejected.");
        }
        TeacherLeave leave = lockOwned(leaveId);
        if (status == leave.getStatus()) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status);
        }
        if (leave.getStatus() != LeaveStatus.PENDING) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), status,
                    "This leave request is already " + leave.getStatus() + ". Use \"Change decision\" to reverse a decision.");
        }
        return applyDecision(leave, status, reason, "UPDATE_TEACHER_LEAVE_STATUS", request);
    }

    /** Admin reversal of a decision (APPROVED ↔ REJECTED) with a required reason. */
    @Transactional
    public TeacherLeaveResponse reverse(Long leaveId, String reason, HttpServletRequest request) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required to change a decision.");
        TeacherLeave leave = lockOwned(leaveId);
        LeaveStatus target;
        if (leave.getStatus() == LeaveStatus.APPROVED) target = LeaveStatus.REJECTED;
        else if (leave.getStatus() == LeaveStatus.REJECTED) target = LeaveStatus.APPROVED;
        else throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), leave.getStatus(),
                    "Only an approved or rejected leave can be reversed (this one is " + leave.getStatus() + ").");
        if (target == LeaveStatus.APPROVED && teacherLeaveRepository.existsActiveOverlapping(
                leave.getSchoolId(), leave.getTeacherId(), leave.getStartDate(), leave.getEndDate(), leave.getId())) {
            throw new IllegalStateException("This teacher already has another pending or approved leave overlapping these dates.");
        }
        return applyDecision(leave, target, reason, "REVERSE_TEACHER_LEAVE_DECISION", request);
    }

    private TeacherLeave lockOwned(Long leaveId) {
        Long schoolId = securityUtil.getSchoolId();
        TeacherLeave leave = teacherLeaveRepository.findByIdForUpdate(leaveId)
                .orElseThrow(() -> new NoSuchElementException("Leave request not found: " + leaveId));
        if (!schoolId.equals(leave.getSchoolId())) {
            throw new SecurityException("Access denied: leave request does not belong to your school.");
        }
        return leave;
    }

    private TeacherLeaveResponse applyDecision(TeacherLeave leave, LeaveStatus status, String reason, String auditAction,
                                               HttpServletRequest request) {
        Long schoolId = leave.getSchoolId();
        String adminUser = securityUtil.getUsername();
        LeaveStatus oldStatus = leave.getStatus();
        leave.setStatus(status);
        leave.setDecidedBy(adminUser);
        leave.setDecidedAt(java.time.LocalDateTime.now());
        leave.setDecisionReason(trimReason(reason));
        TeacherLeave saved = teacherLeaveRepository.save(leave);
        log.info("Admin {} set teacher leave {} to {} (school {})", adminUser, saved.getId(), status, schoolId);
        auditService.log(adminUser, securityUtil.getRole(), auditAction, "TeacherLeave", String.valueOf(saved.getId()),
                "status=" + oldStatus,
                "status=" + status + (saved.getDecisionReason() != null ? ",reason=" + saved.getDecisionReason() : ""),
                request.getRemoteAddr());
        NotificationEventCode eventCode = status == LeaveStatus.APPROVED
                ? NotificationEventCode.LEAVE_APPROVED : NotificationEventCode.LEAVE_REJECTED;
        businessNotifications.direct(schoolId, saved.getTeacherId(), eventCode, NotificationCategory.LEAVE,
                "Leave Status Updated", String.format("Your leave application for %s to %s has been %s.",
                        saved.getStartDate(), saved.getEndDate(), status.name().toLowerCase())
                        + (saved.getDecisionReason() != null ? " Reason: " + saved.getDecisionReason() : ""),
                "TeacherLeave", String.valueOf(saved.getId()), TEACHER_LEAVE_ROUTE, adminUser,
                "teacher-leave:" + saved.getId() + ":decision:" + oldStatus + ":" + status,
                Set.of(ExternalDeliveryChannel.PUSH));
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return toCalendarAwareResponse(saved, school);
    }

    @Transactional
    public void cancelLeave(Long leaveId, HttpServletRequest request) {
        cancelLeave(leaveId, null, request);
    }

    /**
     * Cancels a request and keeps it as history (status CANCELLED, never deleted). A teacher may
     * cancel only their OWN request while it's still PENDING. An admin may cancel any PENDING
     * request, or an APPROVED one with a reason (it changes staff attendance and substitution).
     * Locked read, so a cancel can't race an admin's simultaneous decision.
     */
    @Transactional
    public void cancelLeave(Long leaveId, String reason, HttpServletRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String userId = securityUtil.getUsername();
        String role = securityUtil.getRole();
        TeacherLeave leave = lockOwned(leaveId);
        if (Role.TEACHER.equals(role)) {
            if (!Objects.equals(userId, leave.getTeacherId())) {
                throw new SecurityException("Access denied: this is not your leave request.");
            }
            if (leave.getStatus() != LeaveStatus.PENDING) {
                throw new IllegalStateException(
                        "Only a pending leave request can be cancelled. Contact an admin to change a decided request.");
            }
        }
        if (leave.getStatus() == LeaveStatus.CANCELLED) {
            throw new InvalidLeaveStatusTransitionException(leaveId, leave.getStatus(), LeaveStatus.CANCELLED);
        }
        if (leave.getStatus() == LeaveStatus.REJECTED) {
            throw new IllegalStateException("A rejected leave request can't be cancelled.");
        }
        if (leave.getStatus() == LeaveStatus.APPROVED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required to cancel an approved leave.");
        }
        String oldValue = "startDate=" + leave.getStartDate() + ",endDate=" + leave.getEndDate() + ",status=" + leave.getStatus();
        leave.setStatus(LeaveStatus.CANCELLED);
        leave.setCancelledBy(userId);
        leave.setCancelledAt(java.time.LocalDateTime.now());
        leave.setCancellationReason(trimReason(reason));
        teacherLeaveRepository.save(leave);
        log.info("{} ({}) cancelled teacher leave {} (school {})", userId, role, leaveId, schoolId);
        auditService.log(userId, role, "CANCEL_TEACHER_LEAVE", "TeacherLeave", String.valueOf(leaveId), oldValue,
                "status=CANCELLED" + (leave.getCancellationReason() != null ? ",reason=" + leave.getCancellationReason() : ""),
                request.getRemoteAddr());
        businessNotifications.direct(schoolId, leave.getTeacherId(), NotificationEventCode.LEAVE_CANCELLED,
                NotificationCategory.LEAVE, "Leave Cancelled",
                String.format("Your leave application for %s to %s has been cancelled.",
                        leave.getStartDate(), leave.getEndDate()), "TeacherLeave", String.valueOf(leaveId),
                TEACHER_LEAVE_ROUTE, userId, "teacher-leave:" + leaveId + ":cancelled",
                Set.of(ExternalDeliveryChannel.PUSH));
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String r = reason.trim();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }

    @Transactional(readOnly = true)
    public Page<TeacherLeaveResponse> getMyLeaves(Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        String teacherId = securityUtil.getUsername();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return teacherLeaveRepository.findByTeacherIdAndSchoolIdOrderByStartDateDesc(teacherId, schoolId, pageable)
                .map(leave -> toCalendarAwareResponse(leave, school));
    }

    @Transactional(readOnly = true)
    public Page<TeacherLeaveResponse> getLeavesFiltered(LeaveStatus status, String teacherId, LocalDate date, Pageable pageable) {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));
        return teacherLeaveRepository.findFiltered(schoolId, status, teacherId, date, pageable)
                .map(leave -> toCalendarAwareResponse(leave, school));
    }

    @Transactional(readOnly = true)
    public Map<String, String> getCalendarConfig() {
        School school = schoolRepository.findById(securityUtil.getSchoolId())
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        Set<String> configuredDays = parseConfiguredWorkingDays(school.getWorkingDays());
        return Map.of("workingDays", String.join(",", configuredDays),
                "timezone", SchoolTimeUtil.zoneId(school).getId());
    }

    private TeacherLeaveResponse toCalendarAwareResponse(TeacherLeave leave, School school) {
        List<SchoolHoliday> holidays = schoolHolidayRepository.findOverlapping(
                leave.getSchoolId(), leave.getStartDate(), leave.getEndDate());
        return TeacherLeaveResponse.from(leave, countWorkingLeaveDays(
                leave.getTeacherId(), leave.getSchoolId(), leave.getStartDate(), leave.getEndDate(),
                school.getWorkingDays(), holidays));
    }

    private long countWorkingLeaveDays(String teacherId, Long schoolId, LocalDate start, LocalDate end,
                                       String schoolWorkingDays, List<SchoolHoliday> holidays) {
        Map<String, List<com.indraacademy.ias_management.entity.TeacherAttendanceSchedule>> schedules =
                teacherAttendanceScheduleService.schedulesByTeacher(schoolId, start, end);
        return start.datesUntil(end.plusDays(1))
                .filter(date -> parseConfiguredWorkingDays(teacherAttendanceScheduleService.workingDaysFor(
                        teacherId, date, schoolWorkingDays, schedules)).contains(date.getDayOfWeek().name()))
                .filter(date -> holidays.stream().noneMatch(holiday ->
                        !date.isBefore(holiday.getStartDate()) && !date.isAfter(holiday.getEndDate())))
                .count();
    }

    private Set<String> parseConfiguredWorkingDays(String workingDays) {
        if (workingDays == null || workingDays.isBlank()) {
            throw new IllegalStateException("School working days are not configured. Please update School Settings.");
        }
        Set<String> configuredDays = Arrays.stream(workingDays.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(day -> !day.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (configuredDays.isEmpty()) {
            throw new IllegalStateException("School working days are not configured. Please update School Settings.");
        }
        return configuredDays;
    }
}
