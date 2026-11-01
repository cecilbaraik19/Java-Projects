package com.cecil.invoice.store;

import com.cecil.invoice.util.Gstin;
import com.cecil.invoice.util.Money;
import com.cecil.invoice.util.Words;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Makes realistic (made-up) invoices as plain text, in four different layouts, the way
 * different suppliers really print them. Used for the demo data, the "Try a sample"
 * buttons and the tests. Every invoice comes with its "truth" (the right answers), so
 * the extractor's accuracy can be measured.
 *
 * All companies, GSTINs, phone numbers and addresses are invented.
 */
public final class SampleInvoices {
    private SampleInvoices() {}

    public static final String BUYER = "Nova Learning Pvt Ltd";
    public static final String BUYER_GSTIN = Gstin.complete("20AAICN4821K1Z");
    public static final String BUYER_ADDRESS = "Plot 7, Kanke Road, Ranchi, Jharkhand - 834008";

    /** One supplier: its details, layout and the things it sells (description, HSN/SAC, price). */
    public record Supplier(String key, String name, String gstin, String[] address, String category, int layout, int gstRate,
                           String prefix, Object[][] catalog) {}

    public static final List<Supplier> SUPPLIERS = List.of(
            new Supplier("kanchan", "KANCHAN STATIONERY MART", Gstin.complete("20ABCPK1234M1Z"),
                    new String[] {"Shop 14, Main Road, Ranchi, Jharkhand - 834001", "Phone: 0651-2201145 | Email: sales@kanchan.example"},
                    "Office Supplies", 1, 12, "KSM/", new Object[][] {
                    {"A4 Copier Paper 75 GSM (Ream)", "4802", 260.0}, {"Ball Pen Blue (Box of 50)", "9608", 350.0},
                    {"Lever Arch File", "4820", 95.0}, {"Whiteboard Marker Set", "9608", 180.0}, {"Stapler Heavy Duty", "8472", 420.0},
                    {"Long Register 200 Pages", "4820", 110.0}}),
            new Supplier("byteline", "ByteLine Computers Pvt Ltd", Gstin.complete("19AACCB5521D1Z"),
                    new String[] {"22 Chandni Chowk Market, Kolkata, West Bengal - 700072", "Tel: 033-4006 2211   Email: accounts@byteline.example"},
                    "IT Hardware", 1, 18, "BL/26-27/", new Object[][] {
                    {"Wireless Keyboard and Mouse Combo", "8471", 1450.0}, {"24 inch LED Monitor", "8528", 9800.0},
                    {"SSD 512GB SATA", "8523", 3400.0}, {"UPS 1 kVA", "8504", 5200.0}, {"Laser Printer Toner Cartridge", "8443", 2650.0}}),
            new Supplier("cloudnest", "CLOUDNEST SOFTWARE SOLUTIONS LLP", Gstin.complete("29AAKFC5678P1Z"),
                    new String[] {"4th Floor, Tech Park, Outer Ring Road, Bengaluru, Karnataka 560103"},
                    "Software & Cloud", 2, 18, "CN-26-", new Object[][] {
                    {"Cloud hosting - Standard plan", "998315", 4500.0}, {"Antivirus licence (per device)", "998434", 650.0},
                    {"Domain renewal (.in)", "998319", 850.0}, {"Backup storage 500 GB", "998315", 1200.0}}),
            new Supplier("netlink", "Netlink Broadband Pvt Ltd", Gstin.complete("20AADCN7788B1Z"),
                    new String[] {"Lalpur Chowk, Ranchi, Jharkhand 834001"},
                    "Telecom & Internet", 2, 18, "NB", new Object[][] {
                    {"Broadband 200 Mbps business plan", "998422", 2999.0}, {"Static IP address", "998422", 300.0}}),
            new Supplier("mehta", "MEHTA & ASSOCIATES", Gstin.complete("07AAHFM4321Q1Z"),
                    new String[] {"Chartered Accountants", "B-12, Connaught Place, New Delhi - 110001"},
                    "Professional Services", 3, 18, "MA/26-27/", new Object[][] {
                    {"Statutory audit fees - FY 2025-26", "998221", 25000.0}, {"GST return filing and reconciliation", "998231", 6000.0},
                    {"Tax consultancy - quarterly retainer", "998231", 9000.0}}),
            new Supplier("skyroute", "SKYROUTE TRAVELS", Gstin.complete("10AAPFS2468H1Z"),
                    new String[] {"Fraser Road, Patna 800001  Ph 0612-2345678"},
                    "Travel", 4, 18, "SRT-", new Object[][] {
                    {"Air ticket service fee", "998551", 450.0}, {"Hotel stay - Delhi per night", "996311", 3200.0},
                    {"Cab hire - airport transfer", "996601", 900.0}}),
            new Supplier("fixright", "FixRight Facility Services", Gstin.complete("20AAKFF3344C1Z"),
                    new String[] {"Bistupur, Jamshedpur, Jharkhand - 831001", "Phone: 0657-2423344"},
                    "Repairs & Maintenance", 1, 18, "FR-", new Object[][] {
                    {"Split AC servicing", "998719", 750.0}, {"Plumbing repair - washroom", "998729", 1200.0},
                    {"Electrical wiring work", "998719", 2400.0}}),
            new Supplier("spiceroute", "Spice Route Caterers", Gstin.complete("20ABFPS9087K1Z"),
                    new String[] {"Harmu Road, Ranchi - 834002", "Phone: 98350 12345"},
                    "Food & Catering", 4, 5, "SR", new Object[][] {
                    {"Veg lunch box", "996331", 180.0}, {"Tea and snacks", "996331", 60.0}, {"Event buffet per plate", "996334", 450.0}}),
            new Supplier("aquapure", "AquaPure Water Services", Gstin.complete("20AAQFA1122L1Z"),
                    new String[] {"Doranda, Ranchi 834002"},
                    "Utilities", 4, 18, "AP", new Object[][] {
                    {"Drinking water can 20 L", "2201", 40.0}, {"RO purifier service visit", "998729", 600.0}}),
            new Supplier("greenleaf", "Greenleaf Properties", Gstin.complete("20AAJFG6655N1Z"),
                    new String[] {"Circular Road, Ranchi - 834001"},
                    "Rent & Facilities", 2, 18, "GLP/", new Object[][] {
                    {"Office rent - 2nd floor", "997212", 45000.0}, {"Common area maintenance", "997212", 6000.0},
                    {"Car parking (4 slots)", "997212", 2000.0}}));

