package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class TelegramDeleteResidueStoreTest {
    @Test
    public void readsLittleEndianFlagsWithoutMessageContent() {
        byte[] serialized = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x11223344)
                .putInt(0x80000120)
                .putInt(99)
                .array();

        assertEquals(0x80000120, TelegramDeleteResidueStore.serializedFlags(serialized));
    }

    @Test
    public void shortOrMissingPayloadHasNoFlags() {
        assertEquals(0, TelegramDeleteResidueStore.serializedFlags(null));
        assertEquals(0, TelegramDeleteResidueStore.serializedFlags(new byte[7]));
    }

    @Test
    public void decodesOnlyTelegramDeletePendingTaskMetadata() {
        byte[] serialized = ByteBuffer.allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(24)
                .putLong(-4484683155L)
                .putLong(0x1122334455667788L)
                .array();

        TelegramDeleteResidueStore.PendingDeleteTask task =
                TelegramDeleteResidueStore.decodePendingDeleteTask(81L, serialized);

        assertEquals(81L, task.taskId);
        assertEquals(24, task.taskType);
        assertEquals(-4484683155L, task.dialogId);
        assertEquals(20, task.serializedBytes);
    }

    @Test
    public void ignoresUnrelatedOrTruncatedPendingTasks() {
        byte[] unrelated = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(25)
                .putLong(99L)
                .array();

        assertNull(TelegramDeleteResidueStore.decodePendingDeleteTask(1L, unrelated));
        assertNull(TelegramDeleteResidueStore.decodePendingDeleteTask(1L, new byte[11]));
    }
}
