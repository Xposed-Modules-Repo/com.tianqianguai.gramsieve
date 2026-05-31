package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.tianqianguai.gramsieve.core.FilterConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class XposedConfigProvider {
    private static final String TAG = "GramSieve";
    private static final long RELOAD_THROTTLE_MS = 1500L;
    private static final Uri CONTENT_URI = ConfigContentProvider.CONTENT_URI;

    private final String modulePackageName;
    private final RemotePreferencesProvider remotePreferencesProvider;
    private volatile FilterConfig cachedConfig;
    private volatile long lastCheckedAt;
    private volatile long lastLoadedUpdatedAt;
    private Object xSharedPreferences;
    private Method reloadMethod;
    private Method hasFileChangedMethod;
    private SharedPreferences sharedPreferences;

    public XposedConfigProvider(
            String modulePackageName,
            RemotePreferencesProvider remotePreferencesProvider
    ) {
        this.modulePackageName = modulePackageName;
        this.remotePreferencesProvider = remotePreferencesProvider;
    }

    public synchronized FilterConfig getConfig(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheckedAt < RELOAD_THROTTLE_MS && cachedConfig != null) {
            return cachedConfig;
        }
        lastCheckedAt = now;
        FilterConfig remotePrefsConfig = loadFromRemotePreferences();
        if (remotePrefsConfig != null) {
            if (hasNewerAuthoritativeCache(remotePrefsConfig)) {
                return cachedConfig;
            }
            cachedConfig = remotePrefsConfig;
            lastLoadedUpdatedAt = remotePrefsConfig.updatedAtEpochMs;
            return cachedConfig;
        }
        FilterConfig remoteConfig = loadFromContentProvider(context);
        if (remoteConfig != null) {
            if (hasNewerAuthoritativeCache(remoteConfig)) {
                return cachedConfig;
            }
            cachedConfig = remoteConfig;
            lastLoadedUpdatedAt = remoteConfig.updatedAtEpochMs;
            return cachedConfig;
        }
        if (!ensureLegacyPrefs()) {
            return cachedConfig == null ? FilterConfig.createDefault() : cachedConfig;
        }
        try {
            boolean shouldReload = cachedConfig == null;
            if (hasFileChangedMethod != null) {
                Object changed = hasFileChangedMethod.invoke(xSharedPreferences);
                shouldReload = shouldReload || Boolean.TRUE.equals(changed);
            }
            if (shouldReload && reloadMethod != null) {
                reloadMethod.invoke(xSharedPreferences);
            }
            cachedConfig = ModuleConfigStore.load(sharedPreferences);
        } catch (ReflectiveOperationException ignored) {
            if (cachedConfig == null) {
                cachedConfig = FilterConfig.createDefault();
            }
        }
        return cachedConfig;
    }

    private boolean hasNewerAuthoritativeCache(FilterConfig loadedConfig) {
        return cachedConfig != null
                && lastLoadedUpdatedAt > 0L
                && loadedConfig != null
                && cachedConfig.updatedAtEpochMs > loadedConfig.updatedAtEpochMs;
    }

    public synchronized void replaceCachedConfig(FilterConfig config) {
        cachedConfig = (config == null ? FilterConfig.createDefault() : config).sanitize();
        lastLoadedUpdatedAt = cachedConfig.updatedAtEpochMs;
        lastCheckedAt = SystemClock.elapsedRealtime();
    }

    public synchronized void invalidate() {
        lastCheckedAt = 0L;
    }

    private FilterConfig loadFromRemotePreferences() {
        SharedPreferences remotePreferences = remotePreferencesProvider == null ? null : remotePreferencesProvider.get();
        if (remotePreferences == null) {
            return null;
        }
        if (!remotePreferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)) {
            ModuleLogger.config(TAG, "ConfigProvider: remote prefs empty");
            return null;
        }
        try {
            FilterConfig config = ModuleConfigStore.load(remotePreferences);
            ModuleLogger.config(TAG,
                    "ConfigProvider: remote prefs updatedAt=" + config.updatedAtEpochMs
                            + " debug=" + config.debugLogging
                            + " globalRules=" + config.globalRules.size()
            );
            return config;
        } catch (RuntimeException exception) {
            ModuleLogger.configError(TAG, "ConfigProvider: remote prefs load failed", exception);
            return null;
        }
    }

    private FilterConfig loadFromContentProvider(Context context) {
        if (context == null) {
            ModuleLogger.config(TAG, "ConfigProvider: context=null");
            return null;
        }
        try {
            Bundle bundle = context.getContentResolver().call(CONTENT_URI, ConfigContentProvider.METHOD_GET_CONFIG, null, null);
            if (bundle == null) {
                ModuleLogger.config(TAG, "ConfigProvider: bundle=null");
                return null;
            }
            long updatedAt = bundle.getLong(ConfigContentProvider.KEY_UPDATED_AT_EPOCH_MS, 0L);
            if (cachedConfig != null && updatedAt > 0L && updatedAt == lastLoadedUpdatedAt) {
                ModuleLogger.config(TAG, "ConfigProvider: using cached config updatedAt=" + updatedAt);
                return cachedConfig;
            }
            String json = bundle.getString(ConfigContentProvider.KEY_CONFIG_JSON, null);
            FilterConfig config = ModuleConfigStore.fromJson(json);
            lastLoadedUpdatedAt = config.updatedAtEpochMs;
            ModuleLogger.config(TAG,
                    "ConfigProvider: loaded updatedAt=" + updatedAt
                            + " parsedUpdatedAt=" + config.updatedAtEpochMs
                            + " debug=" + config.debugLogging
                            + " globalRules=" + config.globalRules.size()
            );
            return config;
        } catch (RuntimeException exception) {
            ModuleLogger.configError(TAG, "ConfigProvider: content provider load failed", exception);
            return null;
        }
    }

    private boolean ensureLegacyPrefs() {
        if (sharedPreferences != null) {
            return true;
        }
        try {
            Class<?> clazz = Class.forName("de.robv.android.xposed.XSharedPreferences");
            Constructor<?> constructor = clazz.getConstructor(String.class, String.class);
            xSharedPreferences = constructor.newInstance(modulePackageName, ModuleConfigStore.PREFS_NAME);
            sharedPreferences = (SharedPreferences) xSharedPreferences;
            try {
                reloadMethod = clazz.getMethod("reload");
            } catch (NoSuchMethodException ignored) {
                reloadMethod = null;
            }
            try {
                hasFileChangedMethod = clazz.getMethod("hasFileChanged");
            } catch (NoSuchMethodException ignored) {
                hasFileChangedMethod = null;
            }
            if (reloadMethod != null) {
                reloadMethod.invoke(xSharedPreferences);
            }
            ModuleLogger.config(TAG, "ConfigProvider: using XSharedPreferences fallback");
            return true;
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            ModuleLogger.config(TAG, "ConfigProvider: XSharedPreferences unavailable");
            return false;
        }
    }

    public interface RemotePreferencesProvider {
        SharedPreferences get();
    }
}
