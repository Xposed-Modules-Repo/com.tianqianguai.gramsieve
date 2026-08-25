package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
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
    private final Set<String> loggedCancelledVideoState = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedCancelledPlayerCleanup = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastForcedCancelAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastVideoStateClearAt = new ConcurrentHashMap<>();
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
            // Telegram's mini X calls MediaController.cleanupPlayer() itself, but the primary
            // button path only cancels FileLoader. A still-playing stream then immediately
            // recreates buttonState=1 during updateButtonState, which is the observed rebound.
            stopActivePlayerForExplicitCancel(message, account, key, document);
            hardStopNativeVideoState(account, key, document, "button");
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

    void onUserMiniButton(Object cell, Object[] buttonArgs) {
        Object message = Reflect.field(cell, "currentMessageObject");
        if (!isVideo(message)) {
            return;
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
            return;
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
            hardStopNativeVideoState(account, key, document, "mini");
            ModuleLogger.hook(TAG, "mini explicit-cancel state=" + miniState + " mainState=" + buttonState
                    + " " + targetDescription(account, key, document) + " hadJob=" + hadJob);
        } else if (isExplicitMiniStartState(miniState)) {
            markExplicitStart(key);
            ModuleLogger.hook(TAG, "mini explicit-start " + targetDescription(account, key, document));
        } else {
            ModuleLogger.hook(TAG, "mini ignored reason=non-download-state state=" + miniState
                    + " mainState=" + buttonState + " "
                    + targetDescription(account, key, document));
        }
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

    boolean onLoadFileInternal(Object fileLoader, Object[] args) {
        if (args == null || args.length == 0) {
            return true;
        }
        // loadStreamFile posts work to FileLoader's serial queue before it returns. A user can
        // press X after that outer call has passed our stream hook but before the queued work
        // reaches loadFileInternal. Guarding here closes that race at the actual operation-creation
        // point, shared by ordinary and streaming downloads.
        Object parent = args.length > 5 ? args[5] : null;
        return onDocumentTransport(fileLoader, args[0], parent, null, "internal", false);
    }

    /** @return false to hide a stale loading operation that the user explicitly cancelled. */
    boolean onIsLoadingFile(Object fileLoader, Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof String)) {
            return true;
        }
        String fileName = (String) args[0];
        int account = resolveAccount(null, fileLoader);
        String key = account + ":" + fileName;
        if (!cancellationRegistry.isCancelled(key)) {
            return true;
        }
        if (loggedCancelledTransport.add(key)) {
            ModuleLogger.hook(TAG, "loading-state suppressed after explicit-cancel account=" + account
                    + " key=" + key + " file=" + fileName
                    + " thread=" + Thread.currentThread().getName());
        }
        reinforceNativeCancel(fileLoader, account, key, fileName);
        return false;
    }

    /** @return false when the player-only loading marker must remain cleared after X. */
    boolean onIsLoadingVideo(Object fileLoader, Object[] args) {
        if (args == null || args.length != 2) {
            return true;
        }
        Object document = args[0];
        if (!isVideoDocument(document)) {
            return true;
        }
        int account = resolveAccount(null, fileLoader);
        String key = key(account, document);
        if (key == null || !cancellationRegistry.isCancelled(key)) {
            return true;
        }
        clearNativeVideoLoadingState(fileLoader, account, key, document, "query");
        return false;
    }

    /** @return false to prevent a player callback from reintroducing the canceled video's X state. */
    boolean onSetLoadingVideo(Object fileLoader, Object[] args, String route) {
        if (args == null || args.length == 0) {
            return true;
        }
        Object document = args[0];
        if (!isVideoDocument(document)) {
            return true;
        }
        int account = resolveAccount(null, fileLoader);
        String key = key(account, document);
        if (key == null || !cancellationRegistry.isCancelled(key)) {
            return true;
        }
        clearNativeVideoLoadingState(fileLoader, account, key, document, route);
        return false;
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
                ModuleLogger.hook(TAG, "cancellation failure ignored "
                        + targetDescription(account, key, job.args[0]));
            } else if (isExplicitCancelFailure(args)) {
                // FileLoader removes the old operation before posting reason=1. If the user
                // already started again, re-submit without cancelling the fresh operation.
                scheduleRestart(job, "previous cancel completed", false);
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
        scheduleRestart(job, reason, true);
    }

    private void scheduleRestart(Job job, String reason, boolean cancelFirst) {
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
        long delayMs = cancelFirst
                ? Math.min(15_000L, RESTART_DELAY_MS << Math.min(job.retryCount++, 4))
                : 0L;
        ModuleLogger.hook(TAG, "recovering " + reason + " account=" + job.account
                + " file=" + fileName(job.args[0]) + " delayMs=" + delayMs);
        if (cancelFirst) {
            cancelNative(job);
        }
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
            loggedCancelledVideoState.remove(job.key);
            loggedCancelledPlayerCleanup.remove(job.key);
            lastForcedCancelAt.remove(job.key);
            lastVideoStateClearAt.remove(job.key);
            ModuleLogger.hook(TAG, reason + " "
                    + targetDescription(job.account, job.key, job.args[0]));
        }
    }

    private boolean markExplicitCancel(String key) {
        userStarted.remove(key);
        cancellationRegistry.markCancelled(key);
        loggedCancelledTransport.remove(key);
        lastForcedCancelAt.remove(key);
        lastVideoStateClearAt.remove(key);
        Job job = jobs.remove(key);
        if (job != null) {
            job.state.cancel();
        }
        return job != null;
    }

    private void markExplicitStart(String key) {
        cancellationRegistry.allow(key);
        loggedCancelledTransport.remove(key);
        loggedCancelledVideoState.remove(key);
        loggedCancelledPlayerCleanup.remove(key);
        lastForcedCancelAt.remove(key);
        lastVideoStateClearAt.remove(key);
        userStarted.add(key);
    }

    private void hardStopNativeVideoState(int account, String key, Object document, String source) {
        Object fileLoader = fileLoaderFor(account);
        if (fileLoader == null || document == null) {
            ModuleLogger.hook(TAG, "player hard-stop unavailable source=" + source
                    + " account=" + account + " key=" + key);
            return;
        }
        cancelNativeDocument(fileLoader, account, key, document);
        clearNativeVideoLoadingState(fileLoader, account, key, document, source);
    }

    private void stopActivePlayerForExplicitCancel(Object message, int account, String key,
                                                    Object document) {
        if (message == null || classLoader == null) {
            return;
        }
        try {
            Class<?> mediaControllerClass = classLoader.loadClass("org.telegram.messenger.MediaController");
            Object mediaController = mediaControllerClass.getMethod("getInstance").invoke(null);
            Method isPlaying = findCompatibleMethod(mediaControllerClass, "isPlayingMessage",
                    new Object[]{message});
            if (isPlaying == null || !Boolean.TRUE.equals(isPlaying.invoke(mediaController, message))) {
                return;
            }
            Method cleanup = findCompatibleMethod(mediaControllerClass, "cleanupPlayer",
                    new Object[]{true, true});
            if (cleanup == null) {
                ModuleLogger.hook(TAG, "player cleanup unavailable account=" + account + " key=" + key);
                return;
            }
            cleanup.invoke(mediaController, true, true);
            if (loggedCancelledPlayerCleanup.add(key)) {
                ModuleLogger.hook(TAG, "player cleanup invoked after explicit-cancel "
                        + targetDescription(account, key, document));
            }
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "player cleanup after explicit-cancel failed account=" + account
                            + " key=" + key + ": " + throwable.getMessage());
        }
    }

    private Object fileLoaderFor(int account) {
        ClassLoader loader = classLoader;
        if (loader == null) {
            return null;
        }
        try {
            Class<?> fileLoaderClass = loader.loadClass("org.telegram.messenger.FileLoader");
            Method getInstance = fileLoaderClass.getMethod("getInstance", int.class);
            return getInstance.invoke(null, account);
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "player hard-stop could not resolve FileLoader: " + throwable.getMessage());
            return null;
        }
    }

    private void cancelNativeDocument(Object fileLoader, int account, String key, Object document) {
        try {
            Object[] cancelArgs = new Object[]{document, true};
            Method method = findCompatibleMethod(fileLoader.getClass(), "cancelLoadFile", cancelArgs);
            if (method == null) {
                cancelArgs = new Object[]{document};
                method = findCompatibleMethod(fileLoader.getClass(), "cancelLoadFile", cancelArgs);
            }
            if (method != null) {
                method.setAccessible(true);
                method.invoke(fileLoader, cancelArgs);
            }
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "player hard-stop transport cancel failed account=" + account
                            + " key=" + key + ": " + throwable.getMessage());
        }
    }

    private void clearNativeVideoLoadingState(Object fileLoader, int account, String key,
                                               Object document, String source) {
        if (fileLoader == null || document == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastVideoStateClearAt.get(key);
        if (previous != null && now - previous < 250L) {
            return;
        }
        lastVideoStateClearAt.put(key, now);
        try {
            Method method = findCompatibleMethod(fileLoader.getClass(), "removeLoadingVideo",
                    new Object[]{document, false, true});
            if (method == null) {
                ModuleLogger.hook(TAG, "player loading-state clear unavailable account=" + account
                        + " key=" + key + " source=" + source);
                return;
            }
            method.setAccessible(true);
            // Telegram keeps distinct loadingVideos entries for the normal and playing-player
            // variants. cancelLoadFile() does not remove either entry, so clear both before the
            // next UI refresh can turn the mini button back into X.
            method.invoke(fileLoader, document, false, true);
            method.invoke(fileLoader, document, true, true);
            if (loggedCancelledVideoState.add(key)) {
                ModuleLogger.hook(TAG, "player loading-state cleared after explicit-cancel source="
                        + source + " " + targetDescription(account, key, document));
            }
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "player loading-state clear failed account=" + account
                            + " key=" + key + ": " + throwable.getMessage());
        }
    }

    private void reinforceNativeCancel(Object fileLoader, int account, String key, String fileName) {
        if (fileLoader == null || fileName == null || fileName.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastForcedCancelAt.get(key);
        if (previous != null && now - previous < 750L) {
            return;
        }
        lastForcedCancelAt.put(key, now);
        try {
            Method method = findCompatibleMethod(fileLoader.getClass(), "cancelLoadFile",
                    new Object[]{fileName});
            if (method == null) {
                ModuleLogger.hook(TAG, "loading-state cancel unavailable account=" + account
                        + " key=" + key + " file=" + fileName);
                return;
            }
            method.setAccessible(true);
            method.invoke(fileLoader, fileName);
            ModuleLogger.hook(TAG, "loading-state native cancel reinforced account=" + account
                    + " key=" + key + " file=" + fileName);
        } catch (Throwable throwable) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "loading-state cancel reinforcement failed: " + throwable.getMessage());
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
        if (document == null) {
            return false;
        }
        Object attributes = Reflect.field(document, "attributes");
        if (attributes instanceof Iterable<?>) {
            for (Object attribute : (Iterable<?>) attributes) {
                if (attribute != null && attribute.getClass().getName().contains("DocumentAttributeVideo")) {
                    return true;
                }
            }
        }
        // Videos sent "as a file" can be playable in Telegram without a DocumentAttributeVideo.
        // The explicit-cancel gate must cover those MOV/MP4 documents as well.
        return isVideoMetadata(Reflect.asString(Reflect.field(document, "mime_type")), fileName(document));
    }

    static boolean isVideoMetadata(String mimeType, String fileName) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/")) {
            return true;
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")
                || name.endsWith(".webm") || name.endsWith(".mkv");
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

    static boolean isExplicitMiniStartState(int state) {
        // A visible mini download button at state 0 is a fresh user start, including after X.
        return state == 0;
    }

    static boolean isExplicitCancelFailure(Object[] notificationArgs) {
        // Telegram posts fileLoadFailed(fileName, reason); reason 1 is FileLoadOperation.cancel().
        return notificationArgs != null && notificationArgs.length >= 2
                && Reflect.asInt(notificationArgs[1], Integer.MIN_VALUE) == 1;
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