    /** A supplier that is not in the demo data, to show how a brand-new vendor is handled. */
    public static final Supplier NEW_VENDOR = new Supplier("pixelprint", "PIXEL PRINT HOUSE", Gstin.complete("20AAMFP7410R1Z"),
            new String[] {"Upper Bazar, Ranchi, Jharkhand - 834001", "Phone: 0651-2207788"},
            "Office Supplies", 1, 18, "PPH/", new Object[][] {
            {"Visiting cards (box of 100)", "4911", 350.0}, {"Flex banner 6x3 ft", "4911", 540.0}, {"Brochure printing A4 colour", "4911", 18.0}});

    public static Supplier supplier(String key) {
        if (NEW_VENDOR.key.equals(key)) return NEW_VENDOR;
        for (Supplier s : SUPPLIERS) if (s.key.equals(key)) return s;
        throw new IllegalArgumentException(key);
    }

    /** Problems a sample can have on purpose, so the checks have something to catch. */
    public enum Flaw { NONE, WRONG_TOTAL, BAD_GSTIN, WRONG_GST_TYPE, WORDS_MISMATCH, OCR_NOISE }

    /** The right answers for one sample. */
    public static final class Truth {
        public String vendorName, vendorGstin, invoiceNo, invoiceDate, dueDate = "", poNumber = "", buyerName = BUYER, buyerGstin = BUYER_GSTIN;
        public double subtotal, cgst, sgst, igst, taxTotal, total;
        public String category;
        public final List<Object[]> items = new ArrayList<>();   // description, hsn, qty, rate, amount
    }

    public record Sample(String supplierKey, String text, Truth truth, Flaw flaw) {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter D_MON = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM/yy");

