package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class RecallDetector {
    private static final String TAG = "GramSieve";

    private final MessageCache messageCache;
    private final BackgroundMessageLoader loader;
    private final MediaPrefetcher mediaPrefetcher;

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader) {
        this(messageCache, loader, null);
    }

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader,
                          MediaPrefetcher mediaPrefetcher) {
        this.messageCache = messageCache;
        this.loader = loader;
        this.mediaPrefetcher = mediaPrefetcher;
    }

    public void install(ClassLoader classLoader, XposedModule module) {
        ModuleLogger.hook(TAG, "RecallDetector: installing hooks...");
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            hookMethods(messagesControllerClass, module, classLoader);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to load MessagesController", throwable);
        }
        // Hook MessagesStorage to cache loaded messages
        try {
            Class<?> messagesStorageClass = classLoader.loadClass("org.telegram.messenger.MessagesStorage");
            hookStorageMethods(messagesStorageClass, module);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to load MessagesStorage", throwable);
        }
        ModuleLogger.hook(TAG, "RecallDetector: hooks installation complete");
    }

    private void hookMethods(Class<?> messagesControllerClass, XposedModule module, ClassLoader classLoader) {
        for (java.lang.reflect.Method m : messagesControllerClass.getDeclaredMethods()) {
            String name = m.getName();
            if (name.equals("processUpdateArray")) {
                ModuleLogger.hook(TAG, "RecallDetector: found processUpdateArray with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "processUpdateArray");
            } else if (name.equals("deleteMessages")) {
                ModuleLogger.hook(TAG, "RecallDetector: found deleteMessages with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "deleteMessages");
            } else if (name.equals("editMessage")) {
                ModuleLogger.hook(TAG, "RecallDetector: found editMessage with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "editMessage");
            } else if (name.contains("processLoadedMessages") || name.contains("LoadedMessages")) {
                ModuleLogger.hook(TAG, "RecallDetector: found " + name + " with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "processLoadedMessages");
            }
        }
    }

    private void hookStorageMethods(Class<?> messagesStorageClass, XposedModule module) {
        ModuleLogger.hook(TAG, "RecallDetector: scanning MessagesStorage methods...");
        for (java.lang.reflect.Method m : messagesStorageClass.getDeclaredMethods()) {
            String name = m.getName();
            // Log all methods that might be related to storing messages
            if (name.contains("put") || name.contains("message") || name.contains("Message") || name.contains("save") || name.contains("Save") || name.contains("replace")) {
                ModuleLogger.hook(TAG, "RecallDetector: storage method: " + name + " params=" + m.getParameterCount());
            }
            // Hook methods that store or update messages
            if (name.equals("putMessages") || name.equals("putMessagesInternal")
                    || name.equals("replaceMessageIfExists")) {
                ModuleLogger.hook(TAG, "RecallDetector: found storage." + name + " with " + m.getParameterCount() + " params");
                hookStoragePutMessages(module, m, name);
            }
        }
    }

    private void hookStoragePutMessages(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    // Try to extract messages from args before proceeding
                    cacheMessagesFromStorageArgs(chain.getArgs());
                } catch (Throwable t) {
                    // Ignore
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hook storage." + methodName + " success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook storage." + methodName, throwable);
        }
    }

    private void cacheMessagesFromStorageArgs(java.util.List<Object> args) {
        if (args == null || messageCache == null || loader == null) return;
        // Look for ArrayList of MessageObject or TLRPC.Message
        for (Object arg : args) {
            if (arg instanceof ArrayList) {
                ArrayList<?> list = (ArrayList<?>) arg;
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first != null) {
                        String className = first.getClass().getSimpleName();
                        if (className.contains("Message")) {
                            cacheMessageList(list);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void cacheMessageList(ArrayList<?> messages) {
        int cached = 0;
        for (Object msgObj : messages) {
            try {
                if (msgObj == null) continue;
                Object messageOwner = messageOwner(msgObj);
                // Get dialogId from peer_id
                Object peerId = Reflect.field(messageOwner, "peer_id");
                long dialogId = 0L;
                if (peerId != null) {
                    long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                    long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                    long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                    if (channelId != 0L) dialogId = -channelId;
                    else if (chatId != 0L) dialogId = -chatId;
                    else if (userId != 0L) dialogId = userId;
                }
                if (dialogId == 0L || !loader.isChatEnabled(dialogId)) continue;

                int msgId = Reflect.asInt(Reflect.field(messageOwner, "id"), 0);
                if (msgId == 0) continue;

                String text = Reflect.asString(Reflect.field(messageOwner, "message"));
                String caption = "";
                String mediaType = null;
                String mediaId = null;
                Object media = Reflect.field(messageOwner, "media");
                if (media != null) {
                    String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                    if (mediaCaption != null && !mediaCaption.isEmpty()) {
                        caption = mediaCaption;
                    }
                    mediaType = media.getClass().getSimpleName();
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                            }
                        }
                    }
                }
                String fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
                MessageCache.CachedMessage existing = messageCache.get(dialogId, msgId);
                if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                    messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
                    messageCache.putMediaObject(dialogId, msgId, media);
                    prefetchMedia(dialogId, msgId, msgObj, media);
                    cached++;
                }
            } catch (Throwable t) {
                // Skip individual message errors
            }
        }
        if (cached > 0) {
            ModuleLogger.hook(TAG, "RecallDetector: cached " + cached + " messages from storage");
        }
    }

    private void hookSingleMethod(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    java.util.List<Object> args = chain.getArgs();
                    if (methodName.equals("processUpdateArray")) {
                        processUpdatesFromArgs(args);
                        return chain.proceed();
                    }
                    if (methodName.equals("deleteMessages")) {
                        processDeletionsFromArgs(args);
                        return chain.proceed();
                    }
                    Object result = chain.proceed();
                    if (methodName.equals("editMessage")) {
                        processEditFromArgs(args);
                    } else if (methodName.equals("processLoadedMessages")) {
                        processLoadedMessagesFromArgs(args);
                    }
                    return result;
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, methodName + " hook failed", throwable);
                    return chain.proceed();
                }
            });
            ModuleLogger.hook(TAG, "RecallDetector: hook " + methodName + " success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook " + methodName, throwable);
        }
    }

    private void processUpdatesFromArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) return;
        for (Object arg : args) {
            if (arg instanceof ArrayList) {
                processUpdates((ArrayList<?>) arg);
                return;
            }
        }
    }

    private boolean processDeletionsFromArgs(java.util.List<Object> args) {
        if (args == null || args.size() < 4) return false;
        ArrayList<?> messageIds = null;
        long dialogId = 0L;
        for (Object arg : args) {
            if (arg instanceof ArrayList && messageIds == null) {
                messageIds = (ArrayList<?>) arg;
            } else if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            }
        }
        if (messageIds != null && dialogId != 0L) {
            processDeletions(dialogId, messageIds);
            if (loader != null && loader.isChatEnabled(dialogId) && !messageIds.isEmpty()) {
                int count = messageIds.size();
                messageIds.clear();
                ModuleLogger.hook(TAG, "RecallDetector: cleared deleteMessages ids before Telegram dialogId="
                        + dialogId + " count=" + count);
                return true;
            }
        }
        return false;
    }

    private void processEditFromArgs(java.util.List<Object> args) {
        if (args == null || args.size() < 3) return;
        long dialogId = 0L;
        int messageId = 0;
        String newText = null;
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            } else if (arg instanceof Integer && messageId == 0) {
                messageId = (Integer) arg;
            } else if (arg instanceof String && newText == null) {
                newText = (String) arg;
            }
        }
        if (dialogId != 0L && messageId != 0 && newText != null) {
            processEdit(dialogId, messageId, newText);
        }
    }

    private void processLoadedMessagesFromArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) return;
        // Look for ArrayList of messages and a dialogId
        ArrayList<?> messages = null;
        long dialogId = 0L;
        for (Object arg : args) {
            if (arg instanceof ArrayList && messages == null) {
                messages = (ArrayList<?>) arg;
            } else if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            }
        }
        if (messages != null && dialogId != 0L && loader.isChatEnabled(dialogId)) {
            cacheLoadedMessages(dialogId, messages);
        }
    }

    private void cacheLoadedMessages(long dialogId, ArrayList<?> messages) {
        if (messageCache == null) return;
        int cached = 0;
        for (Object msgObj : messages) {
            try {
                if (msgObj == null) continue;
                Object messageOwner = messageOwner(msgObj);
                int msgId = Reflect.asInt(Reflect.field(messageOwner, "id"), 0);
                if (msgId == 0) continue;

                String text = Reflect.asString(Reflect.field(messageOwner, "message"));
                String caption = "";
                String mediaType = null;
                String mediaId = null;
                Object media = Reflect.field(messageOwner, "media");
                if (media != null) {
                    String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                    if (mediaCaption != null && !mediaCaption.isEmpty()) {
                        caption = mediaCaption;
                    }
                    mediaType = media.getClass().getSimpleName();
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                            }
                        }
                    }
                }
                String fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
                // Only cache if there's content
                if (!fullContent.isEmpty() || mediaId != null) {
                    MessageCache.CachedMessage existing = messageCache.get(dialogId, msgId);
                    if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                        messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
                        messageCache.putMediaObject(dialogId, msgId, media);
                        prefetchMedia(dialogId, msgId, msgObj, media);
                        cached++;
                    }
                }
            } catch (Throwable t) {
                // Skip individual message errors
            }
        }
        if (cached > 0) {
            ModuleLogger.hook(TAG, "RecallDetector: cached " + cached + " loaded messages for dialogId=" + dialogId);
        }
    }

    private static void hook(XposedModule module, Method method, XposedInterface.Hooker hooker) {
        module.hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(hooker);
    }

    private void hookProcessUpdateArray(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method processUpdateArray = Reflect.method(messagesControllerClass, "processUpdateArray", ArrayList.class);
            hook(module, processUpdateArray, chain -> {
                Object result = chain.proceed();
                try {
                    ArrayList<?> updates = (ArrayList<?>) chain.getArg(0);
                    ModuleLogger.hook(TAG, "RecallDetector: processUpdateArray called with " + updates.size() + " updates");
                    processUpdates(updates);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processUpdates failed", throwable);
                }
                return result;
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookProcessUpdateArray success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook processUpdateArray", throwable);
        }
    }

    private void hookDeleteMessages(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method deleteMessages = Reflect.method(messagesControllerClass, "deleteMessages",
                    ArrayList.class, ArrayList.class, ArrayList.class, long.class, int.class, boolean.class);
            hook(module, deleteMessages, chain -> {
                try {
                    ArrayList<?> messagesIds = (ArrayList<?>) chain.getArg(0);
                    long dialogId = (long) chain.getArg(3);
                    ModuleLogger.hook(TAG, "RecallDetector: deleteMessages called dialogId=" + dialogId + " count=" + messagesIds.size());
                    processDeletions(dialogId, messagesIds);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processDeletions failed", throwable);
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookDeleteMessages success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook deleteMessages", throwable);
        }
    }

    private void hookEditMessage(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method editMessage = Reflect.method(messagesControllerClass, "editMessage",
                    long.class, int.class, String.class, boolean.class, ArrayList.class, boolean.class, boolean.class);
            hook(module, editMessage, chain -> {
                try {
                    long dialogId = (long) chain.getArg(0);
                    int messageId = (int) chain.getArg(1);
                    String newText = (String) chain.getArg(2);
                    ModuleLogger.hook(TAG, "RecallDetector: editMessage called dialogId=" + dialogId + " msgId=" + messageId);
                    processEdit(dialogId, messageId, newText);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processEdit failed", throwable);
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookEditMessage success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook editMessage", throwable);
        }
    }

    void processUpdates(ArrayList<?> updates) {
        if (messageCache == null || loader == null || updates == null) {
            return;
        }
        ModuleLogger.hook(TAG, "RecallDetector: processUpdates count=" + updates.size());
        for (Object update : updates) {
            try {
                if (update == null) continue;

                String className = update.getClass().getSimpleName();

                Object message = Reflect.field(update, "message");
                ModuleLogger.hook(TAG, "RecallDetector: class=" + className + " hasMessage=" + (message != null));
                if (message != null) {
                    if (className.contains("Edit")) {
                        // This is an edit update — but Telegram also sends TL_updateEditChannelMessage
                        // for non-content changes (reactions, views, etc.). Only mark as edited
                        // if the message text actually changed.
                        if (isRealEdit(message)) {
                            processEditedMessage(message);
                        } else {
                            // Not a real edit (e.g. reaction added) — refresh cache
                            processMessageFromUpdate(message);
                        }
                    } else {
                        // This is a new message
                        processMessageFromUpdate(message);
                    }
                    continue;
                }
                
                // Check for delete_messages field (TLRPC.TL_updateDeleteMessages, TL_updateDeleteChannelMessages)
                Object deleteMessages = Reflect.field(update, "messages");
                Object channelId = Reflect.field(update, "channel_id");
                if (className.contains("Delete") && deleteMessages instanceof ArrayList) {
                    long dialogId = 0L;
                    long channelIdValue = Reflect.asLong(channelId, 0L);
                    if (channelIdValue != 0L) {
                        dialogId = -channelIdValue;
                    }
                    if (loader.isChatEnabled(dialogId)) {
                        ArrayList<?> msgIds = (ArrayList<?>) deleteMessages;
                        int count = msgIds.size();
                        processDeletions(dialogId, msgIds);
                        if (count > 0) {
                            msgIds.clear();
                            ModuleLogger.hook(TAG, "RecallDetector: cleared delete update ids before Telegram dialogId="
                                    + dialogId + " count=" + count);
                        }
                    }
                }
            } catch (Throwable throwable) {
                ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process update entry", throwable);
            }
        }
    }

    private void processMessageFromUpdate(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) return;

            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }

            boolean enabled = loader.isChatEnabled(dialogId);
            ModuleLogger.hook(TAG, "processMessageFromUpdate: msgId=" + msgId + " dialogId=" + dialogId + " enabled=" + enabled);
            if (!enabled) {
                return;
            }

            String text = Reflect.asString(Reflect.field(message, "message"));
            String caption = "";
            String mediaType = null;
            String mediaId = null;
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    caption = mediaCaption;
                }
                mediaType = media.getClass().getSimpleName();
                Object photo = Reflect.field(media, "photo");
                if (photo != null) {
                    mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                } else {
                    Object video = Reflect.field(media, "video");
                    if (video != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                    } else {
                        Object document = Reflect.field(media, "document");
                        if (document != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                        }
                    }
                }
            }

            String fullContent = text != null ? text : "";
            if (!caption.isEmpty()) {
                fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
            }

            // Fresh update from Telegram — this message is NOT edited/recalled at this point
            // Reset any stale flags from prior sessions
            messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
            messageCache.putMediaObject(dialogId, msgId, media);
            prefetchMedia(dialogId, msgId, message, media);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process message", throwable);
        }
    }

    private Object messageOwner(Object messageLike) {
        Object owner = Reflect.field(messageLike, "messageOwner");
        return owner != null ? owner : messageLike;
    }

    private void prefetchMedia(long dialogId, long messageId, Object messageOwner, Object media) {
        if (mediaPrefetcher == null || media == null) {
            return;
        }
        mediaPrefetcher.prefetchFromMessage(dialogId, messageId, messageOwner, media);
    }

    private boolean isRealEdit(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) {
                ModuleLogger.hook(TAG, "isRealEdit: msgId=0");
                return false;
            }

            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }
            if (dialogId == 0L) {
                ModuleLogger.hook(TAG, "isRealEdit: dialogId=0");
                return false;
            }

            MessageCache.CachedMessage cached = messageCache.get(dialogId, msgId);
            if (cached == null || cached.text == null) {
                ModuleLogger.hook(TAG, "isRealEdit: no cache msgId=" + msgId + " dialogId=" + dialogId);
                return true;
            }

            String newText = Reflect.asString(Reflect.field(message, "message"));
            String newCaption = "";
            String newMediaType = null;
            String newMediaId = null;
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    newCaption = mediaCaption;
                }
                newMediaType = media.getClass().getSimpleName();
                Object photo = Reflect.field(media, "photo");
                if (photo != null) {
                    newMediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                } else {
                    Object video = Reflect.field(media, "video");
                    if (video != null) {
                        newMediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                    } else {
                        Object document = Reflect.field(media, "document");
                        if (document != null) {
                            newMediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                        }
                    }
                }
            }
            String newContent = newText != null ? newText : "";
            if (!newCaption.isEmpty()) {
                newContent = newContent.isEmpty() ? newCaption : newContent + "\n" + newCaption;
            }

            // Build fingerprints for comparison
            String cachedFingerprint = (cached.text != null ? cached.text : "") + "|"
                    + (cached.mediaType != null ? cached.mediaType : "") + "|"
                    + (cached.mediaId != null ? cached.mediaId : "");
            String newFingerprint = newContent + "|"
                    + (newMediaType != null ? newMediaType : "") + "|"
                    + (newMediaId != null ? newMediaId : "");

            boolean same = cachedFingerprint.equals(newFingerprint);
            ModuleLogger.hook(TAG, "isRealEdit: msgId=" + msgId + " same=" + same);
            return !same;
        } catch (Throwable t) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "isRealEdit failed", t);
            return false;
        }
    }

    private void processEditedMessage(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) return;
            
            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }
            
            if (!loader.isChatEnabled(dialogId)) {
                return;
            }
            
            // Get the new content
            String newText = Reflect.asString(Reflect.field(message, "message"));
            String newCaption = "";
            
            // Check for media caption
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    newCaption = mediaCaption;
                }
            }
            
            // Combine text and caption
            String newContent = newText;
            if (!newCaption.isEmpty()) {
                if (!newContent.isEmpty()) {
                    newContent += "\n" + newCaption;
                } else {
                    newContent = newCaption;
                }
            }
            
            // Mark as edited with new content
            messageCache.markEdited(dialogId, msgId, newContent);
            ModuleLogger.hook(TAG, "RecallDetector: marked edited dialogId=" + dialogId + " msgId=" + msgId);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process edited message", throwable);
        }
    }

    void processDeletions(long dialogId, ArrayList<?> messageIds) {
        if (messageCache == null || loader == null || messageIds == null) {
            return;
        }
        ModuleLogger.hook(TAG, "RecallDetector: processDeletions dialogId=" + dialogId + " count=" + messageIds.size() + " enabled=" + loader.isChatEnabled(dialogId));
        if (!loader.isChatEnabled(dialogId)) {
            return;
        }
        for (Object messageIdObj : messageIds) {
            int messageId = Reflect.asInt(messageIdObj, 0);
            if (messageId > 0) {
                messageCache.markRecalled(dialogId, messageId);
                ModuleLogger.hook(TAG, "RecallDetector: marked recalled dialogId=" + dialogId + " msgId=" + messageId);
            }
        }
    }

    void processEdit(long dialogId, int messageId, String newText) {
        if (messageCache == null || loader == null) {
            return;
        }
        ModuleLogger.hook(TAG, "RecallDetector: processEdit dialogId=" + dialogId + " msgId=" + messageId + " enabled=" + loader.isChatEnabled(dialogId));
        if (!loader.isChatEnabled(dialogId)) {
            return;
        }
        messageCache.markEdited(dialogId, messageId, newText);
        ModuleLogger.hook(TAG, "RecallDetector: marked edited dialogId=" + dialogId + " msgId=" + messageId);
    }

    private long resolveSenderId(Object message) {
        try {
            Object fromId = Reflect.field(message, "from_id");
            if (fromId == null) {
                Object messageOwner = Reflect.field(message, "messageOwner");
                if (messageOwner != null) {
                    fromId = Reflect.field(messageOwner, "from_id");
                }
            }
            if (fromId != null) {
                long userId = Reflect.asLong(Reflect.field(fromId, "user_id"), 0L);
                if (userId != 0L) {
                    return userId;
                }
                long chatId = Reflect.asLong(Reflect.field(fromId, "chat_id"), 0L);
                if (chatId != 0L) {
                    return -chatId;
                }
            }
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to resolve sender id", throwable);
        }
        return 0L;
    }
}
