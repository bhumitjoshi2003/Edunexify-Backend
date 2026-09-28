package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Report Card V2: turns a setup into every student's result, using only the canonical Results
 * Phase 1 pieces — MarkService.ExamSheet (roster, applicable subjects, marks, sections),
 * ResultCalculator (scores, rounding), GradingPolicy (grade, pass) and ResultCalculator's
 * section-aware competition ranks. Read-only; nothing here writes.
 *
 * <ul>
 *   <li>A subject a student does not take in an exam, or an exam the student was not part of,
 *       is shown as "—" and left out — never counted as zero.</li>
 *   <li>Any missing mark in an exam the student is part of makes the result Incomplete: no
 *       percentage, grade or rank (exactly like Class Results).</li>
 *   <li>TOTAL: total obtained ÷ total max over the student's exams — for a one-exam card this is
 *       the very score Class Results shows.</li>
 *   <li>WEIGHTED: Σ exam % × weight, or Σ term % × term weight (a term being Σ exam % × weight,
 *       or its total marks when its exams carry no weights). Weights of exams/terms a student
 *       was not part of are left out and the rest re-normalised.</li>
 *   <li>Rank: competition rank of the percentage within the student's section (the latest exam's
 *       rank section), ties shared, incomplete students unranked.</li>
 * </ul>
 */
@Service
public class ReportCardV2Builder {

    public enum Status { PASS, FAIL, INCOMPLETE, NO_RESULT }

    /** One exam column of the card. */
    public record Column(Long examId, String examName, ExamResultStatus resultStatus, Long termId, String termName,
                         BigDecimal weight) {}

    /** One subject × exam cell: not applicable ("—"), not entered (obtained null) or a mark. */
    public record Cell(boolean applicable, Double obtained, int max) {
        public boolean notEntered() { return applicable && obtained == null; }
    }

    public record SubjectLine(String subject, List<Cell> cells, double obtained, double max, int missing,
                              Double percentage, String grade) {}

    public record ExamTotal(boolean applicable, double obtained, double max, int missing, Double percentage) {}

    public record StudentResult(Student student, Long sectionId, Set<Long> sections, List<SubjectLine> subjects,
                                List<ExamTotal> examTotals, double obtained, double max, int marksMissing,
                                Double percentage, String grade, Boolean passed, Integer rank, Status status) {
        public boolean inSection(Long sectionId) { return sectionId == null || sections.contains(sectionId); }
    }

    public record ClassResult(ReportCardSetup setup, AcademicSession session, SchoolClass schoolClass,
                              String gradingSystem, List<Column> columns, List<ReportCardSetupTerm> terms,
                              List<StudentResult> students, Map<Long, String> sectionNames) {
        public StudentResult student(String studentId) {
            for (StudentResult s : students) if (s.student().getStudentId().equals(studentId)) return s;
            return null;
        }
        public String sectionName(Long sectionId) { return sectionId == null ? null : sectionNames.get(sectionId); }
    }

