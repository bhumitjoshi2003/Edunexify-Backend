package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.dto.VerifyRcDTO;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationAudienceType;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.*;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Report Card V2, Phase 2: publishing frozen report-card documents.
 *
 * <p>Publishing renders every eligible student's card from the canonical V2 view model, freezes
 * that view model as a JSON snapshot (SHA-256), stores the PDF rendered from it in object
 * storage and gives each document its own verification token. Everything is generated and
 * stored BEFORE any database row exists; the publication becomes ACTIVE in one transaction
 * together with all its documents (superseding the previous version), so students and parents
 * never see a partial batch. If any student fails, nothing is published, the stored files are
 * cleaned up and the failures are reported for a safe retry.</p>
 *
 * <p>Published documents are never recalculated: students, parents, bulk ZIPs and verification
 * always use the stored snapshot / PDF. Republishing creates version N+1 with new tokens;
 * withdrawing keeps every file and snapshot. The Phase 0 publication system is not touched.</p>
 */
@Service
public class ReportCardV2PublicationService {

    private static final Logger log = LoggerFactory.getLogger(ReportCardV2PublicationService.class);
    static final String RENDERER_VERSION = "openhtmltopdf-1.1.87/report-card-v2-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Canonical JSON (keys sorted) — PostgreSQL JSONB reorders keys, so fingerprints use this form. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final DateTimeFormatter ISSUED = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Autowired private ReportCardSetupService setupService;
    @Autowired private ReportCardSetupRepository setupRepo;
    @Autowired private ReportCardV2Builder builder;
    @Autowired private ReportCardV2Service reportCards;
    @Autowired private ReportCardRenderer renderer;
    @Autowired private ReportCardPdfGenerator qr;
    @Autowired private ObjectStorageService storage;
    @Autowired private ReportCardPublicationV2Repository publicationRepo;
    @Autowired private ReportCardDocumentRepository documentRepo;
    @Autowired private SectionRepository sectionRepo;
    @Autowired private BusinessNotificationService notifications;
    @Autowired private ParentPortalService parentPortal;
    @Autowired private SecurityUtil securityUtil;
    @Autowired private PlatformTransactionManager transactionManager;

    // ── Publish / republish ───────────────────────────────────────────────

    /** One generated, stored (not yet recorded) document. */
    private record Prepared(ReportCardV2Builder.StudentResult student, String token, String reference, String snapshot,
                            String snapshotSha, String objectKey, String pdfSha, long pdfSize, String sectionName,
                            LocalDateTime renderedAt) {}

