package com.indraacademy.ias_management.service;

import java.util.List;

/**
 * Report Card V2: everything the canonical document shows, already worked out and formatted by
 * the backend (the renderer only lays it out). Built by {@link ReportCardV2Service} from the
 * canonical {@link ReportCardV2Builder} result, the school, its design, remarks and attendance.
 */
public record ReportCardV2ViewModel(
        SchoolBlock school,
        String title,
        String sessionLabel,
        StudentBlock student,
        List<TermHeader> termHeaders,
        List<ColumnHeader> columns,
        List<SubjectRow> rows,
        TotalsRow totals,
        Summary summary,
        Attendance attendance,
        List<CoScholastic> coScholastic,
        String teacherRemark,
        String principalRemark,
        Flags flags,
        String teacherSignatureLabel,
        String principalSignatureLabel,
        String footerText,
        String watermarkText,
        String watermarkLogo,
        String density,
        boolean splitPages,
        String previewLine,
        String verificationQr,
        String verificationRef,
        String issuedLine) {

    public record SchoolBlock(String name, String addressLine, String affiliationLine, String motto,
                              String logoUrl, String headerImageUrl) {}

    public record StudentBlock(String name, String studentId, String className, String sectionName, String dob,
                               String fatherName, String motherName, String photoUrl) {}

    /** A term spanning {@code span} exam columns (only when the setup has terms). */
    public record TermHeader(String name, int span, String weightLabel) {}

    public record ColumnHeader(String examName, String weightLabel) {}

    /** One subject: a cell per exam column ("—", "Not entered" or "72 / 100"), then total, % and grade. */
    public record SubjectRow(String subject, List<Cell> cells, String total, String percentage, String grade) {}

    /** A table cell's text and style ("" normal, "muted" for —, "pending" for Not entered). */
    public record Cell(String text, String css) {
        public static Cell of(String text) {
            return new Cell(text, "—".equals(text) ? "muted" : "Not entered".equals(text) || "Incomplete".equals(text) ? "pending" : "");
        }
    }

    public record TotalsRow(List<String> cells, String total, String percentage, String grade) {}

    public record Summary(String percentage, String grade, String rank, String result, String resultNote, String basis) {}

    public record Attendance(String workingDays, String present, String percentage) {}

    public record CoScholastic(String activity, String grade) {}

    public record Flags(boolean photo, boolean attendance, boolean coScholastic, boolean teacherRemark,
                        boolean principalRemark, boolean rank, boolean promotion) {}

    /** A published card's verification QR (data URI) and short reference; null when not published. */
    public boolean hasVerification() { return verificationQr != null; }

    public boolean hasTerms() { return termHeaders != null && !termHeaders.isEmpty(); }

    public int columnCount() { return columns.size(); }
}
