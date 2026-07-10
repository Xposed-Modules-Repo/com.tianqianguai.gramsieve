package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
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
}