    public PublishResultDTO publish(Long setupId, PublishRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        String user = securityUtil.getUsername();
        ReportCardSetup setup = setupService.readableSetup(setupId);
        Long sectionId = request != null ? request.sectionId() : null;
        boolean includeIncomplete = request != null && request.includeIncomplete();
        Section section = null;
        if (sectionId != null) {
            section = sectionRepo.findByIdAndSchoolId(sectionId, schoolId)
                    .orElseThrow(() -> new IllegalArgumentException("Section " + sectionId + " was not found in your school."));
            if (!setup.getClassId().equals(section.getClassId())) {
                throw new IllegalArgumentException("Section " + section.getName() + " is not part of this report card's class.");
            }
        }

        ReportCardV2Builder.ClassResult cls = builder.computeClass(setup);
        if (cls.columns().isEmpty()) throw new IllegalStateException("This report card has no exams.");
        List<String> drafts = cls.columns().stream().filter(c -> c.resultStatus() != ExamResultStatus.PUBLISHED)
                .map(ReportCardV2Builder.Column::examName).toList();
        if (!drafts.isEmpty()) {
            throw new IllegalStateException("Publish the results of " + String.join(", ", drafts) + " before publishing this report card.");
        }
        checkScope(setup, sectionId, publicationRepo.findBySetupIdAndSchoolIdAndStatus(setupId, schoolId, ReportCardPublicationStatus.ACTIVE));

        List<StudentProblem> excluded = new ArrayList<>();
        List<ReportCardV2Builder.StudentResult> eligible = new ArrayList<>();
        for (ReportCardV2Builder.StudentResult s : cls.students()) {
            if (!s.inSection(sectionId) || s.status() == ReportCardV2Builder.Status.NO_RESULT) continue;
            if (s.status() == ReportCardV2Builder.Status.INCOMPLETE && !includeIncomplete) {
                excluded.add(problem(s, s.marksMissing() + " mark(s) not entered"));
                continue;
            }
            eligible.add(s);
        }
        if (eligible.isEmpty()) {
            return new PublishResultDTO(false, null, 0, excluded, List.of(),
                    excluded.isEmpty() ? "No students sit this report card's exams in this class/section."
                            : "Every student has marks missing. Enter the marks, or publish with incomplete cards included.");
        }

        int version = (sectionId == null ? publicationRepo.maxVersionForClass(setupId, schoolId)
                : publicationRepo.maxVersionForSection(setupId, schoolId, sectionId)) + 1;
        LocalDateTime issuedAt = LocalDateTime.now();
        List<Prepared> prepared = new ArrayList<>();
        List<StudentProblem> failed = new ArrayList<>();
        for (ReportCardV2Builder.StudentResult s : eligible) {
            try {
                prepared.add(prepare(cls, s, version, issuedAt, schoolId, setupId));
            } catch (Exception e) {
                log.warn("Report card generation failed for student {} (setup {}): {}", s.student().getStudentId(), setupId, e.getMessage());
                failed.add(problem(s, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }
        if (!failed.isEmpty()) {
            cleanUp(prepared);
            return new PublishResultDTO(false, null, 0, excluded, failed,
                    "Nothing was published: " + failed.size() + " card(s) could not be generated. Fix the problem and publish again.");
        }

        final Long scopeSectionId = sectionId;
        ReportCardPublicationV2 publication;
        try {
            publication = new TransactionTemplate(transactionManager).execute(tx ->
                    activate(setup, scopeSectionId, version, issuedAt, user, cls, prepared));
        } catch (RuntimeException e) {
            cleanUp(prepared);
            throw e;
        }
        notifyPublished(publication, user);
        return new PublishResultDTO(true, toDto(publication, setup.getName(), section != null ? section.getName() : null),
                prepared.size(), excluded, List.of(), "Published version " + version + " — " + prepared.size() + " report card(s).");
    }

    /** Renders, freezes and stores one student's card (no database writes). */
    private Prepared prepare(ReportCardV2Builder.ClassResult cls, ReportCardV2Builder.StudentResult s, int version,
                             LocalDateTime issuedAt, Long schoolId, Long setupId) throws Exception {
        String token = newToken();
        String reference = newReference();
        String issuedLine = "Issued " + issuedAt.format(ISSUED) + "  ·  Ref " + reference + "  ·  Version " + version;
        ReportCardV2ViewModel vm = reportCards.viewModel(cls, s, new ReportCardV2Service.Stamp(issuedLine, qr.qrDataUri(token), reference));
        if (vm.verificationQr() == null) throw new IllegalStateException("The verification QR could not be generated.");
        String snapshot = snapshot(vm, cls, s, version, reference, issuedAt);
        byte[] pdf = renderer.render(vm);
        LocalDateTime renderedAt = LocalDateTime.now();
        String key = storage.buildObjectKey(schoolId, "report-cards", "setup-" + setupId, "v" + version, "pdf");
        storage.putObject(key, pdf, "application/pdf");
        return new Prepared(s, token, reference, snapshot, snapshotSha256(snapshot), key,
                sha256(pdf), pdf.length, cls.sectionName(s.sectionId()), renderedAt);
    }

    /** Inside one transaction: supersede the previous version(s) and record the new one with all documents. */
    private ReportCardPublicationV2 activate(ReportCardSetup setup, Long sectionId, int version, LocalDateTime issuedAt,
                                             String user, ReportCardV2Builder.ClassResult cls, List<Prepared> prepared) {
        Long schoolId = setup.getSchoolId();
        setupRepo.lockByIdAndSchoolId(setup.getId(), schoolId).orElseThrow();
        int current = sectionId == null ? publicationRepo.maxVersionForClass(setup.getId(), schoolId)
                : publicationRepo.maxVersionForSection(setup.getId(), schoolId, sectionId);
        if (current + 1 != version) {
            throw new IllegalStateException("This report card was just published by someone else. Refresh and try again.");
        }
        List<ReportCardPublicationV2> active = publicationRepo.findBySetupIdAndSchoolIdAndStatus(setup.getId(), schoolId, ReportCardPublicationStatus.ACTIVE);
        checkScope(setup, sectionId, active);
        for (ReportCardPublicationV2 p : active) {
            if (sectionId == null || sectionId.equals(p.getSectionId())) {
                p.setStatus(ReportCardPublicationStatus.SUPERSEDED);
                publicationRepo.saveAndFlush(p);
                documentRepo.updateStatusForPublication(p.getId(), schoolId, ReportCardPublicationStatus.SUPERSEDED);
            }
        }
        ReportCardPublicationV2 publication = new ReportCardPublicationV2();
        publication.setSchoolId(schoolId);
        publication.setSetupId(setup.getId());
        publication.setSectionId(sectionId);
        publication.setVersion(version);
        publication.setStatus(ReportCardPublicationStatus.ACTIVE);
        publication.setDocumentCount(prepared.size());
        publication.setPublishedBy(user);
        publication.setPublishedAt(issuedAt);
        publication = publicationRepo.saveAndFlush(publication);
        String schoolName = reportCardsSchoolName(prepared);
        String title = ReportCardDataAssembler.reportTitle(setup.getName(), false);
        List<ReportCardDocument> documents = new ArrayList<>();
        for (Prepared p : prepared) {
            ReportCardDocument d = new ReportCardDocument();
            d.setSchoolId(schoolId);
            d.setPublicationId(publication.getId());
            d.setSetupId(setup.getId());
            d.setStudentId(p.student().student().getStudentId());
            d.setStatus(ReportCardPublicationStatus.ACTIVE);
            d.setVersion(version);
            d.setVerificationToken(p.token());
            d.setReference(p.reference());
            d.setSchoolName(schoolName);
            d.setTitle(title);
            d.setSessionLabel(cls.session().getLabel());
            d.setClassName(cls.schoolClass().getName());
            d.setSectionName(p.sectionName());
            d.setStudentName(p.student().student().getName() != null ? p.student().student().getName() : p.student().student().getStudentId());
            d.setSnapshot(p.snapshot());
            d.setSnapshotSha256(p.snapshotSha());
            d.setPdfObjectKey(p.objectKey());
            d.setPdfSha256(p.pdfSha());
            d.setPdfSize(p.pdfSize());
            d.setRendererVersion(RENDERER_VERSION);
            d.setRenderedAt(p.renderedAt());
            d.setIssuedAt(issuedAt);
            documents.add(d);
        }
        documentRepo.saveAll(documents);
        documentRepo.flush();
        return publication;
    }

    /** A section can't be published over an active whole-class version (republish the class or withdraw it). */
    private static void checkScope(ReportCardSetup setup, Long sectionId, List<ReportCardPublicationV2> active) {
        if (sectionId != null && active.stream().anyMatch(p -> p.getSectionId() == null)) {
            throw new IllegalStateException("This report card is published for the whole class. Republish the whole class, "
                    + "or withdraw it before publishing a single section.");
        }
    }

    private void cleanUp(List<Prepared> prepared) {
        for (Prepared p : prepared) {
            try {
                storage.deleteObjectQuietly(p.objectKey());
            } catch (Exception e) {
                log.warn("Could not remove unpublished report card file {}: {}", p.objectKey(), e.getMessage());
            }
        }
    }

    private static String reportCardsSchoolName(List<Prepared> prepared) {
        try {
            return JSON.readTree(prepared.get(0).snapshot()).path("card").path("school").path("name").asText("School");
        } catch (IOException e) {
            return "School";
        }
    }

    private void notifyPublished(ReportCardPublicationV2 publication, String user) {
        for (ReportCardDocument d : documentRepo.findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(publication.getId(), publication.getSchoolId())) {
            try {
                notifications.studentAndParents(d.getSchoolId(), d.getStudentId(), NotificationAudienceType.STUDENT_WITH_RESULT_PARENTS,
                        NotificationEventCode.REPORT_CARD_READY, NotificationCategory.ACADEMICS_RESULTS,
                        "Report Card Available", "Your " + displayTitle(d) + " for " + d.getSessionLabel() + " is now available.",
                        "ReportCardDocument", String.valueOf(d.getId()), documentRoute(d), user,
                        "report-card-v2:" + d.getId(), Set.of(ExternalDeliveryChannel.PUSH));
            } catch (Exception e) {
                log.warn("Report card notification failed for document {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    /** The in-app link to one document (the recipient's own card; access is checked when it opens). */
    static String documentRoute(ReportCardDocument d) {
        return "/dashboard/report-card-documents/" + d.getId();
    }

    // ── Withdraw ──────────────────────────────────────────────────────────

    @Transactional
    public PublicationDTO withdraw(Long publicationId, WithdrawRequest request) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardPublicationV2 p = publicationRepo.findByIdAndSchoolId(publicationId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Publication not found: " + publicationId));
        ReportCardSetup setup = setupService.readableSetup(p.getSetupId());
        if (p.getStatus() != ReportCardPublicationStatus.ACTIVE) {
            throw new IllegalStateException("Only the active version can be withdrawn.");
        }
        String reason = request != null && request.reason() != null ? request.reason().trim() : null;
        if (reason != null && reason.length() > 500) throw new IllegalArgumentException("The reason can be at most 500 characters.");
        p.setStatus(ReportCardPublicationStatus.WITHDRAWN);
        p.setWithdrawnBy(securityUtil.getUsername());
        p.setWithdrawnAt(LocalDateTime.now());
        p.setWithdrawalReason(reason == null || reason.isEmpty() ? null : reason);
        publicationRepo.saveAndFlush(p);
        documentRepo.updateStatusForPublication(p.getId(), schoolId, ReportCardPublicationStatus.WITHDRAWN);
        return toDto(p, setup.getName(), sectionName(p.getSectionId(), schoolId));
    }

    // ── Admin reads ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PublicationDTO> publications(Long setupId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardSetup setup = setupService.readableSetup(setupId);
        return publicationRepo.findBySetupIdAndSchoolIdOrderByPublishedAtDescIdDesc(setupId, schoolId).stream()
                .map(p -> toDto(p, setup.getName(), sectionName(p.getSectionId(), schoolId))).toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentDTO> documents(Long publicationId) {
        ReportCardPublicationV2 p = adminPublication(publicationId);
        return documentRepo.findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(p.getId(), p.getSchoolId())
                .stream().map(ReportCardV2PublicationService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public BulkCheckDTO bulkCheck(Long publicationId) {
        ReportCardPublicationV2 p = adminPublication(publicationId);
        List<ReportCardDocument> docs = documentRepo.findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(p.getId(), p.getSchoolId());
        List<StudentProblem> missing = new ArrayList<>();
        for (ReportCardDocument d : docs) {
            boolean present;
            try {
                present = storage.headObject(d.getPdfObjectKey()).isPresent();
            } catch (Exception e) {
                present = false;
            }
            if (!present) missing.add(new StudentProblem(d.getStudentId(), d.getStudentName(), "Stored PDF not found"));
        }
        return new BulkCheckDTO(docs.size(), docs.size() - missing.size(), missing);
    }

    /** The documents of a publication for a ZIP (read in the request, streamed later). */
    @Transactional(readOnly = true)
    public List<ReportCardDocument> zipDocuments(Long publicationId) {
        ReportCardPublicationV2 p = adminPublication(publicationId);
        return documentRepo.findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(p.getId(), p.getSchoolId());
    }

    /**
     * Streams a ZIP of the STORED PDFs (never re-rendered), one PDF in memory at a time, and ends
     * with manifest.txt listing total / included / failed and every failed student — a missing or
     * altered file is reported, never silently skipped. Needs no request context (safe on the
     * async streaming thread).
     */
    public void writeZip(List<ReportCardDocument> documents, OutputStream out) throws IOException {
        List<String> failures = new ArrayList<>();
        int included = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ReportCardDocument d : documents) {
                try {
                    Optional<byte[]> pdf = storage.getObjectBytes(d.getPdfObjectKey());
                    if (pdf.isEmpty()) { failures.add(d.getStudentName() + " (" + d.getStudentId() + "): stored PDF not found"); continue; }
                    if (!sha256(pdf.get()).equals(d.getPdfSha256())) { failures.add(d.getStudentName() + " (" + d.getStudentId() + "): stored PDF does not match its fingerprint"); continue; }
                    zip.putNextEntry(new ZipEntry(safeName(d.getStudentName()) + "_" + safeName(d.getStudentId()) + "_" + d.getReference() + ".pdf"));
                    zip.write(pdf.get());
                    zip.closeEntry();
                    included++;
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    failures.add(d.getStudentName() + " (" + d.getStudentId() + "): " + e.getMessage());
                }
            }
            StringBuilder manifest = new StringBuilder()
                    .append("Report cards: ").append(documents.size()).append('\n')
                    .append("Included:     ").append(included).append('\n')
                    .append("Failed:       ").append(failures.size()).append('\n');
            failures.forEach(f -> manifest.append(" - ").append(f).append('\n'));
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /** Emails every student/parent of the ACTIVE version a secure link through the notification pipeline. */
    public SendResultDTO send(Long publicationId) {
        ReportCardPublicationV2 p = adminPublication(publicationId);
        if (p.getStatus() != ReportCardPublicationStatus.ACTIVE) {
            throw new IllegalStateException("Only the active version can be sent.");
        }
        List<ReportCardDocument> docs = documentRepo.findByPublicationIdAndSchoolIdOrderByStudentNameAscStudentIdAsc(p.getId(), p.getSchoolId());
        String base = qr.frontendBase();
        String user = securityUtil.getUsername();
        long sendKey = System.currentTimeMillis();
        int queued = 0;
        for (ReportCardDocument d : docs) {
            try {
                notifications.studentAndParents(d.getSchoolId(), d.getStudentId(), NotificationAudienceType.STUDENT_WITH_RESULT_PARENTS,
                        NotificationEventCode.REPORT_CARD_READY, NotificationCategory.ACADEMICS_RESULTS,
                        "Report card: " + displayTitle(d) + " (" + d.getSessionLabel() + ")",
                        "The " + displayTitle(d) + " of " + d.getStudentName() + " for " + d.getSessionLabel()
                                + " has been published. Sign in to Edunexify to view or download it: " + base + documentRoute(d),
                        "ReportCardDocument", String.valueOf(d.getId()), documentRoute(d), user,
                        "report-card-v2-send:" + d.getId() + ":" + sendKey, Set.of(ExternalDeliveryChannel.EMAIL));
                queued++;
            } catch (Exception e) {
                log.warn("Report card email could not be queued for document {}: {}", d.getId(), e.getMessage());
            }
        }
        return new SendResultDTO(docs.size(), queued, queued + " of " + docs.size()
                + " report card(s) queued for email. Delivery status is in the notification delivery log.");
    }

    private ReportCardPublicationV2 adminPublication(Long publicationId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardPublicationV2 p = publicationRepo.findByIdAndSchoolId(publicationId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Publication not found: " + publicationId));
        setupService.readableSetup(p.getSetupId());
        return p;
    }

    // ── Students & parents (and admins) ───────────────────────────────────

    /** ACTIVE documents of the caller (student) or of a linked child the parent may see results for. */
    @Transactional(readOnly = true)
    public List<DocumentDTO> myDocuments(String studentId) {
        String owner = authorizedStudent(studentId);
        return documentRepo.findBySchoolIdAndStudentIdAndStatusOrderByIssuedAtDesc(securityUtil.getSchoolId(), owner,
                ReportCardPublicationStatus.ACTIVE).stream().map(ReportCardV2PublicationService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public DocumentDTO document(Long documentId) {
        return toDto(readableDocument(documentId));
    }

    /** The STORED PDF of a document (never re-rendered). */
    @Transactional(readOnly = true)
    public ReportCardV2Service.Pdf documentPdf(Long documentId) {
        ReportCardDocument d = readableDocument(documentId);
        byte[] pdf = storage.getObjectBytes(d.getPdfObjectKey())
                .orElseThrow(() -> new NoSuchElementException("The stored report card file could not be found."));
        if (!sha256(pdf).equals(d.getPdfSha256())) {
            log.error("Stored report card {} does not match its fingerprint", d.getId());
            throw new IllegalStateException("The stored report card file failed its integrity check. Please contact the school.");
        }
        return new ReportCardV2Service.Pdf(pdf, safeName(d.getStudentName()) + "_" + safeName(d.getTitle()) + "_" + d.getSessionLabel() + "_" + d.getReference() + ".pdf");
    }

    /**
     * A document the caller may open: an ADMIN any document of their school (any status, for
     * audit); a STUDENT only their own ACTIVE document; a PARENT only an ACTIVE document of a
     * linked child with results access. Never authorised by a student id alone.
     */
    private ReportCardDocument readableDocument(Long documentId) {
        Long schoolId = securityUtil.getSchoolId();
        ReportCardDocument d = documentRepo.findByIdAndSchoolId(documentId, schoolId)
                .orElseThrow(() -> new NoSuchElementException("Report card not found."));
        String role = securityUtil.getRole();
        if (Role.ADMIN.equals(role)) return d;
        if (Role.STUDENT.equals(role) || Role.PARENT.equals(role)) {
            authorizedStudent(d.getStudentId());
            if (d.getStatus() != ReportCardPublicationStatus.ACTIVE) throw new NoSuchElementException("Report card not found.");
            return d;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot open published report cards.");
    }

    /** The student whose documents the caller may see (student: themselves; parent: an authorised child). */
    private String authorizedStudent(String studentId) {
        String role = securityUtil.getRole();
        if (Role.STUDENT.equals(role)) {
            String self = securityUtil.getUsername();
            if (studentId != null && !studentId.isBlank() && !studentId.equals(self)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Students can only open their own report cards.");
            }
            return self;
        }
        if (Role.PARENT.equals(role)) {
            if (studentId == null || studentId.isBlank()) throw new IllegalArgumentException("Choose a child.");
            parentPortal.assertChildAccess(studentId, ParentPortalService.ChildPermission.RESULTS);
            return studentId;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only students and parents have report cards here.");
    }

    // ── Public verification ───────────────────────────────────────────────

    /**
     * Public verification of a V2 document by its token (no login, no school context): only the
     * school, title, session, class, a limited student identity, issue date, reference, version
     * and status — never marks, dates of birth, parents, attendance or remarks. Empty when the
     * token is not a V2 token (the caller then tries the legacy Phase 0 tokens).
     */
    @Transactional(readOnly = true)
    public Optional<VerifyRcDTO> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return documentRepo.findByVerificationToken(token.trim()).map(d -> {
            String cls = d.getSectionName() != null ? d.getClassName() + " – " + d.getSectionName() : d.getClassName();
            String issued = d.getIssuedAt().toLocalDate().toString();
            return switch (d.getStatus()) {
                case ACTIVE -> VerifyRcDTO.document(true, "VALID", d.getSchoolName(), d.getTitle(), cls, d.getSessionLabel(),
                        issued, limitedName(d.getStudentName()), d.getReference(), d.getVersion(), null);
                case SUPERSEDED -> VerifyRcDTO.document(false, "SUPERSEDED", d.getSchoolName(), d.getTitle(), cls, d.getSessionLabel(),
                        issued, limitedName(d.getStudentName()), d.getReference(), d.getVersion(),
                        "This report card was replaced by a newer version issued by the school.");
                case WITHDRAWN -> VerifyRcDTO.document(false, "WITHDRAWN", d.getSchoolName(), null, null, null,
                        null, null, d.getReference(), null, "Withdrawn by the school.");
            };
        });
    }

    /** "Aarav Sharma" → "Aarav S." */
    static String limitedName(String name) {
        if (name == null || name.isBlank()) return null;
        String[] parts = name.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    // ── Snapshot & helpers ────────────────────────────────────────────────

    /**
     * The frozen record of exactly what the card shows (the V2 view model it was rendered from)
     * plus the setup structure and issue data. Image links are kept without their expiring
     * signatures (the images themselves are frozen inside the stored PDF); the QR image is left
     * out (it is derived from the token).
     */
    String snapshot(ReportCardV2ViewModel vm, ReportCardV2Builder.ClassResult cls, ReportCardV2Builder.StudentResult s,
                    int version, String reference, LocalDateTime issuedAt) {
        ObjectNode card = JSON.valueToTree(vm);
        card.remove("verificationQr");
        stripQuery((ObjectNode) card.get("school"), "logoUrl");
        stripQuery((ObjectNode) card.get("school"), "headerImageUrl");
        stripQuery((ObjectNode) card.get("student"), "photoUrl");
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("issuedAt", issuedAt.toString());
        root.put("reference", reference);
        root.put("version", version);
        root.put("rendererVersion", RENDERER_VERSION);
        ObjectNode setup = root.putObject("setup");
        setup.put("id", cls.setup().getId());
        setup.put("name", cls.setup().getName());
        setup.put("resultMode", cls.setup().getResultMode().name());
        setup.put("session", cls.session().getLabel());
        setup.put("className", cls.schoolClass().getName());
        var exams = setup.putArray("exams");
        cls.columns().forEach(c -> {
            ObjectNode e = exams.addObject();
            e.put("examId", c.examId());
            e.put("examName", c.examName());
            e.put("term", c.termName());
            if (c.weight() != null) e.put("weight", c.weight());
        });
        ObjectNode result = root.putObject("result");
        result.put("studentId", s.student().getStudentId());
        result.put("status", s.status().name());
        if (s.percentage() != null) result.put("percentage", s.percentage());
        result.put("grade", s.grade());
        if (s.rank() != null) result.put("rank", s.rank());
        result.put("marksMissing", s.marksMissing());
        root.set("card", card);
        return canonical(root);
    }

    /**
     * SHA-256 of a snapshot in canonical form (sorted keys, no whitespace), so the fingerprint is
     * the same for the JSON written at publish time and the JSONB text PostgreSQL returns later.
     */
    static String snapshotSha256(String snapshotJson) {
        try {
            return sha256(canonical(JSON.readTree(snapshotJson)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("The report card snapshot is not valid JSON.", e);
        }
    }

    private static String canonical(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return CANONICAL.writeValueAsString(JSON.treeToValue(node, Object.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not freeze the report card snapshot.", e);
        }
    }

    private static void stripQuery(ObjectNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return;
        node.put(field, node.get(field).asText().replaceAll("\\?.*$", ""));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String newReference() {
        StringBuilder sb = new StringBuilder("RC-");
        for (int i = 0; i < 10; i++) sb.append(REF_ALPHABET[RANDOM.nextInt(REF_ALPHABET.length)]);
        return sb.toString();
    }

    private static String safeName(String s) {
        return (s == null ? "report_card" : s).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String displayTitle(ReportCardDocument d) {
        String t = d.getTitle();
        return t == null ? "report card" : t.replace("—", "-").replace("  ", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String sectionName(Long sectionId, Long schoolId) {
        if (sectionId == null) return null;
        return sectionRepo.findByIdAndSchoolId(sectionId, schoolId).map(Section::getName).orElse(null);
    }

    private static StudentProblem problem(ReportCardV2Builder.StudentResult s, String reason) {
        return new StudentProblem(s.student().getStudentId(), s.student().getName(), reason);
    }

    static PublicationDTO toDto(ReportCardPublicationV2 p, String setupName, String sectionName) {
        return new PublicationDTO(p.getId(), p.getSetupId(), setupName, p.getSectionId(), sectionName, p.getVersion(),
                p.getStatus().name(), p.getDocumentCount(), p.getPublishedBy(), p.getPublishedAt(), p.getWithdrawnBy(),
                p.getWithdrawnAt(), p.getWithdrawalReason());
    }

    static DocumentDTO toDto(ReportCardDocument d) {
        return new DocumentDTO(d.getId(), d.getPublicationId(), d.getStudentId(), d.getStudentName(), d.getTitle(),
                d.getSessionLabel(), d.getClassName(), d.getSectionName(), d.getVersion(), d.getStatus().name(),
                d.getReference(), d.getIssuedAt());
    }
}