    /** Make one invoice. */
    public static Sample make(Supplier s, int number, LocalDate date, Random rnd, Flaw flaw) {
        Truth t = new Truth();
        t.vendorName = displayName(s.name);
        t.vendorGstin = s.gstin;
        t.invoiceDate = date.toString();
        t.category = s.category;
        t.invoiceNo = s.prefix + (s.layout == 2 || s.layout == 4 ? String.format("%04d", number) : String.format("%03d", number));

        // pick 1-4 items
        List<Object[]> cat = new ArrayList<>(Arrays.asList(s.catalog));
        Collections.shuffle(cat, rnd);
        int count = s.key.equals("netlink") ? 2 : Math.min(cat.size(), 1 + rnd.nextInt(Math.min(4, cat.size())));
        if (s.key.equals("greenleaf")) count = 2 + rnd.nextInt(2);
        double sub = 0;
        for (int i = 0; i < count; i++) {
            Object[] c = s.key.equals("greenleaf") || s.key.equals("netlink") ? s.catalog[i] : cat.get(i);
            double rate = (Double) c[2];
            int qty = switch (s.key) {
                case "kanchan" -> 2 + rnd.nextInt(20);
                case "spiceroute" -> 10 + rnd.nextInt(60);
                case "aquapure" -> c[0].toString().startsWith("Drinking") ? 20 + rnd.nextInt(40) : 1;
                case "skyroute" -> 1 + rnd.nextInt(3);
                case "byteline", "fixright" -> 1 + rnd.nextInt(4);
                case "cloudnest" -> c[0].toString().contains("per device") ? 5 + rnd.nextInt(20) : 1;
                default -> 1;
            };
            double amount = Money.r2(qty * rate);
            t.items.add(new Object[] {c[0], c[1], (double) qty, rate, amount});
            sub += amount;
        }
        t.subtotal = Money.r2(sub);
        boolean intra = s.gstin.substring(0, 2).equals(BUYER_GSTIN.substring(0, 2));
        if (flaw == Flaw.WRONG_GST_TYPE) intra = !intra;
        double tax = Money.r2(t.subtotal * s.gstRate / 100.0);
        if (intra) { t.cgst = Money.r2(tax / 2); t.sgst = Money.r2(tax / 2); }
        else t.igst = tax;
        t.taxTotal = Money.r2(t.cgst + t.sgst + t.igst);
        t.total = Money.r2(t.subtotal + t.taxTotal);
        if (s.layout == 1 || s.layout == 2) t.dueDate = date.plusDays(s.layout == 1 ? 30 : 15).toString();
        if (s.layout == 1 && rnd.nextBoolean()) t.poNumber = "PO-NL-" + (900 + rnd.nextInt(99));
        if (s.layout == 2 && !s.key.equals("greenleaf")) t.poNumber = "PO-NL-" + (900 + rnd.nextInt(99));

        String gstinShown = flaw == Flaw.BAD_GSTIN ? s.gstin.substring(0, 14) + (s.gstin.charAt(14) == 'A' ? 'B' : 'A') : s.gstin;
        if (flaw == Flaw.BAD_GSTIN) t.vendorGstin = gstinShown;
        double totalShown = flaw == Flaw.WRONG_TOTAL ? t.total + 100 : t.total;
        if (flaw == Flaw.WRONG_TOTAL) t.total = totalShown;     // what is printed - the check must catch it
        String words = Words.toWords(flaw == Flaw.WORDS_MISMATCH ? t.total + 1000 : t.total);

        String text = switch (s.layout) {
            case 1 -> layoutClassic(s, t, gstinShown, date, totalShown, words);
            case 2 -> layoutColumns(s, t, gstinShown, date, totalShown, words);
            case 3 -> layoutService(s, t, gstinShown, date, totalShown, words);
            default -> layoutReceipt(s, t, gstinShown, date, totalShown);
        };
        if (s.layout == 3) t.dueDate = date.plusDays(15).toString();
        if (s.layout == 4) {
            t.dueDate = date.plusDays(7).toString();
            for (Object[] it : t.items) it[1] = "";          // a till receipt does not print HSN codes
        }
        if (flaw == Flaw.OCR_NOISE) text = ocrNoise(text);
        return new Sample(s.key, text, t, flaw);
    }

