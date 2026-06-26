package com.tianqianguai.gramsieve.module;

import android.util.LruCache;

import java.util.List;

public final class MessageCache {
    private static final int MAX_CACHE_SIZE = 1000;
    private final LruCache<String, CachedMessage> memoryCache;
    private final MessageDatabaseHelper databaseHelper;

    public MessageCache(MessageDatabaseHelper databaseHelper) {
        this.memoryCache = new LruCache<>(MAX_CACHE_SIZE);
        this.databaseHelper = databaseHelper;
    }

    public void put(long dialogId, long messageId, String text, String caption, long senderId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = new CachedMessage(dialogId, messageId, senderId, text, caption, System.currentTimeMillis());
        memoryCache.put(key, message);
        databaseHelper.insertMessage(message);
    }

    public CachedMessage get(long dialogId, long messageId) {
        String key = dialogId + ":" + messageId;
        CachedMessage message = memoryCache.get(key);
        if (message == null) {
            message = databaseHelper.getMessage(dialogId, messageId);
            if (message != null) {
                memoryCache.put(key, message);
            }
        }
        return message;
    }

    public void markRecalled(long dialogId, long messageId) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            message.isRecalled = true;
            databaseHelper.updateMessage(message);
        }
    }

    public void markEdited(long dialogId, long messageId, String newText) {
        CachedMessage message = get(dialogId, messageId);
        if (message != null) {
            message.isEdited = true;
            message.editedText = newText;
            databaseHelper.updateMessage(message);
        }
    }

    public List<CachedMessage> getRecalledMessages(long dialogId) {
        return databaseHelper.getRecalledMessages(dialogId);
    }

    public List<CachedMessage> getEditedMessages(long dialogId) {
        return databaseHelper.getEditedMessages(dialogId);
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
    }
}
