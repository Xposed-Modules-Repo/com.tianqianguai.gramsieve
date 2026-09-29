package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RuleTextCodec {
    private RuleTextCodec() {
    }

    public static List<FilterConfig.RuleSpec> parse(String raw, FilterConfig.RuleMode mode) {
        return parseInternal(raw, mode, null);
    }

    public static List<FilterConfig.RuleSpec> parseTargeted(String raw, FilterConfig.RuleMode mode, FilterConfig.RuleTarget target) {
        return parseInternal(raw, mode, target == null ? FilterConfig.RuleTarget.ANY : target);
    }

    private static List<FilterConfig.RuleSpec> parseInternal(
            String raw,
            FilterConfig.RuleMode mode,
            FilterConfig.RuleTarget forcedTarget
    ) {
        List<FilterConfig.RuleSpec> rules = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return rules;
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            FilterConfig.RuleTarget target;
            String pattern;
            if (forcedTarget == null) {
                ParsedLine parsedLine = parsePrefixedLine(trimmed);
                target = parsedLine.target;
                pattern = parsedLine.pattern;
            } else {
                target = forcedTarget;
                pattern = stripMatchingPrefix(trimmed, forcedTarget);
            }
            if (pattern.isEmpty()) {
                continue;
            }
            FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
            rule.mode = mode;
            rule.target = target;
            rule.pattern = pattern;
            rule.caseSensitive = false;
            rule.id = buildId(mode, target, pattern, rules.size());
            rules.add(rule.sanitize());
        }
        return rules;
    }

    public static String format(List<FilterConfig.RuleSpec> rules) {
        return formatInternal(rules, null, null);
    }

    public static String formatTargeted(List<FilterConfig.RuleSpec> rules, FilterConfig.RuleMode mode, FilterConfig.RuleTarget target) {
        return formatInternal(rules, mode, target == null ? FilterConfig.RuleTarget.ANY : target);
    }

    private static String formatInternal(
            List<FilterConfig.RuleSpec> rules,
            FilterConfig.RuleMode requiredMode,
            FilterConfig.RuleTarget requiredTarget
    ) {
        StringBuilder builder = new StringBuilder();
        for (FilterConfig.RuleSpec rule : rules) {
            if (rule == null || rule.pattern == null || rule.pattern.isBlank()) {
                continue;
            }
            if (requiredMode != null && rule.mode != requiredMode) {
                continue;
            }
            if (requiredTarget != null) {
                FilterConfig.RuleTarget safeTarget = rule.target == null ? FilterConfig.RuleTarget.ANY : rule.target;
                if (safeTarget != requiredTarget) {
                    continue;
                }
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            if (requiredTarget == null && rule.target != null && rule.target != FilterConfig.RuleTarget.ANY) {
                builder.append(rule.target.prefix());
            }
            builder.append(rule.pattern.trim());
        }
        return builder.toString();
    }

    private static ParsedLine parsePrefixedLine(String trimmed) {
        FilterConfig.RuleTarget target = FilterConfig.RuleTarget.ANY;
        String pattern = trimmed;
        for (FilterConfig.RuleTarget candidate : FilterConfig.RuleTarget.values()) {
            if (candidate == FilterConfig.RuleTarget.ANY) {
                continue;
            }
            String prefix = candidate.prefix();
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                target = candidate;
                pattern = trimmed.substring(prefix.length()).trim();
                break;
            }
        }
        return new ParsedLine(target, pattern);
    }

    private static String stripMatchingPrefix(String trimmed, FilterConfig.RuleTarget target) {
        if (target == null || target == FilterConfig.RuleTarget.ANY) {
            return trimmed;
        }
        String prefix = target.prefix();
        if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return trimmed.substring(prefix.length()).trim();
        }
        return trimmed;
    }

    private static final class ParsedLine {
        final FilterConfig.RuleTarget target;
        final String pattern;

        ParsedLine(FilterConfig.RuleTarget target, String pattern) {
            this.target = target;
            this.pattern = pattern;
        }
    }

    private static String buildId(FilterConfig.RuleMode mode, FilterConfig.RuleTarget target, String pattern, int index) {
        String normalized = pattern.toLowerCase(Locale.ROOT);
        return mode.name().charAt(0) + "_" + target.name() + "_" + Integer.toUnsignedString(normalized.hashCode(), 16) + "_" + index;
    }
}
