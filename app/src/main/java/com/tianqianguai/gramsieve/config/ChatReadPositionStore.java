package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class ChatReadPositionStore {
    public static final String PREFS_NAME = "gramsieve_read_positions";
    public static final String KEY_POSITIONS_JSON = "positions_json";
    public static final int MAX_ENTRIES = 500;
    public static final int MAX_STACK_PER_DIALOG = 50;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ChatReadPositionStore() {
    }

    public static ReadPosition load(Context context, long dialogId) {
        if (context == null) {
            return null;
        }
        ReadPositionsSnapshot snapshot = loadSnapshot(context);
        return snapshot.peekPosition(dialogId);
    }

    public static ReadPosition pop(Context context, long dialogId) {
        if (context == null || dialogId == 0L) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ReadPositionsSnapshot snapshot = loadFromPrefs(prefs);
        ReadPosition popped = snapshot.popPosition(dialogId);
        prefs.edit().putString(KEY_POSITIONS_JSON, toJson(snapshot)).apply();
        return popped;
    }

    public static void save(Context context, long dialogId, int messageId) {
        if (context == null || dialogId == 0L || messageId <= 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ReadPositionsSnapshot snapshot = loadFromPrefs(prefs);
        snapshot.pushPosition(dialogId, messageId, MAX_ENTRIES, MAX_STACK_PER_DIALOG);
        prefs.edit().putString(KEY_POSITIONS_JSON, toJson(snapshot)).apply();
    }

    public static void remove(Context context, long dialogId) {
        if (context == null || dialogId == 0L) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ReadPositionsSnapshot snapshot = loadFromPrefs(prefs);
        snapshot.removePosition(dialogId);
        prefs.edit().putString(KEY_POSITIONS_JSON, toJson(snapshot)).apply();
    }

    static ReadPositionsSnapshot loadSnapshot(Context context) {
        if (context == null) {
            return new ReadPositionsSnapshot();
        }
        return loadFromPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    private static ReadPositionsSnapshot loadFromPrefs(SharedPreferences prefs) {
        return fromJson(prefs.getString(KEY_POSITIONS_JSON, null));
    }

    static String toJson(ReadPositionsSnapshot snapshot) {
        return GSON.toJson((snapshot == null ? new ReadPositionsSnapshot() : snapshot).sanitize());
    }

    static ReadPositionsSnapshot fromJson(String json) {
        try {
            ReadPositionsSnapshot snapshot = json == null || json.isBlank()
                    ? new ReadPositionsSnapshot()
                    : GSON.fromJson(json, ReadPositionsSnapshot.class);
            return snapshot == null ? new ReadPositionsSnapshot() : snapshot.sanitize();
        } catch (RuntimeException ignored) {
            return new ReadPositionsSnapshot();
        }
    }

    public static final class ReadPositionsSnapshot {
        public int schemaVersion = 2;
        public List<ReadPositionEntry> entries = new ArrayList<>();

        ReadPosition peekPosition(long dialogId) {
            if (entries == null) {
                return null;
            }
            for (ReadPositionEntry entry : entries) {
                if (entry != null && entry.dialogId == dialogId && entry.hasPositions()) {
                    int top = entry.peekMessageId();
                    return new ReadPosition(top, entry.timestampEpochMs);
                }
            }
            return null;
        }

        ReadPosition popPosition(long dialogId) {
            if (entries == null) {
                return null;
            }
            for (ReadPositionEntry entry : entries) {
                if (entry != null && entry.dialogId == dialogId && entry.hasPositions()) {
                    int popped = entry.popMessageId();
                    if (!entry.hasPositions()) {
                        entries.remove(entry);
                    }
                    return new ReadPosition(popped, entry.timestampEpochMs);
                }
            }
            return null;
        }

        void pushPosition(long dialogId, int messageId, int maxEntries, int maxStack) {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            long now = System.currentTimeMillis();
            for (ReadPositionEntry entry : entries) {
                if (entry != null && entry.dialogId == dialogId) {
                    entry.pushMessageId(messageId, maxStack);
                    entry.timestampEpochMs = now;
                    sortByTimestamp();
                    return;
                }
            }
            ReadPositionEntry newEntry = new ReadPositionEntry(dialogId, now);
            newEntry.pushMessageId(messageId, maxStack);
            entries.add(0, newEntry);
            sortByTimestamp();
            while (entries.size() > Math.max(1, maxEntries)) {
                entries.remove(entries.size() - 1);
            }
        }

        void removePosition(long dialogId) {
            if (entries == null) {
                return;
            }
            entries.removeIf(e -> e != null && e.dialogId == dialogId);
        }

        private void sortByTimestamp() {
            entries.sort((a, b) -> Long.compare(
                    b == null ? 0L : b.timestampEpochMs,
                    a == null ? 0L : a.timestampEpochMs
            ));
        }

        ReadPositionsSnapshot sanitize() {
            if (entries == null) {
                entries = new ArrayList<>();
            }
            List<ReadPositionEntry> sanitized = new ArrayList<>();
            for (ReadPositionEntry entry : entries) {
                if (entry != null && entry.dialogId != 0L) {
                    entry.migrateIfNeeded();
                    if (entry.hasPositions()) {
                        sanitized.add(entry);
                    }
                }
            }
            entries = sanitized;
            sortByTimestamp();
            while (entries.size() > MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }
            return this;
        }
    }

    public static final class ReadPositionEntry {
        public long dialogId;
        public int messageId;
        public List<Integer> messageIdStack;
        public long timestampEpochMs;

        public ReadPositionEntry() {
        }

        ReadPositionEntry(long dialogId, long timestampEpochMs) {
            this.dialogId = dialogId;
            this.timestampEpochMs = timestampEpochMs;
        }

        boolean hasPositions() {
            return messageIdStack != null && !messageIdStack.isEmpty();
        }

        int peekMessageId() {
            if (messageIdStack == null || messageIdStack.isEmpty()) {
                return 0;
            }
            return messageIdStack.get(messageIdStack.size() - 1);
        }

        int popMessageId() {
            if (messageIdStack == null || messageIdStack.isEmpty()) {
                return 0;
            }
            return messageIdStack.remove(messageIdStack.size() - 1);
        }

        void pushMessageId(int id, int maxStack) {
            if (messageIdStack == null) {
                messageIdStack = new ArrayList<>();
            }
            if (!messageIdStack.isEmpty() && messageIdStack.get(messageIdStack.size() - 1) == id) {
                return;
            }
            messageIdStack.add(id);
            while (messageIdStack.size() > maxStack) {
                messageIdStack.remove(0);
            }
        }

        void migrateIfNeeded() {
            if (messageIdStack != null && !messageIdStack.isEmpty()) {
                return;
            }
            if (messageId > 0) {
                messageIdStack = new ArrayList<>();
                messageIdStack.add(messageId);
                messageId = 0;
            }
        }
    }

    public static final class ReadPosition {
        public final int messageId;
        public final long timestampEpochMs;

        public ReadPosition(int messageId, long timestampEpochMs) {
            this.messageId = messageId;
            this.timestampEpochMs = timestampEpochMs;
        }
    }
}
