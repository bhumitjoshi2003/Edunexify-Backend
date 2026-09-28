package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Report Card V2, Phase 1: remarks / co-scholastic entry, the Generate &amp; Preview summary and
 * the live PDF. Scope: an ADMIN sees their whole school; a TEACHER only their own class and
 * section (enforced here, server-side). Nothing is published in Phase 1 — every PDF is a live
 * preview and says so.
 */
@Service
public class ReportCardV2Service {

    private static final Logger log = LoggerFactory.getLogger(ReportCardV2Service.class);
    private static final int MAX_REMARK = 1000;
    private static final DateTimeFormatter DOB = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    @Autowired private ReportCardSetupService setupService;
    @Autowired private ReportCardV2Builder builder;
    @Autowired private ReportCardDesignService designService;
    @Autowired private ReportCardRenderer renderer;
    @Autowired private ReportCardRemarkV2Repository remarkRepo;
    @Autowired private ReportCardCoScholasticV2Repository coScholasticRepo;
    @Autowired private SchoolCoScholasticActivityRepository activityRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private AttendanceService attendanceService;
    @Autowired private ObjectStorageService objectStorageService;
    @Autowired private TeacherClassScopeService teacherScope;
    @Autowired private SecurityUtil securityUtil;
    @Autowired(required = false) private ReportCardDocumentRepository documentRepo;

    /** Whose cards the caller may see: all (null section = whole class) or one section. */
    private record Scope(boolean teacher, Long sectionId) {}

    private Scope scope(ReportCardV2Builder.ClassResult cls, Long requestedSectionId) {
        if (!Role.TEACHER.equals(securityUtil.getRole())) return new Scope(false, requestedSectionId);
        TeacherClassScopeService.ScopedAccess access = teacherScope.authorizeAndScopeToClass(Role.TEACHER,
                securityUtil.getUsername(), securityUtil.getSchoolId(), cls.schoolClass().getName(), null);
        if (!access.allowed()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.errorMessage());
        return new Scope(true, access.effectiveSectionId());
    }

