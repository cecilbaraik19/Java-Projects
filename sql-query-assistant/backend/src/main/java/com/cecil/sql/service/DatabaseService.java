package com.cecil.sql.service;

import com.cecil.sql.ai.Lexicon;
import com.cecil.sql.db.*;
import com.cecil.sql.model.Role;
import com.cecil.sql.model.User;
import com.cecil.sql.store.DataStore;
import com.cecil.sql.util.Csv;
import com.cecil.sql.util.Json;

import java.util.*;

/** Keeps the databases in memory, decides who can see which one, and turns uploaded CSV files into tables. */
public final class DatabaseService {
    public static final int MAX_UPLOAD_ROWS = 20_000;
    public static final int MAX_UPLOAD_COLUMNS = 40;
    public static final int MAX_TABLES_PER_USER = 10;

    private final DataStore store;
    private final Map<String, Database> dbs = new LinkedHashMap<>();
    private final Map<String, Lexicon> lexicons = new HashMap<>();

    public DatabaseService(DataStore store, List<Database> loaded) {
        this.store = store;
        for (Database d : loaded) dbs.put(d.id, d);
    }

    public static SqlExecutor.AccessPolicy policyFor(User u) {
        return u != null && u.role == Role.VIEWER ? SqlExecutor.AccessPolicy.NO_PRIVATE : SqlExecutor.AccessPolicy.ALL;
    }

    public synchronized List<Database> visible(User u) {
        List<Database> l = new ArrayList<>();
        for (Database d : dbs.values()) if (d.owner == null || d.owner.equals(u.username)) l.add(d);
        return l;
    }

    /** The database if this user may use it, else an error (same message whether it exists or not). */
    public synchronized Database get(User u, String id) {
        Database d = id == null ? null : dbs.get(id);
        if (d == null || (d.owner != null && !d.owner.equals(u.username))) throw new IllegalArgumentException("No such database.");
        return d;
    }

    public synchronized Lexicon lexicon(Database d) {
        return lexicons.computeIfAbsent(d.id, k -> new Lexicon(d));
    }

    public Map<String, Object> summary(Database d) {
        List<Object> tables = new ArrayList<>();
        for (Table t : d.tables()) tables.add(Json.map("name", t.schema.name, "rows", t.size(), "columns", t.schema.columns.size()));
        return Json.map("id", d.id, "title", d.title, "description", d.description, "mine", d.owner != null,
                "tables", tables, "rows", d.rowCount());
    }

    /** Tables, columns, links and a few sample rows, for the "Card catalogue" page. Private values are hidden from Viewers. */
    public Map<String, Object> schema(User u, Database d) {
        SqlExecutor.AccessPolicy p = policyFor(u);
        List<Object> tables = new ArrayList<>();
        for (Table t : d.tables()) {
            TableSchema s = t.schema;
            List<Object> cols = new ArrayList<>();
            for (Column c : s.columns) {
                String link = null;
                for (TableSchema.ForeignKey f : s.foreignKeys) if (f.column().equalsIgnoreCase(c.name)) link = f.refTable() + "." + f.refColumn();
                cols.add(Json.map("name", c.name, "type", c.type, "label", c.label, "synonyms", c.synonyms,
                        "private", c.sensitive, "hidden", !p.canRead(s, c), "unit", c.unit,
                        "key", c.name.equalsIgnoreCase(s.primaryKey), "link", link, "valueWords", c.valueWords.keySet()));
            }
            List<Object> sample = new ArrayList<>();
            for (int r = 0; r < Math.min(5, t.size()); r++) {
                Object[] row = t.rows().get(r);
                List<Object> vals = new ArrayList<>();
                for (int c = 0; c < row.length; c++) vals.add(p.canRead(s, s.columns.get(c)) ? row[c] : "•••");
                sample.add(vals);
            }
            tables.add(Json.map("name", s.name, "alias", s.alias, "description", s.description, "synonyms", s.synonyms,
                    "rows", t.size(), "columns", cols, "sample", sample, "primaryKey", s.primaryKey));
        }
        return Json.map("id", d.id, "title", d.title, "description", d.description, "mine", d.owner != null, "tables", tables);
    }

