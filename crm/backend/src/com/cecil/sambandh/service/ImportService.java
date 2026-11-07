package com.cecil.sambandh.service;

import com.cecil.sambandh.model.Customer;
import com.cecil.sambandh.model.Role;
import com.cecil.sambandh.model.User;
import com.cecil.sambandh.store.DataStore;
import com.cecil.sambandh.util.Csv;
import com.cecil.sambandh.util.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Adding many customers at once from a CSV file (exported from Excel, Google Sheets or an old system).
 * Step 1 "preview" checks every row and shows problems and possible duplicates - nothing is saved.
 * Step 2 "import" saves the good rows. Rows with errors are always skipped; duplicates are skipped if asked.
 */
public class ImportService {
    public static final int MAX_ROWS = 2000;
    public static final int MAX_CHARS = 1_000_000;

    /** Column names we understand (lower case, spaces/underscores ignored) -> our field. */
    private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
    static {
        for (String s : List.of("name", "fullname", "customername", "contactname", "contact")) COLUMNS.put(s, "name");
        for (String s : List.of("company", "companyname", "organisation", "organization", "business")) COLUMNS.put(s, "company");
        for (String s : List.of("email", "e-mail", "emailaddress", "mail")) COLUMNS.put(s, "email");
        for (String s : List.of("phone", "mobile", "phonenumber", "mobilenumber", "contactnumber", "tel")) COLUMNS.put(s, "phone");
        for (String s : List.of("city", "town", "location")) COLUMNS.put(s, "city");
        COLUMNS.put("type", "type");
        for (String s : List.of("source", "leadsource")) COLUMNS.put(s, "source");
        for (String s : List.of("stage", "status", "lifecycle")) COLUMNS.put(s, "stage");
        for (String s : List.of("tags", "tag", "labels")) COLUMNS.put(s, "tags");
        for (String s : List.of("owner", "agent", "assignedto")) COLUMNS.put(s, "owner");
        for (String s : List.of("notes", "note", "remarks", "comments")) COLUMNS.put(s, "notes");
        for (String s : List.of("consentemail", "emailconsent", "agreedtoemails")) COLUMNS.put(s, "consentEmail");
        for (String s : List.of("donotcall", "dnd", "dnc")) COLUMNS.put(s, "doNotCall");
    }

    private final DataStore store;
    private final CustomerService customers;

    public ImportService(DataStore store, CustomerService customers) {
        this.store = store;
        this.customers = customers;
    }

    public static String sampleCsv() {
        return "name,company,email,phone,city,type,source,stage,tags,owner,consent_email,do_not_call\r\n"
                + "Tanvi Oraon,,tanvi.oraon@example.in,98350 44120,Ranchi,Individual,Website,Lead,student-offer,arjun,yes,no\r\n"
                + "Mohan Lal Gupta,Gupta Hardware,mohan@guptahardware.example,+91 94311 22004,Dhanbad,Business,Referral,Prospect,hardware;bulk,kavya,no,yes\r\n"
                + "Sushila Hembrom,Hembrom Handloom,,0651-2203344,Ranchi,Business,Event,Customer,handloom,,yes,no\r\n"
                + "Rakesh,,not-an-email,12345,Bokaro,Individual,Facebook,VIP,,,no,no\r\n";
    }

    private void check(User actor, String csv) {
        if (!actor.role.atLeast(Role.MANAGER)) throw Problem.forbidden("Only a manager can import customers.");
        if (csv == null || csv.isBlank()) throw Problem.bad("Paste CSV text or choose a .csv file.");
        if (csv.length() > MAX_CHARS) throw Problem.bad("The file is too big (1 MB max).");
    }

    /** One checked row of the file. */
    record Row(int line, Map<String, Object> body, CustomerService.Fields fields, List<String> errors,
               List<String> warnings, String duplicateOf) { }

