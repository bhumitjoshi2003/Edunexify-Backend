package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.ReportCardDocumentRepository;
import com.indraacademy.ias_management.repository.ReportCardSetupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Promotion preview result context: published report card snapshot first, then the canonical V2
 * calculation only when every exam is published, otherwise nothing — plus the FAIL / INCOMPLETE
 * warnings. It only annotates; the decisions offered never change.
 */
@ExtendWith(MockitoExtension.class)
class PromotionResultContextServiceTest {

    static final long SCHOOL = 1L, SESSION = 10L, TARGET = 11L, CLASS = 5L;

    @Mock ReportCardSetupRepository setups;
    @Mock ReportCardDocumentRepository documents;
    @Mock ReportCardV2Builder builder;
    PromotionResultContextService service;
    ReportCardSetup halfYearly, annual;

    @BeforeEach
    void setUp() {
        service = new PromotionResultContextService(setups, documents, builder);
        halfYearly = setup(1L, "Half Yearly");
        annual = setup(2L, "Annual");
    }

    @Test
    void publishedReportCardComesFirstThenPublishedResultsWithWarnings() {
        when(setups.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(SCHOOL, SESSION, CLASS))
                .thenReturn(List.of(halfYearly, annual));
        ReportCardDocument doc = new ReportCardDocument();
        doc.setStudentId("S1");
        doc.setReference("RC-ABCDEFGHJK");
        doc.setSnapshot("{\"result\":{\"studentId\":\"S1\",\"status\":\"PASS\",\"percentage\":81.5,\"grade\":\"A2\"}}");
        when(documents.findBySetupIdAndSchoolIdAndStatus(2L, SCHOOL, ReportCardPublicationStatus.ACTIVE)).thenReturn(List.of(doc));
        when(builder.computeClass(annual)).thenReturn(classResult(ExamResultStatus.PUBLISHED,
                result("S2", 30.0, "E", ReportCardV2Builder.Status.FAIL),
                result("S3", 60.0, "C1", ReportCardV2Builder.Status.INCOMPLETE)));

        PromotionPreviewDTO out = service.enrich(SCHOOL, preview("S1", "S2", "S3", "S4"));
        Map<String, PromotionPreviewDTO.Candidate> byId = byId(out);

        PromotionPreviewDTO.ResultContext s1 = byId.get("S1").result();
        assertThat(s1.source()).isEqualTo("REPORT_CARD");
        assertThat(s1.reportCardStatus()).isEqualTo("PUBLISHED");
        assertThat(s1.setupName()).isEqualTo("Annual");                      // the final setup
        assertThat(s1.percentage()).isEqualTo(81.5);
        assertThat(s1.grade()).isEqualTo("A2");
        assertThat(s1.result()).isEqualTo("PASS");
        assertThat(s1.reportCardReference()).isEqualTo("RC-ABCDEFGHJK");
        assertThat(byId.get("S1").warnings()).isEmpty();

        assertThat(byId.get("S2").result().source()).isEqualTo("RESULTS");
        assertThat(byId.get("S2").result().reportCardStatus()).isEqualTo("NOT_PUBLISHED");
        assertThat(byId.get("S2").result().result()).isEqualTo("FAIL");
        assertThat(byId.get("S2").warnings()).extracting(PromotionPreviewDTO.Issue::code).containsExactly("RESULT_FAIL");
        assertThat(byId.get("S3").warnings()).extracting(PromotionPreviewDTO.Issue::code).containsExactly("RESULT_INCOMPLETE");
        assertThat(byId.get("S4").result().result()).isEqualTo("NO_RESULT");

        // Decisions offered are untouched by result context.
        assertThat(byId.get("S2").availableDecisions()).containsExactly(StudentYearEndDecision.Action.PROMOTE,
                StudentYearEndDecision.Action.DETAIN);
        assertThat(byId.get("S2").recommendedDecision()).isEqualTo(StudentYearEndDecision.Action.PROMOTE);
    }

