package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.service.ReportCardDesignService;
import com.indraacademy.ias_management.service.ReportCardSetupService;
import com.indraacademy.ias_management.service.ReportCardV2Service;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Report Card V2 API roles, through real Spring method security: setup and design are ADMIN-only;
 * remarks and Generate & Preview are ADMIN + TEACHER (the service then limits a teacher to their
 * own class/section); SUB_ADMIN, SUPER_ADMIN, STUDENT and PARENT get nothing.
 */
@SpringJUnitConfig(ReportCardV2SecurityTest.Config.class)
class ReportCardV2SecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean ReportCardV2Controller controller() { return new ReportCardV2Controller(); }
    }

    @Autowired ReportCardV2Controller controller;
    @MockBean ReportCardSetupService setupService;
    @MockBean ReportCardDesignService designService;
    @MockBean ReportCardV2Service reportCardService;

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void assertAdminOnly(Runnable call) {
        for (String role : List.of("TEACHER", "SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
        as("ADMIN");
        assertThatCode(call::run).doesNotThrowAnyException();
    }

    private void assertStaffOnly(Runnable call) {
        for (String role : List.of("SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
        for (String role : List.of("ADMIN", "TEACHER")) {
            as(role);
            assertThatCode(call::run).as(role).doesNotThrowAnyException();
        }
    }

    @Test
    void setupWritesAndDesignAreAdminOnly() {
        SetupRequest req = new SetupRequest(1L, 2L, "Annual", null, 0, List.of(), List.of());
        assertAdminOnly(() -> controller.createSetup(req));
        assertAdminOnly(() -> controller.updateSetup(5L, req));
        assertAdminOnly(() -> controller.deleteSetup(5L));
        assertAdminOnly(() -> controller.availableExams(1L, 2L));
        assertAdminOnly(() -> controller.design());
        assertAdminOnly(() -> controller.updateDesign(null));
        assertAdminOnly(() -> controller.activities());
        assertAdminOnly(() -> controller.createActivity(new ActivityRequest("Art", true)));
        assertAdminOnly(() -> controller.updateActivity(3L, new ActivityRequest(null, false)));
        assertAdminOnly(() -> controller.reorderActivities(List.of(3L)));
    }

    @Test
    void remarksAndPreviewAreForAdminsAndTeachers() {
        assertStaffOnly(() -> controller.setups(1L, 2L));
        assertStaffOnly(() -> controller.setup(5L));
        assertStaffOnly(() -> controller.remarks(5L, null));
        assertStaffOnly(() -> controller.saveRemarks(5L, new RemarksSaveRequest(List.of())));
        assertStaffOnly(() -> controller.summary(5L, null));
    }
}
