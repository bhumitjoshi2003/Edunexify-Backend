package com.indraacademy.ias_management.entity;

public enum LeaveStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Withdrawn by the requester or cancelled by an admin — kept as history, never deleted. */
    CANCELLED
}
