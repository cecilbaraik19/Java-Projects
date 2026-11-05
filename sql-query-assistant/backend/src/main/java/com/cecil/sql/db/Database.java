package com.cecil.sql.db;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** A named group of tables, like one MySQL database. Read-only once loaded. */
public final class Database {
    public final String id;
    public final String title;
    public final String description;
    public final String owner;      // null = shared demo database; otherwise the username who uploaded it
    private final Map<String, Table> tables = new LinkedHashMap<>();

    public Database(String id, String title, String description, String owner) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.owner = owner;
    }

    public void add(Table t) { tables.put(t.schema.name.toLowerCase(), t); }
    public Table table(String name) { return name == null ? null : tables.get(name.toLowerCase()); }
    public Collection<Table> tables() { return tables.values(); }

    public int rowCount() {
        int n = 0;
        for (Table t : tables.values()) n += t.size();
        return n;
    }
}
