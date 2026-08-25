package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EditHistoryPolicyStoreTest {
    @Test
    public void disabledAlwaysSkips() {
        assertFalse(EditHistoryPolicyStore.evaluate(
                false, EditHistoryPolicyStore.Mode.BLACKLIST, true));
    }

    @Test
    public void explicitRuleOverridesMode() {
        assertFalse(EditHistoryPolicyStore.evaluate(
                true, EditHistoryPolicyStore.Mode.BLACKLIST, false));
        assertTrue(EditHistoryPolicyStore.evaluate(
                true, EditHistoryPolicyStore.Mode.WHITELIST, true));
    }

    @Test
    public void modeControlsDialogsWithoutRule() {
        assertTrue(EditHistoryPolicyStore.evaluate(
                true, EditHistoryPolicyStore.Mode.BLACKLIST, null));
        assertFalse(EditHistoryPolicyStore.evaluate(
                true, EditHistoryPolicyStore.Mode.WHITELIST, null));
    }
}
