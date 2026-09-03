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

    @Test
    public void copyWithoutRulesClearsEveryRuleScopeAndPreservesOtherSettings() {
        FilterConfig original = FilterConfig.createDefault();
        original.enabled = false;
        original.debugLogging = true;
        original.action = FilterConfig.Action.COLLAPSE;
        original.appLanguageTag = FilterConfig.APP_LANGUAGE_ENGLISH;
        original.enhancements.setEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS, true);
        original.globalRules.add(rule("global"));
        original.globalExclusions.add(rule("keep"));
        FilterConfig.ChatRuleSet chat = original.getOrCreateChatRuleSet(42L);
        chat.excludeFromGlobal = true;
        chat.rules.add(rule("chat"));
        chat.exclusions.add(rule("chat keep"));

        FilterConfig cleared = original.copyWithoutRules();

        assertTrue(cleared.globalRules.isEmpty());
        assertTrue(cleared.globalExclusions.isEmpty());
        assertTrue(cleared.chatRules.isEmpty());
        assertFalse(cleared.enabled);
        assertTrue(cleared.debugLogging);
        assertEquals(FilterConfig.Action.COLLAPSE, cleared.action);
        assertEquals(FilterConfig.APP_LANGUAGE_ENGLISH, cleared.appLanguageTag);
        assertTrue(cleared.enhancements.isEnabled(EnhancementConfig.Feature.DISABLE_TYPING_STATUS));
        assertEquals(1, original.globalRules.size());
        assertEquals(1, original.globalExclusions.size());
        assertEquals(1, original.chatRules.size());
    }

    private static FilterConfig.RuleSpec rule(String pattern) {
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.pattern = pattern;
        return rule.sanitize();
    }
}
