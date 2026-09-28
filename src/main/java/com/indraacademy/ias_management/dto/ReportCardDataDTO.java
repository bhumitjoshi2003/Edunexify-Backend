package com.indraacademy.ias_management.dto;

/**
 * Fully assembled report card data returned to the frontend.
 * The frontend renders sections defined in the template in display order,
 * using the data fields present in this DTO.
 */
public class ReportCardDataDTO {

    // ── Student info ──────────────────────────────────────────────────────
    private String studentId;
    private String studentName;
    private String className;
    private String sectionName;
    private String rollNumber;
    private String session;
    private String fatherName;
    private String motherName;
    private String dateOfBirth;
    private String photoUrl;

    // ── School info ───────────────────────────────────────────────────────
    private String schoolName;
    private String schoolLogoUrl;
    private String schoolAddress;
    private String schoolPhone;
    private String schoolEmail;
    private String affiliationNumber;
    private String schoolCode;
    private String schoolCity;
    /** Board type: CBSE, ICSE, STATE, OTHER — shown as subtitle in school header */
    private String boardType;
    /** Custom header image URL — when set, replaces the auto-generated school header in PDFs and web view. */
    private String reportCardHeaderImageUrl;

    // ── Template ──────────────────────────────────────────────────────────
    private ReportCardTemplateDTO template;

    // ── Grading system resolved for this report card ──────────────────────
    /** One of: PERCENTAGE, LETTER, CBSE */
    private String gradingSystem;

    // ── Weighted results (from WeightageCalculationEngine) ────────────────
    private WeightedGroupResultDTO weightedResult;

    // ── Attendance (populated if ATTENDANCE section is enabled) ───────────
    private AttendanceBlock attendance;

    // ── Remarks (populated when sections are enabled) ──────────────────────
    private String teacherRemarks;
    private String principalRemarks;

    // ── Co-Scholastic (populated if CO_SCHOLASTIC section is enabled) ────────
    private java.util.List<CoScholasticGrade> coScholasticGrades;

    // ── CBSE Compliance ───────────────────────────────────────────────────
    /** Overall letter/percentage grade derived from weightedResult. */
    private String overallGrade;
    /** CGPA (0-10) computed from subject-level CBSE grade points. Null if not CBSE grading. */
    private Double cgpa;
    /** UUID token for QR-based public verification. Null if not yet published. */
    private String verificationToken;
    /** Heading naming what the card covers: "HALF YEARLY — REPORT CARD" or "ANNUAL REPORT CARD". */
    private String reportTitle;

    public String getReportTitle() { return reportTitle; }
    public void setReportTitle(String reportTitle) { this.reportTitle = reportTitle; }

    public ReportCardDataDTO() {}

    // ── Getters / Setters ─────────────────────────────────────────────────

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSectionName() { return sectionName; }
    public void setSectionName(String sectionName) { this.sectionName = sectionName; }

    public String getRollNumber() { return rollNumber; }
    public void setRollNumber(String rollNumber) { this.rollNumber = rollNumber; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getFatherName() { return fatherName; }
    public void setFatherName(String fatherName) { this.fatherName = fatherName; }

    public String getMotherName() { return motherName; }
    public void setMotherName(String motherName) { this.motherName = motherName; }

    public String getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(String dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }

    public String getSchoolLogoUrl() { return schoolLogoUrl; }
    public void setSchoolLogoUrl(String schoolLogoUrl) { this.schoolLogoUrl = schoolLogoUrl; }

    public String getSchoolAddress() { return schoolAddress; }
    public void setSchoolAddress(String schoolAddress) { this.schoolAddress = schoolAddress; }

    public String getSchoolPhone() { return schoolPhone; }
    public void setSchoolPhone(String schoolPhone) { this.schoolPhone = schoolPhone; }

    public String getSchoolEmail() { return schoolEmail; }
    public void setSchoolEmail(String schoolEmail) { this.schoolEmail = schoolEmail; }

    public String getAffiliationNumber() { return affiliationNumber; }
    public void setAffiliationNumber(String affiliationNumber) { this.affiliationNumber = affiliationNumber; }

    public String getSchoolCode() { return schoolCode; }
    public void setSchoolCode(String schoolCode) { this.schoolCode = schoolCode; }

    public String getSchoolCity() { return schoolCity; }
    public void setSchoolCity(String schoolCity) { this.schoolCity = schoolCity; }

    public String getBoardType() { return boardType; }
    public void setBoardType(String boardType) { this.boardType = boardType; }

    public String getReportCardHeaderImageUrl() { return reportCardHeaderImageUrl; }
    public void setReportCardHeaderImageUrl(String reportCardHeaderImageUrl) { this.reportCardHeaderImageUrl = reportCardHeaderImageUrl; }

    public ReportCardTemplateDTO getTemplate() { return template; }
    public void setTemplate(ReportCardTemplateDTO template) { this.template = template; }

    public String getGradingSystem() { return gradingSystem; }
    public void setGradingSystem(String gradingSystem) { this.gradingSystem = gradingSystem; }

    public WeightedGroupResultDTO getWeightedResult() { return weightedResult; }
    public void setWeightedResult(WeightedGroupResultDTO weightedResult) { this.weightedResult = weightedResult; }

    public AttendanceBlock getAttendance() { return attendance; }
    public void setAttendance(AttendanceBlock attendance) { this.attendance = attendance; }

    public String getTeacherRemarks() { return teacherRemarks; }
    public void setTeacherRemarks(String teacherRemarks) { this.teacherRemarks = teacherRemarks; }

    public String getPrincipalRemarks() { return principalRemarks; }
    public void setPrincipalRemarks(String principalRemarks) { this.principalRemarks = principalRemarks; }

    public java.util.List<CoScholasticGrade> getCoScholasticGrades() { return coScholasticGrades; }
    public void setCoScholasticGrades(java.util.List<CoScholasticGrade> coScholasticGrades) { this.coScholasticGrades = coScholasticGrades; }

    public String getOverallGrade() { return overallGrade; }
    public void setOverallGrade(String overallGrade) { this.overallGrade = overallGrade; }

    public Double getCgpa() { return cgpa; }
    public void setCgpa(Double cgpa) { this.cgpa = cgpa; }

    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

    // ── Nested: CoScholasticGrade ─────────────────────────────────────────

    public static class CoScholasticGrade {
        private String activity;
        private String grade;

        public CoScholasticGrade() {}

        public CoScholasticGrade(String activity, String grade) {
            this.activity = activity;
            this.grade = grade;
        }

        public String getActivity() { return activity; }
        public void setActivity(String activity) { this.activity = activity; }

        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
    }

    // ── Nested: AttendanceBlock ───────────────────────────────────────────

    public static class AttendanceBlock {
        private int workingDays;
        private int presentDays;
        private double percentage;

        public AttendanceBlock() {}

        public AttendanceBlock(int workingDays, int presentDays, double percentage) {
            this.workingDays = workingDays;
            this.presentDays = presentDays;
            this.percentage = percentage;
        }

        public int getWorkingDays() { return workingDays; }
        public void setWorkingDays(int workingDays) { this.workingDays = workingDays; }

        public int getPresentDays() { return presentDays; }
        public void setPresentDays(int presentDays) { this.presentDays = presentDays; }

        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
    }
}
