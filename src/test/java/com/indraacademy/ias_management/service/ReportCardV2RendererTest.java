package com.indraacademy.ias_management.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report Card V2 renderer (OpenHTMLtoPDF): the spike matrix — English and Devanagari, 6/10/14
 * subjects × 1–6 exam columns — page counts, repeated table header, rows never split, signatures
 * only at the bottom of the final page with nothing overlapping them, and concurrent rendering.
 * Set OUT_DIR to keep the PDFs for visual review.
 */
class ReportCardV2RendererTest {

    private final OpenHtmlToPdfReportCardRenderer renderer = new OpenHtmlToPdfReportCardRenderer();

    static final String[] SUBJECTS = {"English", "हिन्दी", "Mathematics", "विज्ञान", "Social Science", "संस्कृत",
            "Computer Science", "Physical Education", "Art", "Music", "General Knowledge", "Environmental Studies",
            "Moral Science", "Work Education"};

    static ReportCardV2ViewModel model(String studentName, int subjects, int exams, boolean terms) {
        List<ReportCardV2ViewModel.ColumnHeader> cols = new ArrayList<>();
        for (int e = 0; e < exams; e++) cols.add(new ReportCardV2ViewModel.ColumnHeader(new String[]{"Periodic Test 1", "Half Yearly",
                "Periodic Test 2", "Annual", "Unit Test 3", "Final"}[e], null));
        List<ReportCardV2ViewModel.TermHeader> termHeaders = new ArrayList<>();
        if (terms && exams >= 2) {
            termHeaders.add(new ReportCardV2ViewModel.TermHeader("Term 1", exams / 2, "50%"));
            termHeaders.add(new ReportCardV2ViewModel.TermHeader("Term 2", exams - exams / 2, "50%"));
        }
        List<ReportCardV2ViewModel.SubjectRow> rows = new ArrayList<>();
        for (int s = 0; s < subjects; s++) {
            List<ReportCardV2ViewModel.Cell> cells = new ArrayList<>();
            for (int e = 0; e < exams; e++) cells.add(ReportCardV2ViewModel.Cell.of(s == 3 && e == 0 ? "—" : (70 + (s + e) % 25) + " / 100"));
            rows.add(new ReportCardV2ViewModel.SubjectRow(SUBJECTS[s], cells, "SUBJ-TOTAL", "82.50%", "A2"));
        }
        List<String> totals = new ArrayList<>();
        for (int e = 0; e < exams; e++) totals.add("600 / 700");
        return new ReportCardV2ViewModel(
                new ReportCardV2ViewModel.SchoolBlock("Test Public School", "12 Civil Lines, Lucknow", "CBSE Affiliated  ·  Affiliation No. 2130456", "Scientia · Disciplina", null, null),
                "ANNUAL REPORT CARD", "2026-2027",
                new ReportCardV2ViewModel.StudentBlock(studentName, "S102", "9", "A", "29 Jun 2012", "राजेश शर्मा", "Sunita Sharma", null),
                termHeaders, cols, rows, new ReportCardV2ViewModel.TotalsRow(totals, "4200 / 5000", "84.00%", "A2"),
                new ReportCardV2ViewModel.Summary("84.00%", "A2", "3", "Pass", null, terms ? "Weighted result: Term 1 50%, Term 2 50%" : null),
                new ReportCardV2ViewModel.Attendance("200", "188", "94%"),
                List.of(new ReportCardV2ViewModel.CoScholastic("Drawing", "A"), new ReportCardV2ViewModel.CoScholastic("Games", "B"),
                        new ReportCardV2ViewModel.CoScholastic("Discipline", "A"), new ReportCardV2ViewModel.CoScholastic("Values", "A")),
                "Aarav is attentive and works hard. आरव मेहनती है।", "Keep it up.",
                new ReportCardV2ViewModel.Flags(true, true, true, true, true, true, false),
                "Class Teacher", "Principal", "This is a computer-generated report card.", null, null,
                subjects <= 8 && exams <= 3 ? "normal" : "compact",
                subjects > 11 || (subjects > 9 && exams >= 5),
                "Live preview — not a published report card", null, null, null);
    }

    record Page(String text, float lowestContentY, float signatureY) {}

