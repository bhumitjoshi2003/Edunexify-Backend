package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardPublicationDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ReportCardPublication;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.ReportCardPublicationRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import com.indraacademy.ias_management.notification.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class ReportCardPublicationService {

    private static final Logger log = LoggerFactory.getLogger(ReportCardPublicationService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired private ReportCardPublicationRepository                             pubRepo;
    @Autowired private ReportCardTemplateService                                   templateService;
    @Autowired private StudentRepository                                           studentRepository;
    @Autowired private ReportCardEmailBlastService                                 blastService;
    @Autowired private SecurityUtil                                                securityUtil;
    @Autowired private BusinessNotificationService                                businessNotifications;
    @Autowired private com.indraacademy.ias_management.repository.SchoolRepository schoolRepository;
    @Autowired private SchoolClassRepository                                       schoolClassRepository;
    @Autowired private AcademicSessionRepository                                   academicSessionRepository;
    @Autowired private StudentEnrollmentRepository                                 studentEnrollmentRepository;
    @Autowired private com.indraacademy.ias_management.repository.ReportCardTemplateRepository templateRepository;
    @Autowired private com.indraacademy.ias_management.repository.AssessmentGroupExamMappingRepository examMappingRepository;
    @Autowired private com.indraacademy.ias_management.repository.AssessmentGroupCompositionRepository compositionRepository;
    @Autowired private com.indraacademy.ias_management.repository.ExamConfigRepository examConfigRepository;

    // ── Status ─────────────────────────────────────────────────────────────

    public ReportCardPublicationDTO getStatus(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return pubRepo
            .findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
            .map(p -> toDTO(p, templateId))
            .orElse(ReportCardPublicationDTO.notPublished(templateId, session, className));
    }

    /** Used by the controller to gate STUDENT access — lightweight exists-check. */
    public boolean isPublished(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return pubRepo.existsBySchoolIdAndTemplateIdAndSessionAndClassName(
                schoolId, templateId, session, className);
    }

    // ── Publish ────────────────────────────────────────────────────────────

    /**
     * Names of the exams feeding this template (through its assessment-group tree) whose results
     * are still DRAFT. A report card must never expose draft marks: it cannot be published while
     * any are listed, and students/parents are refused if one is unpublished later.
     */
    @Transactional(readOnly = true)
    public List<String> draftExamNames(Long templateId) {
        return draftExamNames(templateId, securityUtil.getSchoolId());
    }

    /** School-explicit form for callers without a school context (public QR verification). */
    @Transactional(readOnly = true)
    public List<String> draftExamNames(Long templateId, Long schoolId) {
        if (templateId == null || schoolId == null) return List.of();
        var template = templateRepository.findByIdAndSchoolId(templateId, schoolId).orElse(null);
        if (template == null) return List.of();
        java.util.Set<Long> examIds = new java.util.LinkedHashSet<>();
        collectExamIds(template.getAssessmentGroupId(), schoolId, examIds, 0);
        List<String> drafts = new ArrayList<>();
        for (var exam : examConfigRepository.findAllById(examIds)) {
            if (schoolId.equals(exam.getSchoolId()) && !exam.isPublished()) drafts.add(exam.getExamName());
        }
        return drafts;
    }

    private void collectExamIds(Long groupId, Long schoolId, java.util.Set<Long> examIds, int depth) {
        if (groupId == null || depth > 5) return;
        examMappingRepository.findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(groupId, schoolId)
                .forEach(m -> examIds.add(m.getExamConfigId()));
        compositionRepository.findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(groupId, schoolId)
                .forEach(c -> collectExamIds(c.getChildGroupId(), schoolId, examIds, depth + 1));
    }

    @Transactional
    public ReportCardPublicationDTO publish(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        String username = securityUtil.getUsername();
        List<String> drafts = draftExamNames(templateId);
        if (!drafts.isEmpty()) {
            throw new IllegalStateException("Publish the results of " + String.join(", ", drafts)
                    + " before publishing this report card.");
        }

        ReportCardPublication pub = pubRepo
            .findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
            .orElseGet(() -> {
                ReportCardPublication p = new ReportCardPublication();
                p.setSchoolId(schoolId);
                p.setTemplateId(templateId);
                p.setSession(session);
                p.setClassName(className);
                return p;
            });

        pub.setPublishedAt(LocalDateTime.now());
        pub.setPublishedBy(username);
        // Auto-assign a verification token on first publish
        if (pub.getVerificationToken() == null) {
            pub.setVerificationToken(java.util.UUID.randomUUID().toString());
        }
        pub = pubRepo.save(pub);

        List<Student> recipients = historicalRecipientRoster(schoolId, className, session);
        String publicationKey = pub.getPublishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        for (Student student : recipients) {
            businessNotifications.studentAndParents(schoolId, student.getStudentId(),
                    NotificationAudienceType.STUDENT_WITH_RESULT_PARENTS,
                    NotificationEventCode.REPORT_CARD_READY, NotificationCategory.ACADEMICS_RESULTS,
                    "Report Card Available", "Your report card for " + session + " is now available.",
                    "ReportCardPublication", String.valueOf(pub.getId()),
                    reportCardRoute(student.getStudentId(), templateId, session, classId), username,
                    "report-card:" + pub.getId() + ":" + publicationKey + ":" + student.getStudentId(),
                    Set.of(ExternalDeliveryChannel.PUSH));
        }

        log.info("Report card published: template={} session={} class={} by={}",
                 templateId, session, className, username);
        return toDTO(pub, templateId);
    }

    // ── Unpublish ──────────────────────────────────────────────────────────

    @Transactional
    public void unpublish(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        pubRepo.deleteBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className);
        log.info("Report card unpublished: template={} session={} class={}", templateId, session, className);
    }

    // ── Email blast ────────────────────────────────────────────────────────

    /**
     * Validates the publication exists, counts eligible students, fires the
     * async blast, and returns the initiated count immediately.
     */
    public int startEmailBlast(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();

        if (!pubRepo.existsBySchoolIdAndTemplateIdAndSessionAndClassName(
                schoolId, templateId, session, className)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Report card must be published before sending emails.");
        }

        List<Student> students = historicalRecipientRoster(schoolId, className, session);

        int withEmail = (int) students.stream()
            .filter(s -> s.getEmail() != null && !s.getEmail().isBlank())
            .count();

        String schoolName = schoolRepository.findById(schoolId).map(s -> s.getName()).orElse("");

        blastService.execute(templateId, session, className, schoolId, students, schoolName);
        return withEmail;
    }

    /**
     * The notification's link to this student's published card. It names only the recipient's
     * own student (the notification goes to that student and their parents); opening it still
     * passes the normal report-card access check, so it can never show another student's card.
     */
    static String reportCardRoute(String studentId, Long templateId, String session, Long classId) {
        StringBuilder route = new StringBuilder("/dashboard/report-card?studentId=")
                .append(java.net.URLEncoder.encode(studentId, java.nio.charset.StandardCharsets.UTF_8))
                .append("&session=").append(java.net.URLEncoder.encode(session, java.nio.charset.StandardCharsets.UTF_8))
                .append("&templateId=").append(templateId);
        if (classId != null) route.append("&classId=").append(classId);
        return route.toString();
    }

    // ── QR Verification ───────────────────────────────────────────────────

    /**
     * Returns the verification token for an existing publication,
     * so the PDF generator can embed it as a QR code.
     */
    public java.util.Optional<String> getVerificationToken(Long templateId, String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        return pubRepo
            .findBySchoolIdAndTemplateIdAndSessionAndClassName(schoolId, templateId, session, className)
            .map(ReportCardPublication::getVerificationToken);
    }

    /**
     * Public (unauthenticated) lookup — called by GET /api/public/verify-rc?token=...
     * Returns school name, class, session, published date. Never returns marks or personal data.
     */
    public VerifyRcDTO verifyByToken(String token) {
        if (token == null || token.isBlank()) {
            return VerifyRcDTO.invalid("Invalid verification link.");
        }
        return pubRepo.findByVerificationToken(token)
            // A card whose results were unpublished after it was issued is no longer valid: answer
            // exactly as for an unknown token, so nothing about the card or its exams is revealed.
            .filter(pub -> draftExamNames(pub.getTemplateId(), pub.getSchoolId()).isEmpty())
            .map(pub -> {
                String schoolName = schoolRepository.findById(pub.getSchoolId())
                    .map(s -> s.getName())
                    .orElse("School");
                String publishedAt = pub.getPublishedAt() != null
                    ? pub.getPublishedAt().format(ISO) : null;
                return VerifyRcDTO.valid(schoolName, pub.getClassName(),
                    pub.getSession(), publishedAt, pub.getPublishedBy());
            })
            .orElse(VerifyRcDTO.invalid("This report card could not be verified. The QR code may be invalid or the report card was unpublished."));
    }

    /**
     * Enrollment-authoritative historical recipient roster (E6E) for publish-time notifications
     * and email blasts: the live ACTIVE roster (unchanged default for schools with no enrollment
     * data) unioned with every student realized-enrolled in this class during the session — so
     * publishing a report card for a class after some of its students have already been
     * promoted/transferred still notifies them. Reuses the same StudentEnrollmentRepository
     * queries E6C/E6D already added; no new temporal-membership logic.
     */
    private List<Student> historicalRecipientRoster(Long schoolId, String className, String session) {
        java.util.Map<String, Student> roster = new java.util.LinkedHashMap<>();
        studentRepository.findByClassNameAndStatusAndSchoolId(className, StudentStatus.ACTIVE, schoolId)
                .forEach(s -> roster.put(s.getStudentId(), s));

        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        if (classId != null) {
            academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).ifPresent(as -> {
                for (StudentEnrollment row : studentEnrollmentRepository.findRealizedByAcademicSessionAndClassOverlappingRange(
                        schoolId, as.getId(), classId, as.getStartDate(), as.getEndDate())) {
                    roster.computeIfAbsent(row.getStudentId(),
                            sid -> studentRepository.findByStudentIdAndSchoolId(sid, schoolId).orElse(null));
                }
            });
        }
        roster.values().removeIf(java.util.Objects::isNull);
        return new ArrayList<>(roster.values());
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private ReportCardPublicationDTO toDTO(ReportCardPublication pub, Long templateId) {
        String tName = null;
        try { tName = templateService.getTemplate(templateId).getName(); } catch (Exception ignored) {}
        return new ReportCardPublicationDTO(
            true,
            pub.getId(),
            pub.getTemplateId(),
            tName,
            pub.getSession(),
            pub.getClassName(),
            pub.getPublishedAt() != null ? pub.getPublishedAt().format(ISO) : null,
            pub.getPublishedBy(),
            pub.getEmailSentAt() != null ? pub.getEmailSentAt().format(ISO) : null,
            pub.getEmailCount()
        );
    }
}
