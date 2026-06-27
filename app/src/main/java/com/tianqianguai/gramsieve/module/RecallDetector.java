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

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader) {
        this.messageCache = messageCache;
        this.loader = loader;
    }

    public void install(ClassLoader classLoader, XposedModule module) {
        ModuleLogger.hook(TAG, "RecallDetector: installing hooks...");
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            hookMethods(messagesControllerClass, module, classLoader);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to load MessagesController", throwable);
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
            }
        }
    }

    private void hookSingleMethod(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    Object result = chain.proceed();
                    java.util.List<Object> args = chain.getArgs();
                    if (methodName.equals("processUpdateArray")) {
                        processUpdatesFromArgs(args);
                    } else if (methodName.equals("deleteMessages")) {
                        processDeletionsFromArgs(args);
                    } else if (methodName.equals("editMessage")) {
                        processEditFromArgs(args);
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

    private void processDeletionsFromArgs(java.util.List<Object> args) {
        if (args == null || args.size() < 4) return;
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
        }
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
        for (Object update : updates) {
            try {
                if (update == null) continue;
                
                String className = update.getClass().getSimpleName();
                
                // Check for message field (TLRPC.TL_updateNewMessage, TL_updateEditMessage, TL_updateEditChannelMessage, etc.)
                Object message = Reflect.field(update, "message");
                if (message != null) {
                    if (className.contains("Edit")) {
                        // This is an edit update
                        processEditedMessage(message);
                    } else {
                        // This is a new message
                        processMessageFromUpdate(message);
                    }
                    continue;
                }
                
                // Check for delete_messages field (TLRPC.TL_updateDeleteMessages, TL_updateDeleteChannelMessages)
                Object deleteMessages = Reflect.field(update, "messages");
                Object channelId = Reflect.field(update, "channel_id");
                if (deleteMessages instanceof ArrayList) {
                    long dialogId = 0L;
                    if (channelId instanceof Long) {
                        dialogId = -(Long) channelId;
                    }
                    if (loader.isChatEnabled(dialogId)) {
                        ArrayList<?> msgIds = (ArrayList<?>) deleteMessages;
                        for (Object msgIdObj : msgIds) {
                            int msgId = Reflect.asInt(msgIdObj, 0);
                            if (msgId > 0) {
                                messageCache.markRecalled(dialogId, msgId);
                                ModuleLogger.hook(TAG, "RecallDetector: marked recalled dialogId=" + dialogId + " msgId=" + msgId);
                            }
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
            // TLRPC.TL_message fields
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) return;
            
            // Get dialog ID from peer_id
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
            
            // Get all content types
            String text = Reflect.asString(Reflect.field(message, "message"));
            String caption = "";
            
            // Check for media caption
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    caption = mediaCaption;
                }
            }
            
            // Combine text and caption for caching
            String fullContent = text;
            if (!caption.isEmpty()) {
                if (!fullContent.isEmpty()) {
                    fullContent += "\n" + caption;
                } else {
                    fullContent = caption;
                }
            }
            
            messageCache.put(dialogId, msgId, fullContent, caption, 0L);
            ModuleLogger.hook(TAG, "RecallDetector: cached new message dialogId=" + dialogId + " msgId=" + msgId + " content=" + (fullContent.length() > 30 ? fullContent.substring(0, 30) + "..." : fullContent));
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process message", throwable);
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
