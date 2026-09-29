package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FilterConfig {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String APP_LANGUAGE_SYSTEM = "";
    public static final String APP_LANGUAGE_ENGLISH = "en";
    public static final String APP_LANGUAGE_SIMPLIFIED_CHINESE = "zh-CN";

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public boolean enabled = true;
    public boolean debugLogging = false;
    public Action action = Action.HIDE;
    public String appLanguageTag = APP_LANGUAGE_SYSTEM;
    public List<RuleSpec> globalRules = new ArrayList<>();
    public List<RuleSpec> globalExclusions = new ArrayList<>();
    public Map<String, ChatRuleSet> chatRules = new LinkedHashMap<>();
    public long updatedAtEpochMs = System.currentTimeMillis();

    public static FilterConfig createDefault() {
        return new FilterConfig();
    }

    public static String chatKey(long dialogId) {
        return Long.toString(dialogId);
    }

    public ChatRuleSet getChatRuleSet(long dialogId) {
        return chatRules.get(chatKey(dialogId));
    }

    public ChatRuleSet getOrCreateChatRuleSet(long dialogId) {
        String key = chatKey(dialogId);
        ChatRuleSet existing = chatRules.get(key);
        if (existing != null) {
            return existing;
        }
        ChatRuleSet created = new ChatRuleSet();
        chatRules.put(key, created);
        return created;
    }

    public FilterConfig sanitize() {
        if (action == null) {
            action = Action.HIDE;
        }
        appLanguageTag = normalizeAppLanguageTag(appLanguageTag);
        if (globalRules == null) {
            globalRules = new ArrayList<>();
        }
        if (globalExclusions == null) {
            globalExclusions = new ArrayList<>();
        }
        if (chatRules == null) {
            chatRules = new LinkedHashMap<>();
        }
        globalRules = sanitizeRules(globalRules);
        globalExclusions = sanitizeRules(globalExclusions);
        Map<String, ChatRuleSet> sanitizedChats = new LinkedHashMap<>();
        for (Map.Entry<String, ChatRuleSet> entry : chatRules.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            ChatRuleSet chatRuleSet = entry.getValue() == null ? new ChatRuleSet() : entry.getValue().sanitize();
            sanitizedChats.put(entry.getKey(), chatRuleSet);
        }
        chatRules = sanitizedChats;
        if (updatedAtEpochMs <= 0L) {
            updatedAtEpochMs = System.currentTimeMillis();
        }
        return this;
    }

    public FilterConfig deepCopy() {
        FilterConfig copy = new FilterConfig();
        copy.schemaVersion = schemaVersion;
        copy.enabled = enabled;
        copy.debugLogging = debugLogging;
        copy.action = action;
        copy.appLanguageTag = appLanguageTag;
        copy.updatedAtEpochMs = updatedAtEpochMs;
        for (RuleSpec rule : globalRules) {
            copy.globalRules.add(rule.deepCopy());
        }
        for (RuleSpec rule : globalExclusions) {
            copy.globalExclusions.add(rule.deepCopy());
        }
        for (Map.Entry<String, ChatRuleSet> entry : chatRules.entrySet()) {
            copy.chatRules.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }

    public static String normalizeAppLanguageTag(String appLanguageTag) {
        if (appLanguageTag == null || appLanguageTag.isBlank()) {
            return APP_LANGUAGE_SYSTEM;
        }
        if (APP_LANGUAGE_ENGLISH.equalsIgnoreCase(appLanguageTag)) {
            return APP_LANGUAGE_ENGLISH;
        }
        if ("zh".equalsIgnoreCase(appLanguageTag)
                || "zh-CN".equalsIgnoreCase(appLanguageTag)
                || "zh-Hans".equalsIgnoreCase(appLanguageTag)
                || "zh-Hans-CN".equalsIgnoreCase(appLanguageTag)) {
            return APP_LANGUAGE_SIMPLIFIED_CHINESE;
        }
        return APP_LANGUAGE_SYSTEM;
    }

    private static List<RuleSpec> sanitizeRules(List<RuleSpec> rules) {
        List<RuleSpec> sanitized = new ArrayList<>();
        for (RuleSpec rule : rules) {
            if (rule == null) {
                continue;
            }
            RuleSpec normalized = rule.sanitize();
            if (normalized.pattern.isBlank()) {
                continue;
            }
            sanitized.add(normalized);
        }
        return sanitized;
    }

    public enum Action {
        HIDE,
        COLLAPSE,
        DEBUG_MARK
    }

    public enum RuleMode {
        KEYWORD,
        REGEX
    }

    public enum RuleTarget {
        ANY(""),
        TEXT("text:"),
        CAPTION("caption:"),
        BUTTONS("button:"),
        SENDER("sender:"),
        CHAT("chat:");

        private final String prefix;

        RuleTarget(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    public static final class RuleSpec {
        public String id = "";
        public boolean enabled = true;
        public RuleMode mode = RuleMode.KEYWORD;
        public RuleTarget target = RuleTarget.ANY;
        public String pattern = "";
        public boolean caseSensitive = false;

        public RuleSpec sanitize() {
            if (id == null) {
                id = "";
            }
            if (mode == null) {
                mode = RuleMode.KEYWORD;
            }
            if (target == null) {
                target = RuleTarget.ANY;
            }
            if (pattern == null) {
                pattern = "";
            }
            pattern = pattern.trim();
            return this;
        }

        public RuleSpec deepCopy() {
            RuleSpec copy = new RuleSpec();
            copy.id = id;
            copy.enabled = enabled;
            copy.mode = mode;
            copy.target = target;
            copy.pattern = pattern;
            copy.caseSensitive = caseSensitive;
            return copy;
        }
    }

    public static final class ChatRuleSet {
        public boolean enabled = true;
        public boolean excludeFromGlobal = false;
        public List<RuleSpec> rules = new ArrayList<>();
        public List<RuleSpec> exclusions = new ArrayList<>();

        public ChatRuleSet sanitize() {
            if (rules == null) {
                rules = new ArrayList<>();
            }
            if (exclusions == null) {
                exclusions = new ArrayList<>();
            }
            rules = sanitizeRules(rules);
            exclusions = sanitizeRules(exclusions);
            return this;
        }

        public ChatRuleSet deepCopy() {
            ChatRuleSet copy = new ChatRuleSet();
            copy.enabled = enabled;
            copy.excludeFromGlobal = excludeFromGlobal;
            for (RuleSpec rule : rules) {
                copy.rules.add(rule.deepCopy());
            }
            for (RuleSpec rule : exclusions) {
                copy.exclusions.add(rule.deepCopy());
            }
            return copy;
        }

        public boolean isSemanticallyEmpty() {
            return enabled && !excludeFromGlobal && rules.isEmpty() && exclusions.isEmpty();
        }
    }
}
