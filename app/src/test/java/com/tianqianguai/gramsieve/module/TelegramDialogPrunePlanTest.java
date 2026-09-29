package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class TelegramDialogPrunePlanTest {
    @Test
    public void directDialogPlanIncludesVerifiedTelegramTables() {
        Set<String> operations = operationNames();

        assertTrue(operations.contains("dialogs.did"));
        assertTrue(operations.contains("dialog_settings.did"));
        assertTrue(operations.contains("messages_v2.uid"));
        assertTrue(operations.contains("messages_holes.uid"));
        assertTrue(operations.contains("media_v4.uid"));
        assertTrue(operations.contains("media_counts_v2.uid"));
        assertTrue(operations.contains("media_holes_v2.uid"));
        assertTrue(operations.contains("reaction_mentions.dialog_id"));
        assertTrue(operations.contains("webpage_pending_v2.uid"));
    }

    @Test
    public void directDialogPlanDoesNotTouchProfileOrPluginTables() {
        Set<String> operations = operationNames();

        assertFalse(operations.contains("users.uid"));
        assertFalse(operations.contains("user_settings.uid"));
        assertFalse(operations.contains("bot_info_v2.uid"));
        assertFalse(operations.contains("channel_users_v2.uid"));
        assertFalse(operations.contains("channel_admins_v3.uid"));
        assertFalse(operations.contains("messages.id"));
        assertFalse(operations.contains("download_queue.uid"));
        assertFalse(operations.contains("saved_dialogs.did"));
        assertFalse(operations.contains("requested_holes.uid"));
    }

    private Set<String> operationNames() {
        Set<String> operations = new HashSet<>();
        for (TelegramDialogPrunePlan.Operation operation : TelegramDialogPrunePlan.directDialogOperations()) {
            operations.add(operation.table + "." + operation.column);
        }
        return operations;
    }
}
