package com.tianqianguai.gramsieve.module;

import android.content.Context;

import java.util.Locale;

/** Reads Telegram's in-app locale before falling back to Android resources. */
final class TelegramLocale {
    private TelegramLocale() {
    }

    static boolean isChinese(Context context, ClassLoader preferredClassLoader) {
        return isChinese(current(context, preferredClassLoader));
    }

    static boolean isChinese(Locale locale) {
        return locale != null && "zh".equalsIgnoreCase(locale.getLanguage());
    }

    static CharSequence string(
            Context context,
            ClassLoader preferredClassLoader,
            int resourceId
    ) {
        if (resourceId == 0) {
            return null;
        }
        try {
            Class<?> localeController = localeController(context, preferredClassLoader);
            Object value = Reflect.invokeStatic(
                    localeController,
                    "getString",
                    new Class<?>[]{int.class},
                    resourceId
            );
            String localized = Reflect.asString(value).trim();
            if (!localized.isEmpty()) {
                return localized;
            }
        } catch (Throwable ignored) {
            // Older or non-Telegram hosts use Android's configured resources.
        }
        try {
            return context == null ? null : context.getText(resourceId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Locale current(Context context, ClassLoader preferredClassLoader) {
        try {
            Class<?> localeController = localeController(context, preferredClassLoader);
            Object instance = Reflect.invokeStatic(
                    localeController,
                    "getInstance",
                    new Class<?>[0]
            );
            Object current = Reflect.invokeIfExists(
                    instance,
                    "getCurrentLocale",
                    new Class<?>[0]
            );
            if (current instanceof Locale) {
                return (Locale) current;
            }
        } catch (Throwable ignored) {
            // Fall through to the host resource configuration.
        }
        try {
            return context == null ? null : context.getResources().getConfiguration().locale;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> localeController(
            Context context,
            ClassLoader preferredClassLoader
    ) throws ClassNotFoundException {
        ClassLoader classLoader = preferredClassLoader;
        if (classLoader == null && context != null) {
            classLoader = context.getClassLoader();
        }
        return Class.forName(
                "org.telegram.messenger.LocaleController",
                false,
                classLoader
        );
    }
}
