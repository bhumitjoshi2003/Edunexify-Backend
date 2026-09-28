package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.*;
import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.ReportCardDataAssembler;
import com.indraacademy.ias_management.service.ReportCardPdfGenerator;
import com.indraacademy.ias_management.dto.ClassOverviewDTO;
import com.indraacademy.ias_management.service.ClassOverviewService;
import com.indraacademy.ias_management.service.ReportCardPublicationService;
import com.indraacademy.ias_management.service.ReportCardTemplateService;
import com.indraacademy.ias_management.service.RemarksService;
import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class ReportCardController {

    @Autowired private ReportCardTemplateService     templateService;
    @Autowired private ReportCardDataAssembler       assembler;
    @Autowired private RemarksService                remarksService;
    @Autowired private ReportCardPdfGenerator        pdfGenerator;
    @Autowired private ReportCardPublicationService  publicationService;
    @Autowired private ClassOverviewService          classOverviewService;
    @Autowired private StudentRepository             studentRepository;
    @Autowired private SecurityUtil                  securityUtil;
    @Autowired private EntitlementService            entitlementService;
    @Autowired private ParentPortalService            parentPortalService;
    @Autowired private TeacherClassScopeService       teacherClassScopeService;
    @Autowired private com.indraacademy.ias_management.repository.SchoolClassRepository schoolClassRepository;
    @Autowired private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;
    @Autowired private com.indraacademy.ias_management.repository.StudentEnrollmentRepository studentEnrollmentRepository;

    // ── Template CRUD ─────────────────────────────────────────────────────

    /** List all active templates for the school. */
    @GetMapping("/report-card-templates")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<List<ReportCardTemplateDTO>> listTemplates() {
        return ResponseEntity.ok(templateService.listTemplates());
    }

    /** Get a single template with its sections. */
    @GetMapping("/report-card-templates/{id}")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ReportCardTemplateDTO> getTemplate(@PathVariable Long id) {
        return ResponseEntity.ok(templateService.getTemplate(id));
    }

    /** Create a new template. Auto-creates all 10 default sections (all enabled). */
    @PostMapping("/report-card-templates")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> createTemplate(
            @Valid @RequestBody ReportCardTemplateRequest req) {
        return ResponseEntity.ok(templateService.createTemplate(req));
    }

    /** Update template metadata. Does not touch sections. */
    @PutMapping("/report-card-templates/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody ReportCardTemplateRequest req) {
        return ResponseEntity.ok(templateService.updateTemplate(id, req));
    }

    /** Soft-delete a template (sets isActive = false). Cannot delete the default template. */
    @DeleteMapping("/report-card-templates/{id}")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.noContent().build();
    }

    /** Bulk-replace all sections for a template in one call. */
    @PutMapping("/report-card-templates/{id}/sections")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardTemplateDTO> updateSections(
            @PathVariable Long id,
            @Valid @RequestBody ReportCardSectionUpdateRequest req) {
        return ResponseEntity.ok(templateService.updateSections(id, req));
    }

    // ── Report Card Data ──────────────────────────────────────────────────

    /**
     * Assemble and return the full report card data for a student.
     *
     * Students can only fetch their own report card.
     * Teachers and admins can fetch any student's report card.
     *
     * GET /api/report-cards?studentId=&templateId=&session=
     */
    @GetMapping("/report-cards")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> getReportCard(
            @RequestParam String studentId,
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam(required = false) Long classId) {
        try {
            checkStudentOrParentPublishedAccess(studentId, templateId, session, classId);
            checkTeacherOwnsStudents(List.of(studentId));
            return ResponseEntity.ok(assembleOrTranslate(studentId, templateId, session, classId));
        } catch (ReportCardDataAssembler.ReportCardContextAmbiguousException e) {
            return ambiguousResponse(studentId, e);
        }
    }

    // ── Remarks ───────────────────────────────────────────────────────────

    /**
     * Load all existing remarks + co-scholastic entries for a class in one call.
     * GET /api/report-cards/remarks?templateId=&session=&className=
     */
    @GetMapping("/report-cards/remarks")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ClassRemarksDTO> getClassRemarks(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        Long sectionId = checkTeacherClassAccess(className);
        return ResponseEntity.ok(remarksService.getClassRemarks(templateId, session, className, sectionId));
    }

    /**
     * Bulk upsert teacher/principal remarks for a class.
     * PUT /api/report-cards/remarks
     */
    @PutMapping("/report-cards/remarks")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<Void> saveRemarks(@Valid @RequestBody RemarksRequest req) {
        // Principal remarks are ADMIN-only; a teacher may only write the class teacher's remark.
        if (Role.TEACHER.equals(securityUtil.getRole()) && req.getStudentRemarks().stream()
                .anyMatch(item -> item.getPrincipalRemark() != null)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator can enter the principal's remarks.");
        }
        checkTeacherOwnsStudents(req.getStudentRemarks().stream()
                .map(RemarksRequest.StudentRemarkItem::getStudentId).toList());
        remarksService.saveRemarks(req);
        return ResponseEntity.noContent().build();
    }

    /**
     * Bulk upsert co-scholastic grades for a class.
     * PUT /api/report-cards/co-scholastic
     */
    @PutMapping("/report-cards/co-scholastic")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<Void> saveCoScholastic(@Valid @RequestBody CoScholasticRequest req) {
        checkTeacherOwnsStudents(req.getStudentEntries().stream()
                .map(CoScholasticRequest.StudentCoScholasticItem::getStudentId).toList());
        remarksService.saveCoScholastic(req);
        return ResponseEntity.noContent().build();
    }

    // ── PDF Download ──────────────────────────────────────────────────────

    /**
     * Public QR verification endpoint — no authentication required.
     * GET /api/public/verify-rc?token={uuid}
     */
    @GetMapping("/public/verify-rc")
    public ResponseEntity<VerifyRcDTO> verifyReportCard(@RequestParam String token) {
        return ResponseEntity.ok(publicationService.verifyByToken(token));
    }

    /**
     * Generate and download a single student's report card as PDF — the one document used for
     * both "Preview & Print" and "Download" on Web and Android.
     * GET /api/report-cards/pdf?studentId=&session=&templateId=   (template card)
     * GET /api/report-cards/pdf?studentId=&session=[&examId=]     (results card: no template)
     */
    @GetMapping("/report-cards/pdf")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')")
    public ResponseEntity<?> downloadPdf(
            @RequestParam String studentId,
            @RequestParam(required = false) Long templateId,
            @RequestParam String session,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long examId) {
        if (templateId == null) {
            return downloadResultsPdf(studentId, session, examId, classId);
        }
        try {
            checkStudentOrParentPublishedAccess(studentId, templateId, session, classId);
            checkTeacherOwnsStudents(List.of(studentId));

            // Once the exact context is selected (via classId when disambiguation was needed),
            // that same context is used for the PDF and verification-token lookup below — never
            // independently recalculated.
            ReportCardDataDTO data = assembleOrTranslate(studentId, templateId, session, classId);

            publicationService.getVerificationToken(templateId, session, data.getClassName())
                .ifPresent(data::setVerificationToken);

            byte[] pdf = pdfGenerator.generate(data);

            String filename = sanitizeFilename(data.getStudentName()) + "_" + session + "_ReportCard.pdf";

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(pdf);
        } catch (ReportCardDataAssembler.ReportCardContextAmbiguousException e) {
            return ambiguousResponse(studentId, e);
        }
    }

    /**
     * The report card built from the student's exam results (no template): what Class Results,
     * My Results and the parent portal open. Students and parents get published exams only and
     * only their own / their child's card; teachers only their own class; no verification QR
     * (only a published template card has a verification token).
     */
    private ResponseEntity<?> downloadResultsPdf(String studentId, String session, Long examId, Long classId) {
        checkStudentOrParentOwnAccess(studentId);
        checkTeacherOwnsStudents(List.of(studentId));
        String role = securityUtil.getRole();
        boolean includeDrafts = Role.ADMIN.equals(role) || Role.TEACHER.equals(role);
        ReportCardDataDTO data;
        try {
            data = assembler.assembleFromResults(studentId, session, examId, classId, includeDrafts);
        } catch (ReportCardDataAssembler.ReportCardContextAmbiguousException e) {
            return ambiguousResponse(studentId, e);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        byte[] pdf = pdfGenerator.generate(data);
        String filename = sanitizeFilename(data.getStudentName()) + "_" + session + "_ReportCard.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(pdf);
    }

    /**
     * Generate and download all students' report cards for a class as a ZIP of PDFs.
     * GET /api/report-cards/pdf/bulk?templateId=&session=&className=
     */
    @GetMapping("/report-cards/pdf/bulk")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<byte[]> downloadBulkPdf(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {

        Long sectionId = checkTeacherClassAccess(className);

        Long schoolId = securityUtil.getSchoolId();
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                .map(com.indraacademy.ias_management.entity.SchoolClass::getId).orElse(null);
        List<Student> students = historicalClassRoster(schoolId, className, classId, sectionId, session);

        // Computed once for the class (not per student); the verification token is per class.
        Map<String, Integer> classRanks = assembler.classRanksForTemplate(templateId, session);
        String verificationToken = publicationService.getVerificationToken(templateId, session, className).orElse(null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            for (Student student : students) {
                try {
                    // Unchanged Phase 0 behaviour: a student whose card cannot be generated is
                    // skipped (logged) and the ZIP still contains everyone else.
                    ReportCardDataDTO data = assembler.assemble(student.getStudentId(), templateId, session, classId, classRanks);
                    data.setVerificationToken(verificationToken);
                    byte[] pdf = pdfGenerator.generate(data);
                    String entryName = sanitizeFilename(student.getName()) + "_" + student.getStudentId() + ".pdf";
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(pdf);
                    zip.closeEntry();
                } catch (Exception e) {
                    // Skip students with incomplete data; continue with rest
                    org.slf4j.LoggerFactory.getLogger(ReportCardController.class)
                        .warn("Skipping report card for student {}: {}", student.getStudentId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate bulk PDF ZIP", e);
        }

        String zipFilename = sanitizeFilename(className) + "_" + session + "_ReportCards.zip";
        byte[] zipBytes = baos.toByteArray();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipFilename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(zipBytes.length))
                .body(zipBytes);
    }

    // ── Class Performance Overview ────────────────────────────────────────

    /**
     * Phase 7: Returns pre-computed weighted scores for all students in a class.
     * Lightweight — reads AssessmentGroupResult cache, no full assembly.
     * GET /api/report-cards/class-overview?templateId=&session=&className=
     */
    @GetMapping("/report-cards/class-overview")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ClassOverviewDTO> getClassOverview(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        Long sectionId = checkTeacherClassAccess(className);
        return ResponseEntity.ok(classOverviewService.getClassOverview(templateId, session, className, sectionId));
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /** GET /api/report-cards/publish?templateId=&session=&className= */
    @GetMapping("/report-cards/publish")
    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    public ResponseEntity<ReportCardPublicationDTO> getPublishStatus(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        checkTeacherClassAccess(className);
        return ResponseEntity.ok(publicationService.getStatus(templateId, session, className));
    }

    /** POST /api/report-cards/publish — publishes a class's report cards */
    @PostMapping("/report-cards/publish")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<ReportCardPublicationDTO> publish(
            @Valid @RequestBody PublishRequest req) {
        return ResponseEntity.ok(
            publicationService.publish(req.getTemplateId(), req.getSession(), req.getClassName()));
    }

    /** DELETE /api/report-cards/publish?templateId=&session=&className= */
    @DeleteMapping("/report-cards/publish")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Void> unpublish(
            @RequestParam Long templateId,
            @RequestParam String session,
            @RequestParam String className) {
        publicationService.unpublish(templateId, session, className);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/report-cards/email-blast
     * Validates publication exists, fires async blast, returns { initiated, message }.
     */
    @PostMapping("/report-cards/email-blast")
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    public ResponseEntity<Map<String, Object>> emailBlast(
            @Valid @RequestBody PublishRequest req) {
        entitlementService.requireFeature(securityUtil.getSchoolId(), "BULK_COMMUNICATIONS");
        int initiated = publicationService.startEmailBlast(
            req.getTemplateId(), req.getSession(), req.getClassName());
        return ResponseEntity.accepted().body(Map.of(
            "initiated", initiated,
            "message", "Email blast started for " + initiated + " student(s). Emails will arrive shortly."
        ));
    }

    // ── Teacher class-ownership checks ──────────────────────────────────────
    // None of the methods above originally cross-checked a TEACHER caller's own assigned class
    // against the className/studentId they requested — every one of them scoped only by
    // schoolId, letting any teacher read, download, or edit report-card data (including
    // remarks and co-scholastic grades) for any class in the school. Mirrors the same pattern
    // already used correctly by MarkController.checkTeacherClassAccess for the marks module.

    /**
     * For TEACHER callers: verifies their classTeacher field matches the given className, and
     * that their assignment isn't an unresolved legacy one. Throws a 403 ResponseStatusException
     * if access is denied; no-ops (ADMIN unrestricted) otherwise. Returns the teacher's own
     * effective sectionId (null for ADMIN, or for a class with no sections) — callers that fetch
     * a class-wide roster/summary must pass this through to their data layer so a section-scoped
     * teacher never sees another section's data, not just so they clear this class-level gate.
     */
    private Long checkTeacherClassAccess(String className) {
        if (!Role.TEACHER.equals(securityUtil.getRole())) return null;

        ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                securityUtil.getRole(), securityUtil.getUsername(), securityUtil.getSchoolId(), className, null);
        if (!access.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.errorMessage());
        }
        return access.effectiveSectionId();
    }

    /**
     * For TEACHER callers: verifies every one of the given studentIds belongs to their own
     * assigned class AND section. Used for single-student lookups (studentId) and bulk requests
     * (RemarksRequest/CoScholasticRequest, which carry a list of studentIds but no className
     * field directly) — resolves each student's class/section rather than trusting anything the
     * request itself claims.
     */
    private void checkTeacherOwnsStudents(List<String> studentIds) {
        if (!Role.TEACHER.equals(securityUtil.getRole())) return;
        if (studentIds == null || studentIds.isEmpty()) return;

        String teacherId = securityUtil.getUsername();
        Long schoolId = securityUtil.getSchoolId();

        for (String studentId : studentIds) {
            Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId).orElse(null);
            String studentClass = student != null ? student.getClassName() : null;
            Long studentSectionId = student != null ? student.getSectionId() : null;
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(
                    Role.TEACHER, teacherId, schoolId, studentClass, studentSectionId);
            if (!access.allowed()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.errorMessage());
            }
        }
    }

    /**
     * Student-facing HTML and PDF paths must enforce the same publication boundary. Authorization
     * itself (self-identity for STUDENT, current active parent-child link + RESULTS permission
     * for PARENT) remains entirely live/current — E6E only changes how the CLASS a historical
     * publication is checked against gets resolved. Historical enrollment never resurrects an
     * expired/inactive parent relationship; it only supplies the class context once the caller is
     * already authorized.
     */
    /** A student may only open their own card; a parent only a linked child with results access. */
    private void checkStudentOrParentOwnAccess(String studentId) {
        String role = securityUtil.getRole();
        if (Role.STUDENT.equals(role) && !studentId.equals(securityUtil.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Students can only access their own report card.");
        }
        if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.RESULTS);
        }
    }

    private void checkStudentOrParentPublishedAccess(String studentId, Long templateId, String session, Long classId) {
        String role = securityUtil.getRole();
        if (!Role.STUDENT.equals(role) && !Role.PARENT.equals(role)) return;

        if (Role.STUDENT.equals(role) && !studentId.equals(securityUtil.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Students can only access their own report card.");
        }
        if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(studentId, ParentPortalService.ChildPermission.RESULTS);
        }

        // Use the report card's own historical context resolution (never the student's current
        // class) so an already-published report card from before a promotion stays reachable.
        // Never arbitrarily picks a class when more than one is legitimate — see
        // ReportCardDataAssembler.resolveHistoricalContext.
        ReportCardDataAssembler.HistoricalReportCardContext context = resolveContextOrTranslate(studentId, session, classId);
        if (!publicationService.isPublished(templateId, session, context.className())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Report card has not been published yet.");
        }
        // Results Phase 1: an exam's results unpublished after the card was published hide it again.
        if (!publicationService.draftExamNames(templateId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Results for this report card are not published yet.");
        }
    }

    /** Translates the assembler's context-resolution exceptions into the appropriate HTTP
     *  response, shared by the publication-access check and the PDF/data assembly paths so both
     *  fail (or disambiguate) the exact same way for the exact same request.
     *  {@code ReportCardContextAmbiguousException} deliberately propagates uncaught — the two
     *  public endpoints catch it themselves to build the structured 409 body (see
     *  ambiguousResponse), since a bare message string isn't enough for the web UI to offer a
     *  choice or retry with classId. */
    private ReportCardDataAssembler.HistoricalReportCardContext resolveContextOrTranslate(
            String studentId, String session, Long classId) {
        try {
            return assembler.resolveHistoricalContext(studentId, session, classId);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** Shared by getReportCard/downloadPdf so an invalid classId is translated to the same HTTP
     *  response the access check above already uses, rather than surfacing as a 500. See
     *  resolveContextOrTranslate's Javadoc re: ReportCardContextAmbiguousException. */
    private ReportCardDataDTO assembleOrTranslate(String studentId, Long templateId, String session, Long classId) {
        try {
            return assembler.assemble(studentId, templateId, session, classId);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * E6F: structured 409 body for a genuine multi-class report-card ambiguity — the minimal
     * contract the web UI needs to show the candidate historical classes and let the user retry
     * with an explicit classId, rather than a bare message string. Each candidate class name is
     * resolved to its tenant SchoolClass id (null if a class was since renamed/retired — the UI
     * falls back to className-only display in that rare case, still usable for the retry itself
     * since the assembler also accepts a matching className... note: the retry contract is
     * classId-based, so a null id here just means that one candidate can't be re-selected until
     * the class is restored — extremely unlikely in practice, since the class must have existed
     * to be a candidate in the first place).
     */
    private ResponseEntity<Map<String, Object>> ambiguousResponse(
            String studentId, ReportCardDataAssembler.ReportCardContextAmbiguousException e) {
        Long schoolId = securityUtil.getSchoolId();
        List<Map<String, Object>> candidates = e.getCandidates().stream()
                .map(className -> {
                    Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                            .map(com.indraacademy.ias_management.entity.SchoolClass::getId).orElse(null);
                    java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("classId", classId);
                    row.put("className", className);
                    return (Map<String, Object>) row;
                })
                .toList();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ambiguous", true);
        body.put("studentId", studentId);
        body.put("message", "This student has more than one historical class for this session. " +
                "Select the intended class to continue.");
        body.put("candidates", candidates);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Enrollment-authoritative historical roster (E6E) for bulk PDF generation: the live ACTIVE
     * roster (unchanged default for schools with no enrollment data) unioned with every student
     * realized-enrolled in this class(+section) at any point during the session — so a promoted,
     * transferred, or withdrawn student's report card is still produced. Reuses the same
     * StudentEnrollmentRepository queries E6C/E6D already added; no new temporal-membership logic.
     */
    private List<Student> historicalClassRoster(Long schoolId, String className, Long classId, Long sectionId, String session) {
        List<Student> liveStudents = (sectionId != null)
                ? studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.ACTIVE, schoolId)
                : studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId);
        Map<String, Student> roster = new java.util.LinkedHashMap<>();
        liveStudents.forEach(s -> roster.put(s.getStudentId(), s));

        if (classId == null) return new ArrayList<>(roster.values());
        academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).ifPresent(as -> {
            java.util.List<com.indraacademy.ias_management.entity.StudentEnrollment> rows = (sectionId != null)
                    ? studentEnrollmentRepository.findRealizedByAcademicSessionAndClassAndSectionOverlappingRange(
                            schoolId, as.getId(), classId, sectionId, as.getStartDate(), as.getEndDate())
                    : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                            schoolId, as.getId(), classId, as.getStartDate(), as.getEndDate());
            for (com.indraacademy.ias_management.entity.StudentEnrollment row : rows) {
                roster.computeIfAbsent(row.getStudentId(),
                        sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
            }
        });
        roster.values().removeIf(java.util.Objects::isNull);
        return new ArrayList<>(roster.values());
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "Unknown";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
