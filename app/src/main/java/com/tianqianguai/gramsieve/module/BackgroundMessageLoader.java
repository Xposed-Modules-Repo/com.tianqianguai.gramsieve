package com.tianqianguai.gramsieve.module;

import android.os.Handler;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackgroundMessageLoader {
    private final MessageCache messageCache;
    private final AntiRecallConfigStore configStore;
    private final ScheduledExecutorService scheduler;
    private volatile Handler mainHandler;
    private final Set<Long> enabledChats = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    public BackgroundMessageLoader(MessageCache messageCache, AntiRecallConfigStore configStore) {
        this.messageCache = messageCache;
        this.configStore = configStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
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
        scheduler.scheduleAtFixedRate(this::loadMessages, 0, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    public void enableChat(long dialogId) {
        enabledChats.add(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, true);
        }
    }

    public void disableChat(long dialogId) {
        enabledChats.remove(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, false);
        }
    }

    public boolean isChatEnabled(long dialogId) {
        return enabledChats.contains(dialogId);
    }

    private void loadMessages() {
        if (!configStore.isEnabled()) {
            return;
        }
        for (long dialogId : enabledChats) {
            try {
                loadMessagesForChat(dialogId);
            } catch (Throwable throwable) {
                // Log error but continue with other chats
            }
        }
    }

    private void loadMessagesForChat(long dialogId) {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Object controller = Reflect.invokeStatic(messagesControllerClass, "getInstance", new Class<?>[]{int.class}, 0);
            if (controller == null) {
                return;
            }
            Method loadMessages = Reflect.method(messagesControllerClass, "loadMessages",
                    long.class, int.class, int.class, int.class, boolean.class, int.class, int.class, int.class, int.class, boolean.class, int.class);
            Reflect.invoke(loadMessages, controller, dialogId, 0, 50, 0, false, 0, 0, 0, 0, true, 0);
        } catch (Throwable throwable) {
            // Log error
        }
    }

    public void onMessageReceived(long dialogId, long messageId, String text, String caption, long senderId) {
        if (!isChatEnabled(dialogId)) {
            return;
        }
        messageCache.put(dialogId, messageId, text, caption, senderId);
    }
}