    @Autowired private ReportCardSetupTermRepository termRepo;
    @Autowired private ReportCardSetupExamRepository setupExamRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ExamSubjectEntryRepository subjectEntryRepo;
    @Autowired private MarkService markService;
    @Autowired private AcademicSessionRepository sessionRepo;
    @Autowired private SchoolClassRepository classRepo;
    @Autowired private SectionRepository sectionRepo;
    @Autowired private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public ClassResult computeClass(ReportCardSetup setup) {
        Long schoolId = securityUtil.getSchoolId();
        if (!schoolId.equals(setup.getSchoolId())) throw new NoSuchElementException("Report card setup not found: " + setup.getId());
        AcademicSession session = sessionRepo.findByIdAndSchoolId(setup.getAcademicSessionId(), schoolId).orElseThrow();
        SchoolClass cls = classRepo.findByIdAndSchoolId(setup.getClassId(), schoolId).orElseThrow();
        String gradingSystem = markService.gradingSystem(schoolId);

        List<ReportCardSetupTerm> terms = termRepo.findBySetupIdAndSchoolIdOrderByDisplayOrderAscIdAsc(setup.getId(), schoolId);
        Map<Long, ReportCardSetupTerm> termById = new HashMap<>();
        terms.forEach(t -> termById.put(t.getId(), t));
        List<ReportCardSetupExam> links = setupExamRepo.findBySetupIdAndSchoolIdOrderByDisplayOrderAscIdAsc(setup.getId(), schoolId);
        // Columns follow the terms' order, then each exam's own order within its term.
        links = new ArrayList<>(links);
        Map<Long, Integer> termOrder = new HashMap<>();
        for (int i = 0; i < terms.size(); i++) termOrder.put(terms.get(i).getId(), i);
        links.sort(Comparator.comparing((ReportCardSetupExam l) -> l.getTermId() == null ? Integer.MAX_VALUE : termOrder.getOrDefault(l.getTermId(), Integer.MAX_VALUE))
                .thenComparing(ReportCardSetupExam::getDisplayOrder).thenComparing(ReportCardSetupExam::getId));

        List<Column> columns = new ArrayList<>();
        List<ReportCardSetupExam> usedLinks = new ArrayList<>();
        List<List<ExamSubjectEntry>> entriesByExam = new ArrayList<>();
        List<MarkService.ExamSheet> sheets = new ArrayList<>();
        for (ReportCardSetupExam link : links) {
            ExamConfig exam = examConfigRepo.findById(link.getExamConfigId()).filter(e -> schoolId.equals(e.getSchoolId())).orElse(null);
            if (exam == null) continue;
            List<ExamSubjectEntry> entries = new ArrayList<>(subjectEntryRepo.findByExamConfigIdAndSchoolId(exam.getId(), schoolId));
            entries.sort(Comparator.comparing(ExamSubjectEntry::getId));
            ReportCardSetupTerm term = link.getTermId() != null ? termById.get(link.getTermId()) : null;
            columns.add(new Column(exam.getId(), exam.getExamName(), exam.getResultStatus(),
                    term != null ? term.getId() : null, term != null ? term.getName() : null, link.getWeight()));
            usedLinks.add(link);
            entriesByExam.add(entries);
            sheets.add(markService.buildExamSheet(exam, entries));
        }

        // Roster: everyone the exams' canonical sheets say sits them; subjects in exam order.
        Map<String, Student> roster = new LinkedHashMap<>();
        Set<String> subjectNames = new LinkedHashSet<>();
        for (int i = 0; i < sheets.size(); i++) {
            sheets.get(i).rows().forEach(r -> roster.putIfAbsent(r.student().getStudentId(), r.student()));
            entriesByExam.get(i).forEach(e -> subjectNames.add(e.getSubjectName()));
        }

        Weighting weighting = new Weighting(setup.getResultMode(), terms, usedLinks);
        List<StudentResult> results = new ArrayList<>();
        for (Student student : roster.values()) {
            results.add(studentResult(student, subjectNames, sheets, entriesByExam, weighting, gradingSystem));
        }

        Map<String, Double> pct = new HashMap<>();
        Map<String, Long> section = new HashMap<>();
        results.forEach(r -> { pct.put(r.student().getStudentId(), r.percentage()); section.put(r.student().getStudentId(), r.sectionId()); });
        Map<String, Integer> ranks = ResultCalculator.competitionRanksWithin(pct, section::get);
        List<StudentResult> ranked = results.stream().map(r -> new StudentResult(r.student(), r.sectionId(), r.sections(),
                        r.subjects(), r.examTotals(), r.obtained(), r.max(), r.marksMissing(), r.percentage(), r.grade(),
                        r.passed(), ranks.get(r.student().getStudentId()), r.status()))
                .sorted(Comparator.comparing((StudentResult r) -> r.student().getName() == null ? "" : r.student().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(r -> r.student().getStudentId()))
                .toList();

        Set<Long> sectionIds = new HashSet<>();
        ranked.forEach(r -> { sectionIds.addAll(r.sections()); if (r.sectionId() != null) sectionIds.add(r.sectionId()); });
        sectionIds.remove(null);
        Map<Long, String> sectionNames = new HashMap<>();
        if (!sectionIds.isEmpty()) {
            sectionRepo.findAllById(sectionIds).forEach(s -> { if (schoolId.equals(s.getSchoolId())) sectionNames.put(s.getId(), s.getName()); });
        }
        return new ClassResult(setup, session, cls, gradingSystem, columns, terms, ranked, sectionNames);
    }

    private StudentResult studentResult(Student student, Set<String> subjectNames, List<MarkService.ExamSheet> sheets,
                                        List<List<ExamSubjectEntry>> entriesByExam, Weighting weighting, String gradingSystem) {
        String id = student.getStudentId();
        int n = sheets.size();
        MarkService.SheetRow[] rows = new MarkService.SheetRow[n];
        List<ExamTotal> totals = new ArrayList<>();
        List<ResultCalculator.SubjectMark> allMarks = new ArrayList<>();
        Long sectionId = null;
        Set<Long> sections = new HashSet<>();
        int missing = 0;
        double obtained = 0, max = 0;
        for (int i = 0; i < n; i++) {
            MarkService.SheetRow row = sheets.get(i).row(id);
            boolean applicable = row != null && !row.entries().isEmpty();
            rows[i] = applicable ? row : null;
            if (!applicable) {
                totals.add(new ExamTotal(false, 0, 0, 0, null));
                continue;
            }
            ResultCalculator.Score s = row.score();
            totals.add(new ExamTotal(true, s.obtained(), s.max(), s.marksMissing(), s.percentage()));
            missing += s.marksMissing();
            obtained += s.obtained();
            max += s.max();
            for (ExamSubjectEntry e : row.entries()) allMarks.add(new ResultCalculator.SubjectMark(e.getMaxMarks(), row.marks().get(e.getId())));
            sectionId = row.sectionId();
            sections.addAll(row.sections());
        }

        List<SubjectLine> subjects = new ArrayList<>();
        for (String subject : subjectNames) {
            List<Cell> cells = new ArrayList<>();
            double sObt = 0, sMax = 0;
            int sMissing = 0;
            boolean any = false;
            for (int i = 0; i < n; i++) {
                ExamSubjectEntry entry = rows[i] == null ? null : rows[i].entries().stream()
                        .filter(e -> subject.equals(e.getSubjectName())).findFirst().orElse(null);
                if (entry == null) { cells.add(new Cell(false, null, 0)); continue; }
                Double mark = rows[i].marks().get(entry.getId());
                int entryMax = entry.getMaxMarks() != null ? entry.getMaxMarks() : 0;
                cells.add(new Cell(true, mark, entryMax));
                any = true;
                sMax += entryMax;
                if (mark == null) sMissing++; else sObt += mark;
            }
            if (!any) continue;   // a subject this student takes in none of the exams is not on their card
            Double sPct = null;
            if (sMissing == 0 && sMax > 0) {
                sPct = weighting.mode == ReportCardResultMode.WEIGHTED
                        ? weighting.combine(i -> cellMeasure(cells.get(i)))
                        : ResultCalculator.round2(sObt / sMax * 100.0);
            }
            subjects.add(new SubjectLine(subject, cells, sObt, sMax, sMissing, sPct,
                    sPct != null ? GradingPolicy.grade(sPct, gradingSystem) : null));
        }

        boolean anyExam = Arrays.stream(rows).anyMatch(Objects::nonNull);
        if (!anyExam) {
            return new StudentResult(student, student.getSectionId(), Set.of(), subjects, totals, 0, 0, 0, null, null, null, null, Status.NO_RESULT);
        }
        if (missing > 0) {
            return new StudentResult(student, sectionId, sections, subjects, totals, obtained, max, missing, null, null, null, null, Status.INCOMPLETE);
        }
        Double percentage;
        String grade;
        Boolean passed;
        if (weighting.mode == ReportCardResultMode.WEIGHTED) {
            percentage = weighting.combine(i -> rows[i] == null ? Measure.NOT_APPLICABLE
                    : new Measure(true, false, rows[i].score().obtained(), rows[i].score().max(), rows[i].score().percentage()));
            grade = percentage != null ? GradingPolicy.grade(percentage, gradingSystem) : null;
            passed = percentage != null ? GradingPolicy.passed(percentage) : null;
        } else {
            ResultCalculator.Score total = ResultCalculator.score(allMarks, gradingSystem);
            percentage = total.percentage();
            grade = total.grade();
            passed = total.passed();
        }
        Status status = percentage == null ? Status.NO_RESULT : Boolean.TRUE.equals(passed) ? Status.PASS : Status.FAIL;
        return new StudentResult(student, sectionId, sections, subjects, totals, obtained, max, 0, percentage, grade, passed, null, status);
    }

    private static Measure cellMeasure(Cell c) {
        if (!c.applicable()) return Measure.NOT_APPLICABLE;
        if (c.obtained() == null) return new Measure(true, true, 0, c.max(), null);
        return new Measure(true, false, c.obtained(), c.max(), c.max() > 0 ? c.obtained() / c.max() * 100.0 : null);
    }

    /** One exam's (or subject-in-exam's) contribution. */
    record Measure(boolean applicable, boolean incomplete, double obtained, double max, Double percentage) {
        static final Measure NOT_APPLICABLE = new Measure(false, false, 0, 0, null);
    }

    /** The setup's WEIGHTED arithmetic (flat exams, or terms of exams). */
    static final class Weighting {
        final ReportCardResultMode mode;
        final List<ReportCardSetupTerm> terms;
        final List<ReportCardSetupExam> links;

        Weighting(ReportCardResultMode mode, List<ReportCardSetupTerm> terms, List<ReportCardSetupExam> links) {
            this.mode = mode;
            this.terms = terms;
            this.links = links;
        }

        /** Weighted percentage (rounded to 2 dp), or null when nothing applies or a mark is missing. */
        Double combine(IntFunction<Measure> measureOfExam) {
            Measure m = terms.isEmpty() ? weightedMean(indexes(null), measureOfExam) : combineTerms(measureOfExam);
            return m.applicable() && !m.incomplete() && m.percentage() != null ? ResultCalculator.round2(m.percentage()) : null;
        }

        private Measure combineTerms(IntFunction<Measure> measureOfExam) {
            double sumW = 0, sum = 0;
            boolean incomplete = false, any = false;
            for (ReportCardSetupTerm term : terms) {
                List<Integer> idx = indexes(term.getId());
                boolean weighted = idx.stream().allMatch(i -> links.get(i).getWeight() != null);
                Measure tm = weighted ? weightedMean(idx, measureOfExam) : totalOf(idx, measureOfExam);
                if (!tm.applicable()) continue;
                any = true;
                if (tm.incomplete()) { incomplete = true; continue; }
                double w = term.getWeight() != null ? term.getWeight().doubleValue() : 0;
                sumW += w;
                sum += w * tm.percentage();
            }
            if (!any) return Measure.NOT_APPLICABLE;
            if (incomplete || sumW <= 0) return new Measure(true, true, 0, 0, null);
            return new Measure(true, false, 0, 0, sum / sumW);
        }

        private Measure weightedMean(List<Integer> idx, IntFunction<Measure> measureOfExam) {
            double sumW = 0, sum = 0;
            boolean incomplete = false, any = false;
            for (int i : idx) {
                Measure m = measureOfExam.apply(i);
                if (!m.applicable()) continue;
                any = true;
                if (m.incomplete() || m.percentage() == null) { incomplete = true; continue; }
                double w = links.get(i).getWeight() != null ? links.get(i).getWeight().doubleValue() : 0;
                sumW += w;
                sum += w * m.percentage();
            }
            if (!any) return Measure.NOT_APPLICABLE;
            if (incomplete || sumW <= 0) return new Measure(true, true, 0, 0, null);
            return new Measure(true, false, 0, 0, sum / sumW);
        }

        private Measure totalOf(List<Integer> idx, IntFunction<Measure> measureOfExam) {
            double obt = 0, max = 0;
            boolean incomplete = false, any = false;
            for (int i : idx) {
                Measure m = measureOfExam.apply(i);
                if (!m.applicable()) continue;
                any = true;
                if (m.incomplete()) { incomplete = true; continue; }
                obt += m.obtained();
                max += m.max();
            }
            if (!any) return Measure.NOT_APPLICABLE;
            if (incomplete || max <= 0) return new Measure(true, true, 0, 0, null);
            return new Measure(true, false, obt, max, obt / max * 100.0);
        }

        private List<Integer> indexes(Long termId) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < links.size(); i++) if (Objects.equals(links.get(i).getTermId(), termId)) idx.add(i);
            return idx;
        }
    }
}
