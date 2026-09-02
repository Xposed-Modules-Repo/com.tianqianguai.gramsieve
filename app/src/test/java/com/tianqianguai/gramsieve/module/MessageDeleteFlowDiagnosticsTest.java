package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MessageDeleteFlowDiagnosticsTest {
    @Test
    public void recordsPopupAlertAndControllerStagesWithoutMessageContent() {
        MessageDeleteFlowDiagnostics diagnostics = new MessageDeleteFlowDiagnostics();

        diagnostics.recordPopup(-100L, 42, true, true, true, 3, 7, 480, 640);
        diagnostics.recordPopupSize(500, 700);
        diagnostics.recordAlertEntry(-100L, 42, 3);
        diagnostics.recordAlertReturn();
        diagnostics.recordControllerRequest(-100L, 1);
        assertTrue(diagnostics.consumeRecentAlert(-100L, 120_000L));
        diagnostics.recordDeleteRpc("TL_channels_deleteMessages");
        diagnostics.recordOriginRecovery();

        MessageDeleteFlowDiagnostics.Snapshot snapshot = diagnostics.snapshot();
        assertEquals(1, snapshot.popupCount);
        assertEquals(1, snapshot.alertEntryCount);
        assertEquals(1, snapshot.alertReturnCount);
        assertEquals(1, snapshot.controllerRequestCount);
        assertEquals(1, snapshot.deleteRpcCount);
        assertEquals(1, snapshot.originRecoveryCount);
        assertEquals(-100L, snapshot.lastDialogId);
        assertEquals(42, snapshot.lastMessageId);
        assertTrue(snapshot.lastDeleteItemPresent);
        assertTrue(snapshot.lastDeleteItemClickable);
        assertTrue(snapshot.lastDeleteItemHasListener);
        assertEquals(3, snapshot.lastDeleteItemIndex);
        assertEquals(7, snapshot.lastMenuItemCount);
        assertEquals(500, snapshot.lastPopupWidth);
        assertEquals(700, snapshot.lastPopupHeight);
        assertEquals(3, snapshot.lastAlertParameterCount);
        assertEquals(1, snapshot.lastControllerMessageCount);
        assertEquals("TL_channels_deleteMessages", snapshot.lastRpcType);
        assertTrue(snapshot.lastUpdatedAtMs > 0L);
    }

    @Test
    public void recentAlertCanOnlyBeConsumedOnceAndMustMatchDialog() {
        MessageDeleteFlowDiagnostics diagnostics = new MessageDeleteFlowDiagnostics();
        diagnostics.recordAlertEntry(-200L, 7, 2);

        assertTrue(diagnostics.consumeRecentAlert(-200L, 120_000L));
        org.junit.Assert.assertFalse(diagnostics.consumeRecentAlert(-200L, 120_000L));

        diagnostics.recordAlertEntry(-200L, 7, 3);
        org.junit.Assert.assertFalse(diagnostics.consumeRecentAlert(-201L, 120_000L));
        assertTrue(diagnostics.consumeRecentAlert(-200L, 120_000L));
    }
}
