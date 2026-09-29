package com.tianqianguai.gramsieve;

import static org.junit.Assert.assertEquals;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;
import com.tianqianguai.gramsieve.core.RuleTextCodec;

import org.junit.Test;

import java.util.List;

public class RuleTextCodecTest {
    @Test
    public void parseRespectsTargetPrefixes() {
        List<FilterConfig.RuleSpec> rules = RuleTextCodec.parse(
                "sender:promo_bot\nbutton:https://\nfree money",
                FilterConfig.RuleMode.KEYWORD
        );

        assertEquals(3, rules.size());
        assertEquals(FilterConfig.RuleTarget.SENDER, rules.get(0).target);
        assertEquals("promo_bot", rules.get(0).pattern);
        assertEquals(FilterConfig.RuleTarget.BUTTONS, rules.get(1).target);
        assertEquals(FilterConfig.RuleTarget.ANY, rules.get(2).target);
    }

    @Test
    public void formatRoundTripsNormalizedText() {
        List<FilterConfig.RuleSpec> rules = RuleTextCodec.parse(
                "caption:airdrop\ntext:稳赚",
                FilterConfig.RuleMode.KEYWORD
        );

        String formatted = RuleTextCodec.format(rules);
        assertEquals("caption:airdrop\ntext:稳赚", formatted);
    }

    @Test
    public void targetedParsingStripsMatchingPrefixForDedicatedField() {
        List<FilterConfig.RuleSpec> rules = RuleTextCodec.parseTargeted(
                "sender:promo_bot\ntrusted_admin",
                FilterConfig.RuleMode.KEYWORD,
                FilterConfig.RuleTarget.SENDER
        );

        assertEquals(2, rules.size());
        assertEquals(FilterConfig.RuleTarget.SENDER, rules.get(0).target);
        assertEquals("promo_bot", rules.get(0).pattern);
        assertEquals("trusted_admin", rules.get(1).pattern);
    }

    @Test
    public void draftMatrixRoundTripsPerTargetFields() {
        List<FilterConfig.RuleSpec> rules = RuleTextCodec.parse(
                "buy now\ntext:free usdt\nsender:promo_bot\nchat:吃瓜联盟",
                FilterConfig.RuleMode.KEYWORD
        );
        rules.addAll(RuleTextCodec.parse(
                "button:https?://\ncaption:^空投",
                FilterConfig.RuleMode.REGEX
        ));

        RuleDraftMatrix matrix = RuleDraftMatrix.fromRules(rules);

        assertEquals("buy now", matrix.get(FilterConfig.RuleTarget.ANY, FilterConfig.RuleMode.KEYWORD));
        assertEquals("free usdt", matrix.get(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD));
        assertEquals("promo_bot", matrix.get(FilterConfig.RuleTarget.SENDER, FilterConfig.RuleMode.KEYWORD));
        assertEquals("吃瓜联盟", matrix.get(FilterConfig.RuleTarget.CHAT, FilterConfig.RuleMode.KEYWORD));
        assertEquals("^空投", matrix.get(FilterConfig.RuleTarget.CAPTION, FilterConfig.RuleMode.REGEX));
        assertEquals("https?://", matrix.get(FilterConfig.RuleTarget.BUTTONS, FilterConfig.RuleMode.REGEX));

        List<FilterConfig.RuleSpec> rebuilt = matrix.toRules();
        assertEquals("buy now", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.KEYWORD, FilterConfig.RuleTarget.ANY));
        assertEquals("free usdt", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.KEYWORD, FilterConfig.RuleTarget.TEXT));
        assertEquals("promo_bot", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.KEYWORD, FilterConfig.RuleTarget.SENDER));
        assertEquals("吃瓜联盟", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.KEYWORD, FilterConfig.RuleTarget.CHAT));
        assertEquals("^空投", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.REGEX, FilterConfig.RuleTarget.CAPTION));
        assertEquals("https?://", RuleTextCodec.formatTargeted(rebuilt, FilterConfig.RuleMode.REGEX, FilterConfig.RuleTarget.BUTTONS));
    }
}
