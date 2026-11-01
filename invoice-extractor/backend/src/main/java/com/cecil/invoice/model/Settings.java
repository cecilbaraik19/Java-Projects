package com.cecil.invoice.model;

/** Settings the admin can change. Saved in data/settings.json. */
public class Settings {
    public String companyName = "Nova Learning Pvt Ltd";      // our company = the buyer on invoices
    public String companyGstin = "";
    public String companyCity = "Ranchi";

    public boolean autoApprove = true;          // approve without a person when everything is clean
    public double autoApproveConfidence = 0.95; // ...and confidence is at least this
    public double autoApproveMaxTotal = 25000;  // ...and the bill is not bigger than this (big bills always need a person)
    public double amountTolerance = 1.0;        // rupees allowed for rounding in the sums
    public int duplicateDays = 45;              // same vendor + same total within this many days = possible duplicate

    public boolean useClaude = false;           // use Claude (needs ANTHROPIC_API_KEY) for new uploads
    public String claudeModel = "claude-haiku-5-5";
}
