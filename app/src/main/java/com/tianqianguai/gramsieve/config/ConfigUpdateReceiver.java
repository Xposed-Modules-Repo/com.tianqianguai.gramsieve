package com.tianqianguai.gramsieve.config;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;

import com.tianqianguai.gramsieve.core.FilterConfig;

import java.nio.charset.StandardCharsets;

public final class ConfigUpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "GramSieve";
    public static final String ACTION_SAVE_CONFIG = "com.tianqianguai.gramsieve.action.SAVE_CONFIG";
    public static final String EXTRA_CONFIG_JSON = "config_json";
    public static final String EXTRA_CONFIG_JSON_BASE64 = "config_json_base64";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_SAVE_CONFIG.equals(intent.getAction())) {
            return;
        }
        String json = configJsonFromIntent(intent);
        if (json == null || json.isBlank()) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "ConfigUpdateReceiver: ignored empty config");
            return;
        }
        FilterConfig incoming = ModuleConfigStore.fromJson(json);
        FilterConfig local = ModuleConfigStore.load(context.getSharedPreferences(
                ModuleConfigStore.PREFS_NAME,
                Context.MODE_PRIVATE
        ));
        FilterConfig merged = mergeForPersistence(local, incoming);
        ModuleConfigStore.save(context, merged);
        ModuleLogger.config(TAG,
                "ConfigUpdateReceiver: saved config updatedAt=" + merged.updatedAtEpochMs
                        + " chatRules=" + merged.chatRules.size()
        );
    }

    static String configJsonFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String encoded = intent.getStringExtra(EXTRA_CONFIG_JSON_BASE64);
        if (encoded != null && !encoded.isBlank()) {
            try {
                return new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
            } catch (RuntimeException exception) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "ConfigUpdateReceiver: invalid base64 config");
            }
        }
        return intent.getStringExtra(EXTRA_CONFIG_JSON);
    }

    static FilterConfig mergeForPersistence(FilterConfig local, FilterConfig incoming) {
        FilterConfig safeLocal = (local == null ? FilterConfig.createDefault() : local).sanitize();
        FilterConfig safeIncoming = (incoming == null ? FilterConfig.createDefault() : incoming).sanitize();
        // A config is a complete snapshot. Union-merging rules here resurrects entries that
        // the host editor intentionally deleted, so only the newest snapshot may win.
        return (safeIncoming.updatedAtEpochMs >= safeLocal.updatedAtEpochMs
                ? safeIncoming
                : safeLocal).deepCopy().sanitize();
    }
}
