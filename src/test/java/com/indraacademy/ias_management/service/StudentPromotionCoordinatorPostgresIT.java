package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate","spring.test.database.replace=none"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentPromotionService.class,StudentYearEndWorker.class,StudentYearEndService.class})
@EnabledIfEnvironmentVariable(named="DB_URL",matches=".+")
class StudentPromotionCoordinatorPostgresIT {
    static final long SCHOOL=-99701,SOURCE=-99702,TARGET=-99703,CLASS_9=-99704,CLASS_10=-99705;
    static final String S1="E2-PARTIAL-ONE",S2="E2-PARTIAL-TWO";
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("DB_URL"));r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));}
    @Autowired JdbcTemplate jdbc;
    @Autowired StudentPromotionService coordinator;
    @MockBean SecurityUtil security;
    @MockBean ParentPortalService parentPortal;
    @MockBean AuditService auditService;
    @MockBean Clock clock;
    long source1,source2;

    @BeforeEach void fixtures(){
        when(security.getSchoolId()).thenReturn(SCHOOL);when(security.getUsername()).thenReturn("admin");when(security.getRole()).thenReturn("ADMIN");
        when(clock.withZone(any(ZoneId.class))).thenAnswer(i->Clock.fixed(LocalDate.of(2027,3,1).atStartOfDay(i.getArgument(0)).toInstant(),i.getArgument(0)));
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'E2 IT','TRIAL','e2-it',4,8,'Asia/Kolkata')",SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP),(?,?,'2027-2028',DATE '2027-04-01',DATE '2028-03-31',false,CURRENT_TIMESTAMP)",SOURCE,SCHOOL,TARGET,SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES (?,?,'9',true,1,false),(?,?,'10',true,2,false)",CLASS_9,SCHOOL,CLASS_10,SCHOOL);
        insertStudent(S1);insertStudent(S2);source1=insertEnrollment(S1);source2=insertEnrollment(S2);
    }

    @Test void oneInvalidStudentDoesNotRollBackSuccessfulStudent(){
        TestTransaction.flagForCommit();TestTransaction.end();
        try{
            PromotionDecisionRequest request=new PromotionDecisionRequest();request.setSourceSessionId(SOURCE);request.setTargetSessionId(TARGET);
            request.setDecisions(List.of(decision(S1,source1,CLASS_9),decision(S2,source2,CLASS_10)));
            var result=coordinator.executePromotion(request,mock(HttpServletRequest.class));
            assertThat(result.outcomes()).extracting(o->o.code()).containsExactly("PROMOTED","INVALID_SOURCE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,S1)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=?",Integer.class,SCHOOL,S2)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM student_enrollment WHERE id=?",String.class,source1)).isEqualTo("CLOSED");
            assertThat(jdbc.queryForObject("SELECT status FROM student_enrollment WHERE id=?",String.class,source2)).isEqualTo("ACTIVE");
        }finally{cleanup();TestTransaction.start();}
    }

    private PromotionDecisionRequest.Decision decision(String student,long enrollment,long expectedClass){var d=new PromotionDecisionRequest.Decision();d.setStudentId(student);d.setAction(StudentYearEndDecision.Action.PROMOTE);d.setExpectedSourceEnrollmentId(enrollment);d.setExpectedSourceClassId(expectedClass);d.setTargetClassId(CLASS_10);return d;}
    private void insertStudent(String id){jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,joining_date) VALUES (?,?,'Synthetic','ACTIVE',?,'9',DATE '2026-04-01')",id,SCHOOL,CLASS_9);}
    private long insertEnrollment(String id){return jdbc.queryForObject("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,status,effective_from) VALUES (?,?,?,?,'9','ACTIVE',DATE '2026-04-01') RETURNING id",Long.class,SCHOOL,id,SOURCE,CLASS_9);}
    private void cleanup(){jdbc.update("DELETE FROM student_rollover_run WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM student_enrollment WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM student WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM school_class WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM academic_session WHERE school_id=?",SCHOOL);jdbc.update("DELETE FROM school WHERE id=?",SCHOOL);}
}
