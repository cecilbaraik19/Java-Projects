package com.cecil.invoice;

import com.cecil.invoice.extract.*;
import com.cecil.invoice.model.*;
import com.cecil.invoice.service.*;
import com.cecil.invoice.store.DataStore;
import com.cecil.invoice.store.SampleInvoices;
import com.cecil.invoice.store.SampleInvoices.Flaw;
import com.cecil.invoice.store.SampleInvoices.Sample;
import com.cecil.invoice.util.*;
import com.cecil.invoice.web.ApiServer;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.zip.Deflater;

/**
 * All backend tests in one file, with a tiny test runner - no JUnit download needed.
 * The clock is fixed at 1 Nov 2026, 10:00 so results never depend on the real date.
 * Run with run-tests.bat / run-tests.sh.
 */
public final class AllTests {
    private AllTests() {}

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 1, 10, 0).atZone(ZONE).toInstant(), ZONE);
    static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    private static int passed = 0, failed = 0;
    private static final List<String> failures = new ArrayList<>();

    interface Body { void run() throws Exception; }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            failures.add(name + ": " + t);
            System.out.println("  FAIL  " + name + "  ->  " + t);
        }
    }

    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static void eq(Object expected, Object actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
    }

    // ------------------------------------------------------------ helpers

    static Settings settings() {
        Settings s = new Settings();
        s.companyGstin = SampleInvoices.BUYER_GSTIN;
        return s;
    }

    static LocalExtractor.Result read(String text) {
        return new LocalExtractor(new LocalExtractor.Context(List.of(), settings(), TODAY)).extract(text);
    }

    static Sample sample(String supplier, Flaw flaw, int seed) {
        return SampleInvoices.make(SampleInvoices.supplier(supplier), 100 + seed, TODAY.minusDays(3), new Random(seed), flaw);
    }

    /** Fresh in-memory store with users and settings but no invoices. */
    static DataStore emptyStore() {
        DataStore db = new DataStore(null, CLOCK);
        db.settings = settings();
        for (String[] u : new String[][] {{"admin", "ADMIN"}, {"meera", "ACCOUNTANT"}, {"ravi", "CLERK"}}) {
            User x = new User();
            x.id = db.nextId("U");
            x.username = u[0];
            x.name = u[0];
            x.role = Role.valueOf(u[1]);
            x.passwordHash = Passwords.hash(u[0] + "123x");
            db.users.add(x);
        }
        return db;
    }

    static String str(Object o) { return String.valueOf(o); }

    // ------------------------------------------------------------ the tests

    public static void main(String[] args) throws Exception {
        System.out.println("Bahi - AI-Based Invoice Data Extractor - tests (clock fixed at 1 Nov 2026)\n");

        System.out.println("Helpers");
        test("Money reads Indian amounts and writes Indian grouping", () -> {
            eq(123456.5, Money.parse("Rs. 1,23,456.50"), "Indian commas");
            eq(1500.0, Money.parse("1500/-"), "trailing /-");
            eq(-250.0, Money.parse("(250.00)"), "brackets mean minus");
            eq(null, Money.parse("2025-26"), "a year range is not an amount");
            eq("12,34,567.50", Money.format(1234567.5), "format");
            List<Money.Found> f = Money.findAll("CGST @ 9%   1,080.00");
            eq(1, f.size(), "percent is skipped");
            eq(1080.0, f.get(0).value(), "amount");
        });
        test("Dates are read in many formats, day first", () -> {
            eq(LocalDate.of(2026, 10, 12), Dates.parse("12/10/2026"), "dd/mm/yyyy");
            eq(LocalDate.of(2026, 10, 12), Dates.parse("12-Oct-26"), "dd-Mon-yy");
            eq(LocalDate.of(2026, 10, 12), Dates.parse("October 12, 2026"), "Month d, yyyy");
            eq(LocalDate.of(2026, 10, 12), Dates.parse("2026-10-12"), "ISO");
            eq(LocalDate.of(2026, 10, 25), Dates.parse("10/25/2026"), "month first only when day first is impossible");
            eq(null, Dates.parse("MA/26-27/041"), "an invoice number is not a date");
        });
        test("GSTIN check character catches a wrong digit", () -> {
            String g = SampleInvoices.BUYER_GSTIN;
            check(Gstin.valid(g), "made-up GSTIN with the right check character is valid");
            String bad = g.substring(0, 14) + (g.charAt(14) == 'A' ? 'B' : 'A');
            check(!Gstin.valid(bad), "wrong check character");
            check(!Gstin.valid("20AAICN4821K1Q5"), "14th character must be Z");
            eq("Jharkhand", Gstin.state(g), "state from the first two digits");
        });
        test("Amounts in words both ways (lakh and crore)", () -> {
            eq("Rupees Twelve Thousand Five Hundred Forty and Fifty Paise Only", Words.toWords(12540.5), "to words");
            eq(12540.5, Words.parse("Rupees Twelve Thousand Five Hundred Forty and Fifty Paise Only"), "back");
            eq(3_25_00_000.0, Words.parse("Three Crore Twenty Five Lakh Only"), "crore and lakh");
        });
        test("Fuzzy matching of company names", () -> {
            check(Fuzzy.sameCompany("M/s ByteLine Computers Pvt. Ltd.", "ByteLine Computers Pvt Ltd") >= 0.95, "same after cleaning");
            check(Fuzzy.sameCompany("Bytline Computers", "ByteLine Computers Pvt Ltd") >= 0.85, "one letter missing");
            check(Fuzzy.sameCompany("Greenleaf Properties", "Netlink Broadband") < 0.5, "different companies");
        });
        test("JSON writes and reads back; CSV blocks formula injection", () -> {
            Map<String, Object> m = Json.parseObject(Json.write(Map.of("a", "x<y", "n", 3)));
            eq("x<y", m.get("a"), "string survives");
            check(Csv.cell("=SUM(A1)").startsWith("\"'"), "formula is neutralised");
        });

        System.out.println("\nReading invoices (built-in reader)");
        String[][] cases = {{"kanchan", "classic"}, {"cloudnest", "labels in a row"}, {"skyroute", "short receipt"}, {"greenleaf", "rent, columns"},
                {"spiceroute", "receipt, 5% GST"}, {"byteline", "other state, IGST"}};
        for (String[] c : cases) {
            test("Reads every key field: " + c[0] + " (" + c[1] + ")", () -> {
                for (int seed = 1; seed <= 4; seed++) {
                    Sample s = sample(c[0], Flaw.NONE, seed);
                    LocalExtractor.Result r = read(s.text());
                    SampleInvoices.Truth t = s.truth();
                    eq(t.invoiceNo, r.fields.get("invoiceNo").value, "invoice no (seed " + seed + ")");
                    eq(t.invoiceDate, r.fields.get("invoiceDate").value, "invoice date");
                    eq(t.dueDate, r.fields.get("dueDate").value, "due date");
                    eq(t.vendorGstin, r.fields.get("vendorGstin").value, "vendor GSTIN");
                    eq(t.buyerGstin, r.fields.get("buyerGstin").value, "buyer GSTIN");
                    eq(Money.plain(t.subtotal), r.fields.get("subtotal").value, "subtotal");
                    eq(Money.plain(t.total), r.fields.get("total").value, "total");
                    check(r.fields.get("vendorName").value.equalsIgnoreCase(t.vendorName), "vendor name: " + r.fields.get("vendorName").value);
                    eq(t.items.size(), r.items.size(), "number of items");
                    for (int i = 0; i < t.items.size(); i++) {
                        eq(t.items.get(i)[0], r.items.get(i).description, "item description");
                        eq((Double) t.items.get(i)[4], r.items.get(i).amount, "item amount");
                    }
                }
            });
        }
        test("A poor scan is still read (lnvoice N0, Tota1, O for 0)", () -> {
            Sample s = sample("kanchan", Flaw.OCR_NOISE, 7);
            check(s.text().contains("lnvoice N0"), "the sample really is noisy");
            LocalExtractor.Result r = read(s.text());
            eq(s.truth().invoiceNo, r.fields.get("invoiceNo").value, "invoice no via fuzzy label");
            eq(Money.plain(s.truth().total), r.fields.get("total").value, "total via fuzzy label");
            eq(Money.plain(s.truth().subtotal), r.fields.get("subtotal").value, "O0 repaired to 00");
            eq(s.truth().items.size(), r.items.size(), "the table ends at the ruled line");
        });
        test("Numbers inside a description stay in the description", () -> {
            LineItem it = ItemsParser.row("2   24 inch LED Monitor               8528        3    9,800.00     29,400.00", 0);
            eq("24 inch LED Monitor", it.description, "description");
            eq("8528", it.hsn, "HSN");
            eq(3.0, it.qty, "qty");
            eq(9800.0, it.rate, "rate");
            check(it.mathOk, "qty x rate = amount");
        });
        test("Scan repair never touches a GSTIN", () -> {
            String g = "GSTIN/UIN: 20AADCN7788B1ZL";
            eq(g, LocalExtractor.fixDigits(g), "GSTIN unchanged");
            eq("Total 1,080.00", LocalExtractor.fixDigits("Total 1,08O.0O"), "amount repaired");
        });
        test("Due date is worked out from 'Net 15' when not printed", () -> {
            Sample s = sample("mehta", Flaw.NONE, 3);
            LocalExtractor.Result r = read(s.text());
            eq(s.truth().dueDate, r.fields.get("dueDate").value, "invoice date + 15 days");
            check(r.fields.get("dueDate").method.contains("payment terms"), "explains how");
        });
        test("Reads text from a PDF (zipped content stream)", () -> {
            byte[] pdf = tinyPdf(new String[] {"ACME TRADERS", "Invoice No: AT-77", "Grand Total   Rs. 1,180.00"});
            String text = PdfText.extract(pdf);
            check(text.contains("Invoice No: AT-77"), "line 2 found: " + text);
            LocalExtractor.Result r = read(text);
            eq("AT-77", r.fields.get("invoiceNo").value, "invoice no");
            eq("1180.00", r.fields.get("total").value, "total");
        });

        System.out.println("\nChecks");
        test("Clean invoice passes every check", () -> {
            Invoice inv = full(sample("kanchan", Flaw.NONE, 2));
            Validator.run(inv, List.of(), settings(), TODAY);
            eq(0, Validator.count(inv.checks, Validator.FAIL), "no FAIL: " + summary(inv));
            check(inv.confidence >= 0.85, "high confidence " + inv.confidence);
        });
        Object[][] flaws = {{Flaw.WRONG_TOTAL, "TOTAL_MATH"}, {Flaw.BAD_GSTIN, "GSTIN_VENDOR"}, {Flaw.WRONG_GST_TYPE, "GST_TYPE"}, {Flaw.WORDS_MISMATCH, "WORDS"}};
        for (Object[] f : flaws) {
            test("Catches " + f[0] + " with check " + f[1], () -> {
                Invoice inv = full(sample(f[0] == Flaw.WRONG_GST_TYPE ? "skyroute" : "kanchan", (Flaw) f[0], 5));
                Validator.run(inv, List.of(), settings(), TODAY);
                Check c = inv.checks.stream().filter(x -> x.code.equals(f[1])).findFirst().orElse(null);
                check(c != null, "check present");
                eq("FAIL", c.result, c.detail);
            });
        }
        test("Same vendor + same invoice number = duplicate", () -> {
            Sample s = sample("netlink", Flaw.NONE, 1);
            Invoice a = full(s), b = full(s);
            a.id = "IX0001"; b.id = "IX0002";
            Validator.run(b, List.of(a), settings(), TODAY);
            eq("IX0001", b.duplicateOf, "points to the first one");
        });

        System.out.println("\nLearning");
        test("Classifier learns categories (Naive Bayes)", () -> {
            Classifier c = new Classifier(new CategoryModel());
            c.train("laptop keyboard mouse monitor", "IT Hardware");
            c.train("hotel stay cab hire air ticket", "Travel");
            LinkedHashMap<String, Double> p = c.predict("wireless mouse and monitor");
            eq("IT Hardware", p.keySet().iterator().next(), "top guess");
            double sum = 0;
            for (double v : p.values()) sum += v;
            check(Math.abs(sum - 1) < 0.01, "chances add up to 1");
        });
        test("A correction teaches the vendor's label ('Bill Ref' = invoice number)", () -> {
            DataStore db = emptyStore();
            LearningService learning = new LearningService(db);
            InvoiceService svc = new InvoiceService(db, learning);
            User meera = db.userByName("meera"), ravi = db.userByName("ravi");
            Sample s1 = sample("mehta", Flaw.NONE, 1);
            Invoice first = svc.addText(ravi, s1.text(), "TEST", "a.txt");
            check(first.f("invoiceNo").empty(), "first time 'Bill Ref' is not known");
            svc.correct(meera, first.id, Map.of("fields", Map.of("invoiceNo", s1.truth().invoiceNo)));
            svc.approve(meera, first.id, Map.of());
            Vendor v = db.vendors.get(0);
            eq("bill ref", v.labelHints.get("invoiceNo"), "label learned");
            Sample s2 = sample("mehta", Flaw.NONE, 2);
            Invoice second = svc.addText(ravi, s2.text(), "TEST", "b.txt");
            eq(s2.truth().invoiceNo, second.v("invoiceNo"), "read by itself the next time");
            check(second.f("invoiceNo").method.contains("learned"), "says it used the learned label");
            eq(v.id, second.vendorId, "recognised as the same vendor");
        });
        test("Auto-check approves only clean bills from known vendors, under the limit", () -> {
            DataStore db = emptyStore();
            InvoiceService svc = new InvoiceService(db, new LearningService(db));
            User meera = db.userByName("meera"), ravi = db.userByName("ravi");
            Invoice a = svc.addText(ravi, sample("kanchan", Flaw.NONE, 1).text(), "TEST", "a.txt");
            eq(Status.TO_REVIEW, a.status, "a new vendor always needs a person");
            svc.approve(meera, a.id, Map.of());
            Invoice b = svc.addText(ravi, sample("kanchan", Flaw.NONE, 2).text(), "TEST", "b.txt");
            check(b.num("total") <= 25000, "sample is small");
            eq(Status.APPROVED, b.status, "known vendor, clean, sure: auto-approved (" + b.confidence + ")");
            check(b.autoApproved, "marked as auto");
            Invoice c = svc.addText(ravi, sample("kanchan", Flaw.WORDS_MISMATCH, 3).text(), "TEST", "c.txt");
            eq(Status.TO_REVIEW, c.status, "a failed check stops auto-approval");
        });
        test("Approving with a failed check needs override and a note", () -> {
            DataStore db = emptyStore();
            InvoiceService svc = new InvoiceService(db, new LearningService(db));
            User meera = db.userByName("meera");
            Invoice inv = svc.addText(db.userByName("ravi"), sample("byteline", Flaw.WRONG_TOTAL, 1).text(), "TEST", "x.txt");
            try { svc.approve(meera, inv.id, Map.of()); throw new AssertionError("should refuse"); } catch (ApiError e) { eq(409, e.status, "conflict"); }
            try { svc.approve(meera, inv.id, Map.of("override", true, "note", "")); throw new AssertionError("should want a note"); } catch (ApiError e) { eq(400, e.status, "bad request"); }
            svc.approve(meera, inv.id, Map.of("override", true, "note", "Vendor confirmed the figure on phone."));
            eq(Status.APPROVED, inv.status, "approved with note");
        });
        test("Claude's JSON answer is turned into fields and checked", () -> {
            String reply = Json.write(Map.of("content", List.of(Map.of("type", "text", "text",
                    "Here it is: {\"vendorName\":\"Acme\",\"invoiceNo\":\"A-1\",\"invoiceDate\":\"12/10/2026\",\"subtotal\":\"1,000\",\"igst\":180,"
                            + "\"total\":1180,\"items\":[{\"description\":\"Widget\",\"qty\":2,\"rate\":500,\"amount\":1000}],\"confidence\":{\"total\":0.97}}"))));
            ClaudeExtractor.Reading r = ClaudeExtractor.parseResponse(reply);
            eq("2026-10-12", r.fields.get("invoiceDate").value, "date cleaned");
            eq("1000.00", r.fields.get("subtotal").value, "amount cleaned");
            eq(0.97, r.fields.get("total").confidence, "confidence used");
            check(r.items.get(0).mathOk, "item maths");
        });

        System.out.println("\nWeb server");
        test("Demo data loads, and roles are enforced on the server", () -> {
            DataStore db = new DataStore(null, CLOCK);
            db.load();
            check(db.invoices.size() > 40, "demo invoices: " + db.invoices.size());
            check(db.vendors.size() >= 10, "vendors");
            check(!db.vendors.stream().filter(v -> v.name.startsWith("Mehta")).findFirst().get().labelHints.isEmpty(), "demo learning happened");
            ApiServer server = new ApiServer(db, Path.of("frontend"), new Random(1));
            server.start(0);
            try {
                HttpClient http = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + server.port();
                eq(401, http.send(HttpRequest.newBuilder(URI.create(base + "/api/home")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "must sign in");
                HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ravi\",\"password\":\"clerk123\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                eq(200, login.statusCode(), "clerk signs in");
                String cookie = login.headers().firstValue("Set-Cookie").orElse("").split(";")[0];
                HttpResponse<String> list = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices?status=TO_REVIEW")).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
                eq(200, list.statusCode(), "clerk sees the tray");
                String id = str(((Map<?, ?>) ((List<?>) Json.parse(list.body())).get(0)).get("id"));
                HttpResponse<String> app = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices/" + id + "/approve")).header("Cookie", cookie)
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                eq(403, app.statusCode(), "a clerk cannot approve");
                HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create(base + "/api/invoices")).header("Cookie", cookie).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("kind", "text", "text", sample("cloudnest", Flaw.NONE, 9).text(), "fileName", "t.txt")))).build(),
                        HttpResponse.BodyHandlers.ofString());
                eq(200, up.statusCode(), "upload works: " + up.body());
            } finally {
                server.stop();
            }
        });

        System.out.println("\n" + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ more helpers

    /** An Invoice filled from a sample by the built-in reader. */
    static Invoice full(Sample s) {
        LocalExtractor.Result r = read(s.text());
        Invoice inv = new Invoice();
        inv.id = "IX9999";
        inv.rawText = s.text();
        inv.fields = r.fields;
        inv.items = r.items;
        return inv;
    }

    static String summary(Invoice inv) {
        StringBuilder sb = new StringBuilder();
        for (Check c : inv.checks) if (!c.result.equals("PASS")) sb.append(c.code).append(':').append(c.detail).append(' ');
        return sb.toString();
    }

    /** A one-page PDF with a zipped content stream, written by hand. */
    static byte[] tinyPdf(String[] lines) throws Exception {
        StringBuilder content = new StringBuilder();
        int y = 760;
        for (String l : lines) { content.append("BT /F1 10 Tf 1 0 0 1 50 ").append(y).append(" Tm (").append(l).append(") Tj ET\n"); y -= 14; }
        Deflater d = new Deflater();
        d.setInput(content.toString().getBytes(StandardCharsets.ISO_8859_1));
        d.finish();
        byte[] buf = new byte[4096];
        int n = d.deflate(buf);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n4 0 obj << /Length " + n + " /Filter /FlateDecode >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(buf, 0, n);
        out.write("\nendstream\nendobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
