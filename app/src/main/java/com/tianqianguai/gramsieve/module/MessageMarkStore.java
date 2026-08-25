package com.tianqianguai.gramsieve.module;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Stores multiple lightweight message bookmarks without touching Telegram's database. */
final class MessageMarkStore {
    private static final String PREFS_NAME = "gramsieve_marked_positions";
    private static final String KEY_PREFIX = "marks_";
    private static final int MAX_MARKS_PER_DIALOG = 50;
    private static final Gson GSON = new Gson();
    private static final Type MARK_LIST_TYPE = new TypeToken<ArrayList<Mark>>() { }.getType();

    private final SharedPreferences preferences;

    MessageMarkStore(Context context) {
        this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    MessageMarkStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    synchronized void add(int account, long dialogId, int messageId, String preview) {
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        List<Mark> marks = mutableLoad(account, dialogId);
        marks.removeIf(mark -> mark.messageId == messageId);
        Mark mark = new Mark();
        mark.messageId = messageId;
        mark.preview = normalizePreview(preview);
        mark.savedAtEpochMs = System.currentTimeMillis();
        marks.add(0, mark);
        if (marks.size() > MAX_MARKS_PER_DIALOG) {
            marks = new ArrayList<>(marks.subList(0, MAX_MARKS_PER_DIALOG));
        }
        persist(account, dialogId, marks);
        // Keep the original single-value key so older GramSieve builds still jump to the latest.
        preferences.edit().putInt(legacyKey(dialogId), messageId).apply();
    }

    synchronized List<Mark> list(int account, long dialogId) {
        List<Mark> marks = mutableLoad(account, dialogId);
        if (marks.isEmpty() && account == 0) {
            int legacy = preferences.getInt(legacyKey(dialogId), 0);
            if (legacy > 0) {
                Mark mark = new Mark();
                mark.messageId = legacy;
                mark.preview = "";
                marks.add(mark);
            }
        }
        marks.sort(Comparator.comparingLong((Mark mark) -> mark.savedAtEpochMs).reversed());
        return Collections.unmodifiableList(marks);
    }

    synchronized void clear(int account, long dialogId) {
        preferences.edit()
                .remove(key(account, dialogId))
                .remove(legacyKey(dialogId))
                .apply();
    }

    private List<Mark> mutableLoad(int account, long dialogId) {
        String json = preferences.getString(key(account, dialogId), "");
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Mark> decoded = GSON.fromJson(json, MARK_LIST_TYPE);
            List<Mark> valid = new ArrayList<>();
            if (decoded != null) {
                for (Mark mark : decoded) {
                    if (mark != null && mark.messageId > 0) {
                        mark.preview = normalizePreview(mark.preview);
                        valid.add(mark);
                    }
                }
            }
            return valid;
        } catch (RuntimeException ignored) {
            return new ArrayList<>();
        }
    }

    private void persist(int account, long dialogId, List<Mark> marks) {
        preferences.edit().putString(key(account, dialogId), GSON.toJson(marks)).apply();
    }

    private static String key(int account, long dialogId) {
        return KEY_PREFIX + Math.max(0, account) + "_" + dialogId;
    }

    private static String legacyKey(long dialogId) {
        return "marked_msg_" + dialogId;
    }

    static String normalizePreview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 95) + "…";
    }

    static final class Mark {
        int messageId;
        String preview = "";
        long savedAtEpochMs;
    }
}
