package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SelectAllAppearanceTest {
    @Test
    public void prefersTelegramLocalizedLabelAndRefreshesFallbackLanguage() {
        assertEquals("Tout sélectionner", SelectAllAppearance.label("Tout sélectionner", false));
        assertEquals("Select All", SelectAllAppearance.label("", false));
        assertEquals("全选", SelectAllAppearance.label(null, true));
    }

    @Test
    public void usesVisiblePeerColorInsteadOfHardCodedWhite() {
        assertEquals(
                0xFF111111,
                SelectAllAppearance.foregroundColor(
                        0xFF111111,
                        0xFF222222,
                        0xFF333333,
                        false
                )
        );
    }

    @Test
    public void fallsBackThroughThemePlatformAndDayNightDefaults() {
        assertEquals(
                0xFF222222,
                SelectAllAppearance.foregroundColor(0x00111111, 0xFF222222, null, false)
        );
        assertEquals(
                0xFF333333,
                SelectAllAppearance.foregroundColor(null, 0x00222222, 0xFF333333, false)
        );
        assertEquals(
                0xFF000000,
                SelectAllAppearance.foregroundColor(null, null, null, false)
        );
        assertEquals(
                0xFFFFFFFF,
                SelectAllAppearance.foregroundColor(null, null, null, true)
        );
    }
}
