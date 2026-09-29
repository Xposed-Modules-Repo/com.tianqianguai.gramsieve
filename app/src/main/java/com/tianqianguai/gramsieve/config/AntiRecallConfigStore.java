package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AntiRecallConfigStore {
    private static final String PREFS_NAME = "gramsieve_anti_recall";
    private static final String KEY_ENABLED = "anti_recall_enabled";
    private static final String KEY_LOAD_INTERVAL = "load_interval_seconds";
    private static final String KEY_MAX_CACHE_SIZE = "max_cache_size";
    private static final String CHAT_PREFIX = "anti_recall_chat_";

    private static final boolean DEFAULT_ENABLED = false;
    private static final int DEFAULT_LOAD_INTERVAL = 30;
    private static final int DEFAULT_MAX_CACHE_SIZE = 1000;

    private final SharedPreferences prefs;

    public AntiRecallConfigStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int getLoadIntervalSeconds() {
        return prefs.getInt(KEY_LOAD_INTERVAL, DEFAULT_LOAD_INTERVAL);
    }

    public void setLoadIntervalSeconds(int seconds) {
        prefs.edit().putInt(KEY_LOAD_INTERVAL, seconds).apply();
    }

    public int getMaxCacheSize() {
        return prefs.getInt(KEY_MAX_CACHE_SIZE, DEFAULT_MAX_CACHE_SIZE);
    }

    public void setMaxCacheSize(int size) {
        prefs.edit().putInt(KEY_MAX_CACHE_SIZE, size).apply();
    }

    public boolean isChatEnabled(long dialogId) {
        return prefs.getBoolean(CHAT_PREFIX + dialogId, false);
    }

    public void setChatEnabled(long dialogId, boolean enabled) {
        prefs.edit().putBoolean(CHAT_PREFIX + dialogId, enabled).apply();
    }

    public Set<Long> getEnabledChatIds() {
        Set<Long> enabledChats = new HashSet<>();
        Map<String, ?> allEntries = prefs.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(CHAT_PREFIX) && entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                try {
                    long dialogId = Long.parseLong(key.substring(CHAT_PREFIX.length()));
                    enabledChats.add(dialogId);
                } catch (NumberFormatException ignored) {}
            }
        }
        return enabledChats;
    }
}
