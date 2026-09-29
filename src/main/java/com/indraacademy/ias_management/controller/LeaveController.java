package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.LeaveService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/leaves")
public class LeaveController {

    private static final Logger log = LoggerFactory.getLogger(LeaveController.class);

    @Autowired private LeaveService leaveService;
    @Autowired private AuthService authService;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private com.indraacademy.ias_management.service.LeaveOverviewService leaveOverviewService;

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.PARENT + "')")
    @PostMapping("/apply-leave")
    public ResponseEntity<String> applyLeave(@Valid @RequestBody com.indraacademy.ias_management.dto.StudentLeaveApplyRequest leave,
                                             @RequestParam(required = false) String studentId,
                                             HttpServletRequest request) {
        String role = authService.getRole();
        String effectiveStudentId = Role.STUDENT.equals(role) ? authService.getUserId() : studentId;
        if (effectiveStudentId == null || effectiveStudentId.isBlank()) {
            return ResponseEntity.badRequest().body("A child must be selected.");
        }
        if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(effectiveStudentId, ParentPortalService.ChildPermission.MANAGE_LEAVE);
        }
        log.info("Request to apply leave for student ID: {}", effectiveStudentId);
        // Only the date and reason are read from the body; the student is the caller (STUDENT)
        // or an authorised linked child (PARENT), never a body field.
        leaveService.applyLeave(effectiveStudentId, leave, request);
        return ResponseEntity.ok("Leave applied successfully");
    }

    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.PARENT + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/delete/{studentId}/{leaveDate}")
    public ResponseEntity<String> deleteLeave(@PathVariable String studentId, @PathVariable String leaveDate,
                                              @RequestParam(required = false) String reason, HttpServletRequest request) {
        // Cancels the request (kept as CANCELLED history) — never deletes it.
        String userId = authService.getUserId();
        String role = authService.getRole();
        log.info("Request to cancel leave for {} on {} by user {} ({})", studentId, leaveDate, userId, role);

        String finalStudentId = studentId;
        if(Role.STUDENT.equals(role)) {
            finalStudentId = userId;
        } else if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(finalStudentId, ParentPortalService.ChildPermission.MANAGE_LEAVE);
        }

        leaveService.cancelForStudentDate(finalStudentId, leaveDate, reason, request);
        return new ResponseEntity<>("Leave cancelled", HttpStatus.OK);
    }

    @GetMapping("/student")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.SUB_ADMIN + "')")
    public ResponseEntity<?> getLeaves(
            @RequestParam(required = false)  String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) LeaveStatus status,
            Pageable pageable
    ) {
        // TEACHER: always their own assigned class+section — overridden, never merged with
        // whatever (if anything) the client sent, same rule already enforced on /for-review.
        // Without this, a teacher with no className in the request (e.g. the empty-string case)
        // fell through to an unscoped, school-wide leave search.
        String effectiveClassName = className;
        Long sectionId = null;
        if (Role.TEACHER.equals(authService.getRole())) {
            TeacherClassScopeService.TeacherScope scope =
                    teacherClassScopeService.resolveOwnScope(authService.getUserId(), securityUtil.getSchoolId());
            if (!scope.hasClassResponsibility()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You are not assigned as a class teacher."));
            }
            if (scope.sectionRequiredButMissing()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
            }
            effectiveClassName = scope.className();
            sectionId = scope.sectionId();
        }
        log.info("Request to get filtered leaves. Class: {}, Student: {}, Date: {}", effectiveClassName, studentId, date);
        return ResponseEntity.ok(
                leaveService.getLeavesFiltered(effectiveClassName, studentId, date, status, sectionId, pageable)
        );
    }

    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('" + Role.STUDENT + "', '" + Role.PARENT + "', '" + Role.ADMIN + "')")
    public ResponseEntity<Page<Leave>> getLeavesOfStudent(
            @PathVariable String studentId,
            Pageable pageable) {

        // Pulls the studentId from the SecurityContext to prevent users from querying other students' leaves.
        String role = authService.getRole();
        String authenticatedUserId = authService.getUserId();
        String effectiveStudentId = Role.STUDENT.equals(role) ? authenticatedUserId : studentId;
        if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(effectiveStudentId, ParentPortalService.ChildPermission.MANAGE_LEAVE);
        }
        log.info("Request to get leaves for student ID: {} (authenticated as {})", effectiveStudentId, authenticatedUserId);

        return ResponseEntity.ok(leaveService.getLeavesByStudentId(effectiveStudentId, pageable));
    }

    @GetMapping("/date/{date}/class/{className}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.SUB_ADMIN + "')")
    public ResponseEntity<?> getLeavesByDateAndClass(@PathVariable String date, @PathVariable String className) {
        // TEACHER: only their assigned class+section — same rule as getLeaves above.
        Long sectionId = null;
        if (Role.TEACHER.equals(authService.getRole())) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(), className, null);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", access.errorMessage()));
            }
            sectionId = access.effectiveSectionId();
        }
        log.info("Request to get leaves by Date: {} and Class: {}", date, className);
        List<String> leaves = leaveService.getLeavesByDateAndClass(date, className, sectionId);
        return new ResponseEntity<>(leaves, HttpStatus.OK);
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @PatchMapping("/{leaveId}/status")
    public ResponseEntity<?> updateLeaveStatus(
            @PathVariable Long leaveId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        log.info("Request to update status of leave ID: {}", leaveId);
        String statusValue = body.get("status");
        String reason = body.get("reason");
        if (statusValue == null) {
            return ResponseEntity.badRequest().body("Missing 'status' field.");
        }
        LeaveStatus status;
        try {
            status = LeaveStatus.valueOf(statusValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid status value: " + statusValue);
        }
        try {
            Leave updated = leaveService.decide(leaveId, status, reason, request);
            return ResponseEntity.ok(updated);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (InvalidLeaveStatusTransitionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * GET /api/leaves/for-review?className=&studentId=&ids=1,2,3&limit=25
     *
     * <p>The read surface behind the AI Copilot's leave tools and its decision workflow. Two modes:
     * with {@code ids}, it resolves exactly those requests <i>whatever their current status</i>, so
     * the caller can be told "already approved" instead of silently losing the request; without,
     * it returns the PENDING review queue, oldest first.
     *
     * <p><b>Teachers are confined to their own class here</b>, unlike this module's older endpoints
     * which are school-scoped for every staff role. That difference is deliberate and limited to
     * this new surface: natural language makes broad action cheap, so the Copilot is held to the
     * same class confinement the attendance tools already enforce, without changing the behaviour
     * of the existing View Leaves screen.
     */
    @GetMapping("/for-review")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<?> getLeavesForReview(
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String studentId,
            @RequestParam(required = false) List<Long> ids,
            @RequestParam(defaultValue = "25") int limit) {

        String role = authService.getRole();
        String effectiveClassName = className;
        Long sectionId = null;

        if (Role.TEACHER.equals(role)) {
            TeacherClassScopeService.TeacherScope scope =
                    teacherClassScopeService.resolveOwnScope(authService.getUserId(), securityUtil.getSchoolId());
            if (!scope.hasClassResponsibility()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You are not assigned as a class teacher."));
            }
            if (scope.sectionRequiredButMissing()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", TeacherClassScopeService.SECTION_REQUIRED_MESSAGE));
            }
            // Overridden, never merged — a className in the query string can only ever narrow to
            // the teacher's own class, never select a different one.
            effectiveClassName = scope.className();
            sectionId = scope.sectionId();
        }

        List<Leave> result;
        if (ids != null && !ids.isEmpty()) {
            final String scopeClassName = effectiveClassName;
            final Long scopeSectionId = sectionId;
            final Long schoolId = securityUtil.getSchoolId();
            result = leaveService.getLeavesByIds(ids).stream()
                    .filter(l -> scopeClassName == null || scopeClassName.equals(l.getClassName()))
                    .filter(l -> {
                        // Leave itself carries no sectionId — resolve the student's CURRENT
                        // section live, same rationale as LeaveRepository's EXISTS-subquery.
                        if (scopeSectionId == null) return true;
                        Long studentSectionId = studentRepository.findByStudentIdAndSchoolId(l.getStudentId(), schoolId)
                                .map(Student::getSectionId).orElse(null);
                        return scopeSectionId.equals(studentSectionId);
                    })
                    .toList();
        } else {
            result = leaveService.getLeavesForReview(effectiveClassName, studentId, limit, sectionId);
        }
        return ResponseEntity.ok(result);
    }

    /** Explicit reversal of a decision (APPROVED ↔ REJECTED) with a required reason. */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @PostMapping("/{leaveId}/reverse")
    public ResponseEntity<?> reverseLeaveDecision(@PathVariable Long leaveId,
                                                  @RequestBody(required = false) Map<String, String> body,
                                                  HttpServletRequest request) {
        try {
            return ResponseEntity.ok(leaveService.reverse(leaveId, body == null ? null : body.get("reason"), request));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (InvalidLeaveStatusTransitionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Admin cancel (the request stays as CANCELLED history). Approved leave needs a reason. */
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<String> deleteLeaveById(@PathVariable Long leaveId,
                                                  @RequestParam(required = false) String reason,
                                                  HttpServletRequest request) {
        try {
            leaveService.cancelById(leaveId, reason, request);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
        return new ResponseEntity<>("Leave application cancelled", HttpStatus.OK);
    }

    /** Admin: students and staff on approved leave today, and periods still needing a substitute. */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/on-leave-today")
    public ResponseEntity<com.indraacademy.ias_management.service.LeaveOverviewService.OnLeaveToday> onLeaveToday() {
        return ResponseEntity.ok(leaveOverviewService.onLeaveToday());
    }
}
