package com.tianqianguai.gramsieve.module;

import android.util.LruCache;

import java.util.List;

public final class MessageCache {
    public interface MemoryCache {
        CachedMessage get(String key);
        void put(String key, CachedMessage value);
    }

    private final MemoryCache memoryCache;
    private final MessageStore store;

    public static final int DEFAULT_CACHE_SIZE = 1000;

    public MessageCache(MessageStore store) {
        this(store, DEFAULT_CACHE_SIZE);
    }

    public MessageCache(MessageStore store, int cacheSize) {
        this(store, new LruCacheMemoryCache(cacheSize));
    }

    public MessageCache(MessageStore store, MemoryCache memoryCache) {
        this.memoryCache = memoryCache;
        this.store = store;
    }

    public void put(long dialogId, long messageId, String text, String caption, long senderId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = new CachedMessage(dialogId, messageId, senderId, text, caption, System.currentTimeMillis());
        synchronized (memoryCache) {
            memoryCache.put(key, message);
        }
        store.insertMessage(message);
    }

    public CachedMessage get(long dialogId, long messageId) {
        String key = dialogId + ":" + messageId;
        synchronized (memoryCache) {
            CachedMessage message = memoryCache.get(key);
            if (message != null) {
                return message;
            }
        }
        CachedMessage message = store.getMessage(dialogId, messageId);
        if (message != null) {
            synchronized (memoryCache) {
                CachedMessage existing = memoryCache.get(key);
                if (existing != null) {
                    return existing;
                }
                memoryCache.put(key, message);
            }
        }
        return message;
    }

    public void markRecalled(long dialogId, long messageId) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            synchronized (memoryCache) {
                message.isRecalled = true;
            }
            store.updateMessage(message);
        }
    }

    public void markEdited(long dialogId, long messageId, String newText) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            synchronized (memoryCache) {
                message.isEdited = true;
                message.editedText = newText;
            }
            store.updateMessage(message);
        }
    }

    public List<CachedMessage> getRecalledMessages(long dialogId) {
        return store.getRecalledMessages(dialogId);
    }

    public List<CachedMessage> getEditedMessages(long dialogId) {
        return store.getEditedMessages(dialogId);
    }

    static final class LruCacheMemoryCache implements MemoryCache {
        private final LruCache<String, CachedMessage> delegate;

        LruCacheMemoryCache(int maxSize) {
            this.delegate = new LruCache<>(maxSize);
        }

        @Override
        public CachedMessage get(String key) {
            return delegate.get(key);
        }

        @Override
        public void put(String key, CachedMessage value) {
            delegate.put(key, value);
        }
    }

    public static final class CachedMessage {
        public final long dialogId;
        public final long messageId;
        public final long senderId;
        public final String text;
        public final String caption;
        public final long timestamp;
        public boolean isRecalled;
        public boolean isEdited;
        public String editedText;

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp,
                             boolean isRecalled, boolean isEdited, String editedText) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
            this.isRecalled = isRecalled;
            this.isEdited = isEdited;
            this.editedText = editedText;
        }
    }
}
