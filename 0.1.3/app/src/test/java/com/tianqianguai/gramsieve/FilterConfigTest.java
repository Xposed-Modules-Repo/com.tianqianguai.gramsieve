package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;

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
}
