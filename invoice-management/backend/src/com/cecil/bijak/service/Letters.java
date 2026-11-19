package com.cecil.bijak.service;

import com.cecil.bijak.core.Dunning;
import com.cecil.bijak.model.Customer;
import com.cecil.bijak.model.Invoice;
import com.cecil.bijak.model.Mail;
import com.cecil.bijak.model.Payment;
import com.cecil.bijak.model.Settings;
import com.cecil.bijak.util.Money;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The e-mails Bijak writes. Plain text, polite, with every number the customer needs to pay
 * (amount, due date, bank details and the invoice number to write in the payment reference).
 */
public final class Letters {
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private Letters() { }

    public static String date(String iso) { return iso == null ? "" : LocalDate.parse(iso).format(D); }

    private static String hello(Customer c) {
        return "Dear " + (c.contact == null || c.contact.isBlank() ? c.name : c.contact) + ",\n\n";
    }

    private static String bank(Settings s, Invoice i) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nHow to pay\n");
        if (!s.bankAccount.isBlank()) sb.append("  Bank: ").append(s.bankName).append(", A/c ").append(s.bankAccount).append(", IFSC ").append(s.ifsc).append('\n');
        if (!s.upiId.isBlank()) sb.append("  UPI: ").append(s.upiId).append('\n');
        sb.append("  Please write \"").append(i.number).append("\" in the payment reference so we can match it at once.\n");
        return sb.toString();
    }

    private static String bye(Settings s) {
        return "\nWith regards,\nAccounts team\n" + s.companyName + (s.phone.isBlank() ? "" : "\n" + s.phone) + "\n";
    }

    public static Mail invoice(Settings s, Customer c, Invoice i) {
        Mail m = base(c, i, "INVOICE", 0);
        m.subject = "Invoice " + i.number + " from " + s.companyName + " - " + Money.rupees(i.total);
        m.body = hello(c) + "Please find our tax invoice " + i.number + " dated " + date(i.issueDate) + ".\n\n"
                + "  Amount:    Rs. " + Money.format(i.total) + " (" + Money.words(i.total) + ")\n"
                + "  Due date:  " + date(i.dueDate) + "\n"
                + (i.poNumber == null || i.poNumber.isBlank() ? "" : "  Your PO:   " + i.poNumber + "\n")
                + bank(s, i) + bye(s);
        return m;
    }

    public static Mail creditNote(Settings s, Customer c, Invoice cn, Invoice against) {
        Mail m = base(c, cn, "CREDIT_NOTE", 0);
        m.subject = "Credit note " + cn.number + " against " + against.number;
        m.body = hello(c) + "We have issued credit note " + cn.number + " for Rs. " + Money.format(cn.total)
                + " against invoice " + against.number + ".\nReason: " + cn.reason + "\n\n"
                + "The amount has been taken off what you owe" + (cn.balance() > 0 ? "; Rs. " + Money.format(cn.balance())
                + " is kept as a credit for your next invoice." : ".") + "\n" + bye(s);
        return m;
    }

    public static Mail receipt(Settings s, Customer c, Payment p, String allocated) {
        Mail m = new Mail();
        m.kind = "RECEIPT";
        m.to = c.email;
        m.customerId = c.id;
        m.subject = "Payment received - receipt " + p.number + " - " + Money.rupees(p.amount);
        m.body = hello(c) + "Thank you. We have received Rs. " + Money.format(p.amount) + " by " + p.mode
                + " on " + date(p.date) + (p.reference == null || p.reference.isBlank() ? "" : " (ref. " + p.reference + ")") + ".\n"
                + (p.tds > 0 ? "TDS of Rs. " + Money.format(p.tds) + " deducted by you is also counted. Please send the TDS certificate (Form 16A).\n" : "")
                + "\n" + allocated + bye(s);
        return m;
    }

    /** Reminder of the given level (1-5). interest = interest so far (only mentioned when > 0). */
    public static Mail reminder(Settings s, Customer c, Invoice i, int level, long daysLate, long interest) {
        Mail m = base(c, i, "REMINDER", level);
        String bal = "Rs. " + Money.format(i.balance());
        m.subject = Dunning.TITLES[level] + ": invoice " + i.number + " - " + Money.rupees(i.balance())
                + (daysLate > 0 ? " (" + daysLate + " days overdue)" : daysLate == 0 ? " due today" : " due " + date(i.dueDate));
        String text = switch (level) {
            case 1 -> "This is a friendly reminder that invoice " + i.number + " for " + bal + " is due on " + date(i.dueDate)
                    + ". If you have already arranged the payment, thank you - please ignore this e-mail.\n";
            case 2 -> "Invoice " + i.number + " for " + bal + " is due for payment today (" + date(i.dueDate) + ").\n";
            case 3 -> "We have not yet received payment for invoice " + i.number + " (" + bal + "), which was due on "
                    + date(i.dueDate) + " - " + daysLate + " days ago. Could you please let us know when we can expect it?\n";
            case 4 -> "This is our second notice for invoice " + i.number + ". " + bal + " is now " + daysLate
                    + " days overdue. Please arrange the payment this week, or tell us if anything on the invoice is not right.\n";
            default -> "FINAL NOTICE. Invoice " + i.number + " (" + bal + ") is " + daysLate + " days overdue. "
                    + "Please pay within 7 days. If we do not hear from you, we will have to stop further supplies on credit.\n";
        };
        if (interest > 0) text += "\nAs per our terms, interest @" + Money.percent(s.interestBp) + " a year applies after the due date: Rs. "
                + Money.format(interest) + " so far.\n";
        m.body = hello(c) + text + bank(s, i) + bye(s);
        return m;
    }

    private static Mail base(Customer c, Invoice i, String kind, int level) {
        Mail m = new Mail();
        m.kind = kind;
        m.to = c.email;
        m.customerId = c.id;
        m.invoiceId = i.id;
        m.level = level;
        return m;
    }
}
