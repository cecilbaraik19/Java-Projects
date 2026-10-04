package com.cecil.attendance.service;

import com.cecil.attendance.dto.Dtos.EmployeeSummary;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Excel (.xlsx) and PDF versions of the attendance summary report. */
@Service
public class ExportService {

    private static final String[] HEADERS = {"Code", "Name", "Department", "Working days", "Present", "Late",
            "Half day", "On leave", "Absent", "Hours", "Overtime (h)", "Attendance %"};
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy");

    // ---------- Excel ----------

    public byte[] xlsx(List<EmployeeSummary> rows, LocalDate from, LocalDate to, String scope) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Attendance");

            // POI's Font (fully qualified - "Font" below refers to the PDF library's Font)
            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            org.apache.poi.ss.usermodel.Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            CellStyle title = wb.createCellStyle();
            title.setFont(titleFont);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            CellStyle num = wb.createCellStyle();
            num.setDataFormat(wb.createDataFormat().getFormat("0"));
            CellStyle dec = wb.createCellStyle();
            dec.setDataFormat(wb.createDataFormat().getFormat("0.0"));
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.0\"%\""));

            Row t = sh.createRow(0);
            Cell tc = t.createCell(0);
            tc.setCellValue("Attendance summary – " + scope);
            tc.setCellStyle(title);
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));
            sh.createRow(1).createCell(0).setCellValue(from.format(D) + " to " + to.format(D)
                    + "   ·   generated " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")));

            Row h = sh.createRow(3);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
            }
            int r = 4;
            for (EmployeeSummary s : rows) {
                Row row = sh.createRow(r++);
                // text values are written as plain strings, never as formulas
                row.createCell(0).setCellValue(s.employeeCode());
                row.createCell(1).setCellValue(s.employeeName());
                row.createCell(2).setCellValue(s.department());
                long[] counts = {s.workingDays(), s.present(), s.late(), s.halfDay(), s.onLeave(), s.absent()};
                for (int i = 0; i < counts.length; i++) {
                    Cell c = row.createCell(3 + i);
                    c.setCellValue(counts[i]);
                    c.setCellStyle(num);
                }
                Cell hours = row.createCell(9);
                hours.setCellValue(s.totalHours());
                hours.setCellStyle(dec);
                Cell ot = row.createCell(10);
                ot.setCellValue(s.overtimeHours());
                ot.setCellStyle(dec);
                Cell rate = row.createCell(11);
                rate.setCellValue(s.attendanceRate());
                rate.setCellStyle(pct);
            }
            int[] widths = {10, 24, 16, 13, 9, 7, 9, 9, 8, 9, 12, 13};
            for (int i = 0; i < widths.length; i++) sh.setColumnWidth(i, widths[i] * 256);
            sh.createFreezePane(2, 4);
            if (!rows.isEmpty()) sh.setAutoFilter(new CellRangeAddress(3, r - 1, 0, HEADERS.length - 1));

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- PDF ----------

    public byte[] pdf(List<EmployeeSummary> rows, LocalDate from, LocalDate to, String scope) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Attendance summary");
            doc.addCreator("AttendTrack");
            doc.open();

            Font titleF = new Font(Font.HELVETICA, 16, Font.BOLD);
            Font subF = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(0x6b, 0x69, 0x64));
            Font headF = new Font(Font.HELVETICA, 8, Font.BOLD);
            Font cellF = new Font(Font.HELVETICA, 8, Font.NORMAL);
            Font redF = new Font(Font.HELVETICA, 8, Font.BOLD, new Color(0xa8, 0x27, 0x27));

            doc.add(new Paragraph("Attendance summary – " + scope, titleF));
            Paragraph sub = new Paragraph(from.format(D) + " to " + to.format(D) + "   ·   " + rows.size()
                    + " employees   ·   generated " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm")), subF);
            sub.setSpacingAfter(10);
            doc.add(sub);

            PdfPTable table = new PdfPTable(HEADERS.length);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{7, 18, 12, 8, 7, 6, 7, 7, 7, 7, 8, 9});
            table.setHeaderRows(1);
            Color headBg = new Color(0xef, 0xef, 0xec);
            Color zebra = new Color(0xf9, 0xf9, 0xf7);
            for (int i = 0; i < HEADERS.length; i++) {
                PdfPCell c = new PdfPCell(new Phrase(HEADERS[i], headF));
                c.setBackgroundColor(headBg);
                c.setPadding(5);
                c.setHorizontalAlignment(i >= 3 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(c);
            }
            int i = 0;
            for (EmployeeSummary s : rows) {
                Color bg = (i++ % 2 == 1) ? zebra : Color.WHITE;
                String[] values = {s.employeeCode(), s.employeeName(), s.department(), str(s.workingDays()),
                        str(s.present()), str(s.late()), str(s.halfDay()), str(s.onLeave()), str(s.absent()),
                        String.format("%.1f", s.totalHours()), String.format("%.1f", s.overtimeHours()),
                        String.format("%.1f%%", s.attendanceRate())};
                for (int col = 0; col < values.length; col++) {
                    boolean low = col == 11 && s.attendanceRate() < 85;
                    PdfPCell c = new PdfPCell(new Phrase(values[col], low ? redF : cellF));
                    c.setBackgroundColor(bg);
                    c.setPadding(4);
                    c.setBorderColor(new Color(0xe1, 0xe0, 0xd9));
                    c.setHorizontalAlignment(col >= 3 ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                    table.addCell(c);
                }
            }
            doc.add(table);
            Paragraph note = new Paragraph("Attendance % excludes days on leave. Red = below 85%. "
                    + "Overtime counts hours beyond the shift's standard hours plus all weekend/holiday work.", subF);
            note.setSpacingBefore(8);
            doc.add(note);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not build PDF", e);
        } finally {
            if (doc.isOpen()) doc.close();
        }
        return out.toByteArray();
    }

    private static String str(long v) {
        return Long.toString(v);
    }
}
