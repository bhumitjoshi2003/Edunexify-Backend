package com.indraacademy.ias_management.dto;

import java.util.List;
import java.util.Map;

/** Per-student outcomes plus machine-readable batch summary. */
public record PromotionResultDTO(
        int submitted,
        Map<String, Long> summary,
        List<StudentOutcome> outcomes,
        RunSummary run) {

    public PromotionResultDTO(int submitted, Map<String, Long> summary, List<StudentOutcome> outcomes) {
        this(submitted, summary, outcomes, null);
    }

    /** The audit record of this batch (student_rollover_run); null when it could not be recorded. */
    public record RunSummary(
            Long id, String status, Long sourceSessionId, Long targetSessionId, Long classId,
            String startedBy, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt,
            int totalStudents, int promoted, int detained, int passOut, int transferred,
            int pending, int alreadyApplied, int failed) {}

    public record StudentOutcome(
            String studentId, String code, String message,
            Long sourceEnrollmentId, Long targetEnrollmentId,
            String targetEnrollmentStatus,
            boolean lifecycleFinalizationPending) {}
}
