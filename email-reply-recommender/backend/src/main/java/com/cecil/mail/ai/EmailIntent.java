package com.cecil.mail.ai;

/** The kinds of email the AI can recognise. Each one has its own reply style. */
public enum EmailIntent {
    MEETING_REQUEST("Meeting Request", "Someone wants to schedule a call or meeting"),
    COMPLAINT("Complaint", "The sender is unhappy with a product or service"),
    SUPPORT_ISSUE("Technical Support", "The sender needs help with a technical problem"),
    INFO_REQUEST("Information Request", "The sender is asking for details or documents"),
    FOLLOW_UP("Follow-up", "The sender is checking on an earlier message or task"),
    JOB_APPLICATION("Job / Internship", "Application, interview or hiring related"),
    PAYMENT_INVOICE("Payment / Invoice", "Bills, payments, refunds or pending dues"),
    LEAVE_REQUEST("Leave Request", "Asking for leave or time off"),
    DEADLINE_EXTENSION("Deadline Extension", "Asking for more time to finish something"),
    THANK_YOU("Thank You", "Appreciation or gratitude"),
    SALES_PITCH("Sales / Partnership", "Offers, promotions or partnership proposals");

    private final String label;
    private final String description;

    EmailIntent(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }

    public String getDescription() { return description; }
}
