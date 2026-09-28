package com.indraacademy.ias_management.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Report Card V2 renderer: a Thymeleaf XHTML template (templates/report-card-v2/report-card.html)
 * laid out by OpenHTMLtoPDF (PDFBox 3) with embedded Noto fonts.
 *
 * <ul>
 *   <li>Devanagari is shaped by PDFBox/fontbox (GSUB). The font stack lists Noto Sans Devanagari
 *       FIRST: with Latin first, text falls back per character and word widths are measured
 *       unshaped, leaving visible gaps after conjuncts (renderer spike, Phase 1).</li>
 *   <li>Every call builds its own renderer and output; the template engine and font bytes are
 *       shared read-only, so concurrent rendering is safe.</li>
 *   <li>Remote images (logo, photo, header) are fetched here with short timeouts and embedded as
 *       data URIs; a failed fetch simply omits the image — the card is never blocked by it.</li>
 * </ul>
 */
@Service
public class OpenHtmlToPdfReportCardRenderer implements ReportCardRenderer {

    private static final Logger log = LoggerFactory.getLogger(OpenHtmlToPdfReportCardRenderer.class);
    private static final String FONT_DIR = "fonts/noto/";
    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    private final TemplateEngine engine;
    private final Map<String, byte[]> fonts = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public OpenHtmlToPdfReportCardRenderer() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/report-card-v2/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);   // well-formed XHTML for the PDF layout engine
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    @Override
    public byte[] render(ReportCardV2ViewModel model) {
        Context ctx = new Context();
        ctx.setVariable("vm", model);
        ctx.setVariable("logo", embed(model.school().logoUrl()));
        ctx.setVariable("headerImage", embed(model.school().headerImageUrl()));
        ctx.setVariable("photo", model.flags().photo() ? embed(model.student().photoUrl()) : null);
        ctx.setVariable("watermarkLogo", model.watermarkLogo() != null ? faded(embed(model.school().logoUrl())) : null);
        String html = engine.process("report-card", ctx);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            font(builder, "NotoSansDevanagari-Regular.ttf", "Noto Sans Devanagari", 400);
            font(builder, "NotoSansDevanagari-Bold.ttf", "Noto Sans Devanagari", 700);
            font(builder, "NotoSans-Regular.ttf", "Noto Sans", 400);
            font(builder, "NotoSans-Bold.ttf", "Noto Sans", 700);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not generate the report card PDF: " + e.getMessage(), e);
        }
    }

    /** The rendered XHTML (for tests / debugging). */
    String html(ReportCardV2ViewModel model) {
        Context ctx = new Context();
        ctx.setVariable("vm", model);
        return engine.process("report-card", ctx);
    }

    private void font(PdfRendererBuilder builder, String file, String family, int weight) {
        byte[] bytes = fonts.computeIfAbsent(file, f -> {
            try (InputStream in = new ClassPathResource(FONT_DIR + f).getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Report card font missing: " + f, e);
            }
        });
        builder.useFont(() -> new ByteArrayInputStream(bytes), family, weight, PdfRendererBuilder.FontStyle.NORMAL, true);
    }

    /**
     * A watermark version of an image: the same picture at ~6% alpha on a transparent background
     * (PNG), so it reads as faint branding and never hides marks or text drawn with it.
     */
    String faded(String dataUri) {
        if (dataUri == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',') + 1));
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return null;
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(src.getWidth(), src.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            try {
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.06f));
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception e) {
            log.warn("Report card watermark could not be prepared: {}", e.getMessage());
            return null;
        }
    }

    /** Fetches an http(s) image and returns it as a data URI; null if absent or unreachable. */
    String embed(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.startsWith("data:image/")) return url;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null;
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = res.body();
            if (res.statusCode() != 200 || body == null || body.length == 0 || body.length > MAX_IMAGE_BYTES) return null;
            String type = res.headers().firstValue("Content-Type").orElse("image/png").split(";")[0].trim();
            if (!type.startsWith("image/")) return null;
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(body);
        } catch (Exception e) {
            log.warn("Report card image could not be loaded ({}): {}", url.replaceAll("\\?.*$", ""), e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
