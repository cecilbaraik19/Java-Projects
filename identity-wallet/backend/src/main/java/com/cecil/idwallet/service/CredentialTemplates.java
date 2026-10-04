package com.cecil.idwallet.service;

import java.util.List;
import java.util.Optional;

/** Ready-made credential types shown in the issuer portal. Issuers may also send any custom type. */
public final class CredentialTemplates {

    public record Field(String name, String label, String input, boolean required) {}

    public record Template(String type, String label, String description, int validityDays, List<Field> fields) {}

    private static Field req(String n, String l, String i) { return new Field(n, l, i, true); }
    private static Field opt(String n, String l, String i) { return new Field(n, l, i, false); }

    public static final List<Template> ALL = List.of(
            new Template("NATIONAL_ID", "National Identity Card", "Government-issued proof of identity", 3650, List.of(
                    req("fullName", "Full name", "text"), req("dateOfBirth", "Date of birth", "date"),
                    req("gender", "Gender", "text"), req("idNumber", "ID number", "text"),
                    req("nationality", "Nationality", "text"), opt("address", "Address", "text"))),
            new Template("DRIVING_LICENSE", "Driving Licence", "Permission to drive listed vehicle classes", 3650, List.of(
                    req("fullName", "Full name", "text"), req("dateOfBirth", "Date of birth", "date"),
                    req("licenseNumber", "Licence number", "text"), req("vehicleClasses", "Vehicle classes", "text"),
                    opt("bloodGroup", "Blood group", "text"), opt("address", "Address", "text"))),
            new Template("STUDENT_ID", "Student ID", "Proof of enrolment at an institution", 1460, List.of(
                    req("fullName", "Full name", "text"), req("studentId", "Student / roll number", "text"),
                    req("program", "Programme", "text"), req("institution", "Institution", "text"),
                    req("enrollmentYear", "Enrolment year", "number"), opt("dateOfBirth", "Date of birth", "date"))),
            new Template("EMPLOYMENT", "Employment Certificate", "Proof of current employment", 365, List.of(
                    req("fullName", "Full name", "text"), req("employeeId", "Employee ID", "text"),
                    req("designation", "Designation", "text"), req("department", "Department", "text"),
                    req("joiningDate", "Joining date", "date"))),
            new Template("HEALTH_INSURANCE", "Health Insurance Card", "Proof of active health cover", 365, List.of(
                    req("fullName", "Full name", "text"), req("policyNumber", "Policy number", "text"),
                    req("insurer", "Insurer", "text"), req("coverAmount", "Cover amount", "text"),
                    opt("dateOfBirth", "Date of birth", "date"))),
            new Template("ADDRESS_PROOF", "Address Proof", "Verified residential address", 365, List.of(
                    req("fullName", "Full name", "text"), req("address", "Address", "text"),
                    req("city", "City", "text"), req("pinCode", "PIN code", "text"), req("state", "State", "text")))
    );

    public static Optional<Template> find(String type) {
        return ALL.stream().filter(t -> t.type().equals(type)).findFirst();
    }

    /** Friendly labels for claims, including derived ones. */
    public static String label(String claim) {
        if (claim.equals("age_over_18")) return "Age over 18";
        if (claim.equals("age_over_21")) return "Age over 21";
        return ALL.stream().flatMap(t -> t.fields().stream()).filter(f -> f.name().equals(claim))
                .map(Field::label).findFirst().orElse(claim);
    }
}
