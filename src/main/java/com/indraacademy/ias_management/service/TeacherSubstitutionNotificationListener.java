package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

@Component
public class TeacherSubstitutionNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(TeacherSubstitutionNotificationListener.class);
    private final BusinessNotificationService notifications;

    public TeacherSubstitutionNotificationListener(BusinessNotificationService notifications) {
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(TeacherSubstitutionNotificationEvent event) {
        try {
            String kind = event.eventKind();
            boolean cancelled = "CANCELLED".equals(kind);
            String classLabel = event.sectionName() == null || event.sectionName().isBlank()
                    ? "Class " + event.className() : "Class " + event.className() + " " + event.sectionName();
            String when = "Period " + event.periodNumber()
                    + (event.startTime() != null && event.endTime() != null
                        ? " (" + event.startTime() + "–" + event.endTime() + ")" : "")
                    + " on " + event.date();
            String subject = event.subjectName() == null || event.subjectName().isBlank() ? "" : ", " + event.subjectName();
            String note = event.note() == null || event.note().isBlank() ? "" : " Note: " + event.note();
            String title;
            String message;
            if ("ORIGINAL_ASSIGNED".equals(kind)) {
                title = "Your Period Is Covered";
                message = String.format("%s will cover your %s%s, %s.%s",
                        event.substituteTeacherName() == null ? "A substitute" : event.substituteTeacherName(),
                        classLabel, subject, when, note);
            } else if (cancelled) {
                title = "Substitution Cancelled";
                message = String.format("Your cover for %s%s, %s has been cancelled or reassigned.", classLabel, subject, when);
            } else {
                title = "Substitution Assigned";
                message = String.format("You have been assigned %s%s, %s, replacing %s.%s",
                        classLabel, subject, when, event.originalTeacherName(), note);
            }
            notifications.direct(event.schoolId(), event.recipientTeacherId(),
                    cancelled ? NotificationEventCode.TEACHER_SUBSTITUTION_CANCELLED
                            : NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED,
                    NotificationCategory.ACADEMICS_RESULTS, title, message,
                    "TeacherSubstitution", String.valueOf(event.substitutionId()),
                    "/dashboard/teacher-dashboard", event.actorUserId(),
                    "teacher-substitution:" + event.substitutionId() + ":" + event.revision() + ":"
                            + event.eventKind() + ":" + event.recipientTeacherId(),
                    Set.of(ExternalDeliveryChannel.PUSH));
        } catch (RuntimeException failure) {
            log.error("Substitution {} persisted but notification {} to teacher {} failed",
                    event.substitutionId(), event.eventKind(), event.recipientTeacherId(), failure);
        }
    }
}
