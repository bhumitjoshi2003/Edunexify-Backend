package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.RemarksRequest;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.service.TeacherClassScopeService.ScopedAccess;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Report Card Phase 0: principal-remark authorization and the results-card PDF access rules. */
@ExtendWith(MockitoExtension.class)
class ReportCardPhase0SecurityTest {

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
    @InjectMocks private ReportCardController controller;

    private static RemarksRequest remarks(String teacher, String principal) {
        RemarksRequest.StudentRemarkItem item = new RemarksRequest.StudentRemarkItem();
        item.setStudentId("S1");
        item.setTeacherRemark(teacher);
        item.setPrincipalRemark(principal);
        RemarksRequest req = new RemarksRequest();
        req.setTemplateId(100L);
        req.setSession("2026-2027");
        req.setStudentRemarks(List.of(item));
        return req;
    }

    private void asTeacherOfS1() {
        when(securityUtil.getRole()).thenReturn(Role.TEACHER);
        lenient().when(securityUtil.getUsername()).thenReturn("T1");
        lenient().when(securityUtil.getSchoolId()).thenReturn(4L);
        Student s = new Student();
        s.setClassName("9");
        s.setSectionId(3L);
        lenient().when(studentRepository.findByStudentIdAndSchoolId("S1", 4L)).thenReturn(Optional.of(s));
        lenient().when(teacherClassScopeService.authorizeAndScopeToStudent(Role.TEACHER, "T1", 4L, "9", 3L))
                .thenReturn(ScopedAccess.allow(3L));
    }

    // ── Principal remarks ─────────────────────────────────────────────────

    @Test
    void teacherCannotSubmitAPrincipalRemark() {
        asTeacherOfS1();
        assertThatThrownBy(() -> controller.saveRemarks(remarks("Good work", "Excellent")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.saveRemarks(remarks(null, "")))
                .isInstanceOf(ResponseStatusException.class);   // even blanking it is refused
        verifyNoInteractions(remarksService);
    }

    @Test
    void teacherMaySaveTheClassTeacherRemark() {
        asTeacherOfS1();
        RemarksRequest req = remarks("Good work", null);
        assertThat(controller.saveRemarks(req).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(remarksService).saveRemarks(req);
    }

    @Test
    void adminMaySaveBothRemarks() {
        when(securityUtil.getRole()).thenReturn(Role.ADMIN);
        RemarksRequest req = remarks("Good work", "Excellent");
        assertThat(controller.saveRemarks(req).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(remarksService).saveRemarks(req);
    }

    // ── Results-card PDF (no template): what Class Results / My Results open ─

    private ReportCardDataDTO dto() {
        ReportCardDataDTO d = new ReportCardDataDTO();
        d.setStudentName("Aarav");
        return d;
    }

    @Test
    void studentGetsTheirOwnPublishedResultsPdf() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn("S1");
        ReportCardDataDTO d = dto();
        when(assembler.assembleFromResults("S1", "2026-2027", 7L, null, false)).thenReturn(d);
        when(pdfGenerator.generate(d)).thenReturn(new byte[]{'%', 'P'});

        ResponseEntity<?> res = controller.downloadPdf("S1", null, "2026-2027", null, 7L);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        verify(assembler).assembleFromResults("S1", "2026-2027", 7L, null, false);   // published exams only
    }

    @Test
    void studentCannotGetAnotherStudentsPdf() {
        when(securityUtil.getRole()).thenReturn(Role.STUDENT);
        when(securityUtil.getUsername()).thenReturn("S1");
        assertThatThrownBy(() -> controller.downloadPdf("S2", null, "2026-2027", null, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.downloadPdf("S2", 100L, "2026-2027", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(pdfGenerator);
    }

    @Test
    void parentNeedsResultsAccessToTheChild() {
        when(securityUtil.getRole()).thenReturn(Role.PARENT);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no")).when(parentPortalService)
                .assertChildAccess("S9", ParentPortalService.ChildPermission.RESULTS);
        assertThatThrownBy(() -> controller.downloadPdf("S9", null, "2026-2027", null, null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(pdfGenerator);
    }

    @Test
    void teacherSeesDraftsOnlyForTheirOwnClass() {
        asTeacherOfS1();
        ReportCardDataDTO d = dto();
        when(assembler.assembleFromResults("S1", "2026-2027", null, null, true)).thenReturn(d);
        when(pdfGenerator.generate(d)).thenReturn(new byte[]{1});
        assertThat(controller.downloadPdf("S1", null, "2026-2027", null, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Student other = new Student();
        other.setClassName("10");
        other.setSectionId(8L);
        when(studentRepository.findByStudentIdAndSchoolId("S7", 4L)).thenReturn(Optional.of(other));
        when(teacherClassScopeService.authorizeAndScopeToStudent(Role.TEACHER, "T1", 4L, "10", 8L))
                .thenReturn(ScopedAccess.deny("Not your class"));
        assertThatThrownBy(() -> controller.downloadPdf("S7", null, "2026-2027", null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void noResultsYetIsNotFound() {
        when(securityUtil.getRole()).thenReturn(Role.ADMIN);
        when(assembler.assembleFromResults(anyString(), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new java.util.NoSuchElementException("No results"));
        assertThatThrownBy(() -> controller.downloadPdf("S1", null, "2026-2027", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
