package com.indraacademy.ias_management.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.PromotionDecisionRequest;
import com.indraacademy.ias_management.dto.PromotionPreviewDTO;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import com.indraacademy.ias_management.service.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Student Promotion Phase 1 endpoints are ADMIN-only, through real Spring method security. */
@SpringJUnitConfig(StudentRolloverSecurityTest.Config.class)
class StudentRolloverSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean StudentController studentController() { return new StudentController(); }
        @Bean AcademicSessionController academicSessionController() { return new AcademicSessionController(); }
    }

    @Autowired StudentController students;
    @Autowired AcademicSessionController sessions;
    @MockBean StudentService studentService;
    @MockBean StudentBulkImportService studentBulkImportService;
    @MockBean StudentPromotionService studentPromotionService;
    @MockBean PromotionResultContextService promotionResultContext;
    @MockBean PasswordEncoder passwordEncoder;
    @MockBean StudentRepository studentRepository;
    @MockBean UserRepository userRepository;
    @MockBean ObjectMapper objectMapper;
    @MockBean AuthService authService;
    @MockBean ParentPortalService parentPortalService;
    @MockBean TeacherClassScopeService teacherClassScopeService;
    @MockBean SecurityUtil securityUtil;
    @MockBean ObjectStorageService objectStorageService;
    @MockBean AcademicSessionService academicSessionService;
    @MockBean AcademicSessionActivationService academicSessionActivationService;
    @MockBean ClassTeacherActivationService classTeacherActivationService;
    @MockBean SessionReadinessService sessionReadinessService;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void assertAdminOnly(Runnable call) {
        for (String role : List.of("TEACHER", "SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
        as("ADMIN");
        assertThatCode(call::run).doesNotThrowAnyException();
    }

    @Test
    void promotionPreviewExecuteRunsAndReadinessAreAdminOnly() {
        when(studentPromotionService.getPromotionPreview(any(), any(), any(), any()))
                .thenReturn(new PromotionPreviewDTO(1L, 2L, true, List.of(), List.of(), List.of()));
        assertAdminOnly(() -> students.getPromotionPreview(1L, 2L, null, null));
        assertAdminOnly(() -> students.executePromotion(new PromotionDecisionRequest(), mock(HttpServletRequest.class)));
        assertAdminOnly(() -> students.getPromotionRuns(2L));
        assertAdminOnly(() -> sessions.getReadiness(2L));
    }
}
