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
        hookProcessUpdateArray(classLoader, module);
        hookDeleteMessages(classLoader, module);
        hookEditMessage(classLoader, module);
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
                    processUpdates(updates);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processUpdates failed", throwable);
                }
                return result;
            });
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
                    processDeletions(dialogId, messagesIds);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processDeletions failed", throwable);
                }
                return chain.proceed();
            });
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
                    processEdit(dialogId, messageId, newText);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processEdit failed", throwable);
                }
                return chain.proceed();
            });
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook editMessage", throwable);
        }
    }

    void processUpdates(ArrayList<?> updates) {
        if (messageCache == null || loader == null || updates == null) {
            ModuleLogger.hook(TAG, "RecallDetector: processUpdates skipped - cache=" + (messageCache != null) + " loader=" + (loader != null) + " updates=" + (updates != null));
            return;
        }
        ModuleLogger.hook(TAG, "RecallDetector: processUpdates size=" + updates.size());
        for (Object update : updates) {
            try {
                Object message = Reflect.field(update, "message");
                if (message != null) {
                    long dialogId = Reflect.asLong(Reflect.invokeIfExists(message, "getDialogId", new Class<?>[0]), 0L);
                    long messageId = Reflect.asLong(Reflect.invokeIfExists(message, "getId", new Class<?>[0]), 0L);
                    String text = Reflect.asString(Reflect.field(message, "messageText"));
                    String caption = Reflect.asString(Reflect.field(message, "caption"));
                    long senderId = resolveSenderId(message);
                    boolean chatEnabled = loader.isChatEnabled(dialogId);
                    ModuleLogger.hook(TAG, "RecallDetector: update dialogId=" + dialogId + " msgId=" + messageId + " enabled=" + chatEnabled + " text=" + (text != null ? text.substring(0, Math.min(30, text.length())) : "null"));
                    if (chatEnabled) {
                        messageCache.put(dialogId, messageId, text, caption, senderId);
                        ModuleLogger.hook(TAG, "RecallDetector: cached message dialogId=" + dialogId + " msgId=" + messageId);
                    }
                }
            } catch (Throwable throwable) {
                ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process update entry", throwable);
            }
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
