package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.ClassOverviewService;
import com.indraacademy.ias_management.service.EntitlementService;
import com.indraacademy.ias_management.service.ParentPortalService;
import com.indraacademy.ias_management.service.RemarksService;
import com.indraacademy.ias_management.service.ReportCardDataAssembler;
import com.indraacademy.ias_management.service.ReportCardPdfGenerator;
import com.indraacademy.ias_management.service.ReportCardPublicationService;
import com.indraacademy.ias_management.service.ReportCardTemplateService;
import com.indraacademy.ias_management.service.TeacherClassScopeService;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase E6E: Historical Report Card correctness after promotion.
 *
 * checkStudentOrParentPublishedAccess (backing GET /api/report-cards and /api/report-cards/pdf)
 * used to look up publication status against the student's CURRENT class, so an already-published
 * Class-9 report card would incorrectly 403 for a STUDENT/PARENT after the student was promoted to
 * Class 10 — the publication row is keyed by "9", but the check queried "10". It now resolves the
 * historical context the same way ReportCardDataAssembler does
 * (ReportCardDataAssembler.resolveHistoricalContext), so the publication lookup always targets the
 * class the report card was actually published for, and a genuine multi-class ambiguity surfaces
 * as 409 CONFLICT rather than an arbitrary pick.
 */
@ExtendWith(MockitoExtension.class)
class ReportCardControllerHistoricalTest {

    @Mock private ReportCardTemplateService templateService;
    @Mock private ReportCardDataAssembler assembler;
    @Mock private RemarksService remarksService;
    @Mock private ReportCardPdfGenerator pdfGenerator;
    @Mock private ReportCardPublicationService publicationService;
    @Mock private ClassOverviewService classOverviewService;
    @Mock private StudentRepository studentRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private EntitlementService entitlementService;
    @Mock private ParentPortalService parentPortalService;
    @Mock private TeacherClassScopeService teacherClassScopeService;
    @Mock private com.indraacademy.ias_management.repository.SchoolClassRepository schoolClassRepository;
    @Mock private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;
    @Mock private com.indraacademy.ias_management.repository.StudentEnrollmentRepository studentEnrollmentRepository;

    private ReportCardController controller;

    private static final Long SCHOOL_ID = 4L;
    private static final String STUDENT_ID = "S1";
    private static final Long TEMPLATE_ID = 100L;
    private static final String SESSION = "2025-2026";