    /** "KANCHAN STATIONERY MART" -> "Kanchan Stationery Mart" (how the vendor is stored). */
    public static String displayName(String printed) {
        if (!printed.equals(printed.toUpperCase(Locale.ROOT))) return printed;
        StringBuilder sb = new StringBuilder();
        for (String w : printed.toLowerCase(Locale.ROOT).split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.equals("llp") ? "LLP" : w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    private static String pad(String s, int w) { return s.length() >= w ? s + " " : s + " ".repeat(w - s.length()); }
    private static String lpad(String s, int w) { return s.length() >= w ? " " + s : " ".repeat(w - s.length()) + s; }
    private static String qty(double q) { return q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q); }

    // ---------------------------------------------------------------- layout 1: classic GST tax invoice
    private static String layoutClassic(Supplier s, Truth t, String gstin, LocalDate d, double total, String words) {
        StringBuilder b = new StringBuilder();
        b.append(s.name).append('\n');
        for (String a : s.address) b.append(a).append('\n');
        b.append("GSTIN: ").append(gstin).append("\n\n");
        b.append(" ".repeat(28)).append("TAX INVOICE\n\n");
        b.append(pad("Invoice No   : " + t.invoiceNo, 38)).append("Invoice Date : ").append(d.format(DMY)).append('\n');
        b.append(pad("PO No        : " + (t.poNumber.isEmpty() ? "-" : t.poNumber), 38)).append("Due Date     : ").append(LocalDate.parse(t.dueDate).format(DMY)).append("\n\n");
        b.append("Bill To:\n").append(BUYER).append('\n').append(BUYER_ADDRESS).append('\n').append("GSTIN: ").append(BUYER_GSTIN).append("\n\n");
        b.append(pad("Sr", 4)).append(pad("Description", 36)).append(pad("HSN", 8)).append(lpad("Qty", 5)).append(lpad("Rate", 12)).append(lpad("Amount", 14)).append('\n');
        b.append("-".repeat(79)).append('\n');
        int i = 1;
        for (Object[] it : t.items) {
            b.append(pad(String.valueOf(i++), 4)).append(pad((String) it[0], 36)).append(pad((String) it[1], 8))
                    .append(lpad(qty((Double) it[2]), 5)).append(lpad(Money.format((Double) it[3]), 12)).append(lpad(Money.format((Double) it[4]), 14)).append('\n');
        }
        b.append("-".repeat(79)).append('\n');
        int rate = (int) Math.round((t.cgst + t.sgst + t.igst) / t.subtotal * 100);
        b.append(totalLine("Sub Total", t.subtotal));
        if (t.igst > 0) b.append(totalLine("IGST @ " + rate + "%", t.igst));
        else { b.append(totalLine("CGST @ " + fmtRate(rate / 2.0) + "%", t.cgst)); b.append(totalLine("SGST @ " + fmtRate(rate / 2.0) + "%", t.sgst)); }
        b.append(totalLine("Grand Total", total));
        b.append("\nAmount in Words: ").append(words).append('\n');
        b.append("Payment Terms: Net 30\n");
        b.append("Bank: Example Bank, IFSC EXMP0001234\n\n");
        b.append(" ".repeat(42)).append("For ").append(displayName(s.name)).append('\n');
        b.append(" ".repeat(42)).append("Authorised Signatory\n");
        return b.toString();
    }

    private static String fmtRate(double r) { return r == Math.rint(r) ? String.valueOf((int) r) : String.valueOf(r); }

    private static String totalLine(String label, double v) {
        return " ".repeat(46) + pad(label, 19) + lpad(Money.format(v), 14) + "\n";
    }

    // ---------------------------------------------------------------- layout 2: labels in a row, values below
    private static String layoutColumns(Supplier s, Truth t, String gstin, LocalDate d, double total, String words) {
        StringBuilder b = new StringBuilder();
        b.append(s.name).append('\n');
        for (String a : s.address) b.append(a).append('\n');
        b.append(pad("GSTIN/UIN: " + gstin, 40)).append("State: ").append(Gstin.state(gstin)).append("\n\n");
        b.append("TAX INVOICE\n\n");
        b.append(pad("Invoice No.", 18)).append(pad("Date", 18)).append(pad("Due Date", 18)).append(t.poNumber.isEmpty() ? "" : "PO No.").append('\n');
        b.append(pad(t.invoiceNo, 18)).append(pad(d.format(D_MON), 18)).append(pad(LocalDate.parse(t.dueDate).format(D_MON), 18)).append(t.poNumber).append("\n\n");
        b.append("Invoice To:\n").append(BUYER).append(", Ranchi\nGSTIN: ").append(BUYER_GSTIN).append("\n\n");
        b.append(pad("Particulars", 40)).append(pad("SAC", 9)).append(lpad("Qty", 4)).append(lpad("Price", 12)).append(lpad("Total", 13)).append('\n');
        for (Object[] it : t.items) {
            b.append(pad((String) it[0], 40)).append(pad((String) it[1], 9)).append(lpad(qty((Double) it[2]), 4))
                    .append(lpad(Money.format((Double) it[3]), 12)).append(lpad(Money.format((Double) it[4]), 13)).append('\n');
        }
        b.append('\n');
        int rate = (int) Math.round(t.taxTotal / t.subtotal * 100);
        b.append(pad("Taxable Value", 60)).append(lpad(Money.format(t.subtotal), 18)).append('\n');
        if (t.igst > 0) b.append(pad("IGST " + rate + "%", 60)).append(lpad(Money.format(t.igst), 18)).append('\n');
        else {
            b.append(pad("CGST " + fmtRate(rate / 2.0) + "%", 60)).append(lpad(Money.format(t.cgst), 18)).append('\n');
            b.append(pad("SGST " + fmtRate(rate / 2.0) + "%", 60)).append(lpad(Money.format(t.sgst), 18)).append('\n');
        }
        b.append(pad("Total Amount Payable", 60)).append(lpad("Rs. " + Money.format(total), 18)).append('\n');
        b.append(words).append("\n\n");
        b.append("This is a computer generated invoice.\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- layout 3: professional service bill ("Bill Ref")
    private static String layoutService(Supplier s, Truth t, String gstin, LocalDate d, double total, String words) {
        StringBuilder b = new StringBuilder();
        b.append(s.name).append('\n');
        for (String a : s.address) b.append(a).append('\n');
        b.append("GSTIN: ").append(gstin).append("\n\n");
        b.append("INVOICE\n\n");
        b.append("Bill Ref : ").append(t.invoiceNo).append('\n');
        b.append("Dated    : ").append(d.format(LONG)).append('\n');
        b.append("Client   : ").append(BUYER).append('\n');
        b.append("           Client GSTIN ").append(BUYER_GSTIN).append("\n\n");
        b.append(pad("Description of Services", 46)).append(pad("SAC", 10)).append(lpad("Amount", 14)).append('\n');
        for (Object[] it : t.items) b.append(pad((String) it[0], 46)).append(pad((String) it[1], 10)).append(lpad(Money.format((Double) it[4]), 14)).append('\n');
        b.append('\n');
        int rate = (int) Math.round(t.taxTotal / t.subtotal * 100);
        b.append(" ".repeat(30)).append(pad("Taxable Value", 26)).append(lpad(Money.format(t.subtotal), 14)).append('\n');
        if (t.igst > 0) b.append(" ".repeat(30)).append(pad("IGST @ " + rate + "%", 26)).append(lpad(Money.format(t.igst), 14)).append('\n');
        else {
            b.append(" ".repeat(30)).append(pad("CGST @ " + fmtRate(rate / 2.0) + "%", 26)).append(lpad(Money.format(t.cgst), 14)).append('\n');
            b.append(" ".repeat(30)).append(pad("SGST @ " + fmtRate(rate / 2.0) + "%", 26)).append(lpad(Money.format(t.sgst), 14)).append('\n');
        }
        b.append(" ".repeat(30)).append(pad("Total Due", 26)).append(lpad(Money.format(total), 14)).append('\n');
        b.append("\n(").append(words).append(")\n");
        b.append("Payment terms: Net 15. Please quote the Bill Ref with your payment.\n");
        return b.toString();
    }

    // ---------------------------------------------------------------- layout 4: short till receipt
    private static String layoutReceipt(Supplier s, Truth t, String gstin, LocalDate d, double total) {
        StringBuilder b = new StringBuilder();
        String rule = "-".repeat(40) + "\n";
        b.append(s.name).append('\n');
        for (String a : s.address) b.append(a).append('\n');
        b.append("GSTIN ").append(gstin).append('\n').append(rule);
        b.append("INVOICE\n");
        b.append(pad("Inv# " + t.invoiceNo, 22)).append("Dt: ").append(d.format(SHORT)).append('\n');
        b.append("Customer: ").append(BUYER).append('\n');
        b.append("GSTIN ").append(BUYER_GSTIN).append('\n').append(rule);
        b.append(pad("Item", 26)).append(lpad("Qty", 4)).append(lpad("Amt", 10)).append('\n');
        for (Object[] it : t.items) b.append(pad((String) it[0], 26)).append(lpad(qty((Double) it[2]), 4)).append(lpad(Money.format((Double) it[4]), 10)).append('\n');
        b.append(rule);
        int rate = (int) Math.round(t.taxTotal / t.subtotal * 100);
        b.append(pad("Subtotal", 26)).append(lpad(Money.format(t.subtotal), 14)).append('\n');
        if (t.igst > 0) b.append(pad("IGST " + rate + "%", 26)).append(lpad(Money.format(t.igst), 14)).append('\n');
        else {
            b.append(pad("CGST " + fmtRate(rate / 2.0) + "%", 26)).append(lpad(Money.format(t.cgst), 14)).append('\n');
            b.append(pad("SGST " + fmtRate(rate / 2.0) + "%", 26)).append(lpad(Money.format(t.sgst), 14)).append('\n');
        }
        b.append(pad("TOTAL", 22)).append(lpad("Rs. " + Money.format(total), 18)).append('\n').append(rule);
        b.append("Thank you! Please pay within 7 days.\n");
        return b.toString();
    }

    /** Make the text look like a poor scan: a few letters and digits swapped. */
    static String ocrNoise(String text) {
        return text.replace("Invoice No", "lnvoice N0")
                .replace("Grand Total", "Grand Tota1")
                .replace("Sub Total", "Sub-Totai")
                .replace("Invoice Date", "Invoice Dale")
                .replace(".00\n", ".O0\n");
    }
}
