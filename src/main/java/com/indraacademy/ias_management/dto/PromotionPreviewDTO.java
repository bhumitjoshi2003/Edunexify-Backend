package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import com.indraacademy.ias_management.service.StudentYearEndDecision;

import java.util.List;

/** Read-only, backend-authoritative preview for one explicit source/target session pair. */
public record PromotionPreviewDTO(
        Long sourceSessionId,
        Long targetSessionId,
        boolean valid,
        List<Issue> errors,
        List<Candidate> candidates,
        List<UncoveredStudent> uncoveredStudents) {
    public record Issue(String code, String message) {}
    public record Candidate(
            String studentId, String studentName,
            Long sourceEnrollmentId, Long sourceSessionId,
            Long sourceClassId, String sourceClassName,
            Long sourceSectionId, String sourceSectionName,
            List<StudentYearEndDecision.Action> availableDecisions,
            StudentYearEndDecision.Action recommendedDecision,
            Long promoteTargetClassId, String promoteTargetClassName,
            Long detainTargetClassId, String detainTargetClassName,
            boolean promoteTargetSectionRequired,
            Long proposedPromoteTargetSectionId,
            Long proposedDetainTargetSectionId,
            StudentEnrollmentStatus proposedTargetStatus,
            List<Issue> errors, List<Issue> warnings,
            String appliedDecisionState,
            ResultContext result) {}

    /**
     * Read-only result context for the admin's decision — never used to decide anything.
     * {@code source}: REPORT_CARD (the student's published Report Card V2 document), RESULTS (the
     * canonical Report Card V2 calculation over published exam results, report card not yet
     * published) or NONE. {@code result}: PASS, FAIL, INCOMPLETE, NO_RESULT or null.
     * {@code reportCardStatus}: PUBLISHED, NOT_PUBLISHED, RESULTS_NOT_PUBLISHED or NO_SETUP.
     */
    public record ResultContext(
            String source, String setupName, Double percentage, String grade, String result,
            String reportCardStatus, String reportCardReference) {

        public static ResultContext none(String reportCardStatus, String setupName) {
            return new ResultContext("NONE", setupName, null, null, null, reportCardStatus, null);
        }
    }
    public record UncoveredStudent(String studentId, String studentName, String code, String message) {}
}
