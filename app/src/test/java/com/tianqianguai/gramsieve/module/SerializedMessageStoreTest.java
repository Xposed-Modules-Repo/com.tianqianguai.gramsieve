package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SerializedMessageStoreTest {
    @Test
    public void readWaitsForEarlierWrite() {
        RecordingMessageStore delegate = new RecordingMessageStore();

        try (SerializedMessageStore store = new SerializedMessageStore(delegate, "test-message-store")) {
            store.insertMessage(new MessageCache.CachedMessage(1L, 10L, 100L, "hello", null, 123L));

            MessageCache.CachedMessage stored = store.getMessage(1L, 10L);

            assertNotNull(stored);
            assertEquals("hello", stored.text);
        }
    }

    @Test
    public void writeUsesSnapshotTakenBeforeQueueExecution() throws Exception {
        BlockingMessageStore delegate = new BlockingMessageStore();

        try (SerializedMessageStore store = new SerializedMessageStore(delegate, "test-message-store")) {
            MessageCache.CachedMessage message =
                    new MessageCache.CachedMessage(1L, 10L, 100L, "original", null, 123L);

            store.insertMessage(message);
            try {
                assertTrue(delegate.writeStarted.await(1, TimeUnit.SECONDS));
                message.isEdited = true;
                message.editedText = "mutated after enqueue";
            } finally {
                delegate.releaseWrite.countDown();
            }

            MessageCache.CachedMessage stored = store.getMessage(1L, 10L);

            assertNotNull(stored);
            assertFalse(stored.isEdited);
            assertNull(stored.editedText);
        }
    }

    private static class RecordingMessageStore implements MessageStore {
        private final Map<String, MessageCache.CachedMessage> messages = new ConcurrentHashMap<>();
        private final List<MessageCache.CachedMessage> editHistory = new ArrayList<>();

        @Override
        public void insertMessage(MessageCache.CachedMessage message) {
            messages.put(key(message.dialogId, message.messageId), message);
        }

        @Override
        public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
            messages.put(key(message.dialogId, message.messageId), message);
        }

        @Override
        public void insertEditHistory(MessageCache.CachedMessage message) {
            editHistory.add(0, message);
        }

        @Override
        public void updateMessage(MessageCache.CachedMessage message) {
            messages.put(key(message.dialogId, message.messageId), message);
        }

        @Override
        public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
            return messages.get(key(dialogId, messageId));
        }

        @Override
        public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage message : messages.values()) {
                if (message.dialogId == dialogId && message.isRecalled) {
                    result.add(message);
                }
            }
            return result;
        }

        @Override
        public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage message : editHistory) {
                if (message.dialogId == dialogId) {
                    result.add(message);
                }
            }
            return result;
        }

        @Override
        public List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage message : editHistory) {
                if (message.dialogId == dialogId && message.messageId == messageId) {
                    result.add(message);
                }
            }
            return result;
        }

        private static String key(long dialogId, long messageId) {
            return dialogId + ":" + messageId;
        }
    }

    private static final class BlockingMessageStore extends RecordingMessageStore {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);

        @Override
        public void insertMessage(MessageCache.CachedMessage message) {
            writeStarted.countDown();
            try {
                assertTrue(releaseWrite.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            super.insertMessage(message);
        }
    }
}
