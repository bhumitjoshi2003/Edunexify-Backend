package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.controller.StudentController;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentPromotionServiceTest {
    @Mock StudentRepository students;
    @Mock StudentEnrollmentRepository enrollments;
    @Mock AcademicSessionRepository sessions;
    @Mock SchoolClassRepository classes;
    @Mock SectionRepository sections;
    @Mock SchoolRepository schools;
    @Mock StudentYearEndWorker worker;
    @Mock SecurityUtil security;
    @Mock HttpServletRequest servletRequest;
    StudentPromotionService service;

    AcademicSession source, target;
    SchoolClass class9, class10;
    StudentEnrollment enrollment;
    Student student;

    @BeforeEach void setup(){
        service=new StudentPromotionService(students,enrollments,sessions,classes,sections,schools,
                worker,security,Clock.fixed(Instant.parse("2027-03-01T00:00:00Z"),ZoneOffset.UTC));
        lenient().when(security.getSchoolId()).thenReturn(1L);
        source=session(11L,"2026-2027",LocalDate.of(2026,4,1),LocalDate.of(2027,3,31));
        target=session(12L,"2027-2028",LocalDate.of(2027,4,1),LocalDate.of(2028,3,31));
        class9=schoolClass(91L,"9",1); class10=schoolClass(101L,"10",2);
        enrollment=new StudentEnrollment(); enrollment.setId(1001L); enrollment.setSchoolId(1L);
        enrollment.setStudentId("S1"); enrollment.setAcademicSessionId(11L); enrollment.setClassId(91L);
        enrollment.setClassNameSnapshot("9"); enrollment.setSectionId(901L); enrollment.setSectionNameSnapshot("A");
        enrollment.setStatus(StudentEnrollmentStatus.ACTIVE); enrollment.setEffectiveFrom(LocalDate.of(2026,4,1));
        student=new Student();student.setStudentId("S1");student.setSchoolId(1L);student.setName("Student One");
        student.setClassId(91L);student.setSectionId(901L);student.setStatus(StudentStatus.ACTIVE);
        School school=new School();school.setId(1L);school.setTimezone("UTC");
        lenient().when(sessions.findByIdAndSchoolId(11L,1L)).thenReturn(Optional.of(source));
        lenient().when(sessions.findByIdAndSchoolId(12L,1L)).thenReturn(Optional.of(target));
        lenient().when(schools.findById(1L)).thenReturn(Optional.of(school));
        lenient().when(classes.findBySchoolIdAndActiveOrderByDisplayOrderAsc(1L,true)).thenReturn(List.of(class9,class10));
        lenient().when(enrollments.findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(1L,11L)).thenReturn(List.of(enrollment));
        lenient().when(enrollments.findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(1L,12L)).thenReturn(List.of());
        lenient().when(students.findByStudentIdInAndSchoolId(List.of("S1"),1L)).thenReturn(List.of(student));
        lenient().when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(1L,101L,true)).thenReturn(List.of());
        lenient().when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(1L,91L,true)).thenReturn(List.of(section(901L,91L,"A")));
    }

    @Test void previewUsesEnrollmentRatherThanProjectionAndRecommendsPromote(){
        student.setClassId(101L); student.setSectionId(null);
        var preview=service.getPromotionPreview(11L,12L,null,null);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.candidates()).singleElement().satisfies(c->{
            assertThat(c.sourceClassId()).isEqualTo(91L);
            assertThat(c.sourceSectionId()).isEqualTo(901L);
            assertThat(c.recommendedDecision()).isEqualTo(StudentYearEndDecision.Action.PROMOTE);
            assertThat(c.promoteTargetClassId()).isEqualTo(101L);
            assertThat(c.warnings()).extracting(w->w.code()).contains("PROJECTION_DIFFERS");
        });
    }

    @Test void previewReportsTargetSectionSelectionAndDetainReuse(){
        when(sections.findBySchoolIdAndClassIdAndActiveOrderByDisplayOrderAsc(1L,101L,true))
                .thenReturn(List.of(section(10001L,101L,"B")));
        var candidate=service.getPromotionPreview(11L,12L,null,null).candidates().getFirst();
        assertThat(candidate.promoteTargetSectionRequired()).isTrue();
        assertThat(candidate.proposedPromoteTargetSectionId()).isNull();
        assertThat(candidate.proposedDetainTargetSectionId()).isEqualTo(901L);
        assertThat(candidate.availableDecisions()).containsExactly(
                StudentYearEndDecision.Action.PROMOTE,StudentYearEndDecision.Action.DETAIN);
    }

    @Test void finalClassRecommendsPassOutWithoutMakingDecision(){
        enrollment.setClassId(101L);enrollment.setClassNameSnapshot("10");enrollment.setSectionId(null);enrollment.setSectionNameSnapshot(null);
        student.setClassId(101L);student.setSectionId(null);
        var candidate=service.getPromotionPreview(11L,12L,101L,"S1").candidates().getFirst();
        assertThat(candidate.recommendedDecision()).isEqualTo(StudentYearEndDecision.Action.PASS_OUT);
        assertThat(candidate.availableDecisions()).containsExactly(
                StudentYearEndDecision.Action.DETAIN,StudentYearEndDecision.Action.PASS_OUT);
    }

    @Test void invalidSessionPairReturnsMachineReadablePreviewErrors(){
        target.setStartDate(LocalDate.of(2027,4,2));
        var preview=service.getPromotionPreview(11L,12L,null,null);
        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).extracting(e->e.code()).contains("SESSIONS_NOT_CONTIGUOUS");
        assertThat(preview.candidates()).isEmpty();
    }

    @Test void filteredLegacyStudentIsReportedButNotCandidate(){
        when(enrollments.findBySchoolIdAndAcademicSessionIdOrderByStudentIdAscEffectiveFromAsc(1L,11L)).thenReturn(List.of());
        when(students.findByStudentIdAndSchoolId("S1",1L)).thenReturn(Optional.of(student));
        var preview=service.getPromotionPreview(11L,12L,null,"S1");
        assertThat(preview.candidates()).isEmpty();
        assertThat(preview.uncoveredStudents()).singleElement().satisfies(u->assertThat(u.code()).isEqualTo("INVALID_SOURCE"));
    }

    @Test void executeDelegatesExplicitCanonicalCommandAndReturnsOutcome(){
        when(worker.apply(any())).thenReturn(new StudentYearEndDecision.Result(
                StudentYearEndDecision.Outcome.PROMOTED,"applied",1001L,2001L,
                StudentEnrollmentStatus.PLANNED,false));
        var response=service.executePromotion(batch(decision("S1",StudentYearEndDecision.Action.PROMOTE)),servletRequest);
        assertThat(response.outcomes()).singleElement().satisfies(o->{assertThat(o.code()).isEqualTo("PROMOTED");assertThat(o.targetEnrollmentStatus()).isEqualTo("PLANNED");});
        verify(worker).apply(argThat(c->c.schoolId().equals(1L)&&c.sourceSessionId().equals(11L)
                &&c.targetSessionId().equals(12L)&&c.expectedSourceEnrollmentId().equals(1001L)));
    }

    @Test void workerFailureDoesNotPreventLaterStudentSuccess(){
        when(worker.apply(any())).thenThrow(new IllegalArgumentException("stale source"))
                .thenReturn(new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.DETAINED,
                        "applied",1002L,2002L,StudentEnrollmentStatus.PLANNED,false));
        var response=service.executePromotion(batch(
                decision("S1",StudentYearEndDecision.Action.PROMOTE),
                decision("S2",StudentYearEndDecision.Action.DETAIN)),servletRequest);
        assertThat(response.outcomes()).extracting(o->o.code()).containsExactly("VALIDATION_ERROR","DETAINED");
        assertThat(response.summary()).containsEntry("VALIDATION_ERROR",1L).containsEntry("DETAINED",1L);
        verify(worker,times(2)).apply(any());
    }

    @Test void alreadyAppliedAndConflictRemainDistinctPerStudentCodes(){
        when(worker.apply(any())).thenReturn(
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.ALREADY_APPLIED,"same",1001L,2001L,StudentEnrollmentStatus.PLANNED,false),
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.CONFLICT,"different",1002L,null,null,false));
        var response=service.executePromotion(batch(
                decision("S1",StudentYearEndDecision.Action.PROMOTE),
                decision("S2",StudentYearEndDecision.Action.DETAIN)),servletRequest);
        assertThat(response.outcomes()).extracting(o->o.code()).containsExactly("ALREADY_APPLIED","CONFLICT");
    }

    /** Regression for the PASS_OUT-through-executePromotion defect: the batch-level
     *  targetSessionId is @NotNull/@Valid at the controller and therefore always non-null here,
     *  but StudentYearEndService.applyPassOut correctly rejects any non-null target
     *  session/class/section for PASS_OUT. The coordinator must adapt the internal command to
     *  omit all three for PASS_OUT, regardless of what the batch (or an errant caller) supplies. */
    @Test void executeOmitsTargetSessionClassAndSectionForPassOutRegardlessOfBatchTarget(){
        when(worker.apply(any())).thenReturn(new StudentYearEndDecision.Result(
                StudentYearEndDecision.Outcome.PASSED_OUT,"Graduation scheduled",1001L,null,null,true));
        var response=service.executePromotion(batch(passOutDecision("S1")),servletRequest);
        assertThat(response.outcomes()).singleElement().satisfies(o->{
            assertThat(o.code()).isEqualTo("PASSED_OUT");
            assertThat(o.targetEnrollmentId()).isNull();
            assertThat(o.lifecycleFinalizationPending()).isTrue();
        });
        verify(worker).apply(argThat(c->c.action()==StudentYearEndDecision.Action.PASS_OUT
                && c.targetSessionId()==null && c.targetClassId()==null && c.targetSectionId()==null
                && c.sourceSessionId().equals(11L) && c.expectedSourceEnrollmentId().equals(1001L)));
    }

    @Test void executeStillForwardsBatchTargetSessionForPromoteAndDetain(){
        when(worker.apply(any())).thenReturn(
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.PROMOTED,"applied",1001L,2001L,StudentEnrollmentStatus.PLANNED,false),
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.DETAINED,"applied",1002L,2002L,StudentEnrollmentStatus.PLANNED,false));
        service.executePromotion(batch(
                decision("S1",StudentYearEndDecision.Action.PROMOTE),
                decision("S2",StudentYearEndDecision.Action.DETAIN)),servletRequest);
        verify(worker).apply(argThat(c->c.action()==StudentYearEndDecision.Action.PROMOTE
                && c.targetSessionId().equals(12L) && c.targetClassId().equals(101L)));
        verify(worker).apply(argThat(c->c.action()==StudentYearEndDecision.Action.DETAIN
                && c.targetSessionId().equals(12L) && c.targetClassId().equals(91L)));
    }

    /** Proves the adapter operates per-decision, not by silently changing the whole batch's
     *  target-session behavior — a PROMOTE and a PASS_OUT sharing the same batch-level
     *  targetSessionId must each reach the worker with their own, action-correct command. */
    @Test void mixedBatchAdaptsPassOutIndependentlyOfSiblingPromoteDecision(){
        when(worker.apply(any())).thenReturn(
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.PROMOTED,"applied",1001L,2001L,StudentEnrollmentStatus.PLANNED,false),
                new StudentYearEndDecision.Result(StudentYearEndDecision.Outcome.PASSED_OUT,"Graduation scheduled",1002L,null,null,true));
        var response=service.executePromotion(batch(
                decision("S1",StudentYearEndDecision.Action.PROMOTE),
                passOutDecision("S2")),servletRequest);
        assertThat(response.outcomes()).extracting(o->o.code()).containsExactly("PROMOTED","PASSED_OUT");
        verify(worker).apply(argThat(c->c.studentId().equals("S1") && c.action()==StudentYearEndDecision.Action.PROMOTE
                && c.targetSessionId().equals(12L)));
        verify(worker).apply(argThat(c->c.studentId().equals("S2") && c.action()==StudentYearEndDecision.Action.PASS_OUT
                && c.targetSessionId()==null && c.targetClassId()==null && c.targetSectionId()==null));
    }

    @Test void batchLevelTargetSessionIdRemainsRequiredExternally() throws Exception {
        var field = PromotionDecisionRequest.class.getDeclaredField("targetSessionId");
        assertThat(field.getAnnotation(jakarta.validation.constraints.NotNull.class)).isNotNull();
    }

    @Test void previewAndExecuteAreSchoolAdminOnly() throws Exception {
        PreAuthorize preview=StudentController.class.getMethod("getPromotionPreview",
                Long.class,Long.class,Long.class,String.class).getAnnotation(PreAuthorize.class);
        PreAuthorize execute=StudentController.class.getMethod("executePromotion",
                PromotionDecisionRequest.class,HttpServletRequest.class).getAnnotation(PreAuthorize.class);
        assertThat(preview.value()).isEqualTo("hasRole('ADMIN')");
        assertThat(execute.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test void pendingAndExitDecisionsNeverReachTheYearEndWorker(){
        var response=service.executePromotion(batch(decision("S1",StudentYearEndDecision.Action.PENDING),
                decision("S2",StudentYearEndDecision.Action.TRANSFER)),servletRequest);
        assertThat(response.outcomes()).extracting(o->o.code()).containsExactly("PENDING","VALIDATION_ERROR");
        assertThat(response.summary()).containsEntry("PENDING",1L);
        assertThat(response.run()).isNull();                          // no run repository in this narrow test
        verifyNoInteractions(worker);
    }

    @Test void withdrawIsNotAYearEndDecision(){
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        assertThatThrownBy(()->json.readValue("{\"studentId\":\"S1\",\"action\":\"WITHDRAW\"}",PromotionDecisionRequest.Decision.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        assertThat(StudentYearEndDecision.Action.values()).extracting(Enum::name)
                .containsExactly("PROMOTE","DETAIN","PASS_OUT","TRANSFER","PENDING");
    }

    @Test void yearEndServiceRejectsNonMembershipActions(){
        var yearEnd=new StudentYearEndService(null,null,null,null,null,null,null,null,null);
        for(var action:List.of(StudentYearEndDecision.Action.TRANSFER,StudentYearEndDecision.Action.PENDING)){
            assertThatThrownBy(()->yearEnd.apply(new StudentYearEndDecision.Request(1L,"S1",11L,12L,1001L,91L,action,null,null,null)))
                    .as(action.name()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only PROMOTE, DETAIN or PASS_OUT");
        }
    }

    private PromotionDecisionRequest batch(PromotionDecisionRequest.Decision... decisions){var b=new PromotionDecisionRequest();b.setSourceSessionId(11L);b.setTargetSessionId(12L);b.setDecisions(List.of(decisions));return b;}
    private PromotionDecisionRequest.Decision decision(String id,StudentYearEndDecision.Action action){var d=new PromotionDecisionRequest.Decision();d.setStudentId(id);d.setAction(action);d.setExpectedSourceEnrollmentId(id.equals("S1")?1001L:1002L);d.setExpectedSourceClassId(91L);d.setTargetClassId(action==StudentYearEndDecision.Action.DETAIN?91L:101L);return d;}
    /** Mirrors exactly what the real frontend sends for PASS_OUT (web/Android both null out
     *  targetClassId/targetSectionId client-side) — only targetSessionId is ever batch-forced. */
    private PromotionDecisionRequest.Decision passOutDecision(String id){var d=new PromotionDecisionRequest.Decision();d.setStudentId(id);d.setAction(StudentYearEndDecision.Action.PASS_OUT);d.setExpectedSourceEnrollmentId(id.equals("S1")?1001L:1002L);d.setExpectedSourceClassId(91L);return d;}
    private AcademicSession session(long id,String label,LocalDate start,LocalDate end){var s=new AcademicSession();s.setId(id);s.setSchoolId(1L);s.setLabel(label);s.setStartDate(start);s.setEndDate(end);return s;}
    private SchoolClass schoolClass(long id,String name,int order){var c=new SchoolClass();c.setId(id);c.setSchoolId(1L);c.setName(name);c.setActive(true);c.setDisplayOrder(order);return c;}
    private Section section(long id,long classId,String name){var s=new Section();s.setId(id);s.setSchoolId(1L);s.setClassId(classId);s.setName(name);s.setActive(true);return s;}
}
