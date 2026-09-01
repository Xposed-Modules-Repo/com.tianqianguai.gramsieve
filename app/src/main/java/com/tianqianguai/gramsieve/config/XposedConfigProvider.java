package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import com.tianqianguai.gramsieve.core.FilterConfig;

public final class XposedConfigProvider {
    private static final String TAG = "GramSieve";
    private static final long RELOAD_THROTTLE_MS = 1500L;
    private static final long FAILURE_RETRY_MS = 30_000L;
    private static final String CONTENT_URI_STRING = "content://" + ConfigContentProvider.AUTHORITY;
    private static final String HOST_PREFS_NAME = "gramsieve_host_rules";

    private final RemotePreferencesProvider remotePreferencesProvider;
    private final ElapsedRealtimeProvider elapsedRealtimeProvider;
    private volatile FilterConfig cachedConfig;
    private volatile long lastCheckedAt;
    private volatile long lastLoadedUpdatedAt;
    private volatile long retryAfterAt;
    private boolean remotePrefsEmptyLogged;
    private boolean remotePrefsFailureLogged;
    private boolean contentProviderUnavailable;
    private boolean contentProviderFailureLogged;
    private long lastHostPrefsLoggedAt;

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
        FilterConfig hostConfig = loadFromHostPreferences(context);
        FilterConfig remotePrefsConfig = loadFromRemotePreferences();
        FilterConfig preferredConfig = newerConfig(hostConfig, remotePrefsConfig);
        if (preferredConfig != null) {
            if (hasNewerAuthoritativeCache(preferredConfig)) {
                return cachedConfig;
            }
            return rememberLoaded(preferredConfig);
        }
        FilterConfig remoteConfig = loadFromContentProvider(context);
        if (remoteConfig != null) {
            if (hasNewerAuthoritativeCache(remoteConfig)) {
                return cachedConfig;
            }
            return rememberLoaded(remoteConfig);
        }
        return rememberFailure(now);
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

    /** Stores the authoritative config inside Telegram so host-native settings survive restarts. */
    public synchronized FilterConfig persistHostConfig(Context context, FilterConfig config) {
        if (context == null || config == null) {
            return null;
        }
        try {
            FilterConfig safe = config.deepCopy().sanitize();
            if (safe.updatedAtEpochMs <= 0L) {
                safe.updatedAtEpochMs = System.currentTimeMillis();
            }
            SharedPreferences preferences = context.getSharedPreferences(
                    HOST_PREFS_NAME,
                    Context.MODE_PRIVATE
            );
            String json = ModuleConfigStore.toJson(safe);
            if (!preferences.edit()
                    .putString(ModuleConfigStore.KEY_CONFIG_JSON, json)
                    .commit()) {
                return null;
            }
            String savedJson = preferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null);
            if (!json.equals(savedJson)) {
                return null;
            }
            FilterConfig saved = ModuleConfigStore.fromJson(savedJson);
            rememberLoaded(saved);
            lastCheckedAt = elapsedRealtimeProvider.elapsedRealtime();
            return saved.deepCopy();
        } catch (RuntimeException exception) {
            ModuleLogger.warn(
                    ModuleLogger.CAT_CONFIG,
                    TAG,
                    "ConfigProvider: failed to persist Telegram host preferences: "
                            + exception.getClass().getSimpleName()
            );
            return null;
        }
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

    private FilterConfig loadFromHostPreferences(Context context) {
        if (context == null) {
            return null;
        }
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    HOST_PREFS_NAME,
                    Context.MODE_PRIVATE
            );
            if (!preferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)) {
                return null;
            }
            FilterConfig config = ModuleConfigStore.load(preferences);
            if (config.updatedAtEpochMs != lastHostPrefsLoggedAt) {
                lastHostPrefsLoggedAt = config.updatedAtEpochMs;
                ModuleLogger.config(
                        TAG,
                        "ConfigProvider: Telegram host prefs updatedAt=" + config.updatedAtEpochMs
                                + " globalRules=" + config.globalRules.size()
                );
            }
            return config;
        } catch (RuntimeException exception) {
            ModuleLogger.warn(
                    ModuleLogger.CAT_CONFIG,
                    TAG,
                    "ConfigProvider: Telegram host prefs unavailable: "
                            + exception.getClass().getSimpleName()
            );
            return null;
        }
    }

    private FilterConfig newerConfig(FilterConfig first, FilterConfig second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.updatedAtEpochMs >= second.updatedAtEpochMs ? first : second;
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
        if (context.getPackageManager().resolveContentProvider(
                ConfigContentProvider.AUTHORITY,
                0
        ) == null) {
            contentProviderUnavailable = true;
            if (!contentProviderFailureLogged) {
                contentProviderFailureLogged = true;
                ModuleLogger.config(
                        TAG,
                        "ConfigProvider: module provider is not visible from Telegram"
                );
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

    public interface RemotePreferencesProvider {
        SharedPreferences get();
    }

    interface ElapsedRealtimeProvider {
        long elapsedRealtime();
    }
}
