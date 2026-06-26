package com.tianqianguai.gramsieve.module;

import java.util.List;

public interface MessageStore {
    void insertMessage(MessageCache.CachedMessage message);
    void updateMessage(MessageCache.CachedMessage message);
    MessageCache.CachedMessage getMessage(long dialogId, long messageId);
    List<MessageCache.CachedMessage> getRecalledMessages(long dialogId);
    List<MessageCache.CachedMessage> getEditedMessages(long dialogId);
}