    List<Row> analyse(User actor, String csv) {
        List<List<String>> rows = Csv.parse(csv);
        if (rows.size() < 2) throw Problem.bad("The file needs a header line and at least one customer.");
        if (rows.size() - 1 > MAX_ROWS) throw Problem.bad("Too many rows (" + MAX_ROWS + " max at a time).");
        List<String> header = rows.get(0);
        String[] fieldOf = new String[header.size()];
        boolean hasName = false;
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i).toLowerCase(Locale.ROOT).replaceAll("[\\s_]", "");
            fieldOf[i] = COLUMNS.get(key);
            if ("name".equals(fieldOf[i])) hasName = true;
        }
        if (!hasName) throw Problem.bad("The header line needs a \"name\" column. Columns we understand: name, company, email, "
                + "phone, city, type, source, stage, tags, owner, notes, consent_email, do_not_call.");

        List<Row> out = new ArrayList<>();
        Map<String, Integer> seenInFile = new HashMap<>();
        synchronized (store) {
            for (int r = 1; r < rows.size(); r++) {
                List<String> cells = rows.get(r);
                Map<String, Object> b = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(cells.size(), fieldOf.length); i++) {
                    if (fieldOf[i] != null && !cells.get(i).isBlank()) b.put(fieldOf[i], cells.get(i).trim());
                }
                List<String> errors = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                // friendly values
                if (b.get("type") instanceof String t) b.put("type", t.toUpperCase(Locale.ROOT).startsWith("B") ? "BUSINESS" : t.toUpperCase(Locale.ROOT).startsWith("I") ? "INDIVIDUAL" : t);
                if (b.get("source") instanceof String s) {
                    String match = CustomerService.SOURCES.stream().filter(x -> x.equalsIgnoreCase(s)).findFirst().orElse(null);
                    if (match == null) { warnings.add("Source \"" + s + "\" not known – saved as Other."); b.put("source", "Other"); }
                    else b.put("source", match);
                }
                if (b.get("owner") instanceof String o) {
                    User u = store.userByName(o);
                    if (u == null || !u.active) { warnings.add("Owner \"" + o + "\" not found – you will be the owner."); b.remove("owner"); }
                    else b.put("ownerId", u.id);
                    b.remove("owner");
                }
                CustomerService.Fields f = null;
                try {
                    f = customers.readFields(actor, b, null);
                } catch (Problem p) {
                    Object data = p.data;
                    if (data instanceof Map<?, ?> m && m.get("errors") instanceof List<?> l) l.forEach(e -> errors.add(String.valueOf(e)));
                    else errors.add(p.getMessage());
                }
                String dup = null;
                if (f != null) {
                    for (String key : new String[]{f.email() == null ? null : "e:" + f.email(), f.phone() == null ? null : "p:" + f.phone()}) {
                        if (key == null) continue;
                        Integer first = seenInFile.putIfAbsent(key, r + 1);
                        if (first != null) { errors.add("Same " + (key.startsWith("e:") ? "e-mail" : "phone") + " as line " + first + " of this file."); break; }
                    }
                    int best = 0;
                    for (Customer c : store.customers.values()) {
                        if (!c.visible() || c.erased) continue;
                        Duplicates.Match m = Duplicates.compare(f.name(), f.company(), f.email(), f.phone(), f.city(),
                                c.name, c.company, c.email, c.phone, c.city);
                        if (m.score() >= 95) {
                            errors.add("Already saved as " + (CustomerService.canSee(actor, c) ? c.id + " – " + c.name : "a customer") + " (" + String.join(", ", m.reasons()).toLowerCase() + ").");
                            dup = c.id;
                            break;
                        }
                        if (m.score() >= Duplicates.SHOW_FROM && m.score() > best) {
                            best = m.score();
                            dup = c.id;
                            warnings.removeIf(w -> w.startsWith("Maybe the same"));
                            warnings.add("Maybe the same as " + c.id + " – " + c.name + " (" + String.join(", ", m.reasons()).toLowerCase() + ").");
                        }
                    }
                }
                out.add(new Row(r + 1, b, errors.isEmpty() ? f : null, errors, warnings, dup));
            }
        }
        return out;
    }

    public Map<String, Object> preview(User actor, String csv) {
        check(actor, csv);
        List<Row> rows = analyse(actor, csv);
        List<Object> list = new ArrayList<>();
        int ok = 0, warn = 0, bad = 0;
        for (Row r : rows) {
            String status = !r.errors().isEmpty() ? "error" : !r.warnings().isEmpty() ? "warning" : "ok";
            if (status.equals("ok")) ok++; else if (status.equals("warning")) warn++; else bad++;
            list.add(Json.obj("line", r.line(), "status", status, "name", r.body().get("name"),
                    "company", r.body().get("company"), "email", r.body().get("email"), "phone", r.body().get("phone"),
                    "city", r.body().get("city"), "stage", r.fields() == null ? r.body().get("stage") : r.fields().stage().label,
                    "messages", concat(r.errors(), r.warnings())));
        }
        return Json.obj("rows", list, "ok", ok, "warnings", warn, "errors", bad, "total", rows.size());
    }

    public Map<String, Object> commit(User actor, String csv, boolean skipPossibleDuplicates) {
        check(actor, csv);
        synchronized (store) {
            List<Row> rows = analyse(actor, csv);
            int added = 0, skipped = 0;
            List<String> ids = new ArrayList<>();
            for (Row r : rows) {
                if (r.fields() == null || (skipPossibleDuplicates && r.duplicateOf() != null)) { skipped++; continue; }
                Customer c = customers.newCustomer(r.fields());
                store.customers.put(c.id, c);
                ids.add(c.id);
                added++;
            }
            store.log(actor.id, "customer.import", added + " customers", "added " + added + ", skipped " + skipped
                    + (ids.isEmpty() ? "" : " (" + ids.get(0) + (ids.size() > 1 ? " to " + ids.get(ids.size() - 1) : "") + ")"));
            store.save("customers", "audit");
            return Json.obj("added", added, "skipped", skipped, "firstId", ids.isEmpty() ? null : ids.get(0));
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
