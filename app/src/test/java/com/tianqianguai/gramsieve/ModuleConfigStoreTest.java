package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.ModuleConflictDetector;
import com.tianqianguai.gramsieve.core.RuleTextCodec;

import org.junit.Test;

public class ModuleConfigStoreTest {
    @Test
    public void jsonRoundTripPreservesActionAndRules() {
        FilterConfig config = FilterConfig.createDefault();
        config.action = FilterConfig.Action.COLLAPSE;
        config.appLanguageTag = "zh";
        config.globalRules = RuleTextCodec.parse("t.me/\nbutton:https://", FilterConfig.RuleMode.KEYWORD);
        config.globalExclusions = RuleTextCodec.parse("sender:trusted_admin", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 42L;

        String json = ModuleConfigStore.toJson(config);
        FilterConfig decoded = ModuleConfigStore.fromJson(json);

        assertEquals(FilterConfig.Action.COLLAPSE, decoded.action);
        assertEquals(FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE, decoded.appLanguageTag);
        assertEquals(2, decoded.globalRules.size());
        assertEquals(FilterConfig.RuleTarget.BUTTONS, decoded.globalRules.get(1).target);
        assertEquals(1, decoded.globalExclusions.size());
        assertTrue(decoded.updatedAtEpochMs > 0L);
    }

    @Test
    public void jsonRoundTripPreservesEnhancementSettings() {
        FilterConfig config = FilterConfig.createDefault();
        config.enhancements.setEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS, true);
        config.enhancements.setEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST, true);
        config.enhancements.setModuleFallbackEnabled(
                ModuleConflictDetector.KnownModule.TELEGRAM_SPEED_HOOK,
                true
        );
        config.enhancements.downloadParallelism = 12;
        config.enhancements.outgoingPrefix = "[GramSieve]";

        FilterConfig decoded = ModuleConfigStore.fromJson(ModuleConfigStore.toJson(config));

        assertTrue(decoded.enhancements.isEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS));
        assertTrue(decoded.enhancements.isEnabled(EnhancementConfig.Feature.DOWNLOAD_BOOST));
        assertTrue(decoded.enhancements.isModuleFallbackEnabled(
                ModuleConflictDetector.KnownModule.TELEGRAM_SPEED_HOOK
        ));
        assertEquals(12, decoded.enhancements.downloadParallelism);
        assertEquals("[GramSieve]", decoded.enhancements.outgoingPrefix);
    }
}
