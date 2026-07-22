package com.tianqianguai.gramsieve.module;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackgroundMessageLoader {
    interface LoadedMessagesConsumer {
        void onLoadedMessages(long dialogId, List<?> messages);
    }

    private static final String TAG = "GramSieve";
    private static final int DEFAULT_HISTORY_LOAD_COUNT = 50;
    private static final long MIN_IMMEDIATE_LOAD_INTERVAL_MS = 1500L;
    private static final long INITIAL_RETRY_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long MAX_RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(15);

    private final MessageCache messageCache;
    private final AntiRecallConfigStore configStore;
    private final ScheduledExecutorService scheduler;
    private final Set<Long> enabledChats = ConcurrentHashMap.newKeySet();
    private final Map<Long, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<Long, FailureBackoff> failureBackoffs = new ConcurrentHashMap<>();
    private final AtomicBoolean loadCycleQueued = new AtomicBoolean();

    private volatile Handler mainHandler;
    private volatile boolean running;
    private volatile ClassLoader telegramClassLoader;
    private volatile TelegramHistoryApiAdapter historyApi;
    private volatile String compatibilityFailure = "not probed";
    private volatile long lastImmediateLoadAtMs;
    private volatile long lastLoadCycleAtMs;
    private volatile LoadedMessagesConsumer loadedMessagesConsumer;

    public BackgroundMessageLoader(MessageCache messageCache, AntiRecallConfigStore configStore) {
        this.messageCache = messageCache;
        this.configStore = configStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        loadEnabledChats();
    }

    private void loadEnabledChats() {
        if (configStore == null) {
            return;
        }
        Set<Long> savedChats = configStore.getEnabledChatIds();
        if (!savedChats.isEmpty()) {
            enabledChats.addAll(savedChats);
            info("BackgroundMessageLoader: loaded " + savedChats.size() + " enabled chats from config");
        }
    }

    void setLoadedMessagesConsumer(LoadedMessagesConsumer consumer) {
        loadedMessagesConsumer = consumer;
    }

    public void setTelegramClassLoader(ClassLoader classLoader) {
        telegramClassLoader = classLoader;
        info("BackgroundMessageLoader: telegramClassLoader set");
        TelegramHistoryApiAdapter.ProbeResult probe = TelegramHistoryApiAdapter.probe(classLoader);
        historyApi = probe.adapter;
        compatibilityFailure = probe.reason;
        if (probe.isSupported()) {
            info("BackgroundMessageLoader: compatibility supported " + probe.adapter.describe());
        } else {
            info("BackgroundMessageLoader: compatibility unsupported telegram="
                    + probe.telegramVersion + " reason=" + probe.reason
                    + "; proactive history loading disabled safely");
        }
        if (probe.isSupported() && !enabledChats.isEmpty()) {
            start();
        }
    }

    public synchronized void start() {
        if (running || configStore == null || enabledChats.isEmpty()) {
            return;
        }
        if (telegramClassLoader == null) {
            info("BackgroundMessageLoader: start deferred until classLoader is ready");
            return;
        }
        if (historyApi == null) {
            info("BackgroundMessageLoader: start skipped, incompatible Telegram API reason="
                    + compatibilityFailure);
            return;
        }
        running = true;
        int interval = configStore.getLoadIntervalSeconds();
        info("BackgroundMessageLoader: starting isolated history loads with interval=" + interval + "s");
        scheduler.scheduleAtFixedRate(this::loadMessages, 0, interval, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        cancelAllPendingRequests();
        scheduler.shutdown();
        info("BackgroundMessageLoader: stopped");
    }

    public void enableChat(long dialogId) {
        enabledChats.add(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, true);
        }
        info("BackgroundMessageLoader: enabledChat dialogId=" + dialogId + " enabledChats=" + enabledChats.size());
        start();
    }

    public void disableChat(long dialogId) {
        enabledChats.remove(dialogId);
        cancelPendingRequest(dialogId);
        failureBackoffs.remove(dialogId);
        if (configStore != null) {
            configStore.setChatEnabled(dialogId, false);
        }
        info("BackgroundMessageLoader: disabledChat dialogId=" + dialogId + " enabledChats=" + enabledChats.size());
    }

    public boolean isChatEnabled(long dialogId) {
        return enabledChats.contains(dialogId);
    }

    Set<Long> enabledChatIdsSnapshot() {
        return new java.util.HashSet<>(enabledChats);
    }

    public void triggerImmediateLoad(String reason) {
        if (enabledChats.isEmpty()) {
            info("BackgroundMessageLoader: immediate load skipped, no enabled chats reason=" + reason);
            return;
        }
        if (telegramClassLoader == null) {
            info("BackgroundMessageLoader: immediate load skipped, classLoader missing reason=" + reason);
            return;
        }
        if (historyApi == null) {
            info("BackgroundMessageLoader: immediate load skipped, incompatible Telegram API reason="
                    + compatibilityFailure + " trigger=" + reason);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long last = lastImmediateLoadAtMs;
        if (last != 0L && now - last < MIN_IMMEDIATE_LOAD_INTERVAL_MS) {
            info("BackgroundMessageLoader: immediate load debounced reason=" + reason);
            return;
        }
        lastImmediateLoadAtMs = now;
        info("BackgroundMessageLoader: immediate isolated load reason=" + reason
                + " chats=" + enabledChats.size());
        loadMessages();
    }

    private void loadMessages() {
        if (enabledChats.isEmpty() || telegramClassLoader == null || historyApi == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (isLoadCycleTooSoon(now, lastLoadCycleAtMs)) {
            info("BackgroundMessageLoader: coalesced near-duplicate load cycle");
            return;
        }
        if (!loadCycleQueued.compareAndSet(false, true)) {
            info("BackgroundMessageLoader: coalesced overlapping load cycle");
            return;
        }
        lastLoadCycleAtMs = now;

        Handler handler = ensureMainHandler();
        if (handler != null && Looper.myLooper() != Looper.getMainLooper()) {
            if (!handler.post(this::loadMessagesOnCurrentThread)) {
                loadCycleQueued.set(false);
                info("BackgroundMessageLoader: failed to post isolated load cycle");
            }
            return;
        }
        loadMessagesOnCurrentThread();
    }

    private void loadMessagesOnCurrentThread() {
        try {
            if (enabledChats.isEmpty()) {
                return;
            }
            for (long dialogId : enabledChats) {
                try {
                    requestHistoryForChat(dialogId);
                } catch (Throwable throwable) {
                    error("BackgroundMessageLoader: isolated history request failed for dialog "
                            + dialogId, throwable);
                }
            }
        } finally {
            loadCycleQueued.set(false);
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

    private void requestHistoryForChat(long dialogId) throws Exception {
        TelegramHistoryApiAdapter api = historyApi;
        if (api == null || !enabledChats.contains(dialogId)) {
            return;
        }
        FailureBackoff backoff = failureBackoffs.get(dialogId);
        if (backoff != null && SystemClock.elapsedRealtime() < backoff.retryAfterElapsedMs) {
            return;
        }

        Object controller = api.getController(0);
        if (controller == null) {
            info("BackgroundMessageLoader: controller unavailable for dialog " + dialogId);
            return;
        }

        Object peer = resolveInputPeer(controller, dialogId);
        if (peer == null) {
            info("BackgroundMessageLoader: input peer unavailable for dialog " + dialogId);
            return;
        }

        Object request = api.newHistoryRequest(peer, DEFAULT_HISTORY_LOAD_COUNT);

        Object connectionsManager = api.getConnectionsManager(controller);
        if (connectionsManager == null) {
            info("BackgroundMessageLoader: ConnectionsManager unavailable for dialog " + dialogId);
            return;
        }

        String peerType = peer.getClass().getSimpleName();
        PendingRequest pending = new PendingRequest(api, connectionsManager, peerType);
        Object delegate = api.newRequestDelegate(
                (proxy, method, args) -> handleDelegateInvocation(proxy, method, args, dialogId, pending));

        PendingRequest previous = pendingRequests.put(dialogId, pending);
        cancelPending(previous);

        int requestId = api.sendRequest(connectionsManager, request, delegate);
        if (requestId <= 0) {
            pendingRequests.remove(dialogId, pending);
            info("BackgroundMessageLoader: sendRequest rejected for dialog " + dialogId);
            return;
        }

        pending.setRequestId(requestId);
    }

    private Object handleDelegateInvocation(Object proxy, Method method, Object[] args,
                                            long dialogId, PendingRequest pending) {
        if (method.getDeclaringClass() == Object.class) {
            if ("toString".equals(method.getName())) {
                return "GramSieveHistoryRequestDelegate(" + dialogId + ")";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            return null;
        }
        if (!"run".equals(method.getName())) {
            return null;
        }

        pending.finished = true;
        pendingRequests.remove(dialogId, pending);
        if (pending.cancelled) {
            return null;
        }
        Object response = args != null && args.length > 0 ? args[0] : null;
        Object error = args != null && args.length > 1 ? args[1] : null;
        if (error != null) {
            int code = Reflect.asInt(Reflect.field(error, "code"), 0);
            String text = Reflect.asString(Reflect.field(error, "text"));
            FailureBackoff backoff = recordFailure(dialogId);
            info("BackgroundMessageLoader: isolated getHistory error dialog=" + dialogId
                    + " peer=" + pending.peerType + " code=" + code + " text=" + text
                    + " failures=" + backoff.failureCount
                    + " retryIn=" + backoff.retryDelayMs / 1000L + "s");
            return null;
        }

        List<?> messages = historyMessagesFromResponse(response);
        if (messages == null) {
            info("BackgroundMessageLoader: unexpected getHistory response dialog=" + dialogId
                    + " type=" + (response == null ? "null" : response.getClass().getName()));
            return null;
        }
        if (!enabledChats.contains(dialogId)) {
            info("BackgroundMessageLoader: ignored completed request for disabled dialog " + dialogId);
            return null;
        }
        failureBackoffs.remove(dialogId);

        LoadedMessagesConsumer consumer = loadedMessagesConsumer;
        if (consumer == null) {
            info("BackgroundMessageLoader: loaded messages consumer unavailable for dialog " + dialogId);
            return null;
        }
        try {
            consumer.onLoadedMessages(dialogId, messages);
        } catch (Throwable throwable) {
            error("BackgroundMessageLoader: failed to cache isolated history for dialog "
                    + dialogId, throwable);
        }
        return null;
    }

    static Object resolveInputPeer(Object controller, long dialogId) {
        Object peer = Reflect.invokeIfExists(controller, "getInputPeer",
                new Class<?>[]{long.class}, dialogId);
        if (peer == null || dialogId >= 0L
                || !"TL_inputPeerChat".equals(peer.getClass().getSimpleName())) {
            return peer;
        }

        long chatId = -dialogId;
        Object inMemoryChat = Reflect.invokeIfExists(controller, "getChat",
                new Class<?>[]{Long.class}, Long.valueOf(chatId));
        if (inMemoryChat != null) {
            return peer;
        }

        Object storage = Reflect.invokeIfExists(controller, "getMessagesStorage", new Class<?>[0]);
        Object storedChat = Reflect.invokeIfExists(storage, "getChatSync",
                new Class<?>[]{long.class}, chatId);
        if (storedChat == null) {
            return peer;
        }

        Reflect.invokeIfExists(controller, "putChat", null, storedChat, true);
        Object hydratedPeer = Reflect.invokeIfExists(controller, "getInputPeer",
                new Class<?>[]{long.class}, dialogId);
        return hydratedPeer == null ? peer : hydratedPeer;
    }

    static boolean isLoadCycleTooSoon(long nowElapsedMs, long lastElapsedMs) {
        return lastElapsedMs > 0L
                && nowElapsedMs >= lastElapsedMs
                && nowElapsedMs - lastElapsedMs < MIN_IMMEDIATE_LOAD_INTERVAL_MS;
    }

    static long retryDelayMs(int failureCount) {
        int shifts = Math.max(0, Math.min(failureCount - 1, 10));
        long delay = INITIAL_RETRY_DELAY_MS << shifts;
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    private FailureBackoff recordFailure(long dialogId) {
        long now = SystemClock.elapsedRealtime();
        return failureBackoffs.compute(dialogId, (ignored, previous) -> {
            int failureCount = previous == null ? 1 : previous.failureCount + 1;
            long retryDelayMs = retryDelayMs(failureCount);
            return new FailureBackoff(failureCount, retryDelayMs, now + retryDelayMs);
        });
    }

    static List<?> historyMessagesFromResponse(Object response) {
        Object messages = Reflect.field(response, "messages");
        return messages instanceof List<?> ? new ArrayList<>((List<?>) messages) : null;
    }

    static LoadedRange inspectLoadedRange(List<?> messages) {
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        int minDate = Integer.MAX_VALUE;
        int maxDate = Integer.MIN_VALUE;
        int count = 0;
        if (messages != null) {
            for (Object message : messages) {
                int messageId = Reflect.asInt(Reflect.field(message, "id"), 0);
                int date = Reflect.asInt(Reflect.field(message, "date"), 0);
                if (messageId != 0) {
                    minId = Math.min(minId, messageId);
                    maxId = Math.max(maxId, messageId);
                }
                if (date != 0) {
                    minDate = Math.min(minDate, date);
                    maxDate = Math.max(maxDate, date);
                }
                count++;
            }
        }
        return new LoadedRange(count,
                minId == Integer.MAX_VALUE ? 0 : minId,
                maxId == Integer.MIN_VALUE ? 0 : maxId,
                minDate == Integer.MAX_VALUE ? 0 : minDate,
                maxDate == Integer.MIN_VALUE ? 0 : maxDate);
    }

    private void cancelPendingRequest(long dialogId) {
        cancelPending(pendingRequests.remove(dialogId));
    }

    private void cancelAllPendingRequests() {
        for (PendingRequest pending : pendingRequests.values()) {
            cancelPending(pending);
        }
        pendingRequests.clear();
    }

    private void cancelPending(PendingRequest pending) {
        if (pending != null) {
            pending.cancel();
        }
    }

    public void onMessageReceived(long dialogId, long messageId, String text, String caption, long senderId) {
        if (!isChatEnabled(dialogId)) {
            return;
        }
        messageCache.put(dialogId, messageId, text, caption, senderId);
    }

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }

    static final class LoadedRange {
        final int count;
        final int minId;
        final int maxId;
        final int minDate;
        final int maxDate;

        LoadedRange(int count, int minId, int maxId, int minDate, int maxDate) {
            this.count = count;
            this.minId = minId;
            this.maxId = maxId;
            this.minDate = minDate;
            this.maxDate = maxDate;
        }
    }

    private static final class PendingRequest {
        private final TelegramHistoryApiAdapter historyApi;
        private final Object connectionsManager;
        private final String peerType;
        private volatile int requestId;
        private volatile boolean cancelled;
        private volatile boolean finished;

        PendingRequest(TelegramHistoryApiAdapter historyApi, Object connectionsManager, String peerType) {
            this.historyApi = historyApi;
            this.connectionsManager = connectionsManager;
            this.peerType = peerType;
        }

        void setRequestId(int requestId) {
            this.requestId = requestId;
            if (cancelled) {
                cancelRequest(requestId);
            }
        }

        void cancel() {
            cancelled = true;
            int id = requestId;
            if (id > 0 && !finished) {
                cancelRequest(id);
            }
        }

        private void cancelRequest(int id) {
            historyApi.cancelRequest(connectionsManager, id);
        }
    }

    private static final class FailureBackoff {
        private final int failureCount;
        private final long retryDelayMs;
        private final long retryAfterElapsedMs;

        FailureBackoff(int failureCount, long retryDelayMs, long retryAfterElapsedMs) {
            this.failureCount = failureCount;
            this.retryDelayMs = retryDelayMs;
            this.retryAfterElapsedMs = retryAfterElapsedMs;
        }
    }
}
