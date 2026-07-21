package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keeps explicitly requested Telegram video downloads alive until completion or an explicit X click. */
final class ReliableVideoDownloadManager {
    private static final String TAG = "ReliableDownload";
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;
    private static final long STALL_TIMEOUT_MS = 30_000L;
    private static final long RESTART_DELAY_MS = 750L;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Set<String> userStarted = ConcurrentHashMap.newKeySet();
    private final Set<String> explicitlyCancelled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GramSieve-ReliableDownload");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ClassLoader classLoader;

    ReliableVideoDownloadManager() {
        scheduler.scheduleWithFixedDelay(this::scanForStalls,
                WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    void onUserButton(Object cell) {
        Object message = Reflect.field(cell, "currentMessageObject");
        if (!isVideo(message)) {
            return;
        }
        Object document = documentOf(message, Reflect.field(cell, "documentAttach"));
        int account = Reflect.asInt(Reflect.field(cell, "currentAccount"), 0);
        String key = key(account, document);
        if (key == null) {
            return;
        }
        int state = Reflect.asInt(Reflect.field(cell, "buttonState"), -1);
        if (state == 1) {
            userStarted.remove(key);
            explicitlyCancelled.add(key);
            Job job = jobs.remove(key);
            if (job != null) {
                job.state.cancel();
            }
            ModuleLogger.hook(TAG, "explicit cancel account=" + account + " file=" + fileName(document));
        } else if (state == 0) {
            explicitlyCancelled.remove(key);
            userStarted.add(key);
            ModuleLogger.hook(TAG, "explicit start account=" + account + " file=" + fileName(document));
        }
    }

    void onLoadFile(Object fileLoader, Object[] args) {
        if (args == null || args.length != 4 || !isVideoDocument(args[0])) {
            return;
        }
        Object message = args[1];
        int account = resolveAccount(message, fileLoader);
        String key = key(account, args[0]);
        if (key == null || explicitlyCancelled.contains(key)) {
            return;
        }
        Job existing = jobs.get(key);
        if (existing != null) {
            existing.update(fileLoader, args);
            return;
        }
        if (!userStarted.remove(key)) {
            return;
        }
        Job job = new Job(key, account, fileLoader, args.clone());
        jobs.put(key, job);
        ModuleLogger.hook(TAG, "guarding account=" + account + " file=" + fileName(args[0]));
    }

    void onNotification(Object notificationCenter, int id, Object[] args,
                        int progressId, int loadedId, int failedId) {
        int account = Reflect.asInt(Reflect.field(notificationCenter, "currentAccount"), 0);
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        String key = account + ":" + args[0];
        Job job = jobs.get(key);
        if (job == null) {
            return;
        }
        if (id == progressId && args.length >= 2 && args[1] instanceof Number) {
            long bytes = ((Number) args[1]).longValue();
            if (job.state.progress(bytes, System.currentTimeMillis())) {
                job.recovering = false;
                job.retryCount = 0;
            }
        } else if (id == loadedId) {
            complete(job, "completed");
        } else if (id == failedId && !explicitlyCancelled.contains(key)) {
            scheduleRestart(job, "interrupted");
        }
    }

    private void scanForStalls() {
        long now = System.currentTimeMillis();
        for (Job job : jobs.values()) {
            long generation = job.state.generation();
            if (job.state.shouldRecover(generation, now, STALL_TIMEOUT_MS)) {
                scheduleRestart(job, "stalled bytes=" + job.state.downloadedBytes());
            }
        }
    }

    private void scheduleRestart(Job job, String reason) {
        synchronized (job) {
            if (job.recovering || explicitlyCancelled.contains(job.key)) {
                return;
            }
            job.recovering = true;
        }
        long generation = job.state.generation();
        long delayMs = Math.min(15_000L, RESTART_DELAY_MS << Math.min(job.retryCount++, 4));
        ModuleLogger.hook(TAG, "recovering " + reason + " account=" + job.account
                + " file=" + fileName(job.args[0]) + " delayMs=" + delayMs);
        cancelNative(job);
        scheduler.schedule(() -> restart(job, generation), delayMs, TimeUnit.MILLISECONDS);
    }

    private void restart(Job job, long generation) {
        if (!job.state.isCurrent(generation) || explicitlyCancelled.contains(job.key)
                || jobs.get(job.key) != job) {
            return;
        }
        try {
            Method method = findCompatibleMethod(job.fileLoader.getClass(), "loadFile", job.args);
            if (method == null) {
                throw new NoSuchMethodException("loadFile(Document,Object,int,int)");
            }
            method.setAccessible(true);
            method.invoke(job.fileLoader, job.args);
            job.state.markAttempt(generation, System.currentTimeMillis());
            job.recovering = false;
            ModuleLogger.hook(TAG, "restarted account=" + job.account + " file=" + fileName(job.args[0]));
        } catch (Throwable t) {
            job.recovering = false;
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, "restart failed: " + t.getMessage());
        }
    }

    private void cancelNative(Job job) {
        try {
            Method method = findCompatibleMethod(job.fileLoader.getClass(), "cancelLoadFile",
                    new Object[]{job.args[0], true});
            Object[] args = new Object[]{job.args[0], true};
            if (method == null) {
                args = new Object[]{job.args[0]};
                method = findCompatibleMethod(job.fileLoader.getClass(), "cancelLoadFile", args);
            }
            if (method != null) {
                method.setAccessible(true);
                method.invoke(job.fileLoader, args);
            }
        } catch (Throwable t) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, "stuck operation cancel failed: " + t.getMessage());
        }
    }

    private void complete(Job job, String reason) {
        if (jobs.remove(job.key, job)) {
            job.state.cancel();
            explicitlyCancelled.remove(job.key);
            ModuleLogger.hook(TAG, reason + " account=" + job.account + " file=" + fileName(job.args[0]));
        }
    }

    private int resolveAccount(Object message, Object fileLoader) {
        int account = Reflect.asInt(Reflect.field(message, "currentAccount"), -1);
        return account >= 0 ? account : Reflect.asInt(Reflect.field(fileLoader, "currentAccount"), 0);
    }

    private boolean isVideo(Object message) {
        if (message == null) return false;
        try {
            Method method = message.getClass().getMethod("isVideo");
            return Boolean.TRUE.equals(method.invoke(message));
        } catch (Throwable ignored) {
            return isVideoDocument(documentOf(message, null));
        }
    }

    private boolean isVideoDocument(Object document) {
        Object attributes = Reflect.field(document, "attributes");
        if (!(attributes instanceof Iterable<?>)) return false;
        for (Object attribute : (Iterable<?>) attributes) {
            if (attribute != null && attribute.getClass().getName().contains("DocumentAttributeVideo")) return true;
        }
        return false;
    }

    private Object documentOf(Object message, Object fallback) {
        if (message != null) {
            try {
                Method method = message.getClass().getMethod("getDocument");
                Object document = method.invoke(message);
                if (document != null) return document;
            } catch (Throwable ignored) { }
        }
        return fallback;
    }

    private String key(int account, Object document) {
        String name = fileName(document);
        return name == null || name.isEmpty() ? null : account + ":" + name;
    }

    private String fileName(Object document) {
        ClassLoader loader = classLoader;
        if (document == null || loader == null) return null;
        try {
            Class<?> fileLoaderClass = loader.loadClass("org.telegram.messenger.FileLoader");
            for (Method method : fileLoaderClass.getMethods()) {
                if ("getAttachFileName".equals(method.getName()) && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(document.getClass())) {
                    return (String) method.invoke(null, document);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (!name.equals(method.getName()) || params.length != args.length) continue;
            boolean compatible = true;
            for (int i = 0; i < params.length; i++) {
                if (args[i] == null) continue;
                Class<?> actual = args[i].getClass();
                if (params[i].isPrimitive()) {
                    compatible &= (params[i] == int.class && actual == Integer.class)
                            || (params[i] == boolean.class && actual == Boolean.class);
                } else {
                    compatible &= params[i].isAssignableFrom(actual);
                }
            }
            if (compatible) return method;
        }
        return null;
    }

    private static final class Job {
        final String key;
        final int account;
        final ReliableDownloadState state = new ReliableDownloadState();
        volatile Object fileLoader;
        volatile Object[] args;
        volatile boolean recovering;
        volatile int retryCount;

        Job(String key, int account, Object fileLoader, Object[] args) {
            this.key = key;
            this.account = account;
            this.fileLoader = fileLoader;
            this.args = args;
            state.start(System.currentTimeMillis());
        }

        void update(Object fileLoader, Object[] args) {
            this.fileLoader = fileLoader;
            this.args = args.clone();
        }
    }
}
