package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * What a student or parent may send when applying for leave: the day and the reason — nothing
 * else. The student, their name and class, the school and the status (always PENDING) are set by
 * the server; any other field in the request body is ignored.
 */
public class StudentLeaveApplyRequest {

    @NotNull(message = "A leave date is required.")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate leaveDate;

    @NotBlank(message = "A reason is required.")
    @Size(max = 500, message = "The reason can be at most 500 characters.")
    private String reason;

    public StudentLeaveApplyRequest() {}

    public StudentLeaveApplyRequest(LocalDate leaveDate, String reason) {
        this.leaveDate = leaveDate;
        this.reason = reason;
    }

    public LocalDate getLeaveDate() { return leaveDate; }
    public void setLeaveDate(LocalDate leaveDate) { this.leaveDate = leaveDate; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
