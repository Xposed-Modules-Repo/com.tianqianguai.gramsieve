package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageSnapshot;
import com.tianqianguai.gramsieve.core.RuleTextCodec;

import org.junit.Test;

public class FilterEngineTest {
    @Test
    public void exclusionWinsOverKeywordRule() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("t.me/", FilterConfig.RuleMode.KEYWORD);
        config.globalExclusions = RuleTextCodec.parse("sender:trusted_admin", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 1L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                42L,
                7L,
                "join t.me/spam_now",
                "",
                "",
                "trusted_admin",
                "test group",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertFalse(decision.matched);
        assertTrue(decision.excluded);
    }

    @Test
    public void regexMatchesButtonText() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("button:https?://", FilterConfig.RuleMode.REGEX);
        config.updatedAtEpochMs = 2L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -1001L,
                77L,
                8L,
                "",
                "",
                "Open https://spam.example",
                "merchant_bot",
                "promo room",
                true
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertTrue(decision.matched);
        assertEquals(FilterConfig.Action.HIDE, decision.action);
    }

    @Test
    public void perChatOverrideCanIgnoreGlobalRules() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("buy now", FilterConfig.RuleMode.KEYWORD);
        FilterConfig.ChatRuleSet chatRuleSet = config.getOrCreateChatRuleSet(-2002L);
        chatRuleSet.excludeFromGlobal = true;
        chatRuleSet.rules = RuleTextCodec.parse("sender:bad_actor", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 3L;

        MessageSnapshot sameTextDifferentChat = new MessageSnapshot(
                -2002L,
                18L,
                9L,
                "buy now special offer",
                "",
                "",
                "neutral_sender",
                "ignored chat",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, sameTextDifferentChat);
        assertFalse(decision.matched);
    }

    @Test
    public void senderTargetedRuleMatchesBeforeGenericKeyword() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("sender:promo_bot\nfree", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 4L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -3003L,
                123L,
                10L,
                "ordinary message",
                "",
                "",
                "promo_bot",
                "channel",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertTrue(decision.matched);
        assertTrue(decision.reason.startsWith("hard match"));
    }

    @Test
    public void chatTargetedRuleMatchesEveryMessageFromMatchingChat() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("chat:吃瓜联盟", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 5L;

        MessageSnapshot firstMessage = new MessageSnapshot(
                -4004L,
                201L,
                11L,
                "普通聊天内容",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );
        MessageSnapshot secondMessage = new MessageSnapshot(
                -4004L,
                202L,
                12L,
                "完全不含关键词",
                "",
                "",
                "another_sender",
                "TG吃瓜联盟总群",
                false
        );

        FilterDecision firstDecision = new FilterEngine().evaluate(config, firstMessage);
        FilterDecision secondDecision = new FilterEngine().evaluate(config, secondMessage);

        assertTrue(firstDecision.matched);
        assertTrue(secondDecision.matched);
        assertTrue(firstDecision.reason.startsWith("hard match"));
        assertTrue(secondDecision.reason.startsWith("hard match"));
    }

    @Test
    public void chatTargetedRuleAlsoMatchesDialogId() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("chat:-100123", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 6L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -1001234567890L,
                203L,
                13L,
                "plain text",
                "",
                "",
                "sender",
                "Some unrelated title",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertTrue(decision.matched);
        assertTrue(decision.reason.startsWith("hard match"));
    }

    @Test
    public void chatScopedChatTargetRuleDoesNotHideEntireChat() {
        FilterConfig config = FilterConfig.createDefault();
        FilterConfig.ChatRuleSet chatRuleSet = config.getOrCreateChatRuleSet(-5005L);
        chatRuleSet.rules = RuleTextCodec.parse("chat:吃瓜联盟", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 7L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -5005L,
                204L,
                14L,
                "普通聊天内容",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertFalse(decision.matched);
    }

    @Test
    public void chatScopedTextRuleStillFiltersMatchingMessage() {
        FilterConfig config = FilterConfig.createDefault();
        FilterConfig.ChatRuleSet chatRuleSet = config.getOrCreateChatRuleSet(-5006L);
        chatRuleSet.rules = RuleTextCodec.parse("text:只屏蔽这句", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 8L;

        MessageSnapshot matchingSnapshot = new MessageSnapshot(
                -5006L,
                205L,
                15L,
                "普通聊天，但是只屏蔽这句",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );
        MessageSnapshot unrelatedSnapshot = new MessageSnapshot(
                -5006L,
                206L,
                16L,
                "普通聊天内容",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );

        FilterDecision matchingDecision = new FilterEngine().evaluate(config, matchingSnapshot);
        FilterDecision unrelatedDecision = new FilterEngine().evaluate(config, unrelatedSnapshot);

        assertTrue(matchingDecision.matched);
        assertFalse(unrelatedDecision.matched);
    }

    @Test
    public void chatScopedChatTargetExclusionDoesNotKeepEntireChat() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("text:全局屏蔽词", FilterConfig.RuleMode.KEYWORD);
        FilterConfig.ChatRuleSet chatRuleSet = config.getOrCreateChatRuleSet(-5007L);
        chatRuleSet.exclusions = RuleTextCodec.parse("chat:吃瓜联盟", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 9L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -5007L,
                207L,
                17L,
                "这条包含全局屏蔽词",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertTrue(decision.matched);
        assertFalse(decision.excluded);
    }

    @Test
    public void textTargetAlsoMatchesCaptionAndButtons() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("text:放心赢", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 10L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -6006L,
                208L,
                18L,
                "图片",
                "普通图片说明",
                "8T.COM放心赢 https://usdt03.ag/?cid=505817",
                "sender",
                "spam room",
                true
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertTrue(decision.matched);
        assertTrue(decision.reason.startsWith("keyword match"));
    }

    @Test
    public void obviousGamblingPromotionDoesNotMatchWithoutExplicitRule() {
        FilterConfig config = FilterConfig.createDefault();
        config.updatedAtEpochMs = 11L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -7007L,
                209L,
                19L,
                "图片",
                "8T娱乐城 全球数字网投领导者",
                "立即加入 https://usdt03.ag/?cid=505817",
                "promo_bot",
                "spam room",
                true
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertFalse(decision.matched);
    }

    @Test
    public void gamblingDiscussionWithoutPromoCueDoesNotTriggerBuiltinHeuristic() {
        FilterConfig config = FilterConfig.createDefault();
        config.updatedAtEpochMs = 12L;

        MessageSnapshot snapshot = new MessageSnapshot(
                -8008L,
                210L,
                20L,
                "今天在讨论博彩行业监管变化",
                "",
                "",
                "news_sender",
                "news room",
                false
        );

        FilterDecision decision = new FilterEngine().evaluate(config, snapshot);
        assertFalse(decision.matched);
    }
}
