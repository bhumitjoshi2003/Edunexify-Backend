package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.repository.ReportCardPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** /verify-rc looks up a V2 document token first and otherwise answers exactly as Phase 0 did. */
@ExtendWith(MockitoExtension.class)
class ReportCardVerificationOrderTest {

    @Mock ReportCardPublicationRepository pubRepo;
    @Mock ReportCardV2PublicationService v2Publications;
    @Mock ObjectProvider<ReportCardV2PublicationService> v2Provider;
    @InjectMocks ReportCardPublicationService service;

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "v2Publications", v2Provider);
        lenient().when(v2Provider.getIfAvailable()).thenReturn(v2Publications);
    }

    @Test
    void v2TokenIsAnsweredByTheV2Document() {
        VerifyRcDTO v2 = VerifyRcDTO.document(true, "VALID", "School", "ANNUAL — REPORT CARD", "8 – A", "2026-2027",
                "2026-09-29", "Aarav S.", "RC-ABCDEFGHJK", 1, null);
        when(v2Publications.verify("tok")).thenReturn(Optional.of(v2));
        assertThat(service.verifyByToken("tok")).isSameAs(v2);
        verifyNoInteractions(pubRepo);
    }

    @Test
    void withoutReportCardV2OnlyPhase0IsAsked() {
        when(v2Provider.getIfAvailable()).thenReturn(null);
        when(pubRepo.findByVerificationToken("legacy")).thenReturn(Optional.empty());
        assertThat(service.verifyByToken("legacy").isValid()).isFalse();
        verifyNoInteractions(v2Publications);
    }

    @Test
    void otherTokensFallThroughToTheUnchangedPhase0Lookup() {
        when(v2Publications.verify("legacy")).thenReturn(Optional.empty());
        when(pubRepo.findByVerificationToken("legacy")).thenReturn(Optional.empty());
        VerifyRcDTO result = service.verifyByToken("legacy");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getStatus()).isNull();
        verify(pubRepo).findByVerificationToken("legacy");
    }
}
