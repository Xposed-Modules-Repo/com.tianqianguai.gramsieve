package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecallDetectorTest {

    private InMemoryMessageStore store;
    private MessageCache cache;
    private BackgroundMessageLoader loader;
    private RecallDetector detector;

    @Before
    public void setUp() {
        store = new InMemoryMessageStore();
        cache = new MessageCache(store, new HashMapMemoryCache());
        loader = new BackgroundMessageLoader(cache, null);
        detector = new RecallDetector(cache, loader);
    }

    @Test
    public void testInstantiation() {
        assertNotNull(detector);
    }

    @Test
    public void testProcessDeletionsMarksRecalled() {
        cache.put(100, 1, "hello", null, 42);
        cache.put(100, 2, "world", null, 42);
        loader.enableChat(100);

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(1);
        detector.processDeletions(100, ids);

        MessageCache.CachedMessage msg1 = cache.get(100, 1);
        assertNotNull(msg1);
        assertTrue(msg1.isRecalled);

        MessageCache.CachedMessage msg2 = cache.get(100, 2);
        assertNotNull(msg2);
        assertFalse(msg2.isRecalled);
    }

    @Test
    public void testProcessDeletionsSkipsDisabledChat() {
        cache.put(200, 1, "hello", null, 42);

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(1);
        detector.processDeletions(200, ids);

        MessageCache.CachedMessage msg = cache.get(200, 1);
        assertNotNull(msg);
        assertFalse(msg.isRecalled);
    }

    @Test
    public void testProcessDeletionsSkipsZeroIds() {
        cache.put(100, 1, "hello", null, 42);
        loader.enableChat(100);

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(0);
        ids.add(-1);
        detector.processDeletions(100, ids);

        MessageCache.CachedMessage msg = cache.get(100, 1);
        assertNotNull(msg);
        assertFalse(msg.isRecalled);
    }

    @Test
    public void testProcessDeletionsMultipleIds() {
        cache.put(100, 1, "a", null, 1);
        cache.put(100, 2, "b", null, 1);
        cache.put(100, 3, "c", null, 1);
        loader.enableChat(100);

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(1);
        ids.add(3);
        detector.processDeletions(100, ids);

        assertTrue(cache.get(100, 1).isRecalled);
        assertFalse(cache.get(100, 2).isRecalled);
        assertTrue(cache.get(100, 3).isRecalled);
    }

    @Test
    public void testProcessDeleteUpdateClearsEnabledChannelIdsBeforeTelegramHandlesIt() {
        cache.put(-123, 7, "kept", null, 1);
        loader.enableChat(-123);
        FakeDeleteChannelUpdate update = new FakeDeleteChannelUpdate(123, 7);
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(update);

        detector.processUpdates(updates);

        assertTrue(cache.get(-123, 7).isRecalled);
        assertTrue(update.messages.isEmpty());
    }

    @Test
    public void testProcessDeleteUpdateKeepsDisabledChannelIds() {
        cache.put(-123, 7, "kept", null, 1);
        FakeDeleteChannelUpdate update = new FakeDeleteChannelUpdate(123, 7);
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(update);

        detector.processUpdates(updates);

        assertFalse(cache.get(-123, 7).isRecalled);
        assertEquals(1, update.messages.size());
    }

    @Test
    public void testProcessEditMarksEdited() {
        cache.put(100, 1, "original", null, 42);
        loader.enableChat(100);

        detector.processEdit(100, 1, "edited text");

        MessageCache.CachedMessage msg = cache.get(100, 1);
        assertNotNull(msg);
        assertTrue(msg.isEdited);
        assertEquals("edited text", msg.editedText);
    }

    @Test
    public void testProcessEditUpdateRecordsHistoryWithoutChangingTelegramMessage() {
        FakeMedia originalMedia = new FakeMedia("old caption");
        FakeMedia editedMedia = new FakeMedia("new caption");
        cache.putFresh(100, 1, "original", "old caption", 42, "TL_messageMediaPhoto", "old_photo");
        cache.putMediaObject(100, 1, originalMedia);
        loader.enableChat(100);

        FakeTelegramMessage editedMessage = new FakeTelegramMessage(100, 1, "edited", editedMedia);
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(new FakeEditUpdate(editedMessage));

        detector.processUpdates(updates);

        MessageCache.CachedMessage msg = cache.get(100, 1);
        assertNotNull(msg);
        assertTrue(msg.isEdited);
        assertEquals("edited\nnew caption", msg.editedText);
        assertEquals("edited", editedMessage.message);
        assertSame(editedMedia, editedMessage.media);
        assertSame(originalMedia, cache.getMediaObject(100, 1));
    }

    @Test
    public void testProcessEditSkipsDisabledChat() {
        cache.put(200, 1, "original", null, 42);

        detector.processEdit(200, 1, "edited text");

        MessageCache.CachedMessage msg = cache.get(200, 1);
        assertNotNull(msg);
        assertFalse(msg.isEdited);
    }

    @Test
    public void testProcessUpdatesSkipsDisabledChat() {
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(new FakeUpdate(300, 1, "text", "caption"));

        detector.processUpdates(updates);

        assertNull(cache.get(300, 1));
    }

    @Test
    public void testNullCacheSafety() {
        RecallDetector nullDetector = new RecallDetector(null, loader);
        nullDetector.processDeletions(100, new ArrayList<>());
        nullDetector.processEdit(100, 1, "text");
        nullDetector.processUpdates(new ArrayList<>());
    }

    @Test
    public void testNullLoaderSafety() {
        RecallDetector nullDetector = new RecallDetector(cache, null);
        nullDetector.processDeletions(100, new ArrayList<>());
        nullDetector.processEdit(100, 1, "text");
        nullDetector.processUpdates(new ArrayList<>());
    }

    @Test
    public void testMarkRecalledUpdatesStore() {
        cache.put(100, 5, "msg", null, 10);
        loader.enableChat(100);

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(5);
        detector.processDeletions(100, ids);

        assertTrue(store.getLastUpdated().isRecalled);
    }

    @Test
    public void testMarkEditedUpdatesStore() {
        cache.put(100, 5, "msg", null, 10);
        loader.enableChat(100);

        detector.processEdit(100, 5, "new");

        assertTrue(store.getLastUpdated().isEdited);
        assertEquals("new", store.getLastUpdated().editedText);
    }

    private static class FakeUpdate {
        final Object message;

        FakeUpdate(long dialogId, long messageId, String text, String caption) {
            this.message = new FakeMessage(dialogId, messageId, text, caption);
        }
    }

    private static class FakeEditUpdate {
        final Object message;

        FakeEditUpdate(Object message) {
            this.message = message;
        }
    }

    private static class FakeDeleteChannelUpdate {
        final ArrayList<Integer> messages = new ArrayList<>();
        final long channel_id;

        FakeDeleteChannelUpdate(long channelId, int messageId) {
            this.channel_id = channelId;
            this.messages.add(messageId);
        }
    }

    private static class FakeTelegramMessage {
        int id;
        Object peer_id;
        String message;
        Object media;

        FakeTelegramMessage(long dialogId, int messageId, String text, Object media) {
            this.id = messageId;
            this.peer_id = new FakePeer(dialogId);
            this.message = text;
            this.media = media;
        }
    }

    private static class FakePeer {
        long user_id;
        long chat_id;
        long channel_id;

        FakePeer(long dialogId) {
            if (dialogId > 0) {
                this.user_id = dialogId;
            } else {
                this.channel_id = -dialogId;
            }
        }
    }

    private static class FakeMedia {
        String caption;

        FakeMedia(String caption) {
            this.caption = caption;
        }
    }

    private static class FakeMessage {
        private final long dialogId;
        private final long messageId;
        private final String messageText;
        private final String caption;

        FakeMessage(long dialogId, long messageId, String text, String caption) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.messageText = text;
            this.caption = caption;
        }

        public long getDialogId() { return dialogId; }
        public long getId() { return messageId; }
    }

    private static class HashMapMemoryCache implements MessageCache.MemoryCache {
        private final Map<String, MessageCache.CachedMessage> map = new HashMap<>();

        @Override
        public MessageCache.CachedMessage get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, MessageCache.CachedMessage value) {
            map.put(key, value);
        }
    }

    private static class InMemoryMessageStore implements MessageStore {
        private final Map<String, MessageCache.CachedMessage> db = new HashMap<>();
        private MessageCache.CachedMessage lastUpdated;

        @Override
        public void insertMessage(MessageCache.CachedMessage message) {
            db.put(message.dialogId + ":" + message.messageId, message);
        }

        @Override
        public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
            db.put(message.dialogId + ":" + message.messageId, message);
        }

        @Override
        public void updateMessage(MessageCache.CachedMessage message) {
            lastUpdated = message;
        }

        @Override
        public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
            return db.get(dialogId + ":" + messageId);
        }

        @Override
        public List<MessageCache.CachedMessage> getRecalledMessages(long dialogId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage msg : db.values()) {
                if (msg.dialogId == dialogId && msg.isRecalled) {
                    result.add(msg);
                }
            }
            return result;
        }

        @Override
        public List<MessageCache.CachedMessage> getEditedMessages(long dialogId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage msg : db.values()) {
                if (msg.dialogId == dialogId && msg.isEdited) {
                    result.add(msg);
                }
            }
            return result;
        }

        MessageCache.CachedMessage getLastUpdated() {
            return lastUpdated;
        }
    }
}
