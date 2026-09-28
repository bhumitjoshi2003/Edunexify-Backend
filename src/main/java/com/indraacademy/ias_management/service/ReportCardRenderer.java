package com.indraacademy.ias_management.service;

/**
 * Report Card V2: turns the canonical view model into the one report-card document (PDF) used
 * for Web and Android preview, download, print and — in later phases — email, bulk and publishing.
 * The only implementation is {@link OpenHtmlToPdfReportCardRenderer}; a Chromium-based one could
 * replace it behind this interface without touching anything else.
 */
public interface ReportCardRenderer {
    byte[] render(ReportCardV2ViewModel model);
}
