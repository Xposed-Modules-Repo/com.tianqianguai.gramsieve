package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;

import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class XposedConfigProviderTest {
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
}
