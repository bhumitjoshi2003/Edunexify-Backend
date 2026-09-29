package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeacherSubstitutionServiceTest {
    @Mock TeacherSubstitutionRepository substitutions;
    @Mock TimetableRepository timetables;
    @Mock TeacherRepository teachers;
    @Mock TeacherLeaveRepository leaves;
    @Mock TeacherAttendanceRepository attendance;
    @Mock TimetableSessionAccessService sessions;
    @Mock SecurityUtil security;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PermissionService permissions;
    @Mock HttpServletRequest http;
    @Mock SchoolHolidayRepository holidayRepository;

    TeacherSubstitutionService service;

    // 2026-09-24 is a Thursday.
    static final LocalDate DATE = LocalDate.of(2026, 9, 24);
    static final Long SCHOOL_ID = 1L;
    static final Long SESSION_ID = 10L;

    TimetableEntry entry;
    Teacher substituteTeacher;

    @BeforeEach
    void setup() {
        service = new TeacherSubstitutionService(substitutions, timetables, teachers, leaves, attendance,
                sessions, security, audit, events, permissions);
        // "Today" is before DATE so assignments are for the future; holidays are checked.
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-20T06:00:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "holidays", holidayRepository);
        lenient().when(holidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());

        lenient().when(security.getSchoolId()).thenReturn(SCHOOL_ID);
        lenient().when(security.getUsername()).thenReturn("A1");
        lenient().when(security.getRole()).thenReturn(Role.ADMIN);
        lenient().when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(permissions.getPermissionKeysForRole(anyString(), anyLong()))
                .thenReturn(List.of("TIMETABLE_EDIT"));

        entry = new TimetableEntry();
        entry.setId(100L);
        entry.setSchoolId(SCHOOL_ID);
        entry.setAcademicSessionId(SESSION_ID);
        entry.setDay(Day.THURSDAY);
        entry.setPeriodNumber(3);
        entry.setClassName("X");
        entry.setSectionName("A");
        entry.setSubjectName("Maths");
        entry.setStartTime("09:10");
        entry.setEndTime("09:50");
        entry.setTeacherId("T1");
        entry.setTeacherName("Mr Original");

        substituteTeacher = new Teacher();
        substituteTeacher.setTeacherId("T2");
        substituteTeacher.setSchoolId(SCHOOL_ID);
        substituteTeacher.setName("Ms Substitute");
        substituteTeacher.setStatus(TeacherStatus.ACTIVE);

        AcademicSession session = new AcademicSession();
        session.setId(SESSION_ID);
        lenient().when(sessions.currentSessionOrNull(SCHOOL_ID)).thenReturn(session);
        lenient().when(sessions.lockOwnedSession(eq(SCHOOL_ID), eq(SESSION_ID))).thenReturn(session);

        TeacherLeave onLeave = new TeacherLeave();
        onLeave.setTeacherId("T1");
        lenient().when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(onLeave));
        lenient().when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of());

        lenient().when(timetables.findByIdAndSchoolId(100L, SCHOOL_ID)).thenReturn(Optional.of(entry));
        lenient().when(timetables.lockById(100L, SCHOOL_ID)).thenReturn(Optional.of(entry));
        lenient().when(timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(eq(SESSION_ID), anyString(), eq(SCHOOL_ID)))
                .thenReturn(List.of());
        lenient().when(timetables.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(entry));

        lenient().when(teachers.findByTeacherIdAndSchoolId("T2", SCHOOL_ID)).thenReturn(Optional.of(substituteTeacher));
        lenient().when(teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, SCHOOL_ID)).thenReturn(List.of(substituteTeacher));

        lenient().when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(
                eq(SCHOOL_ID), eq(DATE), eq(100L), eq(TeacherSubstitutionStatus.ACTIVE))).thenReturn(Optional.empty());
        lenient().when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of());
        lenient().when(substitutions.saveAndFlush(any())).thenAnswer(inv -> {
            TeacherSubstitution s = inv.getArgument(0);
            if (s.getId() == null) s.setId(500L);
            return s;
        });
    }

    private UpsertRequest request() {
        return new UpsertRequest(100L, DATE, "T2");
    }

    // ─── Authorization ───────────────────────────────────────────────────

    @Test
    void adminCanAssign() {
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
        assertThat(result.status()).isEqualTo(TeacherSubstitutionStatus.ACTIVE);
    }

    @Test
    void permittedSubAdminCanAssign() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenReturn(List.of("TIMETABLE_EDIT"));
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
    }

    @Test
    void unauthorizedSubAdminIsDenied() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenReturn(List.of("TIMETABLE_VIEW"));
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permission");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void permissionLookupFailureDeniesTheSubAdminMutation() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permission");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void adminMutationSucceedsEvenIfPermissionLookupWouldFail() {
        when(security.getRole()).thenReturn(Role.ADMIN);
        Assignment result = service.assign(request(), http);
        assertThat(result.substituteTeacherId()).isEqualTo("T2");
        verify(permissions, never()).getPermissionKeysForRole(anyString(), anyLong());
    }

    // ─── Tenant isolation ────────────────────────────────────────────────

    @Test
    void changeIsDeniedForASubstitutionInAnotherSchool() {
        TeacherSubstitution other = new TeacherSubstitution();
        other.setId(999L);
        other.setSchoolId(2L); // different school than the caller's SCHOOL_ID
        when(substitutions.findById(999L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.change(999L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void cancelIsDeniedForASubstitutionInAnotherSchool() {
        TeacherSubstitution other = new TeacherSubstitution();
        other.setId(999L);
        other.setSchoolId(2L);
        when(substitutions.findById(999L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.cancel(999L, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
    }

    // ─── Conflict rules ──────────────────────────────────────────────────

    @Test
    void originalTeacherCannotSubstituteThemselves() {
        UpsertRequest self = new UpsertRequest(100L, DATE, "T1");
        assertThatThrownBy(() -> service.assign(self, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cannot cover their own period");
    }

    @Test
    void substituteWithATimetableConflictIsRejected() {
        TimetableEntry conflicting = new TimetableEntry();
        conflicting.setDay(Day.THURSDAY);
        conflicting.setPeriodNumber(3);
        when(timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T2", SCHOOL_ID))
                .thenReturn(List.of(conflicting));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already teaching");
    }

    @Test
    void substituteAlreadyCoveringAnotherClassSamePeriodIsRejected() {
        TeacherSubstitution alreadyCovering = new TeacherSubstitution();
        alreadyCovering.setId(1L);
        alreadyCovering.setSubstituteTeacherId("T2");
        alreadyCovering.setPeriodNumber(3);
        when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of(alreadyCovering));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already teaching");
    }

    @Test
    void duplicateActiveSubstitutionForSamePeriodIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        when(substitutions.findBySchoolIdAndDateAndTimetableEntryIdAndStatus(
                SCHOOL_ID, DATE, 100L, TeacherSubstitutionStatus.ACTIVE)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already has an active substitute");
    }

    @Test
    void originalTeacherMustActuallyBeUnavailable() {
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not unavailable");
    }

    // ─── Change / cancel behavior ────────────────────────────────────────

    @Test
    void changeReplacesTheSubstituteAndNotifiesBothTeachers() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setDate(DATE);
        existing.setTimetableEntryId(100L);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);

        Teacher replacement = new Teacher();
        replacement.setTeacherId("T3");
        replacement.setSchoolId(SCHOOL_ID);
        replacement.setName("Mr Replacement");
        replacement.setStatus(TeacherStatus.ACTIVE);

        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));
        when(teachers.findByTeacherIdAndSchoolId("T3", SCHOOL_ID)).thenReturn(Optional.of(replacement));

        Assignment result = service.change(1L, new ChangeRequest("T3"), http);

        assertThat(result.substituteTeacherId()).isEqualTo("T3");
        // old substitute (cancelled), new substitute (assigned), original teacher (who covers now)
        verify(events, times(3)).publishEvent(any(TeacherSubstitutionNotificationEvent.class));
    }

    @Test
    void changeToTheSameTeacherIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setDate(DATE);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.change(1L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already assigned");
    }

    @Test
    void cancelMarksTheSubstitutionCancelledAndNotifiesTheSubstitute() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setAcademicSessionId(SESSION_ID);
        existing.setDate(DATE);
        existing.setSubstituteTeacherId("T2");
        existing.setStatus(TeacherSubstitutionStatus.ACTIVE);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        Assignment result = service.cancel(1L, http);

        assertThat(result.status()).isEqualTo(TeacherSubstitutionStatus.CANCELLED);
        verify(events, times(1)).publishEvent(any(TeacherSubstitutionNotificationEvent.class));
    }

    @Test
    void cancellingAnAlreadyCancelledSubstitutionIsRejected() {
        TeacherSubstitution existing = new TeacherSubstitution();
        existing.setId(1L);
        existing.setSchoolId(SCHOOL_ID);
        existing.setStatus(TeacherSubstitutionStatus.CANCELLED);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.cancel(1L, http))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already cancelled");
    }

    // ─── Post-commit notification event ─────────────────────────────────

    @Test
    void assignNotifiesTheSubstituteAndTheOriginalTeacherOnce() {
        service.assign(request(), http);
        org.mockito.ArgumentCaptor<TeacherSubstitutionNotificationEvent> captor =
                org.mockito.ArgumentCaptor.forClass(TeacherSubstitutionNotificationEvent.class);
        verify(events, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues()).extracting(TeacherSubstitutionNotificationEvent::recipientTeacherId,
                TeacherSubstitutionNotificationEvent::eventKind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("T2", "ASSIGNED"),
                        org.assertj.core.groups.Tuple.tuple("T1", "ORIGINAL_ASSIGNED"));
    }

    // ─── Teacher's own assignments ("mine") ─────────────────────────────

    @Test
    void teacherSeesTheirOwnCoverClassesForTheDate() {
        TeacherSubstitution mine = new TeacherSubstitution();
        mine.setId(1L);
        mine.setSubstituteTeacherId("T2");
        mine.setPeriodNumber(3);
        when(security.getUsername()).thenReturn("T2");
        when(substitutions.findBySchoolIdAndSubstituteTeacherIdAndDateAndStatusOrderByPeriodNumberAsc(
                SCHOOL_ID, "T2", DATE, TeacherSubstitutionStatus.ACTIVE)).thenReturn(List.of(mine));

        List<Assignment> result = service.mine(DATE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).substituteTeacherId()).isEqualTo("T2");
    }

    // ─── Phase 2 ─────────────────────────────────────────────────────────

    private Teacher teacher(String id, String name) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setSchoolId(SCHOOL_ID);
        t.setName(name);
        t.setStatus(TeacherStatus.ACTIVE);
        return t;
    }

    private TimetableEntry slot(long id, String teacherId, Day day, int period, String cls, String section, String subject) {
        TimetableEntry e = new TimetableEntry();
        e.setId(id);
        e.setSchoolId(SCHOOL_ID);
        e.setAcademicSessionId(SESSION_ID);
        e.setDay(day);
        e.setPeriodNumber(period);
        e.setClassName(cls);
        e.setSectionName(section);
        e.setSubjectName(subject);
        e.setTeacherId(teacherId);
        e.setTeacherName(teacherId);
        return e;
    }

    private TeacherAttendance attendanceOf(String teacherId, String status) {
        TeacherAttendance a = new TeacherAttendance();
        a.setTeacherId(teacherId);
        a.setStatus(status);
        return a;
    }

    private TeacherSubstitution activeCover(long id, String substitute, int period) {
        TeacherSubstitution s = new TeacherSubstitution();
        s.setId(id);
        s.setSchoolId(SCHOOL_ID);
        s.setAcademicSessionId(SESSION_ID);
        s.setDate(DATE);
        s.setTimetableEntryId(100L);
        s.setOriginalTeacherId("T1");
        s.setOriginalTeacherName("Mr Original");
        s.setSubstituteTeacherId(substitute);
        s.setSubstituteTeacherName(substitute);
        s.setPeriodNumber(period);
        s.setAssignedBy("A0");
        s.setStatus(TeacherSubstitutionStatus.ACTIVE);
        return s;
    }

    @Test
    void aSubstituteOnApprovedLeaveIsNeitherSuggestedNorAssignable() {
        TeacherLeave t1 = new TeacherLeave();
        t1.setTeacherId("T1");
        TeacherLeave t2 = new TeacherLeave();
        t2.setTeacherId("T2");
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(t1, t2));

        assertThat(service.freeTeachers(100L, DATE)).isEmpty();
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave or absent");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void anAbsentOrOnLeaveSubstituteIsRejected_alsoOnChange() {
        for (String status : List.of("ABSENT", "ON_LEAVE")) {
            when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of(attendanceOf("T2", status)));
            assertThat(service.freeTeachers(100L, DATE)).as(status).isEmpty();
            assertThatThrownBy(() -> service.assign(request(), http)).as(status)
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave or absent");
        }
        TeacherSubstitution existing = activeCover(1L, "T3", 3);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.change(1L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave or absent");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void pastDatesAreRejectedForAssignAndChange() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-29T06:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("past date");
        TeacherSubstitution existing = activeCover(1L, "T3", 3);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.change(1L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("past date");
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void holidaysAndNonWorkingDaysNeedNoCover() {
        SchoolHoliday holiday = new SchoolHoliday();
        holiday.setName("Dussehra");
        holiday.setAffectsAll(true);
        when(holidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(holiday));

        DayOverview day = service.overview(DATE);
        assertThat(day.closedReason()).contains("Dussehra");
        assertThat(day.periods()).isEmpty();
        assertThatThrownBy(() -> service.assign(request(), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no substitution is needed");

        // Non-working day from school settings (Thursday not in the list).
        when(holidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        SchoolRepository schoolRepository = mock(SchoolRepository.class);
        School school = new School();
        school.setWorkingDays("MONDAY,TUESDAY,WEDNESDAY");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        ReflectionTestUtils.setField(service, "schools", schoolRepository);
        assertThat(service.overview(DATE).closedReason()).contains("Thursdays");
    }

    @Test
    void aClassSpecificHolidayDoesNotCloseTheSchool() {
        SchoolHoliday partial = new SchoolHoliday();
        partial.setAffectsAll(false);
        when(holidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(partial));
        assertThat(service.overview(DATE).closedReason()).isNull();
    }

    @Test
    void changeRequiresTheOriginalTeacherToStillBeUnavailable() {
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        TeacherSubstitution existing = activeCover(1L, "T3", 3);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.change(1L, new ChangeRequest("T2"), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no longer needed");
    }

    @Test
    void aCoverWhoseTeacherIsBackIsShownAsNoLongerNeededAndStaysActive() {
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        TeacherSubstitution cover = activeCover(1L, "T2", 3);
        when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of(cover));

        DayOverview day = service.overview(DATE);
        assertThat(day.periods()).singleElement().satisfies(p -> {
            assertThat(p.state()).isEqualTo("NO_LONGER_NEEDED");
            assertThat(p.assignment().id()).isEqualTo(1L);
            assertThat(p.originalTeacherName()).isEqualTo("Mr Original");
            assertThat(p.className()).isEqualTo("X");
        });
        assertThat(day.noLongerNeeded()).isEqualTo(1);
        assertThat(day.needingSubstitute()).isZero();
        assertThat(day.workload()).singleElement().extracting(WorkloadRow::covers).isEqualTo(1);
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void overviewExplainsWhyAndWhenTheTeacherIsAway() {
        TeacherLeave leave = new TeacherLeave();
        leave.setTeacherId("T1");
        leave.setStartDate(DATE.minusDays(1));
        leave.setEndDate(DATE.plusDays(2));
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(leave));
        UncoveredPeriod p = service.overview(DATE).periods().get(0);
        assertThat(p.state()).isEqualTo("NEEDS_SUBSTITUTE");
        assertThat(p.unavailabilityReason()).isEqualTo("APPROVED_LEAVE");
        assertThat(p.leaveStart()).isEqualTo(DATE.minusDays(1));
        assertThat(p.leaveEnd()).isEqualTo(DATE.plusDays(2));
        assertThat(p.suggested().teacherId()).isEqualTo("T2");

        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of(attendanceOf("T1", "ABSENT")));
        assertThat(service.overview(DATE).periods().get(0).unavailabilityReason()).isEqualTo("ABSENT");
    }

    @Test
    void suggestionsAreRankedBySubjectClassWorkloadBackToBackThenName() {
        // Period 3 of X-A, Maths, is uncovered (entry T1). Candidates:
        //   Zed  — teaches Maths elsewhere                         → SAME_SUBJECT
        //   Amy  — teaches X-A (English)                           → KNOWS_CLASS
        //   Bob  — nothing, covering 1 already today (period 6)    → COVERING_1
        //   Cara — nothing, teaches period 4                       → BACK_TO_BACK
        //   Dan  — nothing                                         → (no reasons)
        //   Abe  — nothing                                         → (no reasons, first by name)
        List<TimetableEntry> all = List.of(entry,
                slot(201L, "Zed", Day.MONDAY, 1, "IX", "B", "Maths"),
                slot(202L, "Amy", Day.MONDAY, 2, "X", "A", "English"),
                slot(203L, "Cara", Day.THURSDAY, 4, "VIII", "A", "Art"));
        when(timetables.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(all);
        when(teachers.findByStatusAndSchoolId(TeacherStatus.ACTIVE, SCHOOL_ID)).thenReturn(List.of(
                teacher("Dan", "Dan"), teacher("Cara", "Cara"), teacher("Bob", "Bob"), teacher("Amy", "Amy"),
                teacher("Zed", "Zed"), teacher("Abe", "Abe")));
        TeacherSubstitution bobCover = activeCover(9L, "Bob", 6);
        bobCover.setTimetableEntryId(999L);
        when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of(bobCover));

        List<FreeTeacher> ranked = service.freeTeachers(100L, DATE);
        assertThat(ranked).extracting(FreeTeacher::teacherId).containsExactly("Zed", "Amy", "Abe", "Dan", "Cara", "Bob");
        assertThat(ranked.get(0).reasons()).containsExactly("SAME_SUBJECT");
        assertThat(ranked.get(1).reasons()).containsExactly("KNOWS_CLASS");
        assertThat(ranked.get(4).reasons()).containsExactly("BACK_TO_BACK");
        assertThat(ranked.get(5).reasons()).containsExactly("COVERING_1");
        // Same input, same order.
        assertThat(service.freeTeachers(100L, DATE)).isEqualTo(ranked);
    }

    @Test
    void fillPreviewNeverProposesOneTeacherForTwoClassesInTheSamePeriod() {
        TimetableEntry other = slot(101L, "T9", Day.THURSDAY, 3, "IX", "A", "Science");
        when(timetables.findByAcademicSessionIdAndSchoolId(SESSION_ID, SCHOOL_ID)).thenReturn(List.of(entry, other));
        TeacherLeave t1 = new TeacherLeave();
        t1.setTeacherId("T1");
        TeacherLeave t9 = new TeacherLeave();
        t9.setTeacherId("T9");
        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of(t1, t9));

        FillPreview preview = service.fillPreview(DATE);
        // Only one free teacher (T2) exists: one period gets them, the other is reported as unfillable.
        assertThat(preview.proposals()).singleElement().extracting(FillProposal::substituteTeacherId).isEqualTo("T2");
        assertThat(preview.unfillable()).hasSize(1);
        verify(substitutions, never()).saveAndFlush(any());
    }

    @Test
    void cancelKeepsWhoAssignedAndRecordsWhoCancelled() {
        TeacherSubstitution existing = activeCover(1L, "T2", 3);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        Assignment result = service.cancel(1L, http);
        assertThat(result.assignedBy()).isEqualTo("A0");
        assertThat(result.cancelledBy()).isEqualTo("A1");
        assertThat(result.cancelledAt()).isNotNull();
    }

    @Test
    void assignStoresTheNoteAndTheReasonSource() {
        Assignment result = service.assign(new UpsertRequest(100L, DATE, "T2", "  Worksheet on the desk  "), http);
        assertThat(result.note()).isEqualTo("Worksheet on the desk");
        assertThat(result.reasonSource()).isEqualTo("LEAVE");

        when(leaves.findApprovedOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(attendance.findBySchoolIdAndDate(eq(SCHOOL_ID), any())).thenReturn(List.of(attendanceOf("T1", "ABSENT")));
        assertThat(service.assign(request(), http).reasonSource()).isEqualTo("ABSENCE");
    }

    @Test
    void bulkReportsEveryItem_neverHidingPartialFailures() {
        TimetableEntry missing = slot(777L, "T1", Day.THURSDAY, 5, "X", "A", "Maths");
        when(timetables.findByIdAndSchoolId(777L, SCHOOL_ID)).thenReturn(Optional.of(missing));
        when(timetables.lockById(777L, SCHOOL_ID)).thenReturn(Optional.of(missing));
        when(teachers.findByTeacherIdAndSchoolId("GHOST", SCHOOL_ID)).thenReturn(Optional.empty());

        BulkResult result = service.assignMany(new BulkRequest(DATE, List.of(
                new BulkItem(100L, "T2", null),
                new BulkItem(100L, "T2", null),
                new BulkItem(777L, "GHOST", null))), http);

        assertThat(result.assigned()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(result.outcomes()).extracting(BulkOutcome::status).containsExactly("ASSIGNED", "FAILED", "FAILED");
    }

    @Test
    void bulkIsDeniedForAnUnpermittedSubAdmin() {
        when(security.getRole()).thenReturn(Role.SUB_ADMIN);
        when(permissions.getPermissionKeysForRole(Role.SUB_ADMIN, SCHOOL_ID)).thenReturn(List.of("TIMETABLE_VIEW"));
        assertThatThrownBy(() -> service.assignMany(new BulkRequest(DATE, List.of(new BulkItem(100L, "T2", null))), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("permission");
    }

    @Test
    void myCoverageListsOnlyTheCallersOwnPeriods() {
        when(security.getUsername()).thenReturn("T1");
        TeacherSubstitution cover = activeCover(1L, "T2", 3);
        when(substitutions.findBySchoolIdAndDateAndStatus(SCHOOL_ID, DATE, TeacherSubstitutionStatus.ACTIVE))
                .thenReturn(List.of(cover));
        when(timetables.findByAcademicSessionIdAndTeacherIdAndSchoolId(SESSION_ID, "T1", SCHOOL_ID))
                .thenReturn(List.of(entry, slot(102L, "T1", Day.MONDAY, 1, "X", "A", "Maths")));

        MyCoverage mine = service.myCoverage(DATE);
        assertThat(mine.unavailable()).isTrue();
        assertThat(mine.unavailabilityReason()).isEqualTo("APPROVED_LEAVE");
        assertThat(mine.periods()).singleElement().satisfies(p -> {
            assertThat(p.covered()).isTrue();
            assertThat(p.substituteTeacherName()).isEqualTo("T2");
        });
        verify(timetables, never()).findByAcademicSessionIdAndTeacherIdAndSchoolId(any(), eq("T2"), any());
    }

    @Test
    void aPastSubstitutionCannotBeRemoved_todaysStillCan() {
        TeacherSubstitution existing = activeCover(1L, "T2", 3);
        when(substitutions.findById(1L)).thenReturn(Optional.of(existing));
        when(substitutions.lockByIdAndSchoolId(1L, SCHOOL_ID)).thenReturn(Optional.of(existing));

        // The day after DATE: the cover is in the past and stays exactly as it was.
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.cancel(1L, http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Past substitutions are read-only");
        assertThat(existing.getStatus()).isEqualTo(TeacherSubstitutionStatus.ACTIVE);
        assertThat(existing.getCancelledBy()).isNull();
        verify(substitutions, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());

        // On DATE itself (today), removal still works.
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-24T06:00:00Z"), ZoneOffset.UTC));
        assertThat(service.cancel(1L, http).status()).isEqualTo(TeacherSubstitutionStatus.CANCELLED);
    }
}
