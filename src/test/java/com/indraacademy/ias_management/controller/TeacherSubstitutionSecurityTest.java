package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.TeacherSubstitutionDtos.*;
import com.indraacademy.ias_management.service.TeacherSubstitutionService;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Teacher Substitution Phase 2 endpoint roles through real method security. */
@SpringJUnitConfig(TeacherSubstitutionSecurityTest.Config.class)
class TeacherSubstitutionSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean TeacherSubstitutionController controller(TeacherSubstitutionService service) {
            return new TeacherSubstitutionController(service);
        }
    }

    @Autowired TeacherSubstitutionController controller;
    @MockBean TeacherSubstitutionService service;

    final HttpServletRequest http = mock(HttpServletRequest.class);
    final LocalDate date = LocalDate.of(2026, 10, 6);

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void assertOnly(Runnable call, List<String> allowed) {
        for (String role : List.of("ADMIN", "TEACHER", "SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            if (allowed.contains(role)) assertThatCode(call::run).as(role).doesNotThrowAnyException();
            else assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void rolesForThePhase2Endpoints() {
        assertOnly(() -> controller.overview(date), List.of("ADMIN", "SUB_ADMIN"));
        assertOnly(() -> controller.suggestFill(date), List.of("ADMIN", "SUB_ADMIN"));
        assertOnly(() -> controller.bulk(new BulkRequest(date, List.of(new BulkItem(1L, "T2", null))), http),
                List.of("ADMIN", "SUB_ADMIN"));
        assertOnly(() -> controller.myCoverage(date), List.of("TEACHER"));
        assertOnly(() -> controller.mine(date), List.of("TEACHER"));
    }
}
