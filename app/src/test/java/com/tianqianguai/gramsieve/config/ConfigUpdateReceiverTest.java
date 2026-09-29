package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleTextCodec;

import org.junit.Test;

public class ConfigUpdateReceiverTest {
    @Test
    public void mergeForPersistenceUsesNewerIncomingSnapshotAndKeepsDeletions() {
        FilterConfig local = FilterConfig.createDefault();
        local.updatedAtEpochMs = 10L;
        local.getOrCreateChatRuleSet(-1001L).rules = RuleTextCodec.parseTargeted(
                "亏损",
                FilterConfig.RuleMode.KEYWORD,
                FilterConfig.RuleTarget.TEXT
        );

        FilterConfig incoming = FilterConfig.createDefault();
        incoming.updatedAtEpochMs = 20L;

        FilterConfig merged = ConfigUpdateReceiver.mergeForPersistence(local, incoming);

        assertNull(merged.getChatRuleSet(-1001L));
        assertEquals(20L, merged.updatedAtEpochMs);
    }

    @Test
    public void mergeForPersistenceKeepsNewerLocalSnapshot() {
        FilterConfig local = FilterConfig.createDefault();
        local.updatedAtEpochMs = 30L;
        local.getOrCreateChatRuleSet(-1001L).rules = RuleTextCodec.parseTargeted(
                "8T.COM",
                FilterConfig.RuleMode.KEYWORD,
                FilterConfig.RuleTarget.TEXT
        );

        FilterConfig incoming = FilterConfig.createDefault();
        incoming.updatedAtEpochMs = 20L;
        incoming.getOrCreateChatRuleSet(-1001L).rules = RuleTextCodec.parseTargeted(
                "8t.com",
                FilterConfig.RuleMode.KEYWORD,
                FilterConfig.RuleTarget.TEXT
        );

        FilterConfig merged = ConfigUpdateReceiver.mergeForPersistence(local, incoming);

        assertEquals(1, merged.getChatRuleSet(-1001L).rules.size());
        assertEquals("8T.COM", merged.getChatRuleSet(-1001L).rules.get(0).pattern);
        assertEquals(30L, merged.updatedAtEpochMs);
    }

    @Test
    public void mergeForPersistenceTreatsEqualTimestampIncomingAsAuthoritative() {
        FilterConfig local = FilterConfig.createDefault();
        local.updatedAtEpochMs = 30L;
        local.enabled = true;

        FilterConfig incoming = FilterConfig.createDefault();
        incoming.updatedAtEpochMs = 30L;
        incoming.enabled = false;

        FilterConfig merged = ConfigUpdateReceiver.mergeForPersistence(local, incoming);

        assertEquals(false, merged.enabled);
        assertEquals(30L, merged.updatedAtEpochMs);
    }
}
