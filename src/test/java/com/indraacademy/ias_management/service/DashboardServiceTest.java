package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.DashboardStatsDto;
import com.indraacademy.ias_management.dto.FeeTrendDto;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.Refund;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.repository.AttendanceRow;
import com.indraacademy.ias_management.dto.ClassStatsDto;
import com.indraacademy.ias_management.dto.AttendanceTrendDto;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Financial-reliability phase: dashboard "fees collected" figures used to sum successful
 * Payment rows with no regard for refunds at all — a fully refunded month still counted as
 * revenue. Net collected = gross captured payments − refunds, both computed on their own
 * period (a refund is a new event in its own period, not a retroactive rewrite of the month
 * the original payment landed in).
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private StudentRepository studentRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private StudentFeesRepository studentFeesRepository;
    @Mock private StudentAttendanceRepository studentAttendanceRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private LeaveRepository leaveRepository;
    @Mock private com.indraacademy.ias_management.repository.TeacherLeaveRepository teacherLeaveRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private SecurityUtil securityUtil;

    private DashboardService service;

    private static final Long SCHOOL_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new DashboardService();
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "paymentRepository", paymentRepository);
        ReflectionTestUtils.setField(service, "refundRepository", refundRepository);
        ReflectionTestUtils.setField(service, "studentFeesRepository", studentFeesRepository);
        ReflectionTestUtils.setField(service, "studentAttendanceRepository", studentAttendanceRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "leaveRepository", leaveRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "teacherLeaveRepository", teacherLeaveRepository);

        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School school = new School();
        school.setId(SCHOOL_ID);
        school.setAcademicYearStartMonth(4);
        lenient().when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        lenient().when(studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, SCHOOL_ID)).thenReturn(10L);
        lenient().when(teacherRepository.countBySchoolIdAndStatus(SCHOOL_ID, TeacherStatus.ACTIVE)).thenReturn(2L);
        lenient().when(studentFeesRepository.countDistinctOverdueStudents(any(), any(), anyInt())).thenReturn(0L);
        lenient().when(studentAttendanceRepository.findSchoolRowsOnDate(any(), any())).thenReturn(List.of());
        lenient().when(leaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, SCHOOL_ID)).thenReturn(0L);
    }

    @Test
    void pendingLeaves_isStudentPlusTeacherPending_withABreakdown() {
        when(leaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, SCHOOL_ID)).thenReturn(4L);
        when(teacherLeaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, SCHOOL_ID)).thenReturn(2L);

        DashboardStatsDto stats = service.getStats();

        assertThat(stats.getPendingLeaves()).isEqualTo(6L);
        assertThat(stats.getPendingStudentLeaves()).isEqualTo(4L);
        assertThat(stats.getPendingTeacherLeaves()).isEqualTo(2L);
    }

    @Test
    void feesCollectedThisMonth_noRefunds_equalsGrossPayments() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(500_000L);
        when(refundRepository.sumAmountPaiseBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(0L);

        DashboardStatsDto stats = service.getStats();

        assertThat(stats.getFeesCollectedThisMonth()).isEqualTo(500_000L);
    }

    @Test
    void feesCollectedThisMonth_afterARefund_isReducedByExactlyTheRefundedAmount() {
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(500_000L);
        // A ₹1000 (100000 paise) refund processed this same month.
        when(refundRepository.sumAmountPaiseBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(100_000L);

        DashboardStatsDto stats = service.getStats();

        assertThat(stats.getFeesCollectedThisMonth()).isEqualTo(400_000L); // 500000 - 100000
    }

    @Test
    void feesCollectedThisMonth_nonZeroPlatformFeeAlreadyNettedByTheQuery_isPassedThroughAsIs() {
        // sumAmountCollectedBySchoolIdAndMonthAndYear is a @Query method (no H2/Testcontainers
        // in this project to exercise the real SQL), so this documents getStats()'s own
        // contract: whatever the query returns is treated as already-correctly-netted paise,
        // with only the refund subtraction applied on top — getStats() itself must never
        // re-touch platformFee. The query's own correctness (no double-scaling) is covered by
        // sumAmountCollectedQuery_platformFeeIsNotScaledByOneHundred below.
        LocalDate today = LocalDate.now();
        // 588700 (gross) - 8700 (platformFee, paise, unscaled) = 580000 — what the fixed query
        // now actually returns for the E2E Razorpay payment.
        when(paymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(580_000L);
        when(refundRepository.sumAmountPaiseBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(0L);

        DashboardStatsDto stats = service.getStats();

        assertThat(stats.getFeesCollectedThisMonth()).isEqualTo(580_000L);
    }

    @Test
    void sumAmountCollectedQuery_platformFeeIsNotScaledByOneHundred() throws NoSuchMethodException {
        // Regression guard against literally reintroducing the *100 bug into the JPQL string
        // itself: platformFee is paise on every path that sets it non-zero, so this query must
        // subtract it as-is. The platform-wide SUPER_ADMIN equivalent never had this bug and
        // is the reference for what "correct" looks like.
        java.lang.reflect.Method method = PaymentRepository.class.getMethod(
                "sumAmountCollectedBySchoolIdAndMonthAndYear", Long.class, int.class, int.class);
        org.springframework.data.jpa.repository.Query query =
                method.getAnnotation(org.springframework.data.jpa.repository.Query.class);

        assertThat(query).isNotNull();
        assertThat(query.value())
                .contains("p.amountPaid - p.platformFee")
                .doesNotContain("* 100")
                .doesNotContain("*100");
    }

    @Test
    void feesCollectedThisMonth_doesNotChangeOtherDashboardMetrics() {
        // Explicit check that this phase only touched the revenue figure — every other
        // stat is computed exactly as it was before (from its own, independent mocks above).
        LocalDate today = LocalDate.now();
        when(paymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(0L);
        when(refundRepository.sumAmountPaiseBySchoolIdAndMonthAndYear(SCHOOL_ID, today.getMonthValue(), today.getYear()))
                .thenReturn(0L);

        DashboardStatsDto stats = service.getStats();

        assertThat(stats.getTotalStudents()).isEqualTo(10L);
        assertThat(stats.getTotalTeachers()).isEqualTo(2L);
        assertThat(stats.getOverdueStudents()).isEqualTo(0L);
        assertThat(stats.getPendingLeaves()).isEqualTo(0L);
    }

    // ─── Attendance V2: explicit rows only, one shared formula ────────────────

    private static AttendanceRow row(String studentId, long classId, LocalDate date, AttendanceStatus status) {
        return new AttendanceRow(1L, SCHOOL_ID, studentId, date, status, classId, null);
    }

    @Test
    void todayAttendance_isPresentRowsOverAllSubmittedRows() {
        LocalDate today = LocalDate.now();
        when(studentAttendanceRepository.findSchoolRowsOnDate(eq(SCHOOL_ID), any())).thenReturn(List.of(
                row("S1", 10, today, AttendanceStatus.PRESENT), row("S2", 10, today, AttendanceStatus.PRESENT),
                row("S3", 10, today, AttendanceStatus.PRESENT), row("S4", 10, today, AttendanceStatus.ABSENT)));

        assertThat(service.getStats().getTodayAttendanceRate()).isEqualTo(75.0);
    }

    @Test
    void todayAttendance_isZeroUntilSomethingIsSubmitted() {
        assertThat(service.getStats().getTodayAttendanceRate()).isEqualTo(0.0);
    }

    @Test
    void classStats_useOneMonthQueryPerSchoolAndCountEachClassesSubmittedDays() {
        LocalDate d1 = LocalDate.now().withDayOfMonth(1);
        SchoolClass ten = new SchoolClass();
        ten.setId(10L);
        ten.setName("10");
        when(schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(SCHOOL_ID)).thenReturn(List.of(ten));
        when(studentRepository.findDistinctActiveClassNamesBySchoolId(SCHOOL_ID)).thenReturn(List.of("10"));
        when(studentRepository.findByClassNameAndStatusAndSchoolId("10", StudentStatus.ACTIVE, SCHOOL_ID))
                .thenReturn(List.of(new Student(), new Student()));
        when(studentAttendanceRepository.findSchoolRows(eq(SCHOOL_ID), isNull(), any(), any())).thenReturn(List.of(
                row("S1", 10, d1, AttendanceStatus.PRESENT), row("S2", 10, d1, AttendanceStatus.ABSENT),
                row("S1", 10, d1.plusDays(1), AttendanceStatus.PRESENT), row("S2", 10, d1.plusDays(1), AttendanceStatus.PRESENT),
                row("X9", 99, d1, AttendanceStatus.ABSENT)));   // another class never leaks in

        List<ClassStatsDto> stats = service.getClassStats();

        assertThat(stats).singleElement().satisfies(c -> {
            assertThat(c.getAttendanceRate()).isEqualTo(75.0);
            assertThat(c.getWorkingDays()).isEqualTo(2);
            assertThat(c.getStudentCount()).isEqualTo(2);
        });
        verify(studentAttendanceRepository, times(1)).findSchoolRows(eq(SCHOOL_ID), isNull(), any(), any());
    }

    @Test
    void monthlyTrend_bucketsTheClassesRowsByMonth() {
        LocalDate today = LocalDate.now();
        SchoolClass ten = new SchoolClass();
        ten.setId(10L);
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(Optional.of(ten));
        when(studentAttendanceRepository.findSchoolRows(eq(SCHOOL_ID), eq(10L), any(), any())).thenReturn(List.of(
                row("S1", 10, today, AttendanceStatus.PRESENT), row("S2", 10, today, AttendanceStatus.ABSENT)));

        List<AttendanceTrendDto> trend = service.getAttendanceTrend("10", "monthly");

        assertThat(trend).hasSize(6);
        assertThat(trend.get(5).getAttendanceRate()).isEqualTo(50.0);
        assertThat(trend.get(0).getAttendanceRate()).isEqualTo(0.0);
    }

    @Test
    void feeTrend_netsOutRefundsProcessedInTheSameCalendarMonthAsThePayments() {
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();

        Payment payment = new Payment();
        payment.setPaymentDate(today.atStartOfDay());
        payment.setAmountPaid(200_000);
        payment.setPlatformFee(0);
        when(paymentRepository.findBySchoolIdAndPaymentDateAfter(SCHOOL_ID, since)).thenReturn(List.of(payment));

        Refund refund = new Refund();
        refund.setCreatedAt(today.atStartOfDay());
        refund.setAmountPaise(50_000L);
        when(refundRepository.findBySchoolIdAndCreatedAtAfter(SCHOOL_ID, since)).thenReturn(List.of(refund));

        List<FeeTrendDto> trend = service.getFeeTrend();

        FeeTrendDto currentMonth = trend.get(trend.size() - 1); // last entry is the current month
        assertThat(currentMonth.getAmount()).isEqualTo(150_000L); // 200000 - 50000
    }

    @Test
    void feeTrend_nonZeroPlatformFee_isSubtractedAtFaceValue_notScaledByOneHundred() {
        // Regression test for the platform-fee unit bug found during Razorpay E2E testing:
        // platformFee is paise on every path that sets it non-zero (PaymentController.
        // createOrder computes it in paise before persisting), so it must be subtracted
        // as-is. A stray *100 here silently overcorrects by ~100x — invisible on every
        // manual payment (platformFee always 0) and only surfacing on a real Razorpay
        // payment, exactly the scenario reproduced here: ₹5,887 captured (588700 paise)
        // with an ₹87 platform fee (8700 paise) must net to ₹58.00 (580000 paise), not
        // ₹588700 − 870000 (a nonsensical negative from the old *100 bug).
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();

        Payment payment = new Payment();
        payment.setPaymentDate(today.atStartOfDay());
        payment.setAmountPaid(588_700);
        payment.setPlatformFee(8_700);
        when(paymentRepository.findBySchoolIdAndPaymentDateAfter(SCHOOL_ID, since)).thenReturn(List.of(payment));
        when(refundRepository.findBySchoolIdAndCreatedAtAfter(SCHOOL_ID, since)).thenReturn(List.of());

        List<FeeTrendDto> trend = service.getFeeTrend();

        FeeTrendDto currentMonth = trend.get(trend.size() - 1);
        assertThat(currentMonth.getAmount()).isEqualTo(580_000L); // 588700 - 8700, not 588700 - 870000
    }

    @Test
    void feeTrend_monthWithOnlyARefund_showsAsNegative_notSilentlyFlooredToZero() {
        // A month where a refund exceeds that same month's gross payments (e.g. a payment
        // made in an earlier month gets refunded this month) — the net figure must reflect
        // that honestly, not be floored/hidden.
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();
        when(paymentRepository.findBySchoolIdAndPaymentDateAfter(SCHOOL_ID, since)).thenReturn(List.of());

        Refund refund = new Refund();
        refund.setCreatedAt(today.atStartOfDay());
        refund.setAmountPaise(75_000L);
        when(refundRepository.findBySchoolIdAndCreatedAtAfter(SCHOOL_ID, since)).thenReturn(List.of(refund));

        List<FeeTrendDto> trend = service.getFeeTrend();

        FeeTrendDto currentMonth = trend.get(trend.size() - 1);
        assertThat(currentMonth.getAmount()).isEqualTo(-75_000L);
    }
}
