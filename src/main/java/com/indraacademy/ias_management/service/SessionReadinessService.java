package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationPreviewResult;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.AcademicSessionRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
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
 * Read-only readiness report shown before "Make Current" for a target session (Phase 1: warnings
 * only, never blocks). It reads existing data only: undecided students from the previous
 * (contiguous) session, planned enrollments awaiting activation, class-teacher configuration,
 * timetable entries and generated fees for the target session.
 */
@Service
public class SessionReadinessService {

    private static final Logger log = LoggerFactory.getLogger(SessionReadinessService.class);
    private static final int PENDING_SAMPLE = 50;

    @Autowired private AcademicSessionRepository sessions;
    @Autowired private StudentEnrollmentRepository enrollments;
    @Autowired private StudentRepository students;
    @Autowired private SchoolClassRepository classes;
    @Autowired private SchoolRepository schools;
    @Autowired private SecurityUtil security;
    @Autowired private EntityManager em;
    @Autowired private Clock clock;
    @Autowired(required = false) private ClassTeacherActivationService classTeacherActivation;

    public record PendingStudent(String studentId, String studentName, String className, String sectionName) {}

    public record Readiness(
            Long sessionId, String sessionLabel, boolean current,
            Long previousSessionId, String previousSessionLabel,
            int pendingStudents, List<PendingStudent> pendingSample,
            int targetEnrolled, int plannedEnrollments, int plannedDueNow,
            Integer classTeacherConfigured, Boolean classTeacherIssues,
            int activeClasses, int timetableEntries, int classesWithTimetable,
            int studentsWithFees, int studentsWithoutFees,
            List<String> warnings) {}

    /** Not transactional on purpose: each read runs on its own, so an optional part (e.g. the
     *  class-teacher preview) failing can't fail the whole report. */
    public Readiness readiness(Long sessionId) {
        Long schoolId = security.getSchoolId();
        AcademicSession target = sessions.findByIdAndSchoolId(sessionId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Session not found."));
        AcademicSession previous = sessions.findBySchoolIdOrderByStartDateDesc(schoolId).stream()
                .filter(s -> s.getEndDate().plusDays(1).equals(target.getStartDate()))
                .findFirst().orElse(null);
        School school = schools.findById(schoolId).orElseThrow();
        LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));

        List<StudentEnrollment> targetRows = enrollments
                .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, target.getId());
        Set<String> inTarget = targetRows.stream().filter(e -> e.getStatus() != StudentEnrollmentStatus.CANCELLED)
                .map(StudentEnrollment::getStudentId).collect(Collectors.toSet());
        Set<String> targetEnrolled = targetRows.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.PLANNED || e.getStatus() == StudentEnrollmentStatus.ACTIVE)
                .map(StudentEnrollment::getStudentId).collect(Collectors.toSet());
        List<StudentEnrollment> planned = targetRows.stream()
                .filter(e -> e.getStatus() == StudentEnrollmentStatus.PLANNED).toList();
        int plannedDue = (int) planned.stream().filter(e -> !e.getEffectiveFrom().isAfter(today)).count();

        // Undecided: still open and ACTIVE in the previous session, ACTIVE student, nothing in the target.
        List<PendingStudent> pending = new ArrayList<>();
        if (previous != null) {
            List<StudentEnrollment> open = enrollments
                    .findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(schoolId, previous.getId()).stream()
                    .filter(e -> e.getStatus() == StudentEnrollmentStatus.ACTIVE && e.getEffectiveUntil() == null)
                    .filter(e -> !inTarget.contains(e.getStudentId()))
                    .toList();
            Map<String, Student> byId = open.isEmpty() ? Map.of() : students.findByStudentIdInAndSchoolId(
                    open.stream().map(StudentEnrollment::getStudentId).distinct().toList(), schoolId).stream()
                    .collect(Collectors.toMap(Student::getStudentId, Function.identity()));
            for (StudentEnrollment e : open) {
                Student s = byId.get(e.getStudentId());
                if (s == null || s.getStatus() != StudentStatus.ACTIVE) continue;
                pending.add(new PendingStudent(e.getStudentId(), s.getName(), e.getClassNameSnapshot(), e.getSectionNameSnapshot()));
            }
            pending.sort(Comparator.comparing(PendingStudent::className, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(PendingStudent::studentName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        }

        Integer ctConfigured = null;
        Boolean ctIssues = null;
        if (classTeacherActivation != null) {
            try {
                ActivationPreviewResult ct = classTeacherActivation.previewForSession(target.getId());
                ctConfigured = ct.configuredCount();
                ctIssues = ct.hasIssues();
            } catch (RuntimeException e) {
                log.warn("Class-teacher readiness unavailable for session {}: {}", target.getId(), e.getMessage());
            }
        }

        int activeClasses = classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(schoolId, true).size();
        Object[] timetable = (Object[]) em.createQuery(
                        "SELECT COUNT(t), COUNT(DISTINCT t.classId) FROM TimetableEntry t "
                                + "WHERE t.schoolId = :school AND t.academicSessionId = :session")
                .setParameter("school", schoolId).setParameter("session", target.getId()).getSingleResult();
        int timetableEntries = ((Number) timetable[0]).intValue();
        int classesWithTimetable = ((Number) timetable[1]).intValue();

        Set<String> withFees = new HashSet<>(em.createQuery(
                        "SELECT DISTINCT f.studentId FROM StudentFees f WHERE f.schoolId = :school AND f.academicSessionId = :session",
                        String.class)
                .setParameter("school", schoolId).setParameter("session", target.getId()).getResultList());
        int studentsWithFees = (int) targetEnrolled.stream().filter(withFees::contains).count();
        int studentsWithoutFees = targetEnrolled.size() - studentsWithFees;

        List<String> warnings = new ArrayList<>();
        if (previous == null) {
            warnings.add("No previous session ends the day before " + target.getLabel() + ", so undecided students can't be checked.");
        } else if (!pending.isEmpty()) {
            warnings.add(pending.size() + " student(s) from " + previous.getLabel() + " have no year-end decision yet.");
        }
        if (plannedDue > 0) {
            warnings.add(plannedDue + " planned enrollment(s) are due but not yet activated (the daily activation runs at 4 AM).");
        }
        if (ctConfigured != null && ctConfigured == 0) {
            warnings.add("No class-teacher responsibilities are configured for " + target.getLabel() + ".");
        } else if (Boolean.TRUE.equals(ctIssues)) {
            warnings.add("Some class-teacher responsibilities for " + target.getLabel() + " have issues.");
        }
        if (timetableEntries == 0) {
            warnings.add("No timetable has been set up for " + target.getLabel() + ".");
        } else if (classesWithTimetable < activeClasses) {
            warnings.add((activeClasses - classesWithTimetable) + " active class(es) have no timetable for " + target.getLabel() + ".");
        }
        if (studentsWithoutFees > 0) {
            warnings.add(studentsWithoutFees + " student(s) enrolled for " + target.getLabel() + " have no fees generated yet.");
        }

        return new Readiness(target.getId(), target.getLabel(), target.isCurrent(),
                previous != null ? previous.getId() : null, previous != null ? previous.getLabel() : null,
                pending.size(), pending.stream().limit(PENDING_SAMPLE).toList(),
                targetEnrolled.size(), planned.size(), plannedDue,
                ctConfigured, ctIssues,
                activeClasses, timetableEntries, classesWithTimetable,
                studentsWithFees, studentsWithoutFees,
                List.copyOf(warnings));
    }
}
