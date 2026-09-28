package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.entity.ReportCardPublication;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.ReportCardPublicationRepository;
import com.indraacademy.ias_management.util.SchoolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Report Card Phase 0: the async email blast carries its school explicitly, never the request thread's. */
@ExtendWith(MockitoExtension.class)
class ReportCardEmailBlastServiceTest {

    @Mock private ReportCardPublicationRepository pubRepo;
    @Mock private ReportCardDataAssembler assembler;
    @Mock private ReportCardPdfGenerator pdfGenerator;
    @Mock private EmailService emailService;
    @InjectMocks private ReportCardEmailBlastService service;

    private static Student student(String id, String email) {
        Student s = new Student();
        s.setStudentId(id);
        s.setName("Name " + id);
        s.setEmail(email);
        return s;
    }

    @Test
    void workerThreadWithoutRequestContextUsesTheExplicitSchoolAndClearsItAfterwards() throws Exception {
        AtomicReference<Long> schoolSeenByAssembler = new AtomicReference<>();
        AtomicReference<Long> schoolSeenByRanks = new AtomicReference<>();
        AtomicReference<Long> afterRun = new AtomicReference<>(-1L);
        when(assembler.classRanksForTemplate(100L, "2026-2027")).thenAnswer(inv -> {
            schoolSeenByRanks.set(SchoolContext.get());
            return Map.of("S1", 2);
        });
        ReportCardPublication pub = new ReportCardPublication();
        pub.setVerificationToken("tok-42");
        when(pubRepo.findBySchoolIdAndTemplateIdAndSessionAndClassName(42L, 100L, "2026-2027", "9")).thenReturn(Optional.of(pub));
        ReportCardDataDTO data = new ReportCardDataDTO();
        data.setSchoolName("School Forty-Two");
        when(assembler.assemble(eq("S1"), eq(100L), eq("2026-2027"), isNull(), eq(Map.of("S1", 2)))).thenAnswer(inv -> {
            schoolSeenByAssembler.set(SchoolContext.get());
            return data;
        });
        when(pdfGenerator.generate(data)).thenReturn(new byte[]{1});

        // A fresh thread, like the async pool's: no SchoolContext of its own.
        Thread worker = new Thread(() -> {
            service.execute(100L, "2026-2027", "9", 42L, List.of(student("S1", "s1@example.com"), student("S2", " ")), "School Forty-Two");
            afterRun.set(SchoolContext.get());
        });
        worker.start();
        worker.join();

        assertThat(schoolSeenByRanks.get()).isEqualTo(42L);
        assertThat(schoolSeenByAssembler.get()).isEqualTo(42L);
        assertThat(afterRun.get()).isNull();                                 // cleared for the pooled thread
        assertThat(data.getVerificationToken()).isEqualTo("tok-42");        // the published card's QR
        verify(emailService).sendReportCardEmail(eq("s1@example.com"), eq("Name S1"), eq("School Forty-Two"),
                eq("2026-2027"), any(), anyString());
        verify(emailService, times(1)).sendReportCardEmail(any(), any(), any(), any(), any(), any());
        verify(pubRepo).save(argThat(p -> p.getEmailCount() == 1));
    }

    @Test
    void refusesToRunWithoutASchool() {
        service.execute(100L, "2026-2027", "9", null, List.of(student("S1", "a@b.c")), "x");
        verifyNoInteractions(assembler, emailService);
    }
}
