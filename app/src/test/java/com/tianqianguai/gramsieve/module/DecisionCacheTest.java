package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageSnapshot;
import com.tianqianguai.gramsieve.core.RuleTextCodec;

import org.junit.Test;

public class DecisionCacheTest {
    @Test
    public void cacheKeyChangesWhenChatMetadataBecomesAvailable() {
        FilterConfig config = FilterConfig.createDefault();
        config.globalRules = RuleTextCodec.parse("chat:吃瓜联盟", FilterConfig.RuleMode.KEYWORD);
        config.updatedAtEpochMs = 1L;

        DecisionCache cache = new DecisionCache();
        FilterEngine engine = new FilterEngine();

        MessageSnapshot incompleteSnapshot = new MessageSnapshot(
                -4004L,
                201L,
                11L,
                "plain text",
                "",
                "",
                "",
                "",
                false
        );
        MessageSnapshot enrichedSnapshot = new MessageSnapshot(
                -4004L,
                201L,
                11L,
                "plain text",
                "",
                "",
                "normal_sender",
                "TG吃瓜联盟总群",
                false
        );

        FilterDecision firstDecision = cache.get(config, incompleteSnapshot, () -> engine.evaluate(config, incompleteSnapshot));
        FilterDecision secondDecision = cache.get(config, enrichedSnapshot, () -> engine.evaluate(config, enrichedSnapshot));

        assertFalse(firstDecision.matched);
        assertTrue(secondDecision.matched);
    }
}
