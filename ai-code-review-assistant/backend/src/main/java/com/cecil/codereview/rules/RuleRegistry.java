package com.cecil.codereview.rules;

import com.cecil.codereview.analyzer.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The list of all built-in rules. */
public final class RuleRegistry {
    private RuleRegistry() { }

    public static List<Rule> allRules() {
        List<Rule> rules = new ArrayList<>();
        rules.addAll(SecurityRules.all());
        rules.addAll(BugRules.all());
        rules.addAll(QualityRules.all());
        return Collections.unmodifiableList(rules);
    }

    public static Rule find(String id) {
        for (Rule r : allRules()) {
            if (r.id().equalsIgnoreCase(id)) return r;
        }
        return null;
    }
}
