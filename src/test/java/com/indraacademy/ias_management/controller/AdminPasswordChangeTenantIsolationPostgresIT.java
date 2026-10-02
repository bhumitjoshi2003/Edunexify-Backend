package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ChangePasswordRequest;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.util.JwtUtil;
import com.indraacademy.ias_management.config.RateLimiter;
import com.indraacademy.ias_management.util.SchoolContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Security hotfix, on real PostgreSQL with real commits: POST /api/auth/change-password.
 * An ADMIN may set passwords only for non-admin users of their own school; another school's
 * user is indistinguishable from an unknown one. An admin-set password revokes every session of
 * the target and forces a new password at next login — all or nothing.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none",
        "auth.cookie.secure=false",
        "auth.cookie.sameSite=Lax",
        "jwt.access-token.expiry-minutes=15",
        "jwt.refresh-token.expiry-days=7"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AuthController.class, UserSessionService.class, AdminPasswordChangeTenantIsolationPostgresIT.Beans.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AdminPasswordChangeTenantIsolationPostgresIT {

    static final long SCHOOL_A = -97501, SCHOOL_B = -97502;
    static final String OLD = "OldPass#2025", NEW = "NewPass#2026";

    @TestConfiguration
    static class Beans {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override public String encode(CharSequence raw) { return "enc:" + raw; }
                @Override public boolean matches(CharSequence raw, String encoded) { return ("enc:" + raw).equals(encoded); }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired AuthController controller;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @SpyBean UserSessionService sessions;
    @MockBean AuthService authService;
    @MockBean JwtUtil jwtUtil;
    @MockBean EmailService emailService;
    @MockBean WelcomeEmailService welcomeEmailService;
    @MockBean PasswordResetService passwordResetService;
    @MockBean EntitlementService entitlementService;
    @MockBean RateLimiter rateLimiter;
    @MockBean PermissionService permissionService;
    @MockBean AuditService auditService;

    @BeforeEach
    void fixtures() {
        cleanup();
        user("PW-ADMIN-A", "ADMIN", SCHOOL_A);
        user("PW-ADMIN-A2", "ADMIN", SCHOOL_A);
        user("PW-SUPER", "SUPER_ADMIN", null);
        for (long school : List.of(SCHOOL_A, SCHOOL_B)) {
            String s = school == SCHOOL_A ? "A" : "B";
            user("PW-TEACHER-" + s, "TEACHER", school);
            user("PW-STUDENT-" + s, "STUDENT", school);
            user("PW-PARENT-" + s, "PARENT", school);
        }
        user("PW-ADMIN-B", "ADMIN", SCHOOL_B);
        for (String id : List.of("PW-TEACHER-A", "PW-STUDENT-A", "PW-PARENT-A", "PW-TEACHER-B", "PW-STUDENT-B", "PW-PARENT-B", "PW-ADMIN-A")) {
            session(id, 1);
            session(id, 2);
        }
    }

    @AfterEach
    void cleanup() {
        SchoolContext.clear();
        jdbc.update("DELETE FROM user_session WHERE user_id LIKE 'PW-%'");
        jdbc.update("DELETE FROM users WHERE user_id LIKE 'PW-%'");
        jdbc.update("DELETE FROM student WHERE student_id LIKE 'PW-%'");
        jdbc.update("DELETE FROM teacher WHERE teacher_id LIKE 'PW-%'");
    }

    private void user(String id, String role, Long school) {
        User u = new User();
        u.setUserId(id);
        u.setRole(role);
        u.setSchoolId(school);
        u.setEmail(id.toLowerCase() + "@test.com");
        u.setPassword("enc:" + OLD);
        u.setMustChangePassword(false);
        u.setActive(true);
        users.saveAndFlush(u);
    }

    private void session(String userId, int n) {
        jdbc.update("INSERT INTO user_session (user_id, refresh_token_hash, expires_at) VALUES (?, ?, now() + interval '7 days')",
                userId, (userId + "-" + n + "-hash").toLowerCase());
    }

    private int activeSessions(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM user_session WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
    }

    private Map<String, Object> account(String userId) {
        return jdbc.queryForMap("SELECT password, must_change_password FROM users WHERE user_id = ?", userId);
    }

    private void as(String userId, String role, Long school) {
        when(authService.getUserId()).thenReturn(userId);
        when(authService.getRole()).thenReturn(role);
        if (school == null) SchoolContext.clear(); else SchoolContext.set(school);
    }

    private ResponseEntity<?> change(String target, String oldPassword) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setUserId(target);
        r.setOldPassword(oldPassword);
        r.setNewPassword(NEW);
        return controller.changePassword(r);
    }

    private void assertChanged(String target) {
        assertThat(account(target)).containsEntry("password", "enc:" + NEW).containsEntry("must_change_password", true);
        assertThat(activeSessions(target)).as("sessions of " + target).isZero();
    }

    private void assertUntouched(String target) {
        assertThat(account(target)).containsEntry("password", "enc:" + OLD).containsEntry("must_change_password", false);
        assertThat(activeSessions(target)).as("sessions of " + target).isEqualTo(2);
    }

    // ── Same school: allowed, sessions revoked, forced change ──

    @Test
    void anAdminCanSetPasswordsForTheirOwnSchoolsTeacherStudentAndParent() {
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);
        for (String target : List.of("PW-TEACHER-A", "PW-STUDENT-A", "PW-PARENT-A")) {
            assertThat(change(target, null).getStatusCode().value()).as(target).isEqualTo(200);
            assertChanged(target);
        }
        // Nobody else was affected.
        assertUntouched("PW-TEACHER-B");
        assertThat(activeSessions("PW-ADMIN-A")).isEqualTo(2);
    }

    // ── Cross-school: rejected exactly like an unknown user, nothing changes ──

    @Test
    void anAdminCannotSetPasswordsForAnotherSchoolsTeacherStudentOrParent() {
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);
        for (String target : List.of("PW-TEACHER-B", "PW-STUDENT-B", "PW-PARENT-B")) {
            ResponseEntity<?> response = change(target, null);
            assertThat(response.getStatusCode().value()).as(target).isEqualTo(404);
            assertThat(response.getBody()).as(target).isEqualTo("Target user not found");
            assertUntouched(target);
        }
        verify(sessions, never()).revokeAllForUser(anyString());
        verifyNoInteractions(auditService);
    }

    @Test
    void anotherSchoolsUserIsIndistinguishableFromAnUnknownUser_evenWhenTheyAreAnAdmin() {
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);
        ResponseEntity<?> unknown = change("PW-DOES-NOT-EXIST", null);
        ResponseEntity<?> otherSchoolAdmin = change("PW-ADMIN-B", null);
        assertThat(otherSchoolAdmin.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(otherSchoolAdmin.getBody()).isEqualTo(unknown.getBody());
        assertThat(account("PW-ADMIN-B")).containsEntry("password", "enc:" + OLD);
    }

    @Test
    void anAdminWithoutSchoolContextCannotSetAnyonesPassword() {
        as("PW-ADMIN-A", "ADMIN", null);
        assertThat(change("PW-TEACHER-A", null).getStatusCode().value()).isEqualTo(404);
        assertUntouched("PW-TEACHER-A");
    }

    // ── Admin targets ──

    @Test
    void anAdminCannotSetAnotherAdminsOrASuperAdminsPassword() {
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);
        assertThat(change("PW-ADMIN-A2", null).getStatusCode().value()).isEqualTo(403);
        assertThat(account("PW-ADMIN-A2")).containsEntry("password", "enc:" + OLD);
        // A SUPER_ADMIN belongs to no school, so for an ADMIN it's simply not found.
        assertThat(change("PW-SUPER", null).getStatusCode().value()).isIn(403, 404);
        assertThat(account("PW-SUPER")).containsEntry("password", "enc:" + OLD);
    }

    // ── SUPER_ADMIN keeps platform-wide reach ──

    @Test
    void aSuperAdminCanStillSetPasswordsAcrossSchools_withRevocationAndForcedChange() {
        as("PW-SUPER", "SUPER_ADMIN", null);
        assertThat(change("PW-TEACHER-B", null).getStatusCode().value()).isEqualTo(200);
        assertChanged("PW-TEACHER-B");
        assertThat(change("PW-ADMIN-A", null).getStatusCode().value()).isEqualTo(200);
        assertChanged("PW-ADMIN-A");
    }

    // ── Unchanged flows ──

    @Test
    void aUsersOwnPasswordChangeIsUnchanged_noForcedChange_noRevocation() {
        as("PW-TEACHER-A", "TEACHER", SCHOOL_A);
        assertThat(change(null, "wrong").getStatusCode().value()).isEqualTo(400);
        assertThat(change(null, OLD).getStatusCode().value()).isEqualTo(200);
        assertThat(account("PW-TEACHER-A")).containsEntry("password", "enc:" + NEW).containsEntry("must_change_password", false);
        assertThat(activeSessions("PW-TEACHER-A")).isEqualTo(2);
    }

    @Test
    void nonAdminsStillCannotChangeOtherUsersPasswords() {
        as("PW-TEACHER-A", "TEACHER", SCHOOL_A);
        assertThat(change("PW-STUDENT-A", null).getStatusCode().value()).isEqualTo(403);
        assertUntouched("PW-STUDENT-A");
        as("PW-X", "SUB_ADMIN", SCHOOL_A);
        assertThat(change("PW-STUDENT-A", null).getStatusCode().value()).isEqualTo(403);
        assertUntouched("PW-STUDENT-A");
    }

    // ── All or nothing ──

    @Test
    void ifRevocationFailsNothingIsChanged() {
        doThrow(new IllegalStateException("session store down")).when(sessions).revokeAllForUser("PW-STUDENT-A");
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);

        assertThat(change("PW-STUDENT-A", null).getStatusCode().value()).isEqualTo(500);

        assertUntouched("PW-STUDENT-A");
    }

    // ── Regression: the existing admin "reset to date of birth" flow for students and teachers ──

    @Test
    void adminResetToDateOfBirthStillWorksForOwnSchoolStudentsAndTeachers_andNotAcrossSchools() {
        jdbc.update("INSERT INTO student (student_id, school_id, name, dob, status) VALUES "
                + "('PW-STUDENT-A', ?, 'Stu A', DATE '2012-02-03', 'ACTIVE'), ('PW-STUDENT-B', ?, 'Stu B', DATE '2012-02-03', 'ACTIVE')",
                SCHOOL_A, SCHOOL_B);
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, dob, status) VALUES ('PW-TEACHER-A', ?, 'Tea A', DATE '1990-05-23', 'ACTIVE')",
                SCHOOL_A);
        jakarta.servlet.http.HttpServletRequest http = mock(jakarta.servlet.http.HttpServletRequest.class);
        as("PW-ADMIN-A", "ADMIN", SCHOOL_A);

        assertThat(controller.resetToDefaultPassword(Map.of("userId", "PW-STUDENT-A"), http).getStatusCode().value()).isEqualTo(200);
        assertThat(account("PW-STUDENT-A")).containsEntry("password", "enc:20120203").containsEntry("must_change_password", true);
        assertThat(activeSessions("PW-STUDENT-A")).isZero();

        assertThat(controller.resetToDefaultPassword(Map.of("userId", "PW-TEACHER-A"), http).getStatusCode().value()).isEqualTo(200);
        assertThat(account("PW-TEACHER-A")).containsEntry("password", "enc:19900523").containsEntry("must_change_password", true);
        assertThat(activeSessions("PW-TEACHER-A")).isZero();

        assertThat(controller.resetToDefaultPassword(Map.of("userId", "PW-STUDENT-B"), http).getStatusCode().value()).isEqualTo(403);
        assertUntouched("PW-STUDENT-B");
    }
}
