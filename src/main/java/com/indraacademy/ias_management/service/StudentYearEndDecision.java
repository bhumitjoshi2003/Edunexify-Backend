package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;

/** Internal E1 domain contract. It is deliberately independent of the legacy promotion API DTOs. */
public final class StudentYearEndDecision {
    private StudentYearEndDecision() {}

    /**
     * PROMOTE, DETAIN and PASS_OUT are applied by {@link StudentYearEndService}. TRANSFER is the
     * year-end leaving decision, which the promotion coordinator routes to the year-end exit path,
     * and PENDING is an explicit "reviewed, decide later" — the year-end service rejects both.
     * Withdraw is deliberately not a year-end decision; exceptional withdrawals use the normal
     * Student Details exit.
     */
    public enum Action {
        PROMOTE, DETAIN, PASS_OUT, TRANSFER, PENDING;

        public boolean isYearEndMembershipAction() {
            return this == PROMOTE || this == DETAIN || this == PASS_OUT;
        }
    }

    public enum Outcome {
        PROMOTED, DETAINED, PASSED_OUT, TRANSFERRED, ALREADY_APPLIED, CONFLICT, INVALID_SOURCE
    }

    public record AuditContext(String username, String role, String ipAddress) {
        public AuditContext {
            username = username == null || username.isBlank() ? "SYSTEM" : username;
            role = role == null || role.isBlank() ? "SYSTEM" : role;
            ipAddress = ipAddress == null || ipAddress.isBlank() ? "SYSTEM" : ipAddress;
        }
    }

    public record Request(
            Long schoolId,
            String studentId,
            Long sourceSessionId,
            Long targetSessionId,
            Long expectedSourceEnrollmentId,
            Long expectedSourceClassId,
            Action action,
            Long targetClassId,
            Long targetSectionId,
            AuditContext auditContext) {}

    public record Result(
            Outcome outcome,
            String message,
            Long sourceEnrollmentId,
            Long targetEnrollmentId,
            StudentEnrollmentStatus targetEnrollmentStatus,
            boolean lifecycleFinalizationPending) {}
}