    /** Text per page plus the lowest body-text line and the signature-label line (y grows downwards). */
    static List<Page> pages(byte[] pdf) throws Exception {
        List<Page> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                float[] lowest = {0};
                float[] sign = {-1};
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
                        float y = positions.get(0).getYDirAdj();
                        if (text.contains("Class Teacher") && !text.contains("REMARKS")) sign[0] = Math.max(sign[0], y);
                        else if (!text.startsWith("Page ") && !text.contains("computer-generated") && !text.contains("Live preview")
                                && !text.equals("Principal")) lowest[0] = Math.max(lowest[0], y);
                        super.writeString(text, positions);
                    }
                };
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                out.add(new Page(text, lowest[0], sign[0]));
            }
        }
        return out;
    }

    private byte[] render(String name, int subjects, int exams, boolean terms) throws Exception {
        byte[] pdf = renderer.render(model(name, subjects, exams, terms));
        String dir = System.getenv("OUT_DIR");
        if (dir != null) Files.write(Path.of(dir, "v2-" + subjects + "s-" + exams + "e" + (terms ? "-terms" : "") + ".pdf"), pdf);
        return pdf;
    }

    @Test
    void spikeMatrixPageCountsHeadersRowsAndSignatures() throws Exception {
        for (int subjects : new int[]{6, 10, 14}) {
            for (int exams = 1; exams <= 6; exams++) {
                byte[] pdf = render("Aarav Sharma", subjects, exams, exams >= 4);
                List<Page> pages = pages(pdf);
                String ctx = subjects + " subjects × " + exams + " exams";
                if (subjects <= 10 && exams <= 4) assertThat(pages).as(ctx).hasSize(1);
                assertThat(pages.size()).as(ctx).isBetween(1, 2);
                // Every subject row appears exactly once and whole (never split across pages).
                // (Latin names only: shaped Devanagari does not extract back to its source text.)
                for (int s = 0; s < subjects; s++) {
                    String subject = SUBJECTS[s];
                    if (!subject.matches("[A-Za-z ]+")) continue;
                    long onPages = pages.stream().filter(p -> p.text().contains(subject)).count();
                    assertThat(onPages).as(ctx + " row " + subject).isEqualTo(1);
                }
                // The marks-table header is repeated on any page the table continues on.
                for (Page p : pages) {
                    boolean hasRows = Arrays.stream(SUBJECTS).limit(subjects).filter(x -> x.matches("[A-Za-z ]+"))
                            .anyMatch(x -> p.text().contains(x + " "));
                    if (hasRows) assertThat(p.text()).as(ctx).contains("Subject").contains("Grade");
                }
                // Signatures only on the final page, below all content (no overlap).
                for (int i = 0; i < pages.size(); i++) {
                    Page p = pages.get(i);
                    if (i < pages.size() - 1) assertThat(p.signatureY()).as(ctx + " page " + (i + 1)).isLessThan(0);
                    else {
                        assertThat(p.signatureY()).as(ctx + " last page signature").isGreaterThan(0);
                        assertThat(p.signatureY()).as(ctx + " signature below content").isGreaterThan(p.lowestContentY());
                    }
                }
            }
        }
    }

    @Test
    void devanagariIsEmbeddedWithTheNotoDevanagariFont() throws Exception {
        byte[] pdf = render("आरव शर्मा", 6, 2, false);
        Set<String> fonts = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (var name : doc.getPage(0).getResources().getFontNames()) {
                PDFont f = doc.getPage(0).getResources().getFont(name);
                fonts.add(f.getName());
            }
        }
        assertThat(fonts).anyMatch(f -> f.contains("NotoSansDevanagari"));
        assertThat(fonts).anyMatch(f -> f.contains("NotoSans-"));
        assertThat(fonts).noneMatch(f -> f.contains("Times") || f.contains("Helvetica"));   // no silent fallback
    }

    @Test
    void concurrentRenderingIsSafe() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (int i = 0; i < 18; i++) {
                final int n = i;
                jobs.add(() -> {
                    byte[] pdf = renderer.render(model("Student Number " + n, 6 + n % 9, 1 + n % 6, n % 2 == 0));
                    String text = pages(pdf).stream().map(Page::text).reduce("", String::concat);
                    boolean foreign = false;
                    for (int m = 0; m < 18; m++) if (m != n && text.contains("Student Number " + m + "\n")) foreign = true;
                    return text.contains("Student Number " + n) && !foreign;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(jobs)) assertThat(f.get()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
