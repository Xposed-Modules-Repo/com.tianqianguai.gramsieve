package com.tianqianguai.gramsieve.module;

/** Resolves Telegram's integer account slot without introducing user-id migration. */
public final class TelegramAccountResolver {
    private TelegramAccountResolver() {
    }

    public static int resolve(Object... candidates) {
        int account = resolveKnown(candidates);
        return account >= 0 ? account : 0;
    }

    public static int resolveWithFallback(ClassLoader classLoader, Object... candidates) {
        int account = resolveKnown(candidates);
        return account >= 0 ? account : resolveSelected(classLoader);
    }

    private static int resolveKnown(Object... candidates) {
        if (candidates != null) {
            for (Object candidate : candidates) {
                int account = resolveOne(candidate);
                if (account >= 0) {
                    return account;
                }
            }
        }
        return -1;
    }

    public static int resolve(Object candidate, ClassLoader classLoader) {
        int account = resolveKnown(candidate);
        return account >= 0 ? account : resolveSelected(classLoader);
    }

    public static int resolveMessage(Object messageObject) {
        return resolve(messageObject, Reflect.field(messageObject, "messageOwner"));
    }

    public static int resolveController(Object controller) {
        return resolve(controller, Reflect.field(controller, "messagesStorage"),
                Reflect.invokeIfExists(controller, "getMessagesStorage", new Class<?>[0]));
    }

    public static int resolveHost(Object host, ClassLoader classLoader) {
        int account = resolveKnown(host);
        return account >= 0 ? account : resolveSelected(classLoader);
    }

    private static int resolveOne(Object candidate) {
        if (candidate == null) {
            return -1;
        }
        int account = Reflect.asInt(Reflect.field(candidate, "currentAccount"), -1);
        if (account >= 0) {
            return account;
        }
        account = Reflect.asInt(Reflect.field(candidate, "account"), -1);
        if (account >= 0) {
            return account;
        }
        Object getter = Reflect.invokeIfExists(candidate, "getCurrentAccount", new Class<?>[0]);
        account = Reflect.asInt(getter, -1);
        if (account >= 0) {
            return account;
        }
        getter = Reflect.invokeIfExists(candidate, "getAccount", new Class<?>[0]);
        account = Reflect.asInt(getter, -1);
        return account >= 0 ? account : -1;
    }

    private static int resolveSelected(ClassLoader classLoader) {
        if (classLoader == null) {
            return 0;
        }
        try {
            Class<?> userConfigClass = classLoader.loadClass("org.telegram.messenger.UserConfig");
            return Math.max(0, Reflect.asInt(Reflect.staticField(userConfigClass, "selectedAccount"), 0));
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
