package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceTrendDto;
import com.indraacademy.ias_management.dto.ClassStatsDto;
import com.indraacademy.ias_management.dto.DashboardStatsDto;
import com.indraacademy.ias_management.dto.FeeTrendDto;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.entity.SchoolClass;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);
    private static final DateTimeFormatter TREND_FMT = DateTimeFormatter.ofPattern("MMM yyyy");

    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private StudentFeesRepository studentFeesRepository;
    @Autowired private StudentAttendanceRepository studentAttendanceRepository;
    @Autowired private SchoolClassRepository schoolClassRepository;
    @Autowired private LeaveRepository leaveRepository;
    @Autowired private com.indraacademy.ias_management.repository.TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SecurityUtil securityUtil;

    // ─── /api/dashboard/stats ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElse(null);
        LocalDate today = LocalDate.now(com.indraacademy.ias_management.util.SchoolTimeUtil.zoneId(school));
        int academicStartMonth = school != null ? school.getAcademicYearStartMonth() : 4;

        long totalStudents = studentRepository.countByStatusAndSchoolId(StudentStatus.ACTIVE, schoolId);
        long totalTeachers = teacherRepository.countBySchoolIdAndStatus(
                schoolId, com.indraacademy.ias_management.entity.TeacherStatus.ACTIVE);

        // Net fees collected this month: gross captured payments (amountPaid − platformFee)
        // minus refunds actually processed this month — each on its own period, matching how
        // real accounting reports revenue (a refund is a new event in its own period, not a
        // retroactive rewrite of the month the original payment landed in).
        long grossCollectedThisMonth = paymentRepository
                .sumAmountCollectedBySchoolIdAndMonthAndYear(schoolId, today.getMonthValue(), today.getYear());
        long refundedThisMonth = refundRepository
                .sumAmountPaiseBySchoolIdAndMonthAndYear(schoolId, today.getMonthValue(), today.getYear());
        long feesCollectedThisMonth = grossCollectedThisMonth - refundedThisMonth;

        // Overdue: distinct active students with any unpaid fee up to the current academic month
        String currentSession = currentSession(today, academicStartMonth);
        int currentAcademicMonth = calendarToAcademicMonth(today.getMonthValue(), academicStartMonth);
        long overdueStudents = studentFeesRepository
                .countDistinctOverdueStudents(schoolId, currentSession, currentAcademicMonth);

        // Today's attendance rate (Attendance V2): PRESENT rows / all rows submitted today, school-wide,
        // in the school's own timezone. 0 until some class has submitted attendance today.
        double todayAttendanceRate = rate(studentAttendanceRepository.findSchoolRowsOnDate(schoolId, today));

        // Pending leaves: leave applications with PENDING status
        long pendingStudentLeaves = leaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, schoolId);
        long pendingTeacherLeaves = teacherLeaveRepository.countByStatusAndSchoolId(LeaveStatus.PENDING, schoolId);
        // Two separate tables, so nothing is counted twice.
        long pendingLeaves = pendingStudentLeaves + pendingTeacherLeaves;

        DashboardStatsDto dto = new DashboardStatsDto();
        dto.setTotalStudents(totalStudents);
        dto.setTotalTeachers(totalTeachers);
        dto.setFeesCollectedThisMonth(feesCollectedThisMonth);
        dto.setOverdueStudents(overdueStudents);
        dto.setTodayAttendanceRate(todayAttendanceRate);
        dto.setPendingLeaves(pendingLeaves);
        dto.setPendingStudentLeaves(pendingStudentLeaves);
        dto.setPendingTeacherLeaves(pendingTeacherLeaves);

        log.info("Dashboard stats computed: students={}, teachers={}, fees={}, overdue={}, attendance={}%, leaves={}",
                totalStudents, totalTeachers, feesCollectedThisMonth,
                overdueStudents, todayAttendanceRate, pendingLeaves);
        return dto;
    }

    // ─── /api/dashboard/fee-trend ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FeeTrendDto> getFeeTrend() {
        // Fetch all payments AND refunds in the last 6 calendar months — net revenue per
        // month is gross captured payments minus refunds processed that same month.
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusMonths(5).withDayOfMonth(1).atStartOfDay();
        Long schoolId = securityUtil.getSchoolId();
        List<Payment> recent = paymentRepository.findBySchoolIdAndPaymentDateAfter(schoolId, since);
        List<com.indraacademy.ias_management.entity.Refund> recentRefunds =
                refundRepository.findBySchoolIdAndCreatedAtAfter(schoolId, since);

        // Group by "YYYY-MM" key, sum (amountPaid − platformFee) — both already paise on
        // every path that sets platformFee non-zero; see the matching fix/comment on
        // PaymentRepository.sumAmountCollectedBySchoolIdAndMonthAndYear.
        Map<String, Long> sumByMonth = new TreeMap<>(); // TreeMap keeps insertion order after we populate
        for (Payment p : recent) {
            if (p.getPaymentDate() == null) continue;
            String key = p.getPaymentDate().getYear() + "-"
                    + String.format("%02d", p.getPaymentDate().getMonthValue());
            long schoolCollection = (OnlinePaymentPricingCalculator.PRICING_VERSION.equals(p.getPricingVersion())
                    || "MANUAL".equals(p.getPricingVersion())) && p.getSchoolLiabilityPrincipalPaise() != null
                    ? p.getSchoolLiabilityPrincipalPaise()
                    : p.getAmountPaid() - (long) p.getPlatformFee();
            sumByMonth.merge(key, schoolCollection, Long::sum);
        }
        Map<String, Long> refundedByMonth = new TreeMap<>();
        for (com.indraacademy.ias_management.entity.Refund r : recentRefunds) {
            if (r.getCreatedAt() == null) continue;
            String key = r.getCreatedAt().getYear() + "-"
                    + String.format("%02d", r.getCreatedAt().getMonthValue());
            refundedByMonth.merge(key, r.getAmountPaise(), Long::sum);
        }

        // Build ordered result covering all 6 months (fill 0 for months with no data)
        List<FeeTrendDto> result = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            LocalDate month = today.minusMonths(i).withDayOfMonth(1);
            String key   = month.getYear() + "-" + String.format("%02d", month.getMonthValue());
            String label = month.format(TREND_FMT);   // e.g. "Nov 2025"
            long net = sumByMonth.getOrDefault(key, 0L) - refundedByMonth.getOrDefault(key, 0L);
            result.add(new FeeTrendDto(label, net));
        }
        return result;
    }

    // ─── /api/dashboard/class-stats ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ClassStatsDto> getClassStats() {
        Long schoolId = securityUtil.getSchoolId();
        LocalDate today = schoolToday(schoolId);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        // One query for the whole month, grouped per class in memory (no per-class/per-day queries).
        Map<Long, List<AttendanceRow>> byClass = studentAttendanceRepository
                .findSchoolRows(schoolId, null, monthStart, monthEnd).stream()
                .collect(Collectors.groupingBy(AttendanceRow::classId));
        Map<String, Long> classIds = new HashMap<>();
        for (SchoolClass c : schoolClassRepository.findBySchoolIdOrderByDisplayOrderAsc(schoolId)) classIds.put(c.getName(), c.getId());

        List<ClassStatsDto> result = new ArrayList<>();
        for (String cls : studentRepository.findDistinctActiveClassNamesBySchoolId(schoolId)) {
            long studentCount = studentRepository.findByClassNameAndStatusAndSchoolId(cls, StudentStatus.ACTIVE, schoolId).size();
            if (studentCount == 0) continue;
            List<AttendanceRow> classRows = byClass.getOrDefault(classIds.get(cls), List.of());
            long workingDays = classRows.stream().map(AttendanceRow::date).distinct().count();
            result.add(new ClassStatsDto(cls, studentCount, rate(classRows), workingDays));
        }

        result.sort(Comparator.comparing(dto -> {
            try {
                return String.format("%02d", Integer.parseInt(dto.getClassName()));
            } catch (NumberFormatException e) {
                return dto.getClassName();
            }
        }));

        return result;
    }


    @Transactional(readOnly = true)
    public List<AttendanceTrendDto> getAttendanceTrend(String className, String mode) {
        Long schoolId = securityUtil.getSchoolId();
        LocalDate today = schoolToday(schoolId);
        Long classId = schoolClassRepository.findBySchoolIdAndName(schoolId, className).map(SchoolClass::getId).orElse(null);
        List<AttendanceTrendDto> result = new ArrayList<>();
        boolean weekly = "weekly".equalsIgnoreCase(mode);

        LocalDate rangeStart = weekly ? today.with(DayOfWeek.MONDAY).minusWeeks(7) : today.minusMonths(5).withDayOfMonth(1);
        List<AttendanceRow> classRows = classId == null ? List.of()
                : studentAttendanceRepository.findSchoolRows(schoolId, classId, rangeStart, today);

        if (weekly) {
            DateTimeFormatter weekLabelFmt = DateTimeFormatter.ofPattern("d MMM");
            for (int i = 0; i < 8; i++) {
                LocalDate wStart = rangeStart.plusWeeks(i);
                LocalDate wEnd = wStart.plusDays(6);
                result.add(new AttendanceTrendDto(wStart.format(weekLabelFmt) + "–" + wEnd.format(weekLabelFmt),
                        rate(between(classRows, wStart, wEnd))));
            }
        } else {
            for (int i = 5; i >= 0; i--) {
                LocalDate monthDate = today.minusMonths(i);
                LocalDate monthStart = monthDate.withDayOfMonth(1);
                LocalDate monthEnd = monthDate.withDayOfMonth(monthDate.lengthOfMonth());
                result.add(new AttendanceTrendDto(monthDate.format(TREND_FMT), rate(between(classRows, monthStart, monthEnd))));
            }
        }

        log.info("Attendance trend computed: class={}, mode={}, points={}", className, mode, result.size());
        return result;
    }

    /** Attendance V2 group rate: PRESENT rows / all submitted rows (the shared AttendanceMath formula). */
    private static double rate(List<AttendanceRow> rows) {
        long present = rows.stream().filter(r -> r.status() == AttendanceStatus.PRESENT).count();
        return AttendanceMath.percentage(present, rows.size());
    }

    private static List<AttendanceRow> between(List<AttendanceRow> rows, LocalDate from, LocalDate to) {
        return rows.stream().filter(r -> !r.date().isBefore(from) && !r.date().isAfter(to)).toList();
    }

    private LocalDate schoolToday(Long schoolId) {
        School school = schoolRepository.findById(schoolId).orElse(null);
        return LocalDate.now(com.indraacademy.ias_management.util.SchoolTimeUtil.zoneId(school));
    }

    /**
     * Returns the academic session for the given date, using the school's configured start month.
     * e.g. startMonth=4 (April): April 2026 → "2026-2027", Jan 2026 → "2025-2026"
     *      startMonth=7 (July):  July 2026  → "2026-2027", May 2026 → "2025-2026"
     */
    private String currentSession(LocalDate date, int startMonth) {
        int year = date.getYear();
        return date.getMonthValue() >= startMonth
                ? year + "-" + (year + 1)
                : (year - 1) + "-" + year;
    }

    /** Calendar month (1=Jan…12=Dec) → academic month (1 = startMonth). */
    private int calendarToAcademicMonth(int calendarMonth, int startMonth) {
        return ((calendarMonth - startMonth + 12) % 12) + 1;
    }
}
