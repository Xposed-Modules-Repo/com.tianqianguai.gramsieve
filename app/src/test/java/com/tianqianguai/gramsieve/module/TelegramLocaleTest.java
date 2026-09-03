package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public final class TelegramLocaleTest {
    @Test
    public void recognizesChineseTelegramLocales() {
        assertTrue(TelegramLocale.isChinese(Locale.SIMPLIFIED_CHINESE));
        assertTrue(TelegramLocale.isChinese(Locale.TRADITIONAL_CHINESE));
        assertFalse(TelegramLocale.isChinese(Locale.ENGLISH));
        assertFalse(TelegramLocale.isChinese(null));
    }
}
