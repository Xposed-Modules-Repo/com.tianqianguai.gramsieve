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
}
