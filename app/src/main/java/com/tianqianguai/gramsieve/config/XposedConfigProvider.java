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
    private static final long FAILURE_RETRY_MS = 30_000L;
    private static final String CONTENT_URI_STRING = "content://" + ConfigContentProvider.AUTHORITY;

    private final String modulePackageName;
    private final RemotePreferencesProvider remotePreferencesProvider;
    private final ElapsedRealtimeProvider elapsedRealtimeProvider;
    private volatile FilterConfig cachedConfig;
    private volatile long lastCheckedAt;
    private volatile long lastLoadedUpdatedAt;
    private volatile long retryAfterAt;
    private Object xSharedPreferences;
    private Method reloadMethod;
    private Method hasFileChangedMethod;
    private SharedPreferences sharedPreferences;
    private boolean remotePrefsEmptyLogged;
    private boolean remotePrefsFailureLogged;
    private boolean contentProviderUnavailable;
    private boolean contentProviderFailureLogged;
    private boolean legacyPrefsUnavailable;

    public XposedConfigProvider(
            String modulePackageName,
            RemotePreferencesProvider remotePreferencesProvider
    ) {
        this(modulePackageName, remotePreferencesProvider, SystemClock::elapsedRealtime);
    }

    XposedConfigProvider(
            String modulePackageName,
            RemotePreferencesProvider remotePreferencesProvider,
            ElapsedRealtimeProvider elapsedRealtimeProvider
    ) {
        this.modulePackageName = modulePackageName;
        this.remotePreferencesProvider = remotePreferencesProvider;
        this.elapsedRealtimeProvider = elapsedRealtimeProvider == null
                ? SystemClock::elapsedRealtime
                : elapsedRealtimeProvider;
    }

    public synchronized FilterConfig getConfig(Context context) {
        long now = elapsedRealtimeProvider.elapsedRealtime();
        FilterConfig snapshot = cachedConfig;
        if (snapshot != null
                && (now - lastCheckedAt < RELOAD_THROTTLE_MS || now < retryAfterAt)) {
            return snapshot;
        }
        lastCheckedAt = now;
        FilterConfig remotePrefsConfig = loadFromRemotePreferences();
        if (remotePrefsConfig != null) {
            if (hasNewerAuthoritativeCache(remotePrefsConfig)) {
                return cachedConfig;
            }
            return rememberLoaded(remotePrefsConfig);
        }
        FilterConfig remoteConfig = loadFromContentProvider(context);
        if (remoteConfig != null) {
            if (hasNewerAuthoritativeCache(remoteConfig)) {
                return cachedConfig;
            }
            return rememberLoaded(remoteConfig);
        }
        if (!ensureLegacyPrefs()) {
            return rememberFailure(now);
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
            retryAfterAt = 0L;
        } catch (ReflectiveOperationException ignored) {
            return rememberFailure(now);
        }
        return cachedConfig;
    }

    private FilterConfig rememberLoaded(FilterConfig config) {
        cachedConfig = (config == null ? FilterConfig.createDefault() : config).sanitize();
        lastLoadedUpdatedAt = cachedConfig.updatedAtEpochMs;
        retryAfterAt = 0L;
        return cachedConfig;
    }

    private FilterConfig rememberFailure(long now) {
        if (cachedConfig == null) {
            cachedConfig = FilterConfig.createDefault().sanitize();
        }
        retryAfterAt = now + FAILURE_RETRY_MS;
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
        lastCheckedAt = elapsedRealtimeProvider.elapsedRealtime();
        retryAfterAt = 0L;
    }

    public synchronized void invalidate() {
        lastCheckedAt = -RELOAD_THROTTLE_MS;
        retryAfterAt = 0L;
    }

    private FilterConfig loadFromRemotePreferences() {
        try {
            SharedPreferences remotePreferences = remotePreferencesProvider == null
                    ? null
                    : remotePreferencesProvider.get();
            if (remotePreferences == null) {
                return null;
            }
            if (!remotePreferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)) {
                if (!remotePrefsEmptyLogged) {
                    remotePrefsEmptyLogged = true;
                    ModuleLogger.config(TAG, "ConfigProvider: remote prefs empty; using safe cached defaults");
                }
                return null;
            }
            FilterConfig config = ModuleConfigStore.load(remotePreferences);
            if (config.updatedAtEpochMs != lastLoadedUpdatedAt) {
                ModuleLogger.config(TAG,
                        "ConfigProvider: remote prefs updatedAt=" + config.updatedAtEpochMs
                                + " debug=" + config.debugLogging
                                + " globalRules=" + config.globalRules.size()
                );
            }
            remotePrefsEmptyLogged = false;
            remotePrefsFailureLogged = false;
            return config;
        } catch (RuntimeException exception) {
            if (!remotePrefsFailureLogged) {
                remotePrefsFailureLogged = true;
                ModuleLogger.warn(
                        ModuleLogger.CAT_CONFIG,
                        TAG,
                        "ConfigProvider: remote prefs unavailable; using safe cached defaults: "
                                + exception.getClass().getSimpleName()
                );
            }
            return null;
        }
    }

    private FilterConfig loadFromContentProvider(Context context) {
        if (contentProviderUnavailable) {
            return null;
        }
        if (context == null) {
            if (!contentProviderFailureLogged) {
                contentProviderFailureLogged = true;
                ModuleLogger.config(TAG, "ConfigProvider: host context unavailable; using safe cached defaults");
            }
            return null;
        }
        try {
            Bundle bundle = context.getContentResolver().call(
                    Uri.parse(CONTENT_URI_STRING),
                    ConfigContentProvider.METHOD_GET_CONFIG,
                    null,
                    null
            );
            if (bundle == null) {
                ModuleLogger.config(TAG, "ConfigProvider: bundle=null");
                return null;
            }
            long updatedAt = bundle.getLong(ConfigContentProvider.KEY_UPDATED_AT_EPOCH_MS, 0L);
            if (cachedConfig != null && updatedAt > 0L && updatedAt == lastLoadedUpdatedAt) {
                return cachedConfig;
            }
            String json = bundle.getString(ConfigContentProvider.KEY_CONFIG_JSON, null);
            FilterConfig config = ModuleConfigStore.fromJson(json);
            if (config.updatedAtEpochMs != lastLoadedUpdatedAt) {
                ModuleLogger.config(TAG,
                        "ConfigProvider: loaded updatedAt=" + updatedAt
                                + " parsedUpdatedAt=" + config.updatedAtEpochMs
                                + " debug=" + config.debugLogging
                                + " globalRules=" + config.globalRules.size()
                );
            }
            contentProviderFailureLogged = false;
            return config;
        } catch (IllegalArgumentException exception) {
            contentProviderUnavailable = true;
            if (!contentProviderFailureLogged) {
                contentProviderFailureLogged = true;
                ModuleLogger.warn(
                        ModuleLogger.CAT_CONFIG,
                        TAG,
                        "ConfigProvider: module provider is not visible from Telegram; using remote preferences"
                );
            }
            return null;
        } catch (RuntimeException exception) {
            if (!contentProviderFailureLogged) {
                contentProviderFailureLogged = true;
                ModuleLogger.warn(
                        ModuleLogger.CAT_CONFIG,
                        TAG,
                        "ConfigProvider: module provider unavailable; using safe cached defaults: "
                                + exception.getClass().getSimpleName()
                );
            }
            return null;
        }
    }

    private boolean ensureLegacyPrefs() {
        if (sharedPreferences != null) {
            return true;
        }
        if (legacyPrefsUnavailable) {
            return false;
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
            legacyPrefsUnavailable = true;
            ModuleLogger.config(TAG, "ConfigProvider: legacy preferences unavailable; using safe cached defaults");
            return false;
        }
    }

    public interface RemotePreferencesProvider {
        SharedPreferences get();
    }

    interface ElapsedRealtimeProvider {
        long elapsedRealtime();
    }
}
