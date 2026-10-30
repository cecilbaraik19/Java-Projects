package com.cecil.ngo.model;

import com.cecil.ngo.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All the fixed choices used in the app, each with a friendly label for the screen. */
public final class Enums {
    private Enums() {}

    /** Who a person is in the NGO. Each role can do different things (see AuthService.need...). */
    public enum Role {
        ADMIN("Director / Admin"),
        MANAGER("Program Manager"),
        FINANCE("Finance Officer"),
        VOLUNTEER("Volunteer");
        public final String label;
        Role(String l) { label = l; }
    }

    /** The cause a program works for. */
    public enum Cause {
        EDUCATION("Education"), HEALTH("Health"), WATER("Water & Sanitation"), LIVELIHOOD("Livelihood"),
        WOMEN("Women Empowerment"), ENVIRONMENT("Environment"), RELIEF("Disaster Relief");
        public final String label;
        Cause(String l) { label = l; }
    }

    public enum ProgramStatus {
        PLANNED("Planned"), ACTIVE("Active"), ON_HOLD("On hold"), COMPLETED("Completed");
        public final String label;
        ProgramStatus(String l) { label = l; }
    }

    public enum ActivityKind {
        CAMP("Camp"), CLASS("Class / Session"), DRIVE("Drive"), TRAINING("Training"),
        DISTRIBUTION("Distribution"), SURVEY("Survey"), MEETING("Community meeting");
        public final String label;
        ActivityKind(String l) { label = l; }
    }

    public enum ActivityStatus {
        PLANNED("Planned"), DONE("Done"), CANCELLED("Cancelled");
        public final String label;
        ActivityStatus(String l) { label = l; }
    }

    public enum DonorType {
        INDIVIDUAL("Individual"), CORPORATE("Corporate / CSR"), FOUNDATION("Foundation / Trust"), GOVERNMENT("Government grant");
        public final String label;
        DonorType(String l) { label = l; }
    }

    public enum PayMode {
        UPI("UPI"), BANK("Bank transfer"), CHEQUE("Cheque"), CASH("Cash"), CARD("Card");
        public final String label;
        PayMode(String l) { label = l; }
    }

    public enum ExpenseCategory {
        MATERIALS("Materials & supplies"), TRAVEL("Travel"), FOOD("Food & refreshments"), VENUE("Venue & tents"),
        MEDICAL("Medicines & medical"), STAFF("Staff & honorarium"), PRINTING("Printing"), OTHER("Other");
        public final String label;
        ExpenseCategory(String l) { label = l; }
    }

    /** Used for both expense claims and volunteer hours: waiting, accepted or refused. */
    public enum Review {
        PENDING("Waiting"), APPROVED("Approved"), REJECTED("Rejected");
        public final String label;
        Review(String l) { label = l; }
    }

    public enum Gender {
        FEMALE("Female"), MALE("Male"), OTHER("Other");
        public final String label;
        Gender(String l) { label = l; }
    }

    public enum AgeGroup {
        CHILD("Child (0-14)"), YOUTH("Youth (15-24)"), ADULT("Adult (25-59)"), SENIOR("Senior (60+)");
        public final String label;
        AgeGroup(String l) { label = l; }
    }

    /** Reads an enum value sent by the browser, with a clear message if it is wrong. */
    public static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        try { return Enum.valueOf(type, value.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(field + " has an unknown value: " + value); }
    }

    private static List<Map<String, Object>> list(Enum<?>[] values) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Enum<?> e : values) {
            String label;
            try { label = (String) e.getClass().getField("label").get(e); }
            catch (ReflectiveOperationException ex) { label = e.name(); }
            out.add(Json.map("id", e.name(), "label", label));
        }
        return out;
    }

    /** Every list of choices, sent once to the browser to fill drop-downs. */
    public static Map<String, Object> meta() {
        return Json.map(
                "roles", list(Role.values()),
                "causes", list(Cause.values()),
                "programStatuses", list(ProgramStatus.values()),
                "activityKinds", list(ActivityKind.values()),
                "activityStatuses", list(ActivityStatus.values()),
                "donorTypes", list(DonorType.values()),
                "payModes", list(PayMode.values()),
                "expenseCategories", list(ExpenseCategory.values()),
                "reviews", list(Review.values()),
                "genders", list(Gender.values()),
                "ageGroups", list(AgeGroup.values()));
    }
}
