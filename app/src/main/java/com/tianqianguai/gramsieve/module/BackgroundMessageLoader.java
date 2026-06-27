package com.tianqianguai.gramsieve.module;

import android.os.Handler;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackgroundMessageLoader {
    private static final String TAG = "GramSieve";
    private final MessageCache messageCache;
    private final AntiRecallConfigStore configStore;
    private final ScheduledExecutorService scheduler;
    private volatile Handler mainHandler;
    private final Set<Long> enabledChats = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile ClassLoader telegramClassLoader;

    public BackgroundMessageLoader(MessageCache messageCache, AntiRecallConfigStore configStore) {
        this.messageCache = messageCache;
        this.configStore = configStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        loadEnabledChats();
    }

    private void loadEnabledChats() {
        if (configStore == null) return;
        java.util.Set<Long> savedChats = configStore.getEnabledChatIds();
        if (!savedChats.isEmpty()) {
            enabledChats.addAll(savedChats);
            info("BackgroundMessageLoader: loaded " + savedChats.size() + " enabled chats from config");
            // Auto-start if there are enabled chats
            if (!running) {
                start();
            }
        }
    }

    public void setTelegramClassLoader(ClassLoader classLoader) {
        this.telegramClassLoader = classLoader;
        info("BackgroundMessageLoader: telegramClassLoader set");
    }

    public void start() {
        if (running) {
            return;
        }
        if (configStore == null) {
            return;
        }
        running = true;
        int interval = configStore.getLoadIntervalSeconds();
        info("BackgroundMessageLoader: starting with interval=" + interval + "s");
        scheduler.scheduleAtFixedRate(this::loadMessages, 0, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        info("BackgroundMessageLoader: stopped");
    }

    public void enableChat(long dialogId) {
        enabledChats.add(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, true);
        }
        info("BackgroundMessageLoader: enabledChat dialogId=" + dialogId + " enabledChats=" + enabledChats.size());
        if (!running) {
            start();
        }
    }

    public void disableChat(long dialogId) {
        enabledChats.remove(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, false);
        }
        info("BackgroundMessageLoader: disabledChat dialogId=" + dialogId + " enabledChats=" + enabledChats.size());
    }

    public boolean isChatEnabled(long dialogId) {
        return enabledChats.contains(dialogId);
    }

    private void loadMessages() {
        if (enabledChats.isEmpty()) {
            return;
        }
        info("BackgroundMessageLoader: loadMessages for " + enabledChats.size() + " chats");
        for (long dialogId : enabledChats) {
            try {
                loadMessagesForChat(dialogId);
            } catch (Throwable throwable) {
                error("BackgroundMessageLoader: loadMessages failed for dialog " + dialogId, throwable);
            }
        }
    }

    private void loadMessagesForChat(long dialogId) {
        try {
            ClassLoader classLoader = telegramClassLoader;
            if (classLoader == null) {
                info("BackgroundMessageLoader: telegramClassLoader is null");
                return;
            }
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Object controller = Reflect.invokeStatic(messagesControllerClass, "getInstance", new Class<?>[]{int.class}, 0);
            if (controller == null) {
                info("BackgroundMessageLoader: controller is null for dialog " + dialogId);
                return;
            }
            // Search for loadMessages method
            java.lang.reflect.Method loadMessagesMethod = null;
            for (java.lang.reflect.Method m : messagesControllerClass.getDeclaredMethods()) {
                if (m.getName().equals("loadMessages")) {
                    info("BackgroundMessageLoader: found loadMessages with " + m.getParameterCount() + " params");
                    loadMessagesMethod = m;
                    break;
                }
            }
            if (loadMessagesMethod == null) {
                info("BackgroundMessageLoader: loadMessages method not found");
                return;
            }
            loadMessagesMethod.setAccessible(true);
            // Build args array based on parameter count
            int paramCount = loadMessagesMethod.getParameterCount();
            Object[] args = new Object[paramCount];
            Class<?>[] paramTypes = loadMessagesMethod.getParameterTypes();
            for (int i = 0; i < paramCount; i++) {
                Class<?> type = paramTypes[i];
                if (type == long.class) {
                    args[i] = (i == 0) ? dialogId : 0L;
                } else if (type == int.class) {
                    args[i] = 0;
                } else if (type == boolean.class) {
                    args[i] = false;
                } else {
                    args[i] = null;
                }
            }
            // Set dialogId as first long param
            for (int i = 0; i < paramCount; i++) {
                if (paramTypes[i] == long.class) {
                    args[i] = dialogId;
                    break;
                }
            }
            loadMessagesMethod.invoke(controller, args);
            info("BackgroundMessageLoader: loadMessages called for dialog " + dialogId + " with " + paramCount + " params");
        } catch (Throwable throwable) {
            error("BackgroundMessageLoader: loadMessagesForChat failed for dialog " + dialogId, throwable);
        }
    }

    public void onMessageReceived(long dialogId, long messageId, String text, String caption, long senderId) {
        if (!isChatEnabled(dialogId)) {
            return;
        }
        info("BackgroundMessageLoader: onMessageReceived dialogId=" + dialogId + " msgId=" + messageId);
        messageCache.put(dialogId, messageId, text, caption, senderId);
    }

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }
}
