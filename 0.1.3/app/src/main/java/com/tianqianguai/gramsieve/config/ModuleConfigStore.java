package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tianqianguai.gramsieve.GramSieveApplication;
import com.tianqianguai.gramsieve.core.FilterConfig;

import io.github.libxposed.service.XposedService;

public final class ModuleConfigStore {
    private static final String TAG = "GramSieve";
    public static final String PREFS_NAME = "gramsieve_rules";
    public static final String KEY_CONFIG_JSON = "config_json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ModuleConfigStore() {
    }

    public static FilterConfig load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        FilterConfig config = load(preferences);
        return syncToRemote(context, GramSieveApplication.getXposedService(), config);
    }

    public static FilterConfig load(SharedPreferences preferences) {
        String json = preferences.getString(KEY_CONFIG_JSON, null);
        return fromJson(json);
    }

    public static void save(Context context, FilterConfig config) {
        FilterConfig sanitized = config == null ? FilterConfig.createDefault() : config.sanitize();
        sanitized.updatedAtEpochMs = System.currentTimeMillis();
        String json = toJson(sanitized);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG_JSON, json)
                .commit();
        syncToRemote(context, GramSieveApplication.getXposedService(), sanitized);
    }

    public static String toJson(FilterConfig config) {
        FilterConfig sanitized = (config == null ? FilterConfig.createDefault() : config).sanitize();
        return GSON.toJson(sanitized);
    }

    public static FilterConfig fromJson(String json) {
        try {
            FilterConfig config = json == null || json.isBlank()
                    ? FilterConfig.createDefault()
                    : GSON.fromJson(json, FilterConfig.class);
            if (config == null) {
                return FilterConfig.createDefault();
            }
            return config.sanitize();
        } catch (RuntimeException ignored) {
            return FilterConfig.createDefault();
        }
    }

    public static void syncToRemote(Context context, XposedService service) {
        if (context == null) {
            return;
        }
        FilterConfig config = load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
        syncToRemote(context, service, config);
    }

    private static FilterConfig syncToRemote(Context context, XposedService service, FilterConfig config) {
        FilterConfig localConfig = (config == null ? FilterConfig.createDefault() : config).sanitize();
        if (context == null) {
            return localConfig;
        }
        if (service == null || (service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0L) {
            ModuleLogger.config(TAG, "ModuleConfigStore: remote service unavailable");
            return localConfig;
        }
        try {
            SharedPreferences remotePreferences = service.getRemotePreferences(PREFS_NAME);
            FilterConfig remoteConfig = remotePreferences.contains(KEY_CONFIG_JSON)
                    ? load(remotePreferences)
                    : null;
            if (remoteConfig != null && remoteConfig.updatedAtEpochMs > localConfig.updatedAtEpochMs) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CONFIG_JSON, toJson(remoteConfig))
                        .commit();
                ModuleLogger.config(TAG, "ModuleConfigStore: pulled newer remote preferences into local config");
                return remoteConfig;
            }
            remotePreferences
                    .edit()
                    .putString(KEY_CONFIG_JSON, toJson(localConfig))
                    .commit();
            ModuleLogger.config(TAG, "ModuleConfigStore: synced config to remote preferences");
        } catch (RuntimeException exception) {
            ModuleLogger.configError(TAG, "ModuleConfigStore: failed to sync remote preferences", exception);
        }
        return localConfig;
    }
}
