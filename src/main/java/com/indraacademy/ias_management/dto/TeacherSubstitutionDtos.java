package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.TeacherSubstitution;
import com.indraacademy.ias_management.entity.TeacherSubstitutionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TeacherSubstitutionDtos {
    private TeacherSubstitutionDtos() {}

    public record UpsertRequest(@NotNull Long timetableEntryId, @NotNull LocalDate date,
                                @NotBlank String substituteTeacherId,
                                @Size(max = 300) String note) {
        public UpsertRequest(Long timetableEntryId, LocalDate date, String substituteTeacherId) {
            this(timetableEntryId, date, substituteTeacherId, null);
        }
    }

    /** A null note keeps the current note; "" clears it. */
    public record ChangeRequest(@NotBlank String substituteTeacherId, @Size(max = 300) String note) {
        public ChangeRequest(String substituteTeacherId) { this(substituteTeacherId, null); }
    }

    /**
     * A teacher who is free and available for the period, with the deterministic ranking signals:
     * same subject, already teaches that class/section, cover periods already assigned that day,
     * and whether it would make a back-to-back period. {@code reasons} holds the codes
     * SAME_SUBJECT, KNOWS_CLASS, COVERING_N (N = coveringToday, only when above 0) and BACK_TO_BACK.
     */
    public record FreeTeacher(String teacherId, String name, boolean sameSubject, boolean knowsClass,
                              int coveringToday, boolean backToBack, List<String> reasons) {
        public FreeTeacher(String teacherId, String name) {
            this(teacherId, name, false, false, 0, false, List.of());
        }
    }

    public record Assignment(Long id, long revision, LocalDate date, Long timetableEntryId,
                             String originalTeacherId, String originalTeacherName,
                             String substituteTeacherId, String substituteTeacherName,
                             String className, String sectionName, String subjectName,
                             Integer periodNumber, String startTime, String endTime,
                             TeacherSubstitutionStatus status, String assignedBy,
                             LocalDateTime assignedAt, LocalDateTime updatedAt,
                             String note, String reasonSource, String cancelledBy, LocalDateTime cancelledAt) {
        public Assignment(Long id, long revision, LocalDate date, Long timetableEntryId,
                          String originalTeacherId, String originalTeacherName,
                          String substituteTeacherId, String substituteTeacherName,
                          String className, String sectionName, String subjectName,
                          Integer periodNumber, String startTime, String endTime,
                          TeacherSubstitutionStatus status, String assignedBy,
                          LocalDateTime assignedAt, LocalDateTime updatedAt) {
            this(id, revision, date, timetableEntryId, originalTeacherId, originalTeacherName, substituteTeacherId,
                    substituteTeacherName, className, sectionName, subjectName, periodNumber, startTime, endTime,
                    status, assignedBy, assignedAt, updatedAt, null, null, null, null);
        }

        public static Assignment from(TeacherSubstitution s) {
            return new Assignment(s.getId(), s.getRevision(), s.getDate(), s.getTimetableEntryId(),
                    s.getOriginalTeacherId(), s.getOriginalTeacherName(), s.getSubstituteTeacherId(),
                    s.getSubstituteTeacherName(), s.getClassName(), s.getSectionName(), s.getSubjectName(),
                    s.getPeriodNumber(), s.getStartTime(), s.getEndTime(), s.getStatus(), s.getAssignedBy(),
                    s.getAssignedAt(), s.getUpdatedAt(), s.getNote(), s.getReasonSource(), s.getCancelledBy(),
                    s.getCancelledAt());
        }
    }

    /**
     * One period of the day for the admin. {@code state}: NEEDS_SUBSTITUTE, COVERED or
     * NO_LONGER_NEEDED (an active cover whose original teacher is available again, or the school is
     * closed that day — it stays active until an admin removes it). {@code unavailabilityReason}:
     * APPROVED_LEAVE, ABSENT or ON_LEAVE (null when no longer needed); leave dates are set for
     * approved leave. {@code suggested} is the top-ranked free teacher, if any.
     */
    public record UncoveredPeriod(Long timetableEntryId, String originalTeacherId, String originalTeacherName,
                                  String className, String sectionName, String subjectName, Integer periodNumber,
                                  String startTime, String endTime, Assignment assignment,
                                  List<FreeTeacher> freeTeachers, String state, String unavailabilityReason,
                                  LocalDate leaveStart, LocalDate leaveEnd, FreeTeacher suggested) {
        public UncoveredPeriod(Long timetableEntryId, String originalTeacherId, String originalTeacherName,
                               String className, String sectionName, String subjectName, Integer periodNumber,
                               String startTime, String endTime, Assignment assignment,
                               List<FreeTeacher> freeTeachers) {
            this(timetableEntryId, originalTeacherId, originalTeacherName, className, sectionName, subjectName,
                    periodNumber, startTime, endTime, assignment, freeTeachers,
                    assignment == null ? "NEEDS_SUBSTITUTE" : "COVERED", null, null, null,
                    freeTeachers == null || freeTeachers.isEmpty() ? null : freeTeachers.get(0));
        }
    }

    /** Cover periods assigned to one substitute that day. */
    public record WorkloadRow(String teacherId, String teacherName, int covers) {}

    /**
     * The admin's day view. {@code closedReason} is set on a school holiday or non-working day (no
     * periods need cover then; any active cover is listed as no longer needed).
     */
    public record DayOverview(LocalDate date, String closedReason, List<UncoveredPeriod> periods,
                              List<WorkloadRow> workload, int needingSubstitute, int covered, int noLongerNeeded) {}

    /** One proposed cover in a "Fill all with suggested" preview. */
    public record FillProposal(Long timetableEntryId, Integer periodNumber, String className, String sectionName,
                               String subjectName, String originalTeacherName, String substituteTeacherId,
                               String substituteTeacherName, List<String> reasons) {}

    /** Periods with no free, available teacher to propose. */
    public record FillPreview(LocalDate date, List<FillProposal> proposals, List<UncoveredPeriod> unfillable) {}

    public record BulkItem(@NotNull Long timetableEntryId, @NotBlank String substituteTeacherId, @Size(max = 300) String note) {}

    public record BulkRequest(@NotNull LocalDate date, @NotEmpty @Valid List<BulkItem> items) {}

    /** status: ASSIGNED, CONFLICT or FAILED — every item is reported, nothing is hidden. */
    public record BulkOutcome(Long timetableEntryId, String status, String message, Assignment assignment) {}

    public record BulkResult(int assigned, int conflicts, int failed, List<BulkOutcome> outcomes) {}

    /** One of the caller's own periods on a day they are unavailable. */
    public record MyCoveragePeriod(Long timetableEntryId, Integer periodNumber, String startTime, String endTime,
                                   String className, String sectionName, String subjectName, boolean covered,
                                   String substituteTeacherName) {}

    public record MyCoverage(LocalDate date, boolean unavailable, String unavailabilityReason,
                             List<MyCoveragePeriod> periods) {}
}
