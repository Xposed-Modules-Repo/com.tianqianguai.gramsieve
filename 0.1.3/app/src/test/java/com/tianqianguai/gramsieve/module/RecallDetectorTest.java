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
    public void testProcessGlobalDeleteUpdateMarksEnabledPrivateChat() {
        cache.put(123, 7, "kept", null, 1);
        loader.enableChat(123);
        FakeDeleteMessagesUpdate update = new FakeDeleteMessagesUpdate(7);
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(update);

        detector.processUpdates(updates);

        assertTrue(cache.get(123, 7).isRecalled);
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
    public void testLocalDeleteMessagesBypassesRecallAndPurgesCache() {
        cache.put(100, 7, "trash", null, 1);
        loader.enableChat(100);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        ArrayList<Object> args = new ArrayList<>();
        args.add(ids);
        args.add(100L);

        assertTrue(detector.processDeletionsFromArgs(null, args));

        assertNull(cache.get(100, 7));
        assertEquals(1, ids.size());
        assertTrue(store.deletedKeys.contains("100:7"));
    }

    @Test
    public void testLocalDeleteMessagesUsesOnlyFirstTelegram1283MessageIdList() {
        cache.put(100, 7, "message id", null, 1);
        cache.put(100, 8, "random id collision", null, 1);
        cache.put(100, 9, "other list collision", null, 1);
        loader.enableChat(100);

        ArrayList<Integer> messageIds = new ArrayList<>();
        messageIds.add(7);
        ArrayList<Long> randomIds = new ArrayList<>();
        randomIds.add(8L);
        ArrayList<Integer> otherIds = new ArrayList<>();
        otherIds.add(9);
        ArrayList<Object> args = new ArrayList<>();
        args.add(messageIds);
        args.add(randomIds);
        args.add(otherIds);
        args.add(100L);

        assertTrue(detector.processDeletionsFromArgs(null, args));

        assertNull(cache.get(100, 7));
        assertNotNull(cache.get(100, 8));
        assertNotNull(cache.get(100, 9));
        assertEquals(1, store.deletedKeys.size());
        assertEquals("100:7", store.deletedKeys.get(0));
    }

    @Test
    public void testSelfDeleteUpdateIsNotClearedBeforeTelegramHandlesIt() {
        cache.put(-123, 7, "trash", null, 1);
        loader.enableChat(-123);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        ArrayList<Object> args = new ArrayList<>();
        args.add(ids);
        args.add(-123L);
        detector.processDeletionsFromArgs(null, args);

        FakeDeleteChannelUpdate update = new FakeDeleteChannelUpdate(123, 7);
        ArrayList<Object> updates = new ArrayList<>();
        updates.add(update);

        detector.processUpdates(updates);

        assertNull(cache.get(-123, 7));
        assertEquals(1, update.messages.size());
    }

    @Test
    public void testCleanupModePurgesInsteadOfMarkingRecalled() {
        cache.put(100, 7, "trash", null, 1);
        loader.enableChat(100);
        assertTrue(detector.toggleCleanupMode(100, 60_000L));

        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        detector.processDeletions(100, ids);

        assertNull(cache.get(100, 7));
        assertTrue(store.deletedKeys.contains("100:7"));
    }

    @Test
    public void testLocalDeleteDialogDisablesLoaderAndClearsDialogCache() {
        cache.put(100, 7, "trash", null, 1);
        cache.put(100, 8, "trash 2", null, 1);
        cache.put(200, 7, "keep", null, 1);
        loader.enableChat(100);
        ArrayList<Object> args = new ArrayList<>();
        args.add(100L);
        args.add(0);
        args.add(false);

        assertTrue(detector.handleControllerDialogAction("deleteDialog", args));

        assertFalse(loader.isChatEnabled(100));
        assertTrue(detector.isCleanupModeActive(100));
        assertNull(cache.get(100, 7));
        assertNull(cache.get(100, 8));
        assertNotNull(cache.get(200, 7));
        assertTrue(store.deletedDialogs.contains(100L));
    }

    @Test
    public void testGlobalNotificationDeleteUsesUniqueSelfDeleteDialog() {
        SelfDeleteTracker tracker = new SelfDeleteTracker();
        detector = new RecallDetector(cache, loader, null, tracker);
        cache.put(100, 7, "delete", null, 1);
        cache.put(100, 8, "delete too", null, 1);
        cache.put(200, 7, "keep", null, 1);
        loader.enableChat(100);
        loader.enableChat(200);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        ids.add(8);
        tracker.recordUserDelete(100, ids);
        ArrayList<Object> hookArgs = new ArrayList<>();
        hookArgs.add(new Object[]{ids, 0L});

        assertFalse(detector.shouldSuppressMessagesDeletedEvent(hookArgs));

        assertNull(cache.get(100, 7));
        assertNull(cache.get(100, 8));
        assertNotNull(cache.get(200, 7));
    }

    @Test
    public void testGlobalNotificationDeleteBlocksAmbiguousSelfDeleteDialog() {
        SelfDeleteTracker tracker = new SelfDeleteTracker();
        detector = new RecallDetector(cache, loader, null, tracker);
        cache.put(100, 7, "keep one", null, 1);
        cache.put(200, 7, "keep two", null, 1);
        loader.enableChat(100);
        loader.enableChat(200);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        tracker.recordUserDelete(100, ids);
        tracker.recordUserDelete(200, ids);
        ArrayList<Object> hookArgs = new ArrayList<>();
        hookArgs.add(new Object[]{ids, 0L});

        assertTrue(detector.shouldSuppressMessagesDeletedEvent(hookArgs));

        assertNotNull(cache.get(100, 7));
        assertNotNull(cache.get(200, 7));
    }

    @Test
    public void testGlobalNotificationDeleteBlocksWhenAnyIdLacksIntent() {
        SelfDeleteTracker tracker = new SelfDeleteTracker();
        detector = new RecallDetector(cache, loader, null, tracker);
        cache.put(100, 7, "keep one", null, 1);
        cache.put(100, 8, "keep two", null, 1);
        loader.enableChat(100);
        tracker.recordUserDelete(100, java.util.Collections.singletonList(7));
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        ids.add(8);
        ArrayList<Object> hookArgs = new ArrayList<>();
        hookArgs.add(new Object[]{ids, 0L});

        assertTrue(detector.shouldSuppressMessagesDeletedEvent(hookArgs));

        assertNotNull(cache.get(100, 7));
        assertNotNull(cache.get(100, 8));
    }

    @Test
    public void testBlockPeerRouteOnlyLogsAndKeepsGramSieveState() {
        cache.put(100, 7, "keep", null, 1);
        loader.enableChat(100);
        ArrayList<Object> args = new ArrayList<>();
        args.add(100L);
        args.add(0);
        args.add(false);

        assertFalse(detector.handleControllerDialogAction("blockPeer", args));

        assertTrue(loader.isChatEnabled(100));
        assertFalse(detector.isCleanupModeActive(100));
        assertNotNull(cache.get(100, 7));
        assertFalse(store.deletedDialogs.contains(100L));
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

    private static class FakeDeleteMessagesUpdate {
        final ArrayList<Integer> messages = new ArrayList<>();

        FakeDeleteMessagesUpdate(int messageId) {
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

        @Override
        public void remove(String key) {
            map.remove(key);
        }

        @Override
        public void removeDialog(long dialogId) {
            String prefix = dialogId + ":";
            map.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    private static class InMemoryMessageStore implements MessageStore {
        private final Map<String, MessageCache.CachedMessage> db = new HashMap<>();
        private final List<MessageCache.CachedMessage> editHistory = new ArrayList<>();
        private final List<String> deletedKeys = new ArrayList<>();
        private final List<Long> deletedDialogs = new ArrayList<>();
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
        public void insertEditHistory(MessageCache.CachedMessage message) {
            editHistory.add(0, message);
        }

        @Override
        public void updateMessage(MessageCache.CachedMessage message) {
            lastUpdated = message;
        }

        @Override
        public void deleteMessage(long dialogId, long messageId) {
            String key = dialogId + ":" + messageId;
            db.remove(key);
            deletedKeys.add(key);
        }

        @Override
        public void deleteDialog(long dialogId) {
            String prefix = dialogId + ":";
            db.keySet().removeIf(key -> key.startsWith(prefix));
            editHistory.removeIf(message -> message.dialogId == dialogId);
            deletedDialogs.add(dialogId);
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
            for (MessageCache.CachedMessage msg : editHistory) {
                if (msg.dialogId == dialogId) {
                    result.add(msg);
                }
            }
            return result;
        }

        @Override
        public List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId) {
            List<MessageCache.CachedMessage> result = new ArrayList<>();
            for (MessageCache.CachedMessage msg : editHistory) {
                if (msg.dialogId == dialogId && msg.messageId == messageId) {
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
