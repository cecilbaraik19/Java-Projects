package com.cecil.edge.service;

import com.cecil.edge.model.Enums.Role;
import com.cecil.edge.model.User;
import com.cecil.edge.util.Json;

import java.util.Map;

/** Input checks shared by the services. Every number and text from a form is checked on the server. */
public final class Check {
    private Check() { }

    public static String text(Map<String, Object> b, String key, String label, int max, boolean required) {
        String v = Json.str(b, key);
        if (v == null) {
            if (required) throw ServiceException.bad(label + " is required");
            return null;
        }
        if (v.length() > max) throw ServiceException.bad(label + " must be at most " + max + " characters");
        for (int i = 0; i < v.length(); i++) {
            if (Character.isISOControl(v.charAt(i)) && v.charAt(i) != '\n') throw ServiceException.bad(label + " has invalid characters");
        }
        return v;
    }

    public static Double number(Map<String, Object> b, String key, String label, double min, double max, boolean required) {
        Double v;
        try {
            v = Json.num(b, key);
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad(label + " must be a number");
        }
        if (v == null) {
            if (required) throw ServiceException.bad(label + " is required");
            return null;
        }
        if (v.isNaN() || v < min || v > max) throw ServiceException.bad(label + " must be between " + fmt(min) + " and " + fmt(max));
        return v;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public static <E extends Enum<E>> E choice(Map<String, Object> b, String key, Class<E> type, String label, boolean required) {
        String v = Json.str(b, key);
        if (v == null) {
            if (required) throw ServiceException.bad(label + " is required");
            return null;
        }
        try {
            return Enum.valueOf(type, v);
        } catch (IllegalArgumentException e) {
            throw ServiceException.bad(label + " is not valid");
        }
    }

    public static void role(User u, Role... allowed) {
        for (Role r : allowed) if (u.role == r) return;
        throw ServiceException.forbidden("You do not have permission to do this");
    }

    /** Engineers and admins can change things; viewers can only look (and acknowledge alerts). */
    public static void canEdit(User u) {
        role(u, Role.ADMIN, Role.ENGINEER);
    }
}
