-- Leave Management Phase 1: decision/cancellation history, a CANCELLED state instead of hard
-- deletes, and one active student leave per student per day.

-- ── Status: CANCELLED keeps the request instead of deleting it ───────────────────────────────
ALTER TABLE leaves DROP CONSTRAINT IF EXISTS leaves_status_check;
ALTER TABLE leaves ADD CONSTRAINT leaves_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));

ALTER TABLE teacher_leave ADD CONSTRAINT teacher_leave_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));

-- ── Decision and cancellation history (the audit log keeps the full snapshots) ───────────────
ALTER TABLE leaves
    ADD COLUMN decided_by          VARCHAR(255),
    ADD COLUMN decided_at          TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN decision_reason     VARCHAR(500),
    ADD COLUMN cancelled_by        VARCHAR(255),
    ADD COLUMN cancelled_at        TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN cancellation_reason VARCHAR(500);

ALTER TABLE teacher_leave
    ADD COLUMN decided_by          VARCHAR(255),
    ADD COLUMN decided_at          TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN decision_reason     VARCHAR(500),
    ADD COLUMN cancelled_by        VARCHAR(255),
    ADD COLUMN cancelled_at        TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN cancellation_reason VARCHAR(500);

-- ── Existing duplicate student leave (same school, student and date) ─────────────────────────
-- Nothing is deleted. In each group of active (PENDING/APPROVED) duplicates one request is kept —
-- APPROVED first, then the most recently applied — and the others become CANCELLED with a
-- recorded reason, so they stay visible in the history.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY school_id, student_id, leave_date
               ORDER BY CASE status WHEN 'APPROVED' THEN 0 ELSE 1 END, applied_date DESC, id DESC
           ) AS rn
    FROM leaves
    WHERE status IN ('PENDING', 'APPROVED')
)
UPDATE leaves l
SET status = 'CANCELLED',
    cancelled_by = 'SYSTEM',
    cancelled_at = CURRENT_TIMESTAMP,
    cancellation_reason = 'Duplicate request for the same date (merged during Leave Phase 1 upgrade)'
FROM ranked r
WHERE l.id = r.id AND r.rn > 1;

-- One active (PENDING or APPROVED) leave per student per day. Rejected and cancelled requests stay
-- as history and don't block a new request for the same day.
CREATE UNIQUE INDEX uq_leaves_active_student_date
    ON leaves (school_id, student_id, leave_date)
    WHERE status IN ('PENDING', 'APPROVED');

-- "On leave today" / approved-leave lookups by date.
CREATE INDEX idx_leaves_school_date_status ON leaves (school_id, leave_date, status);
