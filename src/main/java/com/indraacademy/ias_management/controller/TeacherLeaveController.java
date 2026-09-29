package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherLeaveResponse;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.service.TeacherLeaveService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/teacher-leaves")
public class TeacherLeaveController {

    private static final Logger log = LoggerFactory.getLogger(TeacherLeaveController.class);

    @Autowired private TeacherLeaveService teacherLeaveService;

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @PostMapping("/apply")
    public ResponseEntity<?> applyLeave(@Valid @RequestBody TeacherLeaveApplyRequest req, HttpServletRequest request) {
        try {
            TeacherLeaveResponse saved = teacherLeaveService.applyLeave(req, request);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/my-leaves")
    public ResponseEntity<Page<TeacherLeaveResponse>> getMyLeaves(Pageable pageable) {
        return ResponseEntity.ok(teacherLeaveService.getMyLeaves(pageable));
    }

    @PreAuthorize("hasRole('" + Role.TEACHER + "')")
    @GetMapping("/calendar-config")
    public ResponseEntity<Map<String, String>> getCalendarConfig() {
        return ResponseEntity.ok(teacherLeaveService.getCalendarConfig());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping
    public ResponseEntity<Page<TeacherLeaveResponse>> getLeaves(
            @RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) String teacherId,
            @RequestParam(required = false) java.time.LocalDate date,
            Pageable pageable) {
        return ResponseEntity.ok(teacherLeaveService.getLeavesFiltered(status, teacherId, date, pageable));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PatchMapping("/{leaveId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long leaveId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String statusValue = body.get("status");
        if (statusValue == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Missing 'status' field."));
        }
        LeaveStatus status;
        try {
            status = LeaveStatus.valueOf(statusValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid status value: " + statusValue));
        }
        try {
            TeacherLeaveResponse updated = teacherLeaveService.updateStatus(leaveId, status, body.get("reason"), request);
            return ResponseEntity.ok(updated);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (InvalidLeaveStatusTransitionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Admin reversal of a decision (APPROVED ↔ REJECTED) with a required reason. */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{leaveId}/reverse")
    public ResponseEntity<?> reverse(@PathVariable Long leaveId,
                                     @RequestBody(required = false) Map<String, String> body,
                                     HttpServletRequest request) {
        try {
            return ResponseEntity.ok(teacherLeaveService.reverse(leaveId, body == null ? null : body.get("reason"), request));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (InvalidLeaveStatusTransitionException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    /** Cancels (keeps the request as CANCELLED history). An approved leave needs a reason. */
    @PreAuthorize("hasAnyRole('" + Role.TEACHER + "', '" + Role.ADMIN + "')")
    @DeleteMapping("/{leaveId}")
    public ResponseEntity<?> cancelLeave(@PathVariable Long leaveId,
                                         @RequestParam(required = false) String reason,
                                         HttpServletRequest request) {
        try {
            teacherLeaveService.cancelLeave(leaveId, reason, request);
            return ResponseEntity.ok(Map.of("message", "Leave request cancelled successfully."));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }
}
