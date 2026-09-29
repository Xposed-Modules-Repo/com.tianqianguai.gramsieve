package com.tianqianguai.gramsieve.core;

import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageRuleFactory {
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|cc|top|vip|bet|ag|app|io|me|tv|xyz|site|shop|club|info|pro|live|win|casino)\\b"
    );
    private static final Pattern TELEGRAM_LINK = Pattern.compile("(?i)\\b(?:https?://)?(?:t\\.me|telegram\\.me)/[a-z0-9_+/-]{3,}");
    private static final Pattern HANDLE = Pattern.compile("(?i)(?<![\\w@])@[a-z0-9_]{4,32}\\b");
    private static final Pattern BRANDISH_TOKEN = Pattern.compile("(?i)\\b[a-z0-9][a-z0-9._-]{2,}[a-z0-9]\\b");

    private MessageRuleFactory() {
    }

    public static List<FilterConfig.RuleSpec> automaticRules(MessageSnapshot snapshot) {
        Map<String, FilterConfig.RuleSpec> rules = new LinkedHashMap<>();
        addRule(rules, exactContentRule(snapshot));
        if (snapshot == null) {
            return new ArrayList<>(rules.values());
        }
        String visible = snapshot.combinedVisibleContent();
        addStableTokens(rules, visible);
        return new ArrayList<>(rules.values());
    }

    public static FilterConfig.RuleSpec exactContentRule(MessageSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Candidate best = bestCandidate(snapshot);
        if (best == null) {
            return null;
        }
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.mode = FilterConfig.RuleMode.REGEX;
        rule.target = best.target;
        rule.pattern = "^" + Pattern.quote(best.value) + "$";
        rule.caseSensitive = false;
        rule.id = buildId(rule.mode, rule.target, rule.pattern);
        return rule.sanitize();
    }

    public static boolean containsEquivalentRule(
            Iterable<FilterConfig.RuleSpec> rules,
            FilterConfig.RuleSpec candidate
    ) {
        if (rules == null || candidate == null) {
            return false;
        }
        FilterConfig.RuleTarget target = candidate.target == null ? FilterConfig.RuleTarget.ANY : candidate.target;
        FilterConfig.RuleMode mode = candidate.mode == null ? FilterConfig.RuleMode.KEYWORD : candidate.mode;
        boolean caseSensitive = candidate.caseSensitive;
        String pattern = comparablePattern(candidate.pattern, caseSensitive);
        for (FilterConfig.RuleSpec existing : rules) {
            if (existing == null) {
                continue;
            }
            FilterConfig.RuleTarget existingTarget = existing.target == null ? FilterConfig.RuleTarget.ANY : existing.target;
            FilterConfig.RuleMode existingMode = existing.mode == null ? FilterConfig.RuleMode.KEYWORD : existing.mode;
            if (existingTarget == target
                    && existingMode == mode
                    && existing.caseSensitive == caseSensitive
                    && comparablePattern(existing.pattern, caseSensitive).equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static void addStableTokens(Map<String, FilterConfig.RuleSpec> rules, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        addPatternMatches(rules, DOMAIN, value, MessageRuleFactory::normalizeDomain);
        addPatternMatches(rules, TELEGRAM_LINK, value, MessageRuleFactory::normalizeTelegramLink);
        addPatternMatches(rules, HANDLE, value, MessageRuleFactory::normalizeHandle);
        Matcher matcher = BRANDISH_TOKEN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (isStableBrandToken(token)) {
                addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, token));
            }
        }
    }

    private interface TokenNormalizer {
        String normalize(String value);
    }

    private static void addPatternMatches(
            Map<String, FilterConfig.RuleSpec> rules,
            Pattern pattern,
            String value,
            TokenNormalizer normalizer
    ) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String token = normalizer.normalize(matcher.group());
            if (!token.isBlank()) {
                addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, token));
            }
        }
    }

    private static FilterConfig.RuleSpec keywordRule(FilterConfig.RuleTarget target, String pattern) {
        String normalized = normalize(pattern);
        if (normalized.isBlank()) {
            return null;
        }
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.mode = FilterConfig.RuleMode.KEYWORD;
        rule.target = target;
        rule.pattern = normalized;
        rule.caseSensitive = false;
        rule.id = buildId(rule.mode, rule.target, rule.pattern);
        return rule.sanitize();
    }

    private static void addRule(Map<String, FilterConfig.RuleSpec> rules, FilterConfig.RuleSpec rule) {
        if (rule == null) {
            return;
        }
        FilterConfig.RuleTarget target = rule.target == null ? FilterConfig.RuleTarget.ANY : rule.target;
        FilterConfig.RuleMode mode = rule.mode == null ? FilterConfig.RuleMode.KEYWORD : rule.mode;
        String key = target.name() + "|" + mode.name() + "|" + normalize(rule.pattern).toLowerCase(Locale.ROOT);
        rules.putIfAbsent(key, rule);
    }

    private static String normalizeDomain(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".") || normalized.endsWith(",") || normalized.endsWith(";") || normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("t.me".equals(normalized) || "telegram.me".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private static String normalizeTelegramLink(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
        }
        return normalized;
    }

    private static String normalizeHandle(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private static boolean isStableBrandToken(String value) {
        String normalized = normalize(value);
        if (normalized.length() < 4 || normalized.length() > 48) {
            return false;
        }
        if (DOMAIN.matcher(normalized).matches()) {
            return true;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            hasLetter |= Character.isLetter(c);
            hasDigit |= Character.isDigit(c);
        }
        return hasLetter && hasDigit;
    }

    private static Candidate bestCandidate(MessageSnapshot snapshot) {
        Candidate best = null;
        best = better(best, FilterConfig.RuleTarget.TEXT, snapshot.text);
        best = better(best, FilterConfig.RuleTarget.CAPTION, snapshot.caption);
        best = better(best, FilterConfig.RuleTarget.BUTTONS, snapshot.buttonText);
        return best;
    }

    private static Candidate better(Candidate current, FilterConfig.RuleTarget target, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return current;
        }
        Candidate candidate = new Candidate(target, normalized);
        if (current == null || candidate.score() > current.score()) {
            return candidate;
        }
        return current;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String comparablePattern(String value, boolean caseSensitive) {
        String normalized = normalize(value);
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    private static String buildId(FilterConfig.RuleMode mode, FilterConfig.RuleTarget target, String pattern) {
        String normalized = pattern.toLowerCase(Locale.ROOT);
        return "manual_" + mode.name().charAt(0) + "_" + target.name() + "_" + Integer.toUnsignedString(normalized.hashCode(), 16);
    }

    private static final class Candidate {
        final FilterConfig.RuleTarget target;
        final String value;

        Candidate(FilterConfig.RuleTarget target, String value) {
            this.target = target;
            this.value = value;
        }

        int score() {
            return value.length();
        }
    }
}
