package com.tianqianguai.gramsieve.module;

import android.os.Handler;
import android.os.Looper;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BackgroundMessageLoader {
    private static final String TAG = "GramSieve";
    private static final int DEFAULT_HISTORY_LOAD_COUNT = 50;
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
        if (!enabledChats.isEmpty()) {
            if (!running) {
                start();
            } else {
                loadMessages();
            }
        }
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
        Handler handler = ensureMainHandler();
        if (handler != null && Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::loadMessagesOnCurrentThread);
            return;
        }
        loadMessagesOnCurrentThread();
    }

    private void loadMessagesOnCurrentThread() {
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

    private Handler ensureMainHandler() {
        Handler handler = mainHandler;
        if (handler != null) {
            return handler;
        }
        try {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
            return handler;
        } catch (Throwable throwable) {
            return null;
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
            List<java.lang.reflect.Method> candidates = new ArrayList<>();
            for (java.lang.reflect.Method m : messagesControllerClass.getDeclaredMethods()) {
                if (m.getName().equals("loadMessages")) {
                    candidates.add(m);
                }
            }
            if (candidates.isEmpty()) {
                info("BackgroundMessageLoader: loadMessages method not found");
                return;
            }
            candidates.sort(Comparator.comparingInt(Method::getParameterCount).reversed());
            Throwable lastFailure = null;
            for (java.lang.reflect.Method loadMessagesMethod : candidates) {
                Object[] args = buildLoadMessagesArgs(loadMessagesMethod, dialogId);
                if (args == null) {
                    info("BackgroundMessageLoader: skip loadMessages params=" + loadMessagesMethod.getParameterCount());
                    continue;
                }
                try {
                    loadMessagesMethod.setAccessible(true);
                    loadMessagesMethod.invoke(controller, args);
                    info("BackgroundMessageLoader: loadMessages called for dialog " + dialogId + " with " + loadMessagesMethod.getParameterCount() + " params");
                    return;
                } catch (Throwable throwable) {
                    lastFailure = throwable;
                    info("BackgroundMessageLoader: loadMessages params=" + loadMessagesMethod.getParameterCount() + " failed: " + throwable.getClass().getSimpleName());
                }
            }
            if (lastFailure != null) {
                error("BackgroundMessageLoader: no loadMessages overload succeeded for dialog " + dialogId, lastFailure);
            }
        } catch (Throwable throwable) {
            error("BackgroundMessageLoader: loadMessagesForChat failed for dialog " + dialogId, throwable);
        }
    }

    static Object[] buildLoadMessagesArgs(Method method, long dialogId) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        boolean assignedDialogId = false;
        boolean assignedCount = false;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> type = paramTypes[i];
            if (type == long.class || type == Long.class) {
                args[i] = assignedDialogId ? 0L : dialogId;
                assignedDialogId = true;
            } else if (type == int.class || type == Integer.class) {
                args[i] = assignedCount ? 0 : DEFAULT_HISTORY_LOAD_COUNT;
                assignedCount = true;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == float.class || type == Float.class) {
                args[i] = 0f;
            } else if (type == double.class || type == Double.class) {
                args[i] = 0d;
            } else if (type == short.class || type == Short.class) {
                args[i] = (short) 0;
            } else if (type == byte.class || type == Byte.class) {
                args[i] = (byte) 0;
            } else if (type == char.class || type == Character.class) {
                args[i] = (char) 0;
            } else if (ArrayList.class.isAssignableFrom(type) || List.class.isAssignableFrom(type)) {
                args[i] = new ArrayList<>();
            } else if (!type.isPrimitive()) {
                args[i] = null;
            } else {
                return null;
            }
        }
        return assignedDialogId ? args : null;
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
