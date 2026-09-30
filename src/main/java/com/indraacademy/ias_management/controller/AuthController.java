package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.RateLimiter;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ChangeInitialPasswordRequest;
import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.dto.LoginRequest;
import com.indraacademy.ias_management.entity.Admin;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.AdminRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.ParentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.AuditService;
import com.indraacademy.ias_management.service.AuthService;
import com.indraacademy.ias_management.service.PermissionService;
import com.indraacademy.ias_management.service.EmailService;
import com.indraacademy.ias_management.service.WelcomeEmailService;
import com.indraacademy.ias_management.service.PasswordResetService;
import com.indraacademy.ias_management.util.JwtUtil;
import com.indraacademy.ias_management.util.SchoolContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.WebUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    // PARENT is deliberately excluded — Parent accounts are created exclusively through
    // ParentPortalService.createParent (POST /api/parents), which generates the parentId,
    // creates both the Parent and User rows atomically, and sends the Option A account-setup
    // link. This endpoint's else-branch (below) would otherwise let a caller set an
    // admin-typed plaintext password for a PARENT-role User row with no corresponding Parent
    // domain row at all — bypassing the entire generated-ID/no-exposed-password design.
    private static final Set<String> VALID_ROLES = Set.of(
            Role.SUPER_ADMIN, Role.ADMIN, Role.SUB_ADMIN, Role.TEACHER, Role.STUDENT);

    @Autowired private UserRepository userRepository;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private EmailService emailService;
    @Autowired private WelcomeEmailService welcomeEmailService;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private AuthService authService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private TeacherRepository teacherRepository;
    @Autowired private AdminRepository adminRepository;
    @Autowired private ParentRepository parentRepository;
    @Autowired private com.indraacademy.ias_management.service.EntitlementService entitlementService;
    @Autowired private com.indraacademy.ias_management.repository.SchoolEffectiveEntitlementRepository entitlementRepo;
    @Autowired private RateLimiter rateLimiter;
    @Autowired private PermissionService permissionService;
    @Autowired private AuditService auditService;
    @Autowired private com.indraacademy.ias_management.service.UserSessionService userSessionService;

    @Value("${auth.cookie.secure}")
    private boolean isSecure;

    @Value("${auth.cookie.sameSite}")
    private String sameSite;

    @Value("${jwt.access-token.expiry-minutes}")
    private long accessTokenExpiryMinutes;

    @Value("${jwt.refresh-token.expiry-days}")
    private long refreshTokenExpiryDays;

    /** Cookie domain — must cover all school subdomains (e.g. "edunexify.co.in"). */
    @Value("${app.base-domain:edunexify.co.in}")
    private String cookieDomain;

    @GetMapping("/hari")
    public String message() {
        return "HARIBOL";
    }

    /**
     * Returns fresh user info from the database on every call.
     * The JwtAuthFilter validates the HttpOnly accessToken cookie and populates
     * the SecurityContext, so this endpoint is always authoritative — never stale.
     * Returns 401 automatically if the cookie is missing or expired.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        String userId = authService.getUserId();
        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
        }

        User user = userOptional.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getUserId());
        body.put("role", user.getRole());
        body.put("name", resolveName(user.getUserId(), user.getRole(), user.getSchoolId()));
        body.put("className", resolveClassName(user.getUserId(), user.getRole(), user.getSchoolId()));
        body.put("schoolSlug", resolveSchoolSlug(user.getSchoolId()));
        body.put("mustChangePassword", user.isMustChangePassword());

        // Entitlement fields — only for school-scoped users (not SUPER_ADMIN)
        appendEntitlementFields(body, user.getSchoolId());

        // Permission keys for the user's role at their school
        try {
            List<String> permKeys = permissionService.getPermissionKeysForRole(user.getRole(), user.getSchoolId());
            body.put("permissionKeys", permKeys);
        } catch (Exception e) {
            body.put("permissionKeys", List.of());
        }

        return ResponseEntity.ok(body);
    }

    /**
     * Creates the login account for a Student/Teacher/Admin whose domain entity
     * (Student/Teacher/Admin row) has already been created by its own controller.
     *
     * For STUDENT/TEACHER: the backend is the sole source of truth for the initial
     * password — it is always the linked entity's date of birth, formatted yyyyMMdd,
     * and any client-supplied password is ignored. The account is flagged
     * mustChangePassword so the user is forced onto /change-initial-password on
     * first login. ADMIN/SUB_ADMIN/SUPER_ADMIN creation is unchanged: the caller
     * supplies the password and normal strength validation applies.
     */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody User user) {
        log.info("Request to register new user: {}", user.getUserId());

        if (user.getUserId() == null || user.getRole() == null) {
            return ResponseEntity.badRequest().body("userId and role are required.");
        }
        if (!VALID_ROLES.contains(user.getRole())) {
            return ResponseEntity.badRequest().body("Invalid role.");
        }

        String callerRole = authService.getRole();
        boolean callerIsSuperAdmin = Role.SUPER_ADMIN.equals(callerRole);

        // Only SUPER_ADMIN may create another SUPER_ADMIN
        if (Role.SUPER_ADMIN.equals(user.getRole()) && !callerIsSuperAdmin) {
            log.warn("Privilege escalation attempt: caller {} ({}) tried to create SUPER_ADMIN account '{}'",
                    authService.getUserId(), callerRole, user.getUserId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only a SUPER_ADMIN can create a SUPER_ADMIN account.");
        }

        Optional<User> existingAccount = userRepository.findByUserId(user.getUserId());
        if (existingAccount.isPresent()) {
            // Student admission now creates the login in the same transaction. An older client
            // still makes this second call afterwards; for the caller's own school's student that
            // already has its login, report success instead of a misleading "account setup failed".
            User existing = existingAccount.get();
            if (Role.STUDENT.equals(user.getRole()) && Role.STUDENT.equals(existing.getRole())
                    && !callerIsSuperAdmin && SchoolContext.get() != null
                    && SchoolContext.get().equals(existing.getSchoolId())) {
                return ResponseEntity.ok("User registered successfully");
            }
            return ResponseEntity.status(HttpStatus.CONFLICT).body("User ID already exists.");
        }

        // SUPER_ADMIN may set any schoolId; anyone else inherits from their JWT.
        if (!callerIsSuperAdmin) {
            Long callerSchoolId = SchoolContext.get();
            if (callerSchoolId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Caller has no schoolId.");
            }
            user.setSchoolId(callerSchoolId);
        }

        boolean isStudentOrTeacher = Role.STUDENT.equals(user.getRole()) || Role.TEACHER.equals(user.getRole());
        // Account lifecycle is server-controlled; clients cannot register an inactive account.
        user.setActive(true);

        if (isStudentOrTeacher) {
            LocalDate dob = resolveDob(user.getUserId(), user.getRole(), user.getSchoolId());
            if (dob == null) {
                return ResponseEntity.badRequest()
                        .body("Date of birth is required because it is used as the initial password.");
            }
            user.setPassword(passwordEncoder.encode(dob.format(DateTimeFormatter.BASIC_ISO_DATE)));
            user.setMustChangePassword(true);
        } else {
            if (user.getPassword() == null) {
                return ResponseEntity.badRequest().body("Password is required.");
            }
            try {
                validatePasswordStrength(user.getPassword());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(e.getMessage());
            }
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }

        userRepository.save(user);

        // Welcome email — only for STUDENT/TEACHER (the roles with a DOB-derived temporary
        // password); never for ADMIN/SUB_ADMIN/SUPER_ADMIN. Fired only after the save above
        // succeeds, so a retried/duplicate registerUser call (rejected above by the
        // findByUserId conflict check) can never trigger a second send for the same account.
        if (isStudentOrTeacher) {
            String name = resolveName(user.getUserId(), user.getRole(), user.getSchoolId());
            welcomeEmailService.sendWelcomeEmail(
                    user.getUserId(), name, user.getRole(), user.getEmail(), user.getSchoolId());
        }

        return ResponseEntity.ok("User registered successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        if (rateLimiter.isRateLimited("login:" + req.getUserId(), 5, 300000)) {
            return ResponseEntity.status(429).body("Too many login attempts. Try again in 5 minutes.");
        }

        Optional<User> found = userRepository.findByUserId(req.getUserId());
        if (found.isEmpty()) {
            log.warn("Login failed: userId={} not found", req.getUserId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials");
        }
        if (!passwordEncoder.matches(req.getPassword(), found.get().getPassword())) {
            log.warn("Login failed: wrong password for userId={}", req.getUserId());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid credentials");
        }

        User loggedIn = found.get();

        if (!loggedIn.isActive()) {
            log.warn("Login rejected for inactive userId={}", loggedIn.getUserId());
            clearCookies(response);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Your account is inactive. Please contact your school administrator.");
        }

        // Reject login if the school has been deactivated (SUPER_ADMIN has no schoolId — skip for them)
        if (loggedIn.getSchoolId() != null) {
            School school = schoolRepository.findById(loggedIn.getSchoolId()).orElse(null);
            if (school == null || !school.isActive()) {
                log.warn("Login rejected for userId={}: school {} is inactive", loggedIn.getUserId(), loggedIn.getSchoolId());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Your school account has been deactivated. Please contact Edunexify support.");
            }

            // Subscription enforcement is handled by SubscriptionEnforcementFilter:
            // - GET requests are always allowed (read-only access)
            // - POST/PUT/DELETE/PATCH are blocked with 402
            // All roles (including STUDENT and TEACHER) can log in and view data
            // even when the subscription is expired.
        }

        // Option B — school-scoped login enforcement.
        // schoolSlug is only sent when the user is on a branded login page.
        // SUPER_ADMIN has no schoolId so we skip the check for them.
        if (req.getSchoolSlug() != null && !req.getSchoolSlug().isBlank()
                && loggedIn.getSchoolId() != null) {
            String userSchoolSlug = resolveSchoolSlug(loggedIn.getSchoolId());
            if (!req.getSchoolSlug().equalsIgnoreCase(userSchoolSlug)) {
                log.warn("Login rejected: userId={} attempted login on school slug '{}' but belongs to '{}'",
                        loggedIn.getUserId(), req.getSchoolSlug(), userSchoolSlug);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("This account does not belong to this school.");
            }
        }

        // Clear any stale cookies from previous sessions before issuing new ones.
        // Two clearing passes: once with Domain (covers post-domain-fix cookies) and once
        // without Domain (covers legacy host-only cookies set before the Domain fix).
        clearCookies(response);

        boolean passwordChangeRequired = loggedIn.isMustChangePassword();

        if (passwordChangeRequired) {
            // Restricted first-login session: issue only a short-lived access token carrying
            // pwdChangeRequired=true (enforced by JwtAuthFilter's allowlist) and NO refresh
            // token, so the session cannot be silently extended and cannot reach any business
            // API. Precaution: revoke any still-active sessions from earlier, unrestricted
            // logins on this account FIRST — before creating this one — so the row created
            // below survives and this restricted access token remains usable.
            userSessionService.revokeAllForUser(loggedIn.getUserId());

            // Every access token is now session-backed (see JwtAuthFilter) — including this
            // restricted one. Its "session" secret is generated and hashed exactly like a
            // normal refresh JTI, but is never placed in any cookie or response: no refresh
            // token is ever handed to this client, so this session can only ever be used by
            // the access token issued right here, until it naturally expires alongside it.
            String restrictedSessionSecret = UUID.randomUUID().toString();
            Date restrictedExpiry = new Date(System.currentTimeMillis() + (1000L * 60 * accessTokenExpiryMinutes));
            var restrictedSession = userSessionService.createSession(loggedIn.getUserId(), restrictedSessionSecret,
                    restrictedExpiry.toInstant(), request.getHeader("User-Agent"), request.getRemoteAddr());

            String restrictedAccessToken = Jwts.builder()
                    .setSubject(loggedIn.getUserId())
                    .claim("role", loggedIn.getRole())
                    .claim("userId", loggedIn.getUserId())
                    .claim("schoolId", loggedIn.getSchoolId())
                    .claim("pwdChangeRequired", true)
                    .claim("sessionId", restrictedSession.getId())
                    .setIssuedAt(new Date())
                    .setExpiration(restrictedExpiry)
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                    .compact();

            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", restrictedAccessToken, Duration.ofMinutes(accessTokenExpiryMinutes)).toString());

            log.info("Login success (restricted — password change required): userId={}, role={}, schoolId={}",
                    loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", loggedIn.getUserId());
            body.put("role", loggedIn.getRole());
            body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("schoolSlug", resolveSchoolSlug(loggedIn.getSchoolId()));
            body.put("mustChangePassword", true);
            appendEntitlementFields(body, loggedIn.getSchoolId());

            return ResponseEntity.ok(body);
        }

        // Every login creates its own independent session row — logging in on another
        // browser/device never touches this one. jti is a cryptographically random
        // (UUID v4, SecureRandom-backed) session secret; only its SHA-256 hash is
        // ever persisted (see UserSessionService), never the raw value. The session
        // must exist BEFORE the access token is built, since the access token now
        // carries this session's stable id (see JwtAuthFilter).
        String jti = UUID.randomUUID().toString();
        Date refreshExpiry = new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * refreshTokenExpiryDays));
        var session = userSessionService.createSession(loggedIn.getUserId(), jti, refreshExpiry.toInstant(),
                request.getHeader("User-Agent"), request.getRemoteAddr());

        String accessToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .claim("role", loggedIn.getRole())
                .claim("userId", loggedIn.getUserId())
                .claim("schoolId", loggedIn.getSchoolId())
                .claim("sessionId", session.getId())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (1000L * 60 * accessTokenExpiryMinutes)))
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        String refreshToken = Jwts.builder()
                .setSubject(loggedIn.getUserId())
                .setId(jti)
                .setIssuedAt(new Date())
                .setExpiration(refreshExpiry)
                .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", accessToken, Duration.ofMinutes(accessTokenExpiryMinutes)).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", refreshToken, Duration.ofDays(refreshTokenExpiryDays)).toString());

        log.info("Login success: userId={}, role={}, schoolId={}", loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", loggedIn.getUserId());
        body.put("role", loggedIn.getRole());
        body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
        body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
        body.put("schoolSlug", resolveSchoolSlug(loggedIn.getSchoolId()));
        body.put("mustChangePassword", false);
        appendEntitlementFields(body, loggedIn.getSchoolId());

        return ResponseEntity.ok(body);
    }

    private ResponseCookie buildCookie(String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(isSecure)
                .path("/")
                .sameSite(sameSite)
                .maxAge(maxAge);
        // Only set Domain when configured — omitting it makes the cookie host-only,
        // which is required for localhost development (browsers reject Domain=edunexify.co.in on localhost).
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }
        return builder.build();
    }

    /**
     * Sends Max-Age=0 clearing cookies to remove any stale session cookies from
     * previous logins. Two passes are required:
     *   1. With Domain= — clears cookies that were set with the domain attribute
     *      (sessions after the domain fix was deployed).
     *   2. Without Domain — clears legacy host-only cookies set before the domain fix.
     * Both passes are needed during the migration window while old host-only cookies
     * may still be present in users' browsers.
     */
    private void clearCookies(HttpServletResponse response) {
        // Pass 1: domain-scoped cookies
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken",  "", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", Duration.ZERO).toString());

        // Pass 2: host-only cookies (no Domain attribute)
        ResponseCookie clearAccess  = ResponseCookie.from("accessToken",  "").httpOnly(true).secure(isSecure).path("/").sameSite(sameSite).maxAge(Duration.ZERO).build();
        ResponseCookie clearRefresh = ResponseCookie.from("refreshToken", "").httpOnly(true).secure(isSecure).path("/").sameSite(sameSite).maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearAccess.toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearRefresh.toString());
    }

    /**
     * Appends subscription entitlement fields to the auth response body.
     * SUPER_ADMIN has no schoolId → all fields are null/empty.
     * School users with no subscription yet → fields are null/empty (no exception thrown).
     */
    private void appendEntitlementFields(Map<String, Object> body, Long schoolId) {
        if (schoolId == null) {
            putEmptyEntitlementFields(body);
            return;
        }
        try {
            var ent = entitlementRepo.findById(schoolId).orElse(null);
            if (ent == null) {
                putEmptyEntitlementFields(body);
            } else {
                body.put("featureKeys",         entitlementService.getEffectiveFeatureKeys(schoolId));
                body.put("planTier",            ent.getPlanTier());
                body.put("planVersion",         ent.getPlanVersion());
                body.put("subscriptionStatus",  ent.getSubscriptionStatus());
                body.put("trialEndsAt",         ent.getTrialEndsAt() != null ? ent.getTrialEndsAt().toString() : null);
                body.put("expiresAt",           ent.getExpiresAt() != null ? ent.getExpiresAt().toString() : null);
                body.put("graceEndsAt",         ent.getGraceEndsAt() != null ? ent.getGraceEndsAt().toString() : null);
                body.put("maxAiMessagesMonthly", ent.getMaxAiMessagesMonthly());
                body.put("maxKbDocuments",       ent.getMaxKbDocuments());
            }
        } catch (Exception e) {
            log.warn("Could not load entitlement for schoolId={}: {}", schoolId, e.getMessage());
            putEmptyEntitlementFields(body);
        }
    }

    private void putEmptyEntitlementFields(Map<String, Object> body) {
        body.put("featureKeys",          List.of());
        body.put("planTier",             null);
        body.put("planVersion",          null);
        body.put("subscriptionStatus",   null);
        body.put("trialEndsAt",          null);
        body.put("expiresAt",            null);
        body.put("graceEndsAt",          null);
        body.put("maxAiMessagesMonthly", null);
        body.put("maxKbDocuments",       null);
    }

    private String resolveSchoolSlug(Long schoolId) {
        if (schoolId == null) return null;
        return schoolRepository.findById(schoolId).map(School::getSlug).orElse(null);
    }

    private String resolveName(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(Student::getName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(Teacher::getName).orElse(null);
            } else if (Role.PARENT.equals(role)) {
                return parentRepository.findByParentIdAndSchoolId(userId, schoolId)
                        .map(com.indraacademy.ias_management.entity.Parent::getName).orElse(null);
            } else {
                // ADMIN, SUB_ADMIN: scope to school; SUPER_ADMIN has null schoolId so fall back to findById
                if (schoolId != null) {
                    return adminRepository.findByAdminIdAndSchoolId(userId, schoolId)
                            .map(Admin::getName).orElse(null);
                }
                return adminRepository.findById(userId).map(Admin::getName).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve name for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private String resolveClassName(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(Student::getClassName).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(Teacher::getClassTeacher).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve className for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    private LocalDate resolveDob(String userId, String role, Long schoolId) {
        try {
            if (Role.STUDENT.equals(role)) {
                return studentRepository.findByStudentIdAndSchoolId(userId, schoolId)
                        .map(Student::getDob).orElse(null);
            } else if (Role.TEACHER.equals(role)) {
                return teacherRepository.findByTeacherIdAndSchoolId(userId, schoolId)
                        .map(Teacher::getDob).orElse(null);
            }
        } catch (Exception e) {
            log.warn("Could not resolve dob for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }


    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(HttpServletRequest request, HttpServletResponse response) {

        jakarta.servlet.http.Cookie refreshCookieRaw = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookieRaw == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token missing");
        }

        String refreshToken = refreshCookieRaw.getValue();

        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtUtil.getPublicKey())
                    .build()
                    .parseClaimsJws(refreshToken)
                    .getBody();

            String userId = claims.getSubject();
            String tokenJti = claims.getId();

            Optional<User> userOptional = userRepository.findByUserId(userId);

            if (userOptional.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid refresh token");
            }

            User loggedIn = userOptional.get();

            if (!loggedIn.isActive()) {
                log.warn("Token refresh rejected for inactive userId={}", userId);
                clearCookies(response);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Your account is inactive. Please contact your school administrator.");
            }

            // Resolve THIS specific session (never any other session belonging to the
            // same user) — rejects a revoked, expired, or never-issued refresh token
            // exactly as the old single-column JTI check did, but without invalidating
            // every other concurrently logged-in session for this account.
            if (tokenJti == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token has been revoked.");
            }
            var sessionOptional = userSessionService.resolveActive(tokenJti);
            if (sessionOptional.isEmpty() || !sessionOptional.get().getUserId().equals(userId)) {
                log.warn("Refresh token session not found/revoked for userId={}", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token has been revoked.");
            }
            var session = sessionOptional.get();

            // Reject token refresh if the school has been deactivated
            if (loggedIn.getSchoolId() != null) {
                School school = schoolRepository.findById(loggedIn.getSchoolId()).orElse(null);
                if (school == null || !school.isActive()) {
                    log.warn("Token refresh rejected for userId={}: school {} is inactive", userId, loggedIn.getSchoolId());
                    // Clear all cookies (both domain-scoped and host-only) so the client is fully logged out
                    clearCookies(response);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("Your school account has been deactivated. Please contact Edunexify support.");
                }

                // Block token refresh for school end-users when subscription is EXPIRED.
                // ADMIN/SUB_ADMIN are allowed to keep their session so they can renew.
                String refreshRole = loggedIn.getRole();
                if (Role.STUDENT.equals(refreshRole)
                        || Role.TEACHER.equals(refreshRole)
                        || Role.PARENT.equals(refreshRole)) {
                    var ent = entitlementRepo.findById(loggedIn.getSchoolId()).orElse(null);
                    if (ent != null && "EXPIRED".equals(ent.getSubscriptionStatus())) {
                        log.warn("Token refresh rejected for userId={} ({}): school {} subscription is EXPIRED",
                                userId, refreshRole, loggedIn.getSchoolId());
                        clearCookies(response);
                        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                                .body("Your school's subscription has expired. Please contact your school administrator.");
                    }
                }
            }

            // Rotate this SAME session onto a new refresh token — atomically, via a
            // compare-and-swap UPDATE keyed on the session still having the exact hash
            // this request read. If a concurrent refresh using the same token already
            // won that race, this returns false and the row is left completely
            // untouched — exactly one of two simultaneous refreshes may ever succeed.
            String newJti = UUID.randomUUID().toString();
            Date newRefreshExpiry = new Date(System.currentTimeMillis() + (1000L * 60 * 60 * 24 * refreshTokenExpiryDays));
            boolean rotated = userSessionService.rotate(session, newJti, newRefreshExpiry.toInstant(),
                    request.getHeader("User-Agent"), request.getRemoteAddr());
            if (!rotated) {
                log.warn("Refresh token rotation lost a concurrent race for userId={} — rejecting.", userId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token has been revoked.");
            }

            String newAccessToken = jwtUtil.generateAccessToken(userId, loggedIn.getRole(), loggedIn.getSchoolId(), session.getId());

            String newRefreshToken = Jwts.builder()
                    .setSubject(userId)
                    .setId(newJti)
                    .setIssuedAt(new Date())
                    .setExpiration(newRefreshExpiry)
                    .signWith(jwtUtil.getPrivateKey(), SignatureAlgorithm.RS256)
                    .compact();

            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", newAccessToken, Duration.ofMinutes(accessTokenExpiryMinutes)).toString());
            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", newRefreshToken, Duration.ofDays(refreshTokenExpiryDays)).toString());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", loggedIn.getUserId());
            body.put("role", loggedIn.getRole());
            body.put("name", resolveName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("className", resolveClassName(loggedIn.getUserId(), loggedIn.getRole(), loggedIn.getSchoolId()));
            body.put("schoolSlug", resolveSchoolSlug(loggedIn.getSchoolId()));
            appendEntitlementFields(body, loggedIn.getSchoolId());

            return ResponseEntity.ok(body);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Refresh token expired or invalid");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        // Revoke ONLY this session server-side — never any other session belonging to
        // the same user. This ensures the token cannot be reused even if someone
        // captured its raw value, without logging the user out on their other devices.
        jakarta.servlet.http.Cookie refreshCookieRaw = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookieRaw != null) {
            try {
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(jwtUtil.getPublicKey())
                        .build()
                        .parseClaimsJws(refreshCookieRaw.getValue())
                        .getBody();
                String tokenJti = claims.getId();
                if (tokenJti != null) {
                    userSessionService.revokeByRawToken(tokenJti);
                }
                log.info("Session revoked server-side for userId={}", claims.getSubject());
            } catch (Exception e) {
                // Token may already be expired or malformed — still clear cookies
                log.debug("Could not parse refresh token during logout: {}", e.getMessage());
            }
        }

        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", "", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", Duration.ZERO).toString());

        return ResponseEntity.ok("Logged out successfully");
    }

    /** Lists the CALLING user's own active sessions only — never another user's (the
     * userId comes from the SecurityContext, never a request parameter). */
    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(HttpServletRequest request) {
        String userId = authService.getUserId();
        String currentHash = currentSessionHash(request);
        return ResponseEntity.ok(userSessionService.listActiveSessions(userId, currentHash));
    }

    /** Revokes one of the CALLING user's own sessions. Ownership is enforced inside
     * UserSessionService#revokeOwnSession — a session id belonging to another user
     * returns the same 404 as one that doesn't exist, so this endpoint can never be
     * used to probe for or revoke another user's session. */
    @PostMapping("/sessions/{id}/revoke")
    public ResponseEntity<?> revokeSession(@PathVariable Long id) {
        String userId = authService.getUserId();
        boolean revoked = userSessionService.revokeOwnSession(id, userId);
        if (!revoked) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Session not found.");
        }
        return ResponseEntity.ok("Session revoked.");
    }

    /** "Log out all other sessions" — keeps the session making this very request
     * alive, revokes every other active session for the calling user. */
    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<?> revokeOtherSessions(HttpServletRequest request) {
        String userId = authService.getUserId();
        String currentHash = currentSessionHash(request);
        int count = userSessionService.revokeAllOthers(userId, currentHash);
        return ResponseEntity.ok(Map.of("revokedCount", count));
    }

    /** "Log out everywhere" — revokes every session for the calling user, including
     * the one making this request, and clears this browser's cookies too. */
    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(HttpServletResponse response) {
        String userId = authService.getUserId();
        int count = userSessionService.revokeAllForUser(userId);
        clearCookies(response);
        return ResponseEntity.ok(Map.of("revokedCount", count));
    }

    /** Best-effort: resolves the SHA-256 hash of the current request's own refresh
     * token, so the caller's own session can be identified as "current" / excluded
     * from "revoke others". Returns null (never throws) if the cookie is missing,
     * expired, or malformed — callers must treat null as "current session unknown"
     * rather than an error. */
    private String currentSessionHash(HttpServletRequest request) {
        jakarta.servlet.http.Cookie refreshCookieRaw = WebUtils.getCookie(request, "refreshToken");
        if (refreshCookieRaw == null) return null;
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(jwtUtil.getPublicKey())
                    .build()
                    .parseClaimsJws(refreshCookieRaw.getValue())
                    .getBody();
            String tokenJti = claims.getId();
            return tokenJti != null ? userSessionService.hash(tokenJti) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {

        // 1. Extract info from SecurityContext (populated by JwtAuthFilter from the accessToken cookie)
        String callingUserId = authService.getUserId();
        String callingUserRole = authService.getRole();

        String targetUserId = (request.getUserId() != null && !request.getUserId().isEmpty())
                ? request.getUserId()
                : callingUserId;

        log.info("Password change request by {} ({}) for target {}", callingUserId, callingUserRole, targetUserId);

        Optional<User> userOptional = userRepository.findByUserId(targetUserId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Target user not found");
        }

        User targetUser = userOptional.get();
        String targetUserRole = targetUser.getRole();
        boolean isSelfUpdate = callingUserId.equals(targetUserId);

        if (!isSelfUpdate) {
            // Only Admins or Super Admins can change others' passwords
            if (!"ADMIN".equals(callingUserRole) && !"SUPER_ADMIN".equals(callingUserRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Access Denied: You cannot change other users' passwords.");
            }

            // Regular ADMINs cannot touch other ADMINs or SUPER_ADMINs
            if ("ADMIN".equals(callingUserRole)) {
                if ("SUPER_ADMIN".equals(targetUserRole) || "ADMIN".equals(targetUserRole)) {
                    log.warn("Unauthorized attempt: Admin {} tried to reset Admin {}", callingUserId, targetUserId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("Admins cannot change passwords for other Admins or Super Admins.");
                }
            }
        }

        // If changing YOUR OWN password, you must provide the old one.
        if (isSelfUpdate) {
            if (request.getOldPassword() == null || request.getOldPassword().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Current password is required to change your own password.");
            }
            if (!passwordEncoder.matches(request.getOldPassword(), targetUser.getPassword())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Invalid current password.");
            }
        }

        // Validate password complexity
        try {
            validatePasswordStrength(request.getNewPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        // Save New Password
        try {
            targetUser.setPassword(passwordEncoder.encode(request.getNewPassword()));
            userRepository.save(targetUser);

            log.info("Password successfully updated for user {}", targetUserId);
            return ResponseEntity.ok("Password changed successfully");
        } catch (Exception e) {
            log.error("Database error during password update for {}: {}", targetUserId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to update password.");
        }
    }

    /**
     * Completes a mandatory first-login (or admin-reset) password change.
     * Reachable only by a restricted session (see JwtAuthFilter's allowlist) —
     * the target account is resolved from the SecurityContext, never from the
     * request body. On success the account is fully unrestricted in the
     * database, but the client must still sign in again: no new session is
     * granted here, and the restricted access token cookie is cleared.
     */
    @Transactional
    @PostMapping("/change-initial-password")
    public ResponseEntity<?> changeInitialPassword(@Valid @RequestBody ChangeInitialPasswordRequest request,
                                                     HttpServletResponse response) {
        String userId = authService.getUserId();
        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not found");
        }

        User user = userOptional.get();
        if (!user.isMustChangePassword()) {
            return ResponseEntity.badRequest().body("Password change is not required for this account.");
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            return ResponseEntity.badRequest().body("New password and confirmation do not match.");
        }

        try {
            validatePasswordStrength(request.getNewPassword());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            return ResponseEntity.badRequest().body("New password must be different from your temporary password.");
        }
        if (request.getNewPassword().toLowerCase().contains(userId.toLowerCase())) {
            return ResponseEntity.badRequest().body("New password must not contain your User ID.");
        }
        LocalDate dob = resolveDob(userId, user.getRole(), user.getSchoolId());
        if (dob != null && request.getNewPassword().contains(dob.format(DateTimeFormatter.BASIC_ISO_DATE))) {
            return ResponseEntity.badRequest().body("New password must not contain your date of birth.");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        userSessionService.revokeAllForUser(userId); // invalidate any lingering sessions/refresh tokens
        userRepository.save(user);

        // Invalidate the restricted access token cookie — the client must sign in again.
        clearCookies(response);

        log.info("Initial password change completed for userId={}", userId);
        return ResponseEntity.ok("Password changed successfully. Please sign in with your new password.");
    }

    /**
     * Admin-initiated reset of a Student/Teacher's password back to their DOB-derived
     * default, aligned with the same first-login flow used for newly-created accounts:
     * mustChangePassword is set true and any existing session/refresh token is revoked,
     * so the user must sign in with the DOB password and then set a new one before
     * reaching any business API. Out of scope for ADMIN/SUB_ADMIN/SUPER_ADMIN targets —
     * use /change-password for those (unaffected by this endpoint).
     */
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @PostMapping("/reset-to-default-password")
    @Transactional
    public ResponseEntity<?> resetToDefaultPassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String targetUserId = body.get("userId");
        if (targetUserId == null || targetUserId.isBlank()) {
            return ResponseEntity.badRequest().body("userId is required.");
        }

        String callingUserId = authService.getUserId();
        String callingRole = authService.getRole();

        Optional<User> targetOpt = userRepository.findByUserId(targetUserId);
        if (targetOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }
        User target = targetOpt.get();

        // Tenant isolation: non-SUPER_ADMIN callers may only reset accounts in their own school
        if (!Role.SUPER_ADMIN.equals(callingRole)) {
            Long callerSchoolId = SchoolContext.get();
            if (callerSchoolId == null || !callerSchoolId.equals(target.getSchoolId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access Denied.");
            }
        }

        if (!Role.STUDENT.equals(target.getRole()) && !Role.TEACHER.equals(target.getRole())) {
            return ResponseEntity.badRequest().body("This reset is only available for Student and Teacher accounts.");
        }

        LocalDate dob = resolveDob(target.getUserId(), target.getRole(), target.getSchoolId());
        if (dob == null) {
            return ResponseEntity.badRequest()
                    .body("Date of birth is required because it is used as the initial password.");
        }

        target.setPassword(passwordEncoder.encode(dob.format(DateTimeFormatter.BASIC_ISO_DATE)));
        target.setMustChangePassword(true);
        userSessionService.revokeAllForUser(target.getUserId());
        userRepository.save(target);

        auditService.log(callingUserId, callingRole, "RESET_PASSWORD_TO_DEFAULT", "User", targetUserId,
                null, "Password reset to DOB default; mustChangePassword=true", request.getRemoteAddr());

        log.info("Password reset to DOB default for userId={} by {}", targetUserId, callingUserId);
        return ResponseEntity.ok("Password reset. The user must sign in with their date of birth (YYYYMMDD) " +
                "and will be required to set a new password.");
    }

    @PostMapping("/request-password-reset")
    public ResponseEntity<?> requestPasswordReset(@RequestBody User request) {
        String email = request.getEmail();
        String userId = request.getUserId();

        if (userId == null || userId.isEmpty() || email == null || email.isEmpty()) {
            log.warn("Request password reset failed: User ID and Email are required.");
            return ResponseEntity.badRequest().body("User ID and Email are required.");
        }

        if (rateLimiter.isRateLimited("reset:" + email, 3, 3600000)) {
            return ResponseEntity.status(429).body("Too many reset requests. Try again in 1 hour.");
        }

        log.info("Request to initiate password reset for user ID: {}", userId);

        Optional<User> userOptional = userRepository.findByUserId(userId);
        if (userOptional.isEmpty()) {
            log.warn("Password reset failed: User {} not found.", userId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
        }

        User user = userOptional.get();
        if (!user.getEmail().equalsIgnoreCase(email)) {
            log.warn("Password reset failed for user {}: Email does not match.", userId);
            return ResponseEntity.badRequest().body("Email address does not match the registered email.");
        }

        try {
            passwordResetService.sendResetLink(user, "Password Reset Request – Edunexify",
                    "We received a request to reset the password for your Edunexify account. "
                            + "Click the button below to set a new password. This link will expire in "
                            + "<strong style=\"color:#111827;\">1 hour</strong>.");

            log.info("Password reset link sent to email for user: {}", userId);

            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Password reset link sent to your email address.");
            }});
        } catch (Exception e) {
            log.error("Error during password reset request for user: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to process password reset request.");
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestParam String token, @RequestBody HashMap<String, String> requestBody) {
        String newPassword = requestBody.get("newPassword");

        if (newPassword == null || newPassword.isEmpty()) {
            log.warn("Password reset failed: New password cannot be empty.");
            return ResponseEntity.badRequest().body("New password cannot be empty.");
        }

        try {
            validatePasswordStrength(newPassword);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }

        log.info("Attempting to reset password using token.");

        try {
            Optional<User> userOptional = userRepository.findByResetToken(passwordResetService.hashToken(token));
            if (userOptional.isEmpty()) {
                log.warn("Password reset failed: Invalid reset token.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid reset token.");
            }

            User user = userOptional.get();
            if (user.getResetTokenExpiry().before(new Date())) {
                log.warn("Password reset failed for user {}: Token has expired.", user.getUserId());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Reset token has expired.");
            }

            user.setPassword(passwordEncoder.encode(newPassword));
            user.setResetToken(null);
            user.setResetTokenExpiry(null);
            // A password set via a verified emailed token IS the "establish your real
            // password" step — there is nothing left to force a change of afterward. This
            // also closes the actual onboarding path for new Parent accounts (Option A):
            // without this, a parent who sets their password via this link would still be
            // forced through the separate initial-password-change flow on their very next
            // login, immediately after having just set a real password.
            user.setMustChangePassword(false);
            userRepository.save(user);
            // A forgotten-password reset is the classic "my account may be compromised"
            // scenario — unlike a self-service change-password (which already re-proved
            // the old password in the same request), there is no way to know whether an
            // attacker holds a live session on this account. Revoke all of them.
            userSessionService.revokeAllForUser(user.getUserId());
            log.info("Password reset successfully for user: {}", user.getUserId());

            return ResponseEntity.ok("Password reset successfully.");
        } catch (Exception e) {
            log.error("Unexpected error during password reset.", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to reset password.");
        }
    }

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

}
