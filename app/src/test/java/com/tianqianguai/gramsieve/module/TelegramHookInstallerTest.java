package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TelegramHookInstallerTest {
    @Test
    public void onlyCommittedDialogDeleteCallMutatesLocalState() {
        assertTrue(TelegramHookInstaller.isCommittedDialogDeleteCall(
                "performDeleteOrClearDialogAction"));
        assertFalse(TelegramHookInstaller.isCommittedDialogDeleteCall(
                "lambda$performSelectedDialogsAction$105"));
        assertFalse(TelegramHookInstaller.isCommittedDialogDeleteCall(
                "performSelectedDialogsAction"));
    }

    @Test
    public void logTailLimitDefaultsAndRejectsUnsafeValues() {
        assertEquals(300, TelegramHookInstaller.parseLogLineLimit(""));
        assertEquals(25, TelegramHookInstaller.parseLogLineLimit(" 25 "));
        try {
            TelegramHookInstaller.parseLogLineLimit("1201");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("limit above the bounded tail must be rejected");
    }
}