    // ------------------------------------------------------------------ uploads
    public synchronized Map<String, Object> upload(User u, String fileName, String csvText) {
        if (!u.can(Role.ANALYST)) throw new SecurityException("Only analysts and admins can add tables.");
        if (csvText == null || csvText.isBlank()) throw new IllegalArgumentException("The file is empty.");
        List<List<String>> lines = Csv.parse(csvText);
        if (lines.size() < 2) throw new IllegalArgumentException("The file needs a header row and at least one data row.");
        if (lines.size() - 1 > MAX_UPLOAD_ROWS) throw new IllegalArgumentException("At most " + MAX_UPLOAD_ROWS + " rows, please.");
        List<String> header = lines.get(0);
        if (header.size() > MAX_UPLOAD_COLUMNS) throw new IllegalArgumentException("At most " + MAX_UPLOAD_COLUMNS + " columns, please.");

        String table = cleanName(fileName == null ? "my_table" : fileName.replaceAll("(?i)\\.csv$", ""));
        if (table.isEmpty()) table = "my_table";
        String id = "my_" + DataStore.safe(u.username);
        Database db = dbs.get(id);
        if (db == null) db = new Database(id, "My uploads", "Tables uploaded by " + u.displayName + ". Only you can see them.", u.username);
        if (db.table(table) == null && db.tables().size() >= MAX_TABLES_PER_USER)
            throw new IllegalArgumentException("You can keep at most " + MAX_TABLES_PER_USER + " uploaded tables. Remove one first.");

        // columns: clean names, guess types from the data
        List<String> names = new ArrayList<>();
        for (int c = 0; c < header.size(); c++) {
            String n = cleanName(header.get(c));
            if (n.isEmpty() || Character.isDigit(n.charAt(0))) n = "col_" + (c + 1) + (n.isEmpty() ? "" : "_" + n);
            String base = n;
            int k = 2;
            while (names.contains(n)) n = base + "_" + k++;
            names.add(n);
        }
        List<Column> cols = new ArrayList<>();
        for (int c = 0; c < names.size(); c++) {
            List<String> vals = new ArrayList<>();
            for (int r = 1; r < lines.size(); r++) vals.add(c < lines.get(r).size() ? lines.get(r).get(c) : null);
            ColumnType type = ColumnType.guess(vals);
            String label = header.get(c).trim().isEmpty() ? names.get(c) : header.get(c).trim();
            boolean priv = names.get(c).matches(".*(email|phone|mobile|password|aadhaar|aadhar|pan|salary|account).*");
            cols.add(new Column(names.get(c), type, label, List.of(), priv, names.get(c).matches(".*(price|amount|salary|fee|cost|total|revenue).*") ? "rupees" : null));
        }
        // primary key, name column, links to other uploaded tables
        String pk = null;
        if (!cols.isEmpty() && cols.get(0).type == ColumnType.INT && cols.get(0).name.endsWith("id")) pk = cols.get(0).name;
        String nameCol = null;
        for (Column c : cols) if (c.type == ColumnType.TEXT && c.name.contains("name") && !c.sensitive) { nameCol = c.name; break; }
        if (nameCol == null) for (Column c : cols) if (c.type == ColumnType.TEXT && !c.sensitive) { nameCol = c.name; break; }
        List<TableSchema.ForeignKey> fks = new ArrayList<>();
        for (Column c : cols) {
            if (c.name.equals(pk)) continue;
            for (Table other : db.tables())
                if (!other.schema.name.equals(table) && other.schema.primaryKey != null && other.schema.primaryKey.equals(c.name))
                    fks.add(new TableSchema.ForeignKey(c.name, other.schema.name, other.schema.primaryKey));
        }
        List<String> display = new ArrayList<>();
        for (Column c : cols) if (!c.sensitive && display.size() < 6) display.add(c.name);
        String singular = table.endsWith("s") && table.length() > 3 ? table.substring(0, table.length() - 1) : table;
        String alias = initials(table, db);
        TableSchema schema = new TableSchema(table, alias, singular.replace('_', ' '), table.replace('_', ' '),
                "Uploaded from " + (fileName == null ? "a CSV file" : fileName) + ".", List.of(), cols, pk, fks, display, nameCol);

        StringBuilder rebuilt = new StringBuilder(Csv.line(names));
        for (int r = 1; r < lines.size(); r++) rebuilt.append(Csv.line(lines.get(r)));
        Table t = Table.fromCsv(schema, rebuilt.toString());
        db.add(t);
        dbs.put(id, db);
        lexicons.remove(id);
        store.saveDatabase(db);
        return Json.map("db", id, "table", table, "rows", t.size(), "columns", cols);
    }

    public synchronized void deleteTable(User u, String dbId, String table) {
        Database d = get(u, dbId);
        if (d.owner == null) throw new SecurityException("Demo tables cannot be removed.");
        if (d.table(table) == null) throw new IllegalArgumentException("No such table.");
        Database fresh = new Database(d.id, d.title, d.description, d.owner);
        for (Table t : d.tables()) if (!t.schema.name.equalsIgnoreCase(table)) fresh.add(t);
        dbs.put(d.id, fresh);
        lexicons.remove(d.id);
        store.deleteTableFile(d, table);
        store.saveDatabase(fresh);
    }

    static String cleanName(String s) {
        String n = s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (n.length() > 40) n = n.substring(0, 40);
        if (SqlLexer.KEYWORDS.contains(n.toUpperCase())) n = n + "_";   // "order" -> "order_" so SQL can still read it
        return n;
    }

    private static String initials(String table, Database db) {
        StringBuilder sb = new StringBuilder();
        for (String p : table.split("_")) if (!p.isEmpty()) sb.append(p.charAt(0));
        String a = sb.length() == 0 ? "t" : sb.toString();
        if (Character.isDigit(a.charAt(0))) a = "t" + a;
        if (SqlLexer.KEYWORDS.contains(a.toUpperCase())) a = a + "1";
        return a;
    }
}
