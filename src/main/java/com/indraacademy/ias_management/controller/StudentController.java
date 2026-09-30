package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.BulkImportResultDTO;
import com.indraacademy.ias_management.dto.CorrectPlannedEnrollmentRequest;
import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.dto.PromotionResultDTO;
import com.indraacademy.ias_management.dto.StudentExitRequest;
import com.indraacademy.ias_management.dto.StudentLeaveDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.StudentBulkImportService;
import com.indraacademy.ias_management.service.StudentPromotionService;
import com.indraacademy.ias_management.service.StudentService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.ObjectStorageService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import com.indraacademy.ias_management.dto.StudentAdmissionDtos;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/students")
@PreAuthorize("isAuthenticated()")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    @Autowired private StudentService studentService;
    @Autowired private StudentBulkImportService studentBulkImportService;
    @Autowired private StudentPromotionService studentPromotionService;
    @Autowired(required = false) private com.indraacademy.ias_management.service.PromotionResultContextService promotionResultContext;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private TeacherClassScopeService teacherClassScopeService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private ObjectStorageService objectStorageService;

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/search")
    public ResponseEntity<List<Student>> searchStudents(@RequestParam String q) {
        log.info("Request to search students with query: {}", q);
        return ResponseEntity.ok(studentService.searchStudents(q));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping
    public ResponseEntity<?> registerStudent(@Valid @RequestBody StudentAdmissionDtos.AdmissionRequest newStudent,
                                             HttpServletRequest request) {
        // Only admin-editable fields are bound (StudentAdmissionDtos.AdmissionRequest): the Student
        // ID is always generated, and status, school, exit details and photo can't be sent. The
        // student, enrollment and login are created in one transaction.
        log.info("Request to register new student.");
        try {
            Student savedStudent = studentService.addStudent(newStudent, request);
            log.info("Student registered successfully with ID: {}", savedStudent.getStudentId());
            return new ResponseEntity<>(savedStudent, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("already exists")) {
                // Duplicate student ID within this school — 409 Conflict is correct
                log.warn("Student registration failed (Conflict): {}", msg);
                return new ResponseEntity<>(msg, HttpStatus.CONFLICT);
            }
            // Missing or invalid input data — 400 Bad Request
            log.warn("Student registration failed (Bad Request): {}", msg);
            return new ResponseEntity<>(msg, HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            log.error("Unexpected error during student registration.", e);
            return new ResponseEntity<>("Failed to register student.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/{studentId}")
    public ResponseEntity<Student> getStudent(@PathVariable String studentId) {
        String role = authService.getRole();

        final String resolvedStudentId;

        if(Role.STUDENT.equals(role)){
            resolvedStudentId = authService.getUserId();
            log.info("Student accessing their own record with ID: {}", resolvedStudentId);
        } else if (Role.PARENT.equals(role)) {
            parentPortalService.assertChildAccess(studentId);
            resolvedStudentId = studentId;
            log.info("Parent accessing linked student record with ID: {}", resolvedStudentId);
        } else {
            resolvedStudentId = studentId;
            log.info("Admin/Teacher accessing student record with ID: {}", resolvedStudentId);
        }

        Optional<Student> student = studentService.getStudent(resolvedStudentId);
        if (Role.TEACHER.equals(role) && student.isPresent()) {
            // A teacher may open only a student of the class/section they are responsible for —
            // the same rule every other per-student teacher endpoint already applies.
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToStudent(role, authService.getUserId(),
                    securityUtil.getSchoolId(), student.get().getClassName(), student.get().getSectionId());
            if (!access.allowed()) {
                throw new org.springframework.security.access.AccessDeniedException(access.errorMessage());
            }
        }
        student.ifPresent(this::resolvePhotoUrlForDisplay);
        return student.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // Use the final variable in the lambda
                    log.warn("Student with ID {} not found.", resolvedStudentId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Called only after the role-based resolvedStudentId logic above (STUDENT self-only, PARENT
     * via assertChildAccess, ADMIN/TEACHER unrestricted) has already run, so swapping in a fresh
     * presigned GET URL here introduces no new access surface. A legacy /uploads/student-photos/...
     * value is left completely untouched — Phase 3 removed its serving controller (no production
     * student photo still needs it, see the Phase 3 report) — see
     * ObjectStorageService.resolveDisplayUrl.
     */
    private void resolvePhotoUrlForDisplay(Student student) {
        student.setPhotoUrl(objectStorageService.resolveDisplayUrl(student.getPhotoUrl()));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{studentId}")
    public ResponseEntity<Student> updateStudent(@PathVariable String studentId,
                                                 @Valid @RequestBody StudentAdmissionDtos.UpdateEnvelope requestBody,
                                                 HttpServletRequest request) {
        log.info("Request to update student details for ID: {}", studentId);
        // Only admin-editable fields bind; anything else a client sends back (status, photoUrl —
        // including a short-lived signed URL from a GET — exit details) is ignored.
        if (requestBody == null || requestBody.studentDetails() == null) {
            log.warn("Update student failed: Missing studentDetails");
            return ResponseEntity.badRequest().build();
        }

        Student savedStudent = studentService.updateStudent(studentId, requestBody.studentDetails(),
                requestBody.effectiveFromMonth(), request);
        resolvePhotoUrlForDisplay(savedStudent);
        log.info("Student updated successfully with ID: {}", studentId);
        return ResponseEntity.ok(savedStudent);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/new/class/{className}")
    public List<StudentLeaveDTO> getNewStudentsByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        log.info("Request to get UPCOMING students for class: {}, section: {}", className, sectionId);
        List<Student> students = sectionId != null
                ? studentService.getUpcomingStudentsByClassAndSection(className, sectionId)
                : studentService.getUpcomingStudentsByClass(className);
        return students.stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName(), s.getSectionId()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/active/class/{className}")
    public ResponseEntity<?> findActiveStudentsByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        // TEACHER: only their assigned class/section — mirrors the same check already used in
        // AttendanceController for the identical isolation concern. Effective sectionId always
        // comes from the teacher's own assignment, never the client-supplied value.
        if (Role.TEACHER.equals(authService.getRole())) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }
        log.info("Request to get ACTIVE students for class: {}, section: {}", className, sectionId);
        List<Student> students = sectionId != null
                ? studentService.getActiveStudentsByClassAndSection(className, sectionId)
                : studentService.getActiveStudentsByClass(className);
        return ResponseEntity.ok(students.stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName(), s.getSectionId()))
                .collect(Collectors.toList()));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/inactive/class/{className}")
    public List<StudentLeaveDTO> getInactiveStudentsByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        log.info("Request to get INACTIVE students for class: {}, section: {}", className, sectionId);
        List<Student> students = sectionId != null
                ? studentService.getInactiveStudentsByClassAndSection(className, sectionId)
                : studentService.getInactiveStudentsByClass(className);
        return students.stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName(), s.getSectionId()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkImportStudents(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        log.info("Received bulk student import request, file size: {} bytes", file.getSize());
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Uploaded file is empty.");
        }
        BulkImportResultDTO result = studentBulkImportService.bulkImport(file, request);
        log.info("Bulk import completed: {} total, {} successful, {} failed",
                result.getTotalRows(), result.getSuccessful(), result.getFailed());
        return ResponseEntity.ok(result);
    }

    // ─── Promotion endpoints ──────────────────────────────────────────────────

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/promotion/preview")
    public ResponseEntity<PromotionPreviewDTO> getPromotionPreview(
            @RequestParam Long sourceSessionId,
            @RequestParam Long targetSessionId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String studentId) {
        log.info("Request for student promotion preview: sourceSessionId={}, targetSessionId={}",
                sourceSessionId, targetSessionId);
        PromotionPreviewDTO preview = studentPromotionService.getPromotionPreview(
                sourceSessionId, targetSessionId, classId, studentId);
        // Read-only result context (published report card / published results), added after the
        // preview's own transaction; it never affects which decisions are allowed.
        if (promotionResultContext != null) {
            preview = promotionResultContext.enrich(securityUtil.getSchoolId(), preview);
        }
        return ResponseEntity.ok(preview);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/promotion/runs")
    public ResponseEntity<List<PromotionResultDTO.RunSummary>> getPromotionRuns(@RequestParam Long targetSessionId) {
        return ResponseEntity.ok(studentPromotionService.recentRuns(targetSessionId));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/promotion/execute")
    public ResponseEntity<PromotionResultDTO> executePromotion(
            @Valid @RequestBody PromotionDecisionRequest request,
            HttpServletRequest httpRequest) {
        int count = request.getDecisions() != null ? request.getDecisions().size() : 0;
        log.warn("Request to execute promotion for {} student decisions", count);
        return ResponseEntity.ok(studentPromotionService.executePromotion(request, httpRequest));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/promotion/fix-orphaned-sections")
    public ResponseEntity<Map<String, Object>> fixOrphanedSections() {
        int affected = studentPromotionService.fixOrphanedSections();
        return ResponseEntity.ok(Map.of("affected", affected,
                "message", affected + " student section assignment(s) cleared."));
    }

    /**
     * Permanently deletes a student and their related attendance and leave records
     * within the admin's school. Refused with 409 if the student has any retained
     * financial history (fees, payments, allocations) — deactivate via the exit
     * workflow instead. This is a destructive, irreversible operation — use with caution.
     */
    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @DeleteMapping("/{studentId}")
    public ResponseEntity<?> deleteStudent(@PathVariable String studentId, HttpServletRequest request) {
        log.warn("Request to permanently delete student: {}", studentId);
        try {
            studentService.deleteStudent(studentId, request);
            log.info("Student {} deleted successfully.", studentId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            log.warn("Delete failed — student not found: {}", studentId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            log.warn("Delete refused — {}: {}", studentId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error deleting student: {}", studentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete student.");
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/bulk/template")
    public ResponseEntity<byte[]> downloadBulkImportTemplate() {
        log.info("Request to download student bulk import CSV template");
        String csvContent = String.join(",", StudentBulkImportService.TEMPLATE_HEADERS) + "\r\n";
        byte[] bytes = csvContent.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"student_import_template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    // ─── Exit / Re-admission endpoints ───────────────────────────────────────

    @PreAuthorize("hasAnyRole('" + Role.ADMIN + "', '" + Role.TEACHER + "')")
    @GetMapping("/alumni/class/{className}")
    public ResponseEntity<?> getAlumniByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        // TEACHER: only their assigned class/section — same check as findActiveStudentsByClass
        // above. Effective sectionId always comes from the teacher's own assignment, never the
        // client-supplied value.
        if (Role.TEACHER.equals(authService.getRole())) {
            ScopedAccess access = teacherClassScopeService.authorizeAndScopeToClass(
                    authService.getRole(), authService.getUserId(), securityUtil.getSchoolId(), className, sectionId);
            if (!access.allowed()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(access.errorMessage());
            }
            sectionId = access.effectiveSectionId();
        }
        log.info("Request to get GRADUATED students for class: {}, section: {}", className, sectionId);
        List<Student> students = sectionId != null
                ? studentService.getGraduatedStudentsByClassAndSection(className, sectionId)
                : studentService.getGraduatedStudentsByClass(className);
        return ResponseEntity.ok(students.stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName(), s.getSectionId()))
                .collect(Collectors.toList()));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/left/class/{className}")
    public List<StudentLeaveDTO> getLeftStudentsByClass(
            @PathVariable String className,
            @RequestParam(required = false) Long sectionId) {
        log.info("Request to get LEFT (TRANSFERRED/WITHDRAWN) students for class: {}, section: {}", className, sectionId);
        List<Student> students = sectionId != null
                ? studentService.getLeftStudentsByClassAndSection(className, sectionId)
                : studentService.getLeftStudentsByClass(className);
        return students.stream()
                .map(s -> new StudentLeaveDTO(s.getStudentId(), s.getName(), s.getSectionId()))
                .collect(Collectors.toList());
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/{studentId}/pending-dues")
    public ResponseEntity<Map<String, Object>> checkPendingDues(@PathVariable String studentId) {
        log.info("Request to check pending dues for student: {}", studentId);
        return ResponseEntity.ok(studentService.checkPendingDues(studentId));
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/exit")
    public ResponseEntity<?> exitStudent(
            @PathVariable String studentId,
            @Valid @RequestBody StudentExitRequest request,
            HttpServletRequest httpRequest) {
        log.warn("Request to exit student: {} as {}", studentId, request.getExitType());
        try {
            Student student = studentService.exitStudent(studentId, request, httpRequest);
            return ResponseEntity.ok(student);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PutMapping("/{studentId}/enrollment/{enrollmentId}/correct-planned")
    public ResponseEntity<?> correctPlannedEnrollment(
            @PathVariable String studentId,
            @PathVariable Long enrollmentId,
            @Valid @RequestBody CorrectPlannedEnrollmentRequest request,
            HttpServletRequest httpRequest) {
        log.warn("Request to correct PLANNED enrollment {} for student: {}", enrollmentId, studentId);
        try {
            com.indraacademy.ias_management.entity.StudentEnrollment corrected = studentService.correctPlannedEnrollment(
                    studentId, enrollmentId, request.getTargetClassId(), request.getTargetSectionId(), httpRequest);
            return ResponseEntity.ok(corrected);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/readmit")
    public ResponseEntity<?> readmitStudent(
            @PathVariable String studentId,
            @RequestBody(required = false) StudentAdmissionDtos.ReadmitRequest choice,
            HttpServletRequest httpRequest) {
        log.warn("Request to re-admit student: {}", studentId);
        try {
            Student student = studentService.readmitStudent(studentId, choice, httpRequest);
            return ResponseEntity.ok(student);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/cancel-admission")
    public ResponseEntity<?> cancelAdmission(
            @PathVariable String studentId,
            @Valid @RequestBody(required = false) StudentAdmissionDtos.CancelAdmissionRequest body,
            HttpServletRequest httpRequest) {
        log.warn("Request to cancel admission of student: {}", studentId);
        try {
            return ResponseEntity.ok(studentService.cancelAdmission(studentId, body == null ? null : body.reason(), httpRequest));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/{studentId}/login")
    public StudentAdmissionDtos.LoginStatus loginStatus(@PathVariable String studentId) {
        return studentService.loginStatus(studentId);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/login")
    public ResponseEntity<?> createMissingLogin(@PathVariable String studentId, HttpServletRequest httpRequest) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(studentService.createMissingLogin(studentId, httpRequest));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/{studentId}/enrollments")
    public List<StudentAdmissionDtos.EnrollmentHistoryItem> enrollmentHistory(@PathVariable String studentId) {
        return studentService.enrollmentHistory(studentId);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @GetMapping("/{studentId}/restorable-parent-links")
    public List<StudentAdmissionDtos.RestorableParentLink> restorableParentLinks(@PathVariable String studentId) {
        return studentService.restorableParentLinks(studentId);
    }

    @PreAuthorize("hasRole('" + Role.ADMIN + "')")
    @PostMapping("/{studentId}/restore-parent-links")
    public Map<String, Integer> restoreParentLinks(@PathVariable String studentId,
                                                   @RequestBody StudentAdmissionDtos.RestoreParentLinksRequest body,
                                                   HttpServletRequest httpRequest) {
        return Map.of("restored", studentService.restoreParentLinks(studentId,
                body == null ? List.of() : body.relationshipIds(), httpRequest));
    }

}
