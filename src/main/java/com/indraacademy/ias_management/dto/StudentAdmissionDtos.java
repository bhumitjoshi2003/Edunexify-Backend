package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin-editable student fields for admission and editing. Lifecycle-controlled values (ID,
 * school, status, leaving/exit details, photo, enrollment) are deliberately absent, so a
 * request can never write them — unknown JSON properties, including ones an older client still
 * sends back from a GET, are ignored.
 */
public final class StudentAdmissionDtos {
    private StudentAdmissionDtos() {}

    /** New admission (single form). */
    public record AdmissionRequest(
            @Size(max = 200) String name,
            @Size(max = 255) String email,
            @Size(max = 20) String phoneNumber,
            LocalDate dob,
            String className,
            Long sectionId,
            @Size(max = 10) String gender,
            @Size(max = 200) String fatherName,
            @Size(max = 200) String motherName,
            Boolean takesBus,
            Double distance,
            LocalDate joiningDate) {}

    /** Edit. Class, section and joining date are routed through enrollment-aware logic. */
    public record UpdateRequest(
            @Size(max = 200) String name,
            @Size(max = 255) String email,
            @Size(max = 20) String phoneNumber,
            LocalDate dob,
            String className,
            Long sectionId,
            @Size(max = 10) String gender,
            @Size(max = 200) String fatherName,
            @Size(max = 200) String motherName,
            Boolean takesBus,
            Double distance,
            LocalDate joiningDate) {}

    /** Existing PUT body shape: {@code {studentDetails, effectiveFromMonth}}. */
    public record UpdateEnvelope(UpdateRequest studentDetails, Integer effectiveFromMonth) {}

    /** Readmission choices; every field is optional (older clients send an empty body). When
     *  classId is given, sectionId is taken exactly as sent (null = no section). */
    public record ReadmitRequest(Long classId, Long sectionId, LocalDate readmissionDate) {}

    public record CancelAdmissionRequest(@Size(max = 500) String reason) {}

    /** One enrollment period for the read-only history view. */
    public record EnrollmentHistoryItem(
            Long id,
            Long academicSessionId,
            String sessionLabel,
            Long classId,
            String className,
            Long sectionId,
            String sectionName,
            StudentEnrollmentStatus status,
            String state,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            StudentEnrollmentClosureReason closureReason) {}

    public record LoginStatus(boolean exists, boolean active) {}

    /** A parent link ended by the student's exit that an admin may choose to restore. */
    public record RestorableParentLink(
            Long relationshipId,
            String parentId,
            String parentName,
            String relationshipType,
            boolean primaryGuardian,
            LocalDate endedOn) {}

    public record RestoreParentLinksRequest(List<Long> relationshipIds) {}
}
