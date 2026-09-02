package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class HotReloadContractTest {
    @Test
    public void moduleMetadataOptsIntoApi102AutoHotReload() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = resource("META-INF/xposed/module.prop")) {
            properties.load(input);
        }

        assertEquals("102", properties.getProperty("minApiVersion"));
        assertEquals("102", properties.getProperty("targetApiVersion"));
        assertEquals("true", properties.getProperty("autoHotReload"));
        assertEquals("protective", properties.getProperty("exceptionMode"));
    }

    @Test
    public void moduleDeclaresExactlyOneJavaEntry() throws Exception {
        String content;
        try (InputStream input = resource("META-INF/xposed/java_init.list")) {
            content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> entries = Arrays.stream(content.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertEquals(List.of("com.tianqianguai.gramsieve.module.GramSieveModule"), entries);
    }

    @Test
    public void reloadPackageFallsBackFromApplicationToSavedStateAndProcess() {
        assertEquals(TelegramPackages.WEB_PACKAGE, TelegramPackages.resolveReloadPackage(
                TelegramPackages.WEB_PACKAGE, TelegramPackages.PLAY_PACKAGE, "ignored"));
        assertEquals(TelegramPackages.PLAY_PACKAGE, TelegramPackages.resolveReloadPackage(
                "unsupported", TelegramPackages.PLAY_PACKAGE, "ignored"));
        assertEquals(TelegramPackages.WEB_PACKAGE, TelegramPackages.resolveReloadPackage(
                null, null, TelegramPackages.WEB_PACKAGE + ":push"));
        assertTrue(TelegramPackages.resolveReloadPackage(null, null, "unsupported").isEmpty());
    }

    @Test
    public void hookIdsAreStablePerCallerAndDistinctAcrossCallers() throws Exception {
        Method method = String.class.getDeclaredMethod("substring", int.class, int.class);

        String first = firstHookId(method);
        assertEquals(first, firstHookId(method));
        assertNotEquals(first, secondHookId(method));
        assertTrue(first.contains("java.lang.String#substring(int,int)"));
    }

    private static String firstHookId(Method method) {
        return HookIdentity.forCaller("test", method);
    }

    private static String secondHookId(Method method) {
        return HookIdentity.forCaller("test", method);
    }

    private static InputStream resource(String path) {
        InputStream input = HotReloadContractTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull("Missing test resource " + path, input);
        return input;
    }
}
