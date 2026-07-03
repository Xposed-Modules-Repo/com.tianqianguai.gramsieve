package com.tianqianguai.gramsieve.module;

import android.util.LruCache;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageCache {
    public interface MemoryCache {
        CachedMessage get(String key);
        void put(String key, CachedMessage value);
    }

    private final MemoryCache memoryCache;
    private final MessageStore store;
    private final ConcurrentHashMap<String, Object> mediaObjects = new ConcurrentHashMap<>();

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
        put(dialogId, messageId, text, caption, senderId, null, null);
    }

    public void put(long dialogId, long messageId, String text, String caption, long senderId, String mediaType, String mediaId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = new CachedMessage(dialogId, messageId, senderId, text, caption, System.currentTimeMillis(), mediaType, mediaId);
        synchronized (memoryCache) {
            memoryCache.put(key, message);
        }
        store.insertMessage(message);
    }

    /**
     * Insert a fresh (non-edit) message. Preserves isEdited/isRecalled flags
     * if already set by RecallDetector.
     */
    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId) {
        putFresh(dialogId, messageId, text, caption, senderId, null, null, null);
    }

    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId, String mediaType, String mediaId) {
        putFresh(dialogId, messageId, text, caption, senderId, mediaType, mediaId, null);
    }

    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId, String mediaType, String mediaId, String cachedMediaPath) {
        String key = dialogId + ":" + messageId;
        CachedMessage existing = get(dialogId, messageId);
        boolean preserveEdit = existing != null && existing.isEdited;
        boolean preserveRecall = existing != null && existing.isRecalled;
        String preservedEditedText = preserveEdit ? existing.editedText : null;
        // Preserve existing cached media path if we don't have a new one
        String effectiveMediaPath = cachedMediaPath != null ? cachedMediaPath : (existing != null ? existing.cachedMediaPath : null);

        CachedMessage message = new CachedMessage(dialogId, messageId, senderId, text, caption, System.currentTimeMillis(), mediaType, mediaId, effectiveMediaPath);
        message.isEdited = preserveEdit;
        message.isRecalled = preserveRecall;
        message.editedText = preservedEditedText;
        synchronized (memoryCache) {
            memoryCache.put(key, message);
        }
        if (preserveEdit || preserveRecall) {
            store.updateMessage(message);
        } else {
            store.insertOrReplaceFresh(message);
        }
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

    public void putMediaObject(long dialogId, long messageId, Object mediaObject) {
        if (mediaObject == null) {
            return;
        }
        mediaObjects.put(dialogId + ":" + messageId, mediaObject);
    }

    public Object getMediaObject(long dialogId, long messageId) {
        return mediaObjects.get(dialogId + ":" + messageId);
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
        } else {
            // No cached version — create entry with empty original text
            CachedMessage newMsg = new CachedMessage(dialogId, messageId, 0L, "", "", System.currentTimeMillis());
            newMsg.isEdited = true;
            newMsg.editedText = newText;
            String key = dialogId + ":" + messageId;
            synchronized (memoryCache) {
                memoryCache.put(key, newMsg);
            }
            store.insertMessage(newMsg);
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
        public final String mediaType;
        public final String mediaId;
        public final String cachedMediaPath;
        public boolean isRecalled;
        public boolean isEdited;
        public String editedText;

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp) {
            this(dialogId, messageId, senderId, text, caption, timestamp, null, null, null);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp,
                             String mediaType, String mediaId) {
            this(dialogId, messageId, senderId, text, caption, timestamp, mediaType, mediaId, null);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp,
                             String mediaType, String mediaId, String cachedMediaPath) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.cachedMediaPath = cachedMediaPath;
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp,
                              boolean isRecalled, boolean isEdited, String editedText) {
            this(dialogId, messageId, senderId, text, caption, timestamp, null, null, null, isRecalled, isEdited, editedText);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text, String caption, long timestamp,
                             String mediaType, String mediaId, String cachedMediaPath,
                              boolean isRecalled, boolean isEdited, String editedText) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.cachedMediaPath = cachedMediaPath;
            this.isRecalled = isRecalled;
            this.isEdited = isEdited;
            this.editedText = editedText;
        }
    }
}
