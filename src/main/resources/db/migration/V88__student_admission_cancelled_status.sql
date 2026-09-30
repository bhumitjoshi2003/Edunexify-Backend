-- Student Admission Phase 1: an admission scheduled for a future date can be cancelled before
-- the student ever joins. That student never attended, so TRANSFERRED/WITHDRAWN would be wrong;
-- ADMISSION_CANCELLED records it explicitly. Existing values are unchanged.
ALTER TABLE student DROP CONSTRAINT student_status_check;

ALTER TABLE student ADD CONSTRAINT student_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'UPCOMING', 'GRADUATED', 'TRANSFERRED', 'WITHDRAWN',
                      'ADMISSION_CANCELLED'));
