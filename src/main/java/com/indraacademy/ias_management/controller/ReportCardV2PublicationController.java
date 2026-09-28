package com.indraacademy.ias_management.controller;

import com.indraacademy.ias_management.config.Role;
import com.indraacademy.ias_management.dto.ReportCardV2Dtos.*;
import com.indraacademy.ias_management.entity.ReportCardDocument;
import com.indraacademy.ias_management.service.ReportCardV2PublicationService;
import com.indraacademy.ias_management.service.ReportCardV2Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/**
 * Report Card V2, Phase 2: published (frozen) report cards. ADMIN publishes, republishes,
 * withdraws, downloads and sends; STUDENT opens their own ACTIVE cards and PARENT the ACTIVE
 * cards of a linked child (checked per document in the service). TEACHER, SUB_ADMIN and
 * SUPER_ADMIN have no access here — teachers use Generate &amp; Preview.
 */
@RestController
@RequestMapping("/api/report-cards/v2")
public class ReportCardV2PublicationController {

    private static final String ADMIN = "hasRole('" + Role.ADMIN + "')";
    private static final String OWNERS = "hasAnyRole('" + Role.STUDENT + "', '" + Role.PARENT + "')";
    private static final String READERS = "hasAnyRole('" + Role.ADMIN + "', '" + Role.STUDENT + "', '" + Role.PARENT + "')";

    @Autowired private ReportCardV2PublicationService publications;

    // ── Admin ─────────────────────────────────────────────────────────────

    @PreAuthorize(ADMIN)
    @GetMapping("/setups/{id}/publications")
    public List<PublicationDTO> publications(@PathVariable Long id) {
        return publications.publications(id);
    }

    /** Publish, or republish as the next version. Nothing is published unless every card succeeds. */
    @PreAuthorize(ADMIN)
    @PostMapping("/setups/{id}/publications")
    public PublishResultDTO publish(@PathVariable Long id, @RequestBody(required = false) PublishRequest req) {
        return publications.publish(id, req);
    }

    @PreAuthorize(ADMIN)
    @PostMapping("/publications/{id}/withdraw")
    public PublicationDTO withdraw(@PathVariable Long id, @RequestBody(required = false) WithdrawRequest req) {
        return publications.withdraw(id, req);
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/publications/{id}/documents")
    public List<DocumentDTO> publicationDocuments(@PathVariable Long id) {
        return publications.documents(id);
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/publications/{id}/bulk-check")
    public BulkCheckDTO bulkCheck(@PathVariable Long id) {
        return publications.bulkCheck(id);
    }

    /** A ZIP of the stored PDFs, streamed one file at a time, with a manifest of any failures. */
    @PreAuthorize(ADMIN)
    @GetMapping("/publications/{id}/documents.zip")
    public ResponseEntity<StreamingResponseBody> zip(@PathVariable Long id) {
        List<ReportCardDocument> docs = publications.zipDocuments(id);
        StreamingResponseBody body = out -> publications.writeZip(docs, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-cards-" + id + ".zip\"")
                .body(body);
    }

    @PreAuthorize(ADMIN)
    @PostMapping("/publications/{id}/send")
    public SendResultDTO send(@PathVariable Long id) {
        return publications.send(id);
    }

    // ── Students, parents (and admins opening one document) ───────────────

    @PreAuthorize(OWNERS)
    @GetMapping("/documents")
    public List<DocumentDTO> myDocuments(@RequestParam(required = false) String studentId) {
        return publications.myDocuments(studentId);
    }

    @PreAuthorize(READERS)
    @GetMapping("/documents/{id}")
    public DocumentDTO document(@PathVariable Long id) {
        return publications.document(id);
    }

    @PreAuthorize(READERS)
    @GetMapping("/documents/{id}/pdf")
    public ResponseEntity<byte[]> documentPdf(@PathVariable Long id) {
        ReportCardV2Service.Pdf pdf = publications.documentPdf(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.fileName() + "\"")
                .body(pdf.bytes());
    }
}
