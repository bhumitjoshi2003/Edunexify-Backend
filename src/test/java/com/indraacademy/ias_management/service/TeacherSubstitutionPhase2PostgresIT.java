package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Teacher Substitution Phase 2 on real PostgreSQL (V87), with real commits (no test transaction)
 * so the row locks, the partial unique index, per-item bulk transactions and after-commit
 * notifications behave as in production. "Today" is Monday 5 Oct 2026, 08:00 Asia/Kolkata; the
 * working date is Tuesday 6 Oct.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TeacherSubstitutionService.class, TimetableSessionAccessService.class,
        TeacherSubstitutionNotificationListener.class, TeacherSubstitutionPhase2PostgresIT.Beans.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class TeacherSubstitutionPhase2PostgresIT {

    static final long SCHOOL = -97301, OTHER_SCHOOL = -97302, SESSION = -97303, OTHER_SESSION = -97304;
    static final String ORIG = "SB-ORIG", ORIG2 = "SB-ORIG2", SUB_A = "SB-A", SUB_B = "SB-B",
            SUB_LEAVE = "SB-LEAVE", SUB_ABSENT = "SB-ABSENT", OTHER_T = "SB-OTHER";
    static final LocalDate TUE = LocalDate.of(2026, 10, 6);
    static final LocalDate HOLIDAY_THU = LocalDate.of(2026, 10, 8);
    static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    @TestConfiguration
    static class Beans {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-05T02:30:00Z"), ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired TeacherSubstitutionService service;
    @Autowired JdbcTemplate jdbc;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean PermissionService permissions;
    @MockBean BusinessNotificationService notifications;

    final HttpServletRequest http = mock(HttpServletRequest.class);
    long e1, e2, e3, otherEntry;

    @BeforeEach
    void fixtures() {
        cleanup();
        when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        as(SCHOOL, "ADMIN", "admin1");
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,working_days) VALUES "
                + "(?,true,CURRENT_TIMESTAMP,'Sub IT','TRIAL','sub-it',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY'),"
                + "(?,true,CURRENT_TIMESTAMP,'Sub Other','TRIAL','sub-other',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY')",
                SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES "
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),"
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        for (String[] t : new String[][]{{ORIG, "Original One"}, {ORIG2, "Original Two"}, {SUB_A, "Anita"},
                {SUB_B, "Bharat"}, {SUB_LEAVE, "On Leave"}, {SUB_ABSENT, "Absent Today"}}) {
            jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, ?, 'ACTIVE')", t[0], SCHOOL, t[1]);
        }
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, 'Other School', 'ACTIVE')", OTHER_T, OTHER_SCHOOL);
        // Tuesday: ORIG teaches X-A Maths P3 and P4; ORIG2 teaches IX-B Science P3.
        e1 = entry(SCHOOL, SESSION, "X", "A", "TUESDAY", 3, "Maths", ORIG, "Original One");
        e3 = entry(SCHOOL, SESSION, "X", "A", "TUESDAY", 4, "Maths", ORIG, "Original One");
        e2 = entry(SCHOOL, SESSION, "IX", "B", "TUESDAY", 3, "Science", ORIG2, "Original Two");
        // Anita teaches Maths (another day) in X-A → SAME_SUBJECT + KNOWS_CLASS.
        entry(SCHOOL, SESSION, "X", "A", "MONDAY", 1, "Maths", SUB_A, "Anita");
        otherEntry = entry(OTHER_SCHOOL, OTHER_SESSION, "X", "A", "TUESDAY", 3, "Maths", OTHER_T, "Other School");
        jdbc.update("INSERT INTO teacher_leave (school_id,teacher_id,teacher_name,start_date,end_date,reason,status,applied_date) VALUES "
                + "(?,?,'Original One',DATE '2026-10-06',DATE '2026-10-07','Medical','APPROVED',CURRENT_TIMESTAMP),"
                + "(?,?,'On Leave',DATE '2026-10-06',DATE '2026-10-06','Family','APPROVED',CURRENT_TIMESTAMP)", SCHOOL, ORIG, SCHOOL, SUB_LEAVE);
        jdbc.update("INSERT INTO teacher_attendance (teacher_id,school_id,date,status,method,marked_by_admin) VALUES (?,?,?,'ABSENT','ADMIN',true),(?,?,?,'ON_LEAVE','ADMIN',true)",
                ORIG2, SCHOOL, TUE, SUB_ABSENT, SCHOOL, TUE);
        jdbc.update("INSERT INTO school_holidays (school_id,name,start_date,end_date,affects_all) VALUES (?,'Festival',?,?,true)",
                SCHOOL, HOLIDAY_THU, HOLIDAY_THU);
        entry(SCHOOL, SESSION, "X", "A", "THURSDAY", 3, "Maths", ORIG, "Original One");
    }

    @AfterEach
    void cleanup() {
        for (String table : List.of("teacher_substitution", "timetable_entry", "teacher_attendance", "teacher_leave", "school_holidays", "teacher")) {
            jdbc.update("DELETE FROM " + table + " WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        }
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
    }

    private long entry(long school, long session, String cls, String section, String day, int period, String subject,
                       String teacherId, String teacherName) {
        return jdbc.queryForObject("INSERT INTO timetable_entry (school_id, academic_session_id, class_name, section_name, day, "
                + "period_number, start_time, end_time, subject_name, teacher_id, teacher_name) "
                + "VALUES (?, ?, ?, ?, ?, ?, '09:00', '09:40', ?, ?, ?) RETURNING id",
                Long.class, school, session, cls, section, day, period, subject, teacherId, teacherName);
    }

    private void as(long school, String role, String user) {
        when(security.getSchoolId()).thenReturn(school);
        when(security.getRole()).thenReturn(role);
        when(security.getUsername()).thenReturn(user);
    }

    private int activeRows(long entryId) {
        return jdbc.queryForObject("SELECT count(*) FROM teacher_substitution WHERE timetable_entry_id = ? AND status = 'ACTIVE'",
                Integer.class, entryId);
    }

    // ── Availability ──

    @Test
    void teachersOnLeaveOrAbsentAreNeverSuggestedOrAssignable() {
        assertThat(service.freeTeachers(e1, TUE)).extracting(FreeTeacher::teacherId)
                .doesNotContain(SUB_LEAVE, SUB_ABSENT, ORIG, ORIG2)
                .containsExactly(SUB_A, SUB_B);
        for (String unavailable : List.of(SUB_LEAVE, SUB_ABSENT, ORIG2)) {
            assertThatThrownBy(() -> service.assign(new UpsertRequest(e1, TUE, unavailable), http)).as(unavailable)
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave or absent");
        }
        assertThat(activeRows(e1)).isZero();

        Assignment a = service.assign(new UpsertRequest(e1, TUE, SUB_B), http);
        assertThatThrownBy(() -> service.change(a.id(), new ChangeRequest(SUB_ABSENT), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("on leave or absent");
    }

    @Test
    void overviewExplainsReasonsAndRanksTheSuggestion() {
        DayOverview day = service.overview(TUE);
        assertThat(day.closedReason()).isNull();
        assertThat(day.needingSubstitute()).isEqualTo(3);
        UncoveredPeriod p = day.periods().stream().filter(x -> x.timetableEntryId() == e1).findFirst().orElseThrow();
        assertThat(p.unavailabilityReason()).isEqualTo("APPROVED_LEAVE");
        assertThat(p.leaveStart()).isEqualTo(TUE);
        assertThat(p.leaveEnd()).isEqualTo(TUE.plusDays(1));
        assertThat(p.suggested().teacherId()).isEqualTo(SUB_A);
        assertThat(p.suggested().reasons()).containsExactly("SAME_SUBJECT", "KNOWS_CLASS");
        assertThat(day.periods().stream().filter(x -> x.timetableEntryId() == e2).findFirst().orElseThrow()
                .unavailabilityReason()).isEqualTo("ABSENT");
    }

    // ── Dates ──

    @Test
    void holidaysNonWorkingDaysAndPastDatesAreRejected() {
        assertThat(service.overview(HOLIDAY_THU).closedReason()).contains("Festival");
        assertThat(service.overview(HOLIDAY_THU).periods()).isEmpty();
        long thursday = jdbc.queryForObject("SELECT id FROM timetable_entry WHERE school_id = ? AND day = 'THURSDAY'", Long.class, SCHOOL);
        assertThatThrownBy(() -> service.assign(new UpsertRequest(thursday, HOLIDAY_THU, SUB_A), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no substitution is needed");
        assertThat(service.overview(SUNDAY).closedReason()).contains("Sundays");
        assertThatThrownBy(() -> service.assign(new UpsertRequest(e1, LocalDate.of(2026, 9, 29), SUB_A), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("past date");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teacher_substitution WHERE school_id = ?", Integer.class, SCHOOL)).isZero();
    }

    // ── Concurrency ──

    @Test
    void twoAdminsCoveringTheSamePeriodAtOnce_exactlyOneWins() throws Exception {
        List<Object> results = race(() -> service.assign(new UpsertRequest(e1, TUE, SUB_A), http),
                () -> service.assign(new UpsertRequest(e1, TUE, SUB_B), http));
        assertThat(results).filteredOn(r -> r instanceof Assignment).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof ResponseStatusException).hasSize(1);
        assertThat(activeRows(e1)).isEqualTo(1);
    }

    @Test
    void oneTeacherCantBeAssignedToTwoClassesInTheSamePeriodAtOnce() throws Exception {
        List<Object> results = race(() -> service.assign(new UpsertRequest(e1, TUE, SUB_B), http),
                () -> service.assign(new UpsertRequest(e2, TUE, SUB_B), http));
        assertThat(results).filteredOn(r -> r instanceof Assignment).hasSize(1);
        assertThat(results).filteredOn(r -> r instanceof ResponseStatusException).singleElement()
                .satisfies(r -> assertThat(((ResponseStatusException) r).getReason()).contains("already teaching"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teacher_substitution WHERE substitute_teacher_id = ? AND status = 'ACTIVE'",
                Integer.class, SUB_B)).isEqualTo(1);
    }

    private List<Object> race(Callable<Object> a, Callable<Object> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try { return call.call(); } catch (ResponseStatusException e) { return e; }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Fill all ──

    @Test
    void fillAllPreviewsThenSavesEachItem_reportingConflictsAndFailures() {
        FillPreview preview = service.fillPreview(TUE);
        assertThat(preview.proposals()).hasSize(3);
        // Never the same teacher twice in period 3.
        assertThat(preview.proposals().stream().filter(p -> p.periodNumber() == 3).map(FillProposal::substituteTeacherId).distinct())
                .hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teacher_substitution WHERE school_id = ?", Integer.class, SCHOOL)).isZero();

        // Meanwhile another admin covers e2 with someone else.
        FillProposal forE2 = preview.proposals().stream().filter(p -> p.timetableEntryId() == e2).findFirst().orElseThrow();
        service.assign(new UpsertRequest(e2, TUE, SUB_A.equals(forE2.substituteTeacherId()) ? SUB_B : SUB_A), http);

        List<BulkItem> items = new ArrayList<>(preview.proposals().stream()
                .map(p -> new BulkItem(p.timetableEntryId(), p.substituteTeacherId(), null)).toList());
        items.add(new BulkItem(otherEntry, SUB_A, null)); // another school's period
        BulkResult result = service.assignMany(new BulkRequest(TUE, items), http);

        assertThat(result.outcomes()).hasSize(4);
        assertThat(result.assigned() + result.conflicts() + result.failed()).isEqualTo(4);
        assertThat(result.outcomes().stream().filter(o -> o.timetableEntryId() == e2).findFirst().orElseThrow().status())
                .isEqualTo("CONFLICT");
        assertThat(result.outcomes().stream().filter(o -> o.timetableEntryId() == otherEntry).findFirst().orElseThrow().status())
                .isEqualTo("FAILED");
        // Whatever was reported assigned is exactly what is saved — no hidden partial state.
        for (BulkOutcome o : result.outcomes()) {
            if ("ASSIGNED".equals(o.status())) assertThat(activeRows(o.timetableEntryId())).isEqualTo(1);
        }
        assertThat(activeRows(e2)).isEqualTo(1);
        assertThat(activeRows(otherEntry)).isZero();
    }

    // ── Stale + cancellation audit ──

    @Test
    void aCoverWhoseTeacherIsBackStaysActiveAsNoLongerNeededUntilRemoved() {
        Assignment a = service.assign(new UpsertRequest(e1, TUE, SUB_A, "Worksheet on desk"), http);
        assertThat(a.reasonSource()).isEqualTo("LEAVE");
        jdbc.update("UPDATE teacher_leave SET status = 'CANCELLED' WHERE teacher_id = ? AND school_id = ?", ORIG, SCHOOL);

        DayOverview day = service.overview(TUE);
        UncoveredPeriod stale = day.periods().stream().filter(p -> p.timetableEntryId() == e1).findFirst().orElseThrow();
        assertThat(stale.state()).isEqualTo("NO_LONGER_NEEDED");
        assertThat(stale.assignment().substituteTeacherId()).isEqualTo(SUB_A);
        assertThat(stale.assignment().note()).isEqualTo("Worksheet on desk");
        assertThat(day.noLongerNeeded()).isEqualTo(1);
        assertThat(activeRows(e1)).isEqualTo(1);

        assertThatThrownBy(() -> service.change(a.id(), new ChangeRequest(SUB_B), http))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no longer needed");

        as(SCHOOL, "ADMIN", "admin2");
        service.cancel(a.id(), http);
        var row = jdbc.queryForMap("SELECT status, assigned_by, cancelled_by, cancelled_at FROM teacher_substitution WHERE id = ?", a.id());
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("assigned_by")).isEqualTo("admin1");
        assertThat(row.get("cancelled_by")).isEqualTo("admin2");
        assertThat(row.get("cancelled_at")).isNotNull();
    }

    // ── Tenancy ──

    @Test
    void anotherSchoolCantSeeChangeOrRemoveThisSchoolsCovers() {
        Assignment a = service.assign(new UpsertRequest(e1, TUE, SUB_A), http);
        as(OTHER_SCHOOL, "ADMIN", "other-admin");
        assertThatThrownBy(() -> service.change(a.id(), new ChangeRequest(OTHER_T), http)).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.cancel(a.id(), http)).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.assign(new UpsertRequest(e3, TUE, OTHER_T), http)).hasMessageContaining("not found");
        assertThatThrownBy(() -> service.freeTeachers(e3, TUE)).hasMessageContaining("not found");
        assertThat(service.overview(TUE).periods()).isEmpty();
        assertThat(activeRows(e1)).isEqualTo(1);
    }

    // ── Teacher views ──

    @Test
    void myCoverageIsOnlyTheCallersOwnPeriods() {
        service.assign(new UpsertRequest(e1, TUE, SUB_A), http);
        service.assign(new UpsertRequest(e2, TUE, SUB_B), http);

        as(SCHOOL, "TEACHER", ORIG);
        MyCoverage mine = service.myCoverage(TUE);
        assertThat(mine.unavailable()).isTrue();
        assertThat(mine.unavailabilityReason()).isEqualTo("APPROVED_LEAVE");
        assertThat(mine.periods()).extracting(MyCoveragePeriod::timetableEntryId).containsExactly(e1, e3);
        assertThat(mine.periods()).extracting(MyCoveragePeriod::covered).containsExactly(true, false);
        assertThat(mine.periods().get(0).substituteTeacherName()).isEqualTo("Anita");

        as(SCHOOL, "TEACHER", SUB_A);
        MyCoverage available = service.myCoverage(TUE);
        assertThat(available.unavailable()).isFalse();
        assertThat(available.periods()).isEmpty();
        assertThat(service.mine(TUE)).extracting(Assignment::timetableEntryId).containsExactly(e1);
    }

    // ── Notifications ──

    @Test
    void bothTeachersAreNotifiedAfterCommitWithStableDedupKeys() {
        Assignment a = service.assign(new UpsertRequest(e1, TUE, SUB_A, "Chapter 4"), http);
        String prefix = "teacher-substitution:" + a.id() + ":" + a.revision() + ":";
        verify(notifications).direct(eq(SCHOOL), eq(SUB_A), eq(NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED), any(),
                anyString(), argThat(m -> m.contains("Class X A") && m.contains("Maths") && m.contains("Period 3")
                        && m.contains("09:00") && m.contains("Original One") && m.contains("Chapter 4")),
                anyString(), anyString(), anyString(), anyString(), eq(prefix + "ASSIGNED:" + SUB_A), any());
        verify(notifications).direct(eq(SCHOOL), eq(ORIG), eq(NotificationEventCode.TEACHER_SUBSTITUTION_ASSIGNED), any(),
                anyString(), argThat(m -> m.contains("Anita")), anyString(), anyString(), anyString(), anyString(),
                eq(prefix + "ORIGINAL_ASSIGNED:" + ORIG), any());

        // A rejected assignment notifies nobody.
        clearInvocations(notifications);
        assertThatThrownBy(() -> service.assign(new UpsertRequest(e1, TUE, SUB_B), http)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(notifications);
    }
}
