-- Teacher Substitution Phase 2: cancellation keeps who assigned the cover (cancelled_by is separate),
-- an optional admin note for the substitute, and why the original teacher was unavailable when the
-- cover was assigned. Purely additive; V72 is unchanged.

ALTER TABLE teacher_substitution
    ADD COLUMN cancelled_by  VARCHAR(255),
    ADD COLUMN cancelled_at  TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN note          VARCHAR(300),
    ADD COLUMN reason_source VARCHAR(20);

ALTER TABLE teacher_substitution
    ADD CONSTRAINT ck_teacher_substitution_reason_source
        CHECK (reason_source IS NULL OR reason_source IN ('LEAVE', 'ABSENCE'));
