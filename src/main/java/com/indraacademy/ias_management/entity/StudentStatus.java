package com.indraacademy.ias_management.entity;

public enum StudentStatus {
    ACTIVE,
    INACTIVE,      // Legacy — migrated to WITHDRAWN by V6 migration; kept for backward compat
    UPCOMING,
    GRADUATED,     // Completed final class (passed out)
    TRANSFERRED,   // Left to join another school
    WITHDRAWN,     // Left mid-year (dropout, family decision, relocation, etc.)
    ADMISSION_CANCELLED; // Admitted for a future date but cancelled before ever joining

    /**
     * Returns true for terminal exit statuses that the scheduler and
     * calculateStatus() must never overwrite.
     */
    public boolean isExitStatus() {
        return this == GRADUATED || this == TRANSFERRED || this == WITHDRAWN || this == INACTIVE
                || this == ADMISSION_CANCELLED;
    }
}
