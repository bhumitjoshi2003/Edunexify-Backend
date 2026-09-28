package com.indraacademy.ias_management.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Document-first PDF report card generator.
 *
 * Design principles (matching the Angular report-card component):
 *   - Times-Roman throughout — official, serif, document-grade
 *   - White page background; no colored cell fills on data rows
 *   - Thin colored top stripe on school header; bordered "REPORT CARD" box
 *   - All table borders: solid #999 gray (collapsed, document style)
 *   - Grade coloring: text color only — no pill backgrounds
 *   - Attendance: plain 4-column table (Working / Present / Absent / %)
 *   - Result (Pass/Fail): plain bold text, no colored badge
 *   - Section title bars: solid primary-color bar with white text (appropriate in print)
 *   - Uses OpenPDF 1.3.11 (com.github.librepdf:openpdf) — colors are java.awt.Color
 */
@Service
public class ReportCardPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportCardPdfGenerator.class);

    /** Base URL for QR verification links, e.g. https://edunexify.co.in */
    @Value("${frontend.url:https://edunexify.co.in}")
    private String frontendUrl;

    // ── Document palette ───────────────────────────────────────────────────
    private static final Color WHITE       = Color.WHITE;
    private static final Color TEXT_DARK   = new Color(82, 100,  94);   // softened grey-green ink
    private static final Color TEXT_MID    = new Color(126, 139, 133);  // muted grey-green
    private static final Color BORDER_GRAY = new Color(213, 201, 184);  // #d5c9b8 — parchment border
    private static final Color ROW_ALT     = new Color(245, 240, 232);  // #f5f0e8 — warm zebra
    private static final Color TOTALS_BG   = new Color(245, 240, 232);  // same warm tint for totals

    // ── Indra Academy fixed design palette ────────────────────────────────
    private static final Color PARCHMENT   = new Color(250, 248, 242);  // #faf8f2 — page background
    private static final Color DARK_GREEN  = new Color(69,  95,  87);   // softened school-title ink
    private static final Color GOLD        = new Color(194, 155, 75);   // #c29b4b — rules, badge, footer

    // Grade text colors (text-only, no backgrounds)
    private static final Color GRADE_A_COLOR = new Color(20,  90,  20);   // dark green
    private static final Color GRADE_B_COLOR = new Color(12,  84,  96);   // teal-dark
    private static final Color GRADE_C_COLOR = new Color(120, 80,   4);   // amber-dark
    private static final Color GRADE_F_COLOR = new Color(114, 28,  36);   // red-dark

    // Pass / Fail text colors
    private static final Color PASS_COLOR  = new Color(20,  90,  20);
    private static final Color FAIL_COLOR  = new Color(114, 28,  36);

    // ── Fonts (Times-Roman throughout) ─────────────────────────────────────
    // School header
    private static final Font F_SCHOOL_NAME = FontFactory.getFont(FontFactory.TIMES_BOLD,   16, DARK_GREEN);
    private static final Font F_SCHOOL_DET  = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_RC_LABEL    = FontFactory.getFont(FontFactory.TIMES_BOLD,   11, TEXT_DARK);  // colored at build time
    private static final Font F_SESSION     = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);

    // Section / table
    private static final Font F_SEC_TITLE   = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, WHITE);
    private static final Font F_TH          = FontFactory.getFont(FontFactory.TIMES_BOLD,    8, WHITE);
    private static final Font F_SI_LABEL    = FontFactory.getFont(FontFactory.TIMES_BOLD,    7, TEXT_MID);  // student-info label
    private static final Font F_SI_VALUE    = FontFactory.getFont(FontFactory.TIMES_BOLD,  8.5f, TEXT_DARK); // student-info value
    private static final Font F_BODY        = FontFactory.getFont(FontFactory.TIMES_ROMAN,   9, TEXT_DARK);
    private static final Font F_BODY_SM     = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_BODY_BOLD   = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, TEXT_DARK);
    private static final Font F_SUMMARY_VAL = FontFactory.getFont(FontFactory.TIMES_BOLD,   12, TEXT_DARK);
    private static final Font F_SIGN_LABEL  = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_REMARKS     = FontFactory.getFont(FontFactory.TIMES_ITALIC,  9, TEXT_DARK);

    // Grade fonts (text-only)
    private static final Font F_GRADE_A     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_A_COLOR);
    private static final Font F_GRADE_B     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_B_COLOR);
    private static final Font F_GRADE_C     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_C_COLOR);
    private static final Font F_GRADE_F     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_F_COLOR);

    private static final DecimalFormat DF1 = new DecimalFormat("0.0");
    private static final DecimalFormat DF0 = new DecimalFormat("0");

    // ── Branding config ────────────────────────────────────────────────────

    private static class BrandingConfig {
        Color   primary         = DARK_GREEN;  // fixed Indra Academy dark green
        boolean showWatermark   = false;
        String  watermarkText   = "";
        String  watermarkType   = "TEXT";
        boolean showCgpa        = true;
        boolean showGradePoints = false;
        String  layoutStyle     = "WARM_ELEGANCE"; // fixed — single Indra Academy design
        String  schoolMotto     = "";
        String  examTerm        = "";  // e.g. "Half-Yearly" → shown as "Half-Yearly Examination"
        String  session         = "";  // academic session e.g. "2026-2027"
    }

    private BrandingConfig parseBranding(ReportCardTemplateDTO template) {
        BrandingConfig cfg = new BrandingConfig();
        // Always use fixed Indra Academy design — layoutStyle and primary are hardcoded
        if (template == null || template.getBrandingJson() == null
                || template.getBrandingJson().isBlank()) return cfg;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(template.getBrandingJson(), Map.class);

            // primaryColor and layoutStyle intentionally ignored — design is fixed
            cfg.showWatermark   = Boolean.TRUE.equals(map.get("showWatermark"));
            cfg.watermarkText   = (String) map.getOrDefault("watermarkText", "");
            cfg.watermarkType   = (String) map.getOrDefault("watermarkType", "TEXT");
            cfg.schoolMotto     = (String) map.getOrDefault("schoolMotto", "");
            cfg.examTerm        = (String) map.getOrDefault("examTerm", "");

            Object sc  = map.get("showCgpa");
            if (sc  instanceof Boolean) cfg.showCgpa        = (Boolean) sc;
            Object sgp = map.get("showGradePoints");
            if (sgp instanceof Boolean) cfg.showGradePoints = (Boolean) sgp;
        } catch (Exception ignored) {}
        return cfg;
    }

    private boolean isNewLayout(BrandingConfig branding) {
        return "WARM_ELEGANCE".equals(branding.layoutStyle) || "NAVY_SCHOLAR".equals(branding.layoutStyle);
    }

    private static Color hexToColor(String hex) {
        try {
            String h = hex.replace("#", "");
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return new Color(
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16));
        } catch (Exception e) { return new Color(21, 101, 192); }
    }

    // ── Public API ────────────────────────────────────────────────────────

    // ── Report Card V2 renderer (the one visual standard) ─────────────────
    // Present in the running application: every Phase 0 PDF (single, bulk, email) is drawn by the
    // V2 renderer from the same data, so all report cards share one design. Absent in unit tests
    // that construct this class directly, which then exercise the legacy OpenPDF layout below.
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ReportCardRenderer v2Renderer;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ReportCardDesignService designService;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.indraacademy.ias_management.util.SecurityUtil securityUtil;

    /** The report-card PDF: the V2 design when available, otherwise the legacy OpenPDF layout. */
    public byte[] generate(ReportCardDataDTO data) {
        if (v2Renderer != null && designService != null && securityUtil != null && securityUtil.getSchoolId() != null) {
            return v2Renderer.render(ReportCardV2Documents.fromPhase0(data,
                    designService.designFor(securityUtil.getSchoolId()), qrDataUri(data.getVerificationToken()),
                    verificationReference(data.getVerificationToken())));
        }
        return generateLegacy(data);
    }

    /** The verification QR as a data URI (same URL/token as the legacy layout); null without a token. */
    String qrDataUri(String token) {
        if (token == null || token.isBlank()) return null;
        byte[] png = generateQrBytes(verificationUrl(token), 240);
        return png == null ? null : "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
    }

    /** Legacy OpenPDF layout (kept until Phase 0 is retired). */
    public byte[] generateLegacy(ReportCardDataDTO data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // A4, compact margins. Static school/student identity keeps its size;
        // variable sections below use compact spacing so ordinary reports stay on one page.
        Document document = new Document(PageSize.A4, 36f, 36f, 30f, 24f);
        try {
            // All per-document state (writer, token, branding) stays local to this call: the
            // generator is a shared singleton used concurrently by single, bulk and email PDFs.
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            BrandingConfig branding = parseBranding(data.getTemplate());
            // Populate session so the footer seal line can display it
            if (data.getSession() != null && !data.getSession().isBlank()) {
                branding.session = data.getSession();
            }

            // Always draw parchment background; optionally add watermark on top
            String watermarkText = null;
            byte[] watermarkLogoBytes = null;
            if (branding.showWatermark) {
                if ("LOGO".equalsIgnoreCase(branding.watermarkType)) {
                    watermarkLogoBytes = loadLogoBytes(data.getSchoolLogoUrl());
                    if (watermarkLogoBytes == null) {
                        watermarkText = safe(data.getSchoolName(), "CONFIDENTIAL");
                    }
                } else {
                    watermarkText = (branding.watermarkText != null && !branding.watermarkText.isBlank())
                            ? branding.watermarkText : safe(data.getSchoolName(), "CONFIDENTIAL");
                }
            }
            writer.setPageEvent(new PageSetupEvent(watermarkText, watermarkLogoBytes));

            document.open();

            List<ReportCardTemplateDTO.SectionDTO> sections = getSortedEnabledSections(data.getTemplate());
            ReportCardTemplateDTO.SectionDTO coSection = sections.stream()
                    .filter(s -> "CO_SCHOLASTIC".equals(s.getSectionType()))
                    .findFirst().orElse(null);

            // Single default Edunexify report-card design for every school.
            addSchoolHeader(document, writer, data, branding);
            addStudentInfo(document, data, branding);
            addMarksTable(document, data, branding);
            addAttendanceAndCoScholastic(document, data, coSection, branding);
            addAssessmentSummary(document, data, branding);
            addRemarks(document, "Class Teacher's Remarks", data.getTeacherRemarks(), branding);
            addRemarks(document, "Principal's Remarks", data.getPrincipalRemarks(), branding);
            addBottomBalanceSpacer(document, writer, data);
            addPromotionStatus(document, writer, data, branding);
            addQrVerification(document, writer, data.getVerificationToken());
            addSignatures(document, writer, branding);

        } catch (Exception e) {
            log.error("PDF generation failed for student {}: {}", data.getStudentId(), e.getMessage(), e);
            throw new RuntimeException("PDF generation failed: " + e.getMessage(), e);
        } finally {
            if (document.isOpen()) document.close();
        }
        return baos.toByteArray();
    }

    // ── Page setup event: parchment background + optional watermark ──────

    private static class PageSetupEvent extends PdfPageEventHelper {
        private final String text;       // non-null → text watermark
        private final byte[] logoBytes;  // non-null → logo watermark

        PageSetupEvent(String text, byte[] logoBytes) {
            this.text = text;
            this.logoBytes = logoBytes;
        }

        @Override
        public void onStartPage(PdfWriter writer, Document document) {
            // Fill entire page with parchment/cream color
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                cb.saveState();
                cb.setColorFill(PARCHMENT);
                cb.rectangle(0, 0,
                        document.getPageSize().getWidth(),
                        document.getPageSize().getHeight());
                cb.fill();
                cb.restoreState();
            } catch (Exception ignored) {}
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                if (text != null || logoBytes != null) {
                    PdfContentByte under = writer.getDirectContentUnder();
                    under.saveState();
                    PdfGState gs = new PdfGState();

                    if (logoBytes != null) {
                        gs.setFillOpacity(0.055f);
                        gs.setBlendMode(PdfGState.BM_NORMAL);
                        under.setGState(gs);
                        Image img = Image.getInstance(logoBytes);
                        float pw = document.getPageSize().getWidth();
                        float ph = document.getPageSize().getHeight();
                        float maxSize = Math.min(pw, ph) * 0.42f;
                        img.scaleToFit(maxSize, maxSize);
                        float x = (pw - img.getScaledWidth())  / 2f;
                        float y = (ph - img.getScaledHeight()) / 2f;
                        img.setAbsolutePosition(x, y);
                        under.addImage(img);
                    } else {
                        gs.setFillOpacity(0.055f);
                        under.setGState(gs);
                        under.beginText();
                        under.setFontAndSize(
                                BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), 96);
                        under.setColorFill(new Color(210, 205, 195));
                        under.showTextAligned(Element.ALIGN_CENTER, text,
                                document.getPageSize().getWidth()  / 2f,
                                document.getPageSize().getHeight() / 2f,
                                0f);
                        under.endText();
                    }
                    under.restoreState();
                }

                PdfContentByte over = writer.getDirectContent();
                float left = 10f;
                float bottom = 10f;
                float width = document.getPageSize().getWidth() - 20f;
                float height = document.getPageSize().getHeight() - 20f;
                over.saveState();
                over.setColorStroke(DARK_GREEN);
                over.setLineWidth(1.2f);
                over.rectangle(left, bottom, width, height);
                over.stroke();
                over.setColorStroke(GOLD);
                over.setLineWidth(0.6f);
                over.rectangle(left + 4f, bottom + 4f, width - 8f, height - 8f);
                over.stroke();
                over.restoreState();
            } catch (Exception ignored) {}
        }
    }

    // ── SCHOOL_HEADER ─────────────────────────────────────────────────────
    // Layout: [Logo] | [School name + details] | [REPORT CARD box]
    // Logo column is omitted when no logo URL is present.
    // Mirrors Angular: .rc-header-logo | .rc-header-center | rc-report-title-box

    private void addSchoolHeader(Document doc, PdfWriter writer, ReportCardDataDTO data,
                                  BrandingConfig branding) throws DocumentException {
        // Always use the elegant centered header — header image upload has been removed
        addElegantHeader(doc, writer, data, branding);
        return;
        // (dead code below retained for reference only)
    }

    @SuppressWarnings("unused")
    private void addSchoolHeaderLegacy(Document doc, ReportCardDataDTO data,
                                        BrandingConfig branding) throws DocumentException {

        // 1. Thin colored top stripe
        PdfPTable stripe = new PdfPTable(1);
        stripe.setWidthPercentage(100);
        stripe.setSpacingAfter(0);
        PdfPCell stripeCell = new PdfPCell(new Phrase(" "));
        stripeCell.setBackgroundColor(branding.primary);
        stripeCell.setFixedHeight(5f);
        stripeCell.setBorder(Rectangle.NO_BORDER);
        stripe.addCell(stripeCell);
        doc.add(stripe);

        // 2. Try to load the school logo
        Image logoImage = loadLogoImage(data.getSchoolLogoUrl());

        // 3. Header table — 2 cols with logo, or 1 col without
        //    (the "REPORT CARD" label is now in the full-width title band below)
        PdfPTable header;
        if (logoImage != null) {
            header = new PdfPTable(new float[]{0.8f, 4.4f});
        } else {
            header = new PdfPTable(1);
        }
        header.setWidthPercentage(100);
        header.setSpacingAfter(10);

        // Logo cell (only when logo loaded)
        if (logoImage != null) {
            logoImage.scaleToFit(64, 64);
            PdfPCell logoCell = new PdfPCell(logoImage, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            logoCell.setPaddingTop(8);
            logoCell.setPaddingBottom(8);
            logoCell.setPaddingRight(8);
            header.addCell(logoCell);
        }

        // Center: school name (centered, prominent) + address + affiliation + contact
        PdfPCell center = new PdfPCell();
        center.setBackgroundColor(WHITE);
        center.setBorder(Rectangle.NO_BORDER);
        center.setPaddingTop(8);
        center.setPaddingBottom(8);
        center.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font schoolNameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 18, branding.primary);
        Paragraph schoolName = new Paragraph(safe(data.getSchoolName(), "School Name").toUpperCase(), schoolNameFont);
        schoolName.setAlignment(Element.ALIGN_CENTER);
        schoolName.setSpacingAfter(3);
        center.addElement(schoolName);

        // Board type subtitle — e.g. "CBSE Affiliated"
        if (data.getBoardType() != null && !data.getBoardType().isBlank()) {
            String boardLabel = switch (data.getBoardType()) {
                case "CBSE"  -> "CBSE Affiliated";
                case "ICSE"  -> "ICSE Affiliated";
                case "STATE" -> "State Board";
                default      -> "";
            };
            if (!boardLabel.isBlank()) {
                Font boardFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 8.5f, TEXT_MID);
                Paragraph boardPara = new Paragraph(boardLabel, boardFont);
                boardPara.setAlignment(Element.ALIGN_CENTER);
                boardPara.setSpacingAfter(2);
                center.addElement(boardPara);
            }
        }

        // Affiliation number (when present)
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank()) {
            Font affFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8, TEXT_MID);
            Paragraph affPara = new Paragraph("Affiliation No: " + data.getAffiliationNumber(), affFont);
            affPara.setAlignment(Element.ALIGN_CENTER);
            center.addElement(affPara);
        }

        if (data.getSchoolAddress() != null && !data.getSchoolAddress().isBlank()) {
            Paragraph addr = new Paragraph(data.getSchoolAddress(), F_SCHOOL_DET);
            addr.setAlignment(Element.ALIGN_CENTER);
            center.addElement(addr);
        }
        String contact = buildContact(data);
        if (!contact.isBlank()) {
            Paragraph contactPara = new Paragraph(contact, F_SCHOOL_DET);
            contactPara.setAlignment(Element.ALIGN_CENTER);
            center.addElement(contactPara);
        }
        header.addCell(center);
        doc.add(header);

        // Full-width document title band below the school info
        addDocTitleBand(doc, data, branding);
    }

    /**
     * Full-width colored band that unambiguously identifies the document.
     * Shows "REPORT CARD" in large white text, with the academic session and
     * exam/term name (e.g. "Half Yearly") on the line below.
     * Rendered after both the custom header image and the auto-generated header.
     */
    private void addDocTitleBand(Document doc, ReportCardDataDTO data,
                                  BrandingConfig branding) throws DocumentException {
        String layout = branding.layoutStyle;

        if ("WARM_ELEGANCE".equals(layout)) {
            // Gold rules bracket the title; the session sits below the lower rule.
            addHRule(doc, GOLD, 4);
            Font rcFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 12.5f, TEXT_DARK);
            Paragraph rcTitle = new Paragraph(letterSpaced(reportTitle(data)), rcFont);
            rcTitle.setAlignment(Element.ALIGN_CENTER);
            rcTitle.setSpacingBefore(3);
            rcTitle.setSpacingAfter(4);
            doc.add(rcTitle);
            addHRule(doc, GOLD, 3);

            // The exam / term is in the title now; the line below carries the session only.
            String session = data.getSession() != null && !data.getSession().isBlank()
                    ? "Academic Session " + data.getSession() : "";
            if (!session.isBlank()) {
                Font sessionFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.5f, TEXT_MID);
                Paragraph sessionPara = new Paragraph(session, sessionFont);
                sessionPara.setAlignment(Element.ALIGN_CENTER);
                sessionPara.setSpacingAfter(5);
                doc.add(sessionPara);
            }
            return;
        }

        if ("NAVY_SCHOLAR".equals(layout)) {
            addHRule(doc, new Color(200, 200, 200), 6);
            Font rcFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, branding.primary);
            Paragraph rcTitle = new Paragraph("Report Card", rcFont);
            rcTitle.setAlignment(Element.ALIGN_CENTER);
            rcTitle.setSpacingBefore(4);
            rcTitle.setSpacingAfter(2);
            doc.add(rcTitle);
            addHRuleCentered(doc, branding.primary, 4);
            String session = buildSessionLine(data, branding);
            if (!session.isBlank()) {
                Font sessionFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.5f, GOLD);
                Paragraph sessionPara = new Paragraph(session.toUpperCase(), sessionFont);
                sessionPara.setAlignment(Element.ALIGN_CENTER);
                sessionPara.setSpacingAfter(10);
                doc.add(sessionPara);
            }
            return;
        }

        // CLASSIC — solid colored band (original behavior)
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        band.setSpacingAfter(8);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(branding.primary);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(9);
        cell.setPaddingBottom(9);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font titleFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 15, WHITE);
        Paragraph title = new Paragraph("REPORT CARD", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(3);
        cell.addElement(title);

        String session = buildSessionLine(data);
        if (!session.isBlank()) {
            Font metaFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 9, new Color(210, 220, 230));
            Paragraph metaPara = new Paragraph(session, metaFont);
            metaPara.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(metaPara);
        }

        band.addCell(cell);
        doc.add(band);
    }

    static String reportTitle(ReportCardDataDTO data) {
        return data.getReportTitle() != null && !data.getReportTitle().isBlank() ? data.getReportTitle() : "REPORT CARD";
    }

    /** "R E P O R T     C A R D" style; long exam names stay plain so the title fits one line. */
    static String letterSpaced(String title) {
        if (title.length() > 34) return title;
        StringBuilder out = new StringBuilder();
        for (String word : title.split(" ")) {
            if (out.length() > 0) out.append("     ");
            out.append(String.join(" ", word.split("")));
        }
        return out.toString();
    }

    private String buildSessionLine(ReportCardDataDTO data, BrandingConfig branding) {
        StringBuilder meta = new StringBuilder();
        if (data.getSession() != null && !data.getSession().isBlank())
            meta.append("Academic Session ").append(data.getSession());
        // Prefer examTerm from branding; fall back to assessment group name
        String term = (branding.examTerm != null && !branding.examTerm.isBlank())
                ? branding.examTerm
                : (data.getWeightedResult() != null
                        && data.getWeightedResult().getGroupName() != null
                        ? data.getWeightedResult().getGroupName() : "");
        if (!term.isBlank()) {
            if (meta.length() > 0) meta.append(" \u00b7 ");
            meta.append(term);
            if (!term.toLowerCase().contains("exam")) meta.append(" Examination");
        }
        return meta.toString();
    }

    /** Overload for callers that don't have branding context */
    private String buildSessionLine(ReportCardDataDTO data) {
        return buildSessionLine(data, new BrandingConfig());
    }

    private void addHRule(Document doc, Color color, float spacingAfter) throws DocumentException {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingAfter(spacingAfter);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidthBottom(1f);
        c.setMinimumHeight(0);
        c.setPadding(0);
        rule.addCell(c);
        doc.add(rule);
    }

    private void addHRuleCentered(Document doc, Color color, float spacingAfter) throws DocumentException {
        PdfPTable rule = new PdfPTable(new float[]{1f, 2f, 1f});
        rule.setWidthPercentage(100);
        rule.setSpacingAfter(spacingAfter);
        rule.addCell(emptyCell());
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidthBottom(2f);
        c.setMinimumHeight(0);
        c.setPadding(0);
        rule.addCell(c);
        rule.addCell(emptyCell());
        doc.add(rule);
    }

    /**
     * Elegant centered header for WARM_ELEGANCE and NAVY_SCHOLAR layouts.
     * Renders: [logo centered] → [school name large centered] → [motto italic] → [affiliation] → [title band]
     */
    private void addElegantHeader(Document doc, PdfWriter writer, ReportCardDataDTO data,
                                   BrandingConfig branding) throws DocumentException {
        // Logo — centered above school name
        Image logoImage = loadLogoImage(data.getSchoolLogoUrl());
        if (logoImage != null) {
            logoImage.scaleToFit(60, 60);
            logoImage.setAlignment(Image.ALIGN_CENTER);
            PdfPTable logoTable = new PdfPTable(1);
            logoTable.setWidthPercentage(100);
            logoTable.setSpacingAfter(2);
            PdfPCell logoCell = new PdfPCell(logoImage, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            logoCell.setPaddingTop(2);
            logoCell.setPaddingBottom(2);
            logoTable.addCell(logoCell);
            doc.add(logoTable);
        } else {
            // Initials monogram — double circle drawn with PdfContentByte
            String name = safe(data.getSchoolName(), "School");
            String[] words = name.trim().split("\\s+");
            String initials = words.length >= 2
                    ? ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase()
                    : name.substring(0, Math.min(2, name.length())).toUpperCase();

            // Use a 1-cell table as a spacer to reserve vertical room, then draw over it
            float circleSize = 58f; // diameter in points
            PdfPTable spacer = new PdfPTable(1);
            spacer.setWidthPercentage(100);
            spacer.setSpacingBefore(8);
            spacer.setSpacingAfter(6);
            PdfPCell spacerCell = new PdfPCell();
            spacerCell.setBorder(Rectangle.NO_BORDER);
            spacerCell.setFixedHeight(circleSize + 8f);
            spacer.addCell(spacerCell);
            doc.add(spacer);

            // Draw the double circle directly on the canvas
            try {
                com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
                float pageWidth = doc.getPageSize().getWidth();
                float cx = pageWidth / 2f;
                // y position: after the spacer table — approximate from top
                float cy = writer.getVerticalPosition(false) + (circleSize / 2f) + 2f;
                float r1 = circleSize / 2f;       // outer ring radius
                float r2 = r1 - 5f;               // inner ring radius

                // Outer thin gold ring
                cb.setColorStroke(GOLD);
                cb.setLineWidth(0.8f);
                cb.circle(cx, cy, r1);
                cb.stroke();

                // Inner dark-green ring
                cb.setColorStroke(DARK_GREEN);
                cb.setLineWidth(1.5f);
                cb.circle(cx, cy, r2);
                cb.stroke();

                // Initials text centered in circle
                cb.beginText();
                cb.setColorFill(DARK_GREEN);
                cb.setFontAndSize(
                    com.lowagie.text.pdf.BaseFont.createFont(
                        com.lowagie.text.pdf.BaseFont.TIMES_BOLD,
                        com.lowagie.text.pdf.BaseFont.WINANSI, false),
                    16f);
                float tw = cb.getEffectiveStringWidth(initials, false);
                cb.setTextMatrix(cx - tw / 2f, cy - 6f);
                cb.showText(initials);
                cb.endText();
            } catch (Exception ignored) {
                // fallback: if canvas drawing fails, just continue — school name still renders
            }
        }

        // School name — large, uppercase, widely tracked, primary color.
        Font schoolNameFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 22, DARK_GREEN);
        String rawName = safe(data.getSchoolName(), "School Name").toUpperCase();
        Paragraph schoolName = new Paragraph(spacedTitle(rawName), schoolNameFont);
        schoolName.setAlignment(Element.ALIGN_CENTER);
        schoolName.setSpacingAfter(2);
        doc.add(schoolName);

        // Motto (italic, gold)
        if (branding.schoolMotto != null && !branding.schoolMotto.isBlank()) {
            Color mottoColor = GOLD;
            Font mottoFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.5f, mottoColor);
            Paragraph motto = new Paragraph("\u2767  " + branding.schoolMotto + "  \u2767", mottoFont);
            motto.setAlignment(Element.ALIGN_CENTER);
            motto.setSpacingAfter(2);
            doc.add(motto);
        }

        // Affiliation line: "Affiliation No. X · School Code Y · City"
        StringBuilder metaLine = new StringBuilder();
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank())
            metaLine.append("Affiliation No. ").append(data.getAffiliationNumber());
        if (data.getSchoolCode() != null && !data.getSchoolCode().isBlank()) {
            if (metaLine.length() > 0) metaLine.append("  \u00b7  ");
            metaLine.append("School Code ").append(data.getSchoolCode());
        }
        if (data.getSchoolCity() != null && !data.getSchoolCity().isBlank()) {
            if (metaLine.length() > 0) metaLine.append("  \u00b7  ");
            metaLine.append(data.getSchoolCity());
        } else if (data.getSchoolAddress() != null && !data.getSchoolAddress().isBlank()) {
            if (metaLine.length() > 0) metaLine.append("  \u00b7  ");
            metaLine.append(data.getSchoolAddress());
        }
        if (metaLine.length() > 0) {
            Font metaFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.2f, TEXT_MID);
            Paragraph meta = new Paragraph(metaLine.toString(), metaFont);
            meta.setAlignment(Element.ALIGN_CENTER);
            meta.setSpacingAfter(4);
            doc.add(meta);
        }

        // Layout-specific title band
        addDocTitleBand(doc, data, branding);
    }

    /**
     * Loads the school logo for embedding in the PDF.
     *
     * Phase 3: the local-disk fallback branch (resolving a relative /uploads/school-logos/...
     * path against logoDirectory) was removed — ReportCardDataAssembler now always resolves
     * School.logoUrl through ObjectStorageService.resolveDisplayUrl before it ever reaches this
     * class, so a real value is either null or an absolute presigned/http(s) URL. No production
     * school still has a legacy relative path (the one that did, Indra Academy, was re-uploaded
     * through Object Storage).
     *
     * Returns null silently on any error so the header degrades gracefully (2-column layout).
     */
    private Image loadLogoImage(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank() || !(logoUrl.startsWith("http://") || logoUrl.startsWith("https://"))) {
            return null;
        }
        try {
            java.net.URL url = new java.net.URL(logoUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                return Image.getInstance(conn.getInputStream().readAllBytes());
            }
            log.debug("School logo HTTP {} for URL: {}", conn.getResponseCode(), logoUrl);
        } catch (Exception e) {
            log.debug("School logo could not be loaded ({}): {}", logoUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the raw bytes of the school logo for use in the logo watermark.
     * Same URL handling as loadLogoImage() but returns bytes so they can be passed into the
     * static WatermarkEvent class.
     */
    private byte[] loadLogoBytes(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank() || !(logoUrl.startsWith("http://") || logoUrl.startsWith("https://"))) {
            return null;
        }
        try {
            java.net.URL url = new java.net.URL(logoUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                return conn.getInputStream().readAllBytes();
            }
        } catch (Exception e) {
            log.debug("Logo bytes could not be loaded for watermark ({}): {}", logoUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Loads the custom report card header image.
     * Same URL handling as loadLogoImage — see its Phase 3 comment for why the local-disk
     * fallback was removed. Returns null silently on any error so the caller can fall back to
     * the auto-generated header.
     */
    private Image loadHeaderImage(String headerUrl) {
        if (headerUrl == null || headerUrl.isBlank() || !(headerUrl.startsWith("http://") || headerUrl.startsWith("https://"))) {
            return null;
        }
        try {
            java.net.URL url = new java.net.URL(headerUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                return Image.getInstance(conn.getInputStream().readAllBytes());
            }
            log.debug("Report card header HTTP {} for URL: {}", conn.getResponseCode(), headerUrl);
        } catch (Exception e) {
            log.debug("Report card header image could not be loaded ({}): {}", headerUrl, e.getMessage());
        }
        return null;
    }

    // ── STUDENT_INFO ──────────────────────────────────────────────────────
    // Label/value pair table with passport-size photo on the right.

    private void addStudentInfo(Document doc, ReportCardDataDTO data,
                                 BrandingConfig branding) throws DocumentException {
        doc.add(centeredSectionTitle("S T U D E N T   I N F O R M A T I O N"));

        // Load student photo (may be null → placeholder rectangle)
        Image studentPhoto = loadStudentPhotoImage(data.getPhotoUrl());

        // 5-column table: label | value | label | value | photo (rowspan 3)
        // Rows: [Name | Class&Section], [Admission No. | Date of Birth], [Father's Name | Mother's Name]
        PdfPTable table = new PdfPTable(new float[]{1.3f, 2f, 0.3f, 1.3f, 2f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(5);

        String classDisplay = (data.getSectionName() != null && !data.getSectionName().isBlank())
                ? safe(data.getClassName(), "—") + " \u2014 " + data.getSectionName()
                : safe(data.getClassName(), "—");

        // Photo cell spanning 3 rows
        PdfPCell photoCell = new PdfPCell();
        photoCell.setRowspan(3);
        photoCell.setBorder(Rectangle.NO_BORDER);
        photoCell.setPadding(4);
        photoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        photoCell.setVerticalAlignment(Element.ALIGN_TOP);
        if (studentPhoto != null) {
            studentPhoto.scaleToFit(50, 76);
            photoCell.setImage(studentPhoto);
        } else {
            photoCell.setFixedHeight(76f);
        }

        // Row 1: Name | value | [spacer] | Class & Section | value | [photo starts]
        table.addCell(siLabelCell("Name"));
        table.addCell(siValueCell(safe(data.getStudentName(), "—")));
        table.addCell(siSpacerCell(3));  // will define below
        table.addCell(siLabelCell("Class \u0026 Section"));
        table.addCell(siValueCell(classDisplay));
        table.addCell(photoCell);

        // Row 2: Admission No. | value | [spacer col spanned from row 1] | Date of Birth | value
        table.addCell(siLabelCell("Admission No."));
        table.addCell(siValueCell(safe(data.getStudentId(), "—")));
        table.addCell(siLabelCell("Date of Birth"));
        table.addCell(siValueCell(safe(data.getDateOfBirth(), "—")));

        // Row 3: Father's Name | value | [spacer col spanned from row 1] | Mother's Name | value
        table.addCell(siLabelCell("Father\u2019s Name"));
        table.addCell(siValueCell(safe(data.getFatherName(), "\u2014")));
        table.addCell(siLabelCell("Mother\u2019s Name"));
        table.addCell(siValueCell(safe(data.getMotherName(), "\u2014")));

        doc.add(table);
    }

    /**
     * Loads the student photo — same URL handling as loadLogoImage(). See its Phase 3 comment
     * for why the local-disk fallback was removed. Returns null silently so the caller can
     * render a placeholder instead.
     */
    private Image loadStudentPhotoImage(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank() || !(photoUrl.startsWith("http://") || photoUrl.startsWith("https://"))) {
            return null;
        }
        try {
            java.net.URL url = new java.net.URL(photoUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.connect();
            if (conn.getResponseCode() == 200) {
                return Image.getInstance(conn.getInputStream().readAllBytes());
            }
        } catch (Exception e) {
            log.debug("Student photo could not be loaded ({}): {}", photoUrl, e.getMessage());
        }
        return null;
    }

    private PdfPCell siLabelCell(String text) {
        // Italic label, bottom border only, no background — matches design image
        Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.5f, TEXT_MID);
        PdfPCell c = new PdfPCell(new Phrase(text, labelFont));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER_GRAY);
        c.setBorderWidth(0.5f);
        c.setFixedHeight(25.3f);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        c.setPaddingLeft(2);
        c.setPaddingRight(4);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBackgroundColor(PARCHMENT);
        return c;
    }

    private PdfPCell siValueCell(String text) {
        // Bold right-aligned value, bottom border only, no background
        PdfPCell c = new PdfPCell(new Phrase(text, F_SI_VALUE));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER_GRAY);
        c.setBorderWidth(0.5f);
        c.setFixedHeight(25.3f);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        c.setPaddingRight(2);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setBackgroundColor(PARCHMENT);
        return c;
    }

    private PdfPCell siSpacerCell(int rowspan) {
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(PARCHMENT);
        if (rowspan > 1) c.setRowspan(rowspan);
        return c;
    }

    // ── MARKS_TABLE ───────────────────────────────────────────────────────
    // Plain bordered table. Grade column: text-only colored. No row fills except totals.

    private void addMarksTable(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null || wr.getMarksTable() == null) return;

        WeightedGroupResultDTO.MarksTableDTO mt = wr.getMarksTable();
        int examCount = mt.getExamColumns().size();
        if (examCount == 0) return;
        int rowCount = mt.getSubjectRows() != null ? mt.getSubjectRows().size() : 0;
        boolean dense = rowCount > 6 || examCount > 2;
        boolean veryDense = rowCount > 9 || examCount > 3;
        float cellPad = veryDense ? 3.0f : dense ? 4.0f : 5.0f;
        float markFontSize = veryDense ? 7.2f : dense ? 8.0f : 8.6f;

        doc.add(centeredSectionTitle("M A R K S   T A B L E"));

        int extraCols = branding.showGradePoints ? 2 : 1;
        float[] colWidths = new float[examCount + 1 + extraCols];
        colWidths[0] = 3f;
        for (int i = 1; i <= examCount; i++) colWidths[i] = 1.4f;
        colWidths[examCount + 1] = 1.2f;
        if (branding.showGradePoints) colWidths[examCount + 2] = 1.0f;

        PdfPTable table = new PdfPTable(colWidths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(veryDense ? 3 : 5);

        // Header row — subtle grey fill, gold rules, widely-spaced labels.
        Color headerBg = new Color(238, 233, 223);
        Font thFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, veryDense ? 6.8f : 7.4f, TEXT_DARK);
        PdfPCell subjectTh = new PdfPCell(new Phrase("S U B J E C T", thFont));
        subjectTh.setBackgroundColor(headerBg);
        subjectTh.setBorderColor(GOLD);
        subjectTh.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        subjectTh.setBorderWidthTop(0.6f);
        subjectTh.setBorderWidthBottom(0.6f);
        subjectTh.setPadding(cellPad);
        table.addCell(subjectTh);
        for (WeightedGroupResultDTO.MarksTableDTO.ExamColumnDTO col : mt.getExamColumns()) {
            String label = col.getExamName().toUpperCase().replace(" ", "-");
            PdfPCell th = new PdfPCell(new Phrase(label, thFont));
            th.setBackgroundColor(headerBg);
            th.setHorizontalAlignment(Element.ALIGN_CENTER);
            th.setBorderColor(GOLD);
            th.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            th.setBorderWidthTop(0.6f);
            th.setBorderWidthBottom(0.6f);
            th.setPadding(cellPad);
            table.addCell(th);
        }
        PdfPCell gradeTh = new PdfPCell(new Phrase("G R A D E", thFont));
        gradeTh.setBackgroundColor(headerBg);
        gradeTh.setHorizontalAlignment(Element.ALIGN_RIGHT);
        gradeTh.setBorderColor(GOLD);
        gradeTh.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        gradeTh.setBorderWidthTop(0.6f);
        gradeTh.setBorderWidthBottom(0.6f);
        gradeTh.setPadding(cellPad);
        table.addCell(gradeTh);
        if (branding.showGradePoints) {
            PdfPCell gpTh = new PdfPCell(new Phrase("G P", thFont));
            gpTh.setBackgroundColor(headerBg);
            gpTh.setHorizontalAlignment(Element.ALIGN_RIGHT);
            gpTh.setBorderColor(GOLD);
            gpTh.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
            gpTh.setBorderWidthTop(0.6f);
            gpTh.setBorderWidthBottom(0.6f);
            gpTh.setPadding(cellPad);
            table.addCell(gpTh);
        }

        // Data rows — parchment background, bottom border only
        for (WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO row : mt.getSubjectRows()) {
            boolean failed = !GradingPolicy.passed(row.getWeightedPercentage());
            Font subjectFont = failed
                    ? FontFactory.getFont(FontFactory.TIMES_ITALIC, markFontSize, FAIL_COLOR)
                    : FontFactory.getFont(FontFactory.TIMES_ROMAN, markFontSize, TEXT_DARK);

            PdfPCell subCell = new PdfPCell(new Phrase(row.getSubjectName(), subjectFont));
            subCell.setBackgroundColor(PARCHMENT);
            subCell.setBorderColor(BORDER_GRAY);
            subCell.setBorder(Rectangle.BOTTOM);
            subCell.setPadding(cellPad);
            table.addCell(subCell);

            List<WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO> marks = row.getExamMarks();
            for (int i = 0; i < examCount; i++) {
                WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO mark =
                        (marks != null && i < marks.size()) ? marks.get(i) : null;
                Font markFont = failed
                        ? FontFactory.getFont(FontFactory.TIMES_BOLD, markFontSize, FAIL_COLOR)
                        : FontFactory.getFont(FontFactory.TIMES_ROMAN, markFontSize, TEXT_DARK);
                String txt = (mark == null || mark.getObtained() == null)
                        ? "Ab"
                        : DF0.format(mark.getObtained()) + " / " + DF0.format(mark.getMax());
                PdfPCell mc = new PdfPCell(new Phrase(txt, markFont));
                mc.setBackgroundColor(PARCHMENT);
                mc.setHorizontalAlignment(Element.ALIGN_CENTER);
                mc.setBorderColor(BORDER_GRAY);
                mc.setBorder(Rectangle.BOTTOM);
                mc.setPadding(cellPad);
                table.addCell(mc);
            }

            String subGrade = gradeFromPct(row.getWeightedPercentage(), data.getGradingSystem());
            Font gradeF = FontFactory.getFont(FontFactory.TIMES_ROMAN, markFontSize, TEXT_DARK);
            PdfPCell gc = new PdfPCell(new Phrase(subGrade, gradeF));
            gc.setBackgroundColor(PARCHMENT);
            gc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            gc.setBorderColor(BORDER_GRAY);
            gc.setBorder(Rectangle.BOTTOM);
            gc.setPadding(cellPad);
            table.addCell(gc);

            if (branding.showGradePoints) {
                double gp = cbseGradePoint(subGrade);
                PdfPCell gpCell = new PdfPCell(new Phrase(DF1.format(gp), F_BODY_BOLD));
                gpCell.setBackgroundColor(PARCHMENT);
                gpCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                gpCell.setBorderColor(BORDER_GRAY);
                gpCell.setBorder(Rectangle.BOTTOM);
                gpCell.setPadding(cellPad);
                table.addCell(gpCell);
            }
        }

        // TOTAL row — top border in dark green only, no background
        if (mt.getExamTotals() != null && !mt.getExamTotals().isEmpty()) {
            Font totalFont = FontFactory.getFont(FontFactory.TIMES_BOLD, markFontSize, DARK_GREEN);

            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", totalFont));
            totalLabel.setBackgroundColor(PARCHMENT);
            totalLabel.setBorderColor(GOLD);
            totalLabel.setBorder(Rectangle.TOP);
            totalLabel.setBorderWidthTop(0.8f);
            totalLabel.setPadding(cellPad);
            table.addCell(totalLabel);

            for (WeightedGroupResultDTO.MarksTableDTO.ExamTotalDTO total : mt.getExamTotals()) {
                PdfPCell tc = new PdfPCell(new Phrase(
                        DF0.format(total.getObtained()) + " / " + DF0.format(total.getMax()), totalFont));
                tc.setBackgroundColor(PARCHMENT);
                tc.setHorizontalAlignment(Element.ALIGN_CENTER);
                tc.setBorderColor(GOLD);
                tc.setBorder(Rectangle.TOP);
                tc.setBorderWidthTop(0.8f);
                tc.setPadding(cellPad);
                table.addCell(tc);
            }

            String overallGrade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
            PdfPCell og = new PdfPCell(new Phrase(overallGrade, FontFactory.getFont(FontFactory.TIMES_BOLD, markFontSize, DARK_GREEN)));
            og.setBackgroundColor(PARCHMENT);
            og.setHorizontalAlignment(Element.ALIGN_RIGHT);
            og.setBorderColor(GOLD);
            og.setBorder(Rectangle.TOP);
            og.setBorderWidthTop(0.8f);
            og.setPadding(cellPad);
            table.addCell(og);

            if (branding.showGradePoints) {
                double cgpVal = data.getCgpa() != null ? data.getCgpa() : cbseGradePoint(overallGrade);
                PdfPCell gpCell = new PdfPCell(new Phrase(DF1.format(cgpVal), totalFont));
                gpCell.setBackgroundColor(PARCHMENT);
                gpCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                gpCell.setBorderColor(GOLD);
                gpCell.setBorder(Rectangle.TOP);
                gpCell.setBorderWidthTop(0.8f);
                gpCell.setPadding(cellPad);
                table.addCell(gpCell);
            }
        }
        doc.add(table);

        // Grade scale legend — shown for CBSE and LETTER grading systems
        // The fixed Edunexify design intentionally omits the grading scale to
        // keep the A4 page clean and aligned with the approved visual reference.
    }

    /** Renders a compact one-line grade scale legend below the marks table. */
    private void addGradeLegend(Document doc, String gradingSystem,
                                 BrandingConfig branding) throws DocumentException {
        String[][] rows;
        if ("CBSE".equals(gradingSystem)) {
            rows = new String[][]{
                {"A1","91-100","Outstanding"}, {"A2","81-90","Excellent"},
                {"B1","71-80","Very Good"},    {"B2","61-70","Good"},
                {"C1","51-60","Satisfactory"}, {"C2","41-50","Average"},
                {"D", "33-40","Needs Impr."},  {"E", "0-32", "Fail"}
            };
        } else if ("LETTER".equals(gradingSystem)) {
            rows = new String[][]{
                {"A+","90-100","Outstanding"}, {"A","80-89","Excellent"},
                {"B+","70-79","Very Good"},    {"B","60-69","Good"},
                {"C+","50-59","Satisfactory"}, {"C","40-49","Average"},
                {"D", "33-39","Needs Impr."},  {"F","0-32", "Fail"}
            };
        } else {
            return; // PERCENTAGE — no letter grade legend needed
        }

        Font legendLabelFont = FontFactory.getFont(FontFactory.TIMES_BOLD,   6.5f, TEXT_MID);
        Font legendBodyFont  = FontFactory.getFont(FontFactory.TIMES_ROMAN,  6.5f, TEXT_MID);

        // Single-row table: "Grading Scale:" | A1 (91-100) Outstanding | A2 ... | ...
        float[] widths = new float[rows.length + 1];
        widths[0] = 1.4f;
        for (int i = 1; i <= rows.length; i++) widths[i] = 1f;

        PdfPTable legend = new PdfPTable(widths);
        legend.setWidthPercentage(100);
        legend.setSpacingAfter(4);

        PdfPCell title = new PdfPCell(new Phrase("Grading Scale:", legendLabelFont));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPaddingLeft(2);
        title.setVerticalAlignment(Element.ALIGN_MIDDLE);
        legend.addCell(title);

        for (String[] r : rows) {
            Phrase p = new Phrase();
            p.add(new Chunk(r[0] + " ", legendLabelFont));
            p.add(new Chunk("(" + r[1] + ") ", legendBodyFont));
            p.add(new Chunk(r[2], legendBodyFont));
            PdfPCell cell = new PdfPCell(p);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(2);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            legend.addCell(cell);
        }

        doc.add(legend);
    }

    // ── ASSESSMENT_SUMMARY ────────────────────────────────────────────────
    // Compact bordered summary table — no colored KPI blocks.
    // New layouts (WARM_ELEGANCE / NAVY_SCHOLAR): large-number KPI row.

    private void addAssessmentSummary(Document doc, ReportCardDataDTO data,
                                       BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(centeredSectionTitle("A S S E S S M E N T   S U M M A R Y"));

        boolean showCgpa = branding.showCgpa && data.getCgpa() != null;
        String pctStr  = DF1.format(wr.getWeightedPercentage()) + "%";
        String grade   = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
        String rankStr = wr.getRank() > 0 ? String.valueOf(wr.getRank()) : "\u2014";

        int cols = showCgpa ? 4 : 3;
        PdfPTable kpiTable = new PdfPTable(cols);
        kpiTable.setWidthPercentage(100);
        kpiTable.setSpacingAfter(4);

        Font bigFont   = FontFactory.getFont(FontFactory.TIMES_BOLD, 18, DARK_GREEN);
        Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 7.2f, TEXT_MID);

        String[] labels = showCgpa
                ? new String[]{"Overall", "Grade", "Class Rank", "CGPA"}
                : new String[]{"Overall", "Grade", "Class Rank"};
        String[] values = showCgpa
                ? new String[]{pctStr, grade, rankStr, DF1.format(data.getCgpa())}
                : new String[]{pctStr, grade, rankStr};

        for (int i = 0; i < cols; i++) {
            PdfPCell kpi = new PdfPCell();
            kpi.setBorder(Rectangle.NO_BORDER);  // no borders — matches Angular KPI style
            kpi.setBackgroundColor(PARCHMENT);
            kpi.setPaddingTop(3);
            kpi.setPaddingBottom(3);
            kpi.setHorizontalAlignment(Element.ALIGN_CENTER);

            Paragraph bigVal = new Paragraph(values[i], bigFont);
            bigVal.setAlignment(Element.ALIGN_CENTER);
            bigVal.setSpacingAfter(4);
            kpi.addElement(bigVal);

            Paragraph lbl = new Paragraph(labels[i], labelFont);
            lbl.setAlignment(Element.ALIGN_CENTER);
            kpi.addElement(lbl);

            kpiTable.addCell(kpi);
        }
        doc.add(kpiTable);
    }

    // ── ATTENDANCE ────────────────────────────────────────────────────────
    // Plain 4-column table: Working Days | Days Present | Days Absent | Attendance %
    // Mirrors Angular's 4-column <table> replacing the old KPI blocks.

    private void addAttendance(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        ReportCardDataDTO.AttendanceBlock att = data.getAttendance();
        doc.add(sectionTitleBar("ATTENDANCE", branding.primary));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        table.addCell(thCellCenter("Working Days", branding.primary));
        table.addCell(thCellCenter("Days Present", branding.primary));
        table.addCell(thCellCenter("Days Absent", branding.primary));
        table.addCell(thCellCenter("Attendance %", branding.primary));

        int absent = att.getWorkingDays() - att.getPresentDays();
        table.addCell(tdCellCenter(String.valueOf(att.getWorkingDays()), false));
        table.addCell(tdCellCenter(String.valueOf(att.getPresentDays()), false));
        table.addCell(tdCellCenter(String.valueOf(absent), false));

        PdfPCell pctCell = new PdfPCell(
            new Phrase(DF1.format(att.getPercentage()) + "%", F_BODY_BOLD));
        pctCell.setBorderColor(BORDER_GRAY);
        pctCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        pctCell.setPadding(6);
        table.addCell(pctCell);

        doc.add(table);
    }

    // ── ATTENDANCE + CO_SCHOLASTIC (side by side) ─────────────────────────

    private void addAttendanceAndCoScholastic(Document doc, ReportCardDataDTO data,
                                               ReportCardTemplateDTO.SectionDTO coSection,
                                               BrandingConfig branding) throws DocumentException {
        // Outer 2-column table: left = ATTENDANCE, right = CO_SCHOLASTIC
        PdfPTable outer = new PdfPTable(new float[]{1f, 0.04f, 1f});
        outer.setWidthPercentage(100);
        outer.setSpacingBefore(5);
        outer.setSpacingAfter(4);

        // ── LEFT: Attendance ──────────────────────────────────────────────
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.setPaddingRight(8);

        Font sectionTitleFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.8f, TEXT_DARK);
        Paragraph attTitle = new Paragraph("A T T E N D A N C E", sectionTitleFont);
        attTitle.setAlignment(Element.ALIGN_CENTER);
        attTitle.setSpacingAfter(6);
        leftCell.addElement(attTitle);

        if (data.getAttendance() != null) {
            ReportCardDataDTO.AttendanceBlock att = data.getAttendance();

            Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.2f, TEXT_MID);
            Font valueFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8.2f, TEXT_DARK);

            String[][] rows = {
                {"Working Days",  String.valueOf(att.getWorkingDays())},
                {"Days Present",  String.valueOf(att.getPresentDays())},
                {"Percentage",    DF1.format(att.getPercentage()) + "%"},
            };
            for (String[] row : rows) {
                PdfPTable rowTable = new PdfPTable(new float[]{2f, 1f});
                rowTable.setWidthPercentage(100);
                PdfPCell lbl = new PdfPCell(new Phrase(row[0], labelFont));
                lbl.setBorder(Rectangle.BOTTOM);
                lbl.setBorderColor(BORDER_GRAY);
                lbl.setPadding(3.5f);
                lbl.setBackgroundColor(PARCHMENT);
                rowTable.addCell(lbl);
                PdfPCell val = new PdfPCell(new Phrase(row[1], valueFont));
                val.setBorder(Rectangle.BOTTOM);
                val.setBorderColor(BORDER_GRAY);
                val.setPadding(3.5f);
                val.setHorizontalAlignment(Element.ALIGN_RIGHT);
                val.setBackgroundColor(PARCHMENT);
                rowTable.addCell(val);
                leftCell.addElement(rowTable);
            }
        }
        outer.addCell(leftCell);

        // ── DIVIDER ───────────────────────────────────────────────────────
        PdfPCell divider = new PdfPCell(new Phrase(" "));
        divider.setBorder(Rectangle.NO_BORDER);
        outer.addCell(divider);

        // ── RIGHT: Co-Scholastic ──────────────────────────────────────────
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setPaddingLeft(8);

        Paragraph coTitle = new Paragraph("C O - S C H O L A S T I C", sectionTitleFont);
        coTitle.setAlignment(Element.ALIGN_CENTER);
        coTitle.setSpacingAfter(6);
        rightCell.addElement(coTitle);

        List<ReportCardDataDTO.CoScholasticGrade> grades = data.getCoScholasticGrades();
        if (grades != null && !grades.isEmpty()) {
            Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.2f, TEXT_MID);
            Font valueFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8.2f, TEXT_DARK);
            for (ReportCardDataDTO.CoScholasticGrade g : grades) {
                PdfPTable rowTable = new PdfPTable(new float[]{2f, 1f});
                rowTable.setWidthPercentage(100);
                PdfPCell lbl = new PdfPCell(new Phrase(g.getActivity(), labelFont));
                lbl.setBorder(Rectangle.BOTTOM);
                lbl.setBorderColor(BORDER_GRAY);
                lbl.setPadding(3.5f);
                lbl.setBackgroundColor(PARCHMENT);
                rowTable.addCell(lbl);
                String gradeStr = (g.getGrade() != null && !g.getGrade().isBlank()) ? g.getGrade() : "—";
                PdfPCell val = new PdfPCell(new Phrase(gradeStr, valueFont));
                val.setBorder(Rectangle.BOTTOM);
                val.setBorderColor(BORDER_GRAY);
                val.setPadding(3.5f);
                val.setHorizontalAlignment(Element.ALIGN_RIGHT);
                val.setBackgroundColor(PARCHMENT);
                rowTable.addCell(val);
                rightCell.addElement(rowTable);
            }
        } else if (coSection != null) {
            // No grades saved — show blank rows from configJson activities
            Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.2f, TEXT_MID);
            List<String> activities = parseActivities(coSection.getConfigJson());
            for (String act : activities) {
                PdfPTable rowTable = new PdfPTable(new float[]{2f, 1f});
                rowTable.setWidthPercentage(100);
                PdfPCell lbl = new PdfPCell(new Phrase(act, labelFont));
                lbl.setBorder(Rectangle.BOTTOM);
                lbl.setBorderColor(BORDER_GRAY);
                lbl.setPadding(3.5f);
                lbl.setBackgroundColor(PARCHMENT);
                rowTable.addCell(lbl);
                PdfPCell val = new PdfPCell(new Phrase("—", labelFont));
                val.setBorder(Rectangle.BOTTOM);
                val.setBorderColor(BORDER_GRAY);
                val.setPadding(3.5f);
                val.setHorizontalAlignment(Element.ALIGN_RIGHT);
                val.setBackgroundColor(PARCHMENT);
                rowTable.addCell(val);
                rightCell.addElement(rowTable);
            }
        }
        outer.addCell(rightCell);

        doc.add(outer);
    }

    // ── CO_SCHOLASTIC ─────────────────────────────────────────────────────
    // Activity / Grade table — grade uses text-only primary color (no PURPLE_BG).

    private void addCoScholastic(Document doc, ReportCardDataDTO data,
                                  ReportCardTemplateDTO.SectionDTO section,
                                  BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("CO-SCHOLASTIC ACTIVITIES", branding.primary));

        List<ReportCardDataDTO.CoScholasticGrade> grades = data.getCoScholasticGrades();

        if (grades != null && !grades.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[]{3f, 1f});
            table.setWidthPercentage(60);
            table.setSpacingAfter(8);

            table.addCell(thCell("Activity", branding.primary));
            table.addCell(thCellCenter("Grade", branding.primary));

            for (ReportCardDataDTO.CoScholasticGrade g : grades) {
                table.addCell(tdCell(g.getActivity(), false));
                if (g.getGrade() != null && !g.getGrade().isBlank()) {
                    Font gradeFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, branding.primary);
                    PdfPCell gc = new PdfPCell(new Phrase(g.getGrade(), gradeFont));
                    gc.setBackgroundColor(WHITE);
                    gc.setHorizontalAlignment(Element.ALIGN_CENTER);
                    gc.setBorderColor(BORDER_GRAY);
                    gc.setPadding(5);
                    table.addCell(gc);
                } else {
                    table.addCell(tdCellCenter("—", true));
                }
            }
            doc.add(table);
        } else {
            List<String> activities  = parseActivities(section.getConfigJson());
            List<String> gradeScale  = parseGradeScale(section.getConfigJson());

            PdfPTable table = new PdfPTable(buildCoWidths(gradeScale.size()));
            table.setWidthPercentage(80);
            table.setSpacingAfter(8);

            table.addCell(thCell("Activity", branding.primary));
            for (String g : gradeScale) table.addCell(thCellCenter(g, branding.primary));
            table.addCell(thCell("Remarks", branding.primary));

            for (String act : activities) {
                table.addCell(tdCell(act, false));
                for (int i = 0; i < gradeScale.size(); i++) {
                    PdfPCell box = new PdfPCell();
                    box.setFixedHeight(18f);
                    box.setBorderColor(BORDER_GRAY);
                    box.setHorizontalAlignment(Element.ALIGN_CENTER);
                    table.addCell(box);
                }
                PdfPCell rem = new PdfPCell();
                rem.setFixedHeight(18f);
                rem.setBorderColor(BORDER_GRAY);
                table.addCell(rem);
            }
            doc.add(table);
        }
    }

    // ── REMARKS ───────────────────────────────────────────────────────────

    private void addRemarks(Document doc, String title, String text,
                             BrandingConfig branding) throws DocumentException {
        // Inline style: "CLASS TEACHER   italic remark text" on one line
        String label = title.toUpperCase().contains("TEACHER") ? "CLASS TEACHER" : "PRINCIPAL";
        Font labelFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 7f, TEXT_MID);
        Font remarkFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.2f, TEXT_DARK);

        PdfPTable row = new PdfPTable(new float[]{0.85f, 4.55f});
        row.setWidthPercentage(100);
        row.setSpacingBefore(4);
        row.setSpacingAfter(2);

        PdfPCell lbl = new PdfPCell(new Phrase(label, labelFont));
        lbl.setBorder(Rectangle.NO_BORDER);
        lbl.setBackgroundColor(PARCHMENT);
        lbl.setPaddingTop(3);
        row.addCell(lbl);

        String remarkText = (text != null && !text.isBlank()) ? text : "";
        PdfPCell rem = new PdfPCell(new Phrase(remarkText, remarkFont));
        rem.setBorder(Rectangle.NO_BORDER);
        rem.setBackgroundColor(PARCHMENT);
        row.addCell(rem);

        doc.add(row);
    }

    // ── PROMOTION_STATUS ──────────────────────────────────────────────────
    // Centered stamp-style badge — mirrors Angular's rc-result-stamp.
    // New layouts: circular/oval badge with "PASSED WITH [GRADE] DISTINCTION".

    private void addPromotionStatus(Document doc, PdfWriter writer, ReportCardDataDTO data,
                                     BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        if (writer != null) {
            PdfContentByte cb = writer.getDirectContent();
            float pageLeft = doc.left();
            float pageRight = doc.right();
            float pageWidth = pageRight - pageLeft;
            float leftCenter = pageLeft + pageWidth * 0.18f;
            float rightCenter = pageRight - pageWidth * 0.18f;
            float lineY = doc.bottom() + 78f;
            float labelY = doc.bottom() + 60f;
            float halfLine = 70f;

            cb.saveState();
            cb.setColorStroke(TEXT_DARK);
            cb.setLineWidth(0.8f);
            cb.moveTo(leftCenter - halfLine, lineY);
            cb.lineTo(leftCenter + halfLine, lineY);
            cb.moveTo(rightCenter - halfLine, lineY);
            cb.lineTo(rightCenter + halfLine, lineY);
            cb.stroke();
            cb.restoreState();

            try {
                BaseFont regular = BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
                cb.beginText();
                cb.setColorFill(TEXT_MID);
                cb.setFontAndSize(regular, 7.5f);
                cb.showTextAligned(Element.ALIGN_CENTER, "C L A S S   T E A C H E R", leftCenter, labelY, 0);
                cb.showTextAligned(Element.ALIGN_CENTER, "P R I N C I P A L", rightCenter, labelY, 0);
                cb.endText();
            } catch (Exception e) {
                log.debug("Could not draw fixed signature labels: {}", e.getMessage());
            }
            return;
        }

        // 3-column layout: [CLASS TEACHER sig] | [blank center] | [PRINCIPAL sig]
        PdfPTable outer = new PdfPTable(new float[]{2f, 2f, 2f});
        outer.setWidthPercentage(100);
        outer.setSpacingBefore(3);
        outer.setSpacingAfter(3);

        // ── Left: Class Teacher signature line ────────────────────────────
        Font sigLabelFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.5f, TEXT_MID);
        PdfPCell leftSig = new PdfPCell();
        leftSig.setBorder(Rectangle.NO_BORDER);
        leftSig.setBackgroundColor(PARCHMENT);
        leftSig.setPaddingBottom(4);
        leftSig.setHorizontalAlignment(Element.ALIGN_CENTER);

        PdfPTable leftLine = new PdfPTable(1);
        leftLine.setWidthPercentage(80);
        PdfPCell ll = new PdfPCell(new Phrase(" "));
        ll.setBorder(Rectangle.TOP);
        ll.setBorderColor(TEXT_DARK);
        ll.setBorderWidthTop(0.8f);
        ll.setPaddingTop(2);
        leftLine.addCell(ll);
        leftSig.addElement(leftLine);
        Paragraph ctLabel = new Paragraph("C L A S S   T E A C H E R", sigLabelFont);
        ctLabel.setAlignment(Element.ALIGN_CENTER);
        leftSig.addElement(ctLabel);
        outer.addCell(leftSig);

        // ── Center: intentionally blank. Result badge text is not shown in the fixed design.
        PdfPCell badgeCell = new PdfPCell();
        badgeCell.setBorder(Rectangle.NO_BORDER);
        badgeCell.setBackgroundColor(PARCHMENT);
        badgeCell.setPaddingTop(0);
        badgeCell.setPaddingBottom(0);
        badgeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        outer.addCell(badgeCell);

        // ── Right: Principal signature line ───────────────────────────────
        PdfPCell rightSig = new PdfPCell();
        rightSig.setBorder(Rectangle.NO_BORDER);
        rightSig.setBackgroundColor(PARCHMENT);
        rightSig.setPaddingBottom(4);
        rightSig.setHorizontalAlignment(Element.ALIGN_CENTER);

        PdfPTable rightLine = new PdfPTable(1);
        rightLine.setWidthPercentage(80);
        PdfPCell rl = new PdfPCell(new Phrase(" "));
        rl.setBorder(Rectangle.TOP);
        rl.setBorderColor(TEXT_DARK);
        rl.setBorderWidthTop(0.8f);
        rl.setPaddingTop(2);
        rightLine.addCell(rl);
        rightSig.addElement(rightLine);
        Paragraph prLabel = new Paragraph("P R I N C I P A L", sigLabelFont);
        prLabel.setAlignment(Element.ALIGN_CENTER);
        rightSig.addElement(prLabel);
        outer.addCell(rightSig);

        doc.add(outer);
    }

    @SuppressWarnings("unused")
    private Image createResultBadgeImage(PdfWriter writer, String grade, String pctStr, boolean pass, Color resultColor) {
        if (writer == null) return null;
        try {
            PdfTemplate tpl = writer.getDirectContent().createTemplate(86, 86);
            tpl.saveState();
            tpl.setColorStroke(resultColor);
            tpl.setLineWidth(1.3f);
            tpl.circle(43, 43, 40);
            tpl.stroke();
            tpl.setColorStroke(DARK_GREEN);
            tpl.setLineWidth(0.8f);
            tpl.circle(43, 43, 34);
            tpl.stroke();
            tpl.restoreState();

            BaseFont bold = BaseFont.createFont(BaseFont.TIMES_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont italic = BaseFont.createFont(BaseFont.TIMES_ITALIC, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            tpl.beginText();
            tpl.setColorFill(resultColor);
            tpl.setFontAndSize(bold, 5.8f);
            tpl.showTextAligned(Element.ALIGN_CENTER, pass ? "P A S S E D   W I T H" : "R E S U L T", 43, 62, 0);
            tpl.setFontAndSize(bold, 19f);
            tpl.showTextAligned(Element.ALIGN_CENTER, grade, 43, 40, 0);
            tpl.setFontAndSize(bold, 5.8f);
            tpl.showTextAligned(Element.ALIGN_CENTER, pass ? "D I S T I N C T I O N" : "N E E D S   R E V I E W", 43, 27, 0);
            tpl.setColorFill(TEXT_MID);
            tpl.setFontAndSize(italic, 7f);
            tpl.showTextAligned(Element.ALIGN_CENTER, pctStr, 43, 15, 0);
            tpl.endText();

            return Image.getInstance(tpl);
        } catch (Exception e) {
            log.debug("Could not draw circular result badge: {}", e.getMessage());
            return null;
        }
    }

    private void addBottomBalanceSpacer(Document doc, PdfWriter writer, ReportCardDataDTO data) throws DocumentException {
        if (writer == null) return;
        int marksRows = 0;
        int examCols = 0;
        if (data.getWeightedResult() != null && data.getWeightedResult().getMarksTable() != null) {
            WeightedGroupResultDTO.MarksTableDTO mt = data.getWeightedResult().getMarksTable();
            marksRows = mt.getSubjectRows() != null ? mt.getSubjectRows().size() : 0;
            examCols = mt.getExamColumns() != null ? mt.getExamColumns().size() : 0;
        }
        boolean dense = marksRows > 6 || examCols > 2;
        if (dense) return;

        float y = writer.getVerticalPosition(false);
        float targetBeforeSignatures = 205f;
        if (y <= targetBeforeSignatures) return;

        float spacerHeight = Math.min(42f, y - targetBeforeSignatures);
        PdfPTable spacer = new PdfPTable(1);
        spacer.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell(new Phrase(" "));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(spacerHeight);
        cell.setBackgroundColor(PARCHMENT);
        spacer.addCell(cell);
        doc.add(spacer);
    }

    // ── SIGNATURES ────────────────────────────────────────────────────────

    private void addSignatures(Document doc, PdfWriter writer, BrandingConfig branding) throws DocumentException {
        // Note: CLASS TEACHER and PRINCIPAL signature lines are rendered inside addPromotionStatus.
        // This section only renders the footer seal line + branding.

        // "ISSUED UNDER THE SEAL OF THE SCHOOL · 2026-2027" — small caps, gray, centered
        Font sealFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7, TEXT_MID);
        String sealLine = "ISSUED UNDER THE SEAL OF THE SCHOOL";
        if (branding.session != null && !branding.session.isBlank()) {
            sealLine += "  \u00b7  " + branding.session;
        }

        // "Powered by Edunexify" — gold, centered
        Font footerFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 6.5f, GOLD);
        if (writer != null) {
            float centerX = (doc.left() + doc.right()) / 2f;
            PdfContentByte cb = writer.getDirectContent();
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase(sealLine, sealFont), centerX, doc.bottom() + 17f, 0);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Powered by Edunexify", footerFont), centerX, doc.bottom() + 8f, 0);
            return;
        }

        Paragraph sealPara = new Paragraph(sealLine, sealFont);
        sealPara.setAlignment(Element.ALIGN_CENTER);
        sealPara.setSpacingBefore(2);
        sealPara.setSpacingAfter(2);
        doc.add(sealPara);

        Paragraph footer = new Paragraph("Powered by Edunexify", footerFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(2);
        doc.add(footer);
    }

    /** Short, non-sensitive reference printed under the QR (the first block of the token). */
    static String verificationReference(String token) {
        if (token == null || token.isBlank()) return null;
        int dash = token.indexOf('-');
        return (dash > 0 ? token.substring(0, dash) : token.substring(0, Math.min(8, token.length()))).toUpperCase();
    }

    private static final String DEFAULT_FRONTEND_URL = "https://edunexify.co.in";

    /**
     * Public verification URL encoded in the QR — the existing /verify-rc page and API. A missing
     * or unresolved frontend.url (e.g. a literal "${FRONTEND_URL}" when the variable is unset)
     * falls back to the production site so a printed QR is never a dead link.
     */
    String verificationUrl(String token) {
        return frontendBase() + "/verify-rc?token="
                + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The web app's base URL (no trailing slash), with the same safe fallback as the QR link. */
    String frontendBase() {
        String base = frontendUrl != null ? frontendUrl.trim() : "";
        if (!base.startsWith("https://") && !base.startsWith("http://")) {
            log.warn("frontend.url is not a usable URL ({}); report-card links use {}", base, DEFAULT_FRONTEND_URL);
            base = DEFAULT_FRONTEND_URL;
        }
        return base.replaceAll("/+$", "");
    }

    /**
     * QR code resolving to the public verification page, drawn in the blank centre between the
     * two signature lines so it never pushes content onto a second page. The QR holds only the
     * opaque token.
     *
     * Eligibility: only a PUBLISHED TEMPLATE report card has a verification token (the
     * report_card_publication row), so only it carries a QR. A results-based card (no template —
     * what Class Results / My Results open) is not a publication and has no token, and an
     * unpublished template card has none yet: those render without a QR by design. Whenever a
     * token IS present the verification is always printed — if the QR image itself cannot be
     * built, the label and full verification link are printed instead and the failure is logged.
     */
    private void addQrVerification(Document doc, PdfWriter writer, String token) {
        if (writer == null || token == null || token.isBlank()) return;   // not eligible: no token
        byte[] qrBytes = generateQrBytes(verificationUrl(token), 180);
        if (qrBytes == null) {
            log.error("QR image could not be built for verification token ref {} — printing the link instead",
                    verificationReference(token));
            printVerificationLink(doc, writer, token);
            return;
        }
        try {
            float size = 46f;
            float centerX = (doc.left() + doc.right()) / 2f;
            float bottom = doc.bottom() + 36f;
            Image qrImage = Image.getInstance(qrBytes);
            qrImage.scaleAbsolute(size, size);
            qrImage.setAbsolutePosition(centerX - size / 2f, bottom);
            PdfContentByte cb = writer.getDirectContent();
            cb.addImage(qrImage);
            Font labelFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 6.2f, TEXT_MID);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Verify this report card  \u00b7  Ref " + verificationReference(token), labelFont),
                    centerX, bottom - 8f, 0);
        } catch (Exception e) {
            log.error("Failed to embed QR code in PDF (ref {}): {} — printing the link instead",
                    verificationReference(token), e.getMessage());
            printVerificationLink(doc, writer, token);
        }
    }

    /** Text fallback so a card with a token is never left without a way to verify it. */
    private void printVerificationLink(Document doc, PdfWriter writer, String token) {
        float centerX = (doc.left() + doc.right()) / 2f;
        Font labelFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 6.2f, TEXT_MID);
        PdfContentByte cb = writer.getDirectContent();
        ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                new Phrase("Verify this report card  \u00b7  Ref " + verificationReference(token), labelFont),
                centerX, doc.bottom() + 36f, 0);
        ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(verificationUrl(token), labelFont),
                centerX, doc.bottom() + 28f, 0);
    }

    private byte[] generateQrBytes(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new java.util.EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                      com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage binary = MatrixToImageWriter.toBufferedImage(matrix);
            // 8-bit grayscale, so the PDF stores the QR Flate-encoded — a 1-bit PNG becomes a
            // CCITT-fax image, which not every PDF viewer/extractor handles.
            BufferedImage image = new BufferedImage(binary.getWidth(), binary.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            java.awt.Graphics2D g = image.createGraphics();
            try { g.drawImage(binary, 0, 0, null); } finally { g.dispose(); }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Grade helpers ─────────────────────────────────────────────────────

    /** Delegates to the single grading rule (GradingPolicy). */
    String gradeFromPct(double pct, String gradingSystem) {
        return GradingPolicy.grade(pct, gradingSystem);
    }

    private double cbseGradePoint(String grade) {
        return GradingPolicy.cbseGradePoint(grade);
    }

    private Font gradeFont(double pct) {
        if (pct >= 75) return F_GRADE_A;
        if (pct >= 55) return F_GRADE_B;
        if (pct >= 33) return F_GRADE_C;
        return F_GRADE_F;
    }

    // ── Cell / table builders ─────────────────────────────────────────────

    /**
     * Elegant section separator — 3pt left accent bar in primary color, light tinted
     * background, title text in primary color. Replaces the old solid-filled blue bar.
     * Mirrors Angular's .rc-section-title with border-left + tinted background.
     */
    private PdfPTable sectionTitleBar(String title, Color color) throws DocumentException {
        // Two-column approach: narrow colored left bar | label text
        PdfPTable t = new PdfPTable(new float[]{0.025f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);
        t.setSpacingAfter(3);

        // Left accent bar — solid primary color, no border
        PdfPCell accent = new PdfPCell(new Phrase(" "));
        accent.setBackgroundColor(color);
        accent.setBorder(Rectangle.NO_BORDER);
        t.addCell(accent);

        // Very light tint: 8% primary + 92% white
        Color lightTint = new Color(
            Math.min(255, (int)(color.getRed()   * 0.08 + 247)),
            Math.min(255, (int)(color.getGreen() * 0.08 + 247)),
            Math.min(255, (int)(color.getBlue()  * 0.08 + 247))
        );
        Font titleFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 8, color);
        PdfPCell label = new PdfPCell(new Phrase(title, titleFont));
        label.setBackgroundColor(lightTint);
        label.setBorderColor(new Color(210, 210, 210));
        label.setPaddingLeft(8);
        label.setPaddingTop(5);
        label.setPaddingBottom(5);
        t.addCell(label);

        return t;
    }

    /** Plain centered spaced-uppercase section title — matches the Angular design. */
    private Paragraph centeredSectionTitle(String text) {
        Font f = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8.1f, TEXT_DARK);
        Paragraph p = new Paragraph(text, f);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingBefore(6);
        p.setSpacingAfter(4);
        return p;
    }

    /** Table header cell: solid primary background, white Times-Bold, left-aligned. */
    private PdfPCell thCell(String text, Color color) {
        PdfPCell c = new PdfPCell(new Phrase(text, F_TH));
        c.setBackgroundColor(color);
        c.setBorderColor(color);
        c.setPadding(6);
        return c;
    }

    /** Table header cell: solid primary background, white Times-Bold, center-aligned. */
    private PdfPCell thCellCenter(String text, Color color) {
        PdfPCell c = thCell(text, color);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    /** Data cell: white bg, gray border, left-aligned. Muted = smaller gray font. */
    private PdfPCell tdCell(String text, boolean muted) {
        PdfPCell c = new PdfPCell(new Phrase(text, muted ? F_BODY_SM : F_BODY));
        c.setBackgroundColor(WHITE);
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(5);
        return c;
    }

    /** Data cell: white bg, gray border, center-aligned. */
    private PdfPCell tdCellCenter(String text, boolean muted) {
        PdfPCell c = tdCell(text, muted);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    /** Grade cell: white bg, colored text only (no background fill), center-aligned. */
    private PdfPCell tdGradeCenter(String grade, double pct) {
        PdfPCell c = new PdfPCell(new Phrase(grade, gradeFont(pct)));
        c.setBackgroundColor(WHITE);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(5);
        return c;
    }

    private PdfPCell emptyCell() {
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    // ── Config JSON helpers ───────────────────────────────────────────────

    private List<String> parseActivities(String configJson) {
        return parseStringList(configJson, "activities",
                List.of("Discipline", "Sports", "Co-Curricular"));
    }

    private List<String> parseGradeScale(String configJson) {
        return parseStringList(configJson, "gradeScale", List.of("A", "B", "C", "D"));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String json, String key, List<String> defaults) {
        if (json == null || json.isBlank()) return defaults;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = om.readValue(json, Map.class);
            Object val = map.get(key);
            if (val instanceof List) return (List<String>) val;
        } catch (Exception ignored) {}
        return defaults;
    }

    private float[] buildCoWidths(int gradeCount) {
        float[] w = new float[gradeCount + 2];
        w[0] = 3f;
        for (int i = 1; i <= gradeCount; i++) w[i] = 0.6f;
        w[gradeCount + 1] = 2f;
        return w;
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    private List<ReportCardTemplateDTO.SectionDTO> getSortedEnabledSections(
            ReportCardTemplateDTO template) {
        if (template == null || template.getSections() == null) return List.of();
        return template.getSections().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .sorted(Comparator.comparingInt(s -> s.getDisplayOrder() != null ? s.getDisplayOrder() : 0))
                .toList();
    }

    private String safe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String spacedTitle(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                out.append("   ");
            } else {
                out.append(ch);
                if (i < value.length() - 1 && !Character.isWhitespace(value.charAt(i + 1))) {
                    out.append(' ');
                }
            }
        }
        return out.toString();
    }

    private String buildContact(ReportCardDataDTO data) {
        List<String> parts = new ArrayList<>();
        if (data.getSchoolPhone() != null && !data.getSchoolPhone().isBlank())
            parts.add(data.getSchoolPhone());
        if (data.getSchoolEmail() != null && !data.getSchoolEmail().isBlank())
            parts.add(data.getSchoolEmail());
        return String.join("  |  ", parts);
    }
}
