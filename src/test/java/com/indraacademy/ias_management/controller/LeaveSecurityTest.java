package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.StudentLeaveApplyRequest;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
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
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Leave Phase 1 roles through real method security, and who a student / parent application is
 * for: always the caller (student) or a MANAGE_LEAVE-authorised linked child (parent).
 */
@SpringJUnitConfig(LeaveSecurityTest.Config.class)
class LeaveSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean LeaveController leaveController() { return new LeaveController(); }
        @Bean TeacherLeaveController teacherLeaveController() { return new TeacherLeaveController(); }
    }

    @Autowired LeaveController leaves;
    @Autowired TeacherLeaveController teacherLeaves;
    @MockBean LeaveService leaveService;
    @MockBean LeaveOverviewService leaveOverviewService;
    @MockBean TeacherLeaveService teacherLeaveService;
    @MockBean AuthService authService;
    @MockBean TeacherRepository teacherRepository;
    @MockBean StudentRepository studentRepository;
    @MockBean SecurityUtil securityUtil;
    @MockBean ParentPortalService parentPortalService;
    @MockBean TeacherClassScopeService teacherClassScopeService;

    final HttpServletRequest http = mock(HttpServletRequest.class);
    final StudentLeaveApplyRequest req = new StudentLeaveApplyRequest(LocalDate.of(2026, 10, 6), "Fever");

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(authService.getRole()).thenReturn(role);
        when(authService.getUserId()).thenReturn("u1");
    }

    private void assertOnly(Runnable call, List<String> allowed) {
        for (String role : List.of("ADMIN", "TEACHER", "SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            if (allowed.contains(role)) assertThatCode(call::run).as(role).doesNotThrowAnyException();
            else assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void rolesForTheNewAndChangedEndpoints() {
        assertOnly(() -> leaves.applyLeave(req, "S9", http), List.of("STUDENT", "PARENT"));
        assertOnly(() -> leaves.reverseLeaveDecision(1L, Map.of("reason", "x"), http), List.of("ADMIN", "TEACHER"));
        assertOnly(() -> leaves.updateLeaveStatus(1L, Map.of("status", "APPROVED"), http), List.of("ADMIN", "TEACHER"));
        assertOnly(() -> leaves.deleteLeaveById(1L, "x", http), List.of("ADMIN"));
        assertOnly(() -> leaves.onLeaveToday(), List.of("ADMIN"));
        assertOnly(() -> teacherLeaves.reverse(1L, Map.of("reason", "x"), http), List.of("ADMIN"));
        assertOnly(() -> teacherLeaves.updateStatus(1L, Map.of("status", "APPROVED"), http), List.of("ADMIN"));
        assertOnly(() -> teacherLeaves.cancelLeave(1L, null, http), List.of("TEACHER", "ADMIN"));
    }

    @Test
    void aStudentAlwaysAppliesForThemselves_aParentOnlyForAnAuthorisedChild() {
        as("STUDENT");
        leaves.applyLeave(req, "SOMEONE-ELSE", http);
        verify(leaveService).applyLeave(eq("u1"), same(req), any());

        as("PARENT");
        leaves.applyLeave(req, "CHILD-1", http);
        verify(parentPortalService).assertChildAccess("CHILD-1", ParentPortalService.ChildPermission.MANAGE_LEAVE);
        verify(leaveService).applyLeave(eq("CHILD-1"), same(req), any());

        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(parentPortalService).assertChildAccess(eq("NOT-MINE"), any());
        assertThatThrownBy(() -> leaves.applyLeave(req, "NOT-MINE", http))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(leaveService, never()).applyLeave(eq("NOT-MINE"), any(), any());

        // Own history: a student's request is always for their own id (exact match in the service).
        as("STUDENT");
        leaves.getLeavesOfStudent("S10", null);
        verify(leaveService).getLeavesByStudentId(eq("u1"), any());
    }
}
