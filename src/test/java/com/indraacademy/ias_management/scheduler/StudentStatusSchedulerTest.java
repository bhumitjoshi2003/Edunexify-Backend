package com.indraacademy.ias_management.scheduler;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentEnrollmentRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.StudentEnrollmentService;
import com.indraacademy.ias_management.service.StudentYearEndDecision;
import com.indraacademy.ias_management.service.StudentYearEndService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StudentStatusSchedulerTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger schedulerLogger;

    @BeforeEach
    void captureLogs() {
        schedulerLogger = (Logger) LoggerFactory.getLogger(StudentStatusScheduler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        schedulerLogger.addAppender(logAppender);
    }

    @AfterEach
    void releaseLogs() {
        schedulerLogger.detachAppender(logAppender);
    }
    @Test void tenantLocalCandidatesActivateIndependentlyAndBadStudentDoesNotStopBatch(){
        StudentRepository students=mock(StudentRepository.class);
        SchoolRepository schools=mock(SchoolRepository.class);
        StudentEnrollmentService enrollments=mock(StudentEnrollmentService.class);
        StudentYearEndService yearEnd=mock(StudentYearEndService.class);
        StudentEnrollmentRepository enrollmentRepository=mock(StudentEnrollmentRepository.class);
        Clock clock=Clock.fixed(Instant.parse("2026-09-06T00:30:00Z"),ZoneOffset.UTC);
        StudentStatusScheduler scheduler=new StudentStatusScheduler(students,schools,enrollments,yearEnd,enrollmentRepository,clock);
        School india=school(1L,"Asia/Kolkata"), losAngeles=school(2L,"America/Los_Angeles");
        Student bad=student("BAD",1L), good=student("GOOD",1L);
        when(schools.findAll()).thenReturn(List.of(india,losAngeles));
        when(enrollments.findEligiblePlannedStudentIds(1L,LocalDate.of(2026,9,6))).thenReturn(List.of("AUTH"));
        when(enrollments.findEligiblePlannedStudentIds(2L,LocalDate.of(2026,9,5))).thenReturn(List.of());
        when(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                1L,StudentStatus.UPCOMING,LocalDate.of(2026,9,6))).thenReturn(List.of(bad,good));
        when(enrollments.activateEligiblePlannedEnrollment(1L,"BAD",LocalDate.of(2026,9,6)))
                .thenThrow(new IllegalStateException("Academic session invalid"));
        when(enrollments.activateEligiblePlannedEnrollment(1L,"GOOD",LocalDate.of(2026,9,6)))
                .thenReturn(new StudentEnrollmentService.ScheduledActivation(
                        StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED,10L));
        when(enrollments.activateEligiblePlannedEnrollment(1L,"AUTH",LocalDate.of(2026,9,6)))
                .thenReturn(new StudentEnrollmentService.ScheduledActivation(
                        StudentEnrollmentService.ScheduledActivationOutcome.ACTIVATED,11L));

        scheduler.updateStudentStatuses();

        verify(enrollments).activateEligiblePlannedEnrollment(1L,"BAD",LocalDate.of(2026,9,6));
        verify(enrollments).activateEligiblePlannedEnrollment(1L,"GOOD",LocalDate.of(2026,9,6));
        verify(enrollments).activateEligiblePlannedEnrollment(1L,"AUTH",LocalDate.of(2026,9,6));
        verify(enrollments).findEligiblePlannedStudentIds(1L,LocalDate.of(2026,9,6));
        verify(enrollments).findEligiblePlannedStudentIds(2L,LocalDate.of(2026,9,5));
        verify(enrollments).findDueGraduationEnrollments(1L,LocalDate.of(2026,9,6));
        verify(enrollments).findDueGraduationEnrollments(2L,LocalDate.of(2026,9,5));
        verify(enrollments).findDueYearEndExitEnrollments(1L,LocalDate.of(2026,9,6));
        verify(enrollments).findDueYearEndExitEnrollments(2L,LocalDate.of(2026,9,5));
        verify(students).findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                2L,StudentStatus.UPCOMING,LocalDate.of(2026,9,5));
        verifyNoMoreInteractions(enrollments);
    }

    @Test void activeStudentsAreNeverSelectedForDowngrade(){
        StudentRepository students=mock(StudentRepository.class);
        SchoolRepository schools=mock(SchoolRepository.class);
        StudentEnrollmentService enrollments=mock(StudentEnrollmentService.class);
        StudentYearEndService yearEnd=mock(StudentYearEndService.class);
        StudentEnrollmentRepository enrollmentRepository=mock(StudentEnrollmentRepository.class);
        School school=school(1L,"Asia/Kolkata"); when(schools.findAll()).thenReturn(List.of(school));
        new StudentStatusScheduler(students,schools,enrollments,yearEnd,enrollmentRepository,Clock.systemUTC()).updateStudentStatuses();
        verify(students).findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                eq(1L),eq(StudentStatus.UPCOMING),any(LocalDate.class));
        verifyNoMoreInteractions(students);
    }

    @Test void graduationFinalizationRunsPerCandidateAndOneFailureDoesNotBlockAnother(){
        StudentRepository students=mock(StudentRepository.class);
        SchoolRepository schools=mock(SchoolRepository.class);
        StudentEnrollmentService enrollments=mock(StudentEnrollmentService.class);
        StudentYearEndService yearEnd=mock(StudentYearEndService.class);
        StudentEnrollmentRepository enrollmentRepository=mock(StudentEnrollmentRepository.class);
        Clock clock=Clock.fixed(Instant.parse("2026-09-06T00:30:00Z"),ZoneOffset.UTC);
        StudentStatusScheduler scheduler=new StudentStatusScheduler(students,schools,enrollments,yearEnd,enrollmentRepository,clock);
        School india=school(1L,"Asia/Kolkata");
        when(schools.findAll()).thenReturn(List.of(india));
        when(enrollments.findEligiblePlannedStudentIds(1L,LocalDate.of(2026,9,6))).thenReturn(List.of());
        when(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                1L,StudentStatus.UPCOMING,LocalDate.of(2026,9,6))).thenReturn(List.of());

        StudentEnrollment dueBad=enrollment(1L,"BADGRAD",100L,900L);
        StudentEnrollment dueOk=enrollment(1L,"OKGRAD",101L,901L);
        StudentEnrollment dueAlready=enrollment(1L,"ALREADYGRAD",102L,902L);
        when(enrollments.findDueGraduationEnrollments(1L,LocalDate.of(2026,9,6)))
                .thenReturn(List.of(dueBad,dueOk,dueAlready));
        when(yearEnd.finalizeGraduation(eq(1L),eq("BADGRAD"),eq(100L),eq(900L),any()))
                .thenThrow(new IllegalStateException("boom"));
        when(yearEnd.finalizeGraduation(eq(1L),eq("OKGRAD"),eq(101L),eq(901L),any()))
                .thenReturn(new StudentYearEndDecision.Result(
                        StudentYearEndDecision.Outcome.PASSED_OUT,"ok",901L,null,null,false));
        when(yearEnd.finalizeGraduation(eq(1L),eq("ALREADYGRAD"),eq(102L),eq(902L),any()))
                .thenReturn(new StudentYearEndDecision.Result(
                        StudentYearEndDecision.Outcome.ALREADY_APPLIED,"already",902L,null,null,false));

        scheduler.updateStudentStatuses();

        // The bad candidate's exception must not prevent the other two from being processed.
        verify(yearEnd).finalizeGraduation(eq(1L),eq("BADGRAD"),eq(100L),eq(900L),any());
        verify(yearEnd).finalizeGraduation(eq(1L),eq("OKGRAD"),eq(101L),eq(901L),any());
        verify(yearEnd).finalizeGraduation(eq(1L),eq("ALREADYGRAD"),eq(102L),eq(902L),any());
    }

    @Test void logsBootstrapMissingErrorWhenSchoolHasNoPlannedEnrollmentAndZeroEnrollmentRows(){
        StudentRepository students=mock(StudentRepository.class);
        SchoolRepository schools=mock(SchoolRepository.class);
        StudentEnrollmentService enrollments=mock(StudentEnrollmentService.class);
        StudentYearEndService yearEnd=mock(StudentYearEndService.class);
        StudentEnrollmentRepository enrollmentRepository=mock(StudentEnrollmentRepository.class);
        Clock clock=Clock.fixed(Instant.parse("2026-09-06T00:30:00Z"),ZoneOffset.UTC);
        StudentStatusScheduler scheduler=new StudentStatusScheduler(students,schools,enrollments,yearEnd,enrollmentRepository,clock);
        School india=school(1L,"Asia/Kolkata");
        when(schools.findAll()).thenReturn(List.of(india));
        when(enrollments.findEligiblePlannedStudentIds(1L,LocalDate.of(2026,9,6))).thenReturn(List.of());
        Student unbackfilled=student("UNBACKFILLED",1L);
        when(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                1L,StudentStatus.UPCOMING,LocalDate.of(2026,9,6))).thenReturn(List.of(unbackfilled));
        when(enrollments.activateEligiblePlannedEnrollment(1L,"UNBACKFILLED",LocalDate.of(2026,9,6)))
                .thenReturn(new StudentEnrollmentService.ScheduledActivation(
                        StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT,null));
        when(enrollmentRepository.existsBySchoolId(1L)).thenReturn(false);

        scheduler.updateStudentStatuses();

        verify(enrollmentRepository).existsBySchoolId(1L);
        // Read-only observability: the scheduler must not mutate student or enrollment state
        // while establishing the bootstrap-missing signal.
        verifyNoMoreInteractions(enrollmentRepository);
        assertThat(logAppender.list)
                .anyMatch(e -> e.getLevel()==Level.ERROR
                        && e.getFormattedMessage().contains("schoolId=1")
                        && e.getFormattedMessage().toLowerCase().contains("bootstrap"));
    }

    @Test void doesNotLogBootstrapMissingErrorWhenSchoolAlreadyHasEnrollmentRows(){
        StudentRepository students=mock(StudentRepository.class);
        SchoolRepository schools=mock(SchoolRepository.class);
        StudentEnrollmentService enrollments=mock(StudentEnrollmentService.class);
        StudentYearEndService yearEnd=mock(StudentYearEndService.class);
        StudentEnrollmentRepository enrollmentRepository=mock(StudentEnrollmentRepository.class);
        Clock clock=Clock.fixed(Instant.parse("2026-09-06T00:30:00Z"),ZoneOffset.UTC);
        StudentStatusScheduler scheduler=new StudentStatusScheduler(students,schools,enrollments,yearEnd,enrollmentRepository,clock);
        School india=school(1L,"Asia/Kolkata");
        when(schools.findAll()).thenReturn(List.of(india));
        when(enrollments.findEligiblePlannedStudentIds(1L,LocalDate.of(2026,9,6))).thenReturn(List.of());
        Student backfilled=student("BACKFILLED",1L);
        when(students.findBySchoolIdAndStatusAndJoiningDateLessThanEqualOrderByStudentId(
                1L,StudentStatus.UPCOMING,LocalDate.of(2026,9,6))).thenReturn(List.of(backfilled));
        when(enrollments.activateEligiblePlannedEnrollment(1L,"BACKFILLED",LocalDate.of(2026,9,6)))
                .thenReturn(new StudentEnrollmentService.ScheduledActivation(
                        StudentEnrollmentService.ScheduledActivationOutcome.NO_PLANNED_ENROLLMENT,null));
        when(enrollmentRepository.existsBySchoolId(1L)).thenReturn(true);

        scheduler.updateStudentStatuses();

        verify(enrollmentRepository).existsBySchoolId(1L);
        assertThat(logAppender.list).noneMatch(e -> e.getLevel()==Level.ERROR);
    }

    private static School school(long id,String zone){School s=new School();s.setId(id);s.setActive(true);s.setTimezone(zone);return s;}
    private static Student student(String id,long school){Student s=new Student();s.setStudentId(id);s.setSchoolId(school);s.setStatus(StudentStatus.UPCOMING);return s;}
    private static StudentEnrollment enrollment(long schoolId,String studentId,long sessionId,long id){
        StudentEnrollment e=new StudentEnrollment();
        e.setSchoolId(schoolId); e.setStudentId(studentId); e.setAcademicSessionId(sessionId); e.setId(id);
        return e;
    }
}
