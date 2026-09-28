package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.entity.SchoolCoScholasticActivity;
import com.indraacademy.ias_management.entity.SchoolReportCardDesign;
import com.indraacademy.ias_management.repository.SchoolCoScholasticActivityRepository;
import com.indraacademy.ias_management.repository.SchoolReportCardDesignRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Report Card V2: the school's report-card design (one per school) and its co-scholastic
 * activity list. Always scoped to the caller's school; writes are ADMIN-only (controller).
 */
@Service
public class ReportCardDesignService {

    /** Co-scholastic grades a teacher can give (a blank grade means not yet graded). */
    public static final List<String> CO_SCHOLASTIC_GRADES = List.of("A", "B", "C", "D", "E");

    @Autowired private SchoolReportCardDesignRepository designRepo;
    @Autowired private SchoolCoScholasticActivityRepository activityRepo;
    @Autowired private SecurityUtil securityUtil;

    @Transactional(readOnly = true)
    public SchoolReportCardDesign designFor(Long schoolId) {
        return designRepo.findById(schoolId).orElseGet(() -> SchoolReportCardDesign.defaults(schoolId));
    }

    @Transactional(readOnly = true)
    public DesignDTO get() {
        return toDto(designFor(securityUtil.getSchoolId()));
    }

    @Transactional
    public DesignDTO update(DesignDTO req) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolReportCardDesign d = designRepo.findById(schoolId).orElseGet(() -> SchoolReportCardDesign.defaults(schoolId));
        d.setMotto(text(req.motto(), 150, "Motto"));
        d.setFooterText(text(req.footerText(), 300, "Footer text"));
        SchoolReportCardDesign.WatermarkMode mode;
        try {
            mode = SchoolReportCardDesign.WatermarkMode.valueOf(req.watermarkMode() == null ? "NONE" : req.watermarkMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Watermark must be None, Text or Logo.");
        }
        d.setWatermarkMode(mode);
        d.setWatermarkText(text(req.watermarkText(), 40, "Watermark text"));
        d.setShowPhoto(req.showPhoto());
        d.setShowQr(req.showQr());
        d.setShowAttendance(req.showAttendance());
        d.setShowCoScholastic(req.showCoScholastic());
        d.setShowTeacherRemark(req.showTeacherRemark());
        d.setShowPrincipalRemark(req.showPrincipalRemark());
        d.setShowRank(req.showRank());
        d.setShowPromotion(req.showPromotion());
        d.setTeacherSignatureLabel(label(req.teacherSignatureLabel(), "Class Teacher"));
        d.setPrincipalSignatureLabel(label(req.principalSignatureLabel(), "Principal"));
        d.setUpdatedBy(securityUtil.getUsername());
        return toDto(designRepo.saveAndFlush(d));
    }

    // ── Co-scholastic activities ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ActivityDTO> activities(boolean activeOnly) {
        Long schoolId = securityUtil.getSchoolId();
        return (activeOnly ? activityRepo.findBySchoolIdAndActiveTrueOrderByDisplayOrderAscIdAsc(schoolId)
                : activityRepo.findBySchoolIdOrderByDisplayOrderAscIdAsc(schoolId)).stream().map(ReportCardDesignService::toDto).toList();
    }

    @Transactional
    public ActivityDTO createActivity(ActivityRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        String name = activityName(req != null ? req.name() : null);
        List<SchoolCoScholasticActivity> existing = activityRepo.findBySchoolIdOrderByDisplayOrderAscIdAsc(schoolId);
        requireUniqueName(existing, name, null);
        SchoolCoScholasticActivity a = new SchoolCoScholasticActivity();
        a.setSchoolId(schoolId);
        a.setName(name);
        a.setActive(req.active() == null || req.active());
        a.setDisplayOrder(existing.stream().mapToInt(SchoolCoScholasticActivity::getDisplayOrder).max().orElse(-1) + 1);
        return toDto(activityRepo.save(a));
    }

    @Transactional
    public ActivityDTO updateActivity(Long id, ActivityRequest req) {
        Long schoolId = securityUtil.getSchoolId();
        SchoolCoScholasticActivity a = activityRepo.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Activity not found: " + id));
        if (req.name() != null) {
            String name = activityName(req.name());
            requireUniqueName(activityRepo.findBySchoolIdOrderByDisplayOrderAscIdAsc(schoolId), name, id);
            a.setName(name);
        }
        if (req.active() != null) a.setActive(req.active());
        return toDto(activityRepo.save(a));
    }

    /** New order of all the school's activities (every one exactly once). */
    @Transactional
    public List<ActivityDTO> reorder(List<Long> ids) {
        Long schoolId = securityUtil.getSchoolId();
        List<SchoolCoScholasticActivity> all = activityRepo.findBySchoolIdOrderByDisplayOrderAscIdAsc(schoolId);
        Set<Long> expected = new HashSet<>();
        all.forEach(a -> expected.add(a.getId()));
        if (ids == null || ids.size() != all.size() || !expected.equals(new HashSet<>(ids))) {
            throw new IllegalArgumentException("Send every activity exactly once to reorder them.");
        }
        Map<Long, SchoolCoScholasticActivity> byId = new HashMap<>();
        all.forEach(a -> byId.put(a.getId(), a));
        for (int i = 0; i < ids.size(); i++) byId.get(ids.get(i)).setDisplayOrder(i);
        activityRepo.saveAll(all);
        return activities(false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void requireUniqueName(List<SchoolCoScholasticActivity> existing, String name, Long self) {
        if (existing.stream().anyMatch(a -> !a.getId().equals(self) && a.getName().trim().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("An activity called \"" + name + "\" already exists.");
        }
    }

    private static String activityName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("An activity needs a name (80 characters at most).");
        return name;
    }

    private static String text(String raw, int max, String what) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.length() > max) throw new IllegalArgumentException(what + " is too long (" + max + " characters at most).");
        return t;
    }

    private static String label(String raw, String fallback) {
        String t = raw == null || raw.isBlank() ? fallback : raw.trim();
        if (t.length() > 60) throw new IllegalArgumentException("Signature labels are 60 characters at most.");
        return t;
    }

    private static DesignDTO toDto(SchoolReportCardDesign d) {
        return new DesignDTO(d.getMotto(), d.getFooterText(), d.getWatermarkMode().name(), d.getWatermarkText(),
                d.isShowPhoto(), d.isShowQr(), d.isShowAttendance(), d.isShowCoScholastic(), d.isShowTeacherRemark(),
                d.isShowPrincipalRemark(), d.isShowRank(), d.isShowPromotion(), d.getTeacherSignatureLabel(),
                d.getPrincipalSignatureLabel(), d.getUpdatedAt() == null ? null : d.getRevision());
    }

    private static ActivityDTO toDto(SchoolCoScholasticActivity a) {
        return new ActivityDTO(a.getId(), a.getName(), a.getDisplayOrder(), a.isActive());
    }
}
