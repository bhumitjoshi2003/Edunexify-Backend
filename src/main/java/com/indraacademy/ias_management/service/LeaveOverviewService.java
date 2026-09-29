package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.UncoveredPeriod;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.LeaveRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin "On leave today": students and staff with APPROVED leave covering today (school time),
 * and — for staff — how many of their timetable periods today still have no substitute. Read-only;
 * it never assigns substitutes (that stays on the Teacher Substitution page).
 */
@Service
public class LeaveOverviewService {

    private static final Logger log = LoggerFactory.getLogger(LeaveOverviewService.class);

    @Autowired private LeaveRepository leaveRepository;
    @Autowired private TeacherLeaveRepository teacherLeaveRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private Clock clock;
    @Autowired(required = false) private TeacherSubstitutionService substitutionService;

    public record StudentOnLeave(Long leaveId, String studentId, String studentName, String className,
                                 String sectionName, String reason) {}

    public record StaffOnLeave(Long leaveId, String teacherId, String teacherName, LocalDate startDate,
                               LocalDate endDate, String reason, int periodsToday, int periodsNeedingSubstitute) {}

    public record OnLeaveToday(LocalDate date, List<StudentOnLeave> students, List<StaffOnLeave> staff,
                               int periodsNeedingSubstitute) {}

    public OnLeaveToday onLeaveToday() {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepository.findById(schoolId).orElseThrow(() -> new NoSuchElementException("School not found."));
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));

        List<Leave> studentLeave = leaveRepository.findBySchoolIdAndLeaveDateAndStatusOrderByClassNameAscStudentNameAsc(
                schoolId, today.toString(), LeaveStatus.APPROVED);
        Map<String, Student> studentsById = studentLeave.isEmpty() ? Map.of() : studentRepository
                .findByStudentIdInAndSchoolId(studentLeave.stream().map(Leave::getStudentId).distinct().toList(), schoolId)
                .stream().collect(Collectors.toMap(Student::getStudentId, Function.identity(), (a, b) -> a));
        List<StudentOnLeave> students = studentLeave.stream().map(l -> {
            Student s = studentsById.get(l.getStudentId());
            return new StudentOnLeave(l.getId(), l.getStudentId(), l.getStudentName(), l.getClassName(),
                    s == null ? null : s.getSectionName(), l.getReason());
        }).toList();

        // Periods today whose own teacher is on approved leave, and how many still have no substitute.
        Map<String, int[]> periods = new HashMap<>();
        if (substitutionService != null) {
            try {
                for (UncoveredPeriod p : substitutionService.uncovered(today)) {
                    if ("NO_LONGER_NEEDED".equals(p.state())) continue;
                    int[] counts = periods.computeIfAbsent(p.originalTeacherId(), k -> new int[2]);
                    counts[0]++;
                    if (p.assignment() == null) counts[1]++;
                }
            } catch (RuntimeException e) {
                log.warn("Substitution overview unavailable for school {}: {}", schoolId, e.getMessage());
            }
        }
        List<StaffOnLeave> staff = teacherLeaveRepository.findApprovedOverlapping(schoolId, today, today).stream()
                .sorted(Comparator.comparing(TeacherLeave::getTeacherName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(t -> {
                    int[] counts = periods.getOrDefault(t.getTeacherId(), new int[2]);
                    return new StaffOnLeave(t.getId(), t.getTeacherId(), t.getTeacherName(), t.getStartDate(),
                            t.getEndDate(), t.getReason(), counts[0], counts[1]);
                }).toList();
        int needing = staff.stream().mapToInt(StaffOnLeave::periodsNeedingSubstitute).sum();
        return new OnLeaveToday(today, students, staff, needing);
    }
}
