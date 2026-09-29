package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO.ResultContext;
import com.indraacademy.ias_management.entity.ExamResultStatus;
import com.indraacademy.ias_management.entity.ReportCardDocument;
import com.indraacademy.ias_management.entity.ReportCardPublicationStatus;
import com.indraacademy.ias_management.entity.ReportCardSetup;
import com.indraacademy.ias_management.repository.ReportCardDocumentRepository;
import com.indraacademy.ias_management.repository.ReportCardSetupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Read-only result context for the Student Promotion preview. It never calculates a result of its
 * own and never decides anything: for each source class it takes the class's final Report Card V2
 * setup in the source session (the one ordered last) and uses
 * <ol>
 *   <li>the student's published (ACTIVE) report card document — its frozen snapshot; otherwise</li>
 *   <li>the canonical Report Card V2 calculation ({@link ReportCardV2Builder}), but only when every
 *       exam of that setup has published results; otherwise</li>
 *   <li>nothing ("results not published" / "no report card setup").</li>
 * </ol>
 * Deliberately not transactional: it runs after the preview's own read-only transaction, and each
 * class is computed in its own short transaction, so a problem with one class's results can never
 * fail the promotion preview.
 */
@Service
public class PromotionResultContextService {

    private static final Logger log = LoggerFactory.getLogger(PromotionResultContextService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportCardSetupRepository setupRepo;
    private final ReportCardDocumentRepository documentRepo;
    private final ReportCardV2Builder builder;

    public PromotionResultContextService(ReportCardSetupRepository setupRepo, ReportCardDocumentRepository documentRepo,
                                         ReportCardV2Builder builder) {
        this.setupRepo = setupRepo;
        this.documentRepo = documentRepo;
        this.builder = builder;
    }

    /**
     * The preview with each candidate's result context added, plus a warning for a FAIL or
     * INCOMPLETE result (promotion stays allowed — the admin decides).
     */
    public PromotionPreviewDTO enrich(Long schoolId, PromotionPreviewDTO preview) {
        if (preview == null || !preview.valid() || preview.candidates().isEmpty()) return preview;
        Map<Long, Set<String>> byClass = new LinkedHashMap<>();
        for (PromotionPreviewDTO.Candidate c : preview.candidates()) {
            byClass.computeIfAbsent(c.sourceClassId(), k -> new LinkedHashSet<>()).add(c.studentId());
        }
        Map<String, ResultContext> contexts = forClasses(schoolId, preview.sourceSessionId(), byClass);
        List<PromotionPreviewDTO.Candidate> candidates = new ArrayList<>();
        for (PromotionPreviewDTO.Candidate c : preview.candidates()) {
            ResultContext rc = contexts.get(c.studentId());
            List<PromotionPreviewDTO.Issue> warnings = new ArrayList<>(c.warnings());
            if (rc != null && "FAIL".equals(rc.result())) {
                warnings.add(new PromotionPreviewDTO.Issue("RESULT_FAIL",
                        "Result is Fail — promotion is still allowed, but please confirm the decision"));
            } else if (rc != null && "INCOMPLETE".equals(rc.result())) {
                warnings.add(new PromotionPreviewDTO.Issue("RESULT_INCOMPLETE",
                        "Result is incomplete (marks missing) — promotion is still allowed, but please confirm the decision"));
            }
            candidates.add(new PromotionPreviewDTO.Candidate(c.studentId(), c.studentName(), c.sourceEnrollmentId(),
                    c.sourceSessionId(), c.sourceClassId(), c.sourceClassName(), c.sourceSectionId(), c.sourceSectionName(),
                    c.availableDecisions(), c.recommendedDecision(), c.promoteTargetClassId(), c.promoteTargetClassName(),
                    c.detainTargetClassId(), c.detainTargetClassName(), c.promoteTargetSectionRequired(),
                    c.proposedPromoteTargetSectionId(), c.proposedDetainTargetSectionId(), c.proposedTargetStatus(),
                    c.errors(), List.copyOf(warnings), c.appliedDecisionState(), rc));
        }
        return new PromotionPreviewDTO(preview.sourceSessionId(), preview.targetSessionId(), preview.valid(),
                preview.errors(), List.copyOf(candidates), preview.uncoveredStudents());
    }

    /** Result context per student id for the given source classes of one session. */
    public Map<String, ResultContext> forClasses(Long schoolId, Long sourceSessionId, Map<Long, Set<String>> studentsByClass) {
        Map<String, ResultContext> out = new HashMap<>();
        for (Map.Entry<Long, Set<String>> entry : studentsByClass.entrySet()) {
            if (entry.getKey() == null) continue;
            try {
                out.putAll(forClass(schoolId, sourceSessionId, entry.getKey(), entry.getValue()));
            } catch (RuntimeException e) {
                // Context is informational only: a problem here must never block the promotion preview.
                log.warn("Promotion result context unavailable for class {} (session {}): {}",
                        entry.getKey(), sourceSessionId, e.getMessage());
            }
        }
        return out;
    }

    private Map<String, ResultContext> forClass(Long schoolId, Long sessionId, Long classId, Set<String> studentIds) {
        Map<String, ResultContext> out = new HashMap<>();
        List<ReportCardSetup> setups = setupRepo.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(
                schoolId, sessionId, classId);
        if (setups.isEmpty()) {
            studentIds.forEach(id -> out.put(id, ResultContext.none("NO_SETUP", null)));
            return out;
        }
        ReportCardSetup setup = setups.getLast();

        Map<String, ReportCardDocument> documents = new HashMap<>();
        for (ReportCardDocument d : documentRepo.findBySetupIdAndSchoolIdAndStatus(setup.getId(), schoolId, ReportCardPublicationStatus.ACTIVE)) {
            documents.putIfAbsent(d.getStudentId(), d);
        }
        Set<String> withoutDocument = new HashSet<>();
        for (String id : studentIds) {
            ReportCardDocument d = documents.get(id);
            ResultContext fromDocument = d == null ? null : fromSnapshot(d, setup.getName());
            if (fromDocument != null) out.put(id, fromDocument);
            else withoutDocument.add(id);
        }
        if (withoutDocument.isEmpty()) return out;

        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        boolean allPublished = !cls.columns().isEmpty()
                && cls.columns().stream().allMatch(c -> c.resultStatus() == ExamResultStatus.PUBLISHED);
        for (String id : withoutDocument) {
            ReportCardV2Builder.StudentResult s = allPublished ? cls.student(id) : null;
            if (!allPublished) {
                out.put(id, ResultContext.none("RESULTS_NOT_PUBLISHED", setup.getName()));
            } else if (s == null) {
                out.put(id, new ResultContext("RESULTS", setup.getName(), null, null, "NO_RESULT", "NOT_PUBLISHED", null));
            } else {
                out.put(id, new ResultContext("RESULTS", setup.getName(), s.percentage(), s.grade(),
                        s.status().name(), "NOT_PUBLISHED", null));
            }
        }
        return out;
    }

    /** The frozen result of a published document (null if its snapshot can't be read). */
    private static ResultContext fromSnapshot(ReportCardDocument d, String setupName) {
        try {
            JsonNode result = JSON.readTree(d.getSnapshot()).path("result");
            return new ResultContext("REPORT_CARD", setupName,
                    result.hasNonNull("percentage") ? result.get("percentage").asDouble() : null,
                    result.hasNonNull("grade") ? result.get("grade").asText() : null,
                    result.hasNonNull("status") ? result.get("status").asText() : null,
                    "PUBLISHED", d.getReference());
        } catch (Exception e) {
            return null;
        }
    }
}
