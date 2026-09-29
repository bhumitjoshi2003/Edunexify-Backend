package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.StudentEnrollmentService;
import com.indraacademy.ias_management.service.StudentYearEndDecision;
import com.indraacademy.ias_management.service.StudentYearEndService;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class StudentStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(StudentStatusScheduler.class);

    private final StudentRepository studentRepository;
    private final SchoolRepository schoolRepository;
    private final StudentEnrollmentService enrollmentService;
    private final StudentYearEndService yearEndService;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    public StudentStatusScheduler(StudentRepository studentRepository, SchoolRepository schoolRepository,
                                  StudentEnrollmentService enrollmentService,
                                  StudentYearEndService yearEndService,
                                  StudentEnrollmentRepository enrollmentRepository, Clock clock) {
        this.studentRepository = studentRepository;
        this.schoolRepository = schoolRepository;
        this.enrollmentService = enrollmentService;
        this.yearEndService = yearEndService;
        this.enrollmentRepository = enrollmentRepository;
        this.clock = clock;
    }

    /**
     * Guards against concurrent execution within the same JVM instance.
     * Other application instances may run concurrently. Canonical per-student
     * locking makes duplicate attempts idempotent without a distributed lock.
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

//    @Scheduled(cron = "0 26 14 19 11 *")
    // Runs every day at 4:00 AM IST
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Kolkata")
    public void updateStudentStatuses() {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("StudentStatusScheduler is already running — skipping this trigger.");
            return;
        }
        try {
            Map<StudentEnrollmentService.ScheduledActivationOutcome,Integer> outcomes =
                    new EnumMap<>(StudentEnrollmentService.ScheduledActivationOutcome.class);
            int invalidSession=0, invalidMembership=0, conflicts=0, failures=0;
            int graduationFinalized=0, graduationAlreadyFinalized=0, graduationInvalid=0;
            int yearEndExitsFinalized=0, yearEndExitsInvalid=0;
            for (School school : schoolRepository.findAll()) {
                if (!school.isActive()) continue;
                LocalDate today = LocalDate.now(clock.withZone(SchoolTimeUtil.zoneId(school)));
                Set<String> candidateIds = new LinkedHashSet<>(
                        enrollmentService.findEligiblePlannedStudentIds(school.getId(), today));
                // Compatibility audit only: include due UPCOMING rows with no enrollment so
                // they are reported as NO_PLANNED_ENROLLMENT, never status-updated or backfilled.
                studentRepository.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                                school.getId(), StudentStatus.UPCOMING, today)
                        .stream().map(Student::getStudentId).forEach(candidateIds::add);
                Map<StudentEnrollmentService.ScheduledActivationOutcome,Integer> schoolOutcomes =
                        new EnumMap<>(StudentEnrollmentService.ScheduledActivationOutcome.class);
                for (String studentId : candidateIds) {
                    try {
                        var result=enrollmentService.activateEligiblePlannedEnrollment(
                                school.getId(),studentId,today);
                        outcomes.merge(result.outcome(),1,Integer::sum);
                        schoolOutcomes.merge(result.outcome(),1,Integer::sum);
                        if (result.outcome()==StudentEnrollmentService.ScheduledActivationOutcome.EXPIRED_TARGET_SESSION) {
                            log.warn("Scheduled activation reported: schoolId={}, studentId={}, enrollmentId={}, outcome=EXPIRED_TARGET_SESSION — "
                                            + "the target session ended before this PLANNED enrollment was ever activated.",
                                    school.getId(),studentId,result.enrollmentId());
                        } else if (result.outcome()==StudentEnrollmentService.ScheduledActivationOutcome.NOT_ELIGIBLE) {
                            log.warn("Scheduled activation reported: schoolId={}, studentId={}, outcome=NOT_ELIGIBLE — "
                                            + "student status is neither UPCOMING nor continuing ACTIVE.",
                                    school.getId(),studentId);
                        }
                    } catch (Exception e) {
                        String message=e.getMessage()==null?"":e.getMessage();
                        if (message.toLowerCase().contains("session")) invalidSession++;
                        else if (message.contains("Class")||message.contains("Section")) invalidMembership++;
                        else if (message.toLowerCase().contains("conflict")) conflicts++;
                        else failures++;
                        log.warn("Scheduled enrollment activation skipped: schoolId={}, studentId={}, category={}, error={}",
                                school.getId(),studentId,category(message),message);
                    }
                }
                int noPlannedForSchool = schoolOutcomes.getOrDefault(
                        StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT, 0);
                if (noPlannedForSchool > 0) {
                    log.info("StudentStatusScheduler per-school outcome: schoolId={}, noPlannedEnrollment={}",
                            school.getId(), noPlannedForSchool);
                    if (!enrollmentRepository.existsBySchoolId(school.getId())) {
                        log.error("Production bootstrap check failed: schoolId={} has {} candidate student(s) " +
                                        "requiring enrollment-driven activation but ZERO student_enrollment rows " +
                                        "exist for this school. Automatic status activation and graduation are " +
                                        "no-ops for this school until StudentEnrollment backfill runs — invoke " +
                                        "POST /api/maintenance/student-enrollments/backfill-active-schools " +
                                        "(SUPER_ADMIN) or backfill-current-state for this tenant.",
                                school.getId(), noPlannedForSchool);
                    }
                }
                for (var graduation : enrollmentService.findDueGraduationEnrollments(school.getId(), today)) {
                    try {
                        var result = yearEndService.finalizeGraduation(school.getId(), graduation.getStudentId(),
                                graduation.getAcademicSessionId(), graduation.getId(),
                                new StudentYearEndDecision.AuditContext("SYSTEM", "SYSTEM", "SCHEDULER"));
                        switch (result.outcome()) {
                            case PASSED_OUT -> graduationFinalized++;
                            case ALREADY_APPLIED -> graduationAlreadyFinalized++;
                            default -> graduationInvalid++;
                        }
                    } catch (Exception e) {
                        failures++;
                        log.warn("Scheduled graduation skipped: schoolId={}, studentId={}, enrollmentId={}, type={}",
                                school.getId(), graduation.getStudentId(), graduation.getId(),
                                e.getClass().getSimpleName());
                    }
                }
                // Year-end TRANSFER recorded before the session ended, now effective.
                for (var exit : enrollmentService.findDueYearEndExitEnrollments(school.getId(), today)) {
                    try {
                        var result = yearEndService.finalizeYearEndExit(school.getId(), exit.getStudentId(),
                                exit.getAcademicSessionId(), exit.getId(),
                                new StudentYearEndDecision.AuditContext("SYSTEM", "SYSTEM", "SCHEDULER"));
                        switch (result.outcome()) {
                            case TRANSFERRED -> yearEndExitsFinalized++;
                            case ALREADY_APPLIED -> { }
                            default -> yearEndExitsInvalid++;
                        }
                    } catch (Exception e) {
                        failures++;
                        log.warn("Scheduled year-end exit skipped: schoolId={}, studentId={}, enrollmentId={}, type={}",
                                school.getId(), exit.getStudentId(), exit.getId(), e.getClass().getSimpleName());
                    }
                }
            }
            if (yearEndExitsFinalized + yearEndExitsInvalid > 0) {
                log.info("StudentStatusScheduler year-end exits: finalized={}, invalid={}", yearEndExitsFinalized, yearEndExitsInvalid);
            }
            log.info("StudentStatusScheduler completed: admissionActivated={}, continuingActivated={}, alreadyActive={}, noPlanned={}, expiredTargetSession={}, notEligible={}, graduationFinalized={}, graduationAlreadyFinalized={}, graduationInvalid={}, invalidSession={}, invalidMembership={}, conflict={}, failure={}",
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED,0),
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.CONTINUING_ACTIVATED,0),
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.ALREADY_ACTIVE,0),
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT,0),
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.EXPIRED_TARGET_SESSION,0),
                    outcomes.getOrDefault(StudentEnrollmentService.ScheduledActivationOutcome.NOT_ELIGIBLE,0),
                    graduationFinalized,graduationAlreadyFinalized,graduationInvalid,
                    invalidSession,invalidMembership,conflicts,failures);
        } finally {
            isRunning.set(false);
        }
    }

    private String category(String message) {
        if (message.toLowerCase().contains("session")) return "INVALID_SESSION";
        if (message.contains("Class")||message.contains("Section")) return "INVALID_MEMBERSHIP";
        if (message.toLowerCase().contains("conflict")) return "CONFLICT";
        return "FAILURE";
    }
}
