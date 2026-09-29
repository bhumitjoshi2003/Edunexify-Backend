package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.TeacherLeave;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class TeacherLeaveResponse {

    private Long id;
    private String teacherId;
    private String teacherName;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;
    private String reason;
    private LeaveStatus status;
    private LocalDateTime appliedDate;
    /** Working leave days only (configured weekdays minus school holidays). */
    private long days;
    private String decidedBy;
    private LocalDateTime decidedAt;
    private String decisionReason;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
    private String cancellationReason;

    public static TeacherLeaveResponse from(TeacherLeave l) {
        TeacherLeaveResponse r = new TeacherLeaveResponse();
        r.id = l.getId();
        r.teacherId = l.getTeacherId();
        r.teacherName = l.getTeacherName();
        r.startDate = l.getStartDate();
        r.endDate = l.getEndDate();
        r.reason = l.getReason();
        r.status = l.getStatus();
        r.appliedDate = l.getAppliedDate();
        r.days = java.time.temporal.ChronoUnit.DAYS.between(l.getStartDate(), l.getEndDate()) + 1;
        r.decidedBy = l.getDecidedBy();
        r.decidedAt = l.getDecidedAt();
        r.decisionReason = l.getDecisionReason();
        r.cancelledBy = l.getCancelledBy();
        r.cancelledAt = l.getCancelledAt();
        r.cancellationReason = l.getCancellationReason();
        return r;
    }

    public static TeacherLeaveResponse from(TeacherLeave leave, long workingLeaveDays) {
        TeacherLeaveResponse response = from(leave);
        response.days = workingLeaveDays;
        return response;
    }

    public Long getId() { return id; }
    public String getDecidedBy() { return decidedBy; }
    public LocalDateTime getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }
    public String getCancelledBy() { return cancelledBy; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public String getTeacherId() { return teacherId; }
    public String getTeacherName() { return teacherName; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public LocalDateTime getAppliedDate() { return appliedDate; }
    public long getDays() { return days; }
}
