package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.service.ReportCardV2PublicationService;
import com.indraacademy.ias_management.service.ReportCardV2Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Published report card API roles, through real Spring method security: publishing, withdrawing,
 * bulk and send are ADMIN-only; the "my report cards" list is STUDENT / PARENT; one document is
 * ADMIN / STUDENT / PARENT (ownership is then checked per document in the service). TEACHER,
 * SUB_ADMIN and SUPER_ADMIN get nothing.
 */
@SpringJUnitConfig(ReportCardV2PublicationSecurityTest.Config.class)
class ReportCardV2PublicationSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean ReportCardV2PublicationController controller() { return new ReportCardV2PublicationController(); }
    }

    @Autowired ReportCardV2PublicationController controller;
    @MockBean ReportCardV2PublicationService publications;

    @BeforeEach
    void stubs() {
        when(publications.documentPdf(anyLong())).thenReturn(new ReportCardV2Service.Pdf(new byte[]{1}, "card.pdf"));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void as(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "u1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void assertOnly(Runnable call, List<String> allowed) {
        for (String role : List.of("ADMIN", "TEACHER", "SUB_ADMIN", "SUPER_ADMIN", "STUDENT", "PARENT")) {
            as(role);
            if (allowed.contains(role)) assertThatCode(call::run).as(role).doesNotThrowAnyException();
            else assertThatThrownBy(call::run).as(role).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void publicationActionsAreAdminOnly() {
        List<String> admin = List.of("ADMIN");
        assertOnly(() -> controller.publications(1L), admin);
        assertOnly(() -> controller.publish(1L, new PublishRequest(null, false)), admin);
        assertOnly(() -> controller.withdraw(2L, new WithdrawRequest("x")), admin);
        assertOnly(() -> controller.publicationDocuments(2L), admin);
        assertOnly(() -> controller.bulkCheck(2L), admin);
        assertOnly(() -> controller.zip(2L), admin);
        assertOnly(() -> controller.send(2L), admin);
    }

    @Test
    void studentsAndParentsReachDocumentsTeachersDoNot() {
        assertOnly(() -> controller.myDocuments(null), List.of("STUDENT", "PARENT"));
        assertOnly(() -> controller.document(3L), List.of("ADMIN", "STUDENT", "PARENT"));
        assertOnly(() -> controller.documentPdf(3L), List.of("ADMIN", "STUDENT", "PARENT"));
    }
}
