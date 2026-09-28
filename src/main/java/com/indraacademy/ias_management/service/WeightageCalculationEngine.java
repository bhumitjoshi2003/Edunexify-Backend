package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core weightage calculation engine.
 *
 * Supports two group types:
 *   EXAM_BASED  — leaf groups that directly contain exam configs with weightage
 *                 (e.g. Term 1 = UT1 20% + Half Yearly 80%)
 *   GROUP_BASED — composite groups that reference child groups with weightage
 *                 (e.g. Annual = Term 1 50% + Term 2 50%)
 *
 * Computation is recursive: GROUP_BASED groups delegate to EXAM_BASED children.
 *
 * Weighted % algorithm (EXAM_BASED):
 *   For each exam in the group:
 *     exam_pct = sum(obtained) / sum(max) * 100   (normalised, avoids scale bias)
 *   weighted_pct = Σ (exam_pct * exam_weightage)
 *
 * Results Phase 1: a student's subjects in an exam are the ones they actually take
 * (MarkService.applicableEntriesForStudent — the same rule as My Results/Class Results), never
 * "subjects they happen to have a mark for". A missing mark in an applicable subject counts as
 * absent (0, shown as "Ab" on the card) via ResultCalculator and is reported in marksMissing.
 * Class ranks are ResultCalculator competition ranks; nothing here writes to the database.
 */
@Service
public class WeightageCalculationEngine {

    private static final Logger log = LoggerFactory.getLogger(WeightageCalculationEngine.class);

