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

public class MessageCacheTest {

    private InMemoryMessageStore store;
    private MessageCache cache;

    @Before
    public void setUp() {
        store = new InMemoryMessageStore();
        cache = new MessageCache(store, new HashMapMemoryCache());
    }

    @Test
    public void testCachedMessageCreation() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertEquals(123, message.dialogId);
        assertEquals(456, message.messageId);
        assertEquals(789, message.senderId);
        assertEquals("Hello", message.text);
        assertEquals("World", message.caption);
        assertFalse(message.isRecalled);
        assertFalse(message.isEdited);
        assertNull(message.editedText);
    }

    @Test
    public void testCachedMessageFullConstructor() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                1, 2, 3, "text", "cap", 100L, true, false, "edited");
        assertTrue(message.isRecalled);
        assertFalse(message.isEdited);
        assertEquals("edited", message.editedText);
    }

    @Test
    public void testMarkRecalled() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertFalse(message.isRecalled);
        message.isRecalled = true;
        assertTrue(message.isRecalled);
    }

    @Test
    public void testMarkEdited() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertFalse(message.isEdited);
        assertNull(message.editedText);
        message.isEdited = true;
        message.editedText = "New Text";
        assertTrue(message.isEdited);
        assertEquals("New Text", message.editedText);
    }

    @Test
    public void testCacheLookupHit() {
        cache.put(1, 10, "hello", null, 100);
        MessageCache.CachedMessage result = cache.get(1, 10);
        assertNotNull(result);
        assertEquals("hello", result.text);
        assertEquals(0, store.getCallCount);
    }

    @Test
    public void testCacheLookupMissFallsBackToStore() {
        store.seed(1, 10, "from_db", null, 100);
        MessageCache.CachedMessage result = cache.get(1, 10);
        assertNotNull(result);
        assertEquals("from_db", result.text);
        assertEquals(1, store.getCallCount);
        MessageCache.CachedMessage second = cache.get(1, 10);
        assertSame(result, second);
        assertEquals(1, store.getCallCount);
    }

    @Test
    public void testCacheMissReturnsNull() {
        assertNull(cache.get(999, 999));
    }

    @Test
    public void testMarkRecalledIntegration() {
        cache.put(1, 10, "hello", null, 100);
        cache.markRecalled(1, 10);
        MessageCache.CachedMessage msg = cache.get(1, 10);
        assertTrue(msg.isRecalled);
        assertTrue(store.getLastUpdated().isRecalled);
    }

    @Test
    public void testMarkEditedIntegration() {
        cache.put(1, 10, "hello", null, 100);
        cache.markEdited(1, 10, "edited text");
        MessageCache.CachedMessage msg = cache.get(1, 10);
        assertTrue(msg.isEdited);
        assertEquals("edited text", msg.editedText);
        assertTrue(store.getLastUpdated().isEdited);
        assertEquals("edited text", store.getLastUpdated().editedText);
    }

    @Test
    public void testMarkRecalledOnNonexistentMessage() {
        cache.markRecalled(999, 999);
        assertNull(store.getLastUpdated());
    }

    @Test
    public void testRecalledStateSurvivesCacheEviction() {
        HashMapMemoryCache smallCache = new HashMapMemoryCache(5);
        MessageCache smallMessageCache = new MessageCache(store, smallCache);
        smallMessageCache.put(1, 1, "a", null, 1);
        smallMessageCache.put(2, 1, "b", null, 1);
        smallMessageCache.put(3, 1, "c", null, 1);
        smallMessageCache.markRecalled(1, 1);
        smallCache.remove("1:1");
        MessageCache.CachedMessage msg = smallMessageCache.get(1, 1);
        assertNotNull(msg);
        assertTrue(msg.isRecalled);
    }

    @Test
    public void testEditedStateSurvivesCacheEviction() {
        HashMapMemoryCache smallCache = new HashMapMemoryCache(5);
        MessageCache smallMessageCache = new MessageCache(store, smallCache);
        smallMessageCache.put(1, 1, "a", null, 1);
        smallMessageCache.markEdited(1, 1, "new text");
        smallCache.remove("1:1");
        MessageCache.CachedMessage msg = smallMessageCache.get(1, 1);
        assertNotNull(msg);
        assertTrue(msg.isEdited);
        assertEquals("new text", msg.editedText);
    }

    @Test
    public void testFreshUpdatePreservesExistingCachedMediaPathWhenMissing() {
        cache.putFresh(1, 1, "", "", 1, "TL_messageMediaPhoto", "old_photo", "/tmp/original.jpg");

        cache.putFresh(1, 1, "", "", 1, "TL_messageMediaPhoto", "old_photo", null);

        MessageCache.CachedMessage msg = cache.get(1, 1);
        assertNotNull(msg);
        assertEquals("/tmp/original.jpg", msg.cachedMediaPath);
        assertEquals("old_photo", msg.mediaId);
    }

    @Test
    public void testGetRecalledMessages() {
        cache.put(1, 1, "a", null, 1);
        cache.put(1, 2, "b", null, 1);
        cache.put(1, 3, "c", null, 1);
        cache.markRecalled(1, 2);
        List<MessageCache.CachedMessage> recalled = cache.getRecalledMessages(1);
        assertEquals(1, recalled.size());
        assertEquals(2, recalled.get(0).messageId);
        assertTrue(recalled.get(0).isRecalled);
    }

    @Test
    public void testGetEditedMessages() {
        cache.put(1, 1, "a", null, 1);
        cache.put(1, 2, "b", null, 1);
        cache.markEdited(1, 1, "edited");
        List<MessageCache.CachedMessage> edited = cache.getEditedMessages(1);
        assertEquals(1, edited.size());
        assertEquals(1, edited.get(0).messageId);
        assertEquals("edited", edited.get(0).editedText);
    }

    private static class HashMapMemoryCache implements MessageCache.MemoryCache {
        private final Map<String, MessageCache.CachedMessage> map = new HashMap<>();
        private final int maxSize;

        HashMapMemoryCache() {
            this(Integer.MAX_VALUE);
        }

        HashMapMemoryCache(int maxSize) {
            this.maxSize = maxSize;
        }

        @Override
        public MessageCache.CachedMessage get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, MessageCache.CachedMessage value) {
            if (map.size() >= maxSize && !map.containsKey(key)) {
                String firstKey = map.keySet().iterator().next();
                map.remove(firstKey);
            }
            map.put(key, value);
        }

        void remove(String key) {
            map.remove(key);
        }
    }

    private static class InMemoryMessageStore implements MessageStore {
        private final Map<String, MessageCache.CachedMessage> db = new HashMap<>();
        int getCallCount;
        private MessageCache.CachedMessage lastUpdated;

        void seed(long dialogId, long messageId, String text, String caption, long senderId) {
            String key = dialogId + ":" + messageId;
            db.put(key, new MessageCache.CachedMessage(dialogId, messageId, senderId, text, caption, 0));
        }

        @Override
        public void insertMessage(MessageCache.CachedMessage message) {
            String key = message.dialogId + ":" + message.messageId;
            db.put(key, message);
        }

        @Override
        public void insertOrReplaceFresh(MessageCache.CachedMessage message) {
            String key = message.dialogId + ":" + message.messageId;
            db.put(key, message);
        }

        @Override
        public void updateMessage(MessageCache.CachedMessage message) {
            lastUpdated = message;
        }

        @Override
        public MessageCache.CachedMessage getMessage(long dialogId, long messageId) {
            getCallCount++;
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
