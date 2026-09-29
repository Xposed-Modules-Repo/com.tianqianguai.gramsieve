package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class RuleDraftMatrix {
    private final Map<FilterConfig.RuleTarget, Map<FilterConfig.RuleMode, String>> values =
            new EnumMap<>(FilterConfig.RuleTarget.class);

    public RuleDraftMatrix() {
        for (FilterConfig.RuleTarget target : FilterConfig.RuleTarget.values()) {
            Map<FilterConfig.RuleMode, String> byMode = new EnumMap<>(FilterConfig.RuleMode.class);
            for (FilterConfig.RuleMode mode : FilterConfig.RuleMode.values()) {
                byMode.put(mode, "");
            }
            values.put(target, byMode);
        }
    }

    public static RuleDraftMatrix fromRules(List<FilterConfig.RuleSpec> rules) {
        RuleDraftMatrix matrix = new RuleDraftMatrix();
        if (rules == null) {
            return matrix;
        }
        for (FilterConfig.RuleTarget target : FilterConfig.RuleTarget.values()) {
            for (FilterConfig.RuleMode mode : FilterConfig.RuleMode.values()) {
                matrix.set(target, mode, RuleTextCodec.formatTargeted(rules, mode, target));
            }
        }
        return matrix;
    }

    public String get(FilterConfig.RuleTarget target, FilterConfig.RuleMode mode) {
        return values.get(safeTarget(target)).get(safeMode(mode));
    }

    public void set(FilterConfig.RuleTarget target, FilterConfig.RuleMode mode, String raw) {
        values.get(safeTarget(target)).put(safeMode(mode), raw == null ? "" : raw);
    }

    public List<FilterConfig.RuleSpec> toRules() {
        List<FilterConfig.RuleSpec> rules = new ArrayList<>();
        for (FilterConfig.RuleTarget target : FilterConfig.RuleTarget.values()) {
            rules.addAll(RuleTextCodec.parseTargeted(get(target, FilterConfig.RuleMode.KEYWORD), FilterConfig.RuleMode.KEYWORD, target));
            rules.addAll(RuleTextCodec.parseTargeted(get(target, FilterConfig.RuleMode.REGEX), FilterConfig.RuleMode.REGEX, target));
        }
        return rules;
    }

    private static FilterConfig.RuleTarget safeTarget(FilterConfig.RuleTarget target) {
        return target == null ? FilterConfig.RuleTarget.ANY : target;
    }

    private static FilterConfig.RuleMode safeMode(FilterConfig.RuleMode mode) {
        return mode == null ? FilterConfig.RuleMode.KEYWORD : mode;
    }
}
