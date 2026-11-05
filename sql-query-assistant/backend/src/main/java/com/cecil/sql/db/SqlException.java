package com.cecil.sql.db;

/** Any problem with a SQL query: bad syntax, unknown column, blocked statement, too big. Message is plain English. */
public class SqlException extends RuntimeException {
    public final int position;   // character position in the SQL text, or -1

    public SqlException(String message) { this(message, -1); }
    public SqlException(String message, int position) {
        super(message);
        this.position = position;
    }
}
