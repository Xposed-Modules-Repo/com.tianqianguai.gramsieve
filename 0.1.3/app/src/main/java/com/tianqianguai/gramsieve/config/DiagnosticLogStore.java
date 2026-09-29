package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class DiagnosticLogStore {
    public static final String PREFS_NAME = "gramsieve_diagnostics";
    public static final String KEY_LOGS_JSON = "logs_json";
    public static final int MAX_ENTRIES = 240;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private DiagnosticLogStore() {
    }

    public static DiagnosticSnapshot load(Context context) {
        return load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public static DiagnosticSnapshot load(SharedPreferences preferences) {
        if (preferences == null) {
            return new DiagnosticSnapshot();
        }
        return fromJson(preferences.getString(KEY_LOGS_JSON, null));
    }

    public static void append(Context context, DiagnosticEntry entry) {
        if (context == null || entry == null) {
            return;
        }
        append(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), entry);
    }

    public static void append(SharedPreferences preferences, DiagnosticEntry entry) {
        if (preferences == null || entry == null) {
            return;
        }
        DiagnosticSnapshot updated = append(load(preferences), entry, MAX_ENTRIES);
        preferences.edit().putString(KEY_LOGS_JSON, toJson(updated)).apply();
    }

    public static void clear(Context context) {
        if (context == null) {
            return;
        }
        clear(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public static void clear(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }
        preferences.edit().putString(KEY_LOGS_JSON, toJson(new DiagnosticSnapshot())).apply();
    }

    public static String toJson(DiagnosticSnapshot snapshot) {
        return GSON.toJson((snapshot == null ? new DiagnosticSnapshot() : snapshot).sanitize());
    }

    public static DiagnosticSnapshot fromJson(String json) {
        try {
            DiagnosticSnapshot snapshot = json == null || json.isBlank()
                    ? new DiagnosticSnapshot()
                    : GSON.fromJson(json, DiagnosticSnapshot.class);
            return snapshot == null ? new DiagnosticSnapshot() : snapshot.sanitize();
        } catch (RuntimeException ignored) {
            return new DiagnosticSnapshot();
        }
    }

    public static String entryToJson(DiagnosticEntry entry) {
        return GSON.toJson((entry == null ? new DiagnosticEntry() : entry).sanitize());
    }

    public static DiagnosticEntry entryFromJson(String json) {
        try {
            DiagnosticEntry entry = json == null || json.isBlank()
                    ? new DiagnosticEntry()
                    : GSON.fromJson(json, DiagnosticEntry.class);
            return entry == null ? new DiagnosticEntry() : entry.sanitize();
        } catch (RuntimeException ignored) {
            return new DiagnosticEntry();
        }
    }

    static DiagnosticSnapshot append(DiagnosticSnapshot snapshot, DiagnosticEntry entry, int maxEntries) {
        DiagnosticSnapshot target = snapshot == null ? new DiagnosticSnapshot() : snapshot.sanitize();
        DiagnosticEntry normalized = entry == null ? new DiagnosticEntry() : entry.sanitize();
        List<DiagnosticEntry> updated = new ArrayList<>();
        updated.add(normalized);
        for (DiagnosticEntry existing : target.entries) {
            if (updated.size() >= Math.max(1, maxEntries)) {
                break;
            }
            updated.add(existing);
        }
        target.entries = updated;
        target.updatedAtEpochMs = Math.max(System.currentTimeMillis(), normalized.timestampEpochMs);
        return target.sanitize();
    }

    public static final class DiagnosticSnapshot {
        public int schemaVersion = 1;
        public long updatedAtEpochMs = System.currentTimeMillis();
        public List<DiagnosticEntry> entries = new ArrayList<>();

        DiagnosticSnapshot sanitize() {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            List<DiagnosticEntry> sanitized = new ArrayList<>();
            for (DiagnosticEntry entry : entries) {
                if (entry != null) {
                    sanitized.add(entry.sanitize());
                }
                if (sanitized.size() >= MAX_ENTRIES) {
                    break;
                }
            }
            entries = sanitized;
            if (updatedAtEpochMs <= 0L) {
                updatedAtEpochMs = System.currentTimeMillis();
            }
            return this;
        }
    }

    public static final class DiagnosticEntry {
        public long timestampEpochMs = System.currentTimeMillis();
        public String category = "decision";
        public boolean matched = false;
        public boolean excluded = false;
        public String action = "";
        public String ruleId = "";
        public String reason = "";
        public boolean likelyGambling = false;
        public int globalRuleCount = 0;
        public int chatRuleSetCount = 0;
        public long dialogId = 0L;
        public long senderId = 0L;
        public long messageId = 0L;
        public String stableKey = "";
        public String chatName = "";
        public String senderName = "";
        public String text = "";
        public String caption = "";
        public String buttonText = "";
        public boolean hasInlineButtons = false;

        DiagnosticEntry sanitize() {
            if (timestampEpochMs <= 0L) {
                timestampEpochMs = System.currentTimeMillis();
            }
            category = limit(category, 32);
            action = limit(action, 32);
            ruleId = limit(ruleId, 80);
            reason = limit(reason, 160);
            stableKey = limit(stableKey, 200);
            chatName = limit(chatName, 120);
            senderName = limit(senderName, 120);
            text = limit(text, 240);
            caption = limit(caption, 240);
            buttonText = limit(buttonText, 240);
            return this;
        }

        private static String limit(String value, int maxLength) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() <= maxLength) {
                return normalized;
            }
            return normalized.substring(0, maxLength);
        }
    }
}
