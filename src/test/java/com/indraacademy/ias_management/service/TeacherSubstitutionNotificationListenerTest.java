package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherSubstitutionNotificationListenerTest {
    @Mock BusinessNotificationService notifications;
    TeacherSubstitutionNotificationListener listener;

    static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    @BeforeEach
    void setup() {
        listener = new TeacherSubstitutionNotificationListener(notifications);
    }

    private TeacherSubstitutionNotificationEvent assignedEvent() {
        return new TeacherSubstitutionNotificationEvent(1L, 500L, 0L, "T2", "ASSIGNED",
                "X", "A", "Maths", 3, "09:10", "09:50", "Mr Original", DATE, "A1");
    }

    private TeacherSubstitutionNotificationEvent cancelledEvent() {
        return new TeacherSubstitutionNotificationEvent(1L, 500L, 1L, "T2", "CANCELLED",
                "X", "A", "Maths", 3, "09:10", "09:50", "Mr Original", DATE, "A1");
    }

    @Test
    void assignedEventNotifiesTheNewSubstituteViaExistingPushChannel() {
        listener.afterCommit(assignedEvent());

        verify(notifications).direct(eq(1L), eq("T2"), eq(NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED),
                eq(NotificationCategory.ACADEMICS_RESULTS), anyString(), anyString(),
                eq("TeacherSubstitution"), eq("500"), eq("/dashboard/teacher-dashboard"), eq("A1"),
                anyString(), eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    @Test
    void cancelledEventNotifiesTheOldSubstituteWithTheCancelledEventCode() {
        listener.afterCommit(cancelledEvent());

        verify(notifications).direct(eq(1L), eq("T2"), eq(NotificationEventCode.TEACHER_SUBSTITUTION_CANCELLED),
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void idempotencyKeyIsUniquePerSubstitutionRevisionKindAndRecipient() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(assignedEvent());
        verify(notifications).direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), keyCaptor.capture(), any());

        String key = keyCaptor.getValue();
        assertThat(key).isEqualTo("teacher-substitution:500:0:ASSIGNED:T2");
    }

    @Test
    void changingRevisionProducesADistinctIdempotencyKeyFromTheOriginalAssignment() {
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(assignedEvent());
        listener.afterCommit(cancelledEvent());
        verify(notifications, times(2)).direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), keyCaptor.capture(), any());

        assertThat(keyCaptor.getAllValues()).containsExactly(
                "teacher-substitution:500:0:ASSIGNED:T2",
                "teacher-substitution:500:1:CANCELLED:T2");
    }

    @Test
    void aNotificationDispatchFailureIsSwallowedNotPropagated() {
        when(notifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("push provider unavailable"));

        assertThatCode(() -> listener.afterCommit(assignedEvent())).doesNotThrowAnyException();
    }

    @Test
    void substituteMessageCarriesClassPeriodTimeSubjectReplacedTeacherAndNote() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(new TeacherSubstitutionNotificationEvent(1L, 500L, 0L, "T2", "ASSIGNED",
                "X", "A", "Maths", 3, "09:10", "09:50", "Mr Original", DATE, "A1", "Ms Sub", "Chapter 4 worksheet"));
        verify(notifications).direct(anyLong(), anyString(), any(), any(), anyString(), message.capture(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertThat(message.getValue()).contains("Class X A", "Maths", "Period 3", "09:10", "09:50",
                "Mr Original", "Chapter 4 worksheet");
    }

    @Test
    void theOriginalTeacherIsToldWhoCoversTheirPeriod_withItsOwnDedupKey() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        listener.afterCommit(new TeacherSubstitutionNotificationEvent(1L, 500L, 0L, "T1", "ORIGINAL_ASSIGNED",
                "X", "A", "Maths", 3, "09:10", "09:50", "Mr Original", DATE, "A1", "Ms Sub", null));
        verify(notifications).direct(eq(1L), eq("T1"), eq(NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED),
                any(), anyString(), message.capture(), anyString(), anyString(), anyString(), anyString(),
                key.capture(), eq(Set.of(ExternalDeliveryChannel.PUSH)));
        assertThat(message.getValue()).contains("Ms Sub", "Class X A", "Period 3").doesNotContain("Note:");
        assertThat(key.getValue()).isEqualTo("teacher-substitution:500:0:ORIGINAL_ASSIGNED:T1");
    }
}
