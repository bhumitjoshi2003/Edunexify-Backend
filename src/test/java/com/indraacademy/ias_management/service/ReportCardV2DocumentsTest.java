package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.entity.SchoolReportCardDesign;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** One report-card design: Phase 0 cards (template or results) are drawn by the V2 renderer. */
class ReportCardV2DocumentsTest {

    private static ReportCardDataDTO card(String token) throws Exception {
        Method m = ReportCardPdfGeneratorTest.class.getDeclaredMethod("card", String.class, String.class, int.class, String.class, String[].class);
        m.setAccessible(true);
        return (ReportCardDataDTO) m.invoke(null, "Aarav Sharma", "S1", 3, token, new String[]{"Mathematics", "Science"});
    }

    private static ReportCardPdfGenerator wiredGenerator() {
        ReportCardPdfGenerator gen = new ReportCardPdfGenerator();
        ReflectionTestUtils.setField(gen, "frontendUrl", "https://edunexify.co.in");
        ReportCardDesignService design = mock(ReportCardDesignService.class);
        when(design.designFor(7L)).thenReturn(SchoolReportCardDesign.defaults(7L));
        SecurityUtil security = mock(SecurityUtil.class);
        when(security.getSchoolId()).thenReturn(7L);
        ReflectionTestUtils.setField(gen, "v2Renderer", new OpenHtmlToPdfReportCardRenderer());
        ReflectionTestUtils.setField(gen, "designService", design);
        ReflectionTestUtils.setField(gen, "securityUtil", security);
        return gen;
    }

    @Test
    void phase0CardIsRenderedInTheV2DesignWithItsDataAndQr() throws Exception {
        String token = "abcd1234-0000-4000-8000-000000000001";
        ReportCardDataDTO data = card(token);
        data.setReportTitle("HALF YEARLY — REPORT CARD");
        byte[] pdf = wiredGenerator().generate(data);

        String text = ReportCardV2RendererTest.pages(pdf).stream().map(ReportCardV2RendererTest.Page::text).reduce("", String::concat).replaceAll("\\s+", " ");
        assertThat(text).contains("HALF YEARLY", "ACADEMIC PERFORMANCE", "Aarav Sharma", "Mathematics", "82 / 100", "82.00%", "A2");
        assertThat(text).contains("RANK IN SECTION").contains("Verify this report card");
        // Phase 0 QR behaviour unchanged: same public URL and token.
        assertThat(ReportCardPdfGeneratorTest.qrTexts(pdf)).containsExactly("https://edunexify.co.in/verify-rc?token=" + token);
    }

    @Test
    void noTokenMeansNoQrAndMissingMarksStayAbsent() throws Exception {
        ReportCardDataDTO data = card(null);
        var mt = data.getWeightedResult().getMarksTable();
        var absent = new com.indraacademy.ias_management.dto.WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO("Mathematics",
                java.util.List.of(new com.indraacademy.ias_management.dto.WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO(null, 100, 0)), 0);
        java.util.List<com.indraacademy.ias_management.dto.WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO> rows = new java.util.ArrayList<>(mt.getSubjectRows());
        rows.set(0, absent);
        ReflectionTestUtils.setField(mt, "subjectRows", rows);
        ReportCardV2ViewModel vm = ReportCardV2Documents.fromPhase0(data, SchoolReportCardDesign.defaults(7L), null, null);
        assertThat(vm.hasVerification()).isFalse();
        assertThat(vm.rows().get(0).cells().get(0).text()).isEqualTo("Ab");
        assertThat(ReportCardPdfGeneratorTest.qrTexts(new OpenHtmlToPdfReportCardRenderer().render(vm))).isEmpty();
    }

    @Test
    void withoutTheV2BeansTheLegacyLayoutIsUsed() throws Exception {
        ReportCardPdfGenerator plain = new ReportCardPdfGenerator();
        ReflectionTestUtils.setField(plain, "frontendUrl", "https://edunexify.co.in");
        String text = ReportCardV2RendererTest.pages(plain.generate(card(null))).stream().map(ReportCardV2RendererTest.Page::text).reduce("", String::concat);
        assertThat(text).doesNotContain("ACADEMIC PERFORMANCE");
    }
}