    @Test
    void draftExamsOrNoSetupGiveNoResult() {
        when(setups.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(SCHOOL, SESSION, CLASS))
                .thenReturn(List.of(annual));
        when(documents.findBySetupIdAndSchoolIdAndStatus(any(), any(), any())).thenReturn(List.of());
        when(builder.computeClass(annual)).thenReturn(classResult(ExamResultStatus.DRAFT,
                result("S1", 90.0, "A1", ReportCardV2Builder.Status.PASS)));
        PromotionPreviewDTO.ResultContext rc = byId(service.enrich(SCHOOL, preview("S1"))).get("S1").result();
        assertThat(rc.source()).isEqualTo("NONE");
        assertThat(rc.reportCardStatus()).isEqualTo("RESULTS_NOT_PUBLISHED");
        assertThat(rc.percentage()).isNull();                                  // a draft result is never shown

        when(setups.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(SCHOOL, SESSION, CLASS))
                .thenReturn(List.of());
        assertThat(byId(service.enrich(SCHOOL, preview("S1"))).get("S1").result().reportCardStatus()).isEqualTo("NO_SETUP");
    }

    @Test
    void aFailureNeverBreaksThePreview() {
        when(setups.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(SCHOOL, SESSION, CLASS))
                .thenThrow(new IllegalStateException("boom"));
        PromotionPreviewDTO out = service.enrich(SCHOOL, preview("S1"));
        assertThat(out.candidates()).hasSize(1);
        assertThat(out.candidates().getFirst().result()).isNull();
        PromotionPreviewDTO invalid = new PromotionPreviewDTO(SESSION, TARGET, false, List.of(), List.of(), List.of());
        assertThat(service.enrich(SCHOOL, invalid)).isSameAs(invalid);
        verifyNoInteractions(builder);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ReportCardSetup setup(long id, String name) {
        ReportCardSetup s = new ReportCardSetup();
        s.setId(id);
        s.setName(name);
        s.setSchoolId(SCHOOL);
        return s;
    }

    private static PromotionPreviewDTO preview(String... ids) {
        List<PromotionPreviewDTO.Candidate> candidates = Arrays.stream(ids).map(id -> new PromotionPreviewDTO.Candidate(
                id, "Student " + id, 100L, SESSION, CLASS, "5", null, null,
                List.of(StudentYearEndDecision.Action.PROMOTE, StudentYearEndDecision.Action.DETAIN),
                StudentYearEndDecision.Action.PROMOTE, 6L, "6", CLASS, "5", false, null, null,
                StudentEnrollmentStatus.PLANNED, List.of(), List.of(), "NOT_APPLIED", null)).toList();
        return new PromotionPreviewDTO(SESSION, TARGET, true, List.of(), candidates, List.of());
    }

    private static ReportCardV2Builder.StudentResult result(String id, Double pct, String grade, ReportCardV2Builder.Status status) {
        Student st = new Student();
        st.setStudentId(id);
        return new ReportCardV2Builder.StudentResult(st, null, Set.of(), List.of(), List.of(), 0, 0,
                status == ReportCardV2Builder.Status.INCOMPLETE ? 1 : 0, pct, grade,
                status == ReportCardV2Builder.Status.PASS, 1, status);
    }

    private static ReportCardV2Builder.ClassResult classResult(ExamResultStatus examStatus, ReportCardV2Builder.StudentResult... students) {
        return new ReportCardV2Builder.ClassResult(null, null, null, "CBSE",
                List.of(new ReportCardV2Builder.Column(1L, "Annual", examStatus, null, null, null)),
                List.of(), List.of(students), Map.of());
    }

    private static Map<String, PromotionPreviewDTO.Candidate> byId(PromotionPreviewDTO p) {
        Map<String, PromotionPreviewDTO.Candidate> m = new HashMap<>();
        p.candidates().forEach(c -> m.put(c.studentId(), c));
        return m;
    }
}
