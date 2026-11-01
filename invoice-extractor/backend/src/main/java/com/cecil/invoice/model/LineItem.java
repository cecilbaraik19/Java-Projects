package com.cecil.invoice.model;

/** One row of the items table on an invoice. */
public class LineItem {
    public String description = "";
    public String hsn = "";       // HSN code (goods) or SAC code (services)
    public double qty;
    public double rate;
    public double amount;
    public int line = -1;         // line of the invoice text
    public boolean mathOk;        // qty x rate = amount (within a rupee)
}
