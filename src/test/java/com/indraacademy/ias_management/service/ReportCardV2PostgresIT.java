package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassStudentResultDTO;
import com.indraacademy.ias_management.dto.MarkEntryRequest;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.entity.ReportCardResultMode;
import com.indraacademy.ias_management.entity.ReportCardSetup;
import com.indraacademy.ias_management.repository.ReportCardSetupRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Real-PostgreSQL coverage for Report Card V2, Phase 1: V83 tables, constraints and the
 * setup-exam scope trigger; setup validation; the canonical calculations (TOTAL, WEIGHTED,
 * terms, incomplete, not-applicable subjects, section ranks — a one-exam card equal to Class
 * Results); remarks / co-scholastic authorization; design scoping; attendance and the live PDF.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MarkService.class, ExamConfigService.class, WeightageCalculationEngine.class,
        StudentTemporalMembershipResolver.class, TimetableSessionAccessService.class,
        AttendanceService.class, AcademicSessionService.class,
        ReportCardSetupService.class, ReportCardDesignService.class, ReportCardV2Builder.class, ReportCardV2Service.class,
        OpenHtmlToPdfReportCardRenderer.class, ReportCardV2PostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ReportCardV2PostgresIT {

    static final long SCHOOL = -96001L, OTHER_SCHOOL = -96002L, SESSION = -96003L, OTHER_SESSION = -96004L, PAST_SESSION = -96005L;
    static final long CLASS_8 = -96010L, CLASS_9 = -96011L, OTHER_CLASS_8 = -96012L;
    static final long SECTION_A = -96020L, SECTION_B = -96021L;
    static final String A1 = "RCV2-A1", A2 = "RCV2-A2", A3 = "RCV2-A3", B1 = "RCV2-B1";

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-24T06:00:00Z"), ZoneOffset.UTC); }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MarkService marks;
    @Autowired ExamConfigService exams;
    @Autowired ReportCardSetupService setups;
    @Autowired ReportCardDesignService designs;
    @Autowired ReportCardV2Builder builder;
    @Autowired ReportCardV2Service reportCards;
    @Autowired ReportCardSetupRepository setupRepo;
    @Autowired StudentRepository studentRepository;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean StudentService studentService;
    @MockBean TeacherClassScopeService classScope;
    @MockBean ObjectStorageService objectStorage;
    @MockBean com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    long halfYearly, hyMath, hyScience, hySanskrit, finalExam, fMath, fScience, unitTest, utMath;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,grading_system) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'V2 School','TRIAL','rcv2-it',4,8,'Asia/Kolkata','CBSE')," +
                "(?,true,CURRENT_TIMESTAMP,'V2 Other','TRIAL','rcv2-other',4,8,'Asia/Kolkata','CBSE')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)," +
                "(?,?,'2025-2026',DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)," +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL, PAST_SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'8',true,false),(?,?,'9',true,false),(?,?,'8',true,false)",
                CLASS_8, SCHOOL, CLASS_9, SCHOOL, OTHER_CLASS_8, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)",
                SECTION_A, SCHOOL, CLASS_8, SECTION_B, SCHOOL, CLASS_8);
        student(A1, "Aarav", SECTION_A, "A");
        student(A2, "Bina", SECTION_A, "A");
        student(A3, "Chetan", SECTION_A, "A");
        student(B1, "Divya", SECTION_B, "B");
        jdbc.update("INSERT INTO class_subject (school_id,class_name,class_id,subject_name,is_elective,optional_group) VALUES " +
                "(?,'8',?,'Math',false,NULL),(?,'8',?,'Science',false,NULL),(?,'8',?,'Sanskrit',true,'Language')",
                SCHOOL, CLASS_8, SCHOOL, CLASS_8, SCHOOL, CLASS_8);
        jdbc.update("INSERT INTO student_elective_enrollment (school_id,student_id,class_name,optional_group,subject_name) VALUES (?,?,'8','Language','Sanskrit')",
                SCHOOL, A2);

        asAdmin();
        when(studentService.getActiveStudentsByClass(anyString())).thenAnswer(inv ->
                studentRepository.findAll().stream().filter(s -> SCHOOL == s.getSchoolId() && inv.getArgument(0).equals(s.getClassName())).toList());
        when(studentService.getStudent(anyString())).thenAnswer(inv -> studentRepository.findById(inv.getArgument(0)));
        when(objectStorage.resolveDisplayUrl(any())).thenReturn(null);

        halfYearly = exams.addExam("2026-2027", "8", "Half Yearly").getId();
        hyMath = exams.addExamSubject(halfYearly, "Math", 100, null).getId();
        hyScience = exams.addExamSubject(halfYearly, "Science", 100, null).getId();
        hySanskrit = exams.addExamSubject(halfYearly, "Sanskrit", 50, null).getId();
        finalExam = exams.addExam("2026-2027", "8", "Final").getId();
        fMath = exams.addExamSubject(finalExam, "Math", 100, null).getId();
        fScience = exams.addExamSubject(finalExam, "Science", 100, null).getId();
        unitTest = exams.addExam("2026-2027", "8", "Unit Test").getId();
        utMath = exams.addExamSubject(unitTest, "Math", 20, null).getId();

        // A1: HY 80+70 = 150/200 (75%), Final 90+90 (90%)  B1: HY 60+60, Final 70+70  A3: HY 50+40, Final 40+50
        save(A1, hyMath, 80); save(A1, hyScience, 70); save(A1, fMath, 90); save(A1, fScience, 90);
        save(A2, hyMath, 70); save(A2, hyScience, 80); save(A2, hySanskrit, 40); save(A2, fMath, 60); save(A2, fScience, 60);
        save(A3, hyMath, 50); save(A3, hyScience, 40); save(A3, fMath, 40); save(A3, fScience, 50);
        save(B1, hyMath, 60); save(B1, hyScience, 60); save(B1, fMath, 70); save(B1, fScience, 70);
    }

    // ── Setup ─────────────────────────────────────────────────────────────

    @Test
    void setupCrudAndValidation() {
        SetupDTO created = setups.create(request("Half Yearly", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null))));
        assertThat(created.exams()).extracting(SetupExamDTO::examName).containsExactly("Half Yearly");
        assertThat(created.className()).isEqualTo("8");
        assertThat(setups.list(SESSION, CLASS_8)).hasSize(1);

        assertThatThrownBy(() -> setups.create(request(" half  yearly ", ReportCardResultMode.TOTAL, List.of(), List.of(exam(finalExam, null, null)))))
                .hasMessageContaining("already has a report card");
        assertThatThrownBy(() -> setups.create(request("Dup", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null), exam(halfYearly, null, null)))))
                .hasMessageContaining("twice");

        SetupDTO updated = setups.update(created.id(), request("Half Yearly", ReportCardResultMode.TOTAL, List.of(),
                List.of(exam(halfYearly, null, null), exam(unitTest, null, null))));
        assertThat(updated.exams()).extracting(SetupExamDTO::examName).containsExactly("Half Yearly", "Unit Test");

        setups.delete(updated.id());
        em.flush();
        assertThat(setups.list(SESSION, CLASS_8)).isEmpty();
    }

    @Test
    void examsMustBelongToTheSameSchoolSessionAndClass() {
        long class9Exam = exams.addExam("2026-2027", "9", "Half Yearly").getId();
        assertThatThrownBy(() -> setups.create(request("X", ReportCardResultMode.TOTAL, List.of(), List.of(exam(class9Exam, null, null)))))
                .hasMessageContaining("not an exam of class 8");
        long pastExam = exams.addExam("2025-2026", "8", "Old Exam").getId();
        assertThatThrownBy(() -> setups.create(request("X", ReportCardResultMode.TOTAL, List.of(), List.of(exam(pastExam, null, null)))))
                .hasMessageContaining("not an exam of class 8");
        when(security.getSchoolId()).thenReturn(OTHER_SCHOOL);
        long otherSchoolExam = exams.addExam("2026-2027", "8", "Half Yearly").getId();
        asAdmin();
        assertThatThrownBy(() -> setups.create(request("X", ReportCardResultMode.TOTAL, List.of(), List.of(exam(otherSchoolExam, null, null)))))
                .hasMessageContaining("not found in your school");
        // Cross-school session / class ids are not found either.
        assertThatThrownBy(() -> setups.create(new SetupRequest(OTHER_SESSION, CLASS_8, "X", ReportCardResultMode.TOTAL, 0, List.of(), List.of(exam(halfYearly, null, null)))))
                .hasMessageContaining("not found in your school");
        assertThatThrownBy(() -> setups.create(new SetupRequest(SESSION, OTHER_CLASS_8, "X", ReportCardResultMode.TOTAL, 0, List.of(), List.of(exam(halfYearly, null, null)))))
                .hasMessageContaining("not found in your school");

        // The database trigger refuses a mismatched exam even when the service is bypassed.
        SetupDTO ok = setups.create(request("Half Yearly", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null))));
        em.flush();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO report_card_setup_exam (school_id,setup_id,exam_config_id,display_order) VALUES (?,?,?,9)",
                SCHOOL, ok.id(), class9Exam)).hasMessageContaining("does not belong");
    }

    @Test
    void weightsAreValidated() {
        assertThatThrownBy(() -> setups.create(request("T", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, "0.5")))))
                .hasMessageContaining("only used when the result is Weighted");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.WEIGHTED, List.of(), List.of(exam(halfYearly, null, "0.3"), exam(finalExam, null, "0.6")))))
                .hasMessageContaining("add up to 100%");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.WEIGHTED, List.of(), List.of(exam(halfYearly, null, "0.3"), exam(finalExam, null, null)))))
                .hasMessageContaining("Give every exam a weight");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.WEIGHTED, List.of(term("t1", "Term 1", "0.5")),
                List.of(exam(halfYearly, "t1", null), exam(finalExam, null, null))))).hasMessageContaining("put every exam in a term");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.WEIGHTED, List.of(term("t1", "Term 1", "0.5"), term("t2", "Term 2", "0.4")),
                List.of(exam(halfYearly, "t1", null), exam(finalExam, "t2", null))))).hasMessageContaining("term weights must add up to 100%");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.WEIGHTED, List.of(term("t1", "Term 1", "1")),
                List.of(exam(halfYearly, "t1", "0.5"), exam(finalExam, "t1", null))))).hasMessageContaining("every exam a weight or none");
        assertThatThrownBy(() -> setups.create(request("W", ReportCardResultMode.TOTAL, List.of(term("t1", "Term 1", null)), List.of(exam(halfYearly, null, null)))))
                .hasMessageContaining("has no exams");
    }

    // ── Calculations ──────────────────────────────────────────────────────

    @Test
    void singleExamCardEqualsClassResultsExactly() {
        ReportCardSetup setup = setup("Half Yearly", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null)));
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        Map<String, ClassStudentResultDTO> classResults = marks.getClassResults("8", halfYearly, null).stream()
                .collect(Collectors.toMap(ClassStudentResultDTO::getStudentId, r -> r));
        assertThat(cls.students()).hasSize(4);
        for (ReportCardV2Builder.StudentResult s : cls.students()) {
            ClassStudentResultDTO expected = classResults.get(s.student().getStudentId());
            assertThat(s.percentage()).as(s.student().getStudentId()).isEqualTo(expected.getPercentage());
            assertThat(s.grade()).as(s.student().getStudentId()).isEqualTo(expected.getGrade());
            assertThat(s.rank()).as(s.student().getStudentId()).isEqualTo(expected.getRank());
            assertThat(s.passed()).isEqualTo(expected.getPassed());
        }
        // Section-aware: B1 is alone in section B and ranks 1 there.
        assertThat(cls.student(B1).rank()).isEqualTo(1);
        assertThat(cls.student(A1).sectionId()).isEqualTo(SECTION_A);
        // A2's elective Sanskrit is on their card; for everyone else it is "—" (not applicable, not zero).
        assertThat(cls.student(A2).subjects()).extracting(ReportCardV2Builder.SubjectLine::subject).contains("Sanskrit");
        assertThat(cls.student(A1).subjects()).extracting(ReportCardV2Builder.SubjectLine::subject).doesNotContain("Sanskrit");
    }

    @Test
    void totalAcrossExamsSubjectsMissingFromAnExamAreNotZero() {
        ReportCardSetup setup = setup("Annual", ReportCardResultMode.TOTAL, List.of(),
                List.of(exam(halfYearly, null, null), exam(finalExam, null, null), exam(unitTest, null, null)));
        save(A1, utMath, 18);
        ReportCardV2Builder.StudentResult a1 = builder.computeClass(setup).student(A1);
        // 80+70 + 90+90 + 18 = 348 of 200+200+20
        assertThat(a1.percentage()).isEqualTo(ResultCalculator.round2(348.0 / 420.0 * 100.0));
        ReportCardV2Builder.SubjectLine science = a1.subjects().stream().filter(l -> l.subject().equals("Science")).findFirst().orElseThrow();
        assertThat(science.cells().get(2).applicable()).isFalse();          // the unit test has no Science
        assertThat(science.max()).isEqualTo(200.0);
        assertThat(science.percentage()).isEqualTo(80.0);
    }

    @Test
    void missingMarkMakesTheResultIncompleteWithoutPercentageGradeOrRank() {
        ReportCardSetup setup = setup("Annual", ReportCardResultMode.TOTAL, List.of(),
                List.of(exam(halfYearly, null, null), exam(unitTest, null, null)));
        save(A1, utMath, 18);   // A2, A3, B1 have no unit-test mark yet
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        ReportCardV2Builder.StudentResult a3 = cls.student(A3);
        assertThat(a3.status()).isEqualTo(ReportCardV2Builder.Status.INCOMPLETE);
        assertThat(a3.percentage()).isNull();
        assertThat(a3.grade()).isNull();
        assertThat(a3.rank()).isNull();
        assertThat(a3.marksMissing()).isEqualTo(1);
        assertThat(cls.student(A1).rank()).isEqualTo(1);   // the only complete student of section A
    }

    @Test
    void weightedExamsAndTerms() {
        ReportCardSetup flat = setup("Weighted", ReportCardResultMode.WEIGHTED, List.of(),
                List.of(exam(halfYearly, null, "0.3"), exam(finalExam, null, "0.7")));
        // A1: 0.3 × 75 + 0.7 × 90 = 85.5
        assertThat(builder.computeClass(flat).student(A1).percentage()).isEqualTo(85.5);

        ReportCardSetup terms = setup("Terms", ReportCardResultMode.WEIGHTED, List.of(term("t1", "Term 1", "0.4"), term("t2", "Term 2", "0.6")),
                List.of(exam(halfYearly, "t1", null), exam(unitTest, "t1", null), exam(finalExam, "t2", null)));
        save(A1, utMath, 20);
        // Term 1 (no exam weights = total marks): (150 + 20) / 220 = 77.27…%; Term 2: 90% → 0.4 × 77.27… + 0.6 × 90
        double expected = ResultCalculator.round2(0.4 * (170.0 / 220.0 * 100.0) + 0.6 * 90.0);
        ReportCardV2Builder.StudentResult a1 = builder.computeClass(terms).student(A1);
        assertThat(a1.percentage()).isEqualTo(expected);
        assertThat(a1.grade()).isEqualTo(GradingPolicy.grade(expected, "CBSE"));
        assertThat(builder.computeClass(terms).student(A3).status()).isEqualTo(ReportCardV2Builder.Status.INCOMPLETE);   // no unit-test mark

        ReportCardSetup inner = setup("Inner", ReportCardResultMode.WEIGHTED, List.of(term("t1", "Term 1", "0.5"), term("t2", "Term 2", "0.5")),
                List.of(exam(halfYearly, "t1", "0.8"), exam(unitTest, "t1", "0.2"), exam(finalExam, "t2", null)));
        // Term 1 = 0.8 × 75 + 0.2 × 100 = 80; Term 2 = 90 → 85
        assertThat(builder.computeClass(inner).student(A1).percentage()).isEqualTo(85.0);
    }

    // ── Remarks & co-scholastic ───────────────────────────────────────────

    @Test
    void teacherRemarksAreLimitedToOwnSectionAndNeverThePrincipals() {
        ReportCardSetup setup = setup("Half Yearly", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null)));
        long art = designs.createActivity(new ActivityRequest("Art", true)).id();
        long retired = designs.createActivity(new ActivityRequest("Old Activity", false)).id();
        asTeacher();

        RemarksPageDTO page = reportCards.remarks(setup.getId(), null);
        assertThat(page.canEditPrincipal()).isFalse();
        assertThat(page.students()).extracting(RemarkRowDTO::studentId).containsExactlyInAnyOrder(A1, A2, A3);   // section A only

        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, "Hard working", null, Map.of(art, "a")))));
        assertThatThrownBy(() -> reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, null, "Principal words", null)))))
                .isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(B1, "Other section", null, null)))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, null, null, Map.of(art, "Z"))))))
                .hasMessageContaining("grades are");
        assertThatThrownBy(() -> reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, null, null, Map.of(retired, "A"))))))
                .hasMessageContaining("not an active");

        asAdmin();
        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(B1, "Good", "Excellent", null))));
        RemarksPageDTO all = reportCards.remarks(setup.getId(), null);
        assertThat(all.canEditPrincipal()).isTrue();
        Map<String, RemarkRowDTO> byId = all.students().stream().collect(Collectors.toMap(RemarkRowDTO::studentId, r -> r));
        assertThat(byId.get(A1).teacherRemark()).isEqualTo("Hard working");
        assertThat(byId.get(A1).grades()).containsEntry(art, "A");
        assertThat(byId.get(B1).principalRemark()).isEqualTo("Excellent");

        // A card with remarks cannot be deleted silently.
        assertThatThrownBy(() -> setups.delete(setup.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void designIsScopedToTheSchool() {
        designs.update(new DesignDTO("Learn and lead", "Footer", "TEXT", "CONFIDENTIAL", true, true, true, true, true, true, false, false,
                "Class Teacher", "Head of School", null));
        designs.createActivity(new ActivityRequest("Sports", true));
        assertThat(designs.get().principalSignatureLabel()).isEqualTo("Head of School");
        assertThat(designs.get().showRank()).isFalse();
        when(security.getSchoolId()).thenReturn(OTHER_SCHOOL);
        assertThat(designs.get().motto()).isNull();                   // the other school still has the defaults
        assertThat(designs.get().principalSignatureLabel()).isEqualTo("Principal");
        assertThat(designs.activities(false)).isEmpty();
        asAdmin();
        assertThatThrownBy(() -> designs.createActivity(new ActivityRequest(" sports ", true))).hasMessageContaining("already exists");
    }

    // ── Generate & Preview ────────────────────────────────────────────────

    @Test
    void summaryReadinessAndTeacherScope() {
        ReportCardSetup setup = setup("Annual", ReportCardResultMode.TOTAL, List.of(),
                List.of(exam(halfYearly, null, null), exam(unitTest, null, null)));
        save(A1, utMath, 18);
        designs.createActivity(new ActivityRequest("Art", true));
        SummaryDTO admin = reportCards.summary(setup.getId(), null);
        assertThat(admin.readiness().draftExams()).containsExactly("Half Yearly", "Unit Test");
        assertThat(admin.readiness().students()).isEqualTo(4);
        assertThat(admin.readiness().incomplete()).isEqualTo(3);
        assertThat(admin.readiness().missingTeacherRemarks()).isEqualTo(4);
        assertThat(admin.readiness().missingCoScholastic()).isEqualTo(4);
        assertThat(admin.readiness().missingPhotos()).isEqualTo(4);
        assertThat(reportCards.summary(setup.getId(), SECTION_B).students()).extracting(SummaryRowDTO::studentId).containsExactly(B1);

        asTeacher();
        SummaryDTO teacher = reportCards.summary(setup.getId(), SECTION_B);   // asks for B, gets their own A
        assertThat(teacher.teacherView()).isTrue();
        assertThat(teacher.students()).extracting(SummaryRowDTO::studentId).containsExactlyInAnyOrder(A1, A2, A3);
        assertThat(reportCards.previewPdf(setup.getId(), A1).bytes()).startsWith("%PDF".getBytes());
        assertThatThrownBy(() -> reportCards.previewPdf(setup.getId(), B1)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void livePdfIncludesAttendanceRemarksAndTheCanonicalResult() throws Exception {
        ReportCardSetup setup = setup("Half Yearly", ReportCardResultMode.TOTAL, List.of(), List.of(exam(halfYearly, null, null)));
        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, "Steady progress", "Well done", null))));
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        ReportCardV2ViewModel vm = reportCards.viewModel(cls, cls.student(A1));
        assertThat(vm.title()).isEqualTo("HALF YEARLY — REPORT CARD");
        assertThat(vm.attendance()).isNotNull();                          // Attendance V2 for the session
        assertThat(vm.summary().percentage()).isEqualTo("75.00%");
        assertThat(vm.summary().rank()).isEqualTo(String.valueOf(cls.student(A1).rank()));
        assertThat(vm.teacherRemark()).isEqualTo("Steady progress");
        assertThat(vm.previewLine()).contains("not a published report card");

        byte[] pdf = reportCards.previewPdf(setup.getId(), A1).bytes();
        String text = ReportCardV2RendererTest.pages(pdf).stream().map(ReportCardV2RendererTest.Page::text).collect(Collectors.joining());
        assertThat(text).contains("Aarav").contains("75.00%").contains("Steady progress").contains("Well done").contains("ATTENDANCE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void asAdmin() {
        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("ADMIN");
        when(security.getUsername()).thenReturn("admin-v2");
    }

    private void asTeacher() {
        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("TEACHER");
        when(security.getUsername()).thenReturn("T-A");
        when(classScope.resolveOwnScope("T-A", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("8", SECTION_A, false));
        when(classScope.authorizeAndScopeToClass(eq("TEACHER"), eq("T-A"), eq(SCHOOL), eq("8"), any()))
                .thenReturn(TeacherClassScopeService.ScopedAccess.allow(SECTION_A));
    }

    private ReportCardSetup setup(String name, ReportCardResultMode mode, List<TermInput> terms, List<ExamInput> exams) {
        SetupDTO dto = setups.create(request(name, mode, terms, exams));
        em.flush();
        return setupRepo.findById(dto.id()).orElseThrow();
    }

    private static SetupRequest request(String name, ReportCardResultMode mode, List<TermInput> terms, List<ExamInput> exams) {
        return new SetupRequest(SESSION, CLASS_8, name, mode, 0, terms, exams);
    }

    private static ExamInput exam(long id, String termKey, String weight) {
        return new ExamInput(id, termKey, weight == null ? null : new BigDecimal(weight));
    }

    private static TermInput term(String key, String name, String weight) {
        return new TermInput(key, name, weight == null ? null : new BigDecimal(weight));
    }

    private void student(String id, String name, long sectionId, String sectionName) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,'8',?,?,DATE '2026-04-01')", id, SCHOOL, name, CLASS_8, sectionId, sectionName);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id," +
                "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,'8',?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SESSION, CLASS_8, sectionId, sectionName);
    }

    private void save(String studentId, long entryId, double value) {
        MarkEntryRequest r = new MarkEntryRequest();
        r.setStudentId(studentId);
        r.setExamSubjectEntryId(entryId);
        r.setMarksObtained(value);
        marks.bulkSaveMarks(List.of(r), null);
    }
}
