package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/**
 * Report Card V2 setup: which existing exams (Results Phase 1) make up a named card for one
 * school / academic session / class, optionally grouped into terms, and how they combine
 * (TOTAL or WEIGHTED). A flat list — no recursive group tree. Only an ADMIN changes a setup; a
 * teacher may read the setups of their own class (to enter remarks and preview cards).
 */
@Service
public class ReportCardSetupService {

    /** A4 portrait fits six exam columns at readable sizes (see the V2 renderer). */
    public static final int MAX_EXAMS = 6;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.001");

    @Autowired private ReportCardSetupRepository setupRepo;
    @Autowired private ReportCardSetupTermRepository termRepo;
    @Autowired private ReportCardSetupExamRepository setupExamRepo;
    @Autowired private ReportCardRemarkV2Repository remarkRepo;
    @Autowired private ReportCardCoScholasticV2Repository coScholasticRepo;
    @Autowired private ExamConfigRepository examConfigRepo;
    @Autowired private ExamSubjectEntryRepository subjectEntryRepo;
    @Autowired private AcademicSessionRepository sessionRepo;
    @Autowired private SchoolClassRepository classRepo;
    @Autowired private TeacherClassScopeService teacherScope;
    @Autowired private SecurityUtil securityUtil;
    @Autowired(required = false) private com.indraacademy.ias_management.repository.ReportCardPublicationV2Repository publicationRepo;

