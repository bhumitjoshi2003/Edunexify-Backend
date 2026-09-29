package com.indraacademy.ias_management.service;

import java.time.LocalDate;

/**
 * eventKind: ASSIGNED / CANCELLED (to the substitute) or ORIGINAL_ASSIGNED (to the teacher being
 * replaced, telling them who covers their period).
 */
public record TeacherSubstitutionNotificationEvent(
        Long schoolId, Long substitutionId, long revision, String recipientTeacherId,
        String eventKind, String className, String sectionName, String subjectName,
        Integer periodNumber, String startTime, String endTime, String originalTeacherName,
        LocalDate date, String actorUserId, String substituteTeacherName, String note) {

    public TeacherSubstitutionNotificationEvent(Long schoolId, Long substitutionId, long revision, String recipientTeacherId,
            String eventKind, String className, String sectionName, String subjectName, Integer periodNumber,
            String startTime, String endTime, String originalTeacherName, LocalDate date, String actorUserId) {
        this(schoolId, substitutionId, revision, recipientTeacherId, eventKind, className, sectionName, subjectName,
                periodNumber, startTime, endTime, originalTeacherName, date, actorUserId, null, null);
    }
}
