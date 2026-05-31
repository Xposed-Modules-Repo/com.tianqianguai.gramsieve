package com.tianqianguai.gramsieve.config;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import com.tianqianguai.gramsieve.core.FilterConfig;

public final class ConfigPersistActivity extends Activity {
    private static final String TAG = "GramSieve";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        persistAndFinish();
    }

    private void persistAndFinish() {
        try {
            String json = ConfigUpdateReceiver.configJsonFromIntent(getIntent());
            FilterConfig incoming = ModuleConfigStore.fromJson(json);
            FilterConfig local = ModuleConfigStore.load(getSharedPreferences(
                    ModuleConfigStore.PREFS_NAME,
                    Context.MODE_PRIVATE
            ));
            FilterConfig merged = ConfigUpdateReceiver.mergeForPersistence(local, incoming);
            ModuleConfigStore.save(this, merged);
            ModuleLogger.config(TAG,
                    "ConfigPersistActivity: saved config updatedAt=" + merged.updatedAtEpochMs
                            + " chatRules=" + merged.chatRules.size()
            );
        } catch (RuntimeException exception) {
            ModuleLogger.configError(TAG, "ConfigPersistActivity: failed to save config", exception);
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
