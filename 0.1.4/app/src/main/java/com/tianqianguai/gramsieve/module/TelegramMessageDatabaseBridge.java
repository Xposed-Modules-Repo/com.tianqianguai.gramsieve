package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class TelegramMessageDatabaseBridge implements AutoCloseable {
    static final int FLAG_DELETED = 1 << 31;

    private static final String TAG = "TelegramMessageDb";
    private static final String[] MESSAGE_TABLES = {"messages_v2", "messages_topics"};

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "GramSieve-TelegramMessageDb");
        thread.setDaemon(true);
        return thread;
    });

    void captureEditsBeforePutMessages(Object messagesStorage, List<Object> args,
                                       MessageCache messageCache,
                                       BackgroundMessageLoader loader,
                                       MediaPrefetcher mediaPrefetcher) {
        if (messagesStorage == null || args == null || messageCache == null || loader == null) {
            return;
        }
        Object database = database(messagesStorage);
        if (database == null) {
            return;
        }
        List<?> messages = extractMessages(args);
        if (messages.isEmpty()) {
            return;
        }

        int captured = 0;
        for (Object messageLike : messages) {
            try {
                MessageSnapshot incoming = snapshot(messageLike);
                if (!incoming.isValid() || !loader.isChatEnabled(incoming.dialogId)) {
                    continue;
                }
                Object oldMessage = queryOldMessage(messagesStorage, database, incoming.dialogId, incoming.messageId);
                if (oldMessage == null) {
                    continue;
                }
                MessageSnapshot old = snapshot(oldMessage);
                if (!old.isValid()) {
                    continue;
                }
                if (old.fingerprint().equals(incoming.fingerprint())) {
                    continue;
                }
                messageCache.recordEditedVersion(
                        incoming.dialogId,
                        incoming.messageId,
                        old.senderId != 0L ? old.senderId : incoming.senderId,
                        old.content,
                        old.caption,
                        incoming.content,
                        old.mediaType != null ? old.mediaType : incoming.mediaType,
                        old.mediaId != null ? old.mediaId : incoming.mediaId,
                        null
                );
                if (old.media != null) {
                    messageCache.putMediaObject(incoming.dialogId, incoming.messageId, old.media);
                    if (mediaPrefetcher != null) {
                        mediaPrefetcher.prefetchFromMessage(incoming.dialogId, incoming.messageId, oldMessage, old.media);
                    }
                }
                captured++;
            } catch (Throwable throwable) {
                ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                        "capture putMessages edit failed: " + throwable.getMessage());
            }
        }
        if (captured > 0) {
            ModuleLogger.hook(TAG, "captured offline/history edit versions=" + captured);
        }
    }

    void markMessagesDeletedAsync(Object messagesStorage, long dialogId, List<?> messageIds) {
        List<Integer> ids = normalizeMessageIds(messageIds);
        if (messagesStorage == null || ids.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> markMessagesDeleted(messagesStorage, dialogId, ids));
        } catch (RejectedExecutionException exception) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "mark deleted task rejected", exception);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    private void markMessagesDeleted(Object messagesStorage, long dialogId, List<Integer> messageIds) {
        try {
            Object database = database(messagesStorage);
            if (database == null) {
                return;
            }
            int marked = 0;
            String idsSql = joinIds(messageIds);
            for (String table : MESSAGE_TABLES) {
                marked += markMessagesDeletedInTable(database, table, dialogId, idsSql);
            }
            if (marked > 0) {
                ModuleLogger.hook(TAG, "marked Telegram DB messages deleted dialogId="
                        + dialogId + " rows=" + marked);
            }
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "mark Telegram DB deleted failed", throwable);
        }
    }

    private int markMessagesDeletedInTable(Object database, String table, long dialogId, String idsSql) {
        String where = dialogId == 0L
                ? "is_channel = 0"
                : "uid = " + dialogId;
        String query = "SELECT data, mid, uid FROM " + table
                + " WHERE " + where + " AND mid IN (" + idsSql + ")";
        String update = "UPDATE " + table + " SET data = ? WHERE uid = ? AND mid = ?";
        Object cursor = query(database, query);
        Object statement = null;
        int marked = 0;
        try {
            if (cursor == null) {
                return 0;
            }
            statement = Reflect.invokeIfExists(database, "executeFast",
                    new Class<?>[]{String.class}, update);
            if (statement == null) {
                return 0;
            }
            while (Boolean.TRUE.equals(Reflect.invokeIfExists(cursor, "next", new Class<?>[0]))) {
                Object data = null;
                try {
                    data = Reflect.invokeIfExists(cursor, "byteBufferValue",
                            new Class<?>[]{int.class}, 0);
                    int mid = Reflect.asInt(Reflect.invokeIfExists(cursor, "intValue",
                            new Class<?>[]{int.class}, 1), 0);
                    long uid = Reflect.asLong(Reflect.invokeIfExists(cursor, "longValue",
                            new Class<?>[]{int.class}, 2), 0L);
                    if (data == null || mid == 0 || uid == 0L || !setSerializedDeletedFlag(data)) {
                        continue;
                    }
                    Reflect.invokeIfExists(statement, "requery", new Class<?>[0]);
                    Reflect.invokeIfExists(statement, "bindByteBuffer", null, 1, data);
                    Reflect.invokeIfExists(statement, "bindLong", null, 2, uid);
                    Reflect.invokeIfExists(statement, "bindInteger", null, 3, mid);
                    Reflect.invokeIfExists(statement, "step", new Class<?>[0]);
                    marked++;
                } finally {
                    reuse(data);
                }
            }
            return marked;
        } finally {
            dispose(cursor);
            dispose(statement);
        }
    }

    private boolean setSerializedDeletedFlag(Object data) {
        int length = Reflect.asInt(Reflect.invokeIfExists(data, "length", new Class<?>[0]), 0);
        if (length > 0 && length < 8) {
            return false;
        }
        Reflect.invokeIfExists(data, "position", new Class<?>[]{int.class}, 4);
        int flags = Reflect.asInt(Reflect.invokeIfExists(data, "readInt32",
                new Class<?>[]{boolean.class}, true), 0);
        int updatedFlags = flags | FLAG_DELETED;
        if (updatedFlags == flags) {
            Reflect.invokeIfExists(data, "position", new Class<?>[]{int.class}, 0);
            return false;
        }
        Reflect.invokeIfExists(data, "position", new Class<?>[]{int.class}, 4);
        Reflect.invokeIfExists(data, "writeInt32", new Class<?>[]{int.class}, updatedFlags);
        Reflect.invokeIfExists(data, "position", new Class<?>[]{int.class}, 0);
        return true;
    }

    private Object queryOldMessage(Object messagesStorage, Object database, long dialogId, int messageId) {
        Object old = queryOldMessageFromTable(messagesStorage, database, "messages_v2", dialogId, messageId);
        if (old != null) {
            return old;
        }
        return queryOldMessageFromTable(messagesStorage, database, "messages_topics", dialogId, messageId);
    }

    private Object queryOldMessageFromTable(Object messagesStorage, Object database, String table,
                                            long dialogId, int messageId) {
        String query = String.format(Locale.US,
                "SELECT data FROM %s WHERE mid = %d AND uid = %d LIMIT 1",
                table, messageId, dialogId);
        Object cursor = query(database, query);
        Object data = null;
        try {
            if (cursor == null || !Boolean.TRUE.equals(Reflect.invokeIfExists(cursor, "next", new Class<?>[0]))) {
                return null;
            }
            data = Reflect.invokeIfExists(cursor, "byteBufferValue", new Class<?>[]{int.class}, 0);
            return deserializeMessage(messagesStorage, data);
        } finally {
            reuse(data);
            dispose(cursor);
        }
    }

    private Object deserializeMessage(Object messagesStorage, Object data) {
        if (data == null) {
            return null;
        }
        try {
            Reflect.invokeIfExists(data, "position", new Class<?>[]{int.class}, 0);
            int constructor = Reflect.asInt(Reflect.invokeIfExists(data, "readInt32",
                    new Class<?>[]{boolean.class}, false), 0);
            ClassLoader classLoader = messagesStorage.getClass().getClassLoader();
            Class<?> messageClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
            Object message = Reflect.invokeStatic(messageClass, "TLdeserialize", null,
                    data, constructor, false);
            if (message != null) {
                Object userConfig = Reflect.invokeIfExists(messagesStorage, "getUserConfig", new Class<?>[0]);
                long clientUserId = Reflect.asLong(Reflect.invokeIfExists(userConfig,
                        "getClientUserId", new Class<?>[0]), 0L);
                Reflect.invokeIfExists(message, "readAttachPath", null, data, clientUserId);
            }
            return message;
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "deserialize old message failed: " + throwable.getMessage());
            return null;
        }
    }

    private Object database(Object messagesStorage) {
        Object database = Reflect.field(messagesStorage, "database");
        if (database != null) {
            return database;
        }
        return Reflect.invokeIfExists(messagesStorage, "getDatabase", new Class<?>[0]);
    }

    private Object query(Object database, String query) {
        return Reflect.invokeIfExists(database, "queryFinalized",
                new Class<?>[]{String.class, Object[].class}, query, new Object[0]);
    }

    private List<?> extractMessages(List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof ArrayList<?>) {
                ArrayList<?> list = (ArrayList<?>) arg;
                if (looksLikeMessageList(list)) {
                    return list;
                }
            }
            Object messagesField = Reflect.field(arg, "messages");
            if (messagesField instanceof ArrayList<?> && looksLikeMessageList((ArrayList<?>) messagesField)) {
                return (ArrayList<?>) messagesField;
            }
        }
        return Collections.emptyList();
    }

    private boolean looksLikeMessageList(ArrayList<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        Object first = list.get(0);
        if (first == null) {
            return false;
        }
        Object owner = messageOwner(first);
        String simpleName = owner != null ? owner.getClass().getSimpleName() : first.getClass().getSimpleName();
        return simpleName.contains("Message");
    }

    static MessageSnapshot snapshot(Object messageLike) {
        Object owner = messageOwner(messageLike);
        if (owner == null) {
            return MessageSnapshot.EMPTY;
        }
        int messageId = Reflect.asInt(Reflect.field(owner, "id"), 0);
        long dialogId = dialogId(owner);
        long senderId = senderId(owner);
        String text = Reflect.asString(Reflect.field(owner, "message"));
        Object media = Reflect.field(owner, "media");
        String caption = caption(media);
        String content = joinContent(text, caption);
        String mediaType = media != null ? media.getClass().getSimpleName() : null;
        String mediaId = mediaId(media);
        return new MessageSnapshot(dialogId, messageId, senderId, content, caption, mediaType, mediaId, media);
    }

    private static Object messageOwner(Object messageLike) {
        Object owner = Reflect.field(messageLike, "messageOwner");
        return owner != null ? owner : messageLike;
    }

    private static long dialogId(Object message) {
        Object peerId = Reflect.field(message, "peer_id");
        if (peerId == null) {
            return 0L;
        }
        long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
        long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
        long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
        if (userId != 0L) {
            return userId;
        }
        if (chatId != 0L) {
            return -chatId;
        }
        if (channelId != 0L) {
            return -channelId;
        }
        return Reflect.asLong(Reflect.field(message, "dialog_id"), 0L);
    }

    private static long senderId(Object message) {
        Object fromId = Reflect.field(message, "from_id");
        if (fromId == null) {
            return 0L;
        }
        long userId = Reflect.asLong(Reflect.field(fromId, "user_id"), 0L);
        long chatId = Reflect.asLong(Reflect.field(fromId, "chat_id"), 0L);
        long channelId = Reflect.asLong(Reflect.field(fromId, "channel_id"), 0L);
        if (userId != 0L) {
            return userId;
        }
        if (chatId != 0L) {
            return -chatId;
        }
        return channelId != 0L ? -channelId : 0L;
    }

    private static String caption(Object media) {
        if (media == null) {
            return "";
        }
        return Reflect.asString(Reflect.field(media, "caption"));
    }

    private static String joinContent(String text, String caption) {
        String safeText = text != null ? text : "";
        String safeCaption = caption != null ? caption : "";
        if (safeCaption.isEmpty()) {
            return safeText;
        }
        return safeText.isEmpty() ? safeCaption : safeText + "\n" + safeCaption;
    }

    private static String mediaId(Object media) {
        if (media == null) {
            return null;
        }
        Object photo = Reflect.field(media, "photo");
        if (photo != null) {
            return objectMediaId(photo);
        }
        Object video = Reflect.field(media, "video");
        if (video != null) {
            return objectMediaId(video);
        }
        Object document = Reflect.field(media, "document");
        return document != null ? objectMediaId(document) : null;
    }

    private static String objectMediaId(Object object) {
        long id = Reflect.asLong(Reflect.field(object, "id"), 0L);
        if (id != 0L) {
            return String.valueOf(id);
        }
        long volumeId = Reflect.asLong(Reflect.field(object, "volume_id"), 0L);
        long localId = Reflect.asLong(Reflect.field(object, "local_id"), 0L);
        return (volumeId != 0L || localId != 0L) ? volumeId + "_" + localId : null;
    }

    private static List<Integer> normalizeMessageIds(List<?> ids) {
        List<Integer> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Object id : ids) {
            int value = Reflect.asInt(id, 0);
            if (value > 0) {
                result.add(value);
            }
        }
        return result;
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(ids.get(i));
        }
        return builder.toString();
    }

    private static void dispose(Object object) {
        if (object != null) {
            Reflect.invokeIfExists(object, "dispose", new Class<?>[0]);
        }
    }

    private static void reuse(Object object) {
        if (object != null) {
            Reflect.invokeIfExists(object, "reuse", new Class<?>[0]);
        }
    }

    static final class MessageSnapshot {
        static final MessageSnapshot EMPTY = new MessageSnapshot(0L, 0, 0L,
                "", "", null, null, null);

        final long dialogId;
        final int messageId;
        final long senderId;
        final String content;
        final String caption;
        final String mediaType;
        final String mediaId;
        final Object media;

        MessageSnapshot(long dialogId, int messageId, long senderId, String content, String caption,
                        String mediaType, String mediaId, Object media) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.senderId = senderId;
            this.content = content != null ? content : "";
            this.caption = caption != null ? caption : "";
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.media = media;
        }

        boolean isValid() {
            return dialogId != 0L && messageId > 0;
        }

        String fingerprint() {
            return content + "|"
                    + (mediaType != null ? mediaType : "") + "|"
                    + (mediaId != null ? mediaId : "");
        }
    }
}
