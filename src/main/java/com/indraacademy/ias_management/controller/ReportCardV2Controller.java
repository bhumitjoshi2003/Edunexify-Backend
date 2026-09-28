package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.service.ReportCardDesignService;
import com.indraacademy.ias_management.service.ReportCardSetupService;
import com.indraacademy.ias_management.service.ReportCardV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Report Card V2, Phase 1 (runs alongside the Phase 0 report cards, which are untouched):
 * setup (ADMIN), school design and co-scholastic activities (ADMIN), remarks (ADMIN / TEACHER of
 * the class) and Generate &amp; Preview with a live PDF (ADMIN / TEACHER of the class). No
 * publishing in Phase 1. SUB_ADMIN, SUPER_ADMIN, STUDENT and PARENT have no access.
 */
@RestController
@RequestMapping("/api/report-cards/v2")
public class ReportCardV2Controller {

    private static final String ADMIN = "hasRole('" + Role.ADMIN + "')";
    private static final String STAFF = "hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')";

    @Autowired private ReportCardSetupService setupService;
    @Autowired private ReportCardDesignService designService;
    @Autowired private ReportCardV2Service reportCardService;

    // ── Setup ─────────────────────────────────────────────────────────────

    @PreAuthorize(STAFF)
    @GetMapping("/setups")
    public List<SetupDTO> setups(@RequestParam Long academicSessionId, @RequestParam Long classId) {
        return setupService.list(academicSessionId, classId);
    }

    @PreAuthorize(STAFF)
    @GetMapping("/setups/{id}")
    public SetupDTO setup(@PathVariable Long id) {
        return setupService.get(id);
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/setups/available-exams")
    public List<AvailableExamDTO> availableExams(@RequestParam Long academicSessionId, @RequestParam Long classId) {
        return setupService.availableExams(academicSessionId, classId);
    }

    @PreAuthorize(ADMIN)
    @PostMapping("/setups")
    public ResponseEntity<SetupDTO> createSetup(@RequestBody SetupRequest req) {
        return new ResponseEntity<>(setupService.create(req), HttpStatus.CREATED);
    }

    @PreAuthorize(ADMIN)
    @PutMapping("/setups/{id}")
    public SetupDTO updateSetup(@PathVariable Long id, @RequestBody SetupRequest req) {
        return setupService.update(id, req);
    }

    @PreAuthorize(ADMIN)
    @DeleteMapping("/setups/{id}")
    public ResponseEntity<Void> deleteSetup(@PathVariable Long id) {
        setupService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Report Card Design & co-scholastic activities ─────────────────────

    @PreAuthorize(ADMIN)
    @GetMapping("/design")
    public DesignDTO design() {
        return designService.get();
    }

    @PreAuthorize(ADMIN)
    @PutMapping("/design")
    public DesignDTO updateDesign(@RequestBody DesignDTO req) {
        return designService.update(req);
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/design/activities")
    public List<ActivityDTO> activities() {
        return designService.activities(false);
    }

    @PreAuthorize(ADMIN)
    @PostMapping("/design/activities")
    public ResponseEntity<ActivityDTO> createActivity(@RequestBody ActivityRequest req) {
        return new ResponseEntity<>(designService.createActivity(req), HttpStatus.CREATED);
    }

    @PreAuthorize(ADMIN)
    @PutMapping("/design/activities/{id}")
    public ActivityDTO updateActivity(@PathVariable Long id, @RequestBody ActivityRequest req) {
        return designService.updateActivity(id, req);
    }

    @PreAuthorize(ADMIN)
    @PutMapping("/design/activities/order")
    public List<ActivityDTO> reorderActivities(@RequestBody List<Long> ids) {
        return designService.reorder(ids);
    }

    // ── Remarks & co-scholastic grades ────────────────────────────────────

    @PreAuthorize(STAFF)
    @GetMapping("/setups/{id}/remarks")
    public RemarksPageDTO remarks(@PathVariable Long id, @RequestParam(required = false) Long sectionId) {
        return reportCardService.remarks(id, sectionId);
    }

    @PreAuthorize(STAFF)
    @PutMapping("/setups/{id}/remarks")
    public ResponseEntity<Void> saveRemarks(@PathVariable Long id, @RequestBody RemarksSaveRequest req) {
        reportCardService.saveRemarks(id, req);
        return ResponseEntity.noContent().build();
    }

    // ── Generate & Preview ────────────────────────────────────────────────

    @PreAuthorize(STAFF)
    @GetMapping("/setups/{id}/summary")
    public SummaryDTO summary(@PathVariable Long id, @RequestParam(required = false) Long sectionId) {
        return reportCardService.summary(id, sectionId);
    }

    @PreAuthorize(STAFF)
    @GetMapping("/setups/{id}/students/{studentId}/pdf")
    public ResponseEntity<byte[]> previewPdf(@PathVariable Long id, @PathVariable String studentId) {
        ReportCardV2Service.Pdf pdf = reportCardService.previewPdf(id, studentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.bytes());
    }
}
