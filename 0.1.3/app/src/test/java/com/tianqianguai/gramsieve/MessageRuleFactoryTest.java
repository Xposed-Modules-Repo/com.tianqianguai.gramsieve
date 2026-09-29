package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageRuleFactory;
import com.tianqianguai.gramsieve.core.MessageSnapshot;

import org.junit.Test;

import java.util.List;

public class MessageRuleFactoryTest {
    @Test
    public void exactContentRuleMatchesOnlyTheClickedTextInCurrentChat() {
        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "只屏蔽这一整句",
                "",
                "",
                "sender",
                "chat",
                false
        );
        FilterConfig.RuleSpec rule = MessageRuleFactory.exactContentRule(snapshot);
        assertEquals(FilterConfig.RuleMode.REGEX, rule.mode);
        assertEquals(FilterConfig.RuleTarget.TEXT, rule.target);

        FilterConfig config = FilterConfig.createDefault();
        config.getOrCreateChatRuleSet(-1001L).rules.add(rule);
        config.updatedAtEpochMs = 1L;

        FilterDecision exact = new FilterEngine().evaluate(config, snapshot);
        FilterDecision partial = new FilterEngine().evaluate(config, new MessageSnapshot(
                -1001L,
                42L,
                8L,
                "前缀 只屏蔽这一整句 后缀",
                "",
                "",
                "sender",
                "chat",
                false
        ));
        FilterDecision otherChat = new FilterEngine().evaluate(config, new MessageSnapshot(
                -1002L,
                42L,
                9L,
                "只屏蔽这一整句",
                "",
                "",
                "sender",
                "other chat",
                false
        ));

        assertTrue(exact.matched);
        assertFalse(partial.matched);
        assertFalse(otherChat.matched);
    }

    @Test
    public void exactContentRulePrefersMoreSpecificCaptionOverGenericText() {
        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "图片",
                "媒体说明里有更完整的广告内容",
                "",
                "sender",
                "chat",
                false
        );

        FilterConfig.RuleSpec rule = MessageRuleFactory.exactContentRule(snapshot);

        assertEquals(FilterConfig.RuleTarget.CAPTION, rule.target);
        assertTrue(rule.pattern.contains("媒体说明里有更完整的广告内容"));
    }

    @Test
    public void exactContentRuleReturnsNullWhenMessageHasNoVisibleContent() {
        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "",
                "",
                "",
                "sender",
                "chat",
                false
        );

        assertNull(MessageRuleFactory.exactContentRule(snapshot));
    }

    @Test
    public void equivalentRuleDetectionAvoidsDuplicateAutoRules() {
        FilterConfig.RuleSpec rule = MessageRuleFactory.exactContentRule(new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "重复内容",
                "",
                "",
                "sender",
                "chat",
                false
        ));
        FilterConfig.ChatRuleSet chatRuleSet = new FilterConfig.ChatRuleSet();
        chatRuleSet.rules.add(rule.deepCopy());

        assertTrue(MessageRuleFactory.containsEquivalentRule(chatRuleSet.rules, rule));
    }

    @Test
    public void automaticRulesIncludeStablePromoTokensForSameChatOnly() {
        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "图片",
                "8T娱乐城 全球数字网投领导者",
                "立即加入 8T.COM https://usdt03.ag/?cid=505817 @promo_admin",
                "sender",
                "chat",
                true
        );

        List<FilterConfig.RuleSpec> rules = MessageRuleFactory.automaticRules(snapshot);

        assertTrue(hasKeyword(rules, "8t.com"));
        assertTrue(hasKeyword(rules, "usdt03.ag"));
        assertTrue(hasKeyword(rules, "@promo_admin"));

        FilterConfig config = FilterConfig.createDefault();
        config.getOrCreateChatRuleSet(-1001L).rules.addAll(rules);
        config.updatedAtEpochMs = 2L;

        MessageSnapshot changedPromo = new MessageSnapshot(
                -1001L,
                99L,
                8L,
                "今晚活动更新",
                "",
                "进入 8T.COM 领取福利",
                "other",
                "chat",
                true
        );
        MessageSnapshot otherChat = new MessageSnapshot(
                -1002L,
                99L,
                9L,
                "今晚活动更新",
                "",
                "进入 8T.COM 领取福利",
                "other",
                "other chat",
                true
        );

        FilterEngine engine = new FilterEngine();
        assertTrue(engine.evaluate(config, changedPromo).matched);
        assertFalse(engine.evaluate(config, otherChat).matched);
    }

    private boolean hasKeyword(List<FilterConfig.RuleSpec> rules, String pattern) {
        for (FilterConfig.RuleSpec rule : rules) {
            if (rule.mode == FilterConfig.RuleMode.KEYWORD && pattern.equals(rule.pattern)) {
                return true;
            }
        }
        return false;
    }
}
