package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.StudentLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherLeaveApplyRequest;
import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.UncoveredPeriod;
import com.indraacademy.ias_management.entity.Leave;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.exception.InvalidLeaveStatusTransitionException;
import com.indraacademy.ias_management.notification.NotificationAudience;
import com.indraacademy.ias_management.notification.NotificationAudienceType;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Leave Management Phase 1 on real PostgreSQL (V86): the apply DTO can't set status / id / class,
 * server-side date rules, one active leave per student per day, exact own-leave lookup, decision
 * rules and history, cancellation that keeps history, approved-only attendance / fee-waiver data,
 * approver notifications, the pending count, teacher overlap and "On leave today".
 * "Today" is Monday 5 Oct 2026, 08:00 in Asia/Kolkata.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LeaveService.class, TeacherLeaveService.class, LeaveOverviewService.class, TeacherClassScopeService.class,
        StudentEnrollmentService.class, TeacherAttendanceScheduleService.class, LeavePhase1PostgresIT.Beans.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class LeavePhase1PostgresIT {

    static final long SCHOOL = -94101, OTHER_SCHOOL = -94102, SESSION = -94103, OTHER_SESSION = -94104;
    static final long CLASS_8 = -94110, OTHER_CLASS = -94111, SECTION_A = -94120;
    static final String S1 = "LV-S1", S10 = "LV-S10", S2 = "LV-S2", T1 = "LV-T1", CT = "LV-CT";
    static final LocalDate TUE = LocalDate.of(2026, 10, 6);

    @TestConfiguration
    static class Beans {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-05T02:30:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired LeaveService leaves;
    @Autowired TeacherLeaveService teacherLeaves;
    @Autowired LeaveOverviewService overview;
    @Autowired LeaveRepository leaveRepository;
    @Autowired TeacherLeaveRepository teacherLeaveRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean BusinessNotificationService notifications;
    @MockBean TeacherSubstitutionService substitutions;

    final HttpServletRequest http = mock(HttpServletRequest.class);

    @BeforeEach
    void fixtures() {
        when(http.getRemoteAddr()).thenReturn("127.0.0.1");
        as(SCHOOL, "ADMIN", "admin1");
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,working_days) VALUES "
                + "(?,true,CURRENT_TIMESTAMP,'Leave IT','TRIAL','leave-it',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY'),"
                + "(?,true,CURRENT_TIMESTAMP,'Leave Other','TRIAL','leave-other',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY')",
                SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES "
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),"
                + "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'8',true,false),(?,?,'8',true,false)",
                CLASS_8, SCHOOL, OTHER_CLASS, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true)", SECTION_A, SCHOOL, CLASS_8);
        for (String id : List.of(S1, S10, S2)) {
            jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) "
                    + "VALUES (?,?,?,'ACTIVE',?,'8',?,'A',DATE '2026-04-01')", id, SCHOOL, "Student " + id, CLASS_8, SECTION_A);
        }
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, 'Leave Teacher', 'ACTIVE')", T1, SCHOOL);
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status, class_teacher, class_teacher_section_id) "
                + "VALUES (?, ?, 'Class Teacher', 'ACTIVE', '8', ?)", CT, SCHOOL, SECTION_A);
        jdbc.update("INSERT INTO school_holidays (school_id,name,start_date,end_date,affects_all) VALUES (?,'Festival',DATE '2026-10-08',DATE '2026-10-08',true)", SCHOOL);
    }

    private void as(long school, String role, String user) {
        when(security.getSchoolId()).thenReturn(school);
        when(security.getRole()).thenReturn(role);
        when(security.getUsername()).thenReturn(user);
    }

    private Leave apply(String studentId, LocalDate date) {
        return leaves.applyLeave(studentId, new StudentLeaveApplyRequest(date, "Family function"), http);
    }

    // ── 1. The apply request can't carry anything but the date and the reason ──

    @Test
    void tamperedStatusIdAndClassAreIgnoredAndNoOtherRowIsTouched() throws Exception {
        // Another school's leave row the attacker targets by id.
        Long victimId = jdbc.queryForObject("INSERT INTO leaves (applied_date,class_name,leave_date,reason,school_id,status,student_id,student_name) "
                + "VALUES (CURRENT_TIMESTAMP,'X','2026-10-06','victim',?,'PENDING','OTHER-1','Victim') RETURNING id", Long.class, OTHER_SCHOOL);
        String body = "{\"id\":" + victimId + ",\"status\":\"APPROVED\",\"studentId\":\"" + S2 + "\",\"studentName\":\"Hacker\","
                + "\"className\":\"12\",\"classId\":999,\"schoolId\":" + OTHER_SCHOOL + ",\"decidedBy\":\"me\","
                + "\"leaveDate\":\"2026-10-06\",\"reason\":\"Family function\"}";
        StudentLeaveApplyRequest req = new ObjectMapper().findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(body, StudentLeaveApplyRequest.class);
        as(SCHOOL, "STUDENT", S1);

        Leave saved = leaves.applyLeave(S1, req, http);

        assertThat(saved.getId()).isNotEqualTo(victimId);
        assertThat(saved.getStatus()).isEqualTo(LeaveStatus.PENDING);
        assertThat(saved.getStudentId()).isEqualTo(S1);
        assertThat(saved.getStudentName()).isEqualTo("Student " + S1);
        assertThat(saved.getClassName()).isEqualTo("8");
        assertThat(saved.getClassId()).isEqualTo(CLASS_8);
        assertThat(saved.getSchoolId()).isEqualTo(SCHOOL);
        assertThat(saved.getDecidedBy()).isNull();
        em.flush();
        Map<String, Object> victim = jdbc.queryForMap("SELECT * FROM leaves WHERE id=?", victimId);
        assertThat(victim).containsEntry("school_id", OTHER_SCHOOL).containsEntry("status", "PENDING").containsEntry("reason", "victim");
    }

    // ── 2. Server-side date rules ──

    @Test
    void dateRulesAreEnforcedOnTheServer() {
        as(SCHOOL, "STUDENT", S1);
        assertThatThrownBy(() -> apply(S1, LocalDate.of(2026, 10, 2))).hasMessageContaining("past date");
        assertThatThrownBy(() -> apply(S1, LocalDate.of(2026, 10, 5))).hasMessageContaining("before 6:00 AM");
        assertThatThrownBy(() -> apply(S1, LocalDate.of(2026, 10, 11))).hasMessageContaining("closed on Sundays");
        assertThatThrownBy(() -> apply(S1, LocalDate.of(2026, 10, 8))).hasMessageContaining("school holiday");
        assertThatThrownBy(() -> apply(S1, LocalDate.of(2027, 4, 5))).hasMessageContaining("not inside any academic session");
        assertThatThrownBy(() -> leaves.applyLeave(S1, new StudentLeaveApplyRequest(TUE, "  "), http)).hasMessageContaining("reason");
        assertThat(apply(S1, TUE).getLeaveDate()).isEqualTo("2026-10-06");
    }

    // ── 3. Duplicates ──

    @Test
    void oneActiveLeavePerStudentPerDay_cancelledOrRejectedDoNotBlock() {
        as(SCHOOL, "STUDENT", S1);
        Leave first = apply(S1, TUE);
        assertThatThrownBy(() -> apply(S1, TUE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("already been applied");
        leaves.cancelForStudentDate(S1, "2026-10-06", null, http);
        Leave second = apply(S1, TUE);
        as(SCHOOL, "ADMIN", "admin1");
        leaves.decide(second.getId(), LeaveStatus.REJECTED, "Exam day", http);
        as(SCHOOL, "STUDENT", S1);
        Leave third = apply(S1, TUE);
        assertThat(List.of(first.getId(), second.getId(), third.getId())).doesNotHaveDuplicates();
        em.flush();
        assertThat(jdbc.queryForList("SELECT status FROM leaves WHERE student_id=? ORDER BY id", String.class, S1))
                .containsExactly("CANCELLED", "REJECTED", "PENDING");
    }

    @Test
    void theDatabaseRejectsASecondActiveLeaveForTheSameDay() {
        apply(S1, TUE);
        em.flush();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO leaves (applied_date,class_name,leave_date,reason,school_id,status,student_id,student_name) "
                + "VALUES (CURRENT_TIMESTAMP,'8','2026-10-06','dup',?,'APPROVED',?,'x')", SCHOOL, S1))
                .hasMessageContaining("uq_leaves_active_student_date");
    }

    // ── 4. Own leave is an exact student-ID match ──

    @Test
    void ownLeaveLookupIsExact_S1DoesNotSeeS10() {
        apply(S1, TUE);
        apply(S10, TUE);
        em.flush();
        assertThat(leaves.getLeavesByStudentId(S1, PageRequest.of(0, 20)).getContent())
                .extracting(Leave::getStudentId).containsExactly(S1);
        // Staff search keeps its intended partial match.
        assertThat(leaves.getLeavesFiltered(null, "LV-S1", null, null, PageRequest.of(0, 20)).getContent())
                .extracting(Leave::getStudentId).containsExactlyInAnyOrder(S1, S10);
    }

    // ── 5 & 6. Decisions: only from PENDING, with history; reversal is explicit ──

    @Test
    void decisionsRecordWhoWhenWhy_andNeverGoBackToPending() {
        Leave leave = apply(S1, TUE);
        assertThatThrownBy(() -> leaves.decide(leave.getId(), LeaveStatus.PENDING, null, http)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaves.decide(leave.getId(), LeaveStatus.CANCELLED, null, http)).isInstanceOf(IllegalArgumentException.class);

        Leave rejected = leaves.decide(leave.getId(), LeaveStatus.REJECTED, "Exam day", http);
        assertThat(rejected.getDecidedBy()).isEqualTo("admin1");
        assertThat(rejected.getDecidedAt()).isNotNull();
        assertThat(rejected.getDecisionReason()).isEqualTo("Exam day");
        assertThatThrownBy(() -> leaves.decide(leave.getId(), LeaveStatus.APPROVED, null, http))
                .isInstanceOf(InvalidLeaveStatusTransitionException.class).hasMessageContaining("Change decision");

        Leave reversed = leaves.reverse(leave.getId(), "Certificate received", http);
        assertThat(reversed.getStatus()).isEqualTo(LeaveStatus.APPROVED);
        assertThat(reversed.getDecisionReason()).isEqualTo("Certificate received");
    }

    // ── 7. Cancellation keeps history ──

    @Test
    void cancellationKeepsTheRow_studentsCantCancelApproved_adminNeedsAReason() {
        as(SCHOOL, "PARENT", "P1");
        Leave pending = apply(S1, TUE);
        Leave cancelled = leaves.cancelForStudentDate(S1, "2026-10-06", "Plans changed", http);
        assertThat(cancelled.getId()).isEqualTo(pending.getId());
        assertThat(cancelled.getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo("P1");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancellationReason()).isEqualTo("Plans changed");
        assertThat(cancelled.getAppliedDate()).isNotNull();

        as(SCHOOL, "STUDENT", S2);
        Leave approved = apply(S2, TUE);
        as(SCHOOL, "ADMIN", "admin1");
        leaves.decide(approved.getId(), LeaveStatus.APPROVED, null, http);
        as(SCHOOL, "STUDENT", S2);
        assertThatThrownBy(() -> leaves.cancelForStudentDate(S2, "2026-10-06", null, http)).hasMessageContaining("can't be cancelled");
        as(SCHOOL, "ADMIN", "admin1");
        assertThatThrownBy(() -> leaves.cancelById(approved.getId(), null, http)).hasMessageContaining("reason is required");
        assertThat(leaves.cancelById(approved.getId(), "Entered by mistake", http).getStatus()).isEqualTo(LeaveStatus.CANCELLED);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leaves WHERE school_id=?", Integer.class, SCHOOL)).isEqualTo(2);

        as(OTHER_SCHOOL, "ADMIN", "other-admin");
        assertThatThrownBy(() -> leaves.cancelById(pending.getId(), "x", http)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> leaves.decide(approved.getId(), LeaveStatus.REJECTED, null, http)).isInstanceOf(SecurityException.class);
    }

    // ── 9 & 10. Attendance indicator and absence-charge waiver: approved leave only ──

    @Test
    void onlyApprovedLeaveFeedsTheAttendanceIndicatorAndTheFeeWaiver() {
        Leave approved = apply(S1, TUE);
        Leave pending = apply(S2, TUE);
        Leave rejected = apply(S10, TUE);
        leaves.decide(approved.getId(), LeaveStatus.APPROVED, null, http);
        leaves.decide(rejected.getId(), LeaveStatus.REJECTED, null, http);
        em.flush();
        // The exact queries AttendanceService.approvedLeaveKeys (indicator) and
        // approvedLeaveKeysForStudent (AbsenceChargeService waiver) use.
        assertThat(keys(leaveRepository.findApprovedLeaveDays(SCHOOL, "2026-10-01", "2026-10-31"))).containsExactly(S1 + "|2026-10-06");
        assertThat(leaveRepository.findApprovedLeaveDaysForStudent(SCHOOL, S2, "2026-10-01", "2026-10-31")).isEmpty();
        assertThat(leaves.getLeavesByDateAndClass("2026-10-06", "8")).containsExactly(S1);   // no longer all statuses

        // Reversal or cancellation removes the waiver deterministically; nothing is deleted.
        leaves.reverse(approved.getId(), "Not genuine", http);
        em.flush();
        assertThat(leaveRepository.findApprovedLeaveDaysForStudent(SCHOOL, S1, "2026-10-01", "2026-10-31")).isEmpty();
        leaves.reverse(approved.getId(), "Genuine after all", http);
        em.flush();
        assertThat(leaveRepository.findApprovedLeaveDaysForStudent(SCHOOL, S1, "2026-10-01", "2026-10-31")).hasSize(1);
        leaves.cancelById(approved.getId(), "Withdrawn", http);
        em.flush();
        assertThat(leaveRepository.findApprovedLeaveDays(SCHOOL, "2026-10-01", "2026-10-31")).isEmpty();
        assertThat(pending.getStatus()).isEqualTo(LeaveStatus.PENDING);
    }

    private static List<String> keys(List<Object[]> rows) {
        return rows.stream().map(r -> r[0] + "|" + r[1]).collect(Collectors.toList());
    }

    // ── 11. Approver notifications ──

    @Test
    void newRequestsNotifyTheClassTeacherOrTheAdmins() {
        Leave leave = apply(S1, TUE);
        verify(notifications).direct(eq(SCHOOL), eq(CT), eq(NotificationEventCode.LEAVE_SUBMITTED), any(), anyString(),
                contains(S1), eq("Leave"), eq(String.valueOf(leave.getId())), eq("/dashboard/view-leaves"), anyString(),
                eq("student-leave:" + leave.getId() + ":submitted:teacher:" + CT), anySet());
        verify(notifications, never()).publish(eq(SCHOOL), any(), any(), anyString(), anyString(), any(), eq("Leave"), any(), any(), any(), any(), any());

        jdbc.update("UPDATE teacher SET class_teacher = NULL WHERE teacher_id = ?", CT);
        Leave second = apply(S2, TUE);
        verify(notifications).publish(eq(SCHOOL), eq(NotificationEventCode.LEAVE_SUBMITTED), any(), anyString(), anyString(),
                eq(new NotificationAudience(NotificationAudienceType.ROLE, "ADMIN")), eq("Leave"), eq(String.valueOf(second.getId())),
                any(), any(), eq("student-leave:" + second.getId() + ":submitted:admins"), anySet());

        as(SCHOOL, "TEACHER", T1);
        var teacherLeave = teacherLeaves.applyLeave(teacherRequest(TUE, TUE.plusDays(1)), http);
        verify(notifications).publish(eq(SCHOOL), eq(NotificationEventCode.LEAVE_SUBMITTED), any(), anyString(), contains("Leave Teacher"),
                eq(new NotificationAudience(NotificationAudienceType.ROLE, "ADMIN")), eq("TeacherLeave"), eq(String.valueOf(teacherLeave.getId())),
                eq("/dashboard/teacher-leave-requests"), any(), eq("teacher-leave:" + teacherLeave.getId() + ":submitted:admins"), anySet());
    }

    // ── 8. Teacher overlap ──

    @Test
    void overlappingTeacherLeaveIsRejectedUnlessTheOtherIsRejectedOrCancelled() {
        as(SCHOOL, "TEACHER", T1);
        var first = teacherLeaves.applyLeave(teacherRequest(TUE, LocalDate.of(2026, 10, 9)), http);
        assertThatThrownBy(() -> teacherLeaves.applyLeave(teacherRequest(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)), http))
                .hasMessageContaining("overlapping");
        teacherLeaves.cancelLeave(first.getId(), http);
        var second = teacherLeaves.applyLeave(teacherRequest(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)), http);
        as(SCHOOL, "ADMIN", "admin1");
        teacherLeaves.updateStatus(second.getId(), LeaveStatus.REJECTED, "Inspection week", http);
        as(SCHOOL, "TEACHER", T1);
        var third = teacherLeaves.applyLeave(teacherRequest(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12)), http);
        assertThat(third.getStatus()).isEqualTo(LeaveStatus.PENDING);
        em.flush();
        assertThat(jdbc.queryForList("SELECT status FROM teacher_leave WHERE teacher_id=? ORDER BY id", String.class, T1))
                .containsExactly("CANCELLED", "REJECTED", "PENDING");
        assertThat(jdbc.queryForObject("SELECT decision_reason FROM teacher_leave WHERE id=?", String.class, second.getId())).isEqualTo("Inspection week");

        // Reversing the rejected one back to APPROVED would now overlap the pending one.
        as(SCHOOL, "ADMIN", "admin1");
        assertThatThrownBy(() -> teacherLeaves.reverse(second.getId(), "Changed mind", http)).hasMessageContaining("overlapping");
    }

    // ── 12. Pending count (two tables, no double counting) ──

    @Test
    void pendingCountsCoverStudentAndTeacherLeave() {
        apply(S1, TUE);
        apply(S2, TUE);
        as(SCHOOL, "TEACHER", T1);
        teacherLeaves.applyLeave(teacherRequest(TUE, TUE), http);
        em.flush();
        assertThat(leaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, SCHOOL)
                + teacherLeaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, SCHOOL)).isEqualTo(3);
    }

    // ── 13 & 14. On leave today, with substitution needs ──

    @Test
    void onLeaveTodayListsApprovedStudentsAndStaff_withPeriodsNeedingASubstitute() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        jdbc.update("INSERT INTO leaves (applied_date,class_name,leave_date,reason,school_id,status,student_id,student_name) VALUES "
                + "(CURRENT_TIMESTAMP,'8','2026-10-05','Fever',?,'APPROVED',?,'Student LV-S1'),"
                + "(CURRENT_TIMESTAMP,'8','2026-10-05','Pending',?,'PENDING',?,'Student LV-S2')", SCHOOL, S1, SCHOOL, S2);
        jdbc.update("INSERT INTO teacher_leave (school_id,teacher_id,teacher_name,start_date,end_date,reason,status,applied_date) VALUES "
                + "(?,?,'Leave Teacher',DATE '2026-10-05',DATE '2026-10-07','Medical','APPROVED',CURRENT_TIMESTAMP)", SCHOOL, T1);
        when(substitutions.uncovered(today)).thenReturn(List.of(
                period(T1, null), period(T1, null),
                period(T1, new com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.Assignment(1L, 0, today, 2L, T1, "Leave Teacher",
                        CT, "Class Teacher", "8", "A", "Math", 3, null, null, null, null, null, null)),
                period("SOMEONE-ABSENT", null)));

        LeaveOverviewService.OnLeaveToday result = overview.onLeaveToday();

        assertThat(result.date()).isEqualTo(today);
        assertThat(result.students()).singleElement().satisfies(s -> {
            assertThat(s.studentId()).isEqualTo(S1);
            assertThat(s.className()).isEqualTo("8");
            assertThat(s.sectionName()).isEqualTo("A");
        });
        assertThat(result.staff()).singleElement().satisfies(t -> {
            assertThat(t.teacherId()).isEqualTo(T1);
            assertThat(t.reason()).isEqualTo("Medical");
            assertThat(t.periodsToday()).isEqualTo(3);
            assertThat(t.periodsNeedingSubstitute()).isEqualTo(2);
        });
        assertThat(result.periodsNeedingSubstitute()).isEqualTo(2);
        verify(substitutions, never()).assign(any(), any());
    }

    private static UncoveredPeriod period(String teacherId,
                                          com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.Assignment assignment) {
        return new UncoveredPeriod(1L, teacherId, teacherId, "8", "A", "Math", 1, "09:00", "09:40", assignment, List.of());
    }

    private static TeacherLeaveApplyRequest teacherRequest(LocalDate start, LocalDate end) {
        TeacherLeaveApplyRequest r = new TeacherLeaveApplyRequest();
        r.setStartDate(start);
        r.setEndDate(end);
        r.setReason("Personal work");
        return r;
    }
}