    // ── Reads ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SetupDTO> list(Long academicSessionId, Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession session = session(academicSessionId, schoolId);
        SchoolClass cls = schoolClass(classId, schoolId);
        requireClassReadable(cls, schoolId);
        return setupRepo.findBySchoolIdAndAcademicSessionIdAndClassIdOrderByDisplayOrderAscNameAsc(schoolId, session.getId(), cls.getId())
                .stream().map(s -> toDto(s, session, cls)).toList();
    }

    @Transactional(readOnly = true)
    public SetupDTO get(Long setupId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = readableSetup(setupId);
        return toDto(setup, session(setup.getAcademicSessionId(), schoolId), schoolClass(setup.getClassId(), schoolId));
    }

    /** Exams of the school's class and session an admin can put on a card. */
    @Transactional(readOnly = true)
    public List<AvailableExamDTO> availableExams(Long academicSessionId, Long classId) {
        Long schoolId = securityUtil.getSchoolId();
        AcademicSession session = session(academicSessionId, schoolId);
        SchoolClass cls = schoolClass(classId, schoolId);
        return examConfigRepo.findBySessionAndSchoolId(session.getLabel(), schoolId).stream()
                .filter(e -> belongsToClass(e, cls))
                .map(e -> new AvailableExamDTO(e.getId(), e.getExamName(), e.getResultStatus(),
                        subjectEntryRepo.findByExamConfigIdAndSchoolId(e.getId(), schoolId).size()))
                .toList();
    }

    /** The setup, if the caller may read it: an admin any in their school, a teacher their own class. */
    @Transactional(readOnly = true)
    public ReportCardSetup readableSetup(Long setupId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupRepo.findByIdAndSchoolId(setupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Report card setup not found: " + setupId));
        requireClassReadable(schoolClass(setup.getClassId(), schoolId), schoolId);
        return setup;
    }

    // ── Writes (ADMIN) ────────────────────────────────────────────────────

    @Transactional
    public SetupDTO create(SetupRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = new ReportCardSetup();
        setup.setSchoolId(schoolId);
        setup.setCreatedBy(securityUtil.getUsername());
        return save(setup, req, schoolId);
    }

    @Transactional
    public SetupDTO update(Long setupId, SetupRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupRepo.findByIdAndSchoolId(setupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Report card setup not found: " + setupId));
        if (!setup.getAcademicSessionId().equals(req.academicSessionId()) || !setup.getClassId().equals(req.classId())) {
            throw new IllegalArgumentException("A report card's session and class cannot be changed — create a new one instead.");
        }
        if (publicationRepo != null && publicationRepo.existsBySetupIdAndSchoolIdAndStatus(setupId, schoolId,
                com.indraacademy.ias_management.entity.ReportCardPublicationStatus.ACTIVE)) {
            throw new IllegalStateException("This report card is published. Withdraw it before changing its setup.");
        }
        return save(setup, req, schoolId);
    }

    /** Refused while remarks or co-scholastic grades exist for the card, so nothing is lost silently. */
    @Transactional
    public void delete(Long setupId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupRepo.findByIdAndSchoolId(setupId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Report card setup not found: " + setupId));
        if (remarkRepo.existsBySetupIdAndSchoolId(setupId, schoolId) || coScholasticRepo.existsBySetupIdAndSchoolId(setupId, schoolId)) {
            throw new IllegalStateException("This report card already has remarks or co-scholastic grades. "
                    + "Clear them before deleting the report card.");
        }
        if (publicationRepo != null && publicationRepo.existsBySetupIdAndSchoolId(setupId, schoolId)) {
            throw new IllegalStateException("This report card has been published, so it is kept for the record and cannot be deleted.");
        }
        setupExamRepo.deleteAllForSetup(setupId, schoolId);
        termRepo.deleteAllForSetup(setupId, schoolId);
        setupRepo.deleteById(setup.getId());
    }

    private SetupDTO save(ReportCardSetup setup, SetupRequest req, Long schoolId) {
        if (req == null) throw new IllegalArgumentException("Report card details are required.");
        AcademicSession session = session(req.academicSessionId(), schoolId);
        SchoolClass cls = schoolClass(req.classId(), schoolId);
        String name = req.name() == null ? "" : req.name().trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("Give the report card a name, e.g. Half Yearly or Annual.");
        if (name.length() > 100) throw new IllegalArgumentException("The report card name is too long (100 characters at most).");
        ReportCardResultMode mode = req.resultMode() != null ? req.resultMode() : ReportCardResultMode.TOTAL;
        boolean duplicate = setupRepo.findBySchoolIdAndAcademicSessionIdAndClassId(schoolId, session.getId(), cls.getId()).stream()
                .anyMatch(s -> !s.getId().equals(setup.getId()) && s.getName().trim().equalsIgnoreCase(name));
        if (duplicate) throw new IllegalArgumentException("Class " + cls.getName() + " already has a report card called \"" + name + "\" for " + session.getLabel() + ".");

        // Terms
        List<TermInput> terms = req.terms() != null ? req.terms() : List.of();
        Map<String, TermInput> termsByKey = new LinkedHashMap<>();
        Set<String> termNames = new HashSet<>();
        for (TermInput t : terms) {
            String tName = t.name() == null ? "" : t.name().trim();
            if (tName.isEmpty() || tName.length() > 60) throw new IllegalArgumentException("Each term needs a name (60 characters at most).");
            if (t.key() == null || t.key().isBlank()) throw new IllegalArgumentException("Term \"" + tName + "\" has no key.");
            if (!termNames.add(tName.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Term \"" + tName + "\" appears twice.");
            if (termsByKey.put(t.key(), t) != null) throw new IllegalArgumentException("Term key \"" + t.key() + "\" appears twice.");
        }

        // Exams: same school, session and class; no duplicates; at most MAX_EXAMS
        List<ExamInput> exams = req.exams() != null ? req.exams() : List.of();
        if (exams.isEmpty()) throw new IllegalArgumentException("Choose at least one exam for the report card.");
        if (exams.size() > MAX_EXAMS) throw new IllegalArgumentException("A report card can have at most " + MAX_EXAMS + " exams.");
        Set<Long> seen = new HashSet<>();
        Map<Long, ExamConfig> examById = new HashMap<>();
        for (ExamInput e : exams) {
            if (e.examConfigId() == null) throw new IllegalArgumentException("An exam is missing.");
            if (!seen.add(e.examConfigId())) throw new IllegalArgumentException("The same exam is chosen twice.");
            ExamConfig exam = examConfigRepo.findById(e.examConfigId())
                    .filter(x -> schoolId.equals(x.getSchoolId()))
                    .orElseThrow(() -> new IllegalArgumentException("Exam " + e.examConfigId() + " was not found in your school."));
            if (!session.getLabel().equals(exam.getSession()) || !belongsToClass(exam, cls)) {
                throw new IllegalArgumentException("\"" + exam.getExamName() + "\" is not an exam of class " + cls.getName()
                        + " in " + session.getLabel() + ".");
            }
            if (e.termKey() != null && !termsByKey.containsKey(e.termKey())) {
                throw new IllegalArgumentException("\"" + exam.getExamName() + "\" is assigned to a term that does not exist.");
            }
            examById.put(exam.getId(), exam);
        }
        for (String key : termsByKey.keySet()) {
            if (exams.stream().noneMatch(e -> key.equals(e.termKey()))) {
                throw new IllegalArgumentException("Term \"" + termsByKey.get(key).name().trim() + "\" has no exams.");
            }
        }
        validateWeights(mode, termsByKey, exams, examById);

        // Save: setup, then replace its terms and exams in the same transaction
        setup.setAcademicSessionId(session.getId());
        setup.setClassId(cls.getId());
        setup.setName(name);
        setup.setResultMode(mode);
        setup.setDisplayOrder(req.displayOrder() != null ? req.displayOrder() : 0);
        setup.setUpdatedBy(securityUtil.getUsername());
        ReportCardSetup saved = setupRepo.saveAndFlush(setup);

        setupExamRepo.deleteAllForSetup(saved.getId(), schoolId);
        termRepo.deleteAllForSetup(saved.getId(), schoolId);
        Map<String, Long> termIds = new HashMap<>();
        int order = 0;
        for (Map.Entry<String, TermInput> t : termsByKey.entrySet()) {
            ReportCardSetupTerm term = new ReportCardSetupTerm();
            term.setSchoolId(schoolId);
            term.setSetupId(saved.getId());
            term.setName(t.getValue().name().trim());
            term.setDisplayOrder(order++);
            term.setWeight(mode == ReportCardResultMode.WEIGHTED ? t.getValue().weight() : null);
            termIds.put(t.getKey(), termRepo.save(term).getId());
        }
        order = 0;
        for (ExamInput e : exams) {
            ReportCardSetupExam link = new ReportCardSetupExam();
            link.setSchoolId(schoolId);
            link.setSetupId(saved.getId());
            link.setExamConfigId(e.examConfigId());
            link.setTermId(e.termKey() != null ? termIds.get(e.termKey()) : null);
            link.setWeight(mode == ReportCardResultMode.WEIGHTED ? e.weight() : null);
            link.setDisplayOrder(order++);
            setupExamRepo.save(link);
        }
        setupExamRepo.flush();
        return toDto(setupRepo.findByIdAndSchoolId(saved.getId(), schoolId).orElseThrow(), session, cls);
    }

    /**
     * TOTAL: weights are not used (and must not be sent). WEIGHTED without terms: every exam has a
     * weight and they add up to 100%. WEIGHTED with terms: every exam is in a term; the terms'
     * weights add up to 100%; inside a term the exams either all have weights adding up to 100%,
     * or none do (the term is then its exams' total marks).
     */
    static void validateWeights(ReportCardResultMode mode, Map<String, TermInput> terms, List<ExamInput> exams,
                                Map<Long, ExamConfig> examById) {
        for (TermInput t : terms.values()) checkRange(t.weight(), "Term \"" + t.name().trim() + "\"");
        for (ExamInput e : exams) checkRange(e.weight(), "\"" + examById.get(e.examConfigId()).getExamName() + "\"");
        if (mode == ReportCardResultMode.TOTAL) {
            if (terms.values().stream().anyMatch(t -> t.weight() != null) || exams.stream().anyMatch(e -> e.weight() != null)) {
                throw new IllegalArgumentException("Weights are only used when the result is Weighted. Remove them or switch to Weighted.");
            }
            return;
        }
        if (terms.isEmpty()) {
            if (exams.stream().anyMatch(e -> e.weight() == null)) {
                throw new IllegalArgumentException("Give every exam a weight — they must add up to 100%.");
            }
            requireSumOne(exams.stream().map(ExamInput::weight).toList(), "The exam weights");
            return;
        }
        if (exams.stream().anyMatch(e -> e.termKey() == null)) {
            throw new IllegalArgumentException("With terms, put every exam in a term.");
        }
        if (terms.values().stream().anyMatch(t -> t.weight() == null)) {
            throw new IllegalArgumentException("Give every term a weight — they must add up to 100%.");
        }
        requireSumOne(terms.values().stream().map(TermInput::weight).toList(), "The term weights");
        for (Map.Entry<String, TermInput> t : terms.entrySet()) {
            List<BigDecimal> weights = exams.stream().filter(e -> t.getKey().equals(e.termKey())).map(ExamInput::weight).toList();
            long given = weights.stream().filter(Objects::nonNull).count();
            if (given == 0) continue;
            if (given != weights.size()) {
                throw new IllegalArgumentException("In term \"" + t.getValue().name().trim()
                        + "\", give every exam a weight or none (none = the term's total marks).");
            }
            requireSumOne(weights, "The exam weights in term \"" + t.getValue().name().trim() + "\"");
        }
    }

    private static void checkRange(BigDecimal w, String what) {
        if (w != null && (w.signum() <= 0 || w.compareTo(ONE) > 0)) {
            throw new IllegalArgumentException(what + ": a weight must be more than 0% and at most 100%.");
        }
    }

    private static void requireSumOne(List<BigDecimal> weights, String what) {
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(ONE).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
            throw new IllegalArgumentException(what + " must add up to 100% (they add up to "
                    + sum.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%).");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    static boolean belongsToClass(ExamConfig exam, SchoolClass cls) {
        return exam.getClassId() != null ? exam.getClassId().equals(cls.getId()) : cls.getName().equals(exam.getClassName());
    }

    private AcademicSession session(Long id, Long schoolId) {
        if (id == null) throw new IllegalArgumentException("Choose an academic session.");
        return sessionRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Academic session " + id + " was not found in your school."));
    }

    private SchoolClass schoolClass(Long id, Long schoolId) {
        if (id == null) throw new IllegalArgumentException("Choose a class.");
        return classRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Class " + id + " was not found in your school."));
    }

    /** A teacher reads only their own class's cards (fail closed without a class). */
    private void requireClassReadable(SchoolClass cls, Long schoolId) {
        if (!Role.TEACHER.equals(securityUtil.getRole())) return;
        TeacherClassScopeService.TeacherScope scope = teacherScope.resolveOwnScope(securityUtil.getUsername(), schoolId);
        if (!scope.hasClassResponsibility() || !scope.className().equals(cls.getName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only open report cards of your own class.");
        }
    }

    private SetupDTO toDto(ReportCardSetup s, AcademicSession session, SchoolClass cls) {
        Long schoolId = s.getSchoolId();
        List<ReportCardSetupTerm> terms = termRepo.findBySetupIdAndSchoolIdOrderByDisplayOrderAscIdAsc(s.getId(), schoolId);
        Map<Long, String> termNames = new HashMap<>();
        terms.forEach(t -> termNames.put(t.getId(), t.getName()));
        List<SetupExamDTO> exams = setupExamRepo.findBySetupIdAndSchoolIdOrderByDisplayOrderAscIdAsc(s.getId(), schoolId).stream()
                .map(l -> {
                    ExamConfig exam = examConfigRepo.findById(l.getExamConfigId()).orElse(null);
                    return new SetupExamDTO(l.getId(), l.getExamConfigId(), exam != null ? exam.getExamName() : "?",
                            exam != null ? exam.getResultStatus() : null, l.getTermId(),
                            l.getTermId() != null ? termNames.get(l.getTermId()) : null, l.getWeight(), l.getDisplayOrder());
                }).toList();
        return new SetupDTO(s.getId(), s.getAcademicSessionId(), session.getLabel(), s.getClassId(), cls.getName(), s.getName(),
                s.getResultMode(), s.getDisplayOrder(),
                terms.stream().map(t -> new TermDTO(t.getId(), t.getName(), t.getWeight(), t.getDisplayOrder())).toList(),
                exams, s.getRevision());
    }
}
