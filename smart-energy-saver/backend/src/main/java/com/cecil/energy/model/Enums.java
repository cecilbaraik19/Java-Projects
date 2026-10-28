package com.cecil.energy.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All fixed lists used by the app, kept in one file so they are easy to find. */
public final class Enums {

    private Enums() { }

    /** Who can do what. */
    public enum Role {
        /** Manages people and everything else. */
        ADMIN("Admin"),
        /** Energy manager: edits appliances, rates (tariff), targets and applies savings tips. */
        MANAGER("Energy manager"),
        /** Family member / staff: switches appliances on and off and logs usage. */
        MEMBER("Member");

        public final String label;
        Role(String label) { this.label = label; }

        public boolean canManage() { return this == ADMIN || this == MANAGER; }
    }

    /**
     * Kinds of appliances.
     * shiftable  = the job can easily be done at another time of day (good for avoiding peak hours).
     * upgradeCost = rough price of a new 5-star model in rupees (used for the payback time of an upgrade tip).
     */
    public enum ApplianceType {
        AIR_CONDITIONER("Air conditioner", false, 42000),
        FAN("Fan", false, 3500),
        LIGHT("Light", false, 150),
        REFRIGERATOR("Refrigerator", false, 28000),
        TELEVISION("Television", false, 35000),
        SET_TOP_BOX("Set-top box", false, 0),
        COMPUTER("Computer", false, 0),
        LAPTOP("Laptop", false, 0),
        ROUTER("Wi-Fi router", false, 0),
        WATER_HEATER("Water heater (geyser)", true, 9000),
        WASHING_MACHINE("Washing machine", true, 30000),
        WATER_PUMP("Water pump", true, 9000),
        MICROWAVE("Microwave", false, 0),
        COOKTOP("Induction cooktop", false, 0),
        MIXER("Mixer grinder", false, 0),
        WATER_PURIFIER("Water purifier", false, 0),
        IRON("Iron", true, 0),
        EV_CHARGER("EV charger", true, 0),
        GAMING("Gaming console", false, 0),
        PRINTER("Printer", false, 0),
        OTHER("Other", false, 0);

        public final String label;
        public final boolean shiftable;
        public final int upgradeCost;

        ApplianceType(String label, boolean shiftable, int upgradeCost) {
            this.label = label;
            this.shiftable = shiftable;
            this.upgradeCost = upgradeCost;
        }
    }

    /** Where a usage record came from. */
    public enum UsageSource {
        /** Demo data made on the first run. */
        DEMO,
        /** Filled in automatically from the appliance's normal daily hours. */
        ESTIMATE,
        /** Measured by switching the appliance on and off in the app (like a smart plug). */
        LIVE,
        /** Typed in by a person. */
        MANUAL
    }

    /** What a person did with a savings tip. */
    public enum TipStatus { NEW, APPLIED, DISMISSED }

    /** How hard a savings tip is. */
    public enum Effort {
        EASY("Easy - free"),
        LOW_COST("Small cost"),
        INVESTMENT("Buy new");

        public final String label;
        Effort(String label) { this.label = label; }
    }

    /** Lists for the frontend's dropdowns: [{id, label, ...}]. */
    public static Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> types = new ArrayList<>();
        for (ApplianceType t : ApplianceType.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", t.name());
            x.put("label", t.label);
            x.put("shiftable", t.shiftable);
            types.add(x);
        }
        m.put("applianceTypes", types);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Role r : Role.values()) roles.add(Map.of("id", r.name(), "label", r.label));
        m.put("roles", roles);
        List<Map<String, Object>> efforts = new ArrayList<>();
        for (Effort e : Effort.values()) efforts.add(Map.of("id", e.name(), "label", e.label));
        m.put("efforts", efforts);
        List<Map<String, Object>> sources = new ArrayList<>();
        for (UsageSource s : UsageSource.values()) sources.add(Map.of("id", s.name(), "label", s.name().charAt(0) + s.name().substring(1).toLowerCase()));
        m.put("sources", sources);
        return m;
    }

    /** Reads an enum value by name, or returns def when the text is empty or unknown. */
    public static <E extends Enum<E>> E parse(Class<E> type, String text, E def) {
        if (text == null || text.isBlank()) return def;
        try {
            return Enum.valueOf(type, text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
