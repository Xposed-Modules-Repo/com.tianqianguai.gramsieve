package com.tianqianguai.gramsieve.module;

import android.util.LruCache;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class MessageCache {
    public interface MemoryCache {
        CachedMessage get(String key);
        void put(String key, CachedMessage value);
        void remove(String key);
        void removeDialog(long dialogId);

        default CachedMessage get(int accountId, String key) {
            return get(key);
        }

        default void put(int accountId, String key, CachedMessage value) {
            put(key, value);
        }

        default void remove(int accountId, String key) {
            remove(key);
        }

        default void removeDialog(int accountId, long dialogId) {
            removeDialog(dialogId);
        }
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
        put(0, dialogId, messageId, text, caption, senderId, null, null);
    }

    public void put(int accountId, long dialogId, long messageId,
                    String text, String caption, long senderId) {
        put(accountId, dialogId, messageId, text, caption, senderId, null, null);
    }

    public void put(long dialogId, long messageId, String text, String caption, long senderId,
                    String mediaType, String mediaId) {
        put(0, dialogId, messageId, text, caption, senderId, mediaType, mediaId);
    }

    public void put(int accountId, long dialogId, long messageId, String text, String caption,
                    long senderId, String mediaType, String mediaId) {
        String key = key(accountId, dialogId, messageId);
        CachedMessage message = new CachedMessage(accountId, dialogId, messageId, senderId,
                text, caption, System.currentTimeMillis(), mediaType, mediaId);
        synchronized (memoryCache) {
            memoryCache.put(accountId, key, message);
        }
        store.insertMessage(accountId, message);
    }

    /** Insert a fresh (non-edit) message while preserving recall/edit flags. */
    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId) {
        putFresh(0, dialogId, messageId, text, caption, senderId, null, null, null, null);
    }

    public void putFresh(int accountId, long dialogId, long messageId,
                         String text, String caption, long senderId) {
        putFresh(accountId, dialogId, messageId, text, caption, senderId,
                null, null, null, null);
    }

    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId,
                         String mediaType, String mediaId) {
        putFresh(0, dialogId, messageId, text, caption, senderId,
                mediaType, mediaId, null, null);
    }

    public void putFresh(int accountId, long dialogId, long messageId, String text, String caption,
                         long senderId, String mediaType, String mediaId) {
        putFresh(accountId, dialogId, messageId, text, caption, senderId,
                mediaType, mediaId, null, null);
    }

    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId,
                         String mediaType, String mediaId, String cachedMediaPath) {
        putFresh(0, dialogId, messageId, text, caption, senderId,
                mediaType, mediaId, cachedMediaPath, null);
    }

    public void putFresh(int accountId, long dialogId, long messageId, String text, String caption,
                         long senderId, String mediaType, String mediaId, String cachedMediaPath) {
        putFresh(accountId, dialogId, messageId, text, caption, senderId,
                mediaType, mediaId, cachedMediaPath, null);
    }

    public void putFresh(long dialogId, long messageId, String text, String caption, long senderId,
                         String mediaType, String mediaId, String cachedMediaPath,
                         byte[] rawMessageBlob) {
        putFresh(0, dialogId, messageId, text, caption, senderId,
                mediaType, mediaId, cachedMediaPath, rawMessageBlob);
    }

    public void putFresh(int accountId, long dialogId, long messageId, String text, String caption,
                         long senderId, String mediaType, String mediaId, String cachedMediaPath,
                         byte[] rawMessageBlob) {
        String key = key(accountId, dialogId, messageId);
        CachedMessage existing = get(accountId, dialogId, messageId);
        boolean preserveEdit = existing != null && existing.isEdited;
        boolean preserveRecall = existing != null && existing.isRecalled;
        String preservedEditedText = preserveEdit ? existing.editedText : null;
        String effectiveMediaPath = cachedMediaPath != null
                ? cachedMediaPath : (existing != null ? existing.cachedMediaPath : null);
        byte[] effectiveRawMessageBlob = rawMessageBlob != null
                ? rawMessageBlob : (existing != null ? existing.rawMessageBlob : null);

        CachedMessage message = new CachedMessage(accountId, dialogId, messageId, senderId,
                text, caption, System.currentTimeMillis(), mediaType, mediaId, effectiveMediaPath,
                preserveRecall, preserveEdit, preservedEditedText, effectiveRawMessageBlob);
        synchronized (memoryCache) {
            memoryCache.put(accountId, key, message);
        }
        if (preserveEdit || preserveRecall) {
            store.updateMessage(accountId, message);
        } else {
            store.insertOrReplaceFresh(accountId, message);
        }
    }

    public CachedMessage get(long dialogId, long messageId) {
        return get(0, dialogId, messageId);
    }

    public CachedMessage get(int accountId, long dialogId, long messageId) {
        String key = key(accountId, dialogId, messageId);
        synchronized (memoryCache) {
            CachedMessage message = memoryCache.get(accountId, key);
            if (message != null) {
                return message;
            }
        }
        CachedMessage message = store.getMessage(accountId, dialogId, messageId);
        if (message != null) {
            synchronized (memoryCache) {
                CachedMessage existing = memoryCache.get(accountId, key);
                if (existing != null) {
                    return existing;
                }
                memoryCache.put(accountId, key, message);
            }
        }
        return message;
    }

    public void putMediaObject(long dialogId, long messageId, Object mediaObject) {
        putMediaObject(0, dialogId, messageId, mediaObject);
    }

    public void putMediaObject(int accountId, long dialogId, long messageId, Object mediaObject) {
        if (mediaObject == null) {
            return;
        }
        mediaObjects.put(key(accountId, dialogId, messageId), mediaObject);
    }

    public Object getMediaObject(long dialogId, long messageId) {
        return getMediaObject(0, dialogId, messageId);
    }

    public Object getMediaObject(int accountId, long dialogId, long messageId) {
        return mediaObjects.get(key(accountId, dialogId, messageId));
    }

    public CachedMessage remove(long dialogId, long messageId) {
        return remove(0, dialogId, messageId);
    }

    public CachedMessage remove(int accountId, long dialogId, long messageId) {
        String key = key(accountId, dialogId, messageId);
        CachedMessage existing = get(accountId, dialogId, messageId);
        synchronized (memoryCache) {
            memoryCache.remove(accountId, key);
        }
        mediaObjects.remove(key);
        store.deleteMessage(accountId, dialogId, messageId);
        return existing;
    }

    public void removeDialog(long dialogId) {
        removeDialog(0, dialogId);
    }

    public void removeDialog(int accountId, long dialogId) {
        synchronized (memoryCache) {
            memoryCache.removeDialog(accountId, dialogId);
        }
        String prefix = mediaPrefix(accountId, dialogId);
        mediaObjects.keySet().removeIf(key -> key.startsWith(prefix));
        store.deleteDialog(accountId, dialogId);
    }

    public void markRecalled(long dialogId, long messageId) {
        markRecalled(0, dialogId, messageId);
    }

    public void markRecalled(int accountId, long dialogId, long messageId) {
        CachedMessage message = get(accountId, dialogId, messageId);
        if (message != null) {
            synchronized (memoryCache) {
                message.isRecalled = true;
            }
            store.updateMessage(accountId, message);
        }
    }

    public void markEdited(long dialogId, long messageId, String newText) {
        markEdited(0, dialogId, messageId, newText);
    }

    public void markEdited(int accountId, long dialogId, long messageId, String newText) {
        CachedMessage message = get(accountId, dialogId, messageId);
        if (message != null) {
            store.insertEditHistory(accountId, editHistoryFrom(message, newText));
            synchronized (memoryCache) {
                message.isEdited = true;
                message.editedText = newText;
            }
            store.updateMessage(accountId, message);
        } else {
            CachedMessage newMsg = new CachedMessage(accountId, dialogId, messageId, 0L,
                    "", "", System.currentTimeMillis());
            newMsg.isEdited = true;
            newMsg.editedText = newText;
            String key = key(accountId, dialogId, messageId);
            synchronized (memoryCache) {
                memoryCache.put(accountId, key, newMsg);
            }
            store.insertMessage(accountId, newMsg);
            store.insertEditHistory(accountId, newMsg);
        }
    }

    public void recordEditedVersion(long dialogId, long messageId, long senderId,
                                    String originalText, String originalCaption,
                                    String editedText, String mediaType, String mediaId,
                                    String cachedMediaPath) {
        recordEditedVersion(0, dialogId, messageId, senderId, originalText, originalCaption,
                editedText, mediaType, mediaId, cachedMediaPath, null);
    }

    public void recordEditedVersion(int accountId, long dialogId, long messageId, long senderId,
                                    String originalText, String originalCaption,
                                    String editedText, String mediaType, String mediaId,
                                    String cachedMediaPath) {
        recordEditedVersion(accountId, dialogId, messageId, senderId, originalText, originalCaption,
                editedText, mediaType, mediaId, cachedMediaPath, null);
    }

    public void recordEditedVersion(long dialogId, long messageId, long senderId,
                                    String originalText, String originalCaption,
                                    String editedText, String mediaType, String mediaId,
                                    String cachedMediaPath, byte[] rawMessageBlob) {
        recordEditedVersion(0, dialogId, messageId, senderId, originalText, originalCaption,
                editedText, mediaType, mediaId, cachedMediaPath, rawMessageBlob);
    }

    public void recordEditedVersion(int accountId, long dialogId, long messageId, long senderId,
                                    String originalText, String originalCaption,
                                    String editedText, String mediaType, String mediaId,
                                    String cachedMediaPath, byte[] rawMessageBlob) {
        String key = key(accountId, dialogId, messageId);
        CachedMessage existing = get(accountId, dialogId, messageId);
        String effectiveText = firstNonEmpty(existing != null ? existing.text : null, originalText);
        String effectiveCaption = firstNonEmpty(existing != null ? existing.caption : null, originalCaption);
        long effectiveSender = existing != null && existing.senderId != 0L ? existing.senderId : senderId;
        long effectiveTimestamp = existing != null && existing.timestamp != 0L
                ? existing.timestamp : System.currentTimeMillis();
        String effectiveMediaType = firstNonEmptyOrNull(existing != null ? existing.mediaType : null, mediaType);
        String effectiveMediaId = firstNonEmptyOrNull(existing != null ? existing.mediaId : null, mediaId);
        String effectiveMediaPath = firstNonEmptyOrNull(existing != null ? existing.cachedMediaPath : null, cachedMediaPath);
        byte[] existingRawMessageBlob = existing != null ? existing.rawMessageBlob : null;
        byte[] effectiveRawMessageBlob = existingRawMessageBlob != null
                ? existingRawMessageBlob : rawMessageBlob;
        byte[] historyRawMessageBlob = rawMessageBlob != null
                ? rawMessageBlob : existingRawMessageBlob;

        CachedMessage message = new CachedMessage(accountId, dialogId, messageId, effectiveSender,
                effectiveText, effectiveCaption, effectiveTimestamp, effectiveMediaType,
                effectiveMediaId, effectiveMediaPath, existing != null && existing.isRecalled,
                true, editedText, effectiveRawMessageBlob);
        synchronized (memoryCache) {
            memoryCache.put(accountId, key, message);
        }
        store.insertMessage(accountId, message);
        store.insertEditHistory(accountId, new CachedMessage(accountId, dialogId, messageId,
                effectiveSender, effectiveText, effectiveCaption, System.currentTimeMillis(),
                effectiveMediaType, effectiveMediaId, effectiveMediaPath,
                existing != null && existing.isRecalled, true, editedText, historyRawMessageBlob));
    }

    public List<CachedMessage> getRecalledMessages(long dialogId) {
        return getRecalledMessages(0, dialogId);
    }

    public List<CachedMessage> getRecalledMessages(int accountId, long dialogId) {
        return store.getRecalledMessages(accountId, dialogId);
    }

    public List<CachedMessage> getEditedMessages(long dialogId) {
        return getEditedMessages(0, dialogId);
    }

    public List<CachedMessage> getEditedMessages(int accountId, long dialogId) {
        return store.getEditedMessages(accountId, dialogId);
    }

    public List<CachedMessage> getEditHistory(long dialogId, long messageId) {
        return getEditHistory(0, dialogId, messageId);
    }

    public List<CachedMessage> getEditHistory(int accountId, long dialogId, long messageId) {
        return store.getEditHistory(accountId, dialogId, messageId);
    }

    private static String key(int accountId, long dialogId, long messageId) {
        return accountId == 0
                ? dialogId + ":" + messageId
                : accountId + "@" + dialogId + ":" + messageId;
    }

    private static String mediaPrefix(int accountId, long dialogId) {
        return accountId == 0 ? dialogId + ":" : accountId + "@" + dialogId + ":";
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second != null ? second : "";
    }

    private static String firstNonEmptyOrNull(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second != null && !second.isEmpty() ? second : null;
    }

    private static CachedMessage editHistoryFrom(CachedMessage message, String editedText) {
        boolean hasPreviousEditedText = message.editedText != null && !message.editedText.isEmpty();
        String previousText = hasPreviousEditedText ? message.editedText : message.text;
        String previousCaption = hasPreviousEditedText ? "" : message.caption;
        return new CachedMessage(
                message.accountId,
                message.dialogId,
                message.messageId,
                message.senderId,
                previousText,
                previousCaption,
                System.currentTimeMillis(),
                message.mediaType,
                message.mediaId,
                message.cachedMediaPath,
                message.isRecalled,
                true,
                editedText,
                message.rawMessageBlob
        );
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

        @Override
        public void remove(String key) {
            delegate.remove(key);
        }

        @Override
        public void removeDialog(long dialogId) {
            String prefix = dialogId + ":";
            for (String key : delegate.snapshot().keySet()) {
                if (key.startsWith(prefix)) {
                    delegate.remove(key);
                }
            }
        }

        @Override
        public void removeDialog(int accountId, long dialogId) {
            String prefix = mediaPrefix(accountId, dialogId);
            for (String key : delegate.snapshot().keySet()) {
                if (key.startsWith(prefix)) {
                    delegate.remove(key);
                }
            }
        }
    }

    public static final class CachedMessage {
        public final int accountId;
        public final long dialogId;
        public final long messageId;
        public final long senderId;
        public final String text;
        public final String caption;
        public final long timestamp;
        public final String mediaType;
        public final String mediaId;
        public final String cachedMediaPath;
        public final byte[] rawMessageBlob;
        public boolean isRecalled;
        public boolean isEdited;
        public String editedText;

        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp,
                    null, null, null, false, false, null);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp,
                    null, null, null, false, false, null);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, String mediaType, String mediaId) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, null, false, false, null);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp,
                             String mediaType, String mediaId) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, null, false, false, null);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, String mediaType, String mediaId,
                             String cachedMediaPath) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, cachedMediaPath, false, false, null);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp,
                             String mediaType, String mediaId, String cachedMediaPath) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, cachedMediaPath, false, false, null);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, boolean isRecalled,
                             boolean isEdited, String editedText) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp,
                    null, null, null, isRecalled, isEdited, editedText);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp,
                             boolean isRecalled, boolean isEdited, String editedText) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp,
                    null, null, null, isRecalled, isEdited, editedText);
        }

        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, String mediaType, String mediaId,
                             String cachedMediaPath, boolean isRecalled, boolean isEdited,
                             String editedText) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, cachedMediaPath, isRecalled, isEdited, editedText, null);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp, String mediaType,
                             String mediaId, String cachedMediaPath, boolean isRecalled,
                             boolean isEdited, String editedText) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp,
                    mediaType, mediaId, cachedMediaPath, isRecalled, isEdited, editedText, null);
        }

        /** Full constructor with the serialized Telegram message appended for API compatibility. */
        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, String mediaType, String mediaId,
                             String cachedMediaPath, boolean isRecalled, boolean isEdited,
                             String editedText, byte[] rawMessageBlob) {
            this(0, dialogId, messageId, senderId, text, caption, timestamp, mediaType,
                    mediaId, cachedMediaPath, isRecalled, isEdited, editedText, rawMessageBlob);
        }

        public CachedMessage(int accountId, long dialogId, long messageId, long senderId,
                             String text, String caption, long timestamp, String mediaType,
                             String mediaId, String cachedMediaPath, boolean isRecalled,
                             boolean isEdited, String editedText, byte[] rawMessageBlob) {
            this.accountId = Math.max(0, accountId);
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.text = text;
            this.caption = caption;
            this.timestamp = timestamp;
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.cachedMediaPath = cachedMediaPath;
            this.rawMessageBlob = rawMessageBlob == null ? null : rawMessageBlob.clone();
            this.isRecalled = isRecalled;
            this.isEdited = isEdited;
            this.editedText = editedText;
        }

        /** Alternate account-last form for callers that append metadata to old constructors. */
        public CachedMessage(long dialogId, long messageId, long senderId, String text,
                             String caption, long timestamp, String mediaType, String mediaId,
                             String cachedMediaPath, boolean isRecalled, boolean isEdited,
                             String editedText, byte[] rawMessageBlob, int accountId) {
            this(accountId, dialogId, messageId, senderId, text, caption, timestamp, mediaType,
                    mediaId, cachedMediaPath, isRecalled, isEdited, editedText, rawMessageBlob);
        }
    }
}
