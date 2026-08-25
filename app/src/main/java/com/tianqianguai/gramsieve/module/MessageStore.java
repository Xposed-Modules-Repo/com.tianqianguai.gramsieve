package com.tianqianguai.gramsieve.module;

import java.util.List;

public interface MessageStore {
    void insertMessage(MessageCache.CachedMessage message);
    void insertOrReplaceFresh(MessageCache.CachedMessage message);
    void insertEditHistory(MessageCache.CachedMessage message);
    void updateMessage(MessageCache.CachedMessage message);
    void deleteMessage(long dialogId, long messageId);
    void deleteDialog(long dialogId);
    MessageCache.CachedMessage getMessage(long dialogId, long messageId);
    List<MessageCache.CachedMessage> getRecalledMessages(long dialogId);
    List<MessageCache.CachedMessage> getEditedMessages(long dialogId);
    List<MessageCache.CachedMessage> getEditHistory(long dialogId, long messageId);

    /**
     * Account-aware operations. The default implementations deliberately call
     * the legacy account-0-shaped methods so small test stores and old callers
     * remain source compatible; the SQLite and serialized stores override all
     * of these methods to enforce account isolation.
     */
    default void insertMessage(int accountId, MessageCache.CachedMessage message) {
        insertMessage(message);
    }

    default void insertOrReplaceFresh(int accountId, MessageCache.CachedMessage message) {
        insertOrReplaceFresh(message);
    }

    default void insertEditHistory(int accountId, MessageCache.CachedMessage message) {
        insertEditHistory(message);
    }

    default void updateMessage(int accountId, MessageCache.CachedMessage message) {
        updateMessage(message);
    }

    default void deleteMessage(int accountId, long dialogId, long messageId) {
        deleteMessage(dialogId, messageId);
    }

    default void deleteDialog(int accountId, long dialogId) {
        deleteDialog(dialogId);
    }

    default MessageCache.CachedMessage getMessage(int accountId, long dialogId, long messageId) {
        return getMessage(dialogId, messageId);
    }

    default List<MessageCache.CachedMessage> getRecalledMessages(int accountId, long dialogId) {
        return getRecalledMessages(dialogId);
    }

    default List<MessageCache.CachedMessage> getEditedMessages(int accountId, long dialogId) {
        return getEditedMessages(dialogId);
    }

    default List<MessageCache.CachedMessage> getEditHistory(int accountId, long dialogId, long messageId) {
        return getEditHistory(dialogId, messageId);
    }
}
