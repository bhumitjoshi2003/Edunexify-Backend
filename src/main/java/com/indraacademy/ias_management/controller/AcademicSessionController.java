package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.AcademicSessionDto;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationPreviewResult;
import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.SessionActivationOutcome;
import com.indraacademy.ias_management.service.AcademicSessionActivationService;
import com.indraacademy.ias_management.service.AcademicSessionService;
import com.indraacademy.ias_management.service.ClassTeacherActivationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/academic-sessions")
public class AcademicSessionController {

    @Autowired
    private AcademicSessionService sessionService;

    @Autowired
    private AcademicSessionActivationService sessionActivationService;

    @Autowired
    private ClassTeacherActivationService classTeacherActivationService;

    @Autowired
    private com.indraacademy.ias_management.service.SessionReadinessService sessionReadinessService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER', 'STUDENT', 'PARENT', 'SUB_ADMIN')")
    public ResponseEntity<List<AcademicSessionDto>> getAllSessions() {
        return ResponseEntity.ok(sessionService.getAllSessions());
    }

    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER', 'STUDENT', 'PARENT', 'SUB_ADMIN')")
    public ResponseEntity<AcademicSessionDto> getCurrentSession() {
        return ResponseEntity.ok(sessionService.getCurrentSession());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AcademicSessionDto> createSession(@Valid @RequestBody AcademicSessionDto dto) {
        return ResponseEntity.ok(sessionService.createSession(dto));
    }

    /** Phase G: making a session current now also activates its class-teacher responsibilities
     *  into the live Teacher projection, in one atomic transaction — see
     *  {@link AcademicSessionActivationService} for exactly why and how. */
    @PutMapping("/{sessionId}/set-current")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SessionActivationOutcome> setCurrentSession(@PathVariable Long sessionId, HttpServletRequest request) {
        return ResponseEntity.ok(sessionActivationService.setCurrentSessionAndActivate(sessionId, request));
    }

    /** Read-only: what activating {@code sessionId}'s class-teacher configuration WOULD do if it
     *  were made current right now — lets the "Make Current" confirmation show gains/changes/
     *  removals (and whether the target has zero/invalid configuration) before committing. */
    @GetMapping("/{sessionId}/activation-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ActivationPreviewResult> getActivationPreview(@PathVariable Long sessionId) {
        return ResponseEntity.ok(classTeacherActivationService.previewForSession(sessionId));
    }

    /** Read-only readiness warnings shown before "Make Current" (never blocks the switch). */
    @GetMapping("/{sessionId}/readiness")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<com.indraacademy.ias_management.service.SessionReadinessService.Readiness> getReadiness(
            @PathVariable Long sessionId) {
        return ResponseEntity.ok(sessionReadinessService.readiness(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