    @BeforeEach
    void setUp() {
        controller = new ReportCardController();
        ReflectionTestUtils.setField(controller, "templateService", templateService);
        ReflectionTestUtils.setField(controller, "assembler", assembler);
        ReflectionTestUtils.setField(controller, "remarksService", remarksService);
        ReflectionTestUtils.setField(controller, "pdfGenerator", pdfGenerator);
        ReflectionTestUtils.setField(controller, "publicationService", publicationService);
        ReflectionTestUtils.setField(controller, "classOverviewService", classOverviewService);
        ReflectionTestUtils.setField(controller, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(controller, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(controller, "entitlementService", entitlementService);
        ReflectionTestUtils.setField(controller, "parentPortalService", parentPortalService);
        ReflectionTestUtils.setField(controller, "teacherClassScopeService", teacherClassScopeService);
        ReflectionTestUtils.setField(controller, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(controller, "academicSessionRepository", academicSessionRepository);
        ReflectionTestUtils.setField(controller, "studentEnrollmentRepository", studentEnrollmentRepository);
    }

    private ReportCardDataAssembler.HistoricalReportCardContext ctx(String className) {
        return new ReportCardDataAssembler.HistoricalReportCardContext(className, null, null, null);
    }

    @Test
    void alreadyPublishedClassNineReportCardRemainsAccessibleToStudentAfterPromotion() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(true);
        ReportCardDataDTO dto = new ReportCardDataDTO();
        dto.setClassName("9");
        when(assembler.assemble(STUDENT_ID, TEMPLATE_ID, SESSION, null)).thenReturn(dto);

        ResponseEntity<?> response = controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ReportCardDataDTO) response.getBody()).getClassName()).isEqualTo("9");
        // The bug this fixes: publication must never be checked against the student's current class.
        verify(publicationService, never()).isPublished(TEMPLATE_ID, SESSION, "10");
    }

    @Test
    void alreadyPublishedClassNineReportCardRemainsAccessibleToParentAfterPromotion() {
        when(securityUtil.getRole()).thenReturn(Role.PARENT);
        // assertChildAccess is void and does nothing when the parent is authorized for this child.
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(true);
        ReportCardDataDTO dto = new ReportCardDataDTO();
        dto.setClassName("9");
        when(assembler.assemble(STUDENT_ID, TEMPLATE_ID, SESSION, null)).thenReturn(dto);

        ResponseEntity<?> response = controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(parentPortalService).assertChildAccess(STUDENT_ID, ParentPortalService.ChildPermission.RESULTS);
    }

    @Test
    void unpublishedReportCardIsStillBlockedForStudent() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(false);

        assertThatThrownBy(() -> controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
    }

    @Test
    void studentCannotAccessAnotherStudentsReportCardRegardlessOfPublicationStatus() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn("SOMEONE_ELSE");

        assertThatThrownBy(() -> controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.FORBIDDEN);
        verify(assembler, never()).resolveHistoricalContext(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publicationLookupCannotAccidentallyMatchAnotherClass() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(true);
        ReportCardDataDTO dto = new ReportCardDataDTO();
        when(assembler.assemble(STUDENT_ID, TEMPLATE_ID, SESSION, null)).thenReturn(dto);

        controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null);

        // The publication check must be scoped to exactly the resolved historical class — never
        // any other class name (e.g. a same-grade differently-named section like "9A").
        verify(publicationService).isPublished(TEMPLATE_ID, SESSION, "9");
        verify(publicationService, never())
                .isPublished(eq(TEMPLATE_ID), eq(SESSION), argThat(className -> !"9".equals(className)));
    }

    @Test
    void unknownStudentResolvesToNotFoundRatherThanLeakingAnInternalError() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null))
                .thenThrow(new NoSuchElementException("Student not found: " + STUDENT_ID));

        assertThatThrownBy(() -> controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
    }

    @Test
    void twoLegitimateClassContextsSurfaceAsAStructuredConflictRatherThanAnArbitraryChoice() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null))
                .thenThrow(new ReportCardDataAssembler.ReportCardContextAmbiguousException(Set.of("9", "10")));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        com.indraacademy.ias_management.entity.SchoolClass class9 = new com.indraacademy.ias_management.entity.SchoolClass();
        class9.setId(9L);
        class9.setName("9");
        com.indraacademy.ias_management.entity.SchoolClass class10 = new com.indraacademy.ias_management.entity.SchoolClass();
        class10.setId(10L);
        class10.setName("10");
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "9")).thenReturn(java.util.Optional.of(class9));
        when(schoolClassRepository.findBySchoolIdAndName(SCHOOL_ID, "10")).thenReturn(java.util.Optional.of(class10));

        ResponseEntity<?> response = controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("ambiguous", true);
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> candidates = (List<java.util.Map<String, Object>>) body.get("candidates");
        assertThat(candidates).extracting(c -> c.get("className")).containsExactlyInAnyOrder("9", "10");
        assertThat(candidates).extracting(c -> c.get("classId")).containsExactlyInAnyOrder(9L, 10L);
    }

    @Test
    void explicitClassIdIsForwardedToResolveTheAmbiguity() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, 9L)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(true);
        ReportCardDataDTO dto = new ReportCardDataDTO();
        dto.setClassName("9");
        when(assembler.assemble(STUDENT_ID, TEMPLATE_ID, SESSION, 9L)).thenReturn(dto);

        ResponseEntity<?> response = controller.getReportCard(STUDENT_ID, TEMPLATE_ID, SESSION, 9L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ReportCardDataDTO) response.getBody()).getClassName()).isEqualTo("9");
    }

    @Test
    void verificationTokenLookupOnPdfDownloadUsesTheHistoricalPublishedClassFromTheAssembledDto() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn(STUDENT_ID);
        when(assembler.resolveHistoricalContext(STUDENT_ID, SESSION, null)).thenReturn(ctx("9"));
        when(publicationService.isPublished(TEMPLATE_ID, SESSION, "9")).thenReturn(true);

        ReportCardDataDTO dto = new ReportCardDataDTO();
        dto.setClassName("9"); // historical class, as set by the (already-fixed) assembler
        dto.setStudentName("Promoted Pat");
        when(assembler.assemble(STUDENT_ID, TEMPLATE_ID, SESSION, null)).thenReturn(dto);
        when(publicationService.getVerificationToken(TEMPLATE_ID, SESSION, "9"))
                .thenReturn(java.util.Optional.of("token-abc"));
        when(pdfGenerator.generate(dto)).thenReturn(new byte[]{1, 2, 3});

        ResponseEntity<?> response = controller.downloadPdf(STUDENT_ID, TEMPLATE_ID, SESSION, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dto.getVerificationToken()).isEqualTo("token-abc");
        verify(publicationService, never()).getVerificationToken(TEMPLATE_ID, SESSION, "10");
    }
}
