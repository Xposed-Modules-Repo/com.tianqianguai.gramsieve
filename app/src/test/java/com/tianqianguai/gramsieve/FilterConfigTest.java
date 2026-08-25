package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;

import org.junit.Test;

public class FilterConfigTest {
    @Test
    public void sanitizeNormalizesSupportedAppLanguageTags() {
        FilterConfig config = FilterConfig.createDefault();
        config.appLanguageTag = "zh-Hans";

        config.sanitize();

        assertEquals(FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE, config.appLanguageTag);
    }

    @Test
    public void sanitizeFallsBackToSystemForUnknownAppLanguageTags() {
        FilterConfig config = FilterConfig.createDefault();
        config.appLanguageTag = "fr";

        config.sanitize();

        assertEquals(FilterConfig.APP_LANGUAGE_SYSTEM, config.appLanguageTag);
    }

    @Test
    public void deepCopyKeepsEnhancementsIndependent() {
        FilterConfig original = FilterConfig.createDefault();
        original.enhancements.setEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS, true);

        FilterConfig copy = original.deepCopy();
        copy.enhancements.setEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS, false);

        assertTrue(original.enhancements.isEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS));
        assertFalse(copy.enhancements.isEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS));
    }
}
