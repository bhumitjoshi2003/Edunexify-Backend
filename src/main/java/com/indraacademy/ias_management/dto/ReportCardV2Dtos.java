package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.ExamResultStatus;
import com.indraacademy.ias_management.entity.ReportCardResultMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Request/response shapes of the Report Card V2 API (Phase 1: setup, design, remarks, preview). */
public final class ReportCardV2Dtos {
    private ReportCardV2Dtos() {}

    // ── Setup ─────────────────────────────────────────────────────────────

    /** A term in a setup request; {@code key} links exams to it (new terms have no id yet). */
    public record TermInput(String key, String name, BigDecimal weight) {}

    public record ExamInput(Long examConfigId, String termKey, BigDecimal weight) {}

    public record SetupRequest(Long academicSessionId, Long classId, String name, ReportCardResultMode resultMode,
                               Integer displayOrder, List<TermInput> terms, List<ExamInput> exams) {}

    public record TermDTO(Long id, String name, BigDecimal weight, int displayOrder) {}

    public record SetupExamDTO(Long id, Long examConfigId, String examName, ExamResultStatus resultStatus,
                               Long termId, String termName, BigDecimal weight, int displayOrder) {}

    public record SetupDTO(Long id, Long academicSessionId, String sessionLabel, Long classId, String className,
                           String name, ReportCardResultMode resultMode, int displayOrder,
                           List<TermDTO> terms, List<SetupExamDTO> exams, long revision) {}

    /** An exam the admin can add to a setup (same school, session and class). */
    public record AvailableExamDTO(Long id, String examName, ExamResultStatus resultStatus, int subjects) {}

    // ── Design ────────────────────────────────────────────────────────────

    public record DesignDTO(String motto, String footerText, String watermarkMode, String watermarkText,
                            boolean showPhoto, boolean showQr, boolean showAttendance, boolean showCoScholastic,
                            boolean showTeacherRemark, boolean showPrincipalRemark, boolean showRank,
                            boolean showPromotion, String teacherSignatureLabel, String principalSignatureLabel,
                            Long revision) {}

    public record ActivityDTO(Long id, String name, int displayOrder, boolean active) {}

    public record ActivityRequest(String name, Boolean active) {}

    // ── Remarks & co-scholastic ───────────────────────────────────────────

    public record RemarkRowDTO(String studentId, String studentName, Long sectionId, String sectionName,
                               String teacherRemark, String principalRemark, Map<Long, String> grades,
                               boolean locked) {}

    public record RemarksPageDTO(Long setupId, String setupName, String className, Long sectionId,
                                 boolean canEditPrincipal, List<ActivityDTO> activities, List<String> gradeScale,
                                 List<RemarkRowDTO> students) {}

    /**
     * One student's changes. A null field is left unchanged; an empty string clears it. A grade
     * of "" clears that activity's grade.
     */
    public record RemarkSaveRow(String studentId, String teacherRemark, String principalRemark, Map<Long, String> grades) {}

    public record RemarksSaveRequest(List<RemarkSaveRow> students) {}

    // ── Generate & Preview ────────────────────────────────────────────────

    public record ReadinessDTO(List<String> draftExams, int students, int incomplete, int noResult,
                               int missingTeacherRemarks, int missingPrincipalRemarks, int missingCoScholastic,
                               int missingPhotos) {}

    public record SummaryRowDTO(String studentId, String studentName, Long sectionId, String sectionName,
                                Double percentage, String grade, Integer rank, String status, int marksMissing,
                                boolean hasTeacherRemark, boolean hasPrincipalRemark, boolean coScholasticComplete,
                                boolean hasPhoto) {}

    public record SummaryDTO(Long setupId, String setupName, ReportCardResultMode resultMode, String className,
                             String sessionLabel, Long sectionId, boolean teacherView, ReadinessDTO readiness,
                             List<SummaryRowDTO> students) {}

    // ── Publication (Phase 2) ─────────────────────────────────────────────

    public record PublishRequest(Long sectionId, boolean includeIncomplete) {}

    public record WithdrawRequest(String reason) {}

    public record PublicationDTO(Long id, Long setupId, String setupName, Long sectionId, String sectionName,
                                 int version, String status, int documentCount, String publishedBy,
                                 java.time.LocalDateTime publishedAt, String withdrawnBy,
                                 java.time.LocalDateTime withdrawnAt, String withdrawalReason) {}

    public record StudentProblem(String studentId, String studentName, String reason) {}

    /** Outcome of a publish: either a new ACTIVE publication, or nothing published and why. */
    public record PublishResultDTO(boolean published, PublicationDTO publication, int documents,
                                   List<StudentProblem> excludedIncomplete, List<StudentProblem> failed,
                                   String message) {}

    /** A frozen, published report card (no marks — those are only in the stored PDF). */
    public record DocumentDTO(Long id, Long publicationId, String studentId, String studentName, String title,
                              String sessionLabel, String className, String sectionName, int version,
                              String status, String reference, java.time.LocalDateTime issuedAt) {}

    public record BulkCheckDTO(int total, int available, List<StudentProblem> missing) {}

    public record SendResultDTO(int documents, int queued, String message) {}
}
