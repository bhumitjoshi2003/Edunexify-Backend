package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.ReportCardTemplate;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolClass;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.SubjectResultDTO;
import com.indraacademy.ias_management.entity.ExamConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles a fully hydrated ReportCardDataDTO by orchestrating:
 *   1. Student data lookup
 *   2. School data lookup
 *   3. Template + sections lookup
 *   4. WeightageCalculationEngine invocation
 *   5. Attendance summary (if ATTENDANCE section is enabled)
 */
@Service
public class ReportCardDataAssembler {

    private static final Logger log = LoggerFactory.getLogger(ReportCardDataAssembler.class);

    @Autowired private StudentRepository studentRepo;
    @Autowired private SchoolRepository schoolRepo;
    @Autowired private ReportCardTemplateRepository templateRepo;
    @Autowired private ReportCardTemplateSectionRepository sectionRepo;
    @Autowired private AcademicSessionRepository sessionRepo;
    @Autowired private AttendanceService attendanceService;
    @Autowired private MarkService markService;
    @Autowired private WeightageCalculationEngine weightageEngine;
    @Autowired private ReportCardTemplateService templateService;
    @Autowired private ObjectStorageService objectStorageService;
    @Autowired private RemarksService remarksService;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Autowired private SchoolClassRepository schoolClassRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ExamSubjectEntryRepository subjectEntryRepo;

    private static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Assemble a report card for a student using a specific template and session.
     *
     * @param studentId  student's string PK
     * @param templateId report card template ID
     * @param session    academic session label (e.g. "2024-25")
     */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assemble(String studentId, Long templateId, String session) {
        return assemble(studentId, templateId, session, null);
    }

    /**
     * @param classId optional discriminator (SchoolClass.id) resolving which historical class
     *                context this report card is for, when the student has more than one
     *                legitimate context in this session (see resolveHistoricalContext). Null
     *                when the context is uniquely resolvable without it.
     */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assemble(String studentId, Long templateId, String session, Long classId) {
        return assemble(studentId, templateId, session, classId, null);
    }

