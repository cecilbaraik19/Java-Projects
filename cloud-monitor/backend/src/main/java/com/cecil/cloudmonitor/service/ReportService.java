package com.cecil.cloudmonitor.service;

import com.cecil.cloudmonitor.dto.CostReport;
import com.cecil.cloudmonitor.dto.OverviewDto;
import com.cecil.cloudmonitor.model.Alert;
import com.cecil.cloudmonitor.model.CloudResource;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Builds the downloadable PDF report and CSV exports. */
@Service
public class ReportService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    // Colours (same palette as the web app)
    private static final Color INK = new Color(11, 11, 11);
    private static final Color MUTED = new Color(110, 109, 104);
    private static final Color ACCENT = new Color(42, 120, 214);
    private static final Color HEADER_BG = new Color(240, 239, 236);
    private static final Color BORDER = new Color(225, 224, 217);
    private static final Color GOOD = new Color(0, 120, 0);
    private static final Color WARN = new Color(176, 120, 0);
    private static final Color CRIT = new Color(208, 59, 59);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, INK);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, INK);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9, INK);
    private static final Font BODY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
    private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, MUTED);
    private static final Font KPI_VALUE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK);

    private final MonitoringService monitoring;
    private final CostService costs;
    private final AlertService alerts;

    public ReportService(MonitoringService monitoring, CostService costs, AlertService alerts) {
        this.monitoring = monitoring;
        this.costs = costs;
        this.alerts = alerts;
    }

    // ================================================================== PDF

    public byte[] summaryPdf(String generatedBy) {
        OverviewDto ov = monitoring.overview();
        CostReport cost = costs.report();
        List<CloudResource> resources = monitoring.all();
        List<Alert> recentAlerts = alerts.recent(20);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 40, 40, 44, 50);
        try {
            write(doc, out, generatedBy, ov, cost, resources, recentAlerts);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build the PDF report", e);
        }
        return out.toByteArray();
    }

    private void write(Document doc, ByteArrayOutputStream out, String generatedBy, OverviewDto ov, CostReport cost,
                       List<CloudResource> resources, List<Alert> recentAlerts) throws DocumentException {
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        writer.setPageEvent(new Footer());
        doc.addTitle("CloudPulse infrastructure report");
        doc.addAuthor(generatedBy);
        doc.open();

        // ---- title
        Paragraph brand = new Paragraph("CloudPulse", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, ACCENT));
        doc.add(brand);
        doc.add(new Paragraph("Infrastructure report", TITLE));
        Paragraph meta = new Paragraph("Generated " + WHEN.format(Instant.now()) + " by " + generatedBy, SMALL);
        meta.setSpacingAfter(14);
        doc.add(meta);

        // ---- key numbers
        PdfPTable kpis = new PdfPTable(4);
        kpis.setWidthPercentage(100);
        kpis.setSpacingAfter(16);
        kpis.addCell(kpi("Resources", String.valueOf(ov.totalResources()),
                ov.running() + " ok · " + ov.warning() + " warn · " + ov.critical() + " crit · " + ov.stopped() + " off"));
        kpis.addCell(kpi("Avg CPU / memory", pct(ov.avgCpu()) + " / " + pct(ov.avgMemory()), "Across running resources"));
        kpis.addCell(kpi("Projected monthly cost", money(cost.projectedMonthly()),
                money(cost.hourlyBurn()) + " per hour"));
        kpis.addCell(kpi("Active alerts", String.valueOf(ov.activeAlerts()), "Not yet acknowledged"));
        doc.add(kpis);

        // ---- costs
        section(doc, "Costs");
        PdfPTable budget = table(new float[]{2.2f, 1.4f});
        budget.setWidthPercentage(60);
        budget.setHorizontalAlignment(Element.ALIGN_LEFT);
        row(budget, "Monthly budget", money(cost.monthlyBudget()));
        row(budget, "Projected this month", money(cost.projectedMonthly()));
        Font used = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9,
                cost.budgetUsedPct() >= 100 ? CRIT : cost.budgetUsedPct() >= 85 ? WARN : GOOD);
        budget.addCell(cell("Budget used", BODY));
        budget.addCell(cell(String.format(Locale.ENGLISH, "%.1f%%", cost.budgetUsedPct()), used, Element.ALIGN_RIGHT));
        row(budget, "Spent in last 24 hours", money(cost.last24h()));
        row(budget, "Possible savings per month", money(cost.potentialSavings()));
        budget.setSpacingAfter(10);
        doc.add(budget);

        PdfPTable providers = table(new float[]{2f, 1f, 1.4f, 1f});
        header(providers, "Provider", "Resources", "Per month", "Share");
        for (CostReport.Slice s : cost.byProvider()) {
            providers.addCell(cell(s.key(), BODY));
            providers.addCell(cell(String.valueOf(s.count()), BODY, Element.ALIGN_RIGHT));
            providers.addCell(cell(money(s.monthly()), BODY, Element.ALIGN_RIGHT));
            double share = cost.projectedMonthly() > 0 ? s.monthly() / cost.projectedMonthly() * 100 : 0;
            providers.addCell(cell(String.format(Locale.ENGLISH, "%.0f%%", share), BODY, Element.ALIGN_RIGHT));
        }
        providers.setSpacingAfter(10);
        doc.add(providers);

        if (!cost.recommendations().isEmpty()) {
            doc.add(new Paragraph("Ways to save", BODY_BOLD));
            com.lowagie.text.List tips = new com.lowagie.text.List(false, 10);
            for (CostReport.Recommendation r : cost.recommendations()) {
                ListItem li = new ListItem(r.title() + " — save about " + money(r.monthlySaving()) + "/month. " + r.detail(), BODY);
                li.setSpacingAfter(3);
                tips.add(li);
            }
            doc.add(tips);
        }

        // ---- resources
        section(doc, "Resources");
        PdfPTable res = table(new float[]{2.6f, 1.4f, 1f, 1.1f, 0.8f, 0.9f, 0.8f, 1.2f});
        header(res, "Name", "Type", "Provider", "Status", "CPU", "Memory", "Disk", "Per month");
        for (CloudResource r : resources) {
            res.addCell(cell(r.getName(), BODY_BOLD));
            res.addCell(cell(r.getType().name(), BODY));
            res.addCell(cell(r.getProvider().name(), BODY));
            res.addCell(cell(r.getStatus().name(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, statusColor(r.getStatus().name()))));
            res.addCell(cell(r.isStopped() ? "-" : pct(r.getCpu()), BODY, Element.ALIGN_RIGHT));
            res.addCell(cell(r.isStopped() ? "-" : pct(r.getMemory()), BODY, Element.ALIGN_RIGHT));
            res.addCell(cell(pct(r.getDisk()), BODY, Element.ALIGN_RIGHT));
            res.addCell(cell(r.isStopped() ? "Not billed" : money(r.getHourlyCost() * CostService.HOURS_PER_MONTH), BODY, Element.ALIGN_RIGHT));
        }
        doc.add(res);

        // ---- alerts
        section(doc, "Recent alerts");
        if (recentAlerts.isEmpty()) {
            doc.add(new Paragraph("No alerts.", BODY));
        } else {
            PdfPTable al = table(new float[]{1.5f, 1f, 4.5f, 1.3f});
            header(al, "Time", "Severity", "Message", "Acknowledged");
            for (Alert a : recentAlerts) {
                al.addCell(cell(a.getCreatedAt() == null ? "-" : WHEN.format(a.getCreatedAt()), BODY));
                al.addCell(cell(a.getSeverity().name(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, statusColor(a.getSeverity().name()))));
                al.addCell(cell(a.getMessage(), BODY));
                al.addCell(cell(a.isAcknowledged() ? "Yes" + (a.getAcknowledgedBy() != null ? " (" + a.getAcknowledgedBy() + ")" : "") : "No", BODY));
            }
            doc.add(al);
        }

        doc.close();
    }

    private static void section(Document doc, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(12);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private static PdfPCell kpi(String label, String value, String sub) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(BORDER);
        c.setPadding(8);
        c.addElement(new Paragraph(label, SMALL));
        c.addElement(new Paragraph(value, KPI_VALUE));
        c.addElement(new Paragraph(sub, SMALL));
        return c;
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        return t;
    }

    private static void header(PdfPTable t, String... titles) {
        for (String h : titles) {
            PdfPCell c = cell(h.toUpperCase(Locale.ENGLISH), TH);
            c.setBackgroundColor(HEADER_BG);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, String label, String value) {
        t.addCell(cell(label, BODY));
        t.addCell(cell(value, BODY_BOLD, Element.ALIGN_RIGHT));
    }

    private static PdfPCell cell(String text, Font font) {
        return cell(text, font, Element.ALIGN_LEFT);
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBorderColor(BORDER);
        c.setPaddingTop(4);
        c.setPaddingBottom(5);
        c.setPaddingLeft(5);
        c.setPaddingRight(5);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static Color statusColor(String s) {
        return switch (s) {
            case "RUNNING", "INFO" -> GOOD;
            case "WARNING" -> WARN;
            case "CRITICAL" -> CRIT;
            default -> MUTED;
        };
    }

    /** "Page X" at the bottom of every page. */
    private static class Footer extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                    new Phrase("CloudPulse · page " + writer.getPageNumber(), SMALL),
                    (document.left() + document.right()) / 2, document.bottom() - 24, 0);
        }
    }

    // ================================================================== CSV

    public byte[] resourcesCsv() {
        StringBuilder sb = new StringBuilder();
        line(sb, "id", "name", "type", "provider", "region", "instance_type", "ip_address", "status",
                "cpu_pct", "memory_pct", "disk_used_pct", "network_in_kbps", "network_out_kbps",
                "cost_per_hour_usd", "cost_per_month_usd", "last_updated");
        for (CloudResource r : monitoring.all()) {
            line(sb, r.getId(), r.getName(), r.getType(), r.getProvider(), r.getRegion(), r.getInstanceType(),
                    r.getIpAddress(), r.getStatus(), r.getCpu(), r.getMemory(), r.getDisk(), r.getNetworkIn(),
                    r.getNetworkOut(), r.getHourlyCost(),
                    r.isStopped() ? 0 : round2(r.getHourlyCost() * CostService.HOURS_PER_MONTH), r.getLastUpdated());
        }
        return utf8(sb);
    }

    public byte[] alertsCsv(int limit) {
        StringBuilder sb = new StringBuilder();
        line(sb, "id", "created_at", "severity", "resource", "rule", "metric", "value", "threshold",
                "message", "acknowledged", "acknowledged_by");
        for (Alert a : alerts.recent(limit)) {
            line(sb, a.getId(), a.getCreatedAt(), a.getSeverity(), a.getResourceName(), a.getRuleName(), a.getMetric(),
                    a.getValue(), a.getThreshold(), a.getMessage(), a.isAcknowledged(), a.getAcknowledgedBy());
        }
        return utf8(sb);
    }

    public byte[] costsCsv() {
        StringBuilder sb = new StringBuilder();
        line(sb, "id", "name", "type", "provider", "instance_type", "status", "cost_per_hour_usd",
                "cost_per_month_usd", "share_of_bill_pct", "avg_cpu_24h_pct", "peak_cpu_24h_pct");
        for (CostReport.ResourceCost r : costs.report().resources()) {
            line(sb, r.id(), r.name(), r.type(), r.provider(), r.instanceType(), r.status(), r.hourly(),
                    r.monthly(), r.sharePct(), r.avgCpu24h(), r.peakCpu24h());
        }
        return utf8(sb);
    }

    /** Appends one CSV row, quoting values that contain commas, quotes or line breaks. */
    private static void line(StringBuilder sb, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : String.valueOf(values[i]);
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                v = "\"" + v.replace("\"", "\"\"") + "\"";
            }
            sb.append(v);
        }
        sb.append("\r\n");
    }

    /** UTF-8 with a BOM so Excel opens special characters correctly. */
    private static byte[] utf8(StringBuilder sb) {
        return ("﻿" + sb).getBytes(StandardCharsets.UTF_8);
    }

    // ================================================================== formatting

    private static String money(double v) {
        return String.format(Locale.ENGLISH, "$%,.2f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.ENGLISH, "%.1f%%", v);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
