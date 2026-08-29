package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class XposedConfigProviderTest {
    @Test
    public void newerTelegramHostPreferencesOverrideOlderRemotePreferences() {
        FilterConfig hostConfig = FilterConfig.createDefault();
        hostConfig.updatedAtEpochMs = 20L;
        hostConfig.enhancements.setEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID, true);
        SharedPreferences hostPreferences = preferencesWith(hostConfig);

        FilterConfig remoteConfig = FilterConfig.createDefault();
        remoteConfig.updatedAtEpochMs = 10L;
        SharedPreferences remotePreferences = preferencesWith(remoteConfig);

        Context context = mock(Context.class);
        when(context.getSharedPreferences("gramsieve_host_rules", Context.MODE_PRIVATE))
                .thenReturn(hostPreferences);
        XposedConfigProvider provider = new XposedConfigProvider(
                "com.tianqianguai.gramsieve",
                () -> remotePreferences,
                () -> 1_000L
        );

        FilterConfig loaded = provider.getConfig(context);

        assertTrue(loaded.enhancements.isEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID));
        assertTrue(loaded.updatedAtEpochMs == 20L);
    }

    @Test
    public void persistsCompleteSnapshotInsideTelegramHostPreferences() {
        FilterConfig config = FilterConfig.createDefault();
        config.updatedAtEpochMs = 50L;
        config.enhancements.setEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID, true);
        String json = ModuleConfigStore.toJson(config);

        SharedPreferences preferences = mock(SharedPreferences.class);
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(ModuleConfigStore.KEY_CONFIG_JSON, json)).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
        when(preferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null)).thenReturn(json);
        Context context = mock(Context.class);
        when(context.getSharedPreferences("gramsieve_host_rules", Context.MODE_PRIVATE))
                .thenReturn(preferences);
        XposedConfigProvider provider = new XposedConfigProvider(
                "com.tianqianguai.gramsieve",
                () -> null,
                () -> 1_000L
        );

        FilterConfig saved = provider.persistHostConfig(context, config);

        assertTrue(saved != null);
        assertTrue(saved.updatedAtEpochMs == 50L);
        assertTrue(saved.enhancements.isEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID));
    }

    @Test
    public void failedSourcesCacheSafeDefaultsUntilRetryWindow() {
        SharedPreferences remotePreferences = mock(SharedPreferences.class);
        when(remotePreferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)).thenReturn(false);
        AtomicInteger remoteLoads = new AtomicInteger();
        AtomicLong clock = new AtomicLong(1_000L);
        XposedConfigProvider provider = new XposedConfigProvider(
                "com.tianqianguai.gramsieve",
                () -> {
                    remoteLoads.incrementAndGet();
                    return remotePreferences;
                },
                clock::get
        );

        FilterConfig first = provider.getConfig(null);
        FilterConfig immediate = provider.getConfig(null);
        clock.set(10_000L);
        FilterConfig beforeRetry = provider.getConfig(null);

        assertSame(first, immediate);
        assertSame(first, beforeRetry);
        assertTrue(first.enhancements.enabled.isEmpty());
        assertTrue(first.enabled);
        assertTrue(remoteLoads.get() == 1);

        clock.set(31_001L);
        provider.getConfig(null);

        assertTrue(remoteLoads.get() == 2);
    }

    @Test
    public void explicitReplacementOverridesFailureFallbackImmediately() {
        SharedPreferences remotePreferences = mock(SharedPreferences.class);
        when(remotePreferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)).thenReturn(false);
        AtomicLong clock = new AtomicLong(1_000L);
        XposedConfigProvider provider = new XposedConfigProvider(
                "com.tianqianguai.gramsieve",
                () -> remotePreferences,
                clock::get
        );
        provider.getConfig(null);
        FilterConfig replacement = FilterConfig.createDefault();
        replacement.enhancements.setEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID, true);

        provider.replaceCachedConfig(replacement);
        FilterConfig loaded = provider.getConfig(null);

        assertTrue(loaded.enhancements.isEnabled(EnhancementConfig.Feature.SHOW_MESSAGE_ID));
        assertFalse(loaded.enhancements.enabled.isEmpty());
    }

    private SharedPreferences preferencesWith(FilterConfig config) {
        SharedPreferences preferences = mock(SharedPreferences.class);
        when(preferences.contains(ModuleConfigStore.KEY_CONFIG_JSON)).thenReturn(true);
        when(preferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null))
                .thenReturn(ModuleConfigStore.toJson(config));
        return preferences;
    }
}
