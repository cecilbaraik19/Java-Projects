package com.cecil.complaints.store;

import com.cecil.complaints.model.Enums.Category;
import com.cecil.complaints.model.Solution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Starter knowledge base and demo complaints so the app is useful on first run.
 */
public final class SeedData {

    private SeedData() {
    }

    /** One demo complaint before AI analysis. */
    public record DemoComplaint(String name, String email, String orderId, String channel, String tier,
                                String subject, String description, int daysAgo,
                                String finalStatus, String applySolution, Integer rating) {
    }

    public static List<Solution> solutions() {
        List<Solution> list = new ArrayList<>();
        list.add(sol("SOL-001", "Refund a duplicate / double charge", Category.BILLING,
                List.of("charged twice", "double charge", "duplicate payment", "deducted twice", "extra debit", "bank statement"),
                List.of("Check the payment gateway log for two successful transactions on the same order.",
                        "If confirmed, start a reversal of the duplicate transaction.",
                        "Share the reversal reference number with the customer.",
                        "Tell the customer the money returns in 5-7 business days."),
                "I checked our payment records and confirmed that you were charged twice for {orderId}. "
                        + "I have started a reversal of the extra charge. The amount will be back in your account "
                        + "within 5-7 business days, depending on your bank."));
        list.add(sol("SOL-002", "Correct an overcharge or wrong invoice amount", Category.BILLING,
                List.of("overcharged", "wrong amount", "invoice", "hidden charges", "extra fees", "bill"),
                List.of("Compare the invoice with the price shown at checkout.",
                        "Remove incorrect fees and issue a corrected invoice.",
                        "Refund the difference to the original payment method."),
                "I compared your invoice with the price at checkout and found the difference. I have issued a "
                        + "corrected invoice and the extra amount will be refunded to your original payment method "
                        + "within 5-7 business days."));
        list.add(sol("SOL-003", "Payment deducted but order not placed", Category.BILLING,
                List.of("payment deducted", "order not placed", "upi failed", "transaction failed", "money debited"),
                List.of("Find the payment using the transaction ID / UTR number.",
                        "If the order was not created, trigger an automatic refund.",
                        "Offer to place the order manually at the same price."),
                "I found your payment and can confirm the order was not created because of a technical failure. "
                        + "The amount has been marked for automatic refund (usually 3-5 business days). If you still "
                        + "want the item, I can place the order for you at the same price - just reply to this message."));
        list.add(sol("SOL-004", "Apply a missed coupon or promo discount", Category.BILLING,
                List.of("coupon", "promo code", "discount not applied", "offer", "cashback"),
                List.of("Check that the coupon was valid for the order on the order date.",
                        "If valid, refund the discount amount as a credit or to the payment method."),
                "I checked the offer and it was valid for your order. I have refunded the discount amount that "
                        + "should have been applied."));
        list.add(sol("SOL-005", "Track and speed up a delayed delivery", Category.DELIVERY,
                List.of("late", "delayed", "not delivered", "tracking", "stuck in transit", "courier", "shipment"),
                List.of("Check live tracking with the courier partner.",
                        "Raise a priority escalation with the courier hub.",
                        "Share a new expected delivery date with the customer.",
                        "If later than 7 days past promise, offer a delivery-fee refund or voucher."),
                "I have checked with our courier partner and raised a priority request for {orderId}. "
                        + "I will share the updated delivery date with you, and as an apology for the delay I have "
                        + "added a voucher to your account."));
        list.add(sol("SOL-006", "Investigate a 'delivered but not received' package", Category.DELIVERY,
                List.of("shows delivered", "never received", "not received", "missing package", "parcel lost", "wrong address"),
                List.of("Get proof of delivery (GPS location, photo, signature) from the courier.",
                        "Ask the customer to check with neighbours and building security.",
                        "Open a lost-package investigation (48 hours).",
                        "If not found, send a free replacement or full refund."),
                "I have opened an investigation with the courier for {orderId} and asked for the proof of "
                        + "delivery. Please check with neighbours or your building security in the meantime. If the "
                        + "package is not found within 48 hours, we will send a free replacement or a full refund - your choice."));
        list.add(sol("SOL-007", "Send missing items from a partial delivery", Category.DELIVERY,
                List.of("items missing", "partial", "only part", "incomplete order", "missing item"),
                List.of("Check the packing record / weight for the shipment.",
                        "Ship the missing items with express delivery at no cost."),
                "I am sorry that items were missing from {orderId}. I have arranged for the missing items to be "
                        + "shipped to you by express delivery at no extra cost."));
        list.add(sol("SOL-008", "Handle rude or unprofessional delivery staff", Category.DELIVERY,
                List.of("delivery person rude", "delivery boy", "extra money", "misbehaved", "left outside"),
                List.of("Record the incident with time and order number.",
                        "Report the delivery agent to the courier partner for action.",
                        "Refund any extra money that was wrongly collected."),
                "I am sorry about the behaviour of the delivery agent. I have reported this to our courier partner "
                        + "for strict action, and any extra amount that was collected will be refunded to you."));
        list.add(sol("SOL-009", "Replace a damaged or defective product", Category.PRODUCT_QUALITY,
                List.of("damaged", "broken", "defective", "cracked", "not working", "stopped working", "faulty"),
                List.of("Ask the customer for 2-3 photos or a short video of the problem.",
                        "Approve a free replacement and schedule a pickup of the faulty item.",
                        "If out of stock, offer a full refund."),
                "I am sorry the product reached you in this condition. I have approved a free replacement for "
                        + "{orderId} and scheduled a pickup of the faulty item. If you can reply with a photo of the "
                        + "issue, it will help us speed this up."));
        list.add(sol("SOL-010", "Exchange a wrong item (size, colour or model)", Category.PRODUCT_QUALITY,
                List.of("wrong item", "different colour", "wrong size", "wrong model", "not what i ordered"),
                List.of("Confirm the ordered item vs. the delivered item.",
                        "Arrange a free exchange with pickup and delivery on the same visit."),
                "I can see that the item you received is not what you ordered. I have arranged a free exchange: "
                        + "our courier will bring the correct item and collect the wrong one on the same visit."));
        list.add(sol("SOL-011", "Counterfeit or not-as-described product", Category.PRODUCT_QUALITY,
                List.of("fake", "counterfeit", "not original", "does not match description", "misleading"),
                List.of("Escalate to the seller quality team.",
                        "Issue a full refund with free return pickup.",
                        "Flag the listing for review."),
                "Thank you for reporting this. We take product authenticity seriously. I have issued a full refund "
                        + "with free return pickup and flagged the listing for our quality team to review."));
        list.add(sol("SOL-021", "Product safety incident (smoke, fire, shock, injury)", Category.PRODUCT_QUALITY,
                List.of("smoke", "burning smell", "fire", "overheating", "sparks", "electric shock", "injury", "dangerous", "unsafe"),
                List.of("Tell the customer to stop using and unplug the product immediately.",
                        "Escalate to the product safety team within 1 hour and log the incident.",
                        "Arrange an urgent pickup of the unit for inspection.",
                        "Issue a full refund or replacement - customer's choice - without waiting for inspection.",
                        "If anyone was hurt, offer to cover medical costs and involve the legal/compliance team."),
                "Your safety matters most to us. Please stop using the product and keep it unplugged. I have "
                        + "reported this to our product safety team as an urgent incident and arranged a pickup of the "
                        + "unit. You will receive a full refund or a replacement - whichever you prefer - without waiting "
                        + "for the inspection. If anyone was hurt, please tell us so we can support you."));
        list.add(sol("SOL-012", "Speed up a pending refund", Category.REFUND,
                List.of("refund pending", "refund not received", "money not credited", "returned item", "refund status"),
                List.of("Check that the return reached the warehouse and passed inspection.",
                        "If done, push the refund manually and get the bank reference (ARN) number.",
                        "Share the ARN so the customer can track it with their bank."),
                "I have checked your return and pushed the refund through manually. You will receive a bank "
                        + "reference (ARN) number shortly, which you can use to track the amount with your bank. "
                        + "It usually appears within 3-5 business days."));
        list.add(sol("SOL-013", "Approve a rejected return or exchange", Category.REFUND,
                List.of("return rejected", "exchange denied", "return window", "pickup not scheduled"),
                List.of("Review why the return was rejected.",
                        "If the item is eligible, override and approve the return.",
                        "Schedule a pickup within 48 hours."),
                "I reviewed your return request and have approved it. A pickup will be scheduled within the next "
                        + "48 hours, and your refund will start once the item is collected."));
        list.add(sol("SOL-014", "Secure a hacked or compromised account", Category.ACCOUNT,
                List.of("hacked", "unauthorized", "someone else", "login", "security", "fraud", "changed without permission"),
                List.of("Lock the account and log out all sessions immediately.",
                        "Cancel any unauthorized orders and reverse payments.",
                        "Help the customer reset the password and enable two-factor authentication.",
                        "Escalate to the fraud team for investigation."),
                "Your security is our top priority. I have locked your account, signed out all devices and "
                        + "cancelled the orders you did not place. Our fraud team is reviewing the activity. Please "
                        + "reset your password using the link we sent and turn on two-factor authentication."));
        list.add(sol("SOL-015", "Fix login, OTP or password reset problems", Category.ACCOUNT,
                List.of("cannot log in", "otp not received", "password reset", "locked out", "verification"),
                List.of("Verify the customer's identity.",
                        "Check the SMS/email gateway logs for failed OTPs.",
                        "Reset the login manually and send a new reset link."),
                "I have verified your account and sent a fresh password reset link to your registered email. "
                        + "If the OTP still does not arrive, please reply and I will reset your login manually."));
        list.add(sol("SOL-016", "Troubleshoot app crashes and errors", Category.TECHNICAL,
                List.of("app crashing", "error", "not loading", "freezes", "bug", "blank screen", "after update"),
                List.of("Ask for device model, OS version and app version.",
                        "Suggest clearing cache / reinstalling the latest version.",
                        "If it continues, log a bug ticket with the engineering team and share the ticket number."),
                "Sorry for the trouble with the app. Please update to the latest version and clear the app cache "
                        + "(Settings > Apps > Clear cache). If the problem continues, reply with your phone model and "
                        + "app version - I have already logged this with our engineering team."));
        list.add(sol("SOL-017", "Restore internet / connectivity service", Category.TECHNICAL,
                List.of("internet not working", "wifi", "connection dropping", "no signal", "network", "outage"),
                List.of("Check for a known outage in the customer's area.",
                        "Run a remote line test and reset the connection.",
                        "Book a technician visit if the line test fails.",
                        "Credit the bill for outage days over 24 hours."),
                "I ran a remote test on your connection and reset it from our side. If the issue continues, a "
                        + "technician visit has been booked for you. You will also receive a bill credit for the "
                        + "days you were without service."));
        list.add(sol("SOL-018", "Service recovery for poor support experience", Category.SERVICE,
                List.of("rude agent", "no response", "ignored", "on hold", "callback", "ticket closed", "unprofessional"),
                List.of("Apologise sincerely and take ownership of the case.",
                        "Review the call/chat recording and share feedback with the team lead.",
                        "Give the customer a direct contact and a fixed callback time.",
                        "Offer a goodwill voucher."),
                "I am sorry that our team did not give you the help you deserved. I am now your single point of "
                        + "contact for this case and have shared your feedback with the team lead. I have also added "
                        + "a goodwill voucher to your account."));
        list.add(sol("SOL-019", "Reschedule a missed technician visit", Category.SERVICE,
                List.of("technician did not come", "missed appointment", "visit", "no show", "scheduled"),
                List.of("Apologise and book the earliest priority slot.",
                        "Confirm the slot by SMS and email.",
                        "Waive the visit charge."),
                "I am sorry that the technician did not arrive as promised. I have booked a priority slot for you "
                        + "and waived the visit charge. You will receive the confirmation by SMS and email."));
        list.add(sol("SOL-020", "Acknowledge general feedback", Category.OTHER,
                List.of("feedback", "suggestion", "general", "question"),
                List.of("Thank the customer and pass the feedback to the right team."),
                "Thank you for taking the time to share this. I have passed your feedback to the relevant team."));
        return list;
    }

