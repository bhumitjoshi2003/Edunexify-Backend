package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO.MarksTableDTO;
import com.indraacademy.ias_management.entity.SchoolReportCardDesign;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Presents a Phase 0 report card (template or results-based) in the Report Card V2 document, so
 * every report card — Half Yearly, Annual, Final — has one design. Display only: every number,
 * grade and rank is taken as already calculated by the Phase 0 assembler; nothing is recalculated
 * here except summing the marks already shown in a row for its Total column.
 */
final class ReportCardV2Documents {
    private ReportCardV2Documents() {}

    static ReportCardV2ViewModel fromPhase0(ReportCardDataDTO d, SchoolReportCardDesign design, String qr, String ref) {
        WeightedGroupResultDTO wr = d.getWeightedResult();
        MarksTableDTO mt = wr != null ? wr.getMarksTable() : null;
        boolean template = wr != null && !"RESULTS".equals(wr.getGroupType());

        List<ReportCardV2ViewModel.ColumnHeader> columns = new ArrayList<>();
        List<ReportCardV2ViewModel.SubjectRow> rows = new ArrayList<>();
        List<String> totalCells = new ArrayList<>();
        double allObt = 0, allMax = 0;
        if (mt != null) {
            int cols = mt.getExamColumns().size();
            String sep = cols >= 5 ? "/" : " / ";
            for (MarksTableDTO.ExamColumnDTO c : mt.getExamColumns()) {
                boolean showWeight = template && c.getWeightage() > 0 && c.getWeightage() < 0.9999;
                columns.add(new ReportCardV2ViewModel.ColumnHeader(c.getExamName(), showWeight ? pctLabel(c.getWeightage() * 100) : null));
            }
            for (MarksTableDTO.SubjectRowDTO r : mt.getSubjectRows()) {
                List<ReportCardV2ViewModel.Cell> cells = new ArrayList<>();
                double obt = 0, max = 0;
                for (MarksTableDTO.SubjectExamMarkDTO m : r.getExamMarks()) {
                    if (m == null) { cells.add(ReportCardV2ViewModel.Cell.of("—")); continue; }
                    // Phase 0 cards count a missing mark as absent ("Ab"), exactly as before.
                    cells.add(m.getObtained() == null ? new ReportCardV2ViewModel.Cell("Ab", "pending")
                            : ReportCardV2ViewModel.Cell.of(ReportCardV2Service.marks(m.getObtained()) + sep + ReportCardV2Service.marks(m.getMax())));
                    max += m.getMax();
                    if (m.getObtained() != null) obt += m.getObtained();
                }
                rows.add(new ReportCardV2ViewModel.SubjectRow(r.getSubjectName(), cells,
                        max > 0 ? ReportCardV2Service.marks(obt) + sep + ReportCardV2Service.marks(max) : "—",
                        ReportCardV2Service.pct(r.getWeightedPercentage()), r.getGrade() != null ? r.getGrade() : "—"));
            }
            for (MarksTableDTO.ExamTotalDTO t : mt.getExamTotals()) {
                totalCells.add(ReportCardV2Service.marks(t.getObtained()) + sep + ReportCardV2Service.marks(t.getMax()));
                allObt += t.getObtained();
                allMax += t.getMax();
            }
        }
        double pct = wr != null ? wr.getWeightedPercentage() : 0;
        String grade = d.getOverallGrade() != null ? d.getOverallGrade() : "—";
        ReportCardV2ViewModel.TotalsRow totals = new ReportCardV2ViewModel.TotalsRow(totalCells,
                allMax > 0 ? ReportCardV2Service.marks(allObt) + " / " + ReportCardV2Service.marks(allMax) : "—",
                wr != null ? ReportCardV2Service.pct(pct) : "—", grade);
        int missing = wr != null ? wr.getMarksMissing() : 0;
        ReportCardV2ViewModel.Summary summary = new ReportCardV2ViewModel.Summary(
                wr != null ? ReportCardV2Service.pct(pct) : "—", grade,
                wr != null && wr.getRank() > 0 ? String.valueOf(wr.getRank()) : "—",
                wr == null ? "No result" : GradingPolicy.passed(pct) ? "Pass" : "Fail",
                missing > 0 ? missing + " mark" + (missing == 1 ? "" : "s") + " not entered — counted as absent (Ab)." : null,
                null);

        ReportCardV2ViewModel.Attendance attendance = null;
        if (design.isShowAttendance() && d.getAttendance() != null) {
            ReportCardDataDTO.AttendanceBlock a = d.getAttendance();
            attendance = new ReportCardV2ViewModel.Attendance(String.valueOf(a.getWorkingDays()), String.valueOf(a.getPresentDays()),
                    a.getWorkingDays() > 0 ? new DecimalFormat("0.#").format(a.getPercentage()) + "%" : "—");
        }
        List<ReportCardV2ViewModel.CoScholastic> co = null;
        if (design.isShowCoScholastic() && d.getCoScholasticGrades() != null && !d.getCoScholasticGrades().isEmpty()) {
            co = d.getCoScholasticGrades().stream()
                    .map(g -> new ReportCardV2ViewModel.CoScholastic(g.getActivity(), g.getGrade() != null && !g.getGrade().isBlank() ? g.getGrade() : "—"))
                    .toList();
        }

        List<String> affiliation = new ArrayList<>();
        if (d.getBoardType() != null) affiliation.add(switch (d.getBoardType()) {
            case "CBSE" -> "CBSE Affiliated"; case "ICSE" -> "ICSE Affiliated"; case "STATE" -> "State Board"; default -> d.getBoardType();
        });
        if (notBlank(d.getAffiliationNumber())) affiliation.add("Affiliation No. " + d.getAffiliationNumber().trim());
        if (notBlank(d.getSchoolCode())) affiliation.add("School Code " + d.getSchoolCode().trim());
        List<String> address = new ArrayList<>();
        if (notBlank(d.getSchoolAddress())) address.add(d.getSchoolAddress().trim());
        if (notBlank(d.getSchoolCity())) address.add(d.getSchoolCity().trim());

        int rowsCount = rows.size(), cols = columns.size();
        String watermarkText = design.getWatermarkMode() == SchoolReportCardDesign.WatermarkMode.TEXT
                ? (design.getWatermarkText() != null ? design.getWatermarkText() : d.getSchoolName()) : null;
        return new ReportCardV2ViewModel(
                new ReportCardV2ViewModel.SchoolBlock(d.getSchoolName(), address.isEmpty() ? null : String.join(", ", address),
                        affiliation.isEmpty() ? null : String.join("  ·  ", affiliation), design.getMotto(),
                        d.getSchoolLogoUrl(), d.getReportCardHeaderImageUrl()),
                d.getReportTitle() != null ? d.getReportTitle() : "REPORT CARD", d.getSession(),
                new ReportCardV2ViewModel.StudentBlock(d.getStudentName(), d.getStudentId(), d.getClassName(), d.getSectionName(),
                        d.getDateOfBirth(), blankToNull(d.getFatherName()), blankToNull(d.getMotherName()),
                        design.isShowPhoto() ? d.getPhotoUrl() : null),
                List.of(), columns, rows, totals, summary, attendance, co,
                d.getTeacherRemarks(), d.getPrincipalRemarks(),
                new ReportCardV2ViewModel.Flags(design.isShowPhoto(), attendance != null, co != null,
                        design.isShowTeacherRemark(), design.isShowPrincipalRemark(), design.isShowRank(), design.isShowPromotion()),
                design.getTeacherSignatureLabel(), design.getPrincipalSignatureLabel(), design.getFooterText(), watermarkText,
                design.getWatermarkMode() == SchoolReportCardDesign.WatermarkMode.LOGO ? "LOGO" : null,
                rowsCount <= 8 && cols <= 3 ? "normal" : "compact",
                rowsCount > 11 || (rowsCount > 9 && cols >= 5),
                null,
                // Phase 0 QR behaviour is unchanged: a published card with a token always shows it.
                qr, ref, null);
    }

    private static String pctLabel(double v) { return new DecimalFormat("0.##").format(v) + "%"; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String blankToNull(String s) { return notBlank(s) ? s.trim() : null; }
}
