package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamResultStatus;
import com.indraacademy.ias_management.repository.AssessmentGroupExamMappingRepository;
import com.indraacademy.ias_management.entity.ExamSubjectEntry;
import com.indraacademy.ias_management.repository.ClassSubjectRepository;
import com.indraacademy.ias_management.repository.ExamConfigRepository;
import com.indraacademy.ias_management.repository.ExamSubjectEntryRepository;
import com.indraacademy.ias_management.repository.StudentMarkRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExamConfigService {

    private static final Logger log = LoggerFactory.getLogger(ExamConfigService.class);

    /** Classes whose subjects must match ClassSubject records before adding to an exam. */
    private static final Set<String> LOWER_CLASSES = Set.of(
            "1","2","3","4","5","6","7","8","9","10",
            "Play group","Nursery","KG","LKG","UKG"
    );

    @Autowired private ExamConfigRepository examConfigRepository;
    @Autowired private ExamSubjectEntryRepository examSubjectEntryRepository;
    @Autowired private StudentMarkRepository studentMarkRepository;
    @Autowired private ClassSubjectRepository classSubjectRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private AssessmentGroupExamMappingRepository assessmentGroupExamMappingRepository;
    @Autowired(required = false) private com.indraacademy.ias_management.repository.ReportCardSetupExamRepository reportCardSetupExamRepository;
    @Autowired(required = false) private com.indraacademy.ias_management.repository.ReportCardPublicationV2Repository reportCardPublicationV2Repository;
    @Autowired private AuditService auditService;
    @Autowired private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;
    @Autowired private com.indraacademy.ias_management.repository.SchoolClassRepository schoolClassRepository;

    // ─── ExamConfig ───────────────────────────────────────────────────────────

    @Cacheable(value = "exam-config", key = "@securityUtil.getSchoolId() + ':' + #session + '-' + #className")
    @Transactional(readOnly = true)
    public List<ExamConfig> getExams(String session, String className) {
        Long schoolId = securityUtil.getSchoolId();
        if (session != null && !session.isBlank() && className != null && !className.isBlank()) {
            return examConfigRepository.findBySessionAndClassNameAndSchoolId(session, className, schoolId);
        } else if (session != null && !session.isBlank()) {
            return examConfigRepository.findBySessionAndSchoolId(session, schoolId);
        } else if (className != null && !className.isBlank()) {
            return examConfigRepository.findByClassNameAndSchoolId(className, schoolId);
        }
        return examConfigRepository.findBySchoolId(schoolId);
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamConfig addExam(String session, String className, String examName) {
        if (session == null || session.isBlank()
                || className == null || className.isBlank()
                || examName == null || examName.isBlank()) {
            throw new IllegalArgumentException("session, className, and examName are required.");
        }
        Long schoolId = securityUtil.getSchoolId();
        session = session.trim();
        className = className.trim();
        examName = examName.trim();
        if (academicSessionRepository.findBySchoolIdAndLabel(schoolId, session).isEmpty()) {
            throw new IllegalArgumentException("Academic session " + session + " does not exist in your school.");
        }
        com.indraacademy.ias_management.entity.SchoolClass schoolClass = schoolClassRepository.findBySchoolIdAndName(schoolId, className)
                .orElse(null);
        if (schoolClass == null) throw new IllegalArgumentException("Class " + className + " does not exist in your school.");
        if (examConfigRepository.existsBySessionAndClassNameAndExamNameAndSchoolId(session, className, examName, schoolId)) {
            throw new IllegalArgumentException(
                    "Exam '" + examName + "' already exists for class " + className
                            + " in session " + session + ".");
        }
        ExamConfig config = new ExamConfig();
        config.setSession(session);
        config.setClassName(className);
        config.setExamName(examName);
        config.setSchoolId(schoolId);
        config.setClassId(schoolClass.getId());
        ExamConfig saved = examConfigRepository.save(config);
        log.info("Created ExamConfig id={} ({} / {} / {})", saved.getId(), session, className, examName);
        return saved;
    }

    /**
     * Deletes an exam that holds no results. An exam with marks, a published result, or a place in
     * a report-card assessment group is refused — marks are never wiped as a side effect of one
     * delete confirmation (the database also RESTRICTs it, V82).
     */
    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public void deleteExam(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = ownedExam(id, schoolId);
        if (exam.isPublished()) {
            throw new IllegalStateException("Unpublish the results of '" + exam.getExamName() + "' before deleting the exam.");
        }
        List<ExamSubjectEntry> entries = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(id, schoolId);
        long marks = entries.isEmpty() ? 0 : studentMarkRepository.countByExamSubjectEntryIdInAndSchoolId(
                entries.stream().map(ExamSubjectEntry::getId).collect(Collectors.toList()), schoolId);
        if (marks > 0) {
            throw new IllegalStateException("'" + exam.getExamName() + "' already has " + marks
                    + " mark(s) entered, so it cannot be deleted. Marks are never deleted automatically.");
        }
        if (assessmentGroupExamMappingRepository.existsByExamConfigIdAndSchoolId(id, schoolId)) {
            throw new IllegalStateException("'" + exam.getExamName()
                    + "' is used by a report-card assessment group. Remove it from the group first.");
        }
        if (reportCardSetupExamRepository != null && reportCardSetupExamRepository.existsByExamConfigIdAndSchoolId(id, schoolId)) {
            throw new IllegalStateException("'" + exam.getExamName()
                    + "' is used by a report card (Report Cards → Setup). Remove it from the report card first.");
        }
        examSubjectEntryRepository.deleteByExamConfigIdAndSchoolId(id, schoolId);
        examConfigRepository.delete(exam);
        log.info("Deleted ExamConfig id={} (no marks)", id);
    }

    // ─── Result publishing ────────────────────────────────────────────────────

    /** ADMIN: makes the exam's results visible to students and parents and locks its marks. */
    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public ExamConfig publishResults(Long id, String ipAddress) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = ownedExam(id, schoolId);
        if (exam.isPublished()) return exam;
        if (examSubjectEntryRepository.findByExamConfigIdAndSchoolId(id, schoolId).isEmpty()) {
            throw new IllegalStateException("Add subjects to '" + exam.getExamName() + "' before publishing its results.");
        }
        exam.setResultStatus(ExamResultStatus.PUBLISHED);
        exam.setPublishedAt(java.time.LocalDateTime.now());
        exam.setPublishedBy(securityUtil.getUsername());
        ExamConfig saved = examConfigRepository.save(exam);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "PUBLISH_EXAM_RESULTS", "ExamConfig",
                String.valueOf(id), "DRAFT", "PUBLISHED", ipAddress);
        return saved;
    }

    /** ADMIN: hides the exam's results from students and parents again and unlocks marks. */
    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public ExamConfig unpublishResults(Long id, String ipAddress) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = ownedExam(id, schoolId);
        if (!exam.isPublished()) return exam;
        // A frozen (V2) report card that includes this exam is still official: unpublish it first.
        if (reportCardPublicationV2Repository != null && reportCardPublicationV2Repository.existsActiveForExam(id, schoolId)) {
            throw new IllegalStateException("This exam is part of a published report card. Unpublish the report card first.");
        }
        exam.setResultStatus(ExamResultStatus.DRAFT);
        exam.setPublishedAt(null);
        exam.setPublishedBy(null);
        ExamConfig saved = examConfigRepository.save(exam);
        auditService.log(securityUtil.getUsername(), securityUtil.getRole(), "UNPUBLISH_EXAM_RESULTS", "ExamConfig",
                String.valueOf(id), "PUBLISHED", "DRAFT", ipAddress);
        return saved;
    }

    private ExamConfig ownedExam(Long id, Long schoolId) {
        return examConfigRepository.findById(id)
                .filter(e -> schoolId != null && schoolId.equals(e.getSchoolId()))
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + id));
    }

    private static void requireDraft(ExamConfig exam) {
        if (exam.isPublished()) {
            throw new IllegalStateException("Results of '" + exam.getExamName()
                    + "' are published. Unpublish them before changing the exam's subjects.");
        }
    }

    private void requireNoMarks(ExamSubjectEntry entry, Long schoolId, String action) {
        long marks = studentMarkRepository.countByExamSubjectEntryIdInAndSchoolId(List.of(entry.getId()), schoolId);
        if (marks > 0) {
            throw new IllegalStateException("'" + entry.getSubjectName() + "' already has " + marks
                    + " mark(s) entered, so it cannot be " + action + ". Marks are never deleted automatically.");
        }
    }

    private void requireMaxNotBelowMarks(ExamSubjectEntry entry, int newMax, Long schoolId) {
        Double highest = studentMarkRepository.findHighestMark(entry.getId(), schoolId);
        if (highest != null && highest > newMax) {
            throw new IllegalStateException("Max marks for '" + entry.getSubjectName() + "' cannot be below the highest mark already entered ("
                    + (highest % 1 == 0 ? String.valueOf(highest.intValue()) : String.valueOf(highest)) + ").");
        }
    }

    // ─── ExamSubjectEntry ─────────────────────────────────────────────────────

    @Cacheable(value = "exam-config", key = "@securityUtil.getSchoolId() + ':subjects-' + #examId")
    @Transactional(readOnly = true)
    public List<ExamSubjectEntry> getExamSubjects(Long examId) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examId));
        if (!schoolId.equals(exam.getSchoolId())) {
            throw new SecurityException("Access denied: exam does not belong to your school.");
        }
        return examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examId, schoolId);
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamSubjectEntry addExamSubject(Long examId, String subjectName,
                                           Integer maxMarks, LocalDate examDate) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examId));
        if (!schoolId.equals(exam.getSchoolId())) {
            throw new SecurityException("Access denied: exam does not belong to your school.");
        }

        requireDraft(exam);
        if (subjectName == null || subjectName.isBlank()) {
            throw new IllegalArgumentException("subjectName is required.");
        }
        if (maxMarks == null || maxMarks <= 0) {
            throw new IllegalArgumentException("maxMarks must be a positive integer.");
        }

        // For classes 1–10, validate the subject exists in ClassSubject configuration.
        if (LOWER_CLASSES.contains(exam.getClassName())
                && !classSubjectRepository.existsByClassNameAndSubjectNameAndSchoolId(exam.getClassName(), subjectName, securityUtil.getSchoolId())) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' is not configured for class " + exam.getClassName()
                            + ". Add it via POST /api/subjects/class first.");
        }

        if (examSubjectEntryRepository.existsByExamConfigIdAndSubjectNameAndSchoolId(examId, subjectName, schoolId)) {
            throw new IllegalArgumentException(
                    "Subject '" + subjectName + "' is already part of this exam.");
        }

        ExamSubjectEntry entry = new ExamSubjectEntry();
        entry.setSchoolId(schoolId);
        entry.setExamConfigId(examId);
        entry.setSubjectName(subjectName);
        entry.setMaxMarks(maxMarks);
        entry.setExamDate(examDate);
        ExamSubjectEntry saved = examSubjectEntryRepository.save(entry);
        log.info("Added subject '{}' to ExamConfig id={}", subjectName, examId);
        return saved;
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    public ExamSubjectEntry updateExamSubject(Long entryId, Integer maxMarks, LocalDate examDate) {
        Long schoolId = securityUtil.getSchoolId();
        ExamSubjectEntry entry = examSubjectEntryRepository.findByIdAndSchoolId(entryId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("ExamSubjectEntry not found: " + entryId));

        requireDraft(ownedExam(entry.getExamConfigId(), schoolId));
        if (maxMarks != null) {
            if (maxMarks <= 0) throw new IllegalArgumentException("maxMarks must be positive.");
            requireMaxNotBelowMarks(entry, maxMarks, schoolId);
            entry.setMaxMarks(maxMarks);
        }
        if (examDate != null) {
            entry.setExamDate(examDate);
        }
        ExamSubjectEntry saved = examSubjectEntryRepository.save(entry);
        log.info("Updated ExamSubjectEntry id={}", entryId);
        return saved;
    }

    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public void deleteExamSubject(Long entryId) {
        Long schoolId = securityUtil.getSchoolId();
        ExamSubjectEntry entry = examSubjectEntryRepository.findByIdAndSchoolId(entryId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("ExamSubjectEntry not found: " + entryId));
        requireDraft(ownedExam(entry.getExamConfigId(), schoolId));
        requireNoMarks(entry, schoolId, "removed");
        examSubjectEntryRepository.delete(entry);
        log.info("Deleted ExamSubjectEntry id={}", entryId);
    }

    // ─── Bulk sync subjects ─────────────────────────────────────────────────

    /**
     * Replaces the entire subject list for an exam in one call.
     * - Subjects already in the exam are updated (maxMarks, examDate).
     * - New subjects are added (no ClassSubject validation — allows extra subjects like GK).
     * - Subjects previously in the exam but not in the incoming list are removed.
     */
    @CacheEvict(value = "exam-config", allEntries = true)
    @Transactional
    public List<ExamSubjectEntry> bulkSyncExamSubjects(Long examId, List<BulkSubjectRequest> incoming) {
        Long schoolId = securityUtil.getSchoolId();
        ExamConfig exam = examConfigRepository.findById(examId)
                .orElseThrow(() -> new NoSuchElementException("ExamConfig not found: " + examId));
        if (!schoolId.equals(exam.getSchoolId())) {
            throw new SecurityException("Access denied: exam does not belong to your school.");
        }

        requireDraft(exam);
        List<ExamSubjectEntry> existing = examSubjectEntryRepository.findByExamConfigIdAndSchoolId(examId, schoolId);
        Map<String, ExamSubjectEntry> existingByName = new HashMap<>();
        for (ExamSubjectEntry e : existing) {
            existingByName.put(e.getSubjectName(), e);
        }

        Set<String> incomingNames = new HashSet<>();
        List<ExamSubjectEntry> result = new ArrayList<>();

        for (BulkSubjectRequest req : incoming) {
            if (req.subjectName == null || req.subjectName.isBlank()) continue;
            if (req.maxMarks == null || req.maxMarks <= 0) {
                throw new IllegalArgumentException("maxMarks must be positive for subject '" + req.subjectName + "'.");
            }
            String name = req.subjectName.trim();
            incomingNames.add(name);

            ExamSubjectEntry entry = existingByName.get(name);
            if (entry != null) {
                // Update existing
                requireMaxNotBelowMarks(entry, req.maxMarks, schoolId);
                entry.setMaxMarks(req.maxMarks);
                entry.setExamDate(req.examDate);
                result.add(examSubjectEntryRepository.save(entry));
            } else {
                // Add new
                ExamSubjectEntry fresh = new ExamSubjectEntry();
                fresh.setSchoolId(schoolId);
                fresh.setExamConfigId(examId);
                fresh.setSubjectName(name);
                fresh.setMaxMarks(req.maxMarks);
                fresh.setExamDate(req.examDate);
                result.add(examSubjectEntryRepository.save(fresh));
            }
        }

        // Remove subjects that were in the exam but not in the incoming list
        for (ExamSubjectEntry e : existing) {
            if (!incomingNames.contains(e.getSubjectName())) {
                requireNoMarks(e, schoolId, "removed");
                examSubjectEntryRepository.delete(e);
            }
        }

        log.info("Bulk-synced {} subjects for ExamConfig id={}", result.size(), examId);
        return result;
    }

    /** Simple holder for bulk-sync request items. */
    public static class BulkSubjectRequest {
        public String subjectName;
        public Integer maxMarks;
        public LocalDate examDate;
    }

    /** Resolves an ExamSubjectEntry to its parent ExamConfig's className. */
    public Optional<String> resolveClassName(Long examSubjectEntryId) {
        Long schoolId = securityUtil.getSchoolId();
        return examSubjectEntryRepository.findByIdAndSchoolId(examSubjectEntryId, schoolId)
                .flatMap(e -> examConfigRepository.findById(e.getExamConfigId()))
                .filter(exam -> schoolId.equals(exam.getSchoolId()))
                .map(ExamConfig::getClassName);
    }

    /** Resolves an ExamConfig's className. */
    public Optional<String> resolveClassNameForExam(Long examConfigId) {
        Long schoolId = securityUtil.getSchoolId();
        return examConfigRepository.findById(examConfigId)
                .filter(exam -> schoolId.equals(exam.getSchoolId()))
                .map(ExamConfig::getClassName);
    }
}
