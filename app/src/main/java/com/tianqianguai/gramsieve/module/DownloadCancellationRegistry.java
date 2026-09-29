package com.tianqianguai.gramsieve.module;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shares explicit user cancellation intent between download recovery and media prefetching. */
final class DownloadCancellationRegistry {
    private final Set<String> cancelledKeys = ConcurrentHashMap.newKeySet();

    void markCancelled(String key) {
        if (key != null) {
            cancelledKeys.add(key);
        }
    }

    void allow(String key) {
        if (key != null) {
            cancelledKeys.remove(key);
        }
    }

    boolean isCancelled(String key) {
        return key != null && cancelledKeys.contains(key);
    }

    String keyFor(int account, ClassLoader classLoader, Object attachment) {
        String fileName = fileNameFor(classLoader, attachment);
        return fileName == null || fileName.isEmpty() ? null : account + ":" + fileName;
    }

    String fileNameFor(ClassLoader classLoader, Object attachment) {
        if (attachment == null || classLoader == null) {
            return null;
        }
        try {
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            for (Method method : fileLoaderClass.getMethods()) {
                if (!"getAttachFileName".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0].isAssignableFrom(attachment.getClass())) {
                    continue;
                }
                Object value = method.invoke(null, attachment);
                return value instanceof String ? (String) value : null;
            }
        } catch (Throwable ignored) {
            // The caller records the missing key when it matters for diagnostics.
        }
        return null;
    }
}
