package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO.MarksTableDTO;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.lowagie.text.pdf.PRStream;
import com.lowagie.text.pdf.PdfNumber;
import com.lowagie.text.pdf.PdfObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Report Card Phase 0: the shared PDF generator — output, QR, rank and concurrency safety. */
class ReportCardPdfGeneratorTest {

    private final ReportCardPdfGenerator generator = new ReportCardPdfGenerator();
    {
        ReflectionTestUtils.setField(generator, "frontendUrl", "https://edunexify.co.in");
    }

    private static ReportCardDataDTO card(String name, String studentId, int rank, String token, String... subjects) {
        List<MarksTableDTO.ExamColumnDTO> cols = List.of(new MarksTableDTO.ExamColumnDTO(1L, "Half Yearly", 100.0 * subjects.length, 1.0));
        List<MarksTableDTO.SubjectRowDTO> rows = new ArrayList<>();
        List<WeightedGroupResultDTO.SubjectWeightedResultDTO> results = new ArrayList<>();
        for (String s : subjects) {
            MarksTableDTO.SubjectRowDTO row = new MarksTableDTO.SubjectRowDTO(s, List.of(new MarksTableDTO.SubjectExamMarkDTO(82.0, 100, 82.0)), 82.0);
            row.setGrade("A2");
            rows.add(row);
            results.add(new WeightedGroupResultDTO.SubjectWeightedResultDTO(s, 82.0));
        }
        WeightedGroupResultDTO wr = new WeightedGroupResultDTO(1L, "Half Yearly", "EXAM_BASED", 82.0, results, null, null,
                new MarksTableDTO(cols, rows, List.of(new MarksTableDTO.ExamTotalDTO(82.0 * subjects.length, 100.0 * subjects.length))), rank);
        ReportCardDataDTO d = new ReportCardDataDTO();
        d.setStudentId(studentId);
        d.setStudentName(name);
        d.setClassName("9");
        d.setSectionName("A");
        d.setSession("2026-2027");
        d.setSchoolName("Test Public School");
        d.setGradingSystem("CBSE");
        d.setOverallGrade("A2");
        d.setWeightedResult(wr);
        d.setAttendance(new ReportCardDataDTO.AttendanceBlock(100, 92, 92.0));
        d.setVerificationToken(token);
        return d;
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder sb = new StringBuilder();
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int p = 1; p <= reader.getNumberOfPages(); p++) sb.append(extractor.getTextFromPage(p)).append('\n');
            return sb.toString().replaceAll("\\s+", " ");   // the extractor pads words with spaces
        } finally {
            reader.close();
        }
    }

    private static int images(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary xobjects = resources != null ? resources.getAsDict(PdfName.XOBJECT) : null;
            return xobjects == null ? 0 : xobjects.size();
        } finally {
            reader.close();
        }
    }

    /**
     * Decodes every image on page 1 as a QR code and returns the texts found — proving the QR is
     * really embedded in the PDF and what it points to.
     */
    static List<String> qrTexts(byte[] pdf) throws Exception {
        List<String> found = new ArrayList<>();
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfDictionary resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            PdfDictionary xobjects = resources != null ? resources.getAsDict(PdfName.XOBJECT) : null;
            if (xobjects == null) return found;
            for (PdfName key : xobjects.getKeys()) {
                PdfObject obj = PdfReader.getPdfObject(xobjects.get(key));
                if (!(obj instanceof PRStream stream) || !PdfName.IMAGE.equals(stream.getAsName(PdfName.SUBTYPE))) continue;
                int w = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.WIDTH))).intValue();
                int h = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.HEIGHT))).intValue();
                int bpc = ((PdfNumber) PdfReader.getPdfObject(stream.get(PdfName.BITSPERCOMPONENT))).intValue();
                byte[] data = PdfReader.getStreamBytes(stream);
                int channels = data.length >= w * h * 3 && bpc == 8 ? 3 : 1;
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                int rowBytes = bpc == 1 ? (w + 7) / 8 : w * channels;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int v;
                        if (bpc == 1) v = ((data[y * rowBytes + x / 8] >> (7 - x % 8)) & 1) == 1 ? 255 : 0;
                        else v = data[y * rowBytes + x * channels] & 0xff;
                        img.setRGB(x, y, (v << 16) | (v << 8) | v);
                    }
                }
                try {
                    found.add(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                            new BufferedImageLuminanceSource(img)))).getText());
                } catch (com.google.zxing.NotFoundException notAQr) { /* some other image */ }
            }
            return found;
        } finally {
            reader.close();
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try { return reader.getNumberOfPages(); } finally { reader.close(); }
    }

    @Test
    void singleCardRendersOnOneA4PageWithMarksRankAndQr() throws Exception {
        byte[] pdf = generator.generate(card("Aarav Sharma", "S1", 3, "abcd1234-0000-4000-8000-000000000001", "Mathematics", "Science", "English"));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pages(pdf)).isEqualTo(1);
        String t = text(pdf);
        assertThat(t).contains("Aarav Sharma", "Mathematics", "Science", "English");
        assertThat(t).contains("Verify this report card").contains("Ref ABCD1234");
        assertThat(t).doesNotContain("0000-4000-8000");        // only the short reference is printed
        assertThat(images(pdf)).isGreaterThan(images(generator.generate(card("Aarav Sharma", "S1", 3, null, "Mathematics"))));
    }

    @Test
    void rankFromTheCanonicalCalculationIsPrinted() throws Exception {
        String ranked = text(generator.generate(card("Aarav", "S1", 7, null, "Mathematics")));
        String unranked = text(generator.generate(card("Aarav", "S1", 0, null, "Mathematics")));
        assertThat(ranked).contains("7");
        assertThat(unranked).contains("—");
        assertThat(ranked.replace("7", "")).doesNotContain("—\n7");
    }

    @Test
    void unpublishedCardHasNoQr() throws Exception {
        byte[] pdf = generator.generate(card("Aarav", "S1", 1, null, "Mathematics"));
        assertThat(text(pdf)).doesNotContain("Verify this report card");
        assertThat(qrTexts(pdf)).isEmpty();
    }

    @Test
    void publishedCardWithATokenEmbedsAQrThatOpensThePublicVerificationPage() throws Exception {
        String token = "5f2c9a1e-7b3d-4c8e-9f10-2a3b4c5d6e7f";
        byte[] pdf = generator.generate(card("Aarav", "S1", 1, token, "Mathematics", "Science"));
        assertThat(qrTexts(pdf)).containsExactly("https://edunexify.co.in/verify-rc?token=" + token);
        assertThat(text(pdf)).contains("Verify this report card").contains("Ref 5F2C9A1E");
        assertThat(pages(pdf)).isEqualTo(1);
    }

    @Test
    void titleNamesTheExamOrTheWholeSession() throws Exception {
        ReportCardDataDTO exam = card("Aarav", "S1", 1, null, "Mathematics");
        exam.setReportTitle("HALF YEARLY \u2014 REPORT CARD");
        ReportCardDataDTO annual = card("Aarav", "S1", 1, null, "Mathematics");
        annual.setReportTitle("ANNUAL REPORT CARD");
        String examText = text(generator.generate(exam));
        assertThat(examText).contains("H A L F Y E A R L Y \u2014 R E P O R T C A R D").doesNotContain("A N N U A L");
        assertThat(examText).contains("Academic Session 2026-2027");
        assertThat(text(generator.generate(annual))).contains("A N N U A L R E P O R T C A R D");
        assertThat(ReportCardPdfGenerator.letterSpaced("ANNUAL REPORT CARD")).isEqualTo("A N N U A L     R E P O R T     C A R D");
        assertThat(ReportCardPdfGenerator.letterSpaced("PERIODIC ASSESSMENT TWO \u2014 REPORT CARD")).doesNotContain("P E R");  // long: plain
    }

    @Test
    void concurrentGenerationsNeverMixDocumentState() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                final int n = i;
                jobs.add(() -> {
                    String token = String.format("%08x-1111-4000-8000-%012d", n, n);
                    byte[] pdf = generator.generate(card("Student Number " + n, "S" + n, n + 1, token, "Mathematics", "Science"));
                    String t = text(pdf);
                    String ref = String.format("Ref %08X", n);
                    // Its own name, rank and QR reference — nobody else's — on one page.
                    boolean foreign = false;
                    for (int m = 0; m < 40; m++) {
                        if (m == n) continue;
                        if (t.contains(String.format("Ref %08X", m)) || t.contains("Name Student Number " + m + "Class")) foreign = true;
                    }
                    return t.contains("Name Student Number " + n + "Class") && t.contains(ref)
                            && t.contains("Grade " + (n + 1) + " Class Rank") && !foreign && pages(pdf) == 1;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(jobs)) assertThat(f.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void verificationUrlResolvesToThePublicVerificationPage() {
        assertThat(generator.verificationUrl("tok-1")).isEqualTo("https://edunexify.co.in/verify-rc?token=tok-1");
        ReportCardPdfGenerator unresolved = new ReportCardPdfGenerator();
        ReflectionTestUtils.setField(unresolved, "frontendUrl", "${FRONTEND_URL}");
        assertThat(unresolved.verificationUrl("tok-1")).isEqualTo("https://edunexify.co.in/verify-rc?token=tok-1");
        ReportCardPdfGenerator staging = new ReportCardPdfGenerator();
        ReflectionTestUtils.setField(staging, "frontendUrl", "https://staging.example.com/");
        assertThat(staging.verificationUrl("tok-1")).isEqualTo("https://staging.example.com/verify-rc?token=tok-1");
        assertThat(ReportCardPdfGenerator.verificationReference("abcd1234-aaaa")).isEqualTo("ABCD1234");
        assertThat(ReportCardPdfGenerator.verificationReference(null)).isNull();
    }
}