    @Autowired private AssessmentGroupRepository groupRepo;
    @Autowired private AssessmentGroupExamMappingRepository mappingRepo;
    @Autowired private AssessmentGroupCompositionRepository compositionRepo;
    @Autowired private MarkService markService;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ExamSubjectEntryRepository subjectEntryRepo;
    @Autowired private StudentMarkRepository markRepo;
    @Autowired private SecurityUtil securityUtil;

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Compute the weighted result for a single student in an assessment group.
     * schoolId is taken from SecurityUtil (thread-local from JWT).
     */
    @Transactional(readOnly = true)
    public WeightedGroupResultDTO computeForStudent(String studentId, Long groupId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(groupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + groupId));
        log.info("Computing weighted result for student={} group={} session={}", studentId, groupId, session);
        return compute(studentId, group, session, schoolId, 0);
    }

    /**
     * Weighted results for every student in a class for a group, with competition ranks by
     * weighted percentage. Read-only: the result is computed on demand and never persisted from a
     * read request (the assessment_group_result cache is no longer written).
     */
    @Transactional(readOnly = true)
    public List<StudentGroupResultDTO> computeAndRankForClass(
            List<String> studentIds, Map<String, String> studentNames,
            Long groupId, String session) {

        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(groupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + groupId));

        log.info("Computing class results for group={} session={} students={}", groupId, session, studentIds.size());

        List<StudentGroupResultDTO> results = new ArrayList<>();
        for (String studentId : studentIds) {
            try {
                WeightedGroupResultDTO r = compute(studentId, group, session, schoolId, 0);
                results.add(new StudentGroupResultDTO(studentId, studentNames.getOrDefault(studentId, ""),
                        r.getWeightedPercentage(), 0));
            } catch (Exception e) {
                log.warn("Failed to compute result for student={}: {}", studentId, e.getMessage());
                results.add(new StudentGroupResultDTO(studentId, studentNames.getOrDefault(studentId, ""), 0.0, 0));
            }
        }

        Map<String, Double> pct = new HashMap<>();
        results.forEach(r -> pct.put(r.getStudentId(), r.getWeightedPercentage()));
        Map<String, Integer> ranks = ResultCalculator.competitionRanks(pct);
        results.forEach(r -> r.setRank(ranks.getOrDefault(r.getStudentId(), 0)));
        return results;
    }

    // ── Private computation ────────────────────────────────────────────

    private WeightedGroupResultDTO compute(String studentId, AssessmentGroup group,
                                            String session, Long schoolId, int depth) {
        if (depth > 5) {
            throw new IllegalStateException("Assessment group cycle detected at group: " + group.getId());
        }

        if ("EXAM_BASED".equals(group.getGroupType())) {
            return computeExamBased(studentId, group, session, schoolId);
        } else {
            return computeGroupBased(studentId, group, session, schoolId, depth);
        }
    }

    private WeightedGroupResultDTO computeExamBased(String studentId, AssessmentGroup group,
                                                      String session, Long schoolId) {
        List<AssessmentGroupExamMapping> mappings =
                mappingRepo.findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

        if (mappings.isEmpty()) {
            return emptyResult(group);
        }

        // Batch: load all subject entries for all exams in this group
        List<Long> examConfigIds = mappings.stream()
                .map(AssessmentGroupExamMapping::getExamConfigId)
                .collect(Collectors.toList());

        List<ExamSubjectEntry> allSubjects =
                subjectEntryRepo.findByExamConfigIdInAndSchoolId(examConfigIds, schoolId);

        List<Long> allSubjectEntryIds = allSubjects.stream()
                .map(ExamSubjectEntry::getId)
                .collect(Collectors.toList());

        // Batch: load all marks for this student across all subject entries
        Map<Long, StudentMark> markBySubjectEntryId = allSubjectEntryIds.isEmpty() ? Collections.emptyMap()
                : markRepo.findByStudentIdAndExamSubjectEntryIdInAndSchoolId(studentId, allSubjectEntryIds, schoolId)
                        .stream().collect(Collectors.toMap(StudentMark::getExamSubjectEntryId, m -> m, (a, b) -> a));

        // Group subjects by examConfigId
        Map<Long, List<ExamSubjectEntry>> subjectsByExam = allSubjects.stream()
                .collect(Collectors.groupingBy(ExamSubjectEntry::getExamConfigId));

        // Build per-exam breakdowns and accumulate subject weighted percentages
        List<ExamBreakdownDTO> examBreakdowns = new ArrayList<>();
        Map<String, Double> subjectWeightedPcts = new LinkedHashMap<>();
        double totalWeightedPct = 0.0;

        // MarksTable tracking structures
        List<ExamConfig> orderedExams = new ArrayList<>();
        Map<Long, double[]> examObtainedMaxByExam = new LinkedHashMap<>(); // [obtained, max]
        Map<Long, Map<String, MarksTableDTO.SubjectExamMarkDTO>> perExamPerSubjectMarks = new LinkedHashMap<>();
        Set<String> orderedSubjectNames = new LinkedHashSet<>();

        int marksMissing = 0;
        for (AssessmentGroupExamMapping mapping : mappings) {
            Optional<ExamConfig> examOpt = examConfigRepo.findById(mapping.getExamConfigId());
            if (examOpt.isEmpty() || !schoolId.equals(examOpt.get().getSchoolId())) continue;
            ExamConfig exam = examOpt.get();

            List<ExamSubjectEntry> subjects = markService.applicableEntriesForStudent(studentId, exam,
                    subjectsByExam.getOrDefault(exam.getId(), Collections.emptyList()));
            if (subjects.isEmpty()) continue;

            orderedExams.add(exam);
            Map<String, MarksTableDTO.SubjectExamMarkDTO> subjectMarkMap = new LinkedHashMap<>();
            double examObtained = 0.0;
            double examMax = 0.0;

            List<ResultCalculator.SubjectMark> canonical = new ArrayList<>();
            for (ExamSubjectEntry subject : subjects) {
                StudentMark mark = markBySubjectEntryId.get(subject.getId());
                Double obtained = (mark != null) ? mark.getMarksObtained() : null;
                double obtainedVal = (obtained != null) ? obtained : 0.0;
                canonical.add(new ResultCalculator.SubjectMark(subject.getMaxMarks(), obtained));
                examObtained += obtainedVal;
                examMax += subject.getMaxMarks();

                // Per-subject weighted contribution
                double subjectPct = subject.getMaxMarks() > 0
                        ? (obtainedVal / subject.getMaxMarks()) * 100.0 : 0.0;
                double subjectContrib = subjectPct * mapping.getWeightage().doubleValue();
                subjectWeightedPcts.merge(subject.getSubjectName(), subjectContrib, Double::sum);

                // MarksTable: record per-subject per-exam mark (null obtained = absent)
                double markPct = subject.getMaxMarks() > 0
                        ? (obtainedVal / subject.getMaxMarks()) * 100.0 : 0.0;
                subjectMarkMap.put(subject.getSubjectName(),
                        new MarksTableDTO.SubjectExamMarkDTO(obtained, subject.getMaxMarks(), markPct));
                orderedSubjectNames.add(subject.getSubjectName());
            }

            ResultCalculator.Score examScore = ResultCalculator.score(canonical, null);
            marksMissing += examScore.marksMissing();
            double examPct = examScore.percentageCountingMissingAsAbsent();
            double weight = mapping.getWeightage().doubleValue();
            double contribution = examPct * weight;
            totalWeightedPct += contribution;

            examBreakdowns.add(new ExamBreakdownDTO(
                    exam.getId(), exam.getExamName(),
                    examObtained, examMax, examPct, weight, contribution));

            examObtainedMaxByExam.put(exam.getId(), new double[]{examObtained, examMax});
            perExamPerSubjectMarks.put(exam.getId(), subjectMarkMap);
        }

        List<SubjectWeightedResultDTO> subjectResults = subjectWeightedPcts.entrySet().stream()
                .map(e -> new SubjectWeightedResultDTO(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        // Build MarksTableDTO
        List<MarksTableDTO.ExamColumnDTO> examColumns = new ArrayList<>();
        for (ExamConfig exam : orderedExams) {
            double maxTotal = subjectsByExam.getOrDefault(exam.getId(), Collections.emptyList())
                    .stream().mapToDouble(ExamSubjectEntry::getMaxMarks).sum();
            double w = mappings.stream()
                    .filter(m -> m.getExamConfigId().equals(exam.getId()))
                    .findFirst().map(m -> m.getWeightage().doubleValue()).orElse(0.0);
            examColumns.add(new MarksTableDTO.ExamColumnDTO(exam.getId(), exam.getExamName(), maxTotal, w));
        }

        List<MarksTableDTO.SubjectRowDTO> subjectRows = new ArrayList<>();
        for (String subjectName : orderedSubjectNames) {
            List<MarksTableDTO.SubjectExamMarkDTO> examMarks = new ArrayList<>();
            for (ExamConfig exam : orderedExams) {
                Map<String, MarksTableDTO.SubjectExamMarkDTO> subjectMap =
                        perExamPerSubjectMarks.getOrDefault(exam.getId(), Collections.emptyMap());
                examMarks.add(subjectMap.get(subjectName)); // null if subject not in this exam
            }
            subjectRows.add(new MarksTableDTO.SubjectRowDTO(
                    subjectName, examMarks, subjectWeightedPcts.getOrDefault(subjectName, 0.0)));
        }

        List<MarksTableDTO.ExamTotalDTO> examTotals = new ArrayList<>();
        for (ExamConfig exam : orderedExams) {
            double[] om = examObtainedMaxByExam.getOrDefault(exam.getId(), new double[]{0.0, 0.0});
            examTotals.add(new MarksTableDTO.ExamTotalDTO(om[0], om[1]));
        }

        MarksTableDTO marksTable = new MarksTableDTO(examColumns, subjectRows, examTotals);

        WeightedGroupResultDTO result = new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                totalWeightedPct, subjectResults, examBreakdowns, null, marksTable, 0);
        result.setMarksMissing(marksMissing);
        return result;
    }

    private WeightedGroupResultDTO computeGroupBased(String studentId, AssessmentGroup group,
                                                      String session, Long schoolId, int depth) {
        List<AssessmentGroupComposition> compositions =
                compositionRepo.findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId);

        if (compositions.isEmpty()) {
            return emptyResult(group);
        }

        List<GroupBreakdownDTO> groupBreakdowns = new ArrayList<>();
        double totalWeightedPct = 0.0;
        int marksMissing = 0;
        // Subject summary for grouped cards (e.g. Annual = Term 1 + Term 2): the children's exam
        // columns side by side, each subject's weighted % = Σ child subject % × child weight.
        List<MarksTableDTO.ExamColumnDTO> columns = new ArrayList<>();
        List<MarksTableDTO.ExamTotalDTO> totals = new ArrayList<>();
        Map<String, List<MarksTableDTO.SubjectExamMarkDTO>> subjectCells = new LinkedHashMap<>();
        Map<String, Double> subjectWeighted = new LinkedHashMap<>();

        for (AssessmentGroupComposition comp : compositions) {
            AssessmentGroup childGroup = groupRepo.findByIdAndSchoolId(comp.getChildGroupId(), schoolId)
                    .orElse(null);
            if (childGroup == null) continue;

            WeightedGroupResultDTO childResult = compute(studentId, childGroup, session, schoolId, depth + 1);
            marksMissing += childResult.getMarksMissing();
            double weight = comp.getWeightage().doubleValue();
            mergeChildTable(childResult, weight, columns, totals, subjectCells, subjectWeighted);
            double contribution = childResult.getWeightedPercentage() * weight;
            totalWeightedPct += contribution;

            groupBreakdowns.add(new GroupBreakdownDTO(
                    childGroup.getId(), childGroup.getName(),
                    childResult.getWeightedPercentage(), weight, contribution));
        }

        List<MarksTableDTO.SubjectRowDTO> rows = new ArrayList<>();
        List<SubjectWeightedResultDTO> subjectResults = new ArrayList<>();
        subjectCells.forEach((subject, cells) -> {
            double pct = subjectWeighted.getOrDefault(subject, 0.0);
            rows.add(new MarksTableDTO.SubjectRowDTO(subject, cells, pct));
            subjectResults.add(new SubjectWeightedResultDTO(subject, pct));
        });
        MarksTableDTO marksTable = columns.isEmpty() ? null : new MarksTableDTO(columns, rows, totals);
        WeightedGroupResultDTO result = new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                totalWeightedPct, subjectResults, null, groupBreakdowns, marksTable, 0);
        result.setMarksMissing(marksMissing);
        return result;
    }

    /**
     * Appends one child group's marks table to its parent's: the child's exam columns (weight
     * scaled by the child's share), its totals, and per subject its cells (blank where the
     * subject is not in that child) plus the child's subject % × the child's weight.
     */
    private static void mergeChildTable(WeightedGroupResultDTO child, double weight,
                                        List<MarksTableDTO.ExamColumnDTO> columns,
                                        List<MarksTableDTO.ExamTotalDTO> totals,
                                        Map<String, List<MarksTableDTO.SubjectExamMarkDTO>> subjectCells,
                                        Map<String, Double> subjectWeighted) {
        MarksTableDTO table = child.getMarksTable();
        if (table == null || table.getExamColumns() == null || table.getExamColumns().isEmpty()) return;
        int before = columns.size();
        int width = table.getExamColumns().size();
        for (MarksTableDTO.ExamColumnDTO c : table.getExamColumns()) {
            columns.add(new MarksTableDTO.ExamColumnDTO(c.getExamId(), c.getExamName(), c.getMaxTotal(), c.getWeightage() * weight));
        }
        totals.addAll(table.getExamTotals());
        for (MarksTableDTO.SubjectRowDTO row : table.getSubjectRows()) {
            List<MarksTableDTO.SubjectExamMarkDTO> cells = subjectCells.computeIfAbsent(row.getSubjectName(),
                    k -> new ArrayList<>(Collections.nCopies(before, null)));
            while (cells.size() < before) cells.add(null);
            cells.addAll(row.getExamMarks());
            subjectWeighted.merge(row.getSubjectName(), row.getWeightedPercentage() * weight, Double::sum);
        }
        // Subjects this child does not have stay blank in its columns.
        subjectCells.values().forEach(cells -> { while (cells.size() < before + width) cells.add(null); });
    }

    // ── Class ranks (canonical, section-aware) ──────────────────────────

    /**
     * Rank of every student of the group's class by the group's weighted percentage, using the
     * same ResultCalculator competition ranking as Class Results / My Results: within the
     * student's section (the latest enrollment segment, from the exam sheets), rounded to 2 dp,
     * ties share a rank, and a student with any missing mark is unranked. The weighted percentage
     * is the one computeForStudent produces (Σ exam % × weight, recursively for groups), built
     * from the canonical exam sheets in a few bulk queries per exam.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> classRanks(Long groupId, String session) {
        Long schoolId = securityUtil.getSchoolId();
        AssessmentGroup group = groupRepo.findByIdAndSchoolId(groupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Assessment group not found: " + groupId));
        Map<Long, MarkService.ExamSheet> sheets = new HashMap<>();
        Map<String, Double> weighted = new HashMap<>();
        Map<String, Integer> missing = new HashMap<>();
        Map<String, Long> section = new HashMap<>();
        accumulateClass(group, 1.0, schoolId, sheets, weighted, missing, section, 0);
        Map<String, Double> percentage = new HashMap<>();
        weighted.forEach((id, pct) -> percentage.put(id, missing.getOrDefault(id, 0) > 0 ? null : pct));
        return ResultCalculator.competitionRanksWithin(percentage, section::get);
    }

    private void accumulateClass(AssessmentGroup group, double factor, Long schoolId,
                                 Map<Long, MarkService.ExamSheet> sheets, Map<String, Double> weighted,
                                 Map<String, Integer> missing, Map<String, Long> section, int depth) {
        if (depth > 5) throw new IllegalStateException("Assessment group cycle detected at group: " + group.getId());
        if ("EXAM_BASED".equals(group.getGroupType())) {
            for (AssessmentGroupExamMapping mapping :
                    mappingRepo.findByAssessmentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId)) {
                ExamConfig exam = examConfigRepo.findById(mapping.getExamConfigId()).orElse(null);
                if (exam == null || !schoolId.equals(exam.getSchoolId())) continue;
                MarkService.ExamSheet sheet = sheets.computeIfAbsent(exam.getId(), id -> markService.buildExamSheet(
                        exam, subjectEntryRepo.findByExamConfigIdAndSchoolId(id, schoolId)));
                double weight = mapping.getWeightage().doubleValue() * factor;
                for (MarkService.SheetRow row : sheet.rows()) {
                    if (row.entries().isEmpty()) continue;
                    String id = row.student().getStudentId();
                    weighted.merge(id, row.score().percentageCountingMissingAsAbsent() * weight, Double::sum);
                    missing.merge(id, row.score().marksMissing(), Integer::sum);
                    section.put(id, row.sectionId());
                }
            }
        } else {
            for (AssessmentGroupComposition comp :
                    compositionRepo.findByParentGroupIdAndSchoolIdOrderByDisplayOrderAsc(group.getId(), schoolId)) {
                AssessmentGroup child = groupRepo.findByIdAndSchoolId(comp.getChildGroupId(), schoolId).orElse(null);
                if (child != null) accumulateClass(child, factor * comp.getWeightage().doubleValue(),
                        schoolId, sheets, weighted, missing, section, depth + 1);
            }
        }
    }


    private WeightedGroupResultDTO emptyResult(AssessmentGroup group) {
        return new WeightedGroupResultDTO(
                group.getId(), group.getName(), group.getGroupType(),
                0.0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), null, 0);
    }
}
