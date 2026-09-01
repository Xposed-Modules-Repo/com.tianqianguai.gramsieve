package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class TelegramPackagesTest {
    @Test
    public void acceptsOfficialPlayAndWebPackages() {
        assertTrue(TelegramPackages.isSupported(TelegramPackages.PLAY_PACKAGE));
        assertTrue(TelegramPackages.isSupported(TelegramPackages.WEB_PACKAGE));
    }

    @Test
    public void rejectsUnknownEmptyAndNullPackages() {
        assertFalse(TelegramPackages.isSupported("org.telegram.messenger.beta"));
        assertFalse(TelegramPackages.isSupported(""));
        assertFalse(TelegramPackages.isSupported(null));
    }

    @Test
    public void requiresFirstPackageForLifecycleHandling() {
        assertTrue(TelegramPackages.shouldHandle(
                TelegramPackages.WEB_PACKAGE, true));
        assertFalse(TelegramPackages.shouldHandle(
                TelegramPackages.WEB_PACKAGE, false));
        assertFalse(TelegramPackages.shouldHandle(
                "org.telegram.messenger.beta", true));
    }

    @Test
    public void resolvesSupportedResourcePackageAndFallsBackToPlay() {
        assertEquals(TelegramPackages.PLAY_PACKAGE,
                TelegramPackages.resolveResourcePackage(TelegramPackages.PLAY_PACKAGE));
        assertEquals(TelegramPackages.WEB_PACKAGE,
                TelegramPackages.resolveResourcePackage(TelegramPackages.WEB_PACKAGE));
        assertEquals(TelegramPackages.PLAY_PACKAGE,
                TelegramPackages.resolveResourcePackage("org.telegram.messenger.beta"));
        assertEquals(TelegramPackages.PLAY_PACKAGE,
                TelegramPackages.resolveResourcePackage(null));
    }

    @Test
    public void declaresExactlyTheTwoOfficialScopes() throws IOException {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(
                "META-INF/xposed/scope.list");
        if (stream == null) {
            throw new AssertionError("scope.list is not available on the test classpath");
        }
        String scopeText;
        try (InputStream input = stream) {
            scopeText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(
                TelegramPackages.PLAY_PACKAGE + System.lineSeparator()
                        + TelegramPackages.WEB_PACKAGE + System.lineSeparator(),
                scopeText.replace("\r\n", "\n").replace("\n", System.lineSeparator()));
    }
}
