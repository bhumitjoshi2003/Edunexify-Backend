package com.indraacademy.ias_management.exception;

import com.indraacademy.ias_management.entity.LeaveStatus;

/**
 * Thrown when a leave status update would not change anything — the request's target status
 * already matches the leave's current status.
 *
 * <p>This is deliberately narrower than "leave must be PENDING to change": the product has a
 * real, existing reversal feature (APPROVED ↔ REJECTED, via the "Change Leave Status" action in
 * ViewLeavesComponent) that this exception must not break. What it exists to stop is a
 * <i>repeat</i> of a decision already recorded — re-approving an already-approved leave, or
 * re-rejecting an already-rejected one — which is exactly what a double-click, a retried request,
 * or two callers racing on the same still-PENDING leave with the same intended outcome would
 * otherwise produce: a second identical write, and a second "your leave has been approved"
 * notification to the student for a decision that was already made.
 *
 * <p>Controllers should let this become a 409 Conflict — see LeaveController's local catch and
 * GlobalExceptionHandler's handler, matching the established pattern for domain-specific
 * exceptions in this package.
 */
public class InvalidLeaveStatusTransitionException extends RuntimeException {

    private final Long leaveId;
    private final LeaveStatus currentStatus;
    private final LeaveStatus attemptedStatus;

    public InvalidLeaveStatusTransitionException(Long leaveId, LeaveStatus currentStatus, LeaveStatus attemptedStatus) {
        super(String.format(
                "Leave request %d is already %s. It cannot be set to %s again.",
                leaveId, currentStatus, attemptedStatus));
        this.leaveId          = leaveId;
        this.currentStatus    = currentStatus;
        this.attemptedStatus  = attemptedStatus;
    }

    /** Same conflict, with a specific explanation (e.g. "already decided — use reversal"). */
    public InvalidLeaveStatusTransitionException(Long leaveId, LeaveStatus currentStatus, LeaveStatus attemptedStatus,
                                                 String message) {
        super(message);
        this.leaveId          = leaveId;
        this.currentStatus    = currentStatus;
        this.attemptedStatus  = attemptedStatus;
    }

    public Long getLeaveId()               { return leaveId; }
    public LeaveStatus getCurrentStatus()   { return currentStatus; }
    public LeaveStatus getAttemptedStatus() { return attemptedStatus; }
}