    // ── Remarks & co-scholastic ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public RemarksPageDTO remarks(Long setupId, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupService.readableSetup(setupId);
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        Scope scope = scope(cls, sectionId);
        Map<String, ReportCardRemarkV2> remarks = remarksBySetup(setupId, schoolId);
        Map<String, Map<Long, String>> grades = gradesBySetup(setupId, schoolId);
        List<RemarkRowDTO> rows = cls.students().stream().filter(s -> s.inSection(scope.sectionId()))
                .map(s -> {
                    ReportCardRemarkV2 r = remarks.get(s.student().getStudentId());
                    return new RemarkRowDTO(s.student().getStudentId(), s.student().getName(), s.sectionId(),
                            cls.sectionName(s.sectionId()), r != null ? r.getTeacherRemark() : null,
                            r != null ? r.getPrincipalRemark() : null,
                            grades.getOrDefault(s.student().getStudentId(), Map.of()),
                            isLocked(setup, s.student().getStudentId()));
                }).toList();
        return new RemarksPageDTO(setup.getId(), setup.getName(), cls.schoolClass().getName(), scope.sectionId(),
                !scope.teacher(), designService.activities(true), ReportCardDesignService.CO_SCHOLASTIC_GRADES, rows);
    }

    @Transactional
    public void saveRemarks(Long setupId, RemarksSaveRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupService.readableSetup(setupId);
        if (req == null || req.students() == null || req.students().isEmpty()) return;
        boolean teacher = Role.TEACHER.equals(securityUtil.getRole());
        if (teacher && req.students().stream().anyMatch(r -> r.principalRemark() != null)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only an administrator can enter the principal's remarks.");
        }
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        Scope scope = scope(cls, null);
        Map<Long, SchoolCoScholasticActivity> activeActivities = new HashMap<>();
        activityRepo.findBySchoolIdAndActiveTrueOrderByDisplayOrderAscIdAsc(schoolId).forEach(a -> activeActivities.put(a.getId(), a));
        String user = securityUtil.getUsername();
        LocalDateTime now = LocalDateTime.now();
        Map<String, ReportCardRemarkV2> remarks = remarksBySetup(setupId, schoolId);
        Map<String, Map<Long, ReportCardCoScholasticV2>> existingGrades = new HashMap<>();
        coScholasticRepo.findBySetupIdAndSchoolId(setupId, schoolId)
                .forEach(g -> existingGrades.computeIfAbsent(g.getStudentId(), k -> new HashMap<>()).put(g.getActivityId(), g));

        for (RemarkSaveRow row : req.students()) {
            ReportCardV2Builder.StudentResult student = row.studentId() == null ? null : cls.student(row.studentId());
            if (student == null) {
                throw new IllegalArgumentException("Student " + row.studentId() + " is not part of this report card.");
            }
            if (scope.teacher() && !student.inSection(scope.sectionId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only enter remarks for students of your own class and section.");
            }
            assertEditable(setup, row.studentId());
            if (row.teacherRemark() != null || row.principalRemark() != null) {
                ReportCardRemarkV2 r = remarks.get(row.studentId());
                if (r == null) {
                    r = new ReportCardRemarkV2();
                    r.setSchoolId(schoolId);
                    r.setSetupId(setupId);
                    r.setStudentId(row.studentId());
                }
                if (row.teacherRemark() != null) {
                    r.setTeacherRemark(remarkText(row.teacherRemark()));
                    r.setTeacherUpdatedBy(user);
                    r.setTeacherUpdatedAt(now);
                }
                if (row.principalRemark() != null) {
                    r.setPrincipalRemark(remarkText(row.principalRemark()));
                    r.setPrincipalUpdatedBy(user);
                    r.setPrincipalUpdatedAt(now);
                }
                remarks.put(row.studentId(), remarkRepo.save(r));
            }
            if (row.grades() != null) {
                for (Map.Entry<Long, String> g : row.grades().entrySet()) {
                    if (!activeActivities.containsKey(g.getKey())) {
                        throw new IllegalArgumentException("Activity " + g.getKey() + " is not an active co-scholastic activity of your school.");
                    }
                    String grade = g.getValue() == null ? "" : g.getValue().trim().toUpperCase(Locale.ROOT);
                    ReportCardCoScholasticV2 current = existingGrades.getOrDefault(row.studentId(), Map.of()).get(g.getKey());
                    if (grade.isEmpty()) {
                        if (current != null) coScholasticRepo.delete(current);
                        continue;
                    }
                    if (!ReportCardDesignService.CO_SCHOLASTIC_GRADES.contains(grade)) {
                        throw new IllegalArgumentException("Co-scholastic grades are " + String.join(", ", ReportCardDesignService.CO_SCHOLASTIC_GRADES) + ".");
                    }
                    ReportCardCoScholasticV2 entity = current != null ? current : new ReportCardCoScholasticV2();
                    entity.setSchoolId(schoolId);
                    entity.setSetupId(setupId);
                    entity.setStudentId(row.studentId());
                    entity.setActivityId(g.getKey());
                    entity.setGrade(grade);
                    entity.setUpdatedBy(user);
                    coScholasticRepo.save(entity);
                }
            }
        }
    }

    /** Remarks and co-scholastic grades of a student whose card is published (ACTIVE) are locked. */
    boolean isLocked(ReportCardSetup setup, String studentId) {
        return documentRepo != null && documentRepo.existsBySetupIdAndStudentIdAndSchoolIdAndStatus(
                setup.getId(), studentId, setup.getSchoolId(), ReportCardPublicationStatus.ACTIVE);
    }

    /** Phase 2: official content cannot change under a published card — withdraw, edit, republish. */
    void assertEditable(ReportCardSetup setup, String studentId) {
        if (isLocked(setup, studentId)) {
            throw new IllegalStateException("The report card of student " + studentId
                    + " is published, so its remarks and grades are locked. Withdraw it in Published Report Cards to make changes, then republish.");
        }
    }

    private static String remarkText(String raw) {
        String t = raw.trim();
        if (t.length() > MAX_REMARK) throw new IllegalArgumentException("A remark can be at most " + MAX_REMARK + " characters.");
        return t.isEmpty() ? null : t;
    }

    // ── Generate & Preview ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SummaryDTO summary(Long setupId, Long sectionId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupService.readableSetup(setupId);
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        Scope scope = scope(cls, sectionId);
        SchoolReportCardDesign design = designService.designFor(schoolId);
        Map<String, ReportCardRemarkV2> remarks = remarksBySetup(setupId, schoolId);
        Map<String, Map<Long, String>> grades = gradesBySetup(setupId, schoolId);
        Set<Long> activeActivities = new HashSet<>();
        activityRepo.findBySchoolIdAndActiveTrueOrderByDisplayOrderAscIdAsc(schoolId).forEach(a -> activeActivities.add(a.getId()));

        List<SummaryRowDTO> rows = new ArrayList<>();
        int incomplete = 0, noResult = 0, missingTeacher = 0, missingPrincipal = 0, missingCo = 0, missingPhoto = 0;
        for (ReportCardV2Builder.StudentResult s : cls.students()) {
            if (!s.inSection(scope.sectionId())) continue;
            ReportCardRemarkV2 r = remarks.get(s.student().getStudentId());
            boolean hasTeacher = r != null && r.getTeacherRemark() != null && !r.getTeacherRemark().isBlank();
            boolean hasPrincipal = r != null && r.getPrincipalRemark() != null && !r.getPrincipalRemark().isBlank();
            Map<Long, String> g = grades.getOrDefault(s.student().getStudentId(), Map.of());
            boolean coComplete = g.keySet().containsAll(activeActivities);
            boolean hasPhoto = s.student().getPhotoUrl() != null && !s.student().getPhotoUrl().isBlank();
            if (s.status() == ReportCardV2Builder.Status.INCOMPLETE) incomplete++;
            if (s.status() == ReportCardV2Builder.Status.NO_RESULT) noResult++;
            if (design.isShowTeacherRemark() && !hasTeacher) missingTeacher++;
            if (design.isShowPrincipalRemark() && !hasPrincipal) missingPrincipal++;
            if (design.isShowCoScholastic() && !activeActivities.isEmpty() && !coComplete) missingCo++;
            if (design.isShowPhoto() && !hasPhoto) missingPhoto++;
            rows.add(new SummaryRowDTO(s.student().getStudentId(), s.student().getName(), s.sectionId(), cls.sectionName(s.sectionId()),
                    s.percentage(), s.grade(), s.rank(), s.status().name(), s.marksMissing(), hasTeacher, hasPrincipal, coComplete, hasPhoto));
        }
        List<String> drafts = cls.columns().stream().filter(c -> c.resultStatus() != ExamResultStatus.PUBLISHED)
                .map(ReportCardV2Builder.Column::examName).toList();
        ReadinessDTO readiness = new ReadinessDTO(drafts, rows.size(), incomplete, noResult, missingTeacher, missingPrincipal, missingCo, missingPhoto);
        return new SummaryDTO(setup.getId(), setup.getName(), setup.getResultMode(), cls.schoolClass().getName(),
                cls.session().getLabel(), scope.sectionId(), scope.teacher(), readiness, rows);
    }

    /** The live V2 PDF of one student (admin: any student of the card; teacher: own class/section). */
    @Transactional(readOnly = true)
    public Pdf previewPdf(Long setupId, String studentId) {
        ReportCardSetup setup = setupService.readableSetup(setupId);
        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        Scope scope = scope(cls, null);
        ReportCardV2Builder.StudentResult student = cls.student(studentId);
        if (student == null) throw new NoSuchElementException("Student " + studentId + " is not part of this report card.");
        if (scope.teacher() && !student.inSection(scope.sectionId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only preview report cards of your own class and section.");
        }
        byte[] pdf = renderer.render(viewModel(cls, student));
        String name = (student.student().getName() == null ? studentId : student.student().getName()).replaceAll("[^A-Za-z0-9]+", "_");
        return new Pdf(pdf, name + "_" + setup.getName().replaceAll("[^A-Za-z0-9]+", "_") + "_" + cls.session().getLabel() + "_Preview.pdf");
    }

    public record Pdf(byte[] bytes, String fileName) {}

    // ── View model ────────────────────────────────────────────────────────

    ReportCardV2ViewModel viewModel(ReportCardV2Builder.ClassResult cls, ReportCardV2Builder.StudentResult s) {
        return viewModel(cls, s, null);
    }

    /** How a published (official) card is stamped: its issue line and verification QR/reference. */
    record Stamp(String issuedLine, String verificationQr, String verificationRef) {}

    /** @param stamp null for a live preview (marked as such); set for an official, published card. */
    ReportCardV2ViewModel viewModel(ReportCardV2Builder.ClassResult cls, ReportCardV2Builder.StudentResult s, Stamp stamp) {
        Long schoolId = securityUtil.getSchoolId();
        School school = schoolRepo.findById(schoolId).orElseThrow();
        SchoolReportCardDesign design = designService.designFor(schoolId);
        ReportCardSetup setup = cls.setup();
        boolean weighted = setup.getResultMode() == ReportCardResultMode.WEIGHTED;

        // Columns (and term headers)
        List<ReportCardV2ViewModel.ColumnHeader> columns = cls.columns().stream()
                .map(c -> new ReportCardV2ViewModel.ColumnHeader(c.examName(), weighted && c.weight() != null ? pctLabel(c.weight()) : null))
                .toList();
        List<ReportCardV2ViewModel.TermHeader> termHeaders = new ArrayList<>();
        if (!cls.terms().isEmpty()) {
            for (ReportCardSetupTerm t : cls.terms()) {
                int span = (int) cls.columns().stream().filter(c -> t.getId().equals(c.termId())).count();
                if (span > 0) termHeaders.add(new ReportCardV2ViewModel.TermHeader(t.getName(), span,
                        weighted && t.getWeight() != null ? pctLabel(t.getWeight()) : null));
            }
        }

        // Subject rows & totals ("72 / 100", or "72/100" when six-ish columns share the width)
        String sep = columns.size() >= 5 ? "/" : " / ";
        List<ReportCardV2ViewModel.SubjectRow> rows = s.subjects().stream().map(line -> new ReportCardV2ViewModel.SubjectRow(
                line.subject(),
                line.cells().stream().map(c -> ReportCardV2ViewModel.Cell.of(cellText(c, sep))).toList(),
                line.missing() > 0 ? "Not entered" : marks(line.obtained()) + sep + marks(line.max()),
                line.percentage() != null ? pct(line.percentage()) : "—",
                line.grade() != null ? line.grade() : "—")).toList();
        List<String> totalCells = s.examTotals().stream()
                .map(t -> !t.applicable() ? "—" : t.missing() > 0 ? "Incomplete" : marks(t.obtained()) + sep + marks(t.max()))
                .toList();
        String overallTotal = s.status() == ReportCardV2Builder.Status.NO_RESULT ? "—"
                : s.marksMissing() > 0 ? "Incomplete" : marks(s.obtained()) + sep + marks(s.max());
        ReportCardV2ViewModel.TotalsRow totals = new ReportCardV2ViewModel.TotalsRow(totalCells, overallTotal,
                s.percentage() != null ? pct(s.percentage()) : "—", s.grade() != null ? s.grade() : "—");

        // Summary
        String result = switch (s.status()) {
            case PASS -> "Pass";
            case FAIL -> "Fail";
            case INCOMPLETE -> "Incomplete";
            case NO_RESULT -> "No result";
        };
        String resultNote = s.status() == ReportCardV2Builder.Status.INCOMPLETE
                ? s.marksMissing() + " mark" + (s.marksMissing() == 1 ? "" : "s") + " not entered — no percentage, grade or rank until every mark is in."
                : null;
        String basis = weighted ? "Weighted result: " + weightingDescription(cls) : null;
        ReportCardV2ViewModel.Summary summary = new ReportCardV2ViewModel.Summary(
                s.percentage() != null ? pct(s.percentage()) : "—", s.grade() != null ? s.grade() : "—",
                s.rank() != null ? String.valueOf(s.rank()) : "—", result, resultNote, basis);

        // Attendance (Attendance V2, the session up to today)
        ReportCardV2ViewModel.Attendance attendance = null;
        if (design.isShowAttendance()) {
            try {
                LocalDate end = cls.session().getEndDate().isAfter(LocalDate.now()) ? LocalDate.now() : cls.session().getEndDate();
                AttendanceSummaryDTO a = attendanceService.getStudentAttendanceForDateRange(s.student().getStudentId(), cls.session().getStartDate(), end);
                attendance = new ReportCardV2ViewModel.Attendance(String.valueOf(a.getTotalWorkingDays()), String.valueOf(a.getDaysPresent()),
                        a.getTotalWorkingDays() > 0 ? new DecimalFormat("0.#").format(a.getAttendancePercentage()) + "%" : "—");
            } catch (RuntimeException e) {
                log.warn("V2 report card attendance unavailable for student {}: {}", s.student().getStudentId(), e.getMessage());
                attendance = new ReportCardV2ViewModel.Attendance("—", "—", "—");
            }
        }

        // Co-scholastic & remarks
        List<ReportCardV2ViewModel.CoScholastic> coScholastic = null;
        if (design.isShowCoScholastic()) {
            List<SchoolCoScholasticActivity> activities = activityRepo.findBySchoolIdAndActiveTrueOrderByDisplayOrderAscIdAsc(schoolId);
            if (!activities.isEmpty()) {
                Map<Long, String> g = gradesBySetup(setup.getId(), schoolId).getOrDefault(s.student().getStudentId(), Map.of());
                coScholastic = activities.stream().map(a -> new ReportCardV2ViewModel.CoScholastic(a.getName(), g.getOrDefault(a.getId(), "—"))).toList();
            }
        }
        ReportCardRemarkV2 remark = remarkRepo.findBySetupIdAndStudentIdAndSchoolId(setup.getId(), s.student().getStudentId(), schoolId).orElse(null);

        Student st = s.student();
        ReportCardV2ViewModel.StudentBlock student = new ReportCardV2ViewModel.StudentBlock(st.getName(), st.getStudentId(),
                cls.schoolClass().getName(), cls.sectionName(s.sectionId()), st.getDob() != null ? st.getDob().format(DOB) : null,
                blankToNull(st.getFatherName()), blankToNull(st.getMotherName()),
                design.isShowPhoto() ? objectStorageService.resolveDisplayUrl(st.getPhotoUrl()) : null);

        int rowsCount = rows.size(), cols = columns.size();
        String density = rowsCount <= 8 && cols <= 3 ? "normal" : "compact";
        boolean split = rowsCount > 11 || (rowsCount > 9 && cols >= 5);

        List<String> address = new ArrayList<>();
        if (school.getAddress() != null && !school.getAddress().isBlank()) address.add(school.getAddress().trim());
        if (school.getCity() != null && !school.getCity().isBlank()) address.add(school.getCity().trim());
        List<String> affiliation = new ArrayList<>();
        if (school.getBoardType() != null) affiliation.add(boardLabel(school.getBoardType()));
        if (school.getAffiliationNumber() != null && !school.getAffiliationNumber().isBlank()) affiliation.add("Affiliation No. " + school.getAffiliationNumber().trim());
        if (school.getSchoolCode() != null && !school.getSchoolCode().isBlank()) affiliation.add("School Code " + school.getSchoolCode().trim());

        String watermarkText = design.getWatermarkMode() == SchoolReportCardDesign.WatermarkMode.TEXT
                ? (design.getWatermarkText() != null ? design.getWatermarkText() : school.getName()) : null;
        return new ReportCardV2ViewModel(
                new ReportCardV2ViewModel.SchoolBlock(school.getName(), address.isEmpty() ? null : String.join(", ", address),
                        affiliation.isEmpty() ? null : String.join("  ·  ", affiliation), design.getMotto(),
                        objectStorageService.resolveDisplayUrl(school.getLogoUrl()),
                        objectStorageService.resolveDisplayUrl(school.getReportCardHeaderImageUrl())),
                ReportCardDataAssembler.reportTitle(setup.getName(), false), cls.session().getLabel(), student,
                termHeaders, columns, rows, totals, summary, attendance, coScholastic,
                remark != null ? remark.getTeacherRemark() : null, remark != null ? remark.getPrincipalRemark() : null,
                new ReportCardV2ViewModel.Flags(design.isShowPhoto(), design.isShowAttendance(), coScholastic != null,
                        design.isShowTeacherRemark(), design.isShowPrincipalRemark(), design.isShowRank(), design.isShowPromotion()),
                design.getTeacherSignatureLabel(), design.getPrincipalSignatureLabel(), design.getFooterText(), watermarkText,
                design.getWatermarkMode() == SchoolReportCardDesign.WatermarkMode.LOGO ? "LOGO" : null,
                density, split,
                stamp == null ? "Live preview — not a published report card · generated " + LocalDateTime.now().format(STAMP) : null,
                stamp != null ? stamp.verificationQr() : null, stamp != null ? stamp.verificationRef() : null,
                stamp != null ? stamp.issuedLine() : null);
    }

    private static String cellText(ReportCardV2Builder.Cell c, String sep) {
        if (!c.applicable()) return "—";
        if (c.obtained() == null) return "Not entered";
        return marks(c.obtained()) + sep + c.max();
    }

    private static String weightingDescription(ReportCardV2Builder.ClassResult cls) {
        if (cls.terms().isEmpty()) {
            List<String> parts = new ArrayList<>();
            cls.columns().forEach(c -> parts.add(c.examName() + " " + (c.weight() != null ? pctLabel(c.weight()) : "")));
            return String.join(", ", parts).trim();
        }
        List<String> parts = new ArrayList<>();
        for (ReportCardSetupTerm t : cls.terms()) parts.add(t.getName() + " " + (t.getWeight() != null ? pctLabel(t.getWeight()) : ""));
        return String.join(", ", parts).trim();
    }

    private static String pctLabel(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    static String marks(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : new DecimalFormat("0.##").format(v);
    }

    static String pct(double v) {
        return new DecimalFormat("0.00").format(v) + "%";
    }

    private static String boardLabel(BoardType b) {
        return switch (b.name()) {
            case "CBSE" -> "CBSE Affiliated";
            case "ICSE" -> "ICSE Affiliated";
            case "STATE" -> "State Board";
            default -> b.name();
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private Map<String, ReportCardRemarkV2> remarksBySetup(Long setupId, Long schoolId) {
        Map<String, ReportCardRemarkV2> map = new HashMap<>();
        remarkRepo.findBySetupIdAndSchoolId(setupId, schoolId).forEach(r -> map.put(r.getStudentId(), r));
        return map;
    }

    private Map<String, Map<Long, String>> gradesBySetup(Long setupId, Long schoolId) {
        Map<String, Map<Long, String>> map = new HashMap<>();
        coScholasticRepo.findBySetupIdAndSchoolId(setupId, schoolId)
                .forEach(g -> map.computeIfAbsent(g.getStudentId(), k -> new HashMap<>()).put(g.getActivityId(), g.getGrade()));
        return map;
    }
}
