package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.service.StudentYearEndDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Canonical E2 batch request. Names are intentionally absent: IDs are revalidated server-side. */
public class PromotionDecisionRequest {
    @NotNull private Long sourceSessionId;
    @NotNull private Long targetSessionId;
    @NotEmpty @Valid private List<Decision> decisions;
    /** Optional source-class filter the batch was prepared with (recorded on the rollover run). */
    private Long classId;

    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public Long getTargetSessionId() { return targetSessionId; }
    public void setTargetSessionId(Long targetSessionId) { this.targetSessionId = targetSessionId; }
    public List<Decision> getDecisions() { return decisions; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public void setDecisions(List<Decision> decisions) { this.decisions = decisions; }

    public static class Decision {
        @jakarta.validation.constraints.NotBlank private String studentId;
        @NotNull private StudentYearEndDecision.Action action;
        @NotNull private Long expectedSourceEnrollmentId;
        @NotNull private Long expectedSourceClassId;
        private Long targetClassId;
        private Long targetSectionId;
        /** TRANSFER only: the reason recorded for the exit, and optionally the leaving date — which,
         *  if sent, must equal the source session end (the only effective date allowed). */
        private java.time.LocalDate leavingDate;
        @jakarta.validation.constraints.Size(max = 500) private String reason;

        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }
        public StudentYearEndDecision.Action getAction() { return action; }
        public void setAction(StudentYearEndDecision.Action action) { this.action = action; }
        public Long getExpectedSourceEnrollmentId() { return expectedSourceEnrollmentId; }
        public void setExpectedSourceEnrollmentId(Long value) { this.expectedSourceEnrollmentId = value; }
        public Long getExpectedSourceClassId() { return expectedSourceClassId; }
        public void setExpectedSourceClassId(Long value) { this.expectedSourceClassId = value; }
        public Long getTargetClassId() { return targetClassId; }
        public void setTargetClassId(Long targetClassId) { this.targetClassId = targetClassId; }
        public Long getTargetSectionId() { return targetSectionId; }
        public void setTargetSectionId(Long targetSectionId) { this.targetSectionId = targetSectionId; }
        public java.time.LocalDate getLeavingDate() { return leavingDate; }
        public void setLeavingDate(java.time.LocalDate leavingDate) { this.leavingDate = leavingDate; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
