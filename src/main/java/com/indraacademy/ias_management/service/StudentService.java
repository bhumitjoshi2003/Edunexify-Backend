package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.LimitType;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Section;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentAttendanceRepository;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.PaymentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentFeesRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SectionRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.StudentMarkRepository;
import com.indraacademy.ias_management.dto.StudentAdmissionDtos;
import com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import java.time.LocalDate;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);

    /** "Left" list: students who left, plus cancelled admissions (they never joined but must stay findable). */
    private static final List<StudentStatus> LEFT_STATUSES =
            List.of(StudentStatus.TRANSFERRED, StudentStatus.WITHDRAWN, StudentStatus.ADMISSION_CANCELLED);

    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentFeesService studentFeesService;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private UserDetailsServiceImpl userDetailsService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AuditService auditService;
    @Autowired private EntitlementService entitlementService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StudentAttendanceRepository studentAttendanceRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private LeaveRepository leaveRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private ParentPortalService parentPortalService;
    @Autowired private IdGeneratorService idGeneratorService;
    @Autowired private AcademicSessionRepository academicSessionRepository;
    @Autowired private StudentEnrollmentService studentEnrollmentService;
    @Autowired private StudentEnrollmentRepository studentEnrollmentRepository;
    @Autowired private Clock clock;
    @Autowired private StudentLoginService studentLoginService;
    @Autowired private StudentMarkRepository studentMarkRepository;

    private LocalDate schoolToday(Long schoolId) {
        var school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found"));
        return LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
    }

    private AcademicSession requireSessionContaining(Long schoolId, LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("Joining date is required to resolve academic session membership.");
        }
        List<AcademicSession> matches = academicSessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(schoolId, date, date);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "No configured academic session contains " + date + ". Configure the session before admitting the student.");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple academic sessions contain " + date + "; resolve the session overlap before admitting the student.");
        }
        return matches.get(0);
    }

    private Optional<AcademicSession> findSessionContaining(Long schoolId, LocalDate date) {
        List<AcademicSession> matches = academicSessionRepository
                .findAllBySchoolIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(schoolId, date, date);
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple academic sessions contain " + date + "; resolve the session overlap before changing membership.");
        }
        return matches.stream().findFirst();
    }

    /**
     * Resolves {@code className} against the school's configured SchoolClass rows.
     * SchoolClass is the single source of truth for which classes exist — a student
     * can never be created/updated against a free-text class name that wasn't first
     * created in Class Management.
     */
    private SchoolClass resolveAndValidateClass(Long schoolId, String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Class is required.");
        }
        return schoolClassRepository.findBySchoolIdAndName(schoolId, className.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Class '" + className + "' is not configured for this school. " +
                        "Please add it in Class Management before assigning students to it."));
    }

    /**
     * If a sectionId is supplied, validates it belongs to this school AND to the
     * already-resolved class, then sets the canonical sectionName. A cross-school or
     * cross-class section is rejected rather than silently accepted or dropped.
     */
    private void resolveAndValidateSection(Long schoolId, SchoolClass schoolClass, Student student) {
        if (student.getSectionId() == null) {
            return;
        }
        Section section = sectionRepository.findByIdAndSchoolId(student.getSectionId(), schoolId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Section not found for this school."));
        if (!section.getClassId().equals(schoolClass.getId())) {
            throw new IllegalArgumentException(
                    "Section '" + section.getName() + "' does not belong to class '" + schoolClass.getName() + "'.");
        }
        student.setSectionName(section.getName());
    }

    @Transactional(readOnly = true)
    public List<Student> searchStudents(String query) {
        if (query == null || query.trim().length() < 2) return List.of();
        Long schoolId = securityUtil.getSchoolId();
        return studentRepository.searchByNameOrIdAndSchoolId(query.trim(), schoolId);
    }

    /** Admission from the admin form: only the admin-editable fields are read. */
    @Transactional
    public Student addStudent(StudentAdmissionDtos.AdmissionRequest admission, HttpServletRequest request) {
        if (admission == null) {
            throw new IllegalArgumentException("Student object must be provided.");
        }
        Student student = new Student();
        student.setName(trimToNull(admission.name()));
        student.setEmail(trimToNull(admission.email()));
        student.setPhoneNumber(trimToNull(admission.phoneNumber()));
        student.setDob(admission.dob());
        student.setClassName(admission.className());
        student.setSectionId(admission.sectionId());
        student.setGender(trimToNull(admission.gender()));
        student.setFatherName(trimToNull(admission.fatherName()));
        student.setMotherName(trimToNull(admission.motherName()));
        student.setTakesBus(Boolean.TRUE.equals(admission.takesBus()));
        student.setDistance(admission.distance());
        student.setJoiningDate(admission.joiningDate());
        return addStudent(student, request);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Canonical admission: student, enrollment and login are created in one transaction, so a
     * failure at any step leaves nothing behind. Lifecycle-controlled fields on the incoming
     * object are always reset here.
     */
    @Transactional
    public Student addStudent(Student student, HttpServletRequest request) {
        if (student == null) {
            log.error("Attempted to add a null Student object.");
            throw new IllegalArgumentException("Student object must be provided.");
        }
        if (student.getDob() == null) {
            log.warn("Attempted to add student without a date of birth.");
            throw new IllegalArgumentException("Date of birth is required because it is used as the initial password.");
        }
        // Edunexify generates the Student ID for every new account going forward — never
        // an admin-typed or CSV-supplied value, regardless of what the caller sent (both
        // StudentController.registerStudent and StudentBulkImportService already omit any
        // client-supplied studentId before calling this method, but this check is the real
        // enforcement point: nothing reaches here with an admin-chosen ID).
        if (student.getStudentId() == null || student.getStudentId().trim().isEmpty()) {
            student.setStudentId(idGeneratorService.generateStudentId());
        }
        log.info("Attempting to add new student with ID: {}", student.getStudentId());
        student.setLeavingDate(null);
        student.setReasonForLeaving(null);
        student.setConductAtLeaving(null);
        student.setExitRemarks(null);
        student.setPhotoUrl(null);

        Long schoolId = securityUtil.getSchoolId();
        try {
            entitlementService.checkLimit(schoolId, LimitType.STUDENTS, 1);
        } catch (IllegalStateException e) {
            // No subscription configured yet — allow the operation
            log.debug("No entitlement for school {} — skipping student limit check", schoolId);
        }
        try {
            Optional<Student> existingStudent = studentRepository.findByStudentIdAndSchoolId(student.getStudentId(), schoolId);
            if (existingStudent.isPresent()) {
                log.warn("Student with ID {} already exists in school {}.", student.getStudentId(), schoolId);
                throw new IllegalArgumentException("Student with ID " + student.getStudentId() + " already exists.");
            }
            // Student IDs are unique across the entire platform, not just within a school —
            // studentId is the actual database primary key (see Student.java). This check
            // catches a cross-school collision with a clear message before save() ever runs;
            // Student now implementing Persistable also makes save() itself reject a genuine
            // conflict (INSERT, not silent upsert) as a second layer if this check races.
            // A generated ID should never actually collide (see IdGeneratorService), so this
            // path realistically only fires for the exceedingly rare case of two concurrent
            // requests both retrying after a transient failure — kept as defense in depth.
            if (studentRepository.existsById(student.getStudentId())) {
                log.warn("Student ID {} is already registered under a different school.", student.getStudentId());
                throw new IllegalArgumentException("Student ID " + student.getStudentId() + " is already in use. Student IDs must be unique across the entire platform.");
            }

            LocalDate today = schoolToday(schoolId);
            if (student.getJoiningDate() != null && student.getJoiningDate().isAfter(today)) {
                student.setStatus(StudentStatus.UPCOMING);
            }
            else {
                student.setStatus(StudentStatus.ACTIVE);
            }
            student.setSchoolId(schoolId);
            // SchoolClass is authoritative — reject unconfigured classes and never leave classId null.
            SchoolClass schoolClass = resolveAndValidateClass(schoolId, student.getClassName());
            student.setClassName(schoolClass.getName());
            student.setClassId(schoolClass.getId());
            resolveAndValidateSection(schoolId, schoolClass, student);
            AcademicSession session = requireSessionContaining(schoolId, student.getJoiningDate());
            if (session.getEndDate() != null && session.getEndDate().isBefore(today)) {
                throw new IllegalArgumentException("The joining date " + student.getJoiningDate()
                        + " belongs to a past academic session (" + session.getLabel() + "). Historical admissions "
                        + "can't be created through the admission form — use a joining date in the current or a future session.");
            }
            Student savedStudent = studentRepository.saveAndFlush(student);
            if (student.getStatus() == StudentStatus.UPCOMING) {
                // The Student columns remain populated for current UI/authorization compatibility,
                // but this PLANNED row is the authoritative future class/section membership.
                studentEnrollmentService.createPlannedEnrollment(
                        schoolId, savedStudent.getStudentId(), session.getId(), schoolClass.getId(),
                        savedStudent.getSectionId(), savedStudent.getJoiningDate());
            } else {
                studentEnrollmentService.createActiveEnrollment(
                        schoolId, savedStudent.getStudentId(), session.getId(), schoolClass.getId(),
                        savedStudent.getSectionId(), savedStudent.getJoiningDate());
            }

            // The login is part of the admission: if it can't be created, the whole admission
            // rolls back instead of leaving a student without an account.
            studentLoginService.create(savedStudent);

            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "CREATE_STUDENT",
                    "Student",
                    savedStudent.getStudentId(),
                    null,
                    objectMapper.writeValueAsString(savedStudent),
                    request.getRemoteAddr()
            );

            log.info("Successfully saved new student with ID: {}", savedStudent.getStudentId());

            // Admission and billing are deliberately separate lifecycles. A newly admitted
            // student starts as NOT_ASSIGNED in the fee workflow and receives no financial
            // rows until an admin explicitly assigns months, previews them and confirms
            // generation. This also keeps registration available while a school's fee module
            // is disabled, in draft, or not yet configured.
            log.info("Student {} registered without automatic fee generation; fee assignment is pending.",
                    savedStudent.getStudentId());

            return savedStudent;
        } catch (DataAccessException e) {
            log.error("Data access error while adding student with ID: {}", student.getStudentId(), e);
            throw new RuntimeException("Failed to add student due to a database issue.", e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw specific business exceptions
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Audit log serialisation failure — student was saved; log and continue
            log.warn("Failed to serialise student {} for audit log: {}", student.getStudentId(), e.getMessage());
            return student;
        } catch (Exception e) {
            log.error("Unexpected error while adding student with ID: {}", student.getStudentId(), e);
            throw new RuntimeException("An unexpected error occurred while adding the student.", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Student> getStudent(String studentId) {
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get student with null/empty ID.");
            return Optional.empty();
        }
        log.info("Fetching student with ID: {}", studentId);
        try {
            return studentRepository.findByStudentIdAndSchoolId(studentId, securityUtil.getSchoolId());
        } catch (DataAccessException e) {
            log.error("Data access error fetching student with ID: {}", studentId, e);
            throw new RuntimeException("Failed to retrieve student data.", e);
        }
    }

    @Transactional(readOnly = true)
    public List<Student> getActiveStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getActiveStudentsByClassAndSection(String className, Long sectionId) {
        return studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.ACTIVE, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getUpcomingStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.UPCOMING, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getUpcomingStudentsByClassAndSection(String className, Long sectionId) {
        return studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.UPCOMING, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getInactiveStudentsByClass(String className) {
        // Backward compat: returns all non-active/non-upcoming students
        return getLeftStudentsByClass(className);
    }

    @Transactional(readOnly = true)
    public List<Student> getInactiveStudentsByClassAndSection(String className, Long sectionId) {
        return getLeftStudentsByClassAndSection(className, sectionId);
    }

    @Transactional(readOnly = true)
    public List<Student> getGraduatedStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.GRADUATED, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getGraduatedStudentsByClassAndSection(String className, Long sectionId) {
        return studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(className, sectionId, StudentStatus.GRADUATED, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getLeftStudentsByClass(String className) {
        return studentRepository.findByClassNameAndStatusInAndSchoolId(
                className, LEFT_STATUSES, securityUtil.getSchoolId());
    }

    @Transactional(readOnly = true)
    public List<Student> getLeftStudentsByClassAndSection(String className, Long sectionId) {
        return studentRepository.findByClassNameAndSectionIdAndStatusInAndSchoolId(
                className, sectionId, LEFT_STATUSES, securityUtil.getSchoolId());
    }

    // ── Exit Workflow ──────────────────────────────────────────────────

    @Transactional
    public Student exitStudent(String studentId, com.indraacademy.ias_management.dto.StudentExitRequest request, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        StudentStatus exitStatus;
        try {
            exitStatus = StudentStatus.valueOf(request.getExitType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid exit type: " + request.getExitType() + ". Valid: GRADUATED, TRANSFERRED, WITHDRAWN");
        }
        if (exitStatus != StudentStatus.GRADUATED && exitStatus != StudentStatus.TRANSFERRED
                && exitStatus != StudentStatus.WITHDRAWN) {
            throw new IllegalArgumentException("Invalid exit type: " + request.getExitType() + ". Valid: GRADUATED, TRANSFERRED, WITHDRAWN");
        }

        LocalDate exitDate = request.getLeavingDate();
        if (exitDate.isAfter(schoolToday(schoolId))) {
            throw new IllegalArgumentException("Leaving date cannot be in the future");
        }
        Optional<AcademicSession> exitSession = findSessionContaining(schoolId, exitDate);
        StudentEnrollmentService.LifecycleMutation lifecycle = studentEnrollmentService.closeForExplicitExit(
                schoolId, studentId, exitSession.map(AcademicSession::getId).orElse(null), exitDate,
                com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason.valueOf(exitStatus.name()));
        Student student = lifecycle.student();

        String oldStatus = student.getStatus().name();
        student.setStatus(exitStatus);
        student.setReasonForLeaving(request.getReasonForLeaving());
        student.setConductAtLeaving(request.getConductAtLeaving());
        student.setLeavingDate(request.getLeavingDate());
        student.setExitRemarks(request.getExitRemarks());

        Student saved = studentRepository.save(student);
        parentPortalService.endRelationshipsForExitedStudent(
                schoolId, studentId, request.getLeavingDate());
        // A graduate keeps their login (past results and report cards); a student who
        // transferred or withdrew loses login access now — the exit date is never in the future.
        if (exitStatus != StudentStatus.GRADUATED) {
            studentLoginService.deactivate(schoolId, studentId);
        }
        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(),
                "EXIT_STUDENT", "Student", studentId,
                oldStatus, exitStatus.name(), httpRequest.getRemoteAddr());
        log.info("Student {} exited as {} — reason: {}", studentId, exitStatus, request.getReasonForLeaving());
        return saved;
    }

    @Transactional
    public StudentEnrollment correctPlannedEnrollment(
            String studentId, Long enrollmentId, Long targetClassId, Long targetSectionId,
            HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        StudentEnrollment corrected = studentEnrollmentService.correctPlannedEnrollment(
                schoolId, studentId, enrollmentId, targetClassId, targetSectionId);
        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(),
                "CORRECT_PLANNED_ENROLLMENT", "StudentEnrollment", String.valueOf(enrollmentId),
                null, "classId=" + corrected.getClassId() + ",sectionId=" + corrected.getSectionId(),
                httpRequest.getRemoteAddr());
        return corrected;
    }

    @Transactional(readOnly = true)
    public java.util.Map<String, Object> checkPendingDues(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        List<com.indraacademy.ias_management.entity.StudentFees> unpaid =
                studentFeesRepository.findByStudentIdAndSchoolIdAndPaidFalse(studentId, schoolId);

        int unpaidMonths = unpaid.size();
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("hasPendingDues", unpaidMonths > 0);
        result.put("unpaidMonths", unpaidMonths);
        return result;
    }

    @Transactional
    public Student readmitStudent(String studentId, HttpServletRequest httpRequest) {
        return readmitStudent(studentId, null, httpRequest);
    }

    /**
     * Readmits an exited student into a chosen class/section from a chosen date (default today).
     * The same Student record is reused and a new enrollment is created; closed history is never
     * altered. Previously ended parent links are NOT restored here — the admin decides that
     * separately (see {@link #restorableParentLinks}).
     */
    @Transactional
    public Student readmitStudent(String studentId, StudentAdmissionDtos.ReadmitRequest choice,
                                  HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        LocalDate today = schoolToday(schoolId);
        LocalDate readmissionDate = choice != null && choice.readmissionDate() != null ? choice.readmissionDate() : today;
        if (readmissionDate.isAfter(today)) {
            throw new IllegalArgumentException("The readmission date cannot be in the future.");
        }
        Student studentSnapshot = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        if (studentSnapshot.getLeavingDate() != null && !readmissionDate.isAfter(studentSnapshot.getLeavingDate())) {
            throw new IllegalArgumentException("The readmission date must be after the leaving date ("
                    + studentSnapshot.getLeavingDate() + ").");
        }
        Optional<AcademicSession> session = findSessionContaining(schoolId, readmissionDate);
        if (session.isPresent() && session.get().getEndDate().isBefore(today)) {
            throw new IllegalArgumentException("The readmission date belongs to a past academic session ("
                    + session.get().getLabel() + "). Choose a date in the current session.");
        }
        Long classId = studentSnapshot.getClassId();
        Long sectionId = studentSnapshot.getSectionId();
        if (choice != null && choice.classId() != null) {
            classId = choice.classId();
            sectionId = choice.sectionId();
        }
        StudentEnrollmentService.LifecycleMutation lifecycle = studentEnrollmentService.createForExplicitReadmission(
                schoolId, studentId, session.map(AcademicSession::getId).orElse(null),
                classId, sectionId, readmissionDate);
        Student student = lifecycle.student();
        if (lifecycle.legacyUncovered() && choice != null && choice.classId() != null) {
            // No enrollment history (pre-enrollment data): keep the projection in step with the choice.
            SchoolClass schoolClass = schoolClassRepository.findByIdAndSchoolId(classId, schoolId)
                    .orElseThrow(() -> new IllegalArgumentException("Class not found for this school."));
            student.setClassId(schoolClass.getId());
            student.setClassName(schoolClass.getName());
            student.setSectionId(sectionId);
            student.setSectionName(null);
            resolveAndValidateSection(schoolId, schoolClass, student);
        }

        String oldStatus = student.getStatus().name();
        student.setStatus(StudentStatus.ACTIVE);
        student.setReasonForLeaving(null);
        student.setConductAtLeaving(null);
        student.setExitRemarks(null);
        student.setLeavingDate(null);

        Student saved = studentRepository.save(student);
        studentLoginService.reactivate(schoolId, studentId);
        auditService.log(
                securityUtil.getUsername(), securityUtil.getRole(),
                "READMIT_STUDENT", "Student", studentId,
                oldStatus, "ACTIVE classId=" + saved.getClassId() + ",sectionId=" + saved.getSectionId()
                        + ",from=" + readmissionDate, httpRequest.getRemoteAddr());
        log.info("Student {} re-admitted (was {}) from {}", studentId, oldStatus, readmissionDate);
        return saved;
    }

    // ── Cancel admission / login / history ─────────────────────────────

    /**
     * Cancels an UPCOMING student's admission before it starts. The PLANNED enrollment is kept
     * as CANCELLED_BEFORE_START (never deleted), the student becomes ADMISSION_CANCELLED so the
     * scheduler can never activate them, any login is deactivated and parent links are ended.
     */
    @Transactional
    public Student cancelAdmission(String studentId, String reason, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        LocalDate today = schoolToday(schoolId);
        studentEnrollmentService.cancelPlannedAdmission(schoolId, studentId);
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        student.setStatus(StudentStatus.ADMISSION_CANCELLED);
        student.setReasonForLeaving(trimToNull(reason) == null ? "Admission cancelled before joining" : reason.trim());
        student.setLeavingDate(null);
        Student saved = studentRepository.save(student);
        studentLoginService.deactivate(schoolId, studentId);
        parentPortalService.endRelationshipsForExitedStudent(schoolId, studentId, today);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "CANCEL_ADMISSION", "Student",
                studentId, "UPCOMING", "ADMISSION_CANCELLED", httpRequest.getRemoteAddr());
        log.info("Admission cancelled for student {}", studentId);
        return saved;
    }

    @Transactional(readOnly = true)
    public StudentAdmissionDtos.LoginStatus loginStatus(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        requireStudent(schoolId, studentId);
        StudentLoginService.LoginStatus status = studentLoginService.status(schoolId, studentId);
        return new StudentAdmissionDtos.LoginStatus(status.exists(), status.active());
    }

    /**
     * Creates the login for an existing student who has none (e.g. the old two-step admission
     * failed half-way). Refused when any login already exists, so repeating it never creates a
     * second account; refused for students who left by transfer, withdrawal or cancellation.
     */
    @Transactional
    public StudentAdmissionDtos.LoginStatus createMissingLogin(String studentId, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = requireStudent(schoolId, studentId);
        StudentStatus status = student.getStatus();
        if (status != null && status.isExitStatus() && status != StudentStatus.GRADUATED) {
            throw new IllegalStateException("A login can't be created for a student who is " + status + ".");
        }
        if (userRepository.findByUserId(studentId).isPresent()) {
            throw new IllegalStateException("A login already exists for this student.");
        }
        try {
            studentLoginService.create(student);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            throw new IllegalStateException("A login already exists for this student.");
        }
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "CREATE_STUDENT_LOGIN", "User",
                studentId, null, "Initial password = date of birth; must change at first login", httpRequest.getRemoteAddr());
        return new StudentAdmissionDtos.LoginStatus(true, true);
    }

    /** Read-only enrollment history, oldest first. */
    @Transactional(readOnly = true)
    public List<StudentAdmissionDtos.EnrollmentHistoryItem> enrollmentHistory(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        requireStudent(schoolId, studentId);
        java.util.Map<Long, String> labels = new java.util.HashMap<>();
        return studentEnrollmentRepository
                .findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(schoolId, studentId).stream()
                .sorted(java.util.Comparator.comparing(StudentEnrollment::getEffectiveFrom)
                        .thenComparing(StudentEnrollment::getId))
                .map(e -> new StudentAdmissionDtos.EnrollmentHistoryItem(
                        e.getId(), e.getAcademicSessionId(),
                        labels.computeIfAbsent(e.getAcademicSessionId(), id -> academicSessionRepository
                                .findByIdAndSchoolId(id, schoolId).map(AcademicSession::getLabel).orElse(null)),
                        e.getClassId(), e.getClassNameSnapshot(), e.getSectionId(), e.getSectionNameSnapshot(),
                        e.getStatus(), historyState(e), e.getEffectiveFrom(), e.getEffectiveUntil(), e.getClosureReason()))
                .toList();
    }

    private static String historyState(StudentEnrollment e) {
        return switch (e.getStatus()) {
            case ACTIVE -> "CURRENT";
            case PLANNED -> "UPCOMING";
            case CLOSED -> "CLOSED";
            case CANCELLED -> "CANCELLED";
        };
    }

    /** Parent links ended by the student's last exit, offered for restoration after readmission. */
    @Transactional(readOnly = true)
    public List<StudentAdmissionDtos.RestorableParentLink> restorableParentLinks(String studentId) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = requireStudent(schoolId, studentId);
        if (student.getStatus() != null && student.getStatus().isExitStatus()) return List.of();
        LocalDate[] window = readmissionWindow(schoolId, studentId);
        return window == null ? List.of()
                : parentPortalService.endedLinksSince(schoolId, studentId, window[0], window[1]);
    }

    @Transactional
    public int restoreParentLinks(String studentId, List<Long> relationshipIds, HttpServletRequest httpRequest) {
        Long schoolId = securityUtil.getSchoolId();
        requireStudent(schoolId, studentId);
        LocalDate[] window = readmissionWindow(schoolId, studentId);
        if (window == null) {
            throw new IllegalStateException("This student has no readmission to restore parent access for.");
        }
        int restored = parentPortalService.restoreLinks(schoolId, studentId, window[0], window[1], relationshipIds);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "RESTORE_PARENT_LINKS", "Student",
                studentId, null, "relationships=" + relationshipIds, httpRequest.getRemoteAddr());
        return restored;
    }

    /**
     * [last exit date, readmission date]: the last exit is the latest leaving/cancellation among
     * the enrollment history; the readmission is the first realized enrollment starting after it.
     * Null when the student was never readmitted.
     */
    private LocalDate[] readmissionWindow(Long schoolId, String studentId) {
        List<StudentEnrollment> history = studentEnrollmentRepository
                .findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(schoolId, studentId);
        LocalDate lastExit = null;
        boolean lastExitWasCancellation = false;
        for (StudentEnrollment e : history) {
            StudentEnrollmentClosureReason reason = e.getClosureReason();
            LocalDate exitDate = null;
            boolean cancellation = false;
            if (e.getStatus() == StudentEnrollmentStatus.CLOSED && (reason == StudentEnrollmentClosureReason.GRADUATED
                    || reason == StudentEnrollmentClosureReason.TRANSFERRED || reason == StudentEnrollmentClosureReason.WITHDRAWN)) {
                exitDate = e.getEffectiveUntil();
            } else if (e.getStatus() == StudentEnrollmentStatus.CANCELLED && e.getUpdatedAt() != null) {
                exitDate = e.getUpdatedAt().toLocalDate();   // parent links end on the cancellation day
                cancellation = true;
            }
            if (exitDate != null && (lastExit == null || exitDate.isAfter(lastExit))) {
                lastExit = exitDate;
                lastExitWasCancellation = cancellation;
            }
        }
        if (lastExit == null) return null;
        final LocalDate since = lastExit;
        final boolean inclusive = lastExitWasCancellation;
        return history.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE || e.getStatus() == StudentEnrollmentStatus.CLOSED)
                .map(StudentEnrollment::getEffectiveFrom)
                .filter(from -> from.isAfter(since) || (inclusive && from.isEqual(since)))
                .min(java.util.Comparator.naturalOrder())
                .map(readmitted -> new LocalDate[]{since, readmitted})
                .orElse(null);
    }

    private Student requireStudent(Long schoolId, String studentId) {
        return studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
    }

    private StudentStatus calculateStatus(LocalDate joiningDate, LocalDate leavingDate, StudentStatus currentStatus) {
        // Never overwrite exit statuses — they are set by the explicit exit workflow
        if (currentStatus != null && currentStatus.isExitStatus()) {
            return currentStatus;
        }

        LocalDate today = schoolToday(securityUtil.getSchoolId());

        if (joiningDate != null && joiningDate.isAfter(today)) {
            return StudentStatus.UPCOMING;
        }

        return StudentStatus.ACTIVE;
    }

    /** Older entry point kept for internal callers: only the admin-editable fields are read. */
    @Transactional
    public Student updateStudent(String studentId, Student updatedStudent, Integer effectiveFromMonth, HttpServletRequest request) {
        if (updatedStudent == null) {
            throw new IllegalArgumentException("Student ID and updated student object must be provided.");
        }
        return updateStudent(studentId, new StudentAdmissionDtos.UpdateRequest(
                updatedStudent.getName(), updatedStudent.getEmail(), updatedStudent.getPhoneNumber(),
                updatedStudent.getDob(), updatedStudent.getClassName(), updatedStudent.getSectionId(),
                updatedStudent.getGender(), updatedStudent.getFatherName(), updatedStudent.getMotherName(),
                updatedStudent.getTakesBus(), updatedStudent.getDistance(), updatedStudent.getJoiningDate()),
                effectiveFromMonth, request);
    }

    /**
     * Edits a student. Only admin-editable fields are applied to the stored record, so status,
     * exit details, the stored photo key and every other lifecycle field are never overwritten.
     * Class, section and joining-date changes go through the enrollment lifecycle:
     * <ul>
     *   <li>UPCOMING: the not-yet-started PLANNED enrollment is corrected/rescheduled.</li>
     *   <li>ACTIVE: a class/section change is a dated transition from today (an in-place fix only
     *       when the enrollment started today and nothing was recorded under it yet); a joining
     *       date is corrected only when no attendance or marks exist and there is no other history.</li>
     *   <li>Left / cancelled: membership can only change through readmission.</li>
     * </ul>
     */
    @Transactional
    public Student updateStudent(String studentId, StudentAdmissionDtos.UpdateRequest update,
                                 Integer effectiveFromMonth, HttpServletRequest request) {
        if (studentId == null || studentId.trim().isEmpty() || update == null) {
            log.error("Invalid input for updateStudent: studentId or update is null/empty.");
            throw new IllegalArgumentException("Student ID and updated student object must be provided.");
        }
        if (update.name() == null || update.name().isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        log.info("Attempting to update student with ID: {}", studentId);

        Long schoolId = securityUtil.getSchoolId();
        Student existing = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student with ID " + studentId + " not found"));
        String oldValue = toJson(existing);

        // SchoolClass is authoritative — validated before any side effect runs.
        SchoolClass schoolClass = resolveAndValidateClass(schoolId, update.className());
        Section requestedSection = resolveSection(schoolId, schoolClass, update.sectionId());
        Long requestedSectionId = requestedSection == null ? null : requestedSection.getId();

        LocalDate today = schoolToday(schoolId);
        List<StudentEnrollment> history = studentEnrollmentRepository
                .findBySchoolIdAndStudentIdOrderByAcademicSessionIdAscEffectiveFromAsc(schoolId, studentId);
        Optional<AcademicSession> todaySession = findSessionContaining(schoolId, today);
        Optional<StudentEnrollment> effective = todaySession.flatMap(session ->
                studentEnrollmentService.findEffectiveEnrollment(schoolId, studentId, session.getId(), today));
        boolean covered = effective.isPresent()
                || history.stream().anyMatch(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED);

        Long currentClassId = effective.map(StudentEnrollment::getClassId).orElse(existing.getClassId());
        Long currentSectionId = effective.map(StudentEnrollment::getSectionId).orElse(existing.getSectionId());
        boolean classChanged = !Objects.equals(currentClassId, schoolClass.getId());
        boolean sectionChanged = !Objects.equals(currentSectionId, requestedSectionId);
        boolean joiningChanged = update.joiningDate() != null
                && !Objects.equals(existing.getJoiningDate(), update.joiningDate());

        boolean busDetailsChanged = !Objects.equals(existing.getTakesBus(), update.takesBus())
                || (Boolean.TRUE.equals(update.takesBus()) && !Objects.equals(existing.getDistance(), update.distance()));
        boolean emailChanged = !Objects.equals(existing.getEmail(), trimToNull(update.email()));

        existing.setName(update.name().trim());
        existing.setEmail(trimToNull(update.email()));
        existing.setPhoneNumber(trimToNull(update.phoneNumber()));
        if (update.dob() != null) existing.setDob(update.dob());
        existing.setGender(trimToNull(update.gender()));
        existing.setFatherName(trimToNull(update.fatherName()));
        existing.setMotherName(trimToNull(update.motherName()));
        existing.setTakesBus(update.takesBus());
        existing.setDistance(update.distance());

        if (classChanged || sectionChanged || joiningChanged) {
            StudentStatus status = existing.getStatus();
            if (status != null && status.isExitStatus()) {
                throw new IllegalArgumentException("This student is no longer enrolled (" + status
                        + "). Use Re-admit to place them in a class again.");
            }
            if (!covered) {
                applyLegacyMembershipEdit(existing, schoolClass, requestedSection, classChanged, joiningChanged, update);
            } else if (effective.isPresent()) {
                applyActiveMembershipEdit(schoolId, existing, todaySession.orElseThrow(), today, schoolClass,
                        requestedSectionId, classChanged, sectionChanged, joiningChanged ? update.joiningDate() : null);
            } else if (status == StudentStatus.UPCOMING) {
                applyUpcomingMembershipEdit(schoolId, existing, history, schoolClass, requestedSectionId,
                        classChanged || sectionChanged, joiningChanged ? update.joiningDate() : null);
            } else if (status == StudentStatus.ACTIVE) {
                throw new IllegalStateException("No current enrollment covers today, so class, section and "
                        + "joining date can't be changed here.");
            } else {
                throw new IllegalStateException("Class, section and joining date can't be changed for status " + status + ".");
            }
        }

        if (emailChanged) {
            userDetailsService.findUserByUserId(studentId).ifPresent(user -> {
                user.setEmail(existing.getEmail());
                userDetailsService.save(user);
            });
        }

        Student savedStudent = studentRepository.save(existing);
        auditService.logUpdate(securityUtil.getUsername(), securityUtil.getRole(), "UPDATE_STUDENT", "Student",
                studentId, oldValue, toJson(savedStudent), request.getRemoteAddr());
        log.info("Successfully saved updated student record for ID: {}", studentId);

        if (busDetailsChanged && existing.getTakesBus() != null) {
            log.info("Bus details changed for student {}. Updating bus fees from month: {}", studentId, effectiveFromMonth);
            studentFeesService.updateStudentBusFees(studentId, existing.getTakesBus(), existing.getDistance(), effectiveFromMonth);
        }
        return savedStudent;
    }

    /** Pre-enrollment (legacy) students keep the old projection-only behaviour. */
    private void applyLegacyMembershipEdit(Student existing, SchoolClass schoolClass, Section section,
                                           boolean classChanged, boolean joiningChanged,
                                           StudentAdmissionDtos.UpdateRequest update) {
        existing.setClassId(schoolClass.getId());
        existing.setClassName(schoolClass.getName());
        existing.setSectionId(section == null ? null : section.getId());
        existing.setSectionName(section == null ? null : section.getName());
        if (classChanged) {
            studentFeesService.updateStudentFeesForClassChange(existing.getStudentId(), schoolClass.getName());
        }
        if (joiningChanged) {
            existing.setJoiningDate(update.joiningDate());
            existing.setStatus(calculateStatus(update.joiningDate(), existing.getLeavingDate(), existing.getStatus()));
        }
    }

    /** UPCOMING: correct the class/section and/or move the date of the one PLANNED admission row. */
    private void applyUpcomingMembershipEdit(Long schoolId, Student existing, List<StudentEnrollment> history,
                                             SchoolClass schoolClass, Long sectionId,
                                             boolean membershipChanged, LocalDate newJoiningDate) {
        String studentId = existing.getStudentId();
        if (membershipChanged) {
            StudentEnrollment planned = history.stream()
                    .filter(e -> e.getStatus() == StudentEnrollmentStatus.PLANNED)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("This upcoming student has no planned enrollment to correct."));
            // Corrected while still PLANNED (before any date move could start it).
            StudentEnrollment corrected = studentEnrollmentService.correctPlannedEnrollment(
                    schoolId, studentId, planned.getId(), schoolClass.getId(), sectionId);
            existing.setClassId(corrected.getClassId());
            existing.setClassName(corrected.getClassNameSnapshot());
            existing.setSectionId(corrected.getSectionId());
            existing.setSectionName(corrected.getSectionNameSnapshot());
        }
        if (newJoiningDate != null) {
            StudentEnrollment moved = studentEnrollmentService.reschedulePlannedAdmission(schoolId, studentId, newJoiningDate);
            existing.setJoiningDate(moved.getEffectiveFrom());
            if (moved.getStatus() == StudentEnrollmentStatus.ACTIVE) {
                existing.setStatus(StudentStatus.ACTIVE);
            }
        }
    }

    /** ACTIVE: conservative corrections that never rewrite recorded attendance or marks. */
    private void applyActiveMembershipEdit(Long schoolId, Student existing, AcademicSession session, LocalDate today,
                                           SchoolClass schoolClass, Long sectionId,
                                           boolean classChanged, boolean sectionChanged, LocalDate newJoiningDate) {
        String studentId = existing.getStudentId();
        if (newJoiningDate != null) {
            if (hasAttendanceSince(schoolId, studentId, null) || hasMarksSince(schoolId, studentId, null)) {
                throw new IllegalArgumentException("The joining date can't be changed because attendance or marks "
                        + "have already been recorded for this student.");
            }
            studentEnrollmentService.correctActiveStartDate(schoolId, studentId, newJoiningDate);
            existing.setJoiningDate(newJoiningDate);
        }
        if (classChanged || sectionChanged) {
            StudentEnrollment current = studentEnrollmentService
                    .findEffectiveEnrollment(schoolId, studentId, session.getId(), today)
                    .orElseThrow(() -> new IllegalStateException("No current enrollment covers today."));
            if (!current.getEffectiveFrom().isBefore(today)
                    && (hasAttendanceSince(schoolId, studentId, current.getEffectiveFrom())
                        || hasMarksSince(schoolId, studentId, current.getEffectiveFrom()))) {
                throw new IllegalArgumentException("Attendance or marks were already recorded today under Class "
                        + current.getClassNameSnapshot() + (current.getSectionNameSnapshot() == null ? "" : " " + current.getSectionNameSnapshot())
                        + ". Those records stay with that class, so the class/section can be changed from tomorrow.");
            }
            StudentEnrollmentService.EnrollmentTransition transition = classChanged
                    ? studentEnrollmentService.transitionClass(schoolId, studentId, session.getId(),
                            schoolClass.getId(), sectionId, today)
                    : studentEnrollmentService.transitionSection(schoolId, studentId, session.getId(), sectionId, today);
            StudentEnrollment replacement = transition.replacementSegment();
            existing.setClassId(replacement.getClassId());
            existing.setClassName(replacement.getClassNameSnapshot());
            existing.setSectionId(replacement.getSectionId());
            existing.setSectionName(replacement.getSectionNameSnapshot());
            log.info("Enrollment-backed membership change for student {} effective {} ({}).", studentId, today,
                    transition.closedSegment() == null ? "corrected in place" : "previous period closed");
        }
    }

    private boolean hasAttendanceSince(Long schoolId, String studentId, LocalDate from) {
        return !studentAttendanceRepository.findStudentRows(schoolId, studentId,
                from == null ? LocalDate.of(1900, 1, 1) : from, LocalDate.of(9999, 12, 31)).isEmpty();
    }

    private boolean hasMarksSince(Long schoolId, String studentId, LocalDate from) {
        return studentMarkRepository.findByStudentIdAndSchoolId(studentId, schoolId).stream()
                .anyMatch(mark -> from == null || mark.getCreatedAt() == null
                        || !mark.getCreatedAt().toLocalDate().isBefore(from));
    }

    private Section resolveSection(Long schoolId, SchoolClass schoolClass, Long sectionId) {
        if (sectionId == null) return null;
        Section section = sectionRepository.findByIdAndSchoolId(sectionId, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found for this school."));
        if (!section.getClassId().equals(schoolClass.getId())) {
            throw new IllegalArgumentException(
                    "Section '" + section.getName() + "' does not belong to class '" + schoolClass.getName() + "'.");
        }
        return section;
    }

    private String toJson(Student student) {
        try {
            return objectMapper.writeValueAsString(student);
        } catch (JsonProcessingException e) {
            return student.getStudentId();
        }
    }

    /**
     * Permanently deletes a student and all their associated records (attendance,
     * leaves, and the login User account) within the caller's school.
     *
     * The deletion is wrapped in a single transaction so that all tables are cleaned
     * up atomically — a partial failure rolls back the entire operation.
     *
     * NOTE: Payment records are retained for accounting/audit purposes and are NOT
     * deleted. A student with ANY retained financial history (a StudentFees row —
     * paid or not; a generated-but-unpaid liability is still accounting history) OR
     * ANY retained enrollment history (a StudentEnrollment row — current or closed;
     * ordinary admission always creates one) is refused outright, before any destructive
     * cleanup — see the guards below. Use the exit workflow ({@code exitStudent},
     * GRADUATED/TRANSFERRED/WITHDRAWN) to remove such an established student instead.
     * Hard deletion remains available only for a student with neither kind of history —
     * a provisional/mistakenly-created record.
     */
    @Transactional
    public void deleteStudent(String studentId, HttpServletRequest request) {
        if (studentId == null || studentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Student ID must be provided.");
        }

        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        // Financial history must never be destructively removed just to permit student
        // deletion. student_fees_line_item and payment_student_fees_allocation both FK
        // NOT NULL to student_fees(id) with no cascade — deleting a StudentFees row that
        // either references would already fail with a raw DataIntegrityViolationException,
        // a confusing 500 that leaks nothing useful to the caller. Checking up front instead
        // means a clean, expected 409 for a genuinely business-meaningful condition — this
        // student has financial history — rather than treating it as a database error.
        if (studentFeesRepository.existsByStudentIdAndSchoolId(studentId, schoolId)) {
            log.warn("Refusing to delete student {} for school {}: retained financial history exists.",
                    studentId, schoolId);
            throw new IllegalStateException(
                    "Student cannot be deleted because financial records exist. Deactivate the student instead.");
        }

        // Enrollment history is likewise never destructively removed just to permit student
        // deletion — fk_student_enrollment_student (school_id, student_id) is ON DELETE
        // RESTRICT, and ordinary admission always creates a StudentEnrollment row, so this
        // is the normal case for any established student, not an edge case. Deactivate via
        // the exit workflow instead of hard-deleting academic/session history.
        if (studentEnrollmentRepository.existsByStudentIdAndSchoolId(studentId, schoolId)) {
            log.warn("Refusing to delete student {} for school {}: retained enrollment history exists.",
                    studentId, schoolId);
            throw new IllegalStateException(
                    "Student cannot be deleted because enrollment/history records exist. Use the student exit workflow instead.");
        }

        log.warn("Deleting student {} and all associated records for school {}", studentId, schoolId);

        // 1. Delete related records (cascading cleanup) — StudentFees is deliberately never
        // touched here: the guard above already proved none exist for this student.
        studentAttendanceRepository.deleteByStudentIdAndSchoolId(studentId, schoolId);
        leaveRepository.deleteByStudentIdAndSchoolId(studentId, schoolId);
        // Note: Payment records are intentionally kept for financial audit trail

        // 2. Delete the login User account
        userRepository.findByUserId(studentId).ifPresent(userRepository::delete);

        // 3. Delete the Student record itself
        studentRepository.delete(student);

        try {
            auditService.log(
                    securityUtil.getUsername(),
                    securityUtil.getRole(),
                    "DELETE_STUDENT",
                    "Student",
                    studentId,
                    objectMapper.writeValueAsString(student),
                    null,
                    request.getRemoteAddr()
            );
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize deleted student for audit log: {}", studentId);
        }

        log.info("Student {} and associated records deleted successfully.", studentId);
    }

}
