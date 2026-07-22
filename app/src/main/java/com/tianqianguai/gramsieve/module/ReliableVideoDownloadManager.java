package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.Arrays;
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
    private final Set<String> loggedCancelledTransport = ConcurrentHashMap.newKeySet();
    private final DownloadCancellationRegistry cancellationRegistry;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "GramSieve-ReliableDownload");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ClassLoader classLoader;

    ReliableVideoDownloadManager(DownloadCancellationRegistry cancellationRegistry) {
        this.cancellationRegistry = cancellationRegistry;
        scheduler.scheduleWithFixedDelay(this::scanForStalls,
                WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    void onUserButton(Object cell, Object[] buttonArgs) {
        Object message = Reflect.field(cell, "currentMessageObject");
        if (!isVideo(message)) {
            return;
        }
        Object document = documentOf(message, Reflect.field(cell, "documentAttach"));
        int account = Reflect.asInt(Reflect.field(cell, "currentAccount"), 0);
        String key = key(account, document);
        int state = Reflect.asInt(Reflect.field(cell, "buttonState"), -1);
        if (key == null) {
            ModuleLogger.hook(TAG, "button ignored reason=missing-key state=" + state
                    + " account=" + account + " message=" + typeOf(message)
                    + " attachment=" + typeOf(document) + " args=" + Arrays.toString(buttonArgs));
            return;
        }
        boolean hadJob = jobs.containsKey(key);
        boolean pendingStart = userStarted.contains(key);
        boolean cancelled = cancellationRegistry.isCancelled(key);
        ModuleLogger.hook(TAG, "button before state=" + state + " "
                + targetDescription(account, key, document) + " hadJob=" + hadJob
                + " pendingStart=" + pendingStart + " cancelled=" + cancelled
                + " args=" + Arrays.toString(buttonArgs));
        if (isExplicitCancelState(state)) {
            hadJob = markExplicitCancel(key);
            ModuleLogger.hook(TAG, "button explicit-cancel state=" + state + " "
                    + targetDescription(account, key, document) + " hadJob=" + hadJob);
        } else if (state == 0) {
            markExplicitStart(key);
            ModuleLogger.hook(TAG, "button explicit-start " + targetDescription(account, key, document));
        } else {
            ModuleLogger.hook(TAG, "button ignored reason=non-cancel-state state=" + state + " "
                    + targetDescription(account, key, document));
        }
    }

    void onUserButtonComplete(Object cell, Object[] buttonArgs) {
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
        ModuleLogger.hook(TAG, "button after state=" + state + " "
                + targetDescription(account, key, document) + " tracked=" + jobs.containsKey(key)
                + " pendingStart=" + userStarted.contains(key)
                + " cancelled=" + cancellationRegistry.isCancelled(key)
                + " args=" + Arrays.toString(buttonArgs));
    }

    /** @return true when the native mini-button action must not be allowed to restart a canceled stream. */
    boolean onUserMiniButton(Object cell, Object[] buttonArgs) {
        Object message = Reflect.field(cell, "currentMessageObject");
        if (!isVideo(message)) {
            return false;
        }
        Object document = documentOf(message, Reflect.field(cell, "documentAttach"));
        int account = Reflect.asInt(Reflect.field(cell, "currentAccount"), 0);
        String key = key(account, document);
        int miniState = Reflect.asInt(Reflect.field(cell, "miniButtonState"), -1);
        int buttonState = Reflect.asInt(Reflect.field(cell, "buttonState"), -1);
        if (key == null) {
            ModuleLogger.hook(TAG, "mini ignored reason=missing-key miniState=" + miniState
                    + " mainState=" + buttonState + " account=" + account
                    + " message=" + typeOf(message) + " attachment=" + typeOf(document)
                    + " args=" + Arrays.toString(buttonArgs));
            return false;
        }
        boolean hadJob = jobs.containsKey(key);
        boolean pendingStart = userStarted.contains(key);
        boolean cancelled = cancellationRegistry.isCancelled(key);
        ModuleLogger.hook(TAG, "mini before state=" + miniState + " mainState=" + buttonState + " "
                + targetDescription(account, key, document) + " hadJob=" + hadJob
                + " pendingStart=" + pendingStart + " cancelled=" + cancelled
                + " args=" + Arrays.toString(buttonArgs));
        if (isExplicitMiniCancelState(miniState)) {
            hadJob = markExplicitCancel(key);
            ModuleLogger.hook(TAG, "mini explicit-cancel state=" + miniState + " mainState=" + buttonState
                    + " " + targetDescription(account, key, document) + " hadJob=" + hadJob);
        } else if (miniState == 0) {
            if (cancellationRegistry.isCancelled(key)) {
                ModuleLogger.hook(TAG, "mini start blocked after explicit-cancel; requires primary download "
                        + targetDescription(account, key, document));
                return true;
            }
            markExplicitStart(key);
            ModuleLogger.hook(TAG, "mini explicit-start " + targetDescription(account, key, document));
        } else {
            ModuleLogger.hook(TAG, "mini ignored reason=non-download-state state=" + miniState
                    + " mainState=" + buttonState + " "
                    + targetDescription(account, key, document));
        }
        return false;
    }

    void onUserMiniButtonComplete(Object cell, Object[] buttonArgs) {
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
        int miniState = Reflect.asInt(Reflect.field(cell, "miniButtonState"), -1);
        int buttonState = Reflect.asInt(Reflect.field(cell, "buttonState"), -1);
        ModuleLogger.hook(TAG, "mini after state=" + miniState + " mainState=" + buttonState + " "
                + targetDescription(account, key, document) + " tracked=" + jobs.containsKey(key)
                + " pendingStart=" + userStarted.contains(key)
                + " cancelled=" + cancellationRegistry.isCancelled(key)
                + " args=" + Arrays.toString(buttonArgs));
    }

    boolean onLoadFile(Object fileLoader, Object[] args) {
        if (args == null || args.length != 4) {
            return true;
        }
        return onDocumentTransport(fileLoader, args[0], args[1], args, "download", true);
    }

    boolean onLoadStreamFile(Object fileLoader, Object[] args) {
        if (args == null || args.length < 4) {
            return true;
        }
        // FileLoader.loadStreamFile(stream, document, imageLocation, parent, ...) is used
        // after the user opens a video for playback. It can be re-entered repeatedly by the
        // player after a cancellation, so it must honor the same explicit-cancel registry.
        return onDocumentTransport(fileLoader, args[1], args[3], null, "stream", false);
    }

    private boolean onDocumentTransport(Object fileLoader, Object document, Object message,
                                        Object[] restartArgs, String route, boolean trackUserStart) {
        if (!isVideoDocument(document)) {
            return true;
        }
        int account = resolveAccount(message, fileLoader);
        String key = key(account, document);
        if (key == null) {
            ModuleLogger.hook(TAG, "transport observed reason=missing-key route=" + route
                    + " account=" + account + " attachment=" + typeOf(document));
            return true;
        }
        if (cancellationRegistry.isCancelled(key)) {
            if (loggedCancelledTransport.add(key)) {
                ModuleLogger.hook(TAG, "transport suppressed after explicit-cancel "
                        + "route=" + route + " " + targetDescription(account, key, document)
                        + " thread=" + Thread.currentThread().getName());
            }
            return false;
        }
        if (!trackUserStart) {
            return true;
        }
        Job existing = jobs.get(key);
        if (existing != null) {
            existing.update(fileLoader, restartArgs);
            return true;
        }
        if (!userStarted.remove(key)) {
            return true;
        }
        Job job = new Job(key, account, fileLoader, restartArgs.clone());
        jobs.put(key, job);
        ModuleLogger.hook(TAG, "guarding route=" + route + " "
                + targetDescription(account, key, document));
        return true;
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
        } else if (id == failedId) {
            if (cancellationRegistry.isCancelled(key)) {
                ModuleLogger.hook(TAG, "failure notification ignored after explicit-cancel "
                        + targetDescription(account, key, job.args[0]));
            } else {
                scheduleRestart(job, "interrupted");
            }
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
            if (job.recovering) {
                return;
            }
            if (cancellationRegistry.isCancelled(job.key)) {
                ModuleLogger.hook(TAG, "recovery skipped after explicit-cancel "
                        + targetDescription(job.account, job.key, job.args[0]));
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
        if (cancellationRegistry.isCancelled(job.key)) {
            ModuleLogger.hook(TAG, "restart dropped after explicit-cancel "
                    + targetDescription(job.account, job.key, job.args[0])
                    + " generation=" + generation);
            return;
        }
        if (!job.state.isCurrent(generation) || jobs.get(job.key) != job) {
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
            ModuleLogger.hook(TAG, "restarted " + targetDescription(job.account, job.key, job.args[0]));
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
                ModuleLogger.hook(TAG, "native cancel invoked "
                        + targetDescription(job.account, job.key, job.args[0])
                        + " overloadArgs=" + args.length);
            } else {
                ModuleLogger.hook(TAG, "native cancel unavailable "
                        + targetDescription(job.account, job.key, job.args[0]));
            }
        } catch (Throwable t) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, "stuck operation cancel failed: " + t.getMessage());
        }
    }

    private void complete(Job job, String reason) {
        if (jobs.remove(job.key, job)) {
            job.state.cancel();
            cancellationRegistry.allow(job.key);
            loggedCancelledTransport.remove(job.key);
            ModuleLogger.hook(TAG, reason + " "
                    + targetDescription(job.account, job.key, job.args[0]));
        }
    }

    private boolean markExplicitCancel(String key) {
        userStarted.remove(key);
        cancellationRegistry.markCancelled(key);
        loggedCancelledTransport.remove(key);
        Job job = jobs.remove(key);
        if (job != null) {
            job.state.cancel();
        }
        return job != null;
    }

    private void markExplicitStart(String key) {
        cancellationRegistry.allow(key);
        loggedCancelledTransport.remove(key);
        userStarted.add(key);
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
        return cancellationRegistry.keyFor(account, classLoader, document);
    }

    private String fileName(Object document) {
        return cancellationRegistry.fileNameFor(classLoader, document);
    }

    private String targetDescription(int account, String key, Object document) {
        return "account=" + account + " key=" + key + " file=" + fileName(document)
                + " attachment=" + typeOf(document);
    }

    static boolean isExplicitCancelState(int state) {
        // The primary button's states 1 and 4 reach FileLoader.cancelLoadFile in Telegram 12.9.0.
        return state == 1 || state == 4;
    }

    static boolean isExplicitMiniCancelState(int state) {
        // The visible X for a video with mini progress is miniButtonState == 1.
        return state == 1;
    }

    private String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
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
