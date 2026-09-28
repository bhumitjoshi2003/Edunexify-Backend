package com.indraacademy.ias_management.dto;

/**
 * Public response for report-card QR verification.
 * Returned by GET /api/public/verify-rc?token={uuid} — no authentication required.
 * Contains only non-sensitive publication metadata (no marks, no personal data).
 */
public class VerifyRcDTO {

    private boolean valid;
    private String  schoolName;
    private String  className;
    private String  session;
    private String  publishedAt;   // ISO date string
    private String  publishedBy;
    private String  message;       // shown when valid=false

    // Report Card V2 documents only (absent — not even null — in legacy responses).
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String  title;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String  studentName;   // limited identity, e.g. "Aarav S."
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String  reference;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Integer version;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String  status;        // VALID / SUPERSEDED / WITHDRAWN

    public VerifyRcDTO() {}

    /** A Report Card V2 document's public verification result (no marks or private details). */
    public static VerifyRcDTO document(boolean valid, String status, String schoolName, String title, String className,
                                       String session, String issuedAt, String studentName, String reference,
                                       Integer version, String message) {
        VerifyRcDTO dto = new VerifyRcDTO();
        dto.valid = valid;
        dto.status = status;
        dto.schoolName = schoolName;
        dto.title = title;
        dto.className = className;
        dto.session = session;
        dto.publishedAt = issuedAt;
        dto.studentName = studentName;
        dto.reference = reference;
        dto.version = version;
        dto.message = message;
        return dto;
    }

    public String getTitle()        { return title; }
    public String getStudentName()  { return studentName; }
    public String getReference()    { return reference; }
    public Integer getVersion()     { return version; }
    public String getStatus()       { return status; }

    public static VerifyRcDTO valid(String schoolName, String className,
                                    String session, String publishedAt, String publishedBy) {
        VerifyRcDTO dto = new VerifyRcDTO();
        dto.valid       = true;
        dto.schoolName  = schoolName;
        dto.className   = className;
        dto.session     = session;
        dto.publishedAt = publishedAt;
        dto.publishedBy = publishedBy;
        return dto;
    }

    public static VerifyRcDTO invalid(String message) {
        VerifyRcDTO dto = new VerifyRcDTO();
        dto.valid   = false;
        dto.message = message;
        return dto;
    }

    public boolean isValid()        { return valid; }
    public String getSchoolName()   { return schoolName; }
    public String getClassName()    { return className; }
    public String getSession()      { return session; }
    public String getPublishedAt()  { return publishedAt; }
    public String getPublishedBy()  { return publishedBy; }
    public String getMessage()      { return message; }
}