    /**
     * Class ranks for a template's card (see WeightageCalculationEngine.classRanks), computed once
     * so bulk PDFs and email blasts don't recompute the whole class for every student.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> classRanksForTemplate(Long templateId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardTemplate template = templateRepo.findByIdAndSchoolId(templateId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));
        return weightageEngine.classRanks(template.getAssessmentGroupId(), session);
    }

    /** @param classRanks precomputed {@link #classRanksForTemplate}, or null to compute them here. */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assemble(String studentId, Long templateId, String session, Long classId,
                                      Map<String, Integer> classRanks) {
        Long schoolId = securityUtil.getSchoolId();

        // 1. Load student
        Student student = studentRepo.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));

        // 2. Load school
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        // 3. Load template
        ReportCardTemplate template = templateRepo.findByIdAndSchoolId(templateId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        // 4. Compute weighted result via engine
        WeightedGroupResultDTO weightedResult = weightageEngine
                .computeForStudent(studentId, template.getAssessmentGroupId(), session);
        // Rank: the canonical section-aware competition rank of the weighted percentage (unranked
        // while any mark is missing) — the same ResultCalculator ranking as Class Results.
        Map<String, Integer> ranks = classRanks != null ? classRanks
                : weightageEngine.classRanks(template.getAssessmentGroupId(), session);
        Integer rank = ranks != null ? ranks.get(studentId) : null;
        weightedResult.setRank(rank != null ? rank : 0);

        // 4b. Resolve the class/section this report card's academic context actually belongs to
        // for this session — never student.getClassName() directly. See resolveHistoricalContext.
        HistoricalReportCardContext context = resolveHistoricalContext(studentId, session, classId, student);
        String resolvedClassName = context.className();

        // 5. Resolve grading system (template override → school default)
        String gradingSystem = (template.getGradingOverride() != null && !template.getGradingOverride().isBlank())
                ? template.getGradingOverride()
                : (school.getGradingSystem() != null ? school.getGradingSystem() : "PERCENTAGE");

        // Assemble DTO
        ReportCardDataDTO dto = new ReportCardDataDTO();

        fillStudentAndSchool(dto, student, school, context, session);

        // Template
        dto.setTemplate(templateService.getTemplate(templateId));
        dto.setReportTitle(reportTitle(templateTerm(dto.getTemplate(), weightedResult), false));
        dto.setGradingSystem(gradingSystem);

        // Weighted result
        dto.setWeightedResult(weightedResult);

        // Attendance — only if ATTENDANCE section is enabled
        boolean attendanceEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "ATTENDANCE")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);

        if (attendanceEnabled) {
            dto.setAttendance(buildAttendanceBlock(studentId, session, schoolId));
        }

        // Remarks — only if the respective sections are enabled
        boolean teacherRemarksEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "TEACHER_REMARKS")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);
        boolean principalRemarksEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "PRINCIPAL_REMARKS")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);
        boolean coScholasticEnabled = sectionRepo
                .findByTemplateIdAndSectionType(templateId, "CO_SCHOLASTIC")
                .map(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElse(false);

        if (teacherRemarksEnabled) {
            dto.setTeacherRemarks(remarksService.getStudentRemark(
                    studentId, templateId, session, "TEACHER", schoolId));
        }
        if (principalRemarksEnabled) {
            dto.setPrincipalRemarks(remarksService.getStudentRemark(
                    studentId, templateId, session, "PRINCIPAL", schoolId));
        }
        if (coScholasticEnabled) {
            dto.setCoScholasticGrades(remarksService.getStudentCoScholastic(
                    studentId, templateId, session, schoolId));
        }

        // Overall grade + CGPA
        double overallPct = weightedResult.getWeightedPercentage();
        dto.setOverallGrade(gradeFromPct(overallPct, gradingSystem));
        // Subject grades come from the same policy — clients never grade on their own.
        if (weightedResult.getMarksTable() != null && weightedResult.getMarksTable().getSubjectRows() != null) {
            weightedResult.getMarksTable().getSubjectRows()
                    .forEach(row -> row.setGrade(gradeFromPct(row.getWeightedPercentage(), gradingSystem)));
        }
        if ("CBSE".equalsIgnoreCase(gradingSystem)) {
            dto.setCgpa(computeCgpa(weightedResult, gradingSystem));
        }

        return dto;
    }


    /** Student, historical class/section and school fields shared by both kinds of card. */
    private void fillStudentAndSchool(ReportCardDataDTO dto, Student student, School school,
                                      HistoricalReportCardContext context, String session) {
        String resolvedClassName = context.className();
        // Student fields
        dto.setStudentId(student.getStudentId());
        dto.setStudentName(student.getName());
        dto.setClassName(resolvedClassName);
        // E6E: historical section now comes from the applicable realized StudentEnrollment
        // segment for this class/session (E6B) rather than a guess — see resolveHistoricalSection.
        // When no enrollment segment applies, falls back to the student's live section only when
        // the resolved class still equals their CURRENT class (no promotion since this session —
        // a genuinely safe, wholly-legacy case), and is left unset otherwise rather than guessed.
        dto.setSectionName(context.sectionName());
        dto.setSession(session);
        dto.setFatherName(student.getFatherName());
        dto.setMotherName(student.getMotherName());
        if (student.getDob() != null) {
            dto.setDateOfBirth(student.getDob().format(DOB_FMT));
        }
        // Fresh presigned GET URLs for any object-storage key (legacy local-disk paths and
        // already-absolute URLs pass through unchanged) — ReportCardPdfGenerator's
        // loadStudentPhotoImage/loadLogoImage/loadHeaderImage already fetch any http(s) URL over
        // the network, so no generator changes are needed for this to work.
        dto.setPhotoUrl(objectStorageService.resolveDisplayUrl(student.getPhotoUrl()));

        // School fields
        dto.setSchoolName(school.getName());
        dto.setSchoolLogoUrl(objectStorageService.resolveDisplayUrl(school.getLogoUrl()));
        dto.setSchoolAddress(school.getAddress());
        dto.setSchoolPhone(school.getPhone());
        dto.setSchoolEmail(school.getEmail());
        if (school.getBoardType() != null) {
            dto.setBoardType(school.getBoardType().name());
        }
        dto.setAffiliationNumber(school.getAffiliationNumber());
        dto.setSchoolCode(school.getSchoolCode());
        dto.setSchoolCity(school.getCity());
        dto.setReportCardHeaderImageUrl(objectStorageService.resolveDisplayUrl(school.getReportCardHeaderImageUrl()));
    }

    // ── Report title ──────────────────────────────────────────────────────

    /**
     * The card's heading, shared by the PDF and the on-screen card: the exam (or term) it covers —
     * "HALF YEARLY — REPORT CARD" — or "ANNUAL REPORT CARD" for the whole session. A single-exam
     * card is never labelled Annual.
     */
    static String reportTitle(String term, boolean wholeSession) {
        if (wholeSession) return "ANNUAL REPORT CARD";
        if (term == null || term.isBlank()) return "REPORT CARD";
        String t = term.trim().replaceAll("\\s+", " ").toUpperCase();
        return t.contains("ANNUAL") ? "ANNUAL REPORT CARD" : t + " \u2014 REPORT CARD";
    }

    /** A template card covers its branding's exam term if set, otherwise its assessment group. */
    private static String templateTerm(ReportCardTemplateDTO template, WeightedGroupResultDTO result) {
        if (template != null && template.getBrandingJson() != null && !template.getBrandingJson().isBlank()) {
            try {
                Object term = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(template.getBrandingJson(), Map.class).get("examTerm");
                if (term instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) { /* malformed branding: fall back to the group name */ }
        }
        return result != null ? result.getGroupName() : null;
    }

    // ── Results-based card (no template) ──────────────────────────────────

    /**
     * Report card built straight from the canonical Results Phase 1 data (the exam results My
     * Results and Class Results show) when no template is used — the card Class Results, My
     * Results and the parent portal open. One exam, or every exam of the session as columns.
     * Rendered by the same ReportCardPdfGenerator as template cards.
     *
     * @param includeDrafts staff see draft exams (as on their results screens); students and
     *                      parents only ever get published results.
     */
    @Transactional(readOnly = true)
    public ReportCardDataDTO assembleFromResults(String studentId, String session, Long examId, Long classId,
                                                 boolean includeDrafts) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepo.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        School school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new NoSuchElementException("School not found: " + schoolId));

        List<ExamResultDTO> results = new ArrayList<>(markService.getStudentResults(studentId, session, includeDrafts));
        if (examId != null) results.removeIf(r -> !examId.equals(r.getExamId()));
        if (results.isEmpty()) {
            throw new NoSuchElementException("No results are available for this report card yet.");
        }
        HistoricalReportCardContext context = resolveHistoricalContext(studentId, session, classId, student);

        String rawGrading = markService.gradingSystem(schoolId);
        String gradingSystem = GradingPolicy.system(rawGrading);
        ReportCardDataDTO dto = new ReportCardDataDTO();
        fillStudentAndSchool(dto, student, school, context, session);
        dto.setGradingSystem(gradingSystem);
        dto.setWeightedResult(resultsTable(results, rawGrading, examId != null));
        dto.setReportTitle(reportTitle(examId != null ? results.get(0).getExamName() : null, examId == null));
        try {
            dto.setAttendance(buildAttendanceBlock(studentId, session, schoolId));
        } catch (RuntimeException e) {
            log.warn("Report card attendance unavailable for student {} session {}: {}", studentId, session, e.getMessage());
        }

        // A results-based card is not a report-card publication: it has no verification token and
        // therefore no QR (only a published template card is verifiable — see ReportCardPdfGenerator).
        dto.setVerificationToken(null);

        WeightedGroupResultDTO table = dto.getWeightedResult();
        dto.setOverallGrade(GradingPolicy.grade(table.getWeightedPercentage(), rawGrading));
        if (results.size() == 1) {
            // One exam: exactly the rank My Results / Class Results show for it.
            Integer rank = results.get(0).getOverallRank();
            table.setRank(rank != null ? rank : 0);
        } else {
            Integer rank = aggregateRanks(results).get(studentId);
            table.setRank(rank != null ? rank : 0);
        }
        if ("CBSE".equals(gradingSystem) && table.getMarksMissing() == 0) {
            dto.setCgpa(computeCgpa(table, gradingSystem));
        }
        return dto;
    }

    /** The results as a marks table: one column per exam, one row per subject. */
    private WeightedGroupResultDTO resultsTable(List<ExamResultDTO> results, String rawGrading, boolean singleExam) {
        List<WeightedGroupResultDTO.MarksTableDTO.ExamColumnDTO> columns = new ArrayList<>();
        List<WeightedGroupResultDTO.MarksTableDTO.ExamTotalDTO> totals = new ArrayList<>();
        Map<String, WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO[]> cells = new LinkedHashMap<>();
        Map<String, double[]> subjectTotals = new HashMap<>();     // [obtained, max, missing]
        Map<String, String> singleExamGrades = new HashMap<>();
        double obtained = 0, max = 0;
        int missing = 0;
        for (int i = 0; i < results.size(); i++) {
            ExamResultDTO r = results.get(i);
            columns.add(new WeightedGroupResultDTO.MarksTableDTO.ExamColumnDTO(r.getExamId(), r.getExamName(),
                    r.getTotalMaxMarks(), 1.0 / results.size()));
            totals.add(new WeightedGroupResultDTO.MarksTableDTO.ExamTotalDTO(r.getTotalMarksObtained(), r.getTotalMaxMarks()));
            obtained += r.getTotalMarksObtained();
            max += r.getTotalMaxMarks();
            missing += r.getMarksMissing();
            for (SubjectResultDTO sub : r.getSubjects()) {
                int maxMarks = sub.getMaxMarks() != null ? sub.getMaxMarks() : 0;
                Double got = sub.getMarksObtained();
                double pct = maxMarks > 0 && got != null ? got / maxMarks * 100.0 : 0.0;
                cells.computeIfAbsent(sub.getSubjectName(),
                        k -> new WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO[results.size()])[i] =
                        new WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO(got, maxMarks, pct);
                double[] t = subjectTotals.computeIfAbsent(sub.getSubjectName(), k -> new double[3]);
                t[0] += got != null ? got : 0;
                t[1] += maxMarks;
                if (got == null) t[2]++;
                if (singleExam) singleExamGrades.put(sub.getSubjectName(), sub.getGrade());
            }
        }
        List<WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO> rows = new ArrayList<>();
        List<WeightedGroupResultDTO.SubjectWeightedResultDTO> subjects = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            double[] t = subjectTotals.get(entry.getKey());
            double pct = t[1] > 0 ? t[0] / t[1] * 100.0 : 0.0;
            var row = new WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO(entry.getKey(),
                    new ArrayList<>(java.util.Arrays.asList(entry.getValue())), pct);   // null = subject not in that exam
            // A subject with a mark not entered has no grade (canonical rule), never a silent zero grade.
            row.setGrade(singleExam ? singleExamGrades.get(entry.getKey())
                    : t[2] > 0 ? null : GradingPolicy.grade(pct, rawGrading));
            rows.add(row);
            subjects.add(new WeightedGroupResultDTO.SubjectWeightedResultDTO(entry.getKey(), pct));
        }
        double percentage = max > 0 ? obtained / max * 100.0 : 0.0;   // missing marks count as absent ("Ab")
        String name = singleExam ? results.get(0).getExamName() : "All Examinations";
        WeightedGroupResultDTO result = new WeightedGroupResultDTO(null, name, "RESULTS", percentage, subjects,
                null, null, new WeightedGroupResultDTO.MarksTableDTO(columns, rows, totals), 0);
        result.setMarksMissing(missing);
        return result;
    }

    /**
     * Section-aware competition ranks over the total of several exams (Σ obtained / Σ max) — the
     * canonical ResultCalculator ranking on the canonical exam sheets; unranked with a mark missing.
     */
    private Map<String, Integer> aggregateRanks(List<ExamResultDTO> results) {
        Long schoolId = securityUtil.getSchoolId();
        Map<String, double[]> totals = new HashMap<>();
        Map<String, Integer> missing = new HashMap<>();
        Map<String, Long> section = new HashMap<>();
        for (ExamResultDTO r : results) {
            ExamConfig exam = examConfigRepo.findById(r.getExamId()).orElse(null);
            if (exam == null || !schoolId.equals(exam.getSchoolId())) continue;
            MarkService.ExamSheet sheet = markService.buildExamSheet(exam,
                    subjectEntryRepo.findByExamConfigIdAndSchoolId(exam.getId(), schoolId));
            for (MarkService.SheetRow row : sheet.rows()) {
                if (row.entries().isEmpty()) continue;
                String id = row.student().getStudentId();
                double[] t = totals.computeIfAbsent(id, k -> new double[2]);
                t[0] += row.score().obtained();
                t[1] += row.score().max();
                missing.merge(id, row.score().marksMissing(), Integer::sum);
                section.put(id, row.sectionId());
            }
        }
        Map<String, Double> pct = new HashMap<>();
        totals.forEach((id, t) -> pct.put(id, missing.getOrDefault(id, 0) > 0 || t[1] <= 0 ? null : t[0] / t[1] * 100.0));
        return ResultCalculator.competitionRanksWithin(pct, section::get);
    }

    // ── Grade helpers ─────────────────────────────────────────────────────

    private String gradeFromPct(double pct, String gradingSystem) {
        return GradingPolicy.grade(pct, gradingSystem);
    }

    private double cbseGradePoint(String grade) {
        return GradingPolicy.cbseGradePoint(grade);
    }

    private Double computeCgpa(WeightedGroupResultDTO result, String gradingSystem) {
        java.util.List<WeightedGroupResultDTO.SubjectWeightedResultDTO> subjects = result.getSubjectResults();
        if (subjects == null || subjects.isEmpty()) {
            // Fallback to overall percentage when no per-subject data (GROUP_BASED)
            return Math.round(cbseGradePoint(gradeFromPct(result.getWeightedPercentage(), gradingSystem)) * 10.0) / 10.0;
        }
        double sum = 0;
        for (WeightedGroupResultDTO.SubjectWeightedResultDTO s : subjects) {
            sum += cbseGradePoint(gradeFromPct(s.getWeightedPercentage(), gradingSystem));
        }
        double raw = sum / subjects.size();
        return Math.round(raw * 10.0) / 10.0;
    }

    // ── E6E: shared academic-context resolution ───────────────────────────
    //
    // A report card's historical context is student + template + AcademicSession + historical
    // class (+ section). Class discovery is delegated entirely to MarkService/E6D
    // (resolveHistoricalClassNamesForSession — marks ∪ realized enrollment, respecting E6B's
    // legacy/gap classification); this assembler never re-implements that discovery. What IS
    // new here is (a) refusing to arbitrarily pick a class when more than one is legitimately
    // possible — an explicit classId disambiguates instead — and (b) resolving the HISTORICAL
    // SECTION, which E6D's class-name-only resolution doesn't cover, via E6B's enrollment
    // segments directly.

    /** A student's resolved historical report-card context: the exact class(+section) this
     *  report card is for. {@code classId} is the tenant SchoolClass id backing {@code className}
     *  (null if the class name can't be mapped to a current SchoolClass row — e.g. a renamed/
     *  retired class). */
    public record HistoricalReportCardContext(String className, Long classId, Long sectionId, String sectionName) {}

    /** Thrown when a student has more than one legitimate historical class context for the
     *  requested session and the caller supplied no (or a non-matching) classId to disambiguate.
     *  Never resolved by arbitrary selection — see the class Javadoc. */
    public static class ReportCardContextAmbiguousException extends RuntimeException {
        private final Set<String> candidates;
        public ReportCardContextAmbiguousException(Set<String> candidates) {
            super("Multiple historical report-card class contexts exist for this student/session ("
                    + candidates + "). Specify classId to disambiguate.");
            this.candidates = candidates;
        }
        public Set<String> getCandidates() { return candidates; }
    }

    /**
     * Public entry point so callers outside this assembler (e.g. {@code ReportCardController}'s
     * publication-access check) can resolve the same historical context this assembler uses,
     * without needing their own {@code MarkService}/resolver dependencies.
     *
     * @param classId optional discriminator; required only when the student genuinely has more
     *                than one legitimate class context for this session (see
     *                {@link ReportCardContextAmbiguousException}).
     */
    public HistoricalReportCardContext resolveHistoricalContext(String studentId, String session, Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        Student student = studentRepo.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Student not found: " + studentId));
        return resolveHistoricalContext(studentId, session, classId, student);
    }

    private HistoricalReportCardContext resolveHistoricalContext(String studentId, String session, Long classId, Student student) {
        Long schoolId = securityUtil.getSchoolId();

        // Class discovery — entirely E6D's job (marks ∪ realized enrollment for this session,
        // already respecting legacy/gap semantics). Never reimplemented here.
        Set<String> candidateClassNames = markService.resolveHistoricalClassNamesForSession(
                studentId, session, schoolId, student.getClassName());

        String resolvedClassName;
        if (candidateClassNames.isEmpty()) {
            // No marks, no realized enrollment, and E6B classified this as an authoritative gap
            // (legacy fallback was NOT permitted) — there is genuinely nothing to build a report
            // card from. Do not fabricate a context from the live current class.
            throw new NoSuchElementException(
                    "No historical report-card context could be resolved for student " + studentId + " in session " + session);
        } else if (candidateClassNames.size() == 1) {
            resolvedClassName = candidateClassNames.iterator().next();
        } else if (classId != null) {
            String requestedClassName = schoolClassRepo.findByIdAndSchoolId(classId, schoolId)
                    .map(SchoolClass::getName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown classId: " + classId));
            if (!candidateClassNames.contains(requestedClassName)) {
                throw new IllegalArgumentException(
                        "classId " + classId + " (" + requestedClassName + ") does not match any historical " +
                                "report-card context for student " + studentId + " in session " + session +
                                " (" + candidateClassNames + ").");
            }
            resolvedClassName = requestedClassName;
        } else {
            // Multiple legitimate contexts and no discriminator — never guess (no
            // Set.iterator().next(), no "latest wins"). The caller must supply classId.
            throw new ReportCardContextAmbiguousException(candidateClassNames);
        }

        Long resolvedClassId = schoolClassRepo.findBySchoolIdAndName(schoolId, resolvedClassName)
                .map(SchoolClass::getId).orElse(null);
        HistoricalSection section = resolveHistoricalSection(studentId, schoolId, session, resolvedClassName, student);
        return new HistoricalReportCardContext(resolvedClassName, resolvedClassId, section.sectionId(), section.sectionName());
    }

    private record HistoricalSection(Long sectionId, String sectionName) {}

    /**
     * Historical section for the resolved class/session, via E6B's realized enrollment segments
     * — never the marks/exam domain (which has no section column at all) and never a silent
     * guess from the live Student row unless the resolved class still equals the student's
     * CURRENT class (no promotion since this session — a genuinely safe, wholly-legacy case).
     * <p>Multi-segment same-class case: a section change alone (e.g. mid-session section
     * transfer) must not create a second report card, since the report-card class context is
     * unchanged — but more than one section legitimately applied across the session, and no
     * single one can truthfully represent the whole report. Documented compatibility rule: the
     * section from the segment with the latest {@code effectiveFrom} is shown (the section the
     * student was in most recently within this class/session) — the same "latest segment wins
     * for display" rule E6C/E6D already use, never today's live section.
     */
    private HistoricalSection resolveHistoricalSection(
            String studentId, Long schoolId, String session, String resolvedClassName, Student student) {
        Optional<AcademicSession> sessionOpt = sessionRepo.findBySchoolIdAndLabel(schoolId, session);
        if (sessionOpt.isPresent()) {
            try {
                StudentTemporalMembershipResolver.SessionResolution resolution =
                        temporalMembershipResolver.realizedEnrollmentSegmentsForSession(schoolId, studentId, sessionOpt.get().getId());
                if (resolution.classification() != StudentTemporalMembershipResolver.CoverageClassification.CONFLICT) {
                    List<StudentTemporalMembershipResolver.Segment> matching = resolution.segments().stream()
                            .filter(s -> resolvedClassName.equals(s.classNameSnapshot()))
                            .sorted(Comparator.comparing(StudentTemporalMembershipResolver.Segment::effectiveFrom))
                            .toList();
                    if (!matching.isEmpty()) {
                        StudentTemporalMembershipResolver.Segment latest = matching.get(matching.size() - 1);
                        return new HistoricalSection(latest.sectionId(), latest.sectionNameSnapshot());
                    }
                } else {
                    log.error("Enrollment conflict resolving historical section for student {} session {}: {} — " +
                                    "falling back to legacy section rule.",
                            studentId, session, resolution.conflictReason());
                }
            } catch (RuntimeException e) {
                log.error("Failed to resolve realized enrollment section for student {} session {}", studentId, session, e);
            }
        }
        // No enrollment segment applies (LEGACY_UNCOVERED, or no AcademicSession for this label) —
        // the marks/exam domain has no section column, so the only safe, non-fabricated section
        // is the student's CURRENT one, and only when no promotion has happened since this
        // session (resolved class still equals the live class). Left unset otherwise.
        if (resolvedClassName.equals(student.getClassName())) {
            return new HistoricalSection(student.getSectionId(), student.getSectionName());
        }
        return new HistoricalSection(null, null);
    }

    private ReportCardDataDTO.AttendanceBlock buildAttendanceBlock(
            String studentId, String sessionLabel, Long schoolId) {

        // E6E: the real, tenant-owned AcademicSession is the only source of truth for this
        // range — a missing session must fail clearly rather than guess a Jan-1-to-today window
        // (which silently mixed unrelated data into a report card's attendance figures).
        AcademicSession academicSession = sessionRepo.findBySchoolIdAndLabel(schoolId, sessionLabel)
                .orElseThrow(() -> new NoSuchElementException(
                        "No academic session found for label: " + sessionLabel));

        LocalDate start = academicSession.getStartDate();
        LocalDate end = academicSession.getEndDate();
        // Cap end to today so we don't count future days
        if (end.isAfter(LocalDate.now())) {
            end = LocalDate.now();
        }

        // Delegates to AttendanceService's shared enrollment-aware historical attendance logic
        // (E6C) rather than recomputing working-days/absences against a single class name here —
        // never duplicate attendance membership logic in the report-card module.
        AttendanceSummaryDTO summary = attendanceService.getStudentAttendanceForDateRange(studentId, start, end);

        return new ReportCardDataDTO.AttendanceBlock(
                (int) summary.getTotalWorkingDays(),
                (int) summary.getDaysPresent(),
                summary.getAttendancePercentage());
    }
}
