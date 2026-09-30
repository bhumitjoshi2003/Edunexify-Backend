package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ParentDtos;
import com.indraacademy.ias_management.dto.ParentFilterDTO;
import com.indraacademy.ias_management.entity.Parent;
import com.indraacademy.ias_management.entity.ParentStudentRelationship;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.ParentStudentRelationshipRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.specification.ParentSpecification;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ParentPortalService {
    public enum ChildPermission { ATTENDANCE, FEES, PAY_FEES, RESULTS, TIMETABLE, MANAGE_LEAVE }
    private final ParentRepository parentRepository;
    private final ParentStudentRelationshipRepository relationshipRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityUtil securityUtil;
    private final EntitlementService entitlementService;
    private final IdGeneratorService idGeneratorService;
    private final PasswordResetService passwordResetService;
    private final com.indraacademy.ias_management.repository.SchoolRepository schoolRepository;
    private final UserSessionService userSessionService;

    public ParentPortalService(ParentRepository parentRepository,
                               ParentStudentRelationshipRepository relationshipRepository,
                               StudentRepository studentRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               SecurityUtil securityUtil,
                               EntitlementService entitlementService,
                               IdGeneratorService idGeneratorService,
                               PasswordResetService passwordResetService,
                               com.indraacademy.ias_management.repository.SchoolRepository schoolRepository,
                               UserSessionService userSessionService) {
        this.parentRepository = parentRepository;
        this.relationshipRepository = relationshipRepository;
        this.studentRepository = studentRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityUtil = securityUtil;
        this.entitlementService = entitlementService;
        this.idGeneratorService = idGeneratorService;
        this.passwordResetService = passwordResetService;
        this.schoolRepository = schoolRepository;
        this.userSessionService = userSessionService;
    }

    /**
     * Paginated, filterable parent directory for the admin management screen. Avoids the
     * N+1 pattern of the old unpaginated listing (one relationship query, plus one student
     * lookup per relationship, per parent): the page's link counts are computed with a
     * single grouped aggregate query (see ParentStudentRelationshipRepository#countActiveByParentIds).
     */
    @Transactional(readOnly = true)
    public Page<ParentDtos.ParentSummary> listParentsPaged(ParentFilterDTO filter, Pageable pageable) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        filter.setSchoolId(schoolId);
        Page<Parent> page = parentRepository.findAll(ParentSpecification.filter(filter), pageable);
        List<String> parentIds = page.getContent().stream().map(Parent::getParentId).toList();
        Map<String, Long> linkCounts = parentIds.isEmpty() ? Map.of()
                : relationshipRepository.countActiveByParentIds(schoolId, parentIds).stream()
                        .collect(Collectors.toMap(ParentStudentRelationshipRepository.ParentLinkCount::getParentId,
                                ParentStudentRelationshipRepository.ParentLinkCount::getTotal));
        List<ParentDtos.ParentSummary> summaries = page.getContent().stream()
                .map(parent -> new ParentDtos.ParentSummary(parent.getParentId(), parent.getName(), parent.getEmail(),
                        parent.getPhoneNumber(), parent.isActive(),
                        linkCounts.getOrDefault(parent.getParentId(), 0L).intValue()))
                .toList();
        return new PageImpl<>(summaries, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ParentDtos.ParentDirectoryStats directoryStats() {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        long totalParents = parentRepository.countBySchoolId(schoolId);
        long activeParents = parentRepository.countBySchoolIdAndActiveTrue(schoolId);
        long linkedStudents = relationshipRepository.countBySchoolIdAndActiveTrue(schoolId);
        long parentsWithLinks = relationshipRepository.countDistinctActiveParents(schoolId);
        return new ParentDtos.ParentDirectoryStats(totalParents, activeParents, linkedStudents,
                totalParents - parentsWithLinks);
    }

    /**
     * Creates a new Parent account with an Edunexify-generated parentId (par_YYnnnnnn) and
     * Option A onboarding: the account's initial password is a long random value that is
     * never stored in plaintext, never logged, never returned in this method's response, and
     * never communicated to anyone — nobody, including the creating admin, ever needs to
     * know it, because the parent's actual first password is whatever they set via the
     * secure emailed link sent by {@link PasswordResetService#sendParentWelcomeLink}. This is
     * exactly the same mechanism the existing self-service "forgot password" flow already
     * uses, not a second authentication path built specifically for Parent.
     */
    @Transactional
    public ParentDtos.ParentProfile createParent(ParentDtos.CreateParentRequest request) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        String phoneNumber = request.phoneNumber().trim();
        String email = normalizeEmail(request.email());
        if (parentRepository.existsByPhoneNumberAndSchoolId(phoneNumber, schoolId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A parent account with this phone number already exists in this school");
        }
        if (email != null && parentRepository.existsByEmailIgnoreCaseAndSchoolId(email, schoolId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A parent account with this email already exists in this school");
        }

        String parentId = idGeneratorService.generateParentId();

        Parent parent = new Parent();
        parent.setParentId(parentId);
        parent.setSchoolId(schoolId);
        parent.setName(request.name().trim());
        parent.setEmail(email);
        parent.setPhoneNumber(phoneNumber);
        parent.setActive(true);
        parentRepository.save(parent);

        User user = new User();
        user.setUserId(parentId);
        user.setSchoolId(schoolId);
        user.setRole(Role.PARENT);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(generateUnusedPlaceholderPassword()));
        user.setMustChangePassword(true);
        user.setActive(true);
        userRepository.save(user);

        String schoolName = schoolRepository.findById(schoolId)
                .map(com.indraacademy.ias_management.entity.School::getName).orElse("your school");
        passwordResetService.sendParentWelcomeLink(user, parent.getName(), schoolName);

        return profile(parent, schoolId, false);
    }

    /** A random value nobody will ever type — the real first password is whatever the parent
     *  sets via the emailed link. Never logged, never returned, never persisted anywhere
     *  except as its bcrypt hash on the User row. */
    private String generateUnusedPlaceholderPassword() {
        byte[] randomBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(randomBytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    @Transactional
    public ParentDtos.ParentProfile linkStudent(String parentId, ParentDtos.LinkStudentRequest request) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        Parent parent = findParent(parentId, schoolId);
        Student student = studentRepository.findByStudentIdAndSchoolId(request.studentId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An exited student cannot be linked to an active parent account");
        }
        ParentStudentRelationship link = relationshipRepository
                .findBySchoolIdAndParentIdAndStudentId(schoolId, parentId, student.getStudentId())
                .orElseGet(ParentStudentRelationship::new);
        link.setSchoolId(schoolId);
        link.setParentId(parentId);
        link.setStudentId(student.getStudentId());
        link.setRelationshipType(request.relationshipType().trim().toUpperCase());
        if (request.primaryGuardian()) {
            relationshipRepository
                    .findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, student.getStudentId())
                    .stream()
                    .filter(existing -> !parentId.equals(existing.getParentId()))
                    .filter(ParentStudentRelationship::isPrimaryGuardian)
                    .forEach(existing -> {
                        existing.setPrimaryGuardian(false);
                        relationshipRepository.save(existing);
                    });
        }
        link.setPrimaryGuardian(request.primaryGuardian());
        boolean canViewFees = defaultTrue(request.canViewFees());
        boolean canPayFees = defaultTrue(request.canPayFees());
        if (canPayFees && !canViewFees) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Fee payment permission requires fee viewing permission");
        }
        link.setCanViewAttendance(defaultTrue(request.canViewAttendance()));
        link.setCanViewFees(canViewFees);
        link.setCanPayFees(canPayFees);
        link.setCanViewResults(defaultTrue(request.canViewResults()));
        link.setCanViewTimetable(defaultTrue(request.canViewTimetable()));
        link.setCanManageLeave(defaultTrue(request.canManageLeave()));
        link.setEffectiveFrom(request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom());
        link.setEffectiveUntil(request.effectiveUntil());
        if (link.getEffectiveUntil() != null && link.getEffectiveUntil().isBefore(link.getEffectiveFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Effective-until date cannot precede effective-from date");
        }
        link.setActive(true);
        relationshipRepository.save(link);
        return profile(parent, schoolId, false);
    }

    /**
     * Ends all current guardian relationships when a student exits. This is an
     * internal lifecycle operation and deliberately does not depend on the
     * school's current Parent Portal entitlement.
     */
    @Transactional
    public void endRelationshipsForExitedStudent(Long schoolId, String studentId, LocalDate leavingDate) {
        LocalDate endDate = leavingDate == null ? LocalDate.now() : leavingDate;
        relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, studentId)
                .stream()
                .filter(ParentStudentRelationship::isActive)
                .forEach(link -> {
                    link.setActive(false);
                    // Preserve the entity's date invariant for a future-dated link.
                    link.setEffectiveUntil(endDate.isBefore(link.getEffectiveFrom())
                            ? link.getEffectiveFrom() : endDate);
                    relationshipRepository.save(link);
                });
    }

    /**
     * Parent links that ended between {@code since} (the student's last exit) and {@code until}
     * (the readmission date), whose parent account is still active — candidates an admin may
     * choose to restore after readmission. Links an admin removed before the exit or after the
     * readmission are not offered. Empty when the portal is not enabled.
     */
    @Transactional(readOnly = true)
    public List<com.indraacademy.ias_management.dto.StudentAdmissionDtos.RestorableParentLink> endedLinksSince(
            Long schoolId, String studentId, LocalDate since, LocalDate until) {
        if (since == null || until == null || !entitlementService.hasFeature(schoolId, "PARENT_PORTAL")) return List.of();
        return relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, studentId).stream()
                .filter(link -> !link.isActive())
                .filter(link -> link.getEffectiveUntil() != null && !link.getEffectiveUntil().isBefore(since)
                        && !link.getEffectiveUntil().isAfter(until))
                .flatMap(link -> parentRepository.findByParentIdAndSchoolId(link.getParentId(), schoolId)
                        .filter(Parent::isActive)
                        .map(parent -> new com.indraacademy.ias_management.dto.StudentAdmissionDtos.RestorableParentLink(
                                link.getId(), parent.getParentId(), parent.getName(), link.getRelationshipType(),
                                link.isPrimaryGuardian(), link.getEffectiveUntil()))
                        .stream())
                .toList();
    }

    /**
     * Restores exactly the links an admin confirmed. Each must belong to this student and school,
     * be one of {@link #endedLinksSince}'s candidates, and the student must be enrolled again.
     */
    @Transactional
    public int restoreLinks(Long schoolId, String studentId, LocalDate since, LocalDate until, List<Long> relationshipIds) {
        requireFeature(schoolId);
        if (relationshipIds == null || relationshipIds.isEmpty()) return 0;
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent access can only be restored for an enrolled student");
        }
        java.util.Set<Long> allowed = endedLinksSince(schoolId, studentId, since, until).stream()
                .map(com.indraacademy.ias_management.dto.StudentAdmissionDtos.RestorableParentLink::relationshipId)
                .collect(java.util.stream.Collectors.toSet());
        int restored = 0;
        for (Long id : new java.util.LinkedHashSet<>(relationshipIds)) {
            if (!allowed.contains(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent link " + id + " can't be restored for this student");
            }
            ParentStudentRelationship link = relationshipRepository.findByIdAndSchoolId(id, schoolId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent/student link not found"));
            link.setActive(true);
            link.setEffectiveFrom(LocalDate.now());
            link.setEffectiveUntil(null);
            relationshipRepository.save(link);
            restored++;
        }
        return restored;
    }

    @Transactional
    public void unlinkStudent(String parentId, Long relationshipId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        ParentStudentRelationship link = relationshipRepository.findByIdAndSchoolId(relationshipId, schoolId)
                .filter(item -> item.getParentId().equals(parentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent/student link not found"));
        link.setActive(false);
        link.setEffectiveUntil(LocalDate.now());
        relationshipRepository.save(link);
    }

    @Transactional
    public void setParentActive(String parentId, boolean active) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        Parent parent = findParent(parentId, schoolId);
        parent.setActive(active);
        parentRepository.save(parent);
        User user = userRepository.findByUserId(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent login not found"));
        if (!schoolId.equals(user.getSchoolId()) || !Role.PARENT.equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Parent login does not belong to this school");
        }
        user.setActive(active);
        if (!active) userSessionService.revokeAllForUser(user.getUserId());
        userRepository.save(user);
    }

    /**
     * ADMIN-only password reset for a parent login whose original temporary password is
     * unknown. There is no DOB (or similar) to fall back to for a Parent the way
     * AuthController#resetToDefaultPassword does for Student/Teacher, so this mirrors the
     * only other established convention for setting a parent's password — an admin-supplied
     * temporary value, exactly as already used at account creation (createParent above).
     */
    @Transactional
    public void resetPassword(String parentId, String temporaryPassword) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        findParent(parentId, schoolId);
        validatePasswordStrength(temporaryPassword);
        User user = userRepository.findByUserId(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent login not found"));
        if (!schoolId.equals(user.getSchoolId()) || !Role.PARENT.equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Parent login does not belong to this school");
        }
        user.setPassword(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        userSessionService.revokeAllForUser(user.getUserId());
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public ParentDtos.ParentProfile myProfile() {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        String parentId = securityUtil.getUsername();
        return profile(findParent(parentId, schoolId), schoolId, true);
    }

    @Transactional(readOnly = true)
    public ParentDtos.ParentProfile getParent(String parentId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        return profile(findParent(parentId, schoolId), schoolId, false);
    }

    public void assertChildAccess(String studentId) {
        activeChildLink(studentId);
    }

    public void assertChildAccess(String studentId, ChildPermission permission) {
        ParentStudentRelationship link = activeChildLink(studentId);
        boolean allowed = switch (permission) {
            case ATTENDANCE -> link.isCanViewAttendance();
            case FEES -> link.isCanViewFees();
            case PAY_FEES -> link.isCanPayFees() && link.isCanViewFees();
            case RESULTS -> link.isCanViewResults();
            case TIMETABLE -> link.isCanViewTimetable();
            case MANAGE_LEAVE -> link.isCanManageLeave();
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This parent account does not have " + permission.name().toLowerCase().replace('_', ' ') + " access");
        }
    }

    /**
     * Canonical recipient resolver for internal school workflows such as notification
     * publication. It deliberately applies the same relationship dates, active-parent,
     * tenant and exited-student rules as the Parent Portal authorization path.
     */
    @Transactional(readOnly = true)
    public List<String> findActiveParentIdsForStudents(Long schoolId,
                                                       Collection<String> studentIds,
                                                       LocalDate asOf) {
        return findActiveParentIdsForStudents(schoolId, studentIds, asOf, null);
    }

    @Transactional(readOnly = true)
    public List<String> findActiveParentIdsForStudents(Long schoolId,
                                                       Collection<String> studentIds,
                                                       LocalDate asOf,
                                                       ChildPermission requiredPermission) {
        if (schoolId == null || studentIds == null || studentIds.isEmpty()) {
            return List.of();
        }
        LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
        LinkedHashSet<String> parentIds = new LinkedHashSet<>();
        for (String studentId : studentIds) {
            boolean eligibleStudent = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                    .map(student -> student.getStatus() == null || !student.getStatus().isExitStatus())
                    .orElse(false);
            if (!eligibleStudent) continue;

            relationshipRepository.findBySchoolIdAndStudentIdOrderByPrimaryGuardianDesc(schoolId, studentId)
                    .stream()
                    .filter(ParentStudentRelationship::isActive)
                    .filter(link -> !link.getEffectiveFrom().isAfter(effectiveDate))
                    .filter(link -> link.getEffectiveUntil() == null
                            || !link.getEffectiveUntil().isBefore(effectiveDate))
                    .filter(link -> hasPermission(link, requiredPermission))
                    .map(ParentStudentRelationship::getParentId)
                    .filter(parentId -> parentRepository.findByParentIdAndSchoolId(parentId, schoolId)
                            .map(Parent::isActive).orElse(false))
                    .filter(parentId -> userRepository
                            .findByUserIdAndSchoolIdAndActiveTrue(parentId, schoolId).isPresent())
                    .forEach(parentIds::add);
        }
        return List.copyOf(parentIds);
    }

    private boolean hasPermission(ParentStudentRelationship link, ChildPermission permission) {
        if (permission == null) return true;
        return switch (permission) {
            case ATTENDANCE -> link.isCanViewAttendance();
            case FEES -> link.isCanViewFees();
            case PAY_FEES -> link.isCanPayFees() && link.isCanViewFees();
            case RESULTS -> link.isCanViewResults();
            case TIMETABLE -> link.isCanViewTimetable();
            case MANAGE_LEAVE -> link.isCanManageLeave();
        };
    }

    private ParentStudentRelationship activeChildLink(String studentId) {
        Long schoolId = schoolId();
        requireFeature(schoolId);
        LocalDate today = LocalDate.now();
        ParentStudentRelationship link = relationshipRepository
                .findBySchoolIdAndParentIdAndStudentId(schoolId, securityUtil.getUsername(), studentId)
                .filter(ParentStudentRelationship::isActive)
                .filter(rel -> !rel.getEffectiveFrom().isAfter(today))
                .filter(rel -> rel.getEffectiveUntil() == null || !rel.getEffectiveUntil().isBefore(today))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have access to this student"));
        Student student = studentRepository.findByStudentIdAndSchoolId(studentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You do not have access to this student"));
        if (student.getStatus() != null && student.getStatus().isExitStatus()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Parent access is unavailable for students who have left the school");
        }
        return link;
    }

    private ParentDtos.ParentProfile profile(Parent parent, Long schoolId, boolean activeOnly) {
        LocalDate today = LocalDate.now();
        List<ParentDtos.ChildAccess> children = relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(schoolId, parent.getParentId()).stream()
                // Inactive rows are retained for audit/history after unlinking, but they are not
                // current guardian links and must never be rendered as linked students.
                .filter(ParentStudentRelationship::isActive)
                .filter(link -> !activeOnly || (
                        !link.getEffectiveFrom().isAfter(today)
                        && (link.getEffectiveUntil() == null || !link.getEffectiveUntil().isBefore(today))))
                // Unconditional regardless of activeOnly — an exited student must never appear
                // in a parent's linked-child list, including the admin-facing getParent() view
                // (activeOnly=false), which previously relied only on relationship.active and
                // could show a stale link for a student who has since left the school.
                .filter(link -> studentRepository
                        .findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                        .map(student -> student.getStatus() == null || !student.getStatus().isExitStatus())
                        .orElse(false))
                .map(link -> childAccess(link, schoolId)).toList();
        return new ParentDtos.ParentProfile(summary(parent, schoolId), children);
    }

    private ParentDtos.ChildAccess childAccess(ParentStudentRelationship link, Long schoolId) {
        Student student = studentRepository.findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Linked student not found"));
        return new ParentDtos.ChildAccess(link.getId(), student.getStudentId(), student.getName(),
                student.getClassName(), student.getSectionName(), link.getRelationshipType(),
                link.isPrimaryGuardian(), link.isCanViewAttendance(), link.isCanViewFees(),
                link.isCanPayFees(), link.isCanViewResults(), link.isCanViewTimetable(), link.isCanManageLeave(),
                link.getEffectiveFrom(), link.getEffectiveUntil());
    }

    private ParentDtos.ParentSummary summary(Parent parent, Long schoolId) {
        int count = (int) relationshipRepository
                .findBySchoolIdAndParentIdOrderByPrimaryGuardianDescStudentIdAsc(schoolId, parent.getParentId()).stream()
                .filter(ParentStudentRelationship::isActive)
                .filter(link -> studentRepository.findByStudentIdAndSchoolId(link.getStudentId(), schoolId)
                        .map(student -> student.getStatus() == null || !student.getStatus().isExitStatus())
                        .orElse(false))
                .count();
        return new ParentDtos.ParentSummary(parent.getParentId(), parent.getName(), parent.getEmail(),
                parent.getPhoneNumber(), parent.isActive(), count);
    }

    private Parent findParent(String parentId, Long schoolId) {
        return parentRepository.findByParentIdAndSchoolId(parentId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent not found"));
    }

    private Long schoolId() {
        Long schoolId = securityUtil.getSchoolId();
        if (schoolId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "School context is required");
        return schoolId;
    }

    private void requireFeature(Long schoolId) { entitlementService.requireFeature(schoolId, "PARENT_PORTAL"); }
    /** Same rules as AuthController's password-change validation, so the strength requirement is consistent app-wide. */
    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("Password must contain at least one digit");
        }
    }
    private boolean defaultTrue(Boolean value) { return value == null || value; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeEmail(String value) {
        String email = blankToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }
}