    private static Solution sol(String id, String title, Category cat, List<String> keywords, List<String> steps,
                                String template) {
        Solution s = new Solution();
        s.id = id;
        s.title = title;
        s.category = cat;
        s.keywords = new ArrayList<>(keywords);
        s.steps = new ArrayList<>(steps);
        s.responseTemplate = template;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;
        return s;
    }

    public static List<DemoComplaint> complaints() {
        return List.of(
                new DemoComplaint("Ananya Sharma", "ananya.sharma@example.com", "ORD-55821", "EMAIL", "PREMIUM",
                        "Charged twice for my order",
                        "I was charged twice for the same order. My bank statement shows two debits of Rs 2,499. "
                                + "Please refund the extra payment.", 9, "RESOLVED", "SOL-001", 5),
                new DemoComplaint("Rohit Verma", "rohit.v@example.com", "ORD-55902", "WEB", "STANDARD",
                        "Package shows delivered but not received",
                        "The tracking says my parcel was delivered yesterday but I never received it. Nobody came "
                                + "to my house. Very disappointed.", 8, "RESOLVED", "SOL-006", 4),
                new DemoComplaint("Meera Nair", "meera.nair@example.com", "ORD-56011", "CHAT", "STANDARD",
                        "Phone screen cracked on arrival",
                        "The phone arrived with a cracked screen and the box was damaged. I want a replacement.",
                        7, "RESOLVED", "SOL-009", 5),
                new DemoComplaint("Arjun Singh", "arjun.singh@example.com", "ORD-56044", "PHONE", "STANDARD",
                        "Refund still pending after return",
                        "I returned the shoes 3 weeks ago and the refund is still pending. Money not credited yet.",
                        6, "RESOLVED", "SOL-012", 4),
                new DemoComplaint("Kavya Reddy", "kavya.r@example.com", null, "EMAIL", "VIP",
                        "Account hacked - orders I did not place!",
                        "Someone hacked my account and placed 3 orders using my saved card. This is fraud! "
                                + "Please block my account IMMEDIATELY.", 5, "RESOLVED", "SOL-014", 5),
                new DemoComplaint("Vikram Patel", "vikram.p@example.com", "ORD-56102", "SOCIAL", "STANDARD",
                        "Worst delivery experience ever",
                        "My order is 10 days late and the courier keeps saying out for delivery. This is the worst "
                                + "service. I will post about this on Twitter if not fixed today!!!", 3, "IN_PROGRESS", null, null),
                new DemoComplaint("Sneha Gupta", "sneha.g@example.com", "ORD-56133", "WEB", "STANDARD",
                        "App crashes at checkout",
                        "The app keeps crashing every time I try to pay. I updated it but still the same error.",
                        1, "OPEN", null, null),
                new DemoComplaint("Rahul Mehta", "rahul.mehta@example.com", "ORD-56150", "EMAIL", "PREMIUM",
                        "Wrong size delivered",
                        "I ordered a size M shirt but received XL. Different colour too. Please exchange it.",
                        1, "OPEN", null, null),
                new DemoComplaint("Pooja Iyer", "pooja.iyer@example.com", null, "PHONE", "STANDARD",
                        "Support agent was rude and hung up",
                        "I called customer care three times. The agent was rude and disconnected my call. "
                                + "Nobody called back as promised. Totally unacceptable.", 1, "OPEN", null, null),
                new DemoComplaint("Imran Khan", "imran.k@example.com", "ORD-56177", "WEB", "STANDARD",
                        "Heater gave off smoke",
                        "The room heater I bought started giving off smoke and a burning smell on the first use. "
                                + "This is dangerous, my child could have been injured.", 1, "OPEN", null, null),
                new DemoComplaint("Divya Menon", "divya.menon@example.com", "ORD-56190", "EMAIL", "STANDARD",
                        "Coupon not applied",
                        "I used the code SAVE20 but the discount was not applied and I was charged full price.",
                        0, "OPEN", null, null),
                new DemoComplaint("Sanjay Das", "sanjay.das@example.com", null, "CHAT", "STANDARD",
                        "OTP not received",
                        "I cannot log in because the OTP never arrives on my phone. Tried many times.",
                        0, "OPEN", null, null)
        );
    }
}
