package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;

import org.junit.Test;

import java.util.List;

public class HostConfigPanelTest {
    @Test
    public void applyGlobalDraftUpdatesGlobalSettingsAndRules() {
        FilterConfig original = FilterConfig.createDefault();
        original.globalRules.add(rule(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD, "old"));

        RuleDraftMatrix match = new RuleDraftMatrix();
        match.set(FilterConfig.RuleTarget.ANY, FilterConfig.RuleMode.KEYWORD, "spam");
        match.set(FilterConfig.RuleTarget.CHAT, FilterConfig.RuleMode.REGEX, "^bad group$");
        RuleDraftMatrix keep = new RuleDraftMatrix();
        keep.set(FilterConfig.RuleTarget.SENDER, FilterConfig.RuleMode.KEYWORD, "trusted_admin");

        FilterConfig updated = HostConfigPanel.applyGlobalDraft(
                original,
                false,
                true,
                "zh-Hans",
                FilterConfig.Action.COLLAPSE,
                match,
                keep
        );

        assertFalse(updated.enabled);
        assertTrue(updated.debugLogging);
        assertEquals(FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE, updated.appLanguageTag);
        assertEquals(FilterConfig.Action.COLLAPSE, updated.action);
        assertEquals(2, updated.globalRules.size());
        assertEquals(FilterConfig.RuleTarget.ANY, updated.globalRules.get(0).target);
        assertEquals("spam", updated.globalRules.get(0).pattern);
        assertEquals(FilterConfig.RuleTarget.CHAT, updated.globalRules.get(1).target);
        assertEquals(FilterConfig.RuleMode.REGEX, updated.globalRules.get(1).mode);
        assertEquals(1, updated.globalExclusions.size());
        assertEquals(FilterConfig.RuleTarget.SENDER, updated.globalExclusions.get(0).target);
    }

    @Test
    public void applyChatDraftRemovesSemanticallyEmptyChatRules() {
        FilterConfig original = FilterConfig.createDefault();
        FilterConfig.ChatRuleSet chatRules = original.getOrCreateChatRuleSet(42L);
        chatRules.rules.add(rule(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD, "old"));

        FilterConfig updated = HostConfigPanel.applyChatDraft(
                original,
                42L,
                true,
                false,
                new RuleDraftMatrix(),
                new RuleDraftMatrix()
        );

        assertFalse(updated.chatRules.containsKey(FilterConfig.chatKey(42L)));
    }

    @Test
    public void applyChatDraftDoesNotExportChatTargetRules() {
        FilterConfig original = FilterConfig.createDefault();
        RuleDraftMatrix match = new RuleDraftMatrix();
        match.set(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD, "spam");
        match.set(FilterConfig.RuleTarget.CHAT, FilterConfig.RuleMode.KEYWORD, "group name");

        FilterConfig updated = HostConfigPanel.applyChatDraft(
                original,
                42L,
                true,
                true,
                match,
                new RuleDraftMatrix()
        );

        List<FilterConfig.RuleSpec> rules = updated.getChatRuleSet(42L).rules;
        assertEquals(1, rules.size());
        assertEquals(FilterConfig.RuleTarget.TEXT, rules.get(0).target);
        assertEquals("spam", rules.get(0).pattern);
    }

    private static FilterConfig.RuleSpec rule(
            FilterConfig.RuleTarget target,
            FilterConfig.RuleMode mode,
            String pattern
    ) {
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.target = target;
        rule.mode = mode;
        rule.pattern = pattern;
        return rule.sanitize();
    }
}
