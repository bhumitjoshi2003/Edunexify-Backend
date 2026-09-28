package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.indraacademy.ias_management.dto.MarkEntryRequest;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.entity.ReportCardDocument;
import com.indraacademy.ias_management.entity.ReportCardPublicationStatus;
import com.indraacademy.ias_management.entity.ReportCardResultMode;
import com.indraacademy.ias_management.entity.ReportCardSetup;
import com.indraacademy.ias_management.repository.ReportCardDocumentRepository;
import com.indraacademy.ias_management.repository.ReportCardSetupRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real-PostgreSQL coverage for Report Card V2, Phase 2: V84 tables and constraints; publishing
 * frozen documents (all-or-nothing), the frozen snapshot, republish / supersede, withdraw, public
 * verification, the exam-unpublish guard, remark locking, student / parent access and the bulk
 * ZIP of stored PDFs. Object storage is an in-memory map.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MarkService.class, ExamConfigService.class, WeightageCalculationEngine.class,
        StudentTemporalMembershipResolver.class, TimetableSessionAccessService.class,
        AttendanceService.class, AcademicSessionService.class,
        ReportCardSetupService.class, ReportCardDesignService.class, ReportCardV2Builder.class, ReportCardV2Service.class,
        OpenHtmlToPdfReportCardRenderer.class, ReportCardPdfGenerator.class, ReportCardV2PublicationService.class,
        ReportCardV2PublicationPostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ReportCardV2PublicationPostgresIT {

    static final long SCHOOL = -97001L, OTHER_SCHOOL = -97002L, SESSION = -97003L;
    static final long CLASS_8 = -97010L, SECTION_A = -97020L, SECTION_B = -97021L;
    static final String A1 = "RCP-A1", A2 = "RCP-A2", B1 = "RCP-B1";

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-24T06:00:00Z"), ZoneOffset.UTC); }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MarkService marks;
    @Autowired ExamConfigService exams;
    @Autowired ReportCardSetupService setups;
    @Autowired ReportCardV2Service reportCards;
    @Autowired ReportCardV2PublicationService publications;
    @Autowired ReportCardSetupRepository setupRepo;
    @Autowired ReportCardDocumentRepository documentRepo;
    @Autowired StudentRepository studentRepository;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean StudentService studentService;
    @MockBean TeacherClassScopeService classScope;
    @MockBean ObjectStorageService objectStorage;
    @MockBean BusinessNotificationService notifications;
    @MockBean ParentPortalService parentPortal;
    @MockBean com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    final Map<String, byte[]> store = new ConcurrentHashMap<>();
    final AtomicInteger keys = new AtomicInteger();
    long halfYearly, hyMath, hyScience;
    ReportCardSetup setup;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,grading_system) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'Publication School','TRIAL','rcp-it',4,8,'Asia/Kolkata','CBSE')," +
                "(?,true,CURRENT_TIMESTAMP,'Publication Other','TRIAL','rcp-other',4,8,'Asia/Kolkata','CBSE')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'8',true,false)", CLASS_8, SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)",
                SECTION_A, SCHOOL, CLASS_8, SECTION_B, SCHOOL, CLASS_8);
        student(A1, "Aarav Sharma", SECTION_A, "A");
        student(A2, "Bina Rao", SECTION_A, "A");
        student(B1, "Divya", SECTION_B, "B");
        jdbc.update("INSERT INTO class_subject (school_id,class_name,class_id,subject_name,is_elective,optional_group) VALUES " +
                "(?,'8',?,'Math',false,NULL),(?,'8',?,'Science',false,NULL)", SCHOOL, CLASS_8, SCHOOL, CLASS_8);

        asAdmin();
        when(studentService.getActiveStudentsByClass(anyString())).thenAnswer(inv ->
                studentRepository.findAll().stream().filter(s -> SCHOOL == s.getSchoolId() && inv.getArgument(0).equals(s.getClassName())).toList());
        when(studentService.getStudent(anyString())).thenAnswer(inv -> studentRepository.findById(inv.getArgument(0)));
        when(objectStorage.resolveDisplayUrl(any())).thenReturn(null);
        when(objectStorage.buildObjectKey(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(inv ->
                "schools/" + inv.getArgument(0) + "/" + inv.getArgument(1) + "/" + inv.getArgument(2) + "/" + inv.getArgument(3)
                        + "-" + keys.incrementAndGet() + "." + inv.getArgument(4));
        doAnswer(inv -> { store.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(objectStorage).putObject(anyString(), any(byte[].class), anyString());
        when(objectStorage.getObjectBytes(anyString())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(objectStorage.headObject(anyString())).thenAnswer(inv -> store.containsKey(inv.getArgument(0))
                ? Optional.of(new ObjectStorageService.ObjectMetadata(1, "application/pdf")) : Optional.empty());
        doAnswer(inv -> { store.remove(inv.getArgument(0)); return null; }).when(objectStorage).deleteObjectQuietly(anyString());

        halfYearly = exams.addExam("2026-2027", "8", "Half Yearly").getId();
        hyMath = exams.addExamSubject(halfYearly, "Math", 100, null).getId();
        hyScience = exams.addExamSubject(halfYearly, "Science", 100, null).getId();
        save(A1, hyMath, 80); save(A1, hyScience, 70);
        save(A2, hyMath, 60); save(A2, hyScience, 60);
        save(B1, hyMath, 50); save(B1, hyScience, 40);

        SetupDTO dto = setups.create(new SetupRequest(SESSION, CLASS_8, "Half Yearly", ReportCardResultMode.TOTAL, 0, List.of(),
                List.of(new ExamInput(halfYearly, null, null))));
        em.flush();
        setup = setupRepo.findById(dto.id()).orElseThrow();
    }

    // ── Publish ───────────────────────────────────────────────────────────

    @Test
    void publishingRequiresPublishedExams() {
        assertThatThrownBy(() -> publications.publish(setup.getId(), null)).hasMessageContaining("Publish the results of Half Yearly");
        assertThat(count("report_card_publication_v2")).isZero();
        assertThat(store).isEmpty();
    }

    @Test
    void publishFreezesEveryStudentsCardWithItsOwnTokenAndStoredPdf() throws Exception {
        exams.publishResults(halfYearly, null);
        PublishResultDTO result = publications.publish(setup.getId(), null);
        assertThat(result.published()).isTrue();
        assertThat(result.documents()).isEqualTo(3);
        assertThat(result.publication().version()).isEqualTo(1);
        assertThat(result.publication().status()).isEqualTo("ACTIVE");

        List<ReportCardDocument> docs = activeDocs();
        assertThat(docs).extracting(ReportCardDocument::getStudentId).containsExactlyInAnyOrder(A1, A2, B1);
        assertThat(docs).extracting(ReportCardDocument::getVerificationToken).doesNotHaveDuplicates();
        assertThat(docs).extracting(ReportCardDocument::getReference).allMatch(r -> r.matches("RC-[A-Z2-9]{10}"));
        ReportCardDocument a1 = doc(docs, A1);
        byte[] pdf = store.get(a1.getPdfObjectKey());
        assertThat(pdf).startsWith("%PDF".getBytes());
        assertThat(ReportCardV2PublicationService.sha256(pdf)).isEqualTo(a1.getPdfSha256());
        assertThat(ReportCardV2PublicationService.snapshotSha256(a1.getSnapshot())).isEqualTo(a1.getSnapshotSha256());
        assertThat(a1.getSchoolName()).isEqualTo("Publication School");
        assertThat(a1.getSectionName()).isEqualTo("A");

        JsonNode snap = new com.fasterxml.jackson.databind.ObjectMapper().readTree(a1.getSnapshot());
        assertThat(snap.path("reference").asText()).isEqualTo(a1.getReference());
        assertThat(snap.path("setup").path("exams").get(0).path("examName").asText()).isEqualTo("Half Yearly");
        assertThat(snap.path("result").path("percentage").asDouble()).isEqualTo(75.0);
        assertThat(snap.path("card").has("verificationQr")).isFalse();
        assertThat(snap.path("card").path("student").toString()).contains("Aarav Sharma");

        String text = ReportCardV2RendererTest.pages(pdf).stream().map(ReportCardV2RendererTest.Page::text).collect(Collectors.joining());
        assertThat(text).contains("Aarav Sharma").contains("75.00%").contains(a1.getReference()).doesNotContain("not a published report card");

        verify(notifications, times(3)).studentAndParents(eq(SCHOOL), anyString(), any(), any(), any(), anyString(), anyString(),
                eq("ReportCardDocument"), anyString(), startsWith("/dashboard/report-card-documents/"), anyString(), startsWith("report-card-v2:"), any());
    }

    @Test
    void laterChangesDoNotAlterAPublishedDocument() throws Exception {
        exams.publishResults(halfYearly, null);
        publications.publish(setup.getId(), null);
        ReportCardDocument before = doc(activeDocs(), A1);
        byte[] pdfBefore = store.get(before.getPdfObjectKey()).clone();
        String snapshotBefore = before.getSnapshot();
        String shaBefore = before.getSnapshotSha256();

        jdbc.update("UPDATE student_mark SET marks_obtained = 10 WHERE student_id = ?", A1);
        jdbc.update("UPDATE student SET name = 'Renamed Student' WHERE student_id = ?", A1);
        jdbc.update("UPDATE school SET name = 'Renamed School' WHERE id = ?", SCHOOL);
        em.clear();

        ReportCardDocument after = documentRepo.findById(before.getId()).orElseThrow();
        // JSONB returns the same document (keys reordered); its canonical fingerprint is unchanged.
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(after.getSnapshot())).isEqualTo(json.readTree(snapshotBefore));
        assertThat(ReportCardV2PublicationService.snapshotSha256(after.getSnapshot())).isEqualTo(shaBefore).isEqualTo(after.getSnapshotSha256());
        assertThat(after.getSnapshot()).contains("Aarav Sharma").contains("\"percentage\": 75.0").doesNotContain("Renamed");
        assertThat(publications.documentPdf(after.getId()).bytes()).isEqualTo(pdfBefore);
        assertThat(publications.verify(after.getVerificationToken()).orElseThrow().getSchoolName()).isEqualTo("Publication School");
    }

    @Test
    void incompleteStudentsAreExcludedUnlessIncludedAndFailuresPublishNothing() {
        jdbc.update("DELETE FROM student_mark WHERE student_id = ? AND exam_subject_entry_id = ?", A2, hyScience);
        exams.publishResults(halfYearly, null);
        PublishResultDTO result = publications.publish(setup.getId(), null);
        assertThat(result.published()).isTrue();
        assertThat(result.excludedIncomplete()).extracting(StudentProblem::studentId).containsExactly(A2);
        assertThat(activeDocs()).extracting(ReportCardDocument::getStudentId).containsExactlyInAnyOrder(A1, B1);

        // Storage fails for one student on republish: nothing changes, the failure is reported, files are cleaned up.
        int storedBefore = store.size();
        AtomicInteger uploads = new AtomicInteger();
        doAnswer(inv -> {
            if (uploads.incrementAndGet() == 2) throw new RuntimeException("storage offline");
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(objectStorage).putObject(anyString(), any(byte[].class), anyString());
        PublishResultDTO failed = publications.publish(setup.getId(), new PublishRequest(null, true));
        assertThat(failed.published()).isFalse();
        assertThat(failed.failed()).hasSize(1);
        assertThat(failed.failed().get(0).reason()).contains("storage offline");
        assertThat(failed.message()).contains("Nothing was published");
        assertThat(store).hasSize(storedBefore);
        assertThat(activeDocs()).hasSize(2);
        assertThat(publications.publications(setup.getId())).extracting(PublicationDTO::version).containsExactly(1);
    }

    // ── Republish, withdraw, verification ─────────────────────────────────

    @Test
    void republishSupersedesWithNewTokensAndWithdrawHidesEverything() {
        exams.publishResults(halfYearly, null);
        PublishResultDTO v1 = publications.publish(setup.getId(), null);
        ReportCardDocument oldA1 = doc(activeDocs(), A1);
        assertThat(publications.verify(oldA1.getVerificationToken()).orElseThrow().getStatus()).isEqualTo("VALID");

        PublishResultDTO v2 = publications.publish(setup.getId(), null);
        assertThat(v2.publication().version()).isEqualTo(2);
        em.clear();
        ReportCardDocument newA1 = doc(activeDocs(), A1);
        assertThat(newA1.getVerificationToken()).isNotEqualTo(oldA1.getVerificationToken());
        assertThat(newA1.getVersion()).isEqualTo(2);
        assertThat(documentRepo.findById(oldA1.getId()).orElseThrow().getStatus()).isEqualTo(ReportCardPublicationStatus.SUPERSEDED);
        assertThat(publications.publications(setup.getId())).extracting(PublicationDTO::status).containsExactly("ACTIVE", "SUPERSEDED");

        VerifyRcDTO superseded = publications.verify(oldA1.getVerificationToken()).orElseThrow();
        assertThat(superseded.isValid()).isFalse();
        assertThat(superseded.getStatus()).isEqualTo("SUPERSEDED");
        VerifyRcDTO valid = publications.verify(newA1.getVerificationToken()).orElseThrow();
        assertThat(valid.isValid()).isTrue();
        assertThat(valid.getStudentName()).isEqualTo("Aarav S.");
        assertThat(valid.getReference()).isEqualTo(newA1.getReference());

        asStudent(A1);
        assertThat(publications.myDocuments(null)).extracting(DocumentDTO::id).containsExactly(newA1.getId());
        assertThatThrownBy(() -> publications.document(oldA1.getId())).isInstanceOf(NoSuchElementException.class);

        asAdmin();
        publications.withdraw(v2.publication().id(), new WithdrawRequest("Marks correction"));
        em.clear();
        VerifyRcDTO withdrawn = publications.verify(newA1.getVerificationToken()).orElseThrow();
        assertThat(withdrawn.getStatus()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.getMessage()).isEqualTo("Withdrawn by the school.");
        assertThat(withdrawn.getStudentName()).isNull();
        assertThat(store.get(newA1.getPdfObjectKey())).isNotNull();                     // kept for the record
        assertThat(publications.document(newA1.getId()).status()).isEqualTo("WITHDRAWN");  // admin audit view
        asStudent(A1);
        assertThat(publications.myDocuments(null)).isEmpty();
        assertThatThrownBy(() -> publications.documentPdf(newA1.getId())).isInstanceOf(NoSuchElementException.class);
        asAdmin();
        assertThatThrownBy(() -> publications.withdraw(v2.publication().id(), null)).hasMessageContaining("Only the active version");
        assertThat(v1.publication().id()).isNotNull();
        assertThat(publications.verify("not-a-v2-token")).isEmpty();                         // falls through to Phase 0
    }

    @Test
    void sectionScopeRules() {
        exams.publishResults(halfYearly, null);
        PublishResultDTO a = publications.publish(setup.getId(), new PublishRequest(SECTION_A, false));
        assertThat(a.documents()).isEqualTo(2);
        PublishResultDTO b = publications.publish(setup.getId(), new PublishRequest(SECTION_B, false));
        assertThat(b.documents()).isEqualTo(1);
        assertThat(b.publication().version()).isEqualTo(1);           // versions count per scope
        assertThat(activeDocs()).hasSize(3);

        PublishResultDTO whole = publications.publish(setup.getId(), null);   // supersedes both sections
        em.clear();
        assertThat(whole.documents()).isEqualTo(3);
        assertThat(publications.publications(setup.getId())).filteredOn(p -> p.status().equals("ACTIVE")).hasSize(1);
        assertThatThrownBy(() -> publications.publish(setup.getId(), new PublishRequest(SECTION_A, false)))
                .hasMessageContaining("published for the whole class");
    }

    @Test
    void databaseAllowsOnlyOneActivePublicationPerScope() {
        exams.publishResults(halfYearly, null);
        publications.publish(setup.getId(), null);
        em.flush();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO report_card_publication_v2 (school_id,setup_id,version,status,published_at,created_at,updated_at) " +
                "VALUES (?,?,9,'ACTIVE',now(),now(),now())", SCHOOL, setup.getId())).hasMessageContaining("uq_report_card_publication_v2_active_scope");
    }

    // ── Guards ────────────────────────────────────────────────────────────

    @Test
    void examCannotBeUnpublishedAndSetupNotChangedWhileTheReportCardIsActive() {
        exams.publishResults(halfYearly, null);
        PublishResultDTO p = publications.publish(setup.getId(), null);
        assertThatThrownBy(() -> exams.unpublishResults(halfYearly, null))
                .hasMessage("This exam is part of a published report card. Unpublish the report card first.");
        assertThatThrownBy(() -> setups.update(setup.getId(), new SetupRequest(SESSION, CLASS_8, "Renamed", ReportCardResultMode.TOTAL, 0,
                List.of(), List.of(new ExamInput(halfYearly, null, null))))).hasMessageContaining("Withdraw it");
        assertThatThrownBy(() -> setups.delete(setup.getId())).hasMessageContaining("cannot be deleted");

        publications.withdraw(p.publication().id(), null);
        em.flush();
        assertThat(exams.unpublishResults(halfYearly, null).isPublished()).isFalse();
    }

    @Test
    void remarksAreLockedWhileTheStudentsCardIsActive() {
        exams.publishResults(halfYearly, null);
        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, "Good", null, null))));
        PublishResultDTO p = publications.publish(setup.getId(), new PublishRequest(SECTION_A, false));
        em.flush();

        assertThatThrownBy(() -> reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, "Changed", null, null)))))
                .isInstanceOf(IllegalStateException.class);
        RemarksPageDTO page = reportCards.remarks(setup.getId(), null);
        assertThat(page.students()).filteredOn(r -> r.studentId().equals(A1)).extracting(RemarkRowDTO::locked).containsExactly(true);
        assertThat(page.students()).filteredOn(r -> r.studentId().equals(B1)).extracting(RemarkRowDTO::locked).containsExactly(false);
        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(B1, "Fine", null, null))));   // section B is not published

        publications.withdraw(p.publication().id(), null);
        em.flush();
        reportCards.saveRemarks(setup.getId(), new RemarksSaveRequest(List.of(new RemarkSaveRow(A1, "Changed", null, null))));
    }

    // ── Access ────────────────────────────────────────────────────────────

    @Test
    void studentsAndParentsOnlyReachTheirOwnActiveCards() {
        exams.publishResults(halfYearly, null);
        publications.publish(setup.getId(), null);
        List<ReportCardDocument> docs = activeDocs();
        ReportCardDocument a1 = doc(docs, A1), b1 = doc(docs, B1);

        asStudent(A1);
        assertThat(publications.myDocuments(null)).extracting(DocumentDTO::studentId).containsExactly(A1);
        assertThat(publications.documentPdf(a1.getId()).bytes()).isEqualTo(store.get(a1.getPdfObjectKey()));
        assertThatThrownBy(() -> publications.myDocuments(B1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> publications.documentPdf(b1.getId())).isInstanceOf(ResponseStatusException.class);

        when(security.getRole()).thenReturn("PARENT");
        when(security.getUsername()).thenReturn("P-1");
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "no"))
                .when(parentPortal).assertChildAccess(eq(B1), any());
        assertThat(publications.myDocuments(A1)).extracting(DocumentDTO::studentId).containsExactly(A1);
        assertThat(publications.document(a1.getId()).reference()).isEqualTo(a1.getReference());
        assertThatThrownBy(() -> publications.myDocuments(B1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> publications.documentPdf(b1.getId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> publications.myDocuments(null)).isInstanceOf(IllegalArgumentException.class);

        when(security.getRole()).thenReturn("TEACHER");
        assertThatThrownBy(() -> publications.documentPdf(a1.getId())).isInstanceOf(ResponseStatusException.class);

        // Another school's admin cannot see this school's documents or publications.
        when(security.getRole()).thenReturn("ADMIN");
        when(security.getSchoolId()).thenReturn(OTHER_SCHOOL);
        assertThatThrownBy(() -> publications.document(a1.getId())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> publications.documents(a1.getPublicationId())).isInstanceOf(NoSuchElementException.class);
    }

    // ── Bulk & send ───────────────────────────────────────────────────────

    @Test
    void zipUsesTheStoredPdfsAndReportsMissingFiles() throws Exception {
        exams.publishResults(halfYearly, null);
        PublishResultDTO p = publications.publish(setup.getId(), null);
        List<ReportCardDocument> docs = activeDocs();
        store.remove(doc(docs, B1).getPdfObjectKey());

        BulkCheckDTO check = publications.bulkCheck(p.publication().id());
        assertThat(check.total()).isEqualTo(3);
        assertThat(check.available()).isEqualTo(2);
        assertThat(check.missing()).extracting(StudentProblem::studentId).containsExactly(B1);

        clearInvocations(objectStorage);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        publications.writeZip(publications.zipDocuments(p.publication().id()), out);
        verify(objectStorage, never()).putObject(anyString(), any(byte[].class), anyString());   // nothing re-rendered

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) entries.put(e.getName(), zip.readAllBytes());
        }
        assertThat(entries).hasSize(3);
        assertThat(entries.keySet()).anyMatch(n -> n.startsWith("Aarav_Sharma_RCP_A1_") && n.endsWith(".pdf"));
        assertThat(entries.get(entries.keySet().stream().filter(n -> n.startsWith("Aarav")).findFirst().orElseThrow()))
                .isEqualTo(store.get(doc(docs, A1).getPdfObjectKey()));
        String manifest = new String(entries.get("manifest.txt"), StandardCharsets.UTF_8);
        assertThat(manifest).contains("Report cards: 3").contains("Included:     2").contains("Failed:       1").contains("Divya (RCP-B1): stored PDF not found");
    }

    @Test
    void sendQueuesASecureLinkByEmail() {
        exams.publishResults(halfYearly, null);
        PublishResultDTO p = publications.publish(setup.getId(), null);
        clearInvocations(notifications);
        SendResultDTO sent = publications.send(p.publication().id());
        assertThat(sent.documents()).isEqualTo(3);
        assertThat(sent.queued()).isEqualTo(3);
        verify(notifications, times(3)).studentAndParents(eq(SCHOOL), anyString(), any(), any(), any(), anyString(),
                contains("https://edunexify.co.in/dashboard/report-card-documents/"), eq("ReportCardDocument"), anyString(),
                anyString(), anyString(), startsWith("report-card-v2-send:"),
                eq(Set.of(com.indraacademy.ias_management.notification.ExternalDeliveryChannel.EMAIL)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private List<ReportCardDocument> activeDocs() {
        em.flush();
        return documentRepo.findBySetupIdAndSchoolIdAndStatus(setup.getId(), SCHOOL, ReportCardPublicationStatus.ACTIVE);
    }

    private static ReportCardDocument doc(List<ReportCardDocument> docs, String studentId) {
        return docs.stream().filter(d -> d.getStudentId().equals(studentId)).findFirst().orElseThrow();
    }

    private int count(String table) {
        em.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE school_id = ?", Integer.class, SCHOOL);
    }

    private void asAdmin() {
        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("ADMIN");
        when(security.getUsername()).thenReturn("admin-rcp");
    }

    private void asStudent(String id) {
        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("STUDENT");
        when(security.getUsername()).thenReturn(id);
    }

    private void student(String id, String name, long sectionId, String sectionName) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,'8',?,?,DATE '2026-04-01')", id, SCHOOL, name, CLASS_8, sectionId, sectionName);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id," +
                "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,'8',?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SESSION, CLASS_8, sectionId, sectionName);
    }

    private void save(String studentId, long entryId, double value) {
        MarkEntryRequest r = new MarkEntryRequest();
        r.setStudentId(studentId);
        r.setExamSubjectEntryId(entryId);
        r.setMarksObtained(value);
        marks.bulkSaveMarks(List.of(r), null);
    }
}
