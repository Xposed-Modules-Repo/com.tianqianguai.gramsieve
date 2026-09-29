package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class PersistentLogStore {
    public static final String PREFS_NAME = "gramsieve_persistent_log";
    public static final String KEY_LOG_JSON = "log_json";
    public static final int MAX_ENTRIES = 500;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PersistentLogStore() {
    }

    public static LogSnapshot load(Context context) {
        if (context == null) {
            return new LogSnapshot();
        }
        return load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public static LogSnapshot load(SharedPreferences preferences) {
        if (preferences == null) {
            return new LogSnapshot();
        }
        return fromJson(preferences.getString(KEY_LOG_JSON, null));
    }

    public static void append(Context context, LogEntry entry) {
        if (context == null || entry == null) {
            return;
        }
        append(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), entry);
    }

    public static void append(SharedPreferences preferences, LogEntry entry) {
        if (preferences == null || entry == null) {
            return;
        }
        LogSnapshot updated = append(load(preferences), entry, MAX_ENTRIES);
        preferences.edit().putString(KEY_LOG_JSON, toJson(updated)).apply();
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
        preferences.edit().putString(KEY_LOG_JSON, toJson(new LogSnapshot())).apply();
    }

    public static String toJson(LogSnapshot snapshot) {
        return GSON.toJson((snapshot == null ? new LogSnapshot() : snapshot).sanitize());
    }

    public static LogSnapshot fromJson(String json) {
        try {
            LogSnapshot snapshot = json == null || json.isBlank()
                    ? new LogSnapshot()
                    : GSON.fromJson(json, LogSnapshot.class);
            return snapshot == null ? new LogSnapshot() : snapshot.sanitize();
        } catch (RuntimeException ignored) {
            return new LogSnapshot();
        }
    }

    public static String entryToJson(LogEntry entry) {
        return GSON.toJson((entry == null ? new LogEntry() : entry).sanitize());
    }

    public static LogEntry entryFromJson(String json) {
        try {
            LogEntry entry = json == null || json.isBlank()
                    ? new LogEntry()
                    : GSON.fromJson(json, LogEntry.class);
            return entry == null ? new LogEntry() : entry.sanitize();
        } catch (RuntimeException ignored) {
            return new LogEntry();
        }
    }

    static LogSnapshot append(LogSnapshot snapshot, LogEntry entry, int maxEntries) {
        LogSnapshot target = snapshot == null ? new LogSnapshot() : snapshot.sanitize();
        LogEntry normalized = entry == null ? new LogEntry() : entry.sanitize();
        List<LogEntry> updated = new ArrayList<>();
        updated.add(normalized);
        for (LogEntry existing : target.entries) {
            if (updated.size() >= Math.max(1, maxEntries)) {
                break;
            }
            updated.add(existing);
        }
        target.entries = updated;
        target.updatedAtEpochMs = Math.max(System.currentTimeMillis(), normalized.timestampEpochMs);
        return target.sanitize();
    }

    public static final class LogSnapshot {
        public int schemaVersion = 1;
        public long updatedAtEpochMs = System.currentTimeMillis();
        public List<LogEntry> entries = new ArrayList<>();

        LogSnapshot sanitize() {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            List<LogEntry> sanitized = new ArrayList<>();
            for (LogEntry entry : entries) {
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

    public static final class LogEntry {
        public long timestampEpochMs = System.currentTimeMillis();
        public String level = "INFO";
        public String category = "general";
        public String tag = "";
        public String message = "";
        public String throwable = "";

        public LogEntry() {
        }

        public LogEntry(String level, String category, String tag, String message) {
            this.level = level;
            this.category = category;
            this.tag = tag;
            this.message = message;
        }

        public LogEntry(String level, String category, String tag, String message, Throwable throwable) {
            this.level = level;
            this.category = category;
            this.tag = tag;
            this.message = message;
            this.throwable = throwableToString(throwable);
        }

        LogEntry sanitize() {
            if (timestampEpochMs <= 0L) {
                timestampEpochMs = System.currentTimeMillis();
            }
            level = limit(level, 16);
            category = limit(category, 32);
            tag = limit(tag, 64);
            message = limit(message, 512);
            throwable = limit(throwable, 1024);
            return this;
        }

        private static String limit(String value, int maxLength) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() <= maxLength) {
                return normalized;
            }
            return normalized.substring(0, maxLength);
        }

        private static String throwableToString(Throwable throwable) {
            if (throwable == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(throwable.getClass().getName());
            if (throwable.getMessage() != null) {
                sb.append(": ").append(throwable.getMessage());
            }
            StackTraceElement[] trace = throwable.getStackTrace();
            int limit = Math.min(trace.length, 8);
            for (int i = 0; i < limit; i++) {
                sb.append("\n  at ").append(trace[i]);
            }
            return sb.toString();
        }
    }
}
