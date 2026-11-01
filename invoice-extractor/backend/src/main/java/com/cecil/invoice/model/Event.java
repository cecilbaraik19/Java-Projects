package com.cecil.invoice.model;

/** One line of the history log: who did what, and when. */
public class Event {
    public String id;
    public String at;
    public String user;
    public String action;
    public String detail;
}
