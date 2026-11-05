package com.cecil.sql.store;

import com.cecil.sql.model.Role;
import com.cecil.sql.model.User;
import com.cecil.sql.util.Clock;
import com.cecil.sql.util.Passwords;

import java.util.List;

/** Demo accounts created on the first run. All people are made up. */
public final class SeedData {
    private SeedData() {}

    public static List<User> users() {
        String now = Clock.now().toString();
        return List.of(
                new User("admin", "Meenakshi Rao (Data Office)", Role.ADMIN, Passwords.hash("admin123"), now),
                new User("neha", "Neha Tirkey (Exam Cell)", Role.ANALYST, Passwords.hash("analyst123"), now),
                new User("arjun", "Arjun Lakra (Hostel Warden)", Role.VIEWER, Passwords.hash("viewer123"), now));
    }

    /** Questions asked on the first run so History and the shelf are not empty. {user, db, question} */
    public static final String[][] FIRST_QUESTIONS = {
            {"neha", "koel_college", "Number of students in each department"},
            {"neha", "koel_college", "Average marks per course"},
            {"neha", "koel_college", "How many students failed"},
            {"neha", "koel_college", "Top 5 students by CGPA"},
            {"neha", "koel_college", "Students with attendance below 60% in Java"},
            {"neha", "ghar_store", "Total sales per category"},
            {"arjun", "koel_college", "How many hostellers are there"},
            {"arjun", "koel_college", "Hostel fee payments that are pending"},
            {"arjun", "koel_college", "Hostellers from Patna"},
            {"admin", "koel_college", "Which department has the most students"},
            {"admin", "koel_college", "Total fees by payment mode"},
            {"admin", "ghar_store", "Top 3 customers by number of orders"},
            {"admin", "ghar_store", "Total revenue from delivered orders"},
    };

    /** Saved cards: {user, db, name, question} */
    public static final String[][] SAVED = {
            {"neha", "koel_college", "Department strength", "Number of students in each department"},
            {"neha", "koel_college", "Failed in any subject", "How many students failed"},
            {"neha", "koel_college", "Low attendance", "Enrollments with attendance below 60%"},
            {"arjun", "koel_college", "Hostel count", "How many hostellers are there"},
            {"arjun", "koel_college", "Pending hostel fees", "Hostel fee payments that are pending"},
            {"admin", "koel_college", "Fee collection by mode", "Total fees by payment mode"},
            {"admin", "ghar_store", "Sales by category", "Total sales per category"},
    };
}
