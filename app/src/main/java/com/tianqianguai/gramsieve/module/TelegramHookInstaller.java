package com.tianqianguai.gramsieve.module;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupWindow;

import androidx.recyclerview.widget.RecyclerView;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.ChatReadPositionStore;
import com.tianqianguai.gramsieve.config.ConfigContentProvider;
import com.tianqianguai.gramsieve.config.ConfigUpdateReceiver;
import com.tianqianguai.gramsieve.config.DiagnosticLogStore;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.LogFileSupport;
import com.tianqianguai.gramsieve.config.LogPrivacy;
import com.tianqianguai.gramsieve.config.LogTimeRange;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.config.RuntimeModuleProbe;
import com.tianqianguai.gramsieve.config.XposedConfigProvider;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageRuleFactory;
import com.tianqianguai.gramsieve.core.MessageSnapshot;
import com.tianqianguai.gramsieve.core.ModuleConflictDetector;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class TelegramHookInstaller {
    private static final String TAG = "GramSieve";
    private static final String MODULE_PACKAGE = "com.tianqianguai.gramsieve";
    private static final String CONFIG_MODE_GLOBAL = "global";
    private static final String CONFIG_MODE_CHAT = "chat";
    private static final int MENU_ID_CHAT = 0x47530011;
    private static final int MENU_ID_GLOBAL = 0x47530012;
    private static final int MENU_ID_BLOCK_MESSAGE = 0x47530013;
    private static final int MENU_ID_SCROLL_TOP = 0x47530014;
    private static final int MENU_ID_SELECT_ALL = 0x47530015;
    private static final int MENU_ID_MARK_MESSAGE = 0x47530016;
    private static final int MENU_ID_JUMP_TO_MARK = 0x47530017;
    private static final int MENU_ID_ANTI_RECALL = 0x47530018;
    private static final int MENU_ID_EDIT_HISTORY = 0x47530019;
    private static final int SETTINGS_ROW_GRAMSIEVE = 0x4753001A;
    private static final int MENU_ID_CLEANUP_MODE = 0x4753001B;
    private static final int MENU_ID_RELOAD_MESSAGE = 0x4753001C;
    private static final int MENU_ID_FIRST_MESSAGE = 0x4753001D;
    private static final int SETTINGS_ROW_COLOR_START = 0xFF2AABEE;
    private static final int SETTINGS_ROW_COLOR_END = 0xFF229ED9;
    private static final long MODULE_FALLBACK_SNAPSHOT_MS = 1500L;
    private static final String CLI_ACTION = MODULE_PACKAGE + ".action.CLI";
    private static final int CLI_PROTOCOL_VERSION = 1;
    private static final int MAX_CLI_RESPONSE_CHARS = 512 * 1024;
    private ViewGroup downloadPageFragmentView = null;
    private MessageCache messageCache;
    private MediaCache mediaCache;
    private MediaPrefetcher mediaPrefetcher;
    private final DownloadCancellationRegistry downloadCancellationRegistry =
            new DownloadCancellationRegistry();
    private final ReliableDownloadHooks reliableDownloadHooks;
    private BackgroundMessageLoader backgroundMessageLoader;
    private RecallDetector recallDetector;
    private AntiRecallConfigStore antiRecallConfigStore;
    private EditHistoryPolicyStore editHistoryPolicyStore;
    private LocalDialogDeleteStore localDialogDeleteStore;
    private MessageMarkStore messageMarkStore;
    private TelegramDialogDatabasePruner dialogDatabasePruner;
    private final Set<LocalDialogDeleteStore.HiddenDialog> locallyHiddenDialogs =
            Collections.synchronizedSet(new HashSet<>());
    private static final int SCROLL_JUMP_THRESHOLD = 50;
    private static final long CLEANUP_MODE_DURATION_MS = SelfDeleteTracker.DEFAULT_CLEANUP_MODE_MS;

    private final XposedModule module;
    private final EnhancementHookInstaller enhancementHooks;
    private final UiCallbackRegistry uiCallbacks = new UiCallbackRegistry();
    private final Set<ViewGroup> initializedDownloadActionBars =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> persistentDownloadButtonHosts =
            Collections.newSetFromMap(new WeakHashMap<>());
    private XposedConfigProvider configProvider;
    private volatile Context hostApplicationContext;
    private volatile String telegramResourcePackageName = TelegramPackages.PLAY_PACKAGE;
    private volatile BroadcastReceiver cliReceiver;
    private final Set<Thread> cliWorkers = Collections.synchronizedSet(new HashSet<>());
    private volatile WeakReference<Object> activeChatActivity = new WeakReference<>(null);
    private volatile WeakReference<Object> activeDialogsActivity = new WeakReference<>(null);
    private volatile WeakReference<ViewGroup> activeConfigRoot = new WeakReference<>(null);
    private volatile WeakReference<Activity> resumedHostActivity = new WeakReference<>(null);
    private volatile Application hostLifecycleApplication;
    private volatile Application.ActivityLifecycleCallbacks hostLifecycleCallbacks;
    private final Object moduleFallbackLock = new Object();
    private volatile EnhancementConfig moduleFallbackSnapshot = new EnhancementConfig();
    private volatile long moduleFallbackCheckedAt = -MODULE_FALLBACK_SNAPSHOT_MS;
    private final FilterEngine filterEngine = new FilterEngine();
    private final DecisionCache decisionCache = new DecisionCache();
    private final AtomicInteger bindingProbeBudget = new AtomicInteger(12);
    private final AtomicInteger hookEntryBudget = new AtomicInteger(24);
    private final AtomicInteger decisionProbeBudget = new AtomicInteger(12);
    private final AtomicInteger refreshProbeBudget = new AtomicInteger(12);
    private final AtomicInteger readMarkProbeBudget = new AtomicInteger(16);
    private final Map<String, Long> recentDiagnosticKeys = new LinkedHashMap<String, Long>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 128;
        }
    };
    private final Map<String, Long> recentReadMarkKeys = new LinkedHashMap<String, Long>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 512;
        }
    };
    private final Map<String, Long> recentUiTraceKeys = new LinkedHashMap<String, Long>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 256;
        }
    };
    private final UiTraceRateLimiter uiTraceRateLimiter = new UiTraceRateLimiter();
    private final MessageDeleteFlowDiagnostics messageDeleteDiagnostics =
            new MessageDeleteFlowDiagnostics();
    private boolean installed;
    private boolean persistentDiagnosticsUnavailable;
    private volatile long trackedDialogId;
    private volatile int lastTopmostMessageId;
    private volatile boolean readPositionDirty;
    private volatile boolean jumpDetected;
    private volatile boolean settingsListRowLogged;
    private volatile Boolean keepDownloadButtonVisibleEnabled;
    private volatile boolean persistentDownloadHookErrorLogged;
    private volatile boolean retiring;

    TelegramHookInstaller(XposedModule module) {
        this.module = module;
        this.reliableDownloadHooks = new ReliableDownloadHooks(
                module,
                downloadCancellationRegistry,
                () -> usesModuleFallback(ModuleConflictDetector.ConflictKind.DOWNLOAD_ACCELERATION)
        );
        this.enhancementHooks = new EnhancementHookInstaller(module);
    }

    synchronized void install(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        if (installed) {
            return;
        }
        retiring = false;
        String actualPackageName = applicationInfo == null ? null : applicationInfo.packageName;
        telegramResourcePackageName = TelegramPackages.resolveResourcePackage(actualPackageName);
        info("Telegram host package=" + (actualPackageName == null ? "<unknown>" : actualPackageName)
                + " resourcePackage=" + telegramResourcePackageName);
        if (configProvider == null) {
            configProvider = new XposedConfigProvider(
                    MODULE_PACKAGE,
                    () -> module.getRemotePreferences(ModuleConfigStore.PREFS_NAME)
            );
        }
        enhancementHooks.install(classLoader, configProvider);
        initAntiRecall(classLoader);
        logRemoteCapabilities();
        logTelegramVersion(classLoader, applicationInfo);
        hookPushNotificationTriggers(classLoader);
        hookTaggedViewMeasure();
        reliableDownloadHooks.install(classLoader);
        hookChatMessageCell(classLoader);
        hookRecyclerViewBinding(classLoader);
        hookChatActivityAdapter(classLoader);
        hookChatActivityMenu(classLoader);
        hookChatActivityResume(classLoader);
        hookChatActivityPause(classLoader);
        hookScrollToLastMessage(classLoader);
        hookMessageContextMenu(classLoader);
        hookMessageDeleteFlow(classLoader);
        hookSettingsActivityMenu(classLoader);
        hookProfileSettingsMenu(classLoader);
        hookDownloadActivityMenu(classLoader);
        hookDialogDeletionDiagnostics(classLoader);
        hookOnItemClickDiagnostic(classLoader);
        installed = true;
        info("Installed Telegram hooks");
    }

    String targetPackageName() {
        return telegramResourcePackageName;
    }

    void rebindVisibleHostUi(ClassLoader classLoader) {
        Object host = resolveCurrentTelegramFragment(classLoader);
        if (host == null) {
            info("UIRebind: no visible Telegram fragment; lifecycle hooks remain armed");
            return;
        }
        Runnable rebind = () -> {
            if (retiring) {
                return;
            }
            Object parentActivity = Reflect.invokeIfExists(host, "getParentActivity", new Class<?>[0]);
            if (parentActivity instanceof Activity && isTelegramHostActivity((Activity) parentActivity)) {
                resumedHostActivity = new WeakReference<>((Activity) parentActivity);
            }
            if (isFragment(host, "ChatActivity")) {
                activeChatActivity = new WeakReference<>(host);
                injectChatMenu(host);
                refreshChatActivityFiltering(host);
                beginReadPositionTracking(host);
            } else if (isFragment(host, "ProfileActivity")) {
                injectGlobalSettingsMenu(host, true);
            } else if (isFragment(host, "SettingsActivity")) {
                refreshSettingsList(host);
            } else if (isFragment(host, "DialogsActivity")) {
                ensureDownloadUiLifecycle(host);
            }
            info("UIRebind: completed fragment=" + host.getClass().getSimpleName());
        };

        View anchor = resolveHostFragmentView(host);
        if (anchor != null && uiCallbacks.post(anchor, rebind)) {
            info("UIRebind: scheduled fragment=" + host.getClass().getSimpleName());
            return;
        }
        Object parentActivity = Reflect.invokeIfExists(host, "getParentActivity", new Class<?>[0]);
        if (parentActivity instanceof Activity) {
            ((Activity) parentActivity).runOnUiThread(rebind);
            info("UIRebind: scheduled via parent activity fragment="
                    + host.getClass().getSimpleName());
            return;
        }
        info("UIRebind: visible fragment has no UI anchor; waiting for onResume fragment="
                + host.getClass().getSimpleName());
    }

    private Object resolveCurrentTelegramFragment(ClassLoader classLoader) {
        if (classLoader == null) {
            return null;
        }
        try {
            Class<?> launchActivity = classLoader.loadClass("org.telegram.ui.LaunchActivity");
            Object fragment = Reflect.invokeStatic(
                    launchActivity, "getSafeLastFragment", new Class<?>[0]);
            if (fragment == null) {
                fragment = Reflect.invokeStatic(
                        launchActivity, "getLastFragmentIncludeMainTabs", new Class<?>[0]);
            }
            if (fragment == null) {
                fragment = Reflect.invokeStatic(
                        launchActivity, "getLastFragment", new Class<?>[0]);
            }
            return fragment;
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private boolean isFragment(Object fragment, String simpleName) {
        return fragment != null && (simpleName.equals(fragment.getClass().getSimpleName())
                || fragment.getClass().getName().endsWith("." + simpleName));
    }

    private void refreshSettingsList(Object settingsActivity) {
        Object listView = Reflect.field(settingsActivity, "listView");
        Object adapter = Reflect.field(listView, "adapter");
        if (adapter == null) {
            info("UIRebind: SettingsActivity adapter unavailable; waiting for next fillItems");
            return;
        }
        Reflect.invokeIfExists(adapter, "update", new Class<?>[]{boolean.class}, true);
        info("UIRebind: SettingsActivity rows refresh requested");
    }

    synchronized boolean prepareForHotReload() {
        if (retiring) {
            return true;
        }
        retiring = true;
        boolean clean = true;
        enhancementHooks.prepareForHotReload();
        clean &= reliableDownloadHooks.prepareForHotReload();

        Context context = hostApplicationContext;
        BroadcastReceiver receiver = cliReceiver;
        cliReceiver = null;
        if (context != null && receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // The host may already have unregistered its receiver while shutting down.
            } catch (RuntimeException exception) {
                clean = false;
                error("Hot reload: CLI receiver unregister failed", exception);
            }
        }
        clean &= awaitCliWorkers(3_000L);

        unregisterHostActivityTracking();

        ViewGroup configRoot = activeConfigRoot.get();
        activeConfigRoot = new WeakReference<>(null);
        clean &= HostConfigPanel.closeForHotReload(configRoot, 4_000L);
        clean &= uiCallbacks.prepareForHotReload(3_000L);
        synchronized (initializedDownloadActionBars) {
            initializedDownloadActionBars.clear();
        }
        synchronized (persistentDownloadButtonHosts) {
            persistentDownloadButtonHosts.clear();
        }

        TelegramDialogDatabasePruner pruner = dialogDatabasePruner;
        if (pruner != null) {
            clean &= pruner.prepareForHotReload(4_000L);
        }
        RecallDetector detector = recallDetector;
        if (detector != null) {
            clean &= detector.prepareForHotReload();
        }
        BackgroundMessageLoader loader = backgroundMessageLoader;
        if (loader != null) {
            clean &= loader.prepareForHotReload();
        }
        MediaPrefetcher prefetcher = mediaPrefetcher;
        if (prefetcher != null) {
            clean &= prefetcher.prepareForHotReload();
        }
        EditHistoryPolicyStore policyStore = editHistoryPolicyStore;
        if (policyStore != null) {
            try {
                policyStore.close();
            } catch (RuntimeException exception) {
                clean = false;
                error("Hot reload: edit-history store close failed", exception);
            }
        }
        MessageCache cache = messageCache;
        if (cache != null) {
            clean &= cache.prepareForHotReload();
        }

        downloadPageFragmentView = null;
        backgroundMessageLoader = null;
        recallDetector = null;
        antiRecallConfigStore = null;
        editHistoryPolicyStore = null;
        localDialogDeleteStore = null;
        messageMarkStore = null;
        dialogDatabasePruner = null;
        mediaPrefetcher = null;
        mediaCache = null;
        messageCache = null;
        configProvider = null;
        hostApplicationContext = null;
        savedClassLoader = null;
        activeChatActivity = new WeakReference<>(null);
        activeDialogsActivity = new WeakReference<>(null);
        resumedHostActivity = new WeakReference<>(null);
        keepDownloadButtonVisibleEnabled = null;
        persistentDownloadHookErrorLogged = false;
        locallyHiddenDialogs.clear();
        decisionCache.clear();
        installed = false;
        info("Hot reload: old generation resources retired clean=" + clean);
        return clean;
    }

    private volatile ClassLoader savedClassLoader;

    private void initAntiRecall(ClassLoader classLoader) {
        this.savedClassLoader = classLoader;
        try {
            Context context = resolveHostApplication();
            if (context == null) {
                info("Anti-recall: host application context not available, deferring");
                return;
            }
            doInitAntiRecall(context, classLoader);
        } catch (Throwable throwable) {
            error("Failed to initialize anti-recall components", throwable);
        }
    }

    private void initAntiRecallDeferred() {
        if (backgroundMessageLoader != null) {
            return;
        }
        try {
            Context context = resolveHostApplication();
            if (context == null) {
                return;
            }
            doInitAntiRecall(context, savedClassLoader);
        } catch (Throwable throwable) {
            error("Failed to deferred-initialize anti-recall", throwable);
        }
    }

    private void initAntiRecallFromChat(Object chatActivity) {
        if (backgroundMessageLoader != null) {
            return;
        }
        try {
            Context context = resolveContextFromActivity(chatActivity);
            initAntiRecallFromContext(context, "chat");
        } catch (Throwable throwable) {
            error("Failed to initialize anti-recall from chat", throwable);
        }
    }

    private synchronized void initAntiRecallFromContext(Context context, String source) {
        if (backgroundMessageLoader != null) {
            return;
        }
        if (context == null) {
            info("Anti-recall: " + source + " context is null");
            return;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        info("Anti-recall: initializing from " + source + " context");
        doInitAntiRecall(appContext, savedClassLoader);
    }

    private void doInitAntiRecall(Context context, ClassLoader classLoader) {
        hostApplicationContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        ModuleLogger.init(context);
        registerHostActivityTracking(hostApplicationContext);
        antiRecallConfigStore = new AntiRecallConfigStore(context);
        messageMarkStore = new MessageMarkStore(context);
        localDialogDeleteStore = new LocalDialogDeleteStore(context);
        dialogDatabasePruner = new TelegramDialogDatabasePruner(context, new TelegramDialogDatabasePruner.Logger() {
            @Override
            public void info(String message) {
                TelegramHookInstaller.this.info(message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                TelegramHookInstaller.this.error(message, throwable);
            }
        });
        locallyHiddenDialogs.clear();
        Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs =
                localDialogDeleteStore.hiddenDialogs(resolveSelectedTelegramAccount(classLoader));
        locallyHiddenDialogs.addAll(hiddenDialogs);
        pruneKnownHiddenDialogs(hiddenDialogs);
        MessageDatabaseHelper databaseHelper = new MessageDatabaseHelper(context);
        databaseHelper.getWritableDatabase();
        editHistoryPolicyStore = new EditHistoryPolicyStore(context, antiRecallConfigStore);
        messageCache = new MessageCache(new SerializedMessageStore(databaseHelper));
        mediaCache = new MediaCache(context);
        mediaPrefetcher = new MediaPrefetcher(messageCache, mediaCache, downloadCancellationRegistry);
        backgroundMessageLoader = new BackgroundMessageLoader(
                messageCache,
                antiRecallConfigStore,
                () -> usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL)
        );
        recallDetector = new RecallDetector(messageCache, backgroundMessageLoader,
                mediaPrefetcher,
                editHistoryPolicyStore,
                () -> usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL),
                () -> usesModuleFallback(ModuleConflictDetector.ConflictKind.EDIT_HISTORY));
        recallDetector.setDeleteFlowDiagnostics(messageDeleteDiagnostics);
        backgroundMessageLoader.setLoadedMessagesConsumer(recallDetector::cacheBackgroundMessages);
        if (classLoader != null) {
            mediaPrefetcher.setTelegramClassLoader(classLoader);
            recallDetector.install(classLoader, module);
            backgroundMessageLoader.setTelegramClassLoader(classLoader);
        }
        registerCliBridge(hostApplicationContext);
        info("Anti-recall components initialized");
    }

    private void hookPushNotificationTriggers(ClassLoader classLoader) {
        hookPushListenerController(classLoader);
        hookConnectionsManagerPushFallback(classLoader);
    }

    private void hookPushListenerController(ClassLoader classLoader) {
        try {
            Class<?> pushControllerClass = classLoader.loadClass("org.telegram.messenger.PushListenerController");
            boolean hooked = false;
            for (Method method : pushControllerClass.getDeclaredMethods()) {
                if (!"processRemoteMessage".equals(method.getName())) {
                    continue;
                }
                hook(method, chain -> {
                    triggerImmediateBackgroundLoad(
                            "PushListenerController.processRemoteMessage" + describeHookArgs(chain.getArgs())
                    );
                    return chain.proceed();
                });
                hooked = true;
            }
            if (hooked) {
                info("Anti-recall: hooked PushListenerController.processRemoteMessage for push-triggered loading");
            } else {
                info("Anti-recall: PushListenerController.processRemoteMessage not found");
            }
        } catch (ClassNotFoundException throwable) {
            info("Anti-recall: PushListenerController unavailable");
        } catch (Throwable throwable) {
            error("Anti-recall: failed to hook PushListenerController", throwable);
        }
    }

    private void hookConnectionsManagerPushFallback(ClassLoader classLoader) {
        try {
            Class<?> connectionsManagerClass = classLoader.loadClass("org.telegram.tgnet.ConnectionsManager");
            boolean hooked = false;
            for (Method method : connectionsManagerClass.getDeclaredMethods()) {
                if (!"onInternalPushReceived".equals(method.getName())) {
                    continue;
                }
                hook(method, chain -> {
                    triggerImmediateBackgroundLoad(
                            "ConnectionsManager.onInternalPushReceived" + describeHookArgs(chain.getArgs())
                    );
                    return chain.proceed();
                });
                hooked = true;
            }
            if (hooked) {
                info("Anti-recall: hooked ConnectionsManager.onInternalPushReceived fallback");
            } else {
                info("Anti-recall: ConnectionsManager.onInternalPushReceived not found");
            }
        } catch (ClassNotFoundException throwable) {
            info("Anti-recall: ConnectionsManager unavailable for push fallback");
        } catch (Throwable throwable) {
            error("Anti-recall: failed to hook ConnectionsManager push fallback", throwable);
        }
    }

    private void triggerImmediateBackgroundLoad(String reason) {
        try {
            initAntiRecallDeferred();
            BackgroundMessageLoader loader = backgroundMessageLoader;
            if (loader == null) {
                info("Anti-recall: push-triggered load skipped, loader unavailable reason=" + reason);
                return;
            }
            loader.triggerImmediateLoad(reason);
        } catch (Throwable throwable) {
            error("Anti-recall: push-triggered load failed reason=" + reason, throwable);
        }
    }

    private synchronized void registerCliBridge(Context context) {
        if (context == null || cliReceiver != null) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                String command = intent == null ? "" : stringExtra(intent, "command");
                if (isAsyncCliCommand(command)) {
                    PendingResult pendingResult = goAsync();
                    startCliWorker(receiverContext, intent, command, pendingResult);
                    return;
                }
                try {
                    JSONObject response = handleCliCommand(receiverContext, intent, command);
                    setResultCode(Activity.RESULT_OK);
                    setResultData(cliResponseData(response));
                    info("CLI command completed command=" + command);
                } catch (Throwable throwable) {
                    JSONObject response = new JSONObject();
                    try {
                        response.put("ok", false);
                        response.put("command", command);
                        response.put("error", throwable.getMessage() == null
                                ? throwable.getClass().getSimpleName()
                                : throwable.getMessage());
                    } catch (Throwable ignored) {
                        // JSONObject with three primitive fields cannot fail in normal operation.
                    }
                    setResultCode(Activity.RESULT_CANCELED);
                    setResultData(cliResponseData(response));
                    info("CLI command rejected command=" + command + " reason="
                            + (throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName()
                            : throwable.getMessage()));
                }
            }
        };
        context.registerReceiver(
                receiver,
                new IntentFilter(CLI_ACTION),
                Manifest.permission.DUMP,
                null,
                Context.RECEIVER_EXPORTED
        );
        cliReceiver = receiver;
        info("CLI bridge registered action=" + CLI_ACTION + " permission=android.permission.DUMP");
    }

    private void startCliWorker(Context context, Intent intent, String command,
                                BroadcastReceiver.PendingResult pendingResult) {
        Thread worker = new Thread(() -> {
            try {
                completeAsyncCliCommand(context, intent, command, pendingResult);
            } finally {
                synchronized (cliWorkers) {
                    cliWorkers.remove(Thread.currentThread());
                    cliWorkers.notifyAll();
                }
            }
        }, "GramSieve-cli-" + command.replace('.', '-'));
        worker.setDaemon(true);
        synchronized (cliWorkers) {
            if (retiring) {
                pendingResult.setResultCode(Activity.RESULT_CANCELED);
                pendingResult.setResultData("{\"ok\":false,\"error\":\"hot reload in progress\"}");
                pendingResult.finish();
                return;
            }
            cliWorkers.add(worker);
            worker.start();
        }
    }

    private boolean awaitCliWorkers(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (cliWorkers) {
            while (!cliWorkers.isEmpty()) {
                for (Thread worker : cliWorkers) {
                    worker.interrupt();
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    cliWorkers.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private boolean isAsyncCliCommand(String command) {
        return "modules.scan".equalsIgnoreCase(command)
                || "log.tail".equalsIgnoreCase(command)
                || "log.range".equalsIgnoreCase(command)
                || "log.info".equalsIgnoreCase(command)
                || "log.export".equalsIgnoreCase(command)
                || "ui.log-console.select".equalsIgnoreCase(command);
    }

    private void completeAsyncCliCommand(Context context, Intent intent, String command,
                                         BroadcastReceiver.PendingResult pendingResult) {
        try {
            JSONObject response = handleCliCommand(context, intent, command);
            pendingResult.setResultCode(Activity.RESULT_OK);
            pendingResult.setResultData(cliResponseData(response));
            info("CLI command completed command=" + command);
        } catch (Throwable throwable) {
            JSONObject response = new JSONObject();
            try {
                response.put("ok", false);
                response.put("command", command);
                response.put("error", throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage());
            } catch (Throwable ignored) {
                // JSONObject with three primitive fields cannot fail in normal operation.
            }
            pendingResult.setResultCode(Activity.RESULT_CANCELED);
            pendingResult.setResultData(cliResponseData(response));
            info("CLI command rejected command=" + command + " reason="
                    + (throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage()));
        } finally {
            pendingResult.finish();
        }
    }

    private JSONObject handleCliCommand(Context context, Intent intent, String rawCommand) throws Exception {
        String command = rawCommand == null ? "" : rawCommand.trim().toLowerCase(Locale.ROOT);
        if (command.isBlank()) {
            throw new IllegalArgumentException("Missing command");
        }
        JSONObject response = cliSuccess(command);
        switch (command) {
            case "help":
                return cliHelp(response);
            case "ping":
                response.put("protocolVersion", CLI_PROTOCOL_VERSION);
                response.put("package", context.getPackageName());
                response.put("trackedDialogId", trackedDialogId);
                return response;
            case "state":
                return cliState(response, context);
            case "modules.scan":
                return cliModuleScan(response, context);
            case "log.tail":
                return cliLogTail(response, context, intent);
            case "log.range":
                return cliLogRange(response, context, intent);
            case "log.info":
                return cliLogInfo(response, context);
            case "log.export":
                return cliLogExport(response, context, intent);
            case "config.get":
                response.put("config", new JSONObject(
                        ModuleConfigStore.toJson(currentCliConfig(context))));
                return response;
            case "config.set":
                return cliSetConfig(response, context, intent);
            case "feature.list":
                return cliFeatureList(response, context);
            case "feature.get":
                return cliFeatureGet(response, context, requireExtra(intent, "name"));
            case "feature.set":
                return cliFeatureSet(response, context, intent);
            case "fallback.list":
                return cliFallbackList(response, context);
            case "fallback.get":
                return cliFallbackGet(response, context, requireExtra(intent, "name"));
            case "fallback.set":
                return cliFallbackSet(response, context, intent);
            case "anti-recall.get":
                return cliAntiRecall(response, intent, false);
            case "anti-recall.set":
                return cliAntiRecall(response, intent, true);
            case "anti-recall.list":
                return cliAntiRecallList(response);
            case "edit-history.get":
                return cliEditHistory(response, intent, false);
            case "edit-history.set":
                return cliEditHistory(response, intent, true);
            case "load.trigger":
                backgroundMessageLoader.triggerImmediateLoad("cli");
                response.put("accepted", true);
                response.put("enabledChats", backgroundMessageLoader.enabledChatIdsSnapshot().size());
                response.put("externalFallback", usesModuleFallback(
                        ModuleConflictDetector.ConflictKind.ANTI_RECALL));
                return response;
            case "mark.list":
                return cliMarks(response, intent, "list");
            case "mark.set":
                return cliMarks(response, intent, "set");
            case "mark.clear":
                return cliMarks(response, intent, "clear");
            case "read-position.get":
                return cliReadPosition(response, context, intent, "get");
            case "read-position.set":
                return cliReadPosition(response, context, intent, "set");
            case "read-position.clear":
                return cliReadPosition(response, context, intent, "clear");
            case "message.get":
                return cliMessages(response, intent, "get");
            case "message.recalled":
                return cliMessages(response, intent, "recalled");
            case "message.edited":
                return cliMessages(response, intent, "edited");
            case "message.history":
                return cliMessages(response, intent, "history");
            case "cleanup.get":
                return cliCleanup(response, intent, false);
            case "cleanup.set":
                return cliCleanup(response, intent, true);
            case "ui.state":
                return cliUiState(response);
            case "ui.download-button.state":
                return cliUiDownloadButtonState(response);
            case "ui.download-select-all.state":
                return cliUiDownloadSelectAll(response, false);
            case "ui.download-select-all.click":
                return cliUiDownloadSelectAll(response, true);
            case "ui.message-menu.open":
                return cliUiMessageMenuOpen(response, intent);
            case "ui.message-menu.close":
                return cliUiMessageMenuClose(response, intent);
            case "ui.message-delete.state":
                return cliUiMessageDeleteState(response);
            case "ui.menu.state":
                return cliUiMenuState(response);
            case "ui.menu.open":
                return cliUiMenuOpen(response, intent);
            case "ui.first-message":
                return cliUiFirstMessage(response, context, intent);
            case "ui.config.open":
                return cliUiConfigOpen(response);
            case "ui.config.close":
                return cliUiConfigClose(response);
            case "ui.config.sections":
                return cliUiConfigSections(response, intent, false);
            case "ui.config.section.set":
                return cliUiConfigSections(response, intent, true);
            case "ui.log-console.state":
                return cliUiLogConsole(response, intent, false);
            case "ui.log-console.select":
                return cliUiLogConsole(response, intent, true);
            case "ui.jump-mark":
                return cliUiJump(response, context, intent);
            case "ui.scroll":
                return cliUiScroll(response, context, intent);
            default:
                throw new IllegalArgumentException("Unsupported command: " + command);
        }
    }

    private JSONObject cliHelp(JSONObject response) throws Exception {
        response.put("protocolVersion", CLI_PROTOCOL_VERSION);
        response.put(
                "adbTemplate",
                "adb -s <device> shell am broadcast -a " + CLI_ACTION
                        + " -p " + telegramResourcePackageName + " --receiver-registered-only"
                        + " --es command <command> [--es name <name>] [--es value <value>]"
                         + " [--es dialog_id <id>] [--es account_id <id>]"
                        + " [--es message_id <id>] [--es limit <n>] [--es preview <text>]"
                        + " [--es from <time>] [--es to <time>] [--el from_ms <epoch>] [--el to_ms <epoch>]"
        );
        response.put("commands", new JSONArray(Arrays.asList(
                "help", "ping", "state", "modules.scan", "log.tail", "log.range", "log.info", "log.export",
                "config.get", "config.set",
                "feature.list", "feature.get", "feature.set",
                "fallback.list", "fallback.get", "fallback.set",
                "anti-recall.list", "anti-recall.get", "anti-recall.set",
                "edit-history.get", "edit-history.set", "load.trigger",
                "mark.list", "mark.set", "mark.clear",
                "read-position.get", "read-position.set", "read-position.clear",
                "message.get", "message.recalled", "message.edited", "message.history",
                "cleanup.get", "cleanup.set", "ui.state", "ui.download-button.state",
                "ui.download-select-all.state", "ui.download-select-all.click",
                "ui.message-menu.open", "ui.message-menu.close", "ui.message-delete.state",
                "ui.menu.state", "ui.menu.open",
                "ui.config.open", "ui.config.close", "ui.config.sections",
                "ui.config.section.set",
                "ui.log-console.state", "ui.log-console.select",
                "ui.jump-mark", "ui.first-message", "ui.scroll"
        )));
        response.put("configSetExtras", new JSONArray(Arrays.asList("config_json", "config_b64")));
        response.put("maxResponseChars", MAX_CLI_RESPONSE_CHARS);
        response.put("logTailBytes", LogFileSupport.MAX_TAIL_BYTES);
        response.put("logTailLines", LogFileSupport.MAX_TAIL_LINES);
        response.put("logRangeBytes", LogFileSupport.MAX_RANGE_BYTES);
        response.put("logTimeFormat", "yyyy-MM-dd HH:mm:ss or epoch seconds/milliseconds");
        response.put("result", "JSON is returned directly in am broadcast result data");
        return response;
    }

    private JSONObject cliLogTail(JSONObject response, Context context, Intent intent) throws Exception {
        int lines = parseLogLineLimit(stringExtra(intent, "limit"));
        LogFileSupport.TailResult tail = LogFileSupport.readTail(
                context,
                lines,
                LogFileSupport.MAX_TAIL_BYTES
        );
        response.put("available", tail.available);
        response.put("source", tail.sourcePath);
        response.put("totalBytes", tail.totalBytes);
        response.put("returnedBytes", tail.returnedBytes);
        response.put("lineCount", tail.lineCount);
        response.put("truncated", tail.truncated);
        response.put("text", tail.text);
        if (!tail.error.isBlank()) {
            response.put("error", tail.error);
        }
        return response;
    }

    private JSONObject cliLogRange(JSONObject response, Context context, Intent intent) throws Exception {
        LogTimeRange range = logRangeFromIntent(intent);
        LogFileSupport.RangeResult result = LogFileSupport.readRange(
                context,
                range,
                LogFileSupport.DEFAULT_RANGE_BYTES
        );
        response.put("available", result.available);
        response.put("source", result.sourcePath);
        response.put("totalBytes", result.totalBytes);
        response.put("returnedBytes", result.returnedBytes);
        response.put("lineCount", result.lineCount);
        response.put("matchedEntries", result.matchedEntries);
        response.put("truncated", result.truncated);
        response.put("fromMs", range.fromInclusiveMs == Long.MIN_VALUE
                ? JSONObject.NULL : range.fromInclusiveMs);
        response.put("toMs", range.toInclusiveMs == Long.MAX_VALUE
                ? JSONObject.NULL : range.toInclusiveMs);
        response.put("from", range.fromDisplay());
        response.put("to", range.toDisplay());
        response.put("text", result.text);
        if (!result.error.isBlank()) {
            response.put("error", result.error);
        }
        return response;
    }

    private JSONObject cliLogInfo(JSONObject response, Context context) throws Exception {
        java.io.File preferred = LogFileSupport.preferredLogFile(context);
        java.io.File external = LogFileSupport.externalLogFile(context);
        java.io.File internal = LogFileSupport.internalLogFile(context);
        response.put("source", preferred == null ? "" : preferred.getAbsolutePath());
        response.put("externalPath", external == null ? "" : external.getAbsolutePath());
        response.put("internalPath", internal == null ? "" : internal.getAbsolutePath());
        response.put("available", preferred != null && preferred.isFile() && preferred.canRead());
        response.put("bytes", preferred != null && preferred.isFile() ? preferred.length() : 0L);
        response.put("tailBytesLimit", LogFileSupport.MAX_TAIL_BYTES);
        response.put("tailLinesLimit", LogFileSupport.MAX_TAIL_LINES);
        return response;
    }

    private JSONObject cliLogExport(JSONObject response, Context context, Intent intent) throws Exception {
        LogTimeRange range = logRangeFromIntent(intent);
        LogFileSupport.ExportResult result = LogFileSupport.exportToDownloads(context, range);
        response.put("exported", result.exported);
        response.put("displayName", result.displayName);
        response.put("uri", result.uri == null ? "" : result.uri.toString());
        response.put("source", result.sourcePath);
        response.put("bytes", result.bytes);
        response.put("ranged", result.ranged);
        response.put("matchedEntries", result.matchedEntries);
        response.put("fromMs", range.fromInclusiveMs == Long.MIN_VALUE
                ? JSONObject.NULL : range.fromInclusiveMs);
        response.put("toMs", range.toInclusiveMs == Long.MAX_VALUE
                ? JSONObject.NULL : range.toInclusiveMs);
        if (!result.error.isBlank()) {
            response.put("error", result.error);
        }
        return response;
    }

    private LogTimeRange logRangeFromIntent(Intent intent) {
        String from = stringExtra(intent, "from");
        String to = stringExtra(intent, "to");
        if ((from == null || from.isBlank()) && intent != null && intent.hasExtra("from_ms")) {
            from = String.valueOf(intent.getLongExtra("from_ms", 0L));
        }
        if ((to == null || to.isBlank()) && intent != null && intent.hasExtra("to_ms")) {
            to = String.valueOf(intent.getLongExtra("to_ms", 0L));
        }
        return LogTimeRange.parse(from, to);
    }

    static int parseLogLineLimit(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return LogFileSupport.DEFAULT_TAIL_LINES;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
            if (parsed > LogFileSupport.MAX_TAIL_LINES) {
                throw new IllegalArgumentException("limit exceeds " + LogFileSupport.MAX_TAIL_LINES);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit is not an integer: " + raw);
        }
    }

    private JSONObject cliState(JSONObject response, Context context) throws Exception {
        FilterConfig config = currentCliConfig(context);
        JSONArray enabledFeatures = new JSONArray();
        for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
            if (config.enhancements.isEnabled(feature)) {
                enabledFeatures.put(feature.name());
            }
        }
        JSONArray enabledFallbacks = new JSONArray();
        for (ModuleConflictDetector.KnownModule knownModule
                : ModuleConflictDetector.KnownModule.values()) {
            if (config.enhancements.isModuleFallbackEnabled(knownModule)) {
                enabledFallbacks.put(knownModule.name());
            }
        }
        response.put("protocolVersion", CLI_PROTOCOL_VERSION);
        response.put("updatedAtEpochMs", config.updatedAtEpochMs);
        response.put("enabledFeatures", enabledFeatures);
        response.put("enabledFallbacks", enabledFallbacks);
        response.put("antiRecallChats", backgroundMessageLoader.enabledChatIdsSnapshot().size());
        response.put("antiRecallDialogIds", longArray(
                backgroundMessageLoader.enabledChatIdsSnapshot()));
        response.put("trackedDialogId", trackedDialogId);
        response.put("activeChat", activeChatActivity.get() != null);
        response.put("selectedAccount", resolveSelectedTelegramAccount(savedClassLoader));
        return response;
    }

    private JSONObject cliModuleScan(JSONObject response, Context context) throws Exception {
        RuntimeModuleProbe.Result result = RuntimeModuleProbe.scan(context, module);
        response.put("source", result.source);
        JSONArray modules = new JSONArray();
        for (ModuleConflictDetector.KnownModule knownModule : result.modules) {
            JSONObject item = fallbackJson(currentCliConfig(context).enhancements, knownModule);
            modules.put(item);
        }
        response.put("modules", modules);
        return response;
    }

    private JSONObject cliSetConfig(JSONObject response, Context context, Intent intent) throws Exception {
        String json = stringExtra(intent, "config_json");
        if (json.isBlank()) {
            String encoded = requireExtra(intent, "config_b64");
            json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        }
        new JSONObject(json);
        FilterConfig updated = ModuleConfigStore.fromJson(json).deepCopy().sanitize();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        FilterConfig saved = saveUpdatedConfig(context, updated);
        decisionCache.clear();
        response.put("updatedAtEpochMs", saved.updatedAtEpochMs);
        response.put("config", new JSONObject(ModuleConfigStore.toJson(saved)));
        return response;
    }

    private JSONObject cliFeatureList(JSONObject response, Context context) throws Exception {
        FilterConfig config = currentCliConfig(context);
        JSONArray features = new JSONArray();
        for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
            features.put(featureJson(config.enhancements, feature));
        }
        response.put("features", features);
        return response;
    }

    private JSONObject cliFeatureGet(JSONObject response, Context context, String name) throws Exception {
        FilterConfig config = currentCliConfig(context);
        response.put("feature", featureJson(config.enhancements, parseFeature(name)));
        return response;
    }

    private JSONObject cliFeatureSet(JSONObject response, Context context, Intent intent) throws Exception {
        EnhancementConfig.Feature feature = parseFeature(requireExtra(intent, "name"));
        if (!feature.isAvailableInCurrentBuild()) {
            throw new IllegalArgumentException("Feature is not available in this build: " + feature.name());
        }
        boolean value = parseBoolean(requireExtra(intent, "value"));
        FilterConfig updated = currentCliConfig(context).deepCopy();
        updated.enhancements.setEnabled(feature, value);
        updated.updatedAtEpochMs = System.currentTimeMillis();
        FilterConfig saved = saveUpdatedConfig(context, updated);
        decisionCache.clear();
        response.put("feature", featureJson(saved.enhancements, feature));
        response.put("updatedAtEpochMs", saved.updatedAtEpochMs);
        return response;
    }

    private JSONObject featureJson(EnhancementConfig config, EnhancementConfig.Feature feature)
            throws Exception {
        JSONObject result = new JSONObject();
        result.put("name", feature.name());
        result.put("key", feature.key);
        result.put("category", feature.category.name());
        result.put("available", feature.isAvailableInCurrentBuild());
        result.put("enabled", config.isEnabled(feature));
        result.put("effective", config.isEnabledForGramSieve(feature));
        return result;
    }

    private JSONObject cliFallbackList(JSONObject response, Context context) throws Exception {
        FilterConfig config = currentCliConfig(context);
        JSONArray fallbacks = new JSONArray();
        for (ModuleConflictDetector.KnownModule knownModule
                : ModuleConflictDetector.KnownModule.values()) {
            fallbacks.put(fallbackJson(config.enhancements, knownModule));
        }
        response.put("fallbacks", fallbacks);
        return response;
    }

    private JSONObject cliFallbackGet(JSONObject response, Context context, String name) throws Exception {
        FilterConfig config = currentCliConfig(context);
        response.put("fallback", fallbackJson(config.enhancements, parseKnownModule(name)));
        return response;
    }

    private JSONObject cliFallbackSet(JSONObject response, Context context, Intent intent) throws Exception {
        ModuleConflictDetector.KnownModule knownModule = parseKnownModule(requireExtra(intent, "name"));
        boolean value = parseBoolean(requireExtra(intent, "value"));
        FilterConfig updated = currentCliConfig(context).deepCopy();
        updated.enhancements.setModuleFallbackEnabled(knownModule, value);
        updated.updatedAtEpochMs = System.currentTimeMillis();
        FilterConfig saved = saveUpdatedConfig(context, updated);
        decisionCache.clear();
        response.put("fallback", fallbackJson(saved.enhancements, knownModule));
        response.put("updatedAtEpochMs", saved.updatedAtEpochMs);
        return response;
    }

    private JSONObject fallbackJson(EnhancementConfig config,
                                    ModuleConflictDetector.KnownModule knownModule) throws Exception {
        JSONObject result = new JSONObject();
        result.put("name", knownModule.name());
        result.put("displayName", knownModule.displayName);
        result.put("enabled", config.isModuleFallbackEnabled(knownModule));
        JSONArray capabilities = new JSONArray();
        for (ModuleConflictDetector.ConflictKind kind : knownModule.conflictKinds()) {
            capabilities.put(kind.name());
        }
        result.put("capabilities", capabilities);
        return result;
    }

    private JSONObject cliAntiRecall(JSONObject response, Intent intent, boolean mutate) throws Exception {
        long dialogId = requireDialogId(intent);
        if (mutate) {
            boolean value = parseBoolean(requireExtra(intent, "value"));
            if (value) {
                backgroundMessageLoader.enableChat(dialogId);
            } else {
                backgroundMessageLoader.disableChat(dialogId);
            }
        }
        response.put("dialogId", dialogId);
        response.put("enabled", backgroundMessageLoader.isChatEnabled(dialogId));
        response.put("effective", backgroundMessageLoader.isChatEnabled(dialogId)
                && !usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL));
        return response;
    }

    private JSONObject cliAntiRecallList(JSONObject response) throws Exception {
        response.put("dialogIds", longArray(backgroundMessageLoader.enabledChatIdsSnapshot()));
        response.put("externalFallback", usesModuleFallback(
                ModuleConflictDetector.ConflictKind.ANTI_RECALL));
        return response;
    }

    private JSONArray longArray(Set<Long> values) {
        List<Long> ordered = new ArrayList<>(values == null ? Collections.emptySet() : values);
        Collections.sort(ordered);
        JSONArray result = new JSONArray();
        for (Long value : ordered) {
            if (value != null) {
                result.put(value);
            }
        }
        return result;
    }

    private JSONObject cliEditHistory(JSONObject response, Intent intent, boolean mutate) throws Exception {
        int accountId = intExtra(intent, "account_id", resolveSelectedTelegramAccount(savedClassLoader));
        long dialogId = longExtra(intent, "dialog_id", 0L);
        if (mutate) {
            String setting = requireExtra(intent, "name").trim().toLowerCase(Locale.ROOT);
            String value = requireExtra(intent, "value").trim();
            switch (setting) {
                case "enabled":
                    editHistoryPolicyStore.setEnabled(accountId, parseBoolean(value));
                    break;
                case "mode":
                    editHistoryPolicyStore.setMode(accountId,
                            EditHistoryPolicyStore.Mode.valueOf(value.toUpperCase(Locale.ROOT)));
                    break;
                case "dialog":
                    if (dialogId == 0L) {
                        throw new IllegalArgumentException("dialog_id is required for dialog policy");
                    }
                    if ("follow".equalsIgnoreCase(value) || "default".equalsIgnoreCase(value)) {
                        editHistoryPolicyStore.clearDialogRule(accountId, dialogId);
                    } else if ("record".equalsIgnoreCase(value) || parseBoolean(value)) {
                        editHistoryPolicyStore.setDialogRecorded(accountId, dialogId, true);
                    } else {
                        editHistoryPolicyStore.setDialogRecorded(accountId, dialogId, false);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported edit-history setting: " + setting);
            }
        }
        response.put("accountId", accountId);
        response.put("enabled", editHistoryPolicyStore.isEnabled(accountId));
        response.put("mode", editHistoryPolicyStore.getMode(accountId).name());
        response.put("externalFallback", usesModuleFallback(
                ModuleConflictDetector.ConflictKind.EDIT_HISTORY));
        if (dialogId != 0L) {
            Boolean rule = editHistoryPolicyStore.getDialogRule(accountId, dialogId);
            response.put("dialogId", dialogId);
            response.put("dialogRule", rule == null ? JSONObject.NULL : rule);
            response.put("effective", editHistoryPolicyStore.shouldRecord(accountId, dialogId)
                    && !usesModuleFallback(ModuleConflictDetector.ConflictKind.EDIT_HISTORY));
        }
        return response;
    }

    private JSONObject cliMarks(JSONObject response, Intent intent, String action) throws Exception {
        int accountId = intExtra(intent, "account_id", resolveSelectedTelegramAccount(savedClassLoader));
        long dialogId = requireDialogId(intent);
        if ("set".equals(action)) {
            int messageId = intExtra(intent, "message_id", 0);
            if (messageId <= 0) {
                throw new IllegalArgumentException("message_id must be positive");
            }
            messageMarkStore.add(accountId, dialogId, messageId, stringExtra(intent, "preview"));
        } else if ("clear".equals(action)) {
            messageMarkStore.clear(accountId, dialogId);
        }
        List<MessageMarkStore.Mark> marks = messageMarkStore.list(accountId, dialogId);
        JSONArray items = new JSONArray();
        for (MessageMarkStore.Mark mark : marks) {
            JSONObject item = new JSONObject();
            item.put("messageId", mark.messageId);
            item.put("preview", mark.preview);
            item.put("savedAtEpochMs", mark.savedAtEpochMs);
            items.put(item);
        }
        response.put("accountId", accountId);
        response.put("dialogId", dialogId);
        response.put("marks", items);
        return response;
    }

    private JSONObject cliReadPosition(JSONObject response, Context context, Intent intent,
                                       String action) throws Exception {
        long dialogId = requireDialogId(intent);
        if ("set".equals(action)) {
            int messageId = intExtra(intent, "message_id", 0);
            if (messageId <= 0) {
                throw new IllegalArgumentException("message_id must be positive");
            }
            ChatReadPositionStore.save(context, dialogId, messageId);
        } else if ("clear".equals(action)) {
            ChatReadPositionStore.remove(context, dialogId);
        }
        ChatReadPositionStore.ReadPosition position = ChatReadPositionStore.load(context, dialogId);
        response.put("dialogId", dialogId);
        if (position == null) {
            response.put("position", JSONObject.NULL);
        } else {
            JSONObject item = new JSONObject();
            item.put("messageId", position.messageId);
            item.put("timestampEpochMs", position.timestampEpochMs);
            response.put("position", item);
        }
        return response;
    }

    private JSONObject cliMessages(JSONObject response, Intent intent, String action) throws Exception {
        int accountId = intExtra(intent, "account_id", resolveSelectedTelegramAccount(savedClassLoader));
        long dialogId = requireDialogId(intent);
        int messageId = intExtra(intent, "message_id", 0);
        int limit = Math.max(1, Math.min(200, intExtra(intent, "limit", 50)));
        List<MessageCache.CachedMessage> messages;
        switch (action) {
            case "get":
                if (messageId <= 0) {
                    throw new IllegalArgumentException("message_id must be positive");
                }
                MessageCache.CachedMessage message = messageCache.get(accountId, dialogId, messageId);
                messages = message == null
                        ? Collections.emptyList()
                        : Collections.singletonList(message);
                break;
            case "recalled":
                messages = messageCache.getRecalledMessages(accountId, dialogId);
                break;
            case "edited":
                messages = messageCache.getEditedMessages(accountId, dialogId);
                break;
            case "history":
                if (messageId <= 0) {
                    throw new IllegalArgumentException("message_id must be positive");
                }
                messages = messageCache.getEditHistory(accountId, dialogId, messageId);
                break;
            default:
                throw new IllegalArgumentException("Unsupported message action: " + action);
        }
        JSONArray items = new JSONArray();
        int count = Math.min(limit, messages == null ? 0 : messages.size());
        for (int i = 0; i < count; i++) {
            items.put(cachedMessageJson(messages.get(i)));
        }
        response.put("accountId", accountId);
        response.put("dialogId", dialogId);
        response.put("messageId", messageId > 0 ? messageId : JSONObject.NULL);
        response.put("total", messages == null ? 0 : messages.size());
        response.put("returned", count);
        response.put("messages", items);
        return response;
    }

    private JSONObject cachedMessageJson(MessageCache.CachedMessage message) throws Exception {
        JSONObject item = new JSONObject();
        item.put("accountId", message.accountId);
        item.put("dialogId", message.dialogId);
        item.put("messageId", message.messageId);
        item.put("senderId", message.senderId);
        item.put("text", message.text == null ? "" : message.text);
        item.put("caption", message.caption == null ? "" : message.caption);
        item.put("timestamp", message.timestamp);
        item.put("mediaType", message.mediaType == null ? "" : message.mediaType);
        item.put("mediaId", message.mediaId == null ? "" : message.mediaId);
        item.put("cachedMediaPath", message.cachedMediaPath == null ? "" : message.cachedMediaPath);
        item.put("rawMessageBytes", message.rawMessageBlob == null ? 0 : message.rawMessageBlob.length);
        item.put("recalled", message.isRecalled);
        item.put("edited", message.isEdited);
        item.put("editedText", message.editedText == null ? "" : message.editedText);
        return item;
    }

    private JSONObject cliCleanup(JSONObject response, Intent intent, boolean mutate) throws Exception {
        long dialogId = requireDialogId(intent);
        if (mutate) {
            recallDetector.setCleanupMode(
                    dialogId,
                    parseBoolean(requireExtra(intent, "value")),
                    CLEANUP_MODE_DURATION_MS
            );
        }
        response.put("dialogId", dialogId);
        response.put("enabled", recallDetector.isCleanupModeActive(dialogId));
        response.put("durationMs", CLEANUP_MODE_DURATION_MS);
        return response;
    }

    private JSONObject cliUiState(JSONObject response) throws Exception {
        Object chatActivity = activeChatActivity.get();
        HostConfigPanel.LogSelectionDiagnostics logConsole =
                HostConfigPanel.inspectLogSelection(false, 0, -1, 1_500L);
        response.put("activeChat", chatActivity != null);
        response.put("trackedDialogId", trackedDialogId);
        response.put("resumedActivity", activityName(resumedHostActivity.get()));
        response.put("configPanelOpen", logConsole.panelOpen);
        response.put("selectedMessage", chatActivity != null
                && Reflect.field(chatActivity, "selectedObject") != null);
        response.put("visibleMessages", visibleMessageState(chatActivity));
        response.put("downloadButton", downloadButtonState());
        response.put("downloadSelectAll", downloadSelectAllState());
        response.put("directActions", new JSONArray(Arrays.asList(
                "config.open", "config.close", "log-console.state",
                "log-console.select", "download-button.state", "download-select-all.state",
                "download-select-all.click", "message-menu.open", "message-menu.close",
                "message-delete.state",
                "menu.state", "menu.open", "config.sections", "config.section.set", "jump-mark",
                "first-message", "scroll"
        )));
        return response;
    }

    private JSONObject cliUiDownloadButtonState(JSONObject response) throws Exception {
        response.put("downloadButton", downloadButtonState());
        return response;
    }

    private JSONObject cliUiDownloadSelectAll(JSONObject response, boolean click) throws Exception {
        if (click) {
            Object activity = activeDialogsActivity.get();
            Object pager = Reflect.field(activity, "searchViewPager");
            if (!bindDownloadSelectAll(pager, "cli-click")) {
                throw new IllegalStateException(
                        "Download selection mode is not active on the downloads tab");
            }
            Object actionMode = Reflect.invokeIfExists(pager, "getActionMode", new Class<?>[0]);
            View button = actionMode instanceof ViewGroup
                    ? ((ViewGroup) actionMode).findViewWithTag(MENU_ID_SELECT_ALL)
                    : null;
            if (button == null || !button.performClick()) {
                throw new IllegalStateException("Download Select All action is unavailable");
            }
            response.put("clicked", true);
        }
        response.put("downloadSelectAll", downloadSelectAllState());
        return response;
    }

    private JSONObject downloadSelectAllState() throws Exception {
        Object activity = activeDialogsActivity.get();
        Object pager = Reflect.field(activity, "searchViewPager");
        Object container = Reflect.invokeIfExists(pager, "getDownloadsContainer", new Class<?>[0]);
        Object current = Reflect.invokeIfExists(pager, "getCurrentView", new Class<?>[0]);
        Object actionMode = Reflect.invokeIfExists(pager, "getActionMode", new Class<?>[0]);
        boolean showing = Boolean.TRUE.equals(
                Reflect.invokeIfExists(pager, "actionModeShowing", new Class<?>[0]));
        View button = actionMode instanceof ViewGroup
                ? ((ViewGroup) actionMode).findViewWithTag(MENU_ID_SELECT_ALL)
                : null;
        JSONObject state = new JSONObject();
        state.put("hostAvailable", activity != null);
        state.put("pagerAvailable", pager != null);
        state.put("downloadsContainerAvailable", container instanceof ViewGroup);
        state.put("currentDownloads", container != null && current == container);
        state.put("actionModeShowing", showing);
        state.put("actionModeAvailable", actionMode instanceof ViewGroup);
        Object selectedFiles = Reflect.field(pager, "selectedFiles");
        state.put("selectedCount", selectedFiles instanceof Map<?, ?>
                ? ((Map<?, ?>) selectedFiles).size()
                : 0);
        state.put("selectAll", viewState(button));
        return state;
    }

    private JSONObject downloadButtonState() throws Exception {
        Object activity = activeDialogsActivity.get();
        Object actionBar = Reflect.field(activity, "actionBar");
        View item = actionBar instanceof ViewGroup
                ? nativeDownloadItem(activity, (ViewGroup) actionBar)
                : null;
        JSONObject state = viewState(item);
        Context context = activity == null
                ? resolveHostApplication()
                : contextFromSettingsHost(activity);
        boolean configured = context != null && isEnhancementEnabled(
                context,
                EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE
        );
        keepDownloadButtonVisibleEnabled = configured;
        state.put("hostAvailable", activity != null);
        state.put("configuredEnabled", configured);
        state.put("nativeVisibleFlag", Boolean.TRUE.equals(
                Reflect.field(activity, "downloadsItemVisible")));
        boolean forced;
        synchronized (persistentDownloadButtonHosts) {
            forced = activity != null && persistentDownloadButtonHosts.contains(activity);
        }
        state.put("forcedByGramSieve", forced);
        if (item != null) {
            state.put("class", item.getClass().getName());
            state.put("alpha", item.getAlpha());
            state.put("scaleX", item.getScaleX());
            state.put("scaleY", item.getScaleY());
            state.put("width", item.getWidth());
            state.put("height", item.getHeight());
        }
        return state;
    }

    private JSONObject cliUiMessageDeleteState(JSONObject response) throws Exception {
        Object chatActivity = activeChatActivity.get();
        Object popupWindow = Reflect.field(chatActivity, "scrimPopupWindow");
        Object content = Reflect.invokeIfExists(popupWindow, "getContentView", new Class<?>[0]);
        View popupContent = content instanceof View ? (View) content : null;
        View deleteItem = findNativeDeleteMenuItem(popupContent);
        ViewGroup parent = deleteItem != null && deleteItem.getParent() instanceof ViewGroup
                ? (ViewGroup) deleteItem.getParent()
                : null;

        JSONObject state = new JSONObject();
        state.put("activeChat", chatActivity != null);
        state.put("popupAvailable", popupWindow instanceof PopupWindow);
        state.put("popupContent", viewState(popupContent));
        state.put("deleteItem", viewState(deleteItem));
        state.put("deleteItemIndex", parent == null ? -1 : parent.indexOfChild(deleteItem));
        state.put("menuItemCount", parent == null ? 0 : parent.getChildCount());
        state.put("selectedMessageId", chatActivity == null
                ? 0
                : resolveMessageId(Reflect.field(chatActivity, "selectedObject")));
        state.put("dialogId", trackedDialogId);
        if (popupWindow instanceof PopupWindow) {
            PopupWindow window = (PopupWindow) popupWindow;
            state.put("windowWidth", window.getWidth());
            state.put("windowHeight", window.getHeight());
            state.put("windowShowing", window.isShowing());
        }
        if (popupContent != null) {
            state.put("contentWidth", popupContent.getWidth());
            state.put("contentHeight", popupContent.getHeight());
            state.put("contentMeasuredWidth", popupContent.getMeasuredWidth());
            state.put("contentMeasuredHeight", popupContent.getMeasuredHeight());
        }
        if (deleteItem != null) {
            Rect visibleBounds = new Rect();
            state.put("deleteItemGloballyVisible", deleteItem.getGlobalVisibleRect(visibleBounds));
            JSONObject bounds = new JSONObject();
            bounds.put("left", visibleBounds.left);
            bounds.put("top", visibleBounds.top);
            bounds.put("right", visibleBounds.right);
            bounds.put("bottom", visibleBounds.bottom);
            state.put("deleteItemBounds", bounds);
        }
        state.put("flow", messageDeleteDiagnosticsJson(messageDeleteDiagnostics.snapshot()));
        response.put("messageDelete", state);
        return response;
    }

    private JSONObject cliUiMessageMenuOpen(JSONObject response, Intent intent) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        int messageId = intExtra(intent, "message_id", 0);
        if (messageId <= 0) {
            throw new IllegalArgumentException("message_id must be positive");
        }
        View messageView = findVisibleMessageView(chatActivity, messageId);
        if (messageView == null) {
            throw new IllegalStateException("Message is not currently visible: " + messageId);
        }
        Object opened = Reflect.invokeIfExists(
                chatActivity,
                "createMenu",
                new Class<?>[]{
                        View.class,
                        boolean.class,
                        boolean.class,
                        float.class,
                        float.class,
                        boolean.class,
                        boolean.class,
                        boolean.class
                },
                messageView,
                true,
                false,
                messageView.getWidth() / 2f,
                messageView.getHeight() / 2f,
                false,
                false,
                false
        );
        if (!Boolean.TRUE.equals(opened)) {
            throw new IllegalStateException("Telegram did not open the message menu");
        }
        response.put("opened", true);
        response.put("messageId", messageId);
        return cliUiMessageDeleteState(response);
    }

    private JSONObject cliUiMessageMenuClose(JSONObject response, Intent intent) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        Object popup = Reflect.field(chatActivity, "scrimPopupWindow");
        boolean showing = popup instanceof PopupWindow && ((PopupWindow) popup).isShowing();
        if (showing) {
            dismissScrimPopup(chatActivity);
        }
        response.put("closed", showing);
        return cliUiMessageDeleteState(response);
    }

    private View findVisibleMessageView(Object chatActivity, int messageId) {
        Object list = chatActivity == null ? null : Reflect.field(chatActivity, "chatListView");
        if (!(list instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) list;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (resolveMessageId(resolveMessageObject(child)) == messageId) {
                return child;
            }
        }
        return null;
    }

    private JSONObject messageDeleteDiagnosticsJson(MessageDeleteFlowDiagnostics.Snapshot snapshot)
            throws Exception {
        JSONObject state = new JSONObject();
        state.put("popupCount", snapshot.popupCount);
        state.put("alertEntryCount", snapshot.alertEntryCount);
        state.put("alertReturnCount", snapshot.alertReturnCount);
        state.put("controllerRequestCount", snapshot.controllerRequestCount);
        state.put("deleteRpcCount", snapshot.deleteRpcCount);
        state.put("originRecoveryCount", snapshot.originRecoveryCount);
        state.put("lastUpdatedAtMs", snapshot.lastUpdatedAtMs);
        state.put("lastDialogId", snapshot.lastDialogId);
        state.put("lastMessageId", snapshot.lastMessageId);
        state.put("lastDeleteItemPresent", snapshot.lastDeleteItemPresent);
        state.put("lastDeleteItemClickable", snapshot.lastDeleteItemClickable);
        state.put("lastDeleteItemHasListener", snapshot.lastDeleteItemHasListener);
        state.put("lastDeleteItemIndex", snapshot.lastDeleteItemIndex);
        state.put("lastMenuItemCount", snapshot.lastMenuItemCount);
        state.put("lastPopupWidth", snapshot.lastPopupWidth);
        state.put("lastPopupHeight", snapshot.lastPopupHeight);
        state.put("lastAlertParameterCount", snapshot.lastAlertParameterCount);
        state.put("lastControllerMessageCount", snapshot.lastControllerMessageCount);
        state.put("lastRpcType", snapshot.lastRpcType);
        return state;
    }

    private JSONObject visibleMessageState(Object chatActivity) throws Exception {
        JSONObject state = new JSONObject();
        Object list = chatActivity == null ? null : Reflect.field(chatActivity, "chatListView");
        state.put("available", list instanceof ViewGroup);
        if (!(list instanceof ViewGroup)) {
            return state;
        }
        ViewGroup group = (ViewGroup) list;
        int recognized = 0;
        int minId = Integer.MAX_VALUE;
        int maxId = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            Object messageObject = resolveMessageObject(group.getChildAt(i));
            int messageId = resolveMessageId(messageObject);
            if (messageId <= 0) {
                continue;
            }
            recognized++;
            minId = Math.min(minId, messageId);
            maxId = Math.max(maxId, messageId);
        }
        state.put("childViews", group.getChildCount());
        state.put("recognized", recognized);
        state.put("minId", recognized == 0 ? JSONObject.NULL : minId);
        state.put("maxId", recognized == 0 ? JSONObject.NULL : maxId);
        return state;
    }

    private JSONObject cliUiMenuState(JSONObject response) throws Exception {
        Object visibleFragment = resolveCurrentTelegramFragment(savedClassLoader);
        Object chatActivity = activeChatActivity.get();
        if (chatActivity == null && isFragment(visibleFragment, "ChatActivity")) {
            chatActivity = visibleFragment;
        }
        response.put("visibleFragment", visibleFragment == null
                ? ""
                : visibleFragment.getClass().getName());
        response.put("chat", chatActivity != null);

        Object headerItem = chatActivity == null ? null : Reflect.field(chatActivity, "headerItem");
        JSONObject chatItems = new JSONObject();
        chatItems.put("settings", menuItemState(headerItem, MENU_ID_CHAT));
        chatItems.put("scrollTop", menuItemState(headerItem, MENU_ID_SCROLL_TOP));
        chatItems.put("firstMessage", menuItemState(headerItem, MENU_ID_FIRST_MESSAGE));
        chatItems.put("jumpToMark", menuItemState(headerItem, MENU_ID_JUMP_TO_MARK));
        chatItems.put("antiRecall", menuItemState(headerItem, MENU_ID_ANTI_RECALL));
        chatItems.put("cleanupMode", menuItemState(headerItem, MENU_ID_CLEANUP_MODE));
        response.put("chatItems", chatItems);

        JSONArray menuLabels = new JSONArray();
        ViewGroup popupLayout = menuPopupLayout(headerItem);
        if (popupLayout != null) {
            List<String> labels = new ArrayList<>();
            collectMenuLabels(popupLayout, labels);
            for (String label : labels) {
                menuLabels.put(label);
            }
            response.put("chatMenuDirectChildren", popupLayout.getChildCount());
        } else {
            response.put("chatMenuDirectChildren", 0);
        }
        response.put("chatMenuLabels", menuLabels);
        response.put("jumpToFirstMessagePresent", containsMenuLabel(
                menuLabels, "跳转到第一条消息", "Jump to first message"));

        Object profileHost = isFragment(visibleFragment, "ProfileActivity")
                ? visibleFragment
                : null;
        Object overflow = profileHost == null ? null : resolveOverflowMenuItem(profileHost);
        response.put("profileSettings", menuItemState(overflow, MENU_ID_GLOBAL));

        Object actionBar = visibleFragment == null ? null : Reflect.field(visibleFragment, "actionBar");
        View selectAll = actionBar instanceof ViewGroup
                ? ((ViewGroup) actionBar).findViewWithTag(MENU_ID_SELECT_ALL)
                : null;
        response.put("selectAll", viewState(selectAll));

        Object listView = isFragment(visibleFragment, "SettingsActivity")
                ? Reflect.field(visibleFragment, "listView")
                : null;
        Object adapter = Reflect.field(listView, "adapter");
        Object rawItems = Reflect.field(adapter, "items");
        boolean settingsRowPresent = rawItems instanceof List<?>
                && containsGramSieveSettingsItem((List<?>) rawItems);
        response.put("settingsRowPresent", settingsRowPresent);
        return response;
    }

    private JSONObject cliUiMenuOpen(JSONObject response, Intent intent) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        Object headerItem = Reflect.field(chatActivity, "headerItem");
        if (headerItem == null) {
            throw new IllegalStateException("Active chat header menu is unavailable");
        }
        Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
        response.put("opened", true);
        return cliUiMenuState(response);
    }

    private JSONObject cliUiFirstMessage(
            JSONObject response,
            Context context,
            Intent intent
    ) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        boolean invoked = jumpToFirstMessage(chatActivity, context);
        response.put("dialogId", trackedDialogId);
        response.put("targetMessageId", 1);
        response.put("invoked", invoked);
        return response;
    }

    private JSONObject menuItemState(Object menuItem, int targetId) throws Exception {
        ViewGroup popupLayout = menuPopupLayout(menuItem);
        List<View> matches = new ArrayList<>();
        if (popupLayout != null) {
            collectTaggedMenuItemViews(popupLayout, targetId, matches);
        }
        JSONObject state = viewState(matches.isEmpty() ? null : matches.get(0));
        state.put("occurrences", matches.size());
        return state;
    }

    private void collectMenuLabels(ViewGroup group, List<String> labels) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String label = String.valueOf(((TextView) child).getText()).trim();
                if (!label.isEmpty() && !labels.contains(label)) {
                    labels.add(label.length() <= 80 ? label : label.substring(0, 80));
                }
            }
            if (child instanceof ViewGroup) {
                collectMenuLabels((ViewGroup) child, labels);
            }
        }
    }

    private boolean containsMenuLabel(JSONArray labels, String... expected) {
        for (int i = 0; i < labels.length(); i++) {
            String actual = labels.optString(i, "").trim();
            for (String candidate : expected) {
                if (candidate.equalsIgnoreCase(actual)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JSONObject viewState(View view) throws Exception {
        JSONObject state = new JSONObject();
        state.put("present", view != null);
        if (view == null) {
            return state;
        }
        state.put("visibility", view.getVisibility());
        state.put("shown", view.isShown());
        state.put("attached", view.isAttachedToWindow());
        state.put("enabled", view.isEnabled());
        state.put("clickable", view.isClickable());
        state.put("hasClickListener", view.hasOnClickListeners());
        return state;
    }

    private JSONObject cliUiConfigOpen(JSONObject response) throws Exception {
        Activity activity = resumedHostActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            throw new IllegalStateException(
                    "No resumed Telegram activity; foreground Telegram once and retry"
            );
        }
        boolean opened = showHostConfigPanel(
                activity,
                activity,
                CONFIG_MODE_GLOBAL,
                0L,
                ""
        );
        if (!opened) {
            throw new IllegalStateException("Could not attach GramSieve config panel");
        }
        response.put("opened", true);
        response.put("activity", activityName(activity));
        appendLogSelectionDiagnostics(
                response,
                HostConfigPanel.inspectLogSelection(false, 0, -1, 1_500L)
        );
        return appendFeatureSectionDiagnostics(
                response,
                HostConfigPanel.inspectFeatureSections(null, null, 1_500L)
        );
    }

    private JSONObject cliUiConfigClose(JSONObject response) throws Exception {
        ViewGroup root = activeConfigRoot.get();
        boolean closed = HostConfigPanel.closeExisting(root);
        if (closed) {
            activeConfigRoot = new WeakReference<>(null);
        }
        response.put("closed", closed);
        return response;
    }

    private JSONObject cliUiConfigSections(
            JSONObject response,
            Intent intent,
            boolean mutate
    ) throws Exception {
        String section = mutate ? requireExtra(intent, "name") : null;
        Boolean expanded = mutate
                ? parseBoolean(requireExtra(intent, "value"))
                : null;
        HostConfigPanel.FeatureSectionDiagnostics diagnostics =
                HostConfigPanel.inspectFeatureSections(section, expanded, 1_500L);
        if (mutate && (!diagnostics.panelOpen || !diagnostics.globalPanel
                || !diagnostics.changed)) {
            throw new IllegalStateException(diagnostics.error.isBlank()
                    ? "Global GramSieve config sections are unavailable"
                    : diagnostics.error);
        }
        return appendFeatureSectionDiagnostics(response, diagnostics);
    }

    private JSONObject appendFeatureSectionDiagnostics(
            JSONObject response,
            HostConfigPanel.FeatureSectionDiagnostics diagnostics
    ) throws Exception {
        JSONObject state = new JSONObject();
        state.put("panelOpen", diagnostics.panelOpen);
        state.put("error", diagnostics.error);
        state.put("globalPanel", diagnostics.globalPanel);
        state.put("testedAvailable", diagnostics.testedAvailable);
        state.put("testedExpanded", diagnostics.testedExpanded);
        state.put("untestedAvailable", diagnostics.untestedAvailable);
        state.put("untestedExpanded", diagnostics.untestedExpanded);
        state.put("changed", diagnostics.changed);
        state.put("testedCapabilities", new JSONArray(diagnostics.testedCapabilities));
        state.put("testedControls", new JSONArray(diagnostics.testedControls));
        state.put("untestedControls", new JSONArray(diagnostics.untestedControls));
        response.put("featureSections", state);
        return response;
    }

    private JSONObject cliUiLogConsole(
            JSONObject response,
            Intent intent,
            boolean exercise
    ) throws Exception {
        int start = intExtra(intent, "start", 0);
        int end = intExtra(intent, "end", -1);
        HostConfigPanel.LogSelectionDiagnostics diagnostics =
                HostConfigPanel.inspectLogSelection(exercise, start, end, 2_500L);
        return appendLogSelectionDiagnostics(response, diagnostics);
    }

    private JSONObject appendLogSelectionDiagnostics(
            JSONObject response,
            HostConfigPanel.LogSelectionDiagnostics diagnostics
    ) throws Exception {
        JSONObject state = new JSONObject();
        state.put("panelOpen", diagnostics.panelOpen);
        state.put("exercised", diagnostics.exercised);
        state.put("attached", diagnostics.attached);
        state.put("globallyVisible", diagnostics.globallyVisible);
        state.put("visibilityRequested", diagnostics.visibilityRequested);
        state.put("textSelectable", diagnostics.textSelectable);
        state.put("longClickable", diagnostics.longClickable);
        state.put("focusable", diagnostics.focusable);
        state.put("focused", diagnostics.focused);
        state.put("textLength", diagnostics.textLength);
        state.put("selectionStart", diagnostics.selectionStart);
        state.put("selectionEnd", diagnostics.selectionEnd);
        state.put("hasSelection", diagnostics.hasSelection);
        state.put("longClickPerformed", diagnostics.longClickPerformed);
        state.put("contextMenuShown", diagnostics.contextMenuShown);
        state.put("actionModeCreateCount", diagnostics.actionModeCreateCount);
        state.put("actionModeActive", diagnostics.actionModeActive);
        state.put("selectionMenuSize", diagnostics.selectionMenuSize);
        state.put("actionModeObserved", diagnostics.actionModeObserved);
        state.put("selectionControllerPresent", diagnostics.selectionControllerPresent);
        state.put("selectionControllerActive", diagnostics.selectionControllerActive);
        state.put("startHandleShowing", diagnostics.startHandleShowing);
        state.put("endHandleShowing", diagnostics.endHandleShowing);
        state.put("selectionHandlesShowing", diagnostics.selectionHandlesShowing);
        state.put("selectionUiReady", diagnostics.selectionUiReady);
        if (!diagnostics.error.isBlank()) {
            state.put("error", diagnostics.error);
        }
        response.put("logConsole", state);
        return response;
    }

    private String activityName(Activity activity) {
        return activity == null ? "" : activity.getClass().getName();
    }

    private JSONObject cliUiJump(JSONObject response, Context context, Intent intent) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        int messageId = intExtra(intent, "message_id", 0);
        if (messageId <= 0) {
            int accountId = intExtra(intent, "account_id", resolveSelectedTelegramAccount(savedClassLoader));
            List<MessageMarkStore.Mark> marks = messageMarkStore.list(accountId, trackedDialogId);
            if (marks.isEmpty()) {
                throw new IllegalStateException("No marked message for active dialog");
            }
            messageId = marks.get(0).messageId;
        }
        boolean invoked = invokeScrollToMessageId(chatActivity, messageId);
        if (!invoked) {
            throw new IllegalStateException("Telegram scrollToMessageId is unavailable");
        }
        response.put("dialogId", trackedDialogId);
        response.put("messageId", messageId);
        response.put("invoked", true);
        return response;
    }

    private JSONObject cliUiScroll(JSONObject response, Context context, Intent intent) throws Exception {
        Object chatActivity = requireActiveChat(intent);
        scrollChatToTop(chatActivity, context);
        response.put("dialogId", trackedDialogId);
        response.put("invoked", true);
        return response;
    }

    private Object requireActiveChat(Intent intent) {
        Object chatActivity = activeChatActivity.get();
        if (chatActivity == null || trackedDialogId == 0L) {
            throw new IllegalStateException("No resumed Telegram chat");
        }
        long requestedDialogId = longExtra(intent, "dialog_id", 0L);
        if (requestedDialogId != 0L && requestedDialogId != trackedDialogId) {
            throw new IllegalStateException("Active dialog is " + trackedDialogId
                    + ", requested " + requestedDialogId);
        }
        return chatActivity;
    }

    private FilterConfig currentCliConfig(Context context) {
        if (configProvider == null || context == null) {
            throw new IllegalStateException("Configuration provider is unavailable");
        }
        return configProvider.getConfig(context).deepCopy().sanitize();
    }

    private JSONObject cliSuccess(String command) throws Exception {
        JSONObject response = new JSONObject();
        response.put("ok", true);
        response.put("command", command);
        return response;
    }

    private String cliResponseData(JSONObject response) {
        String data = response == null ? "" : response.toString();
        if (data.length() <= MAX_CLI_RESPONSE_CHARS) {
            return data;
        }
        JSONObject bounded = new JSONObject();
        try {
            bounded.put("ok", false);
            bounded.put("command", response.optString("command", ""));
            bounded.put("error", "CLI response exceeds " + MAX_CLI_RESPONSE_CHARS + " characters");
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"CLI response too large\"}";
        }
        return bounded.toString();
    }

    private String requireExtra(Intent intent, String name) {
        String value = stringExtra(intent, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private String stringExtra(Intent intent, String name) {
        String value = intent == null ? null : intent.getStringExtra(name);
        return value == null ? "" : value;
    }

    private long requireDialogId(Intent intent) {
        long dialogId = longExtra(intent, "dialog_id", 0L);
        if (dialogId == 0L) {
            throw new IllegalArgumentException("dialog_id must be non-zero");
        }
        return dialogId;
    }

    private int intExtra(Intent intent, String name, int defaultValue) {
        String value = stringExtra(intent, name).trim();
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is not an integer: " + value);
        }
    }

    private long longExtra(Intent intent, String name, long defaultValue) {
        String value = stringExtra(intent, name).trim();
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is not a long: " + value);
        }
    }

    private boolean parseBoolean(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "on".equals(value)
                || "yes".equals(value) || "record".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "0".equals(value) || "off".equals(value)
                || "no".equals(value) || "skip".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("Expected boolean, got: " + raw);
    }

    private EnhancementConfig.Feature parseFeature(String raw) {
        for (EnhancementConfig.Feature feature : EnhancementConfig.Feature.values()) {
            if (feature.name().equalsIgnoreCase(raw) || feature.key.equalsIgnoreCase(raw)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("Unknown feature: " + raw);
    }

    private ModuleConflictDetector.KnownModule parseKnownModule(String raw) {
        for (ModuleConflictDetector.KnownModule knownModule
                : ModuleConflictDetector.KnownModule.values()) {
            if (knownModule.name().equalsIgnoreCase(raw)
                    || knownModule.displayName.equalsIgnoreCase(raw)) {
                return knownModule;
            }
        }
        throw new IllegalArgumentException("Unknown module fallback: " + raw);
    }

    private String describeHookArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(" args=[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Object arg = args.get(i);
            if (arg instanceof CharSequence) {
                builder.append("strLen=").append(((CharSequence) arg).length());
            } else {
                builder.append(String.valueOf(arg));
            }
        }
        return builder.append(']').toString();
    }

    private Context resolveHostApplication() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentApplication = activityThreadClass.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (!(app instanceof Context)) {
                return null;
            }
            Context context = (Context) app;
            Context appContext = context.getApplicationContext();
            hostApplicationContext = appContext == null ? context : appContext;
            return hostApplicationContext;
        } catch (Throwable throwable) {
            info("Anti-recall: ActivityThread.currentApplication() unavailable");
            return null;
        }
    }

    private synchronized void registerHostActivityTracking(Context context) {
        if (hostLifecycleCallbacks != null || context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Application application = appContext instanceof Application
                ? (Application) appContext
                : context instanceof Application ? (Application) context : null;
        if (application == null) {
            info("CLI activity tracking unavailable: host Application missing");
            return;
        }
        Application.ActivityLifecycleCallbacks callbacks =
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(Activity activity, Bundle state) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityResumed(Activity activity) {
                        if (isTelegramHostActivity(activity)) {
                            resumedHostActivity = new WeakReference<>(activity);
                        }
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        if (resumedHostActivity.get() == activity) {
                            resumedHostActivity = new WeakReference<>(null);
                        }
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                        if (resumedHostActivity.get() == activity) {
                            resumedHostActivity = new WeakReference<>(null);
                        }
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        if (resumedHostActivity.get() == activity) {
                            resumedHostActivity = new WeakReference<>(null);
                        }
                    }
                };
        application.registerActivityLifecycleCallbacks(callbacks);
        hostLifecycleApplication = application;
        hostLifecycleCallbacks = callbacks;
        info("CLI activity tracking registered");
    }

    private synchronized void unregisterHostActivityTracking() {
        Application application = hostLifecycleApplication;
        Application.ActivityLifecycleCallbacks callbacks = hostLifecycleCallbacks;
        hostLifecycleApplication = null;
        hostLifecycleCallbacks = null;
        resumedHostActivity = new WeakReference<>(null);
        if (application == null || callbacks == null) {
            return;
        }
        try {
            application.unregisterActivityLifecycleCallbacks(callbacks);
        } catch (RuntimeException exception) {
            error("Hot reload: CLI activity tracking unregister failed", exception);
        }
    }

    private boolean isTelegramHostActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        String packageName = activity.getPackageName();
        return packageName != null && packageName.equals(telegramResourcePackageName);
    }

    private boolean usesModuleFallback(ModuleConflictDetector.ConflictKind kind) {
        if (kind == null) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        EnhancementConfig snapshot = moduleFallbackSnapshot;
        if (now - moduleFallbackCheckedAt < MODULE_FALLBACK_SNAPSHOT_MS) {
            return snapshot.yieldsToModule(kind);
        }
        synchronized (moduleFallbackLock) {
            now = SystemClock.elapsedRealtime();
            snapshot = moduleFallbackSnapshot;
            if (now - moduleFallbackCheckedAt < MODULE_FALLBACK_SNAPSHOT_MS) {
                return snapshot.yieldsToModule(kind);
            }
            moduleFallbackCheckedAt = now;
            XposedConfigProvider provider = configProvider;
            if (provider == null) {
                return snapshot.yieldsToModule(kind);
            }
            Context context = hostApplicationContext;
            if (context == null) {
                context = resolveHostApplication();
            }
            if (context == null) {
                return snapshot.yieldsToModule(kind);
            }
            try {
                FilterConfig config = provider.getConfig(context);
                EnhancementConfig loaded = config.enhancements == null
                        ? new EnhancementConfig()
                        : config.enhancements.deepCopy().sanitize();
                moduleFallbackSnapshot = loaded;
                return loaded.yieldsToModule(kind);
            } catch (RuntimeException ignored) {
                return snapshot.yieldsToModule(kind);
            }
        }
    }

    private void invalidateModuleFallbackSnapshot() {
        moduleFallbackCheckedAt = -MODULE_FALLBACK_SNAPSHOT_MS;
    }

    private void hookTaggedViewMeasure() {
        try {
            Method measure = Reflect.method(View.class, "measure", int.class, int.class);
            hook(measure, chain -> {
                Object result = chain.proceed();
                Object view = chain.getThisObject();
                if (view instanceof View) {
                    UiMutation.overrideMeasuredHeight((View) view, null);
                }
                return result;
            });
            info("Hooked View.measure for tagged hidden rows");
        } catch (Throwable throwable) {
            error("Failed to hook View.measure", throwable);
        }
    }

    private void logRemoteCapabilities() {
        try {
            String[] remoteFiles = module.listRemoteFiles();
            info(
                    "Remote caps properties=" + module.getFrameworkProperties()
                            + " files=" + (remoteFiles == null ? "null" : Arrays.toString(remoteFiles))
            );
        } catch (Throwable throwable) {
            error("Remote capability probe failed", throwable);
        }
    }

    private void hookChatMessageCell(ClassLoader classLoader) {
        try {
            Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
            Class<?> groupedMessagesClass = classLoader.loadClass("org.telegram.messenger.MessageObject$GroupedMessages");
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            boolean hooked = false;
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObjectInternal",
                    new Class<?>[]{messageObjectClass}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageContent",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookCellLifecycleMethod(
                    cellClass,
                    "onLayout",
                    new Class<?>[]{boolean.class, int.class, int.class, int.class, int.class}
            );
            hooked |= tryHookCellLifecycleMethod(
                    cellClass,
                    "onAttachedToWindow",
                    new Class<?>[0]
            );
            hooked |= tryHookCellMeasureMethod(
                    cellClass,
                    "onMeasure",
                    new Class<?>[]{int.class, int.class}
            );
            if (!hooked) {
                throw new IllegalStateException("No ChatMessageCell hook points were registered");
            }
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell", throwable);
        }
    }

    private boolean tryHookMessageMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleMessageBinding);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private boolean tryHookCellLifecycleMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleCellLifecycle);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private boolean tryHookCellMeasureMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleCellMeasure);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private static String signatureOf(Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Class<?> parameterType = parameterTypes[i];
            builder.append(parameterType == null ? "null" : parameterType.getSimpleName());
        }
        return builder.append(')').toString();
    }

    private void hookChatActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method createView = Reflect.method(chatActivityClass, "createView", Context.class);
            hook(createView, chain -> {
                Object result = chain.proceed();
                try {
                    injectChatMenu(chain.getThisObject());
                } catch (Throwable throwable) {
                    error("Chat menu injection failed", throwable);
                }
                return result;
            });
            info("Hooked ChatActivity menu");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity menu", throwable);
        }
    }

    private void hookChatActivityResume(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method onResume = Reflect.method(chatActivityClass, "onResume");
            hook(onResume, chain -> {
                Object result = chain.proceed();
                try {
                    Object chatActivity = chain.getThisObject();
                    activeChatActivity = new WeakReference<>(chatActivity);
                    injectChatMenu(chatActivity);
                    refreshChatActivityFiltering(chatActivity);
                    beginReadPositionTracking(chatActivity);
                } catch (Throwable throwable) {
                    error("ChatActivity resume refresh failed", throwable);
                }
                return result;
            });
            info("Hooked ChatActivity.onResume refresh");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.onResume", throwable);
        }
    }

    private void hookChatActivityPause(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method onPause = Reflect.method(chatActivityClass, "onPause");
            hook(onPause, chain -> {
                try {
                    Object chatActivity = chain.getThisObject();
                    flushReadPosition(chatActivity);
                    markLoadedFilteredMessagesAsRead(chatActivity);
                    Object active = activeChatActivity.get();
                    if (active == chatActivity) {
                        activeChatActivity.clear();
                    }
                } catch (Throwable throwable) {
                    error("ChatActivity pause flush failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked ChatActivity.onPause read position flush");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.onPause", throwable);
        }
    }

    private void hookScrollToLastMessage(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method scrollToLast = Reflect.method(
                    chatActivityClass,
                    "scrollToLastMessage",
                    boolean.class,
                    boolean.class,
                    Runnable.class
            );
            hook(scrollToLast, chain -> {
                try {
                    Object chatActivity = chain.getThisObject();
                    saveReadPositionBeforeJump(chatActivity);
                } catch (Throwable throwable) {
                    error("scrollToLastMessage pre-save failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked ChatActivity.scrollToLastMessage");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.scrollToLastMessage", throwable);
        }
    }

    private void saveReadPositionBeforeJump(Object chatActivity) {
        if (suppressNextSaveBeforeJump) {
            return;
        }
        long dialogId = trackedDialogId;
        int currentPos = lastTopmostMessageId;
        if (dialogId == 0L || currentPos <= 0) {
            return;
        }
        Context context = resolveContextFromActivity(chatActivity);
        if (context == null) {
            return;
        }
        ChatReadPositionStore.save(context.getApplicationContext(), dialogId, currentPos);
        jumpDetected = true;
        info("SaveBeforeJump: saved position " + currentPos + " for dialog " + dialogId);
    }

    private void hookMessageContextMenu(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method createMenu = Reflect.method(
                    chatActivityClass,
                    "createMenu",
                    View.class,
                    boolean.class,
                    boolean.class,
                    float.class,
                    float.class,
                    boolean.class,
                    boolean.class,
                    boolean.class
            );
            deoptimize(createMenu, "ChatActivity.createMenu(View, boolean, boolean, float, float, boolean, boolean, boolean)");
            hook(createMenu, chain -> {
                Object result = chain.proceed();
                try {
                    if (Boolean.TRUE.equals(result) && chain.getArg(0) instanceof View) {
                        injectMessageBlockMenu(chain.getThisObject(), (View) chain.getArg(0));
                    }
                } catch (Throwable throwable) {
                    error("Message context menu injection failed", throwable);
                }
                return result;
            });
            info("Hooked ChatActivity message context menu");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity message context menu", throwable);
        }
    }

    private void hookMessageDeleteFlow(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            int hooked = 0;
            for (Method method : chatActivityClass.getDeclaredMethods()) {
                int parameterCount = method.getParameterCount();
                if (!"createDeleteMessagesAlert".equals(method.getName())
                        || (parameterCount != 2 && parameterCount != 3)) {
                    continue;
                }
                method.setAccessible(true);
                deoptimize(method, "ChatActivity.createDeleteMessagesAlert/" + parameterCount);
                hook(method, XposedInterface.PRIORITY_HIGHEST, chain -> {
                    Object selected = chain.getArg(0);
                    if (selected == null) {
                        selected = Reflect.field(chain.getThisObject(), "selectedObject");
                    }
                    int messageId = resolveMessageId(selected);
                    long dialogId = trackedDialogId;
                    messageDeleteDiagnostics.recordAlertEntry(dialogId, messageId, parameterCount);
                    info("MessageDeleteFlow: alert enter params=" + parameterCount
                            + " dialogId=" + dialogId + " messageId=" + messageId);
                    try {
                        return chain.proceed();
                    } finally {
                        messageDeleteDiagnostics.recordAlertReturn();
                        Object visibleDialog = Reflect.invokeIfExists(
                                chain.getThisObject(), "getVisibleDialog", new Class<?>[0]);
                        info("MessageDeleteFlow: alert return params=" + parameterCount
                                + " visibleDialog=" + (visibleDialog != null));
                    }
                });
                hooked++;
            }
            info("MessageDeleteFlow: hooked alert overloads=" + hooked);
        } catch (Throwable throwable) {
            error("MessageDeleteFlow: failed to hook delete alert", throwable);
        }
    }

    private void hookChatActivityAdapter(ClassLoader classLoader) {
        try {
            Class<?> adapterClass = classLoader.loadClass("org.telegram.ui.ChatActivity$ChatActivityAdapter");
            Class<?> viewHolderClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
            Method onBindViewHolder = Reflect.method(adapterClass, "onBindViewHolder", viewHolderClass, int.class);
            deoptimize(onBindViewHolder, "ChatActivityAdapter.onBindViewHolder(ViewHolder, int)");
            hook(onBindViewHolder, this::handleChatRowBinding);
            info("Hooked ChatActivityAdapter.onBindViewHolder(ViewHolder, int)");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivityAdapter", throwable);
        }
    }

    private void hookRecyclerViewBinding(ClassLoader classLoader) {
        try {
            Class<?> adapterClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter");
            Class<?> recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Recycler");
            Class<?> viewHolderClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");

            Method bindViewHolder = Reflect.method(adapterClass, "bindViewHolder", viewHolderClass, int.class);
            deoptimize(bindViewHolder, "RecyclerView.Adapter.bindViewHolder(ViewHolder, int)");
            hook(bindViewHolder, this::handleRecyclerViewBinding);
            info("Hooked RecyclerView.Adapter.bindViewHolder(ViewHolder, int)");

            Method onViewAttachedToWindow = Reflect.method(adapterClass, "onViewAttachedToWindow", viewHolderClass);
            deoptimize(onViewAttachedToWindow, "RecyclerView.Adapter.onViewAttachedToWindow(ViewHolder)");
            hook(onViewAttachedToWindow, this::handleRecyclerViewAttachment);
            info("Hooked RecyclerView.Adapter.onViewAttachedToWindow(ViewHolder)");

            Method tryBindViewHolderByDeadline = Reflect.method(
                    recyclerClass,
                    "tryBindViewHolderByDeadline",
                    viewHolderClass,
                    int.class,
                    int.class,
                    long.class
            );
            deoptimize(tryBindViewHolderByDeadline, "RecyclerView.Recycler.tryBindViewHolderByDeadline(ViewHolder, int, int, long)");
        } catch (Throwable throwable) {
            error("Failed to hook RecyclerView binding", throwable);
        }
    }

    private void hookProfileSettingsMenu(ClassLoader classLoader) {
        try {
            Class<?> profileActivityClass = classLoader.loadClass("org.telegram.ui.ProfileActivity");
            Method createActionBarMenu = Reflect.method(profileActivityClass, "createActionBarMenu", boolean.class);
            hook(createActionBarMenu, chain -> {
                Object result = chain.proceed();
                try {
                    injectGlobalSettingsMenu(chain.getThisObject(), true);
                } catch (Throwable throwable) {
                    error("ProfileActivity menu injection failed", throwable);
                }
                return result;
            });
            try {
                Method onResume = Reflect.method(profileActivityClass, "onResume");
                hook(onResume, chain -> {
                    Object result = chain.proceed();
                    try {
                        injectGlobalSettingsMenu(chain.getThisObject(), true);
                    } catch (Throwable throwable) {
                        error("ProfileActivity menu resume rebind failed", throwable);
                    }
                    return result;
                });
            } catch (NoSuchMethodException ignored) {
                info("ProfileActivity.onResume unavailable for menu rebind");
            }
            info("Hooked ProfileActivity settings menu");
        } catch (Throwable throwable) {
            error("Failed to hook ProfileActivity menu", throwable);
        }
    }

    private void hookDownloadActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            hookNativeDownloadVisibility(dialogsClass);
            hookDownloadSelectionActionMode(classLoader);
            Method createView = Reflect.method(dialogsClass, "createView", Context.class);
            hook(createView, chain -> {
                Object contextArg = chain.getArg(0);
                if (contextArg instanceof Context) {
                    try {
                        initAntiRecallFromContext((Context) contextArg, "dialogs");
                    } catch (Throwable throwable) {
                        error("DialogsActivity anti-recall init failed", throwable);
                    }
                }
                Object result = chain.proceed();
                try {
                    ensureDownloadUiLifecycle(chain.getThisObject());
                } catch (Throwable t) {
                    error("SelectAll: createView hook failed", t);
                }
                return result;
            });
            try {
                Method onResume = Reflect.method(dialogsClass, "onResume");
                hook(onResume, chain -> {
                    Object result = chain.proceed();
                    try {
                        ensureDownloadUiLifecycle(chain.getThisObject());
                    } catch (Throwable throwable) {
                        error("SelectAll: resume rebind failed", throwable);
                    }
                    return result;
                });
            } catch (NoSuchMethodException ignored) {
                info("DialogsActivity.onResume unavailable for action-mode rebind");
            }
            info("Hooked DialogsActivity lifecycle for download controls");
        } catch (Throwable t) {
            error("Failed to hook DialogsActivity.createView", t);
        }
    }

    private void hookNativeDownloadVisibility(Class<?> dialogsClass) {
        try {
            Method checkVisibility = Reflect.method(dialogsClass, "checkUi_itemDownloadsVisibility");
            hook(checkVisibility, chain -> {
                try {
                    Object activity = chain.getThisObject();
                    if (!retiring && shouldKeepDownloadButtonVisible(activity)) {
                        Reflect.setField(activity, "downloadsItemVisible", true);
                    }
                } catch (Throwable throwable) {
                    if (!persistentDownloadHookErrorLogged) {
                        persistentDownloadHookErrorLogged = true;
                        error("PersistentDownloadButton: native visibility override failed", throwable);
                    }
                }
                return chain.proceed();
            });
            info("PersistentDownloadButton: hooked native visibility controller");
        } catch (Throwable throwable) {
            error("PersistentDownloadButton: native visibility controller unavailable; lifecycle fallback armed",
                    throwable);
        }
    }

    private void hookDownloadSelectionActionMode(ClassLoader classLoader) {
        try {
            Class<?> pagerClass = classLoader.loadClass(
                    "org.telegram.ui.Components.SearchViewPager");
            Method showActionMode = Reflect.method(pagerClass, "showActionMode", boolean.class);
            hook(showActionMode, chain -> {
                Object result = chain.proceed();
                if (Boolean.TRUE.equals(chain.getArg(0))) {
                    try {
                        bindDownloadSelectAll(chain.getThisObject(), "action-mode");
                    } catch (Throwable throwable) {
                        error("SelectAll: download action-mode binding failed", throwable);
                    }
                }
                return result;
            });
            info("SelectAll: hooked SearchViewPager.showActionMode");
        } catch (Throwable throwable) {
            error("SelectAll: failed to hook SearchViewPager.showActionMode", throwable);
        }
    }

    private void ensureDownloadUiLifecycle(Object activity) {
        Object actionBar = Reflect.field(activity, "actionBar");
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        activeDialogsActivity = new WeakReference<>(activity);
        ViewGroup bar = (ViewGroup) actionBar;
        boolean firstInitialization;
        synchronized (initializedDownloadActionBars) {
            firstInitialization = initializedDownloadActionBars.add(bar);
        }
        uiCallbacks.post(bar, () -> {
            try {
                syncPersistentDownloadButton(activity, bar, "lifecycle");
                bindDownloadSelectAll(Reflect.field(activity, "searchViewPager"), "lifecycle");
                if (!firstInitialization) {
                    return;
                }
                installActionModeDetector(bar);
            } catch (Throwable throwable) {
                error("SelectAll: lifecycle setup failed", throwable);
            }
        }, 500);
    }

    private void hookDialogDeletionDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            int hooked = 0;
            for (Method method : dialogsClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!"performSelectedDialogsAction".equals(name)
                        && !"performDeleteOrClearDialogAction".equals(name)
                        && !name.startsWith("lambda$performSelectedDialogsAction$")) {
                    continue;
                }
                String methodName = name;
                int paramCount = method.getParameterCount();
                hook(method, chain -> {
                    try {
                        info("DialogDeleteTrace: " + methodName + " params=" + paramCount
                                + describeHookArgs(chain.getArgs()));
                    } catch (Throwable throwable) {
                        error("DialogDeleteTrace: failed before " + methodName, throwable);
                    }
                    recordDialogDeletionIntentFromUi(methodName, chain.getThisObject(), chain.getArgs());
                    return chain.proceed();
                });
                hooked++;
            }
            info("DialogDeleteTrace: hooked DialogsActivity deletion methods count=" + hooked);
        } catch (Throwable throwable) {
            error("DialogDeleteTrace: failed to hook DialogsActivity deletion methods", throwable);
        }
    }

    private void recordDialogDeletionIntentFromUi(String methodName, Object host, List<Object> args) {
        int action = firstIntArg(args, -1);
        if (action != 0x66) {
            return;
        }
        List<Long> dialogIds = dialogIdsFromArgs(args);
        if (dialogIds.isEmpty() || !isCommittedDialogDeleteCall(methodName)) {
            return;
        }
        if (recallDetector == null) {
            initAntiRecallDeferred();
        }
        RecallDetector detector = recallDetector;
        if (detector == null) {
            info("DialogDeleteTrace: ui delete not marked, anti-recall unavailable count=" + dialogIds.size());
            return;
        }
        boolean revoke = lastBooleanArg(args, false);
        boolean shouldPruneDatabase = shouldPruneDialogDatabaseForCall(methodName);
        int account = resolveDialogDeletionAccount(host);
        int marked = 0;
        for (Long dialogId : dialogIds) {
            if (dialogId != null && dialogId != 0L
                    && detector.processDialogDeletion(account, dialogId, action, revoke, "uiDeleteDialog")) {
                markLocalDialogHidden(dialogId, account);
                if (shouldPruneDatabase) {
                    pruneTelegramDialogDatabase(dialogId, account, methodName);
                }
                marked++;
            }
        }
        if (marked > 0) {
            info("DialogDeleteTrace: marked local ui delete count=" + marked
                    + " action=" + action + " source=" + methodName);
            refreshDialogsUi(host);
        }
    }

    private void markLocalDialogHidden(long dialogId, int account) {
        LocalDialogDeleteStore.HiddenDialog hiddenDialog =
                new LocalDialogDeleteStore.HiddenDialog(account, dialogId);
        locallyHiddenDialogs.add(hiddenDialog);
        LocalDialogDeleteStore store = localDialogDeleteStore;
        if (store != null) {
            store.hide(dialogId, hiddenDialog.account);
        }
        info("DialogDeleteTrace: local hide dialogId=" + dialogId + " account=" + hiddenDialog.account);
    }

    private boolean shouldPruneDialogDatabaseForCall(String methodName) {
        return "performDeleteOrClearDialogAction".equals(methodName);
    }

    private void pruneTelegramDialogDatabase(long dialogId, int account, String source) {
        if (dialogId == 0L) {
            return;
        }
        TelegramDialogDatabasePruner pruner = dialogDatabasePruner;
        if (pruner == null) {
            initAntiRecallDeferred();
            pruner = dialogDatabasePruner;
        }
        if (pruner == null) {
            info("DialogDatabasePrune: skipped, pruner unavailable dialogId=" + dialogId
                    + " account=" + account + " source=" + source);
            return;
        }
        pruner.pruneAsync(dialogId, account, source);
    }

    private void pruneKnownHiddenDialogs(Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs) {
        if (hiddenDialogs == null || hiddenDialogs.isEmpty()) {
            return;
        }
        TelegramDialogDatabasePruner pruner = dialogDatabasePruner;
        if (pruner == null) {
            return;
        }
        for (LocalDialogDeleteStore.HiddenDialog hiddenDialog : hiddenDialogs) {
            if (hiddenDialog == null || hiddenDialog.dialogId == 0L) {
                continue;
            }
            pruner.pruneAsync(hiddenDialog.dialogId, hiddenDialog.account, "startup-hidden");
        }
    }

    private int resolveDialogDeletionAccount(Object host) {
        int account = Reflect.asInt(Reflect.field(host, "currentAccount"), Integer.MIN_VALUE);
        if (account != Integer.MIN_VALUE) {
            return Math.max(0, account);
        }
        Object getter = Reflect.invokeIfExists(host, "getCurrentAccount", new Class<?>[0]);
        account = Reflect.asInt(getter, Integer.MIN_VALUE);
        if (account != Integer.MIN_VALUE) {
            return Math.max(0, account);
        }
        return resolveSelectedTelegramAccount(savedClassLoader);
    }

    private boolean isLocallyHiddenDialog(long dialogId, int account) {
        if (dialogId == 0L) {
            return false;
        }
        LocalDialogDeleteStore.HiddenDialog hiddenDialog =
                new LocalDialogDeleteStore.HiddenDialog(account, dialogId);
        if (locallyHiddenDialogs.contains(hiddenDialog)) {
            return true;
        }
        LocalDialogDeleteStore store = localDialogDeleteStore;
        if (store != null && store.isHidden(dialogId, hiddenDialog.account)) {
            locallyHiddenDialogs.add(hiddenDialog);
            return true;
        }
        return false;
    }

    private void refreshDialogsUi(Object host) {
        if (host == null) {
            return;
        }
        try {
            Object viewPages = Reflect.field(host, "viewPages");
            if (!(viewPages instanceof Object[])) {
                return;
            }
            for (Object page : (Object[]) viewPages) {
                Object listView = Reflect.field(page, "listView");
                Object adapter = listView instanceof View
                        ? Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0])
                        : null;
                if (listView instanceof ViewGroup) {
                    hideVisibleDialogRows((ViewGroup) listView, adapter);
                }
                if (listView instanceof View) {
                    Reflect.invokeIfExists(adapter, "notifyDataSetChanged", new Class<?>[0]);
                    ((View) listView).requestLayout();
                    ((View) listView).invalidate();
                    uiCallbacks.post((View) listView, () -> {
                        try {
                            Object delayedAdapter = Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0]);
                            if (listView instanceof ViewGroup) {
                                hideVisibleDialogRows((ViewGroup) listView, delayedAdapter);
                            }
                            Reflect.invokeIfExists(delayedAdapter, "notifyDataSetChanged", new Class<?>[0]);
                            ((View) listView).requestLayout();
                            ((View) listView).invalidate();
                        } catch (Throwable throwable) {
                            error("DialogDeleteTrace: delayed refresh dialogs UI failed", throwable);
                        }
                    });
                }
            }
        } catch (Throwable throwable) {
            error("DialogDeleteTrace: refresh dialogs UI failed", throwable);
        }
    }

    private void hideVisibleDialogRows(ViewGroup listView, Object adapter) {
        int account = resolveDialogDeletionAccount(adapter);
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (isDialogListRow(null, child)) {
                applyLocalDialogHideToRow(child, account);
            }
        }
    }

    static boolean isCommittedDialogDeleteCall(String methodName) {
        return "performDeleteOrClearDialogAction".equals(methodName);
    }

    private int firstIntArg(List<Object> args, int fallback) {
        if (args == null) {
            return fallback;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return fallback;
    }

    private boolean lastBooleanArg(List<Object> args, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        boolean value = fallback;
        for (Object arg : args) {
            if (arg instanceof Boolean) {
                value = (Boolean) arg;
            }
        }
        return value;
    }

    private List<Long> dialogIdsFromArgs(List<Object> args) {
        List<Long> dialogIds = new ArrayList<>();
        if (args == null) {
            return dialogIds;
        }
        for (Object arg : args) {
            if (arg instanceof Long) {
                dialogIds.add((Long) arg);
            } else if (arg instanceof List<?>) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Long) {
                        dialogIds.add((Long) item);
                    }
                }
            }
        }
        return dialogIds;
    }

    /**
     * Diagnostic hook to find the real selection field for download page.
     * Hooks SearchDownloadsContainer$ExternalSyntheticLambda2.invoke to dump
     * parentFragment and adapter fields before/after onItemClick.
     */
    private void hookOnItemClickDiagnostic(ClassLoader classLoader) {
        try {
            // Enumerate ALL inner classes of SearchDownloadsContainer
            Class<?> containerClass = classLoader.loadClass("org.telegram.ui.Components.SearchDownloadsContainer");
            info("SelectAll: found SearchDownloadsContainer, listing inner classes...");
            Class<?>[] innerClasses = containerClass.getDeclaredClasses();
            for (Class<?> inner : innerClasses) {
                info("SelectAll: inner class: " + inner.getSimpleName() + " -> " + inner.getName());
            }
            // Also list all declared methods
            for (Method m : containerClass.getDeclaredMethods()) {
                info("SelectAll: method: " + m.getName() + " params=" + m.getParameterCount());
            }
            // Hook lambda$new$1 (likely click listener) and lambda$new$0 (likely long-click)
            for (Method m : containerClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("lambda$new$0") || name.equals("lambda$new$1")) {
                    info("SelectAll: hooking " + name + " params=" + m.getParameterCount());
                    hook(m, chain -> {
                        try {
                            Object thisObj = chain.getThisObject();
                            info("SelectAll: >>> " + name + " called");
                            java.util.Map<String, Object> beforePF = dumpCollectionFields(thisObj, "before");
                            Object result = chain.proceed();
                            java.util.Map<String, Object> afterPF = dumpCollectionFields(thisObj, "after");
                            compareFields(beforePF, afterPF, name);
                            return result;
                        } catch (Throwable t) {
                            error("SelectAll: hook error on " + name, t);
                            return chain.proceed();
                        }
                    });
                }
            }
            // Also hook DownloadsAdapter methods
            try {
                Class<?> adapterClass = classLoader.loadClass("org.telegram.ui.Components.SearchDownloadsContainer$DownloadsAdapter");
                info("SelectAll: found DownloadsAdapter, listing methods...");
                for (Method m : adapterClass.getDeclaredMethods()) {
                    info("SelectAll: adapter method: " + m.getName() + " params=" + m.getParameterCount());
                }
                // Hook onBindViewHolder to dump adapter fields when binding
                for (Method m : adapterClass.getDeclaredMethods()) {
                    if (m.getName().equals("onBindViewHolder")) {
                        info("SelectAll: hooking adapter.onBindViewHolder");
                        hook(m, chain -> {
                            try {
                                Object adapter = chain.getThisObject();
                                info("SelectAll: >>> adapter.onBindViewHolder called");
                                dumpAllFieldsDeep(adapter, "adapter", 1);
                                return chain.proceed();
                            } catch (Throwable t) {
                                error("SelectAll: hook error on onBindViewHolder", t);
                                return chain.proceed();
                            }
                        });
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                info("SelectAll: DownloadsAdapter not found: " + e.getMessage());
            }
        } catch (Throwable t) {
            error("SelectAll: hookOnItemClickDiagnostic failed", t);
        }
    }

    /**
     * Dumps all Collection/Map fields from an object for diagnostic comparison.
     */
    private java.util.Map<String, Object> dumpCollectionFields(Object obj, String label) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (obj == null) return result;

        Class<?> clazz = obj.getClass();
        info("SelectAll: dumping fields for " + label + " class=" + clazz.getSimpleName());

        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof java.util.Collection || value instanceof java.util.Map) {
                        result.put(field.getName(), value);
                        int size = getCollectionSize(value);
                        info("SelectAll:   field=" + field.getName() + " type=" + value.getClass().getSimpleName() + " size=" + size);
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return result;
    }

    /**
     * Gets the size of a Collection or Map.
     */
    private int getCollectionSize(Object obj) {
        if (obj instanceof java.util.Collection) {
            return ((java.util.Collection<?>) obj).size();
        } else if (obj instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) obj).size();
        }
        return -1;
    }

    /**
     * Compares before/after field maps and logs any size changes.
     */
    private void compareFields(java.util.Map<String, Object> before, java.util.Map<String, Object> after, String label) {
        for (java.util.Map.Entry<String, Object> entry : before.entrySet()) {
            String fieldName = entry.getKey();
            Object beforeVal = entry.getValue();
            Object afterVal = after.get(fieldName);

            int beforeSize = getCollectionSize(beforeVal);
            int afterSize = getCollectionSize(afterVal);

            if (beforeSize != afterSize) {
                info("SelectAll: FIELD CHANGED in " + label + ": " + fieldName + " size " + beforeSize + " -> " + afterSize);
            }
        }
    }

    /**
     * Dumps ALL fields (not just Collection/Map) from an object up to a given depth.
     * For primitive/boxed types, logs their value. For objects, recurse.
     */
    private void dumpAllFieldsDeep(Object obj, String label, int maxDepth) {
        if (obj == null || maxDepth <= 0) return;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof java.util.Collection) {
                        int size = ((java.util.Collection<?>) value).size();
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + size + "]");
                    } else if (value instanceof java.util.Map) {
                        int size = ((java.util.Map<?, ?>) value).size();
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + size + "]");
                    } else if (value instanceof android.util.LongSparseArray) {
                        info("SelectAll:   " + label + "." + field.getName() + "=LongSparseArray[" + ((android.util.LongSparseArray<?>) value).size() + "]");
                    } else if (value instanceof android.util.SparseArray) {
                        info("SelectAll:   " + label + "." + field.getName() + "=SparseArray[" + ((android.util.SparseArray<?>) value).size() + "]");
                    } else if (value != null && field.getType().isArray()) {
                        int len = java.lang.reflect.Array.getLength(value);
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + len + "]");
                    } else if (value instanceof String) {
                        info("SelectAll:   " + label + "." + field.getName() + " "
                                + LogPrivacy.field("value", (String) value));
                    } else if (value instanceof Number || value instanceof Boolean) {
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value);
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void syncPersistentDownloadButton(Object activity, ViewGroup actionBar, String reason) {
        if (activity == null || actionBar == null || retiring) {
            return;
        }
        if (shouldKeepDownloadButtonVisible(activity)) {
            enforceNativeDownloadButtonVisibility(activity, actionBar, reason);
        } else {
            restoreNativeDownloadButtonVisibility(activity, reason);
        }
    }

    private boolean shouldKeepDownloadButtonVisible(Object activity) {
        Boolean cached = keepDownloadButtonVisibleEnabled;
        if (cached != null) {
            return cached;
        }
        Context context = contextFromSettingsHost(activity);
        if (context == null || configProvider == null) {
            return false;
        }
        boolean enabled = isEnhancementEnabled(
                context,
                EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE
        );
        keepDownloadButtonVisibleEnabled = enabled;
        return enabled;
    }

    private void enforceNativeDownloadButtonVisibility(
            Object activity,
            ViewGroup actionBar,
            String reason
    ) {
        View item = nativeDownloadItem(activity, actionBar);
        if (item == null) {
            info("PersistentDownloadButton: native item unavailable reason=" + reason);
            return;
        }
        Reflect.setField(activity, "downloadsItemVisible", true);
        boolean refreshed = invokeNoArg(activity, "checkUi_itemDownloadsVisibility");
        if (!refreshed) {
            item.setVisibility(View.VISIBLE);
            item.setAlpha(1f);
            item.setScaleX(1f);
            item.setScaleY(1f);
        }
        boolean first;
        synchronized (persistentDownloadButtonHosts) {
            first = persistentDownloadButtonHosts.add(activity);
        }
        if (first || !"lifecycle".equals(reason)) {
            info("PersistentDownloadButton: native item enabled reason=" + reason
                    + " visibility=" + item.getVisibility()
                    + " alpha=" + item.getAlpha()
                    + " controller=" + (refreshed ? "native" : "view-fallback"));
        }
    }

    private void restoreNativeDownloadButtonVisibility(Object activity, String reason) {
        boolean owned;
        synchronized (persistentDownloadButtonHosts) {
            owned = persistentDownloadButtonHosts.remove(activity);
        }
        if (!owned) {
            return;
        }
        boolean restored = invokeTwoBooleanArgs(activity, "updateProxyButton", false, true);
        info("PersistentDownloadButton: native ownership restored reason=" + reason
                + " controller=" + (restored ? "native" : "deferred"));
    }

    private View nativeDownloadItem(Object activity, ViewGroup actionBar) {
        Object direct = Reflect.field(activity, "downloadsItem");
        if (direct instanceof View) {
            return (View) direct;
        }
        return findDownloadButton(actionBar);
    }

    private boolean invokeNoArg(Object target, String methodName) {
        try {
            Method method = Reflect.method(target.getClass(), methodName);
            Reflect.invoke(method, target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeTwoBooleanArgs(
            Object target,
            String methodName,
            boolean first,
            boolean second
    ) {
        try {
            Method method = Reflect.method(target.getClass(), methodName, boolean.class, boolean.class);
            Reflect.invoke(method, target, first, second);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshPersistentDownloadButtonUi(String reason) {
        Object activity = activeDialogsActivity.get();
        Object actionBar = Reflect.field(activity, "actionBar");
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) actionBar;
        uiCallbacks.post(bar, () -> syncPersistentDownloadButton(activity, bar, reason));
    }

    private ViewGroup findActionBarMenu(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu") && child instanceof ViewGroup) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    /**
     * Installs an {@link ViewGroup.OnHierarchyChangeListener} on the ActionBarMenu
     * so we detect action mode (selection mode) the moment Telegram adds its
     * action-mode views. When a numeric TextView (selection count) appears,
     * we inject a "Select All" button into the same menu.
     */
    private void installActionModeDetector(ViewGroup actionBar) {
        ViewGroup menu = findActionBarMenu(actionBar);
        if (menu == null) {
            info("SelectAll: ActionBarMenu not found for detector");
            return;
        }
        info("SelectAll: installing action mode detector on ActionBarMenu children=" + menu.getChildCount());
        if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            if (downloadPageFragmentView != null) {
                injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
            } else {
                injectSelectAllIntoActionMode(menu);
            }
        }
        uiCallbacks.setHierarchyListener(menu, new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    View existing = menu.findViewWithTag(MENU_ID_SELECT_ALL);
                    if (existing != null && existing.hasOnClickListeners()) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        uiCallbacks.post(menu, () -> {
                            try {
                                // Check if we're on the download page. Both helpers also
                                // rebind callbacks when the tagged view survived a hot reload.
                                if (downloadPageFragmentView != null) {
                                    info("SelectAll: on download page, injecting Select All into action mode");
                                    injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
                                } else {
                                    injectSelectAllIntoActionMode(menu);
                                }
                            } catch (Throwable t) {
                                error("SelectAll: delayed inject failed", t);
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    error("SelectAll: hierarchy listener error", t);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        // Also check existing children in case action mode is already active
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (isActionModeIndicator(menu.getChildAt(i))) {
                info("SelectAll: action mode already active, injecting now");
                if (downloadPageFragmentView != null) {
                    injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
                } else {
                    injectSelectAllIntoActionMode(menu);
                }
                return;
            }
        }
    }

    /**
     * After the download page loads, polls for action mode activation.
     * When action mode buttons (转到消息, 转发, 删除) appear, injects "Select All".
     */
    private void installActionModeDetectorOnDownloadPage(ViewGroup actionBar, Object activity) {
        View fragmentView = resolveFragmentView(activity);
        if (!(fragmentView instanceof ViewGroup)) {
            info("SelectAll: no fragmentView for download page");
            return;
        }
        java.util.List<View> containers = new java.util.ArrayList<>();
        findAllViewsByClassName((ViewGroup) fragmentView, "SearchDownloadsContainer", containers, 0);
        if (containers.isEmpty()) {
            info("SelectAll: not on download page");
            return;
        }
        info("SelectAll: download page detected, polling for action mode");
        ViewGroup fragmentViewRef = (ViewGroup) fragmentView;
        // Poll for action mode buttons
        uiCallbacks.post(actionBar, new Runnable() {
            @Override
            public void run() {
                try {
                    if (!actionBar.isAttachedToWindow()) return;
                    // Check ActionBar itself for action mode buttons
                    boolean actionModeActive = false;
                    ViewGroup actionModeMenu = null;
                    for (int i = 0; i < actionBar.getChildCount(); i++) {
                        View child = actionBar.getChildAt(i);
                        if (child instanceof ViewGroup) {
                            ViewGroup group = (ViewGroup) child;
                            for (int j = 0; j < group.getChildCount(); j++) {
                                View grandchild = group.getChildAt(j);
                                String desc = grandchild.getContentDescription() != null ? grandchild.getContentDescription().toString() : "";
                                if (desc.contains("转到消息") || desc.contains("删除") || desc.contains("转发") ||
                                    desc.contains("Go to") || desc.contains("Delete") || desc.contains("Forward")) {
                                    actionModeActive = true;
                                    actionModeMenu = group;
                                    break;
                                }
                            }
                        }
                    }
                    if (actionModeActive && actionModeMenu != null) {
                        View existing = actionModeMenu.findViewWithTag(MENU_ID_SELECT_ALL);
                        if (existing == null || !existing.hasOnClickListeners()) {
                            info("SelectAll: action mode detected, injecting or rebinding Select All");
                            injectSelectAllIntoActionModeForDownload(actionModeMenu, fragmentViewRef);
                        }
                    }
                    uiCallbacks.post(actionBar, this, 500);
                } catch (Throwable t) {
                    error("SelectAll: poll error", t);
                    uiCallbacks.post(actionBar, this, 1000);
                }
            }
        }, 500);
    }

    private boolean bindDownloadSelectAll(Object pager, String reason) {
        if (pager == null || retiring) {
            return false;
        }
        Object parent = Reflect.field(pager, "parent");
        if (parent != null) {
            activeDialogsActivity = new WeakReference<>(parent);
        }
        Object container = Reflect.invokeIfExists(
                pager, "getDownloadsContainer", new Class<?>[0]);
        Object current = Reflect.invokeIfExists(pager, "getCurrentView", new Class<?>[0]);
        boolean showing = Boolean.TRUE.equals(
                Reflect.invokeIfExists(pager, "actionModeShowing", new Class<?>[0]));
        Object actionMode = Reflect.invokeIfExists(
                pager, "getActionMode", new Class<?>[0]);
        boolean currentDownloads = container != null && current == container;
        if (!showing || !currentDownloads
                || !(container instanceof ViewGroup)
                || !(actionMode instanceof ViewGroup)) {
            return false;
        }
        injectSelectAllIntoActionModeForDownload(
                (ViewGroup) actionMode,
                (ViewGroup) container
        );
        info("SelectAll: bound native download action mode reason=" + reason);
        return true;
    }

    /**
     * Injects "Select All" button next to the action buttons (like delete)
     * that appear when selection mode is active.
     */
    private void injectSelectAllNextToActionButton(ViewGroup menu, ViewGroup actionBar, ViewGroup fragmentView) {
        View button = menu.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            Context context = actionBar.getContext();
            CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(label);
            created.setTextSize(14);
            created.setTextColor(0xFFFFFFFF);
            created.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            created.setGravity(android.view.Gravity.CENTER);
            created.setBackgroundColor(0x33FFFFFF);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // Inject into ActionBarMenu (not ActionBar container) so it appears
            // alongside the action buttons (delete, forward, etc.)
            int insertIndex = menu.getChildCount();
            menu.addView(created, insertIndex, lp);
            menu.invalidate();
            menu.requestLayout();
            info("SelectAll: injected into ActionBarMenu at index=" + insertIndex
                    + " children=" + menu.getChildCount());
            button = created;
        }
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                info("SelectAll: download page Select All clicked");
                selectAllDownloadItems(fragmentView);
            } catch (Throwable t) {
                error("SelectAll: download page select all failed", t);
            }
        });
    }

    /**
     * Selects all download items by directly writing message IDs to
     * parentFragment.selectedDialogs and refreshing the adapter.
     */


    private void selectAllDownloadItems(ViewGroup fragmentView) {
        java.util.List<View> containers = new java.util.ArrayList<>();
        if (fragmentView.getClass().getSimpleName().contains("SearchDownloadsContainer")) {
            containers.add(fragmentView);
        }
        findAllViewsByClassName(fragmentView, "SearchDownloadsContainer", containers, 0);
        if (containers.isEmpty()) {
            info("SelectAll: SearchDownloadsContainer not found");
            return;
        }
        Object container = containers.get(0);
        Object adapter = Reflect.field(container, "adapter");
        if (adapter == null) {
            info("SelectAll: no adapter on SearchDownloadsContainer");
            return;
        }
        info("SelectAll: adapter=" + adapter.getClass().getSimpleName());

        // Get ALL items from adapter (not just visible cells)
        Object adapterObj = adapter;
        Integer itemCount = Reflect.asInt(Reflect.invokeIfExists(adapterObj, "getItemCount", new Class<?>[0]), 0);
        info("SelectAll: adapter has " + itemCount + " items total");
        if (itemCount <= 0) return;

        // Find getMessage method on adapter
        java.lang.reflect.Method getMessageMethod = null;
        for (java.lang.reflect.Method m : adapterObj.getClass().getDeclaredMethods()) {
            if (m.getName().equals("getMessage") && m.getParameterCount() == 1) {
                getMessageMethod = m;
                getMessageMethod.setAccessible(true);
                break;
            }
        }
        if (getMessageMethod == null) {
            info("SelectAll: getMessage not found on adapter");
            return;
        }

        // Find uiCallback (SearchViewPager) from the container
        // SearchDownloadsContainer delegates all selection to uiCallback.toggleItemSelection()
        Object uiCallback = Reflect.field(container, "uiCallback");
        if (uiCallback == null) {
            info("SelectAll: uiCallback not found on container");
            return;
        }
        info("SelectAll: uiCallback class=" + uiCallback.getClass().getSimpleName());

        // Find toggleItemSelection method by searching the class hierarchy
        java.lang.reflect.Method toggleMethod = null;
        Class<?> cl = uiCallback.getClass();
        while (cl != null && cl != Object.class) {
            for (java.lang.reflect.Method m : cl.getDeclaredMethods()) {
                if (m.getName().equals("toggleItemSelection") && m.getParameterCount() == 3) {
                    toggleMethod = m;
                    toggleMethod.setAccessible(true);
                    info("SelectAll: found toggleItemSelection on " + cl.getSimpleName() + " params=" + m.getParameterTypes()[0].getSimpleName() + "," + m.getParameterTypes()[1].getSimpleName() + "," + m.getParameterTypes()[2].getSimpleName());
                    break;
                }
            }
            if (toggleMethod != null) break;
            cl = cl.getSuperclass();
        }
        // Also check interfaces
        if (toggleMethod == null) {
            for (Class<?> iface : uiCallback.getClass().getInterfaces()) {
                for (java.lang.reflect.Method m : iface.getDeclaredMethods()) {
                    if (m.getName().equals("toggleItemSelection") && m.getParameterCount() == 3) {
                        toggleMethod = m;
                        toggleMethod.setAccessible(true);
                        info("SelectAll: found toggleItemSelection on interface " + iface.getSimpleName());
                        break;
                    }
                }
                if (toggleMethod != null) break;
            }
        }

        if (toggleMethod == null) {
            info("SelectAll: toggleItemSelection not found, dumping all methods");
            for (java.lang.reflect.Method m : uiCallback.getClass().getDeclaredMethods()) {
                info("SelectAll: uiCallback method: " + m.getName() + "(" + m.getParameterCount() + ")");
            }
            return;
        }

        // Find selectedFiles on uiCallback to know which items are already selected
        java.util.Set<Object> alreadySelectedIds = new java.util.HashSet<>();
        Object selectedFiles = Reflect.field(uiCallback, "selectedFiles");
        if (selectedFiles instanceof java.util.Map) {
            java.util.Map<?, ?> sf = (java.util.Map<?, ?>) selectedFiles;
            info("SelectAll: selectedFiles has " + sf.size() + " entries");
            // Extract message IDs from the keys - dump key fields
            for (Object key : sf.keySet()) {
                Class<?> kc = key.getClass();
                while (kc != null && kc != Object.class) {
                    for (java.lang.reflect.Field f : kc.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(key);
                            info("SelectAll: key." + f.getName() + "=" + val);
                            if (val instanceof Integer) alreadySelectedIds.add(val);
                        } catch (Throwable ignored) {}
                    }
                    kc = kc.getSuperclass();
                }
            }
            info("SelectAll: already selected IDs: " + alreadySelectedIds.size());
        }

        // Build a map of visible message ID -> SharedDocumentCell view
        java.util.Map<Integer, View> visibleViews = new java.util.HashMap<>();
        java.util.List<View> cells = new java.util.ArrayList<>();
        findAllViewsByClassName(fragmentView, "SharedDocumentCell", cells, 0);
        for (View cell : cells) {
            if (cell.getVisibility() != View.VISIBLE) continue;
            Object msg = Reflect.field(cell, "message");
            if (msg == null) continue;
            Object idObj = Reflect.invokeIfExists(msg, "getId", new Class<?>[0]);
            if (idObj instanceof Integer) visibleViews.put((Integer) idObj, cell);
        }
        info("SelectAll: " + visibleViews.size() + " visible cells mapped");

        // Call toggleItemSelection for ALL adapter items, SKIP already-selected
        // For visible items, pass the actual view so checkmark appears
        int selected = 0;
        for (int i = 0; i < itemCount; i++) {
            Object message;
            try {
                message = getMessageMethod.invoke(adapterObj, i);
            } catch (Throwable ignored) { continue; }
            if (message == null) continue;
            Object msgIdObj = Reflect.invokeIfExists(message, "getId", new Class<?>[0]);
            int msgId = msgIdObj instanceof Integer ? (Integer) msgIdObj : 0;
            if (alreadySelectedIds.contains(msgId)) continue;
            View cellView = visibleViews.get(msgId);
            try {
                toggleMethod.invoke(uiCallback, message, cellView, 0);
                selected++;
            } catch (Throwable t) {
                info("SelectAll: toggle error at " + i + ": " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage()));
            }
        }
        info("SelectAll: " + selected + " new items selected, " + alreadySelectedIds.size() + " preserved");
    }

    /**
     * Installs the hierarchy change detector on a specific ActionBarMenu ViewGroup.
     */
    private void installHierarchyDetector(ViewGroup menu) {
        if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            injectSelectAllIntoActionMode(menu);
        }
        uiCallbacks.setHierarchyListener(menu, new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    View existing = menu.findViewWithTag(MENU_ID_SELECT_ALL);
                    if (existing != null && existing.hasOnClickListeners()) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        uiCallbacks.post(menu, () -> {
                            try {
                                injectSelectAllIntoActionMode(menu);
                            } catch (Throwable t) {
                                error("SelectAll: delayed inject failed", t);
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    error("SelectAll: hierarchy listener error", t);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        // Check existing children
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (isActionModeIndicator(menu.getChildAt(i))) {
                info("SelectAll: action mode already active on fragment menu, injecting now");
                injectSelectAllIntoActionMode(menu);
                return;
            }
        }
    }

    /**
     * Returns true if the view looks like a Telegram action mode indicator.
     * Typical indicators: a TextView showing the selection count (e.g. "1", "5"),
     * or a close/back button that only appears in action mode.
     */
    private boolean isActionModeIndicator(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString().trim();
            if (text.matches("\\d+")) {
                return true;
            }
        }
        // Check if it's a ViewGroup containing a numeric TextView (nested indicator)
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    String text = ((TextView) child).getText().toString().trim();
                    if (text.matches("\\d+")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void findAllViewsByClassName(ViewGroup group, String nameFragment, java.util.List<View> result, int depth) {
        if (depth > 15) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains(nameFragment)) {
                result.add(child);
            }
            if (child instanceof ViewGroup) {
                findAllViewsByClassName((ViewGroup) child, nameFragment, result, depth + 1);
            }
        }
    }

    private View findSubItemDeep(ViewGroup group, int depth) {
        if (depth > 5) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenuSubItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findSubItemDeep((ViewGroup) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findDownloadButton(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                return item;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private View forceDownloadButtonVisible(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                View downloadBtn = item;
                                // 诊断日志：输出按钮状态
                                int vis = downloadBtn.getVisibility();
                                int w = downloadBtn.getWidth();
                                int h = downloadBtn.getHeight();
                                ViewGroup.LayoutParams lp = downloadBtn.getLayoutParams();
                                info("SelectAll: downloadBtn vis=" + vis + " w=" + w + " h=" + h + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null"));
                                if (lp != null) {
                                    info("SelectAll: lp.width=" + lp.width + " lp.height=" + lp.height);
                                }
                                // 检查父容器
                                ViewParent parent = downloadBtn.getParent();
                                if (parent instanceof ViewGroup) {
                                    ViewGroup parentVg = (ViewGroup) parent;
                                    int parentVis = parentVg.getVisibility();
                                    int parentW = parentVg.getWidth();
                                    int parentH = parentVg.getHeight();
                                    info("SelectAll: parent vis=" + parentVis + " w=" + parentW + " h=" + parentH + " class=" + parentVg.getClass().getSimpleName());
                                }
                                // 强制设置尺寸并显示
                                downloadBtn.setVisibility(View.VISIBLE);
                                if (lp != null && (lp.width <= 0 || lp.height <= 0)) {
                                    int size = dp(downloadBtn.getContext(), 48);
                                    lp.width = size;
                                    lp.height = size;
                                    downloadBtn.setLayoutParams(lp);
                                    info("SelectAll: forced size to " + size);
                                }
                                return item;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void makeDownloadButtonVisible(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                View downloadBtn = item;
                                // 诊断日志：输出按钮状态
                                int vis = downloadBtn.getVisibility();
                                int w = downloadBtn.getWidth();
                                int h = downloadBtn.getHeight();
                                ViewGroup.LayoutParams lp = downloadBtn.getLayoutParams();
                                info("SelectAll: downloadBtn vis=" + vis + " w=" + w + " h=" + h + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null"));
                                if (lp != null) {
                                    info("SelectAll: lp.width=" + lp.width + " lp.height=" + lp.height);
                                }
                                // 检查父容器
                                ViewParent parent = downloadBtn.getParent();
                                if (parent instanceof ViewGroup) {
                                    ViewGroup parentVg = (ViewGroup) parent;
                                    int parentVis = parentVg.getVisibility();
                                    int parentW = parentVg.getWidth();
                                    int parentH = parentVg.getHeight();
                                    info("SelectAll: parent vis=" + parentVis + " w=" + parentW + " h=" + parentH + " class=" + parentVg.getClass().getSimpleName());
                                }
                                // 强制设置尺寸并显示
                                downloadBtn.setVisibility(View.VISIBLE);
                                if (lp != null && (lp.width <= 0 || lp.height <= 0)) {
                                    int size = dp(downloadBtn.getContext(), 48);
                                    lp.width = size;
                                    lp.height = size;
                                    downloadBtn.setLayoutParams(lp);
                                    info("SelectAll: forced size to " + size);
                                }
                                downloadBtn.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                                    if (v.getVisibility() != View.VISIBLE) {
                                        info("SelectAll: download button hidden! Setting back to VISIBLE");
                                        v.setVisibility(View.VISIBLE);
                                    }
                                });
                                info("SelectAll: attached layout listener and set VISIBLE");
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private void dumpTextViews(ViewGroup group, String label, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (!text.isEmpty()) {
                    info("SelectAll: " + label + "[" + depth + "][" + i + "] "
                            + LogPrivacy.field("text", text));
                }
            }
            if (child instanceof ViewGroup) {
                dumpTextViews((ViewGroup) child, label, depth + 1, maxDepth);
            }
        }
    }

    private void dumpViewTree(View view, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        String name = view.getClass().getSimpleName();
        String extra = "";
        if (view instanceof TextView) {
            extra = " " + LogPrivacy.field("text", String.valueOf(((TextView) view).getText()));
        }
        info("SelectAll: " + spaces(depth) + name + extra + " vis=" + view.getVisibility() + " w=" + view.getWidth() + " h=" + view.getHeight());
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViewTree(vg.getChildAt(i), depth + 1, maxDepth);
            }
        }
    }

    private String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    private void dumpMenuItems(ViewGroup menu) {
        info("SelectAll: ActionBarMenu children=" + menu.getChildCount());
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            String name = child.getClass().getSimpleName();
            String text = "";
            if (child instanceof TextView) {
                text = " " + LogPrivacy.field("text", String.valueOf(((TextView) child).getText()));
            }
            int id = child.getId();
            Object tag = child.getTag();
            info("SelectAll: menu[" + i + "]=" + name + text + " vis=" + child.getVisibility() + " id=" + id + " tag=" + tag);
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View sub = vg.getChildAt(j);
                    String subName = sub.getClass().getSimpleName();
                    String subText = "";
                    if (sub instanceof TextView) {
                        subText = " " + LogPrivacy.field("text", String.valueOf(((TextView) sub).getText()));
                    }
                    info("SelectAll:   [" + j + "]=" + subName + subText + " vis=" + sub.getVisibility());
                }
            }
        }
    }

    private void injectSelectAllIntoContentView(View actionBar) {
        if (!(actionBar instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) actionBar;
        View button = bar.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            if (bar.getChildCount() < 5) return;
            View contentView = bar.getChildAt(4);
            if (!(contentView instanceof ViewGroup)) return;
            ViewGroup content = (ViewGroup) contentView;
            info("SelectAll: ActionBar content children=" + content.getChildCount());
            for (int i = 0; i < content.getChildCount(); i++) {
                View c = content.getChildAt(i);
                String txt = "";
                if (c instanceof TextView) {
                    txt = " " + LogPrivacy.field("text", String.valueOf(((TextView) c).getText()));
                }
                info("SelectAll:   [" + i + "]=" + c.getClass().getSimpleName() + txt
                        + " vis=" + c.getVisibility() + " w=" + c.getWidth());
            }
            Context context = content.getContext();
            CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(label);
            created.setTextSize(14);
            created.setTextColor(0xFFFFFFFF);
            created.setPadding(dp(context, 16), 0, dp(context, 16), 0);
            created.setGravity(android.view.Gravity.CENTER);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            int insertIdx = 2;
            for (int i = 0; i < content.getChildCount(); i++) {
                View c = content.getChildAt(i);
                if (c.getClass().getSimpleName().contains("Number")
                        || (c instanceof TextView && c.getWidth() > 200)) {
                    insertIdx = i + 1;
                    break;
                }
            }
            content.addView(created, insertIdx, lp);
            info("SelectAll: inserted at index " + insertIdx);
            button = created;
        }
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                info("SelectAll: clicked!");
                if (parent != null) selectAllFromContentView(parent);
            } catch (Throwable t) {
                error("Select all failed", t);
            }
        });
    }

    private View findViewByText(ViewGroup group, String text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().contains(text)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByText((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasTextViewWith(View view, String targetText) {
        if (view instanceof TextView) {
            return ((TextView) view).getText().toString().contains(targetText);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasTextViewWith(group.getChildAt(i), targetText)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void selectAllFromContentView(ViewGroup contentView) {
        View listView = findListViewDeep(contentView, 0);
        if (!(listView instanceof ViewGroup)) {
            info("SelectAll: no list view found");
            return;
        }
        ViewGroup rv = (ViewGroup) listView;
        Object longClickListener = Reflect.field(rv, "mOnItemLongClickListener");
        if (longClickListener == null) {
            Object adapter = Reflect.invokeIfExists(rv, "getAdapter", new Class<?>[0]);
            Object hostFragment = adapter != null ? Reflect.field(adapter, "this$0") : null;
            if (hostFragment != null) {
                longClickListener = hostFragment;
            }
        }
        if (longClickListener == null) {
            info("SelectAll: no longClickListener");
            return;
        }
        Object adapter = Reflect.invokeIfExists(rv, "getAdapter", new Class<?>[0]);
        if (adapter == null) {
            info("SelectAll: no adapter");
            return;
        }
        Object itemInternals = Reflect.field(adapter, "itemInternals");
        if (!(itemInternals instanceof java.util.ArrayList)) {
            info("SelectAll: no itemInternals");
            return;
        }
        @SuppressWarnings("unchecked")
        java.util.ArrayList<?> items = (java.util.ArrayList<?>) itemInternals;
        int selected = 0;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item == null) continue;
            Object dialog = Reflect.field(item, "dialog");
            if (dialog == null) continue;
            long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
            if (dialogId == 0) continue;
            View childView = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
            Reflect.invokeIfExists(longClickListener, "addOrRemoveSelectedDialog", new Class<?>[]{long.class, View.class}, dialogId, childView);
            selected++;
        }
        View firstChild = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
        if (firstChild == null) return;
        Object holder = Reflect.invokeIfExists(rv, "getChildViewHolder", new Class<?>[]{View.class}, firstChild);
        int pos = holder != null ? Reflect.asInt(Reflect.invokeIfExists(holder, "getLayoutPosition", new Class<?>[0]), 0) : 0;
        Object result = null;
        try {
            Class<?> rlvClass = Class.forName("org.telegram.ui.Components.RecyclerListView");
            result = Reflect.invokeIfExists(longClickListener, "onItemLongClick",
                    new Class<?>[]{rlvClass, View.class, int.class, float.class, float.class, int.class, androidx.recyclerview.widget.RecyclerView.Adapter.class},
                    rv, firstChild, pos, 0f, 0f, 0, adapter);
        } catch (ClassNotFoundException ignored) {}
        info("SelectAll: onItemLongClick result=" + result);
        Object selectedDialogs = Reflect.field(adapter, "selectedDialogs");
        if (selectedDialogs instanceof java.util.ArrayList) {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Long> list = (java.util.ArrayList<Long>) selectedDialogs;
            for (int i = 1; i < items.size(); i++) {
                Object item = items.get(i);
                if (item == null) continue;
                Object dialog = Reflect.field(item, "dialog");
                if (dialog == null) continue;
                long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                if (dialogId != 0 && !list.contains(dialogId)) {
                    list.add(dialogId);
                }
            }
            Reflect.invokeIfExists(longClickListener, "updateSelectedCount", new Class<?>[0]);
            info("SelectAll: selectedDialogs.size=" + list.size());
        }
    }

    private void dumpViewHierarchy(ViewGroup group, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getSimpleName();
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 3) {
                info("SelectAll: d=" + depth + " i=" + i + " " + name + " children=" + ((ViewGroup) child).getChildCount() + " h=" + child.getHeight());
            }
            if (child instanceof ViewGroup) {
                dumpViewHierarchy((ViewGroup) child, depth + 1, maxDepth);
            }
        }
    }

    private View findListViewDeep(ViewGroup group, int depth) {
        if (depth > 12) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getSimpleName();
            if (name.contains("Recycler") || name.contains("ListView") || name.contains("yclerList")) {
                info("SelectAll: d=" + depth + " found " + name + " children=" + (child instanceof ViewGroup ? ((ViewGroup) child).getChildCount() : 0));
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findListViewDeep((ViewGroup) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findViewByClassName(ViewGroup group, String nameSuffix, int depth) {
        if (depth > 10) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains(nameSuffix)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByClassName((ViewGroup) child, nameSuffix, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void injectSelectAllMenu(Object activity) {
        info("SelectAll: entering injectSelectAllMenu for " + activity.getClass().getSimpleName());
        Object actionBar = Reflect.field(activity, "actionBar");
        info("SelectAll: actionBar=" + (actionBar == null ? "null" : actionBar.getClass().getName()));
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) actionBar;
        View button = bar.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            Context context = bar.getContext();
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(isChineseLocale(context) ? "全选" : "Select All");
            created.setTextSize(14);
            created.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            created.setGravity(android.view.Gravity.CENTER);
            bar.addView(created, 0);
            info("SelectAll: injected button into " + activity.getClass().getSimpleName()
                    + " actionBar");
            button = created;
        }
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                selectAllDownloadItems(activity);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
    }

    private void injectSelectAllIntoActionMode(Object menu) {
        if (!(menu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menuView = (ViewGroup) menu;
        View button = menuView.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            Context context = menuView.getContext();
            CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(label);
            created.setTextSize(14);
            created.setTextColor(0xFFFFFFFF);
            created.setPadding(dp(context, 16), 0, dp(context, 16), 0);
            created.setGravity(android.view.Gravity.CENTER);
            menuView.addView(created);
            info("SelectAll: injected into action mode bar");
            button = created;
        }
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                selectAllInActionMode(menuView);
            } catch (Throwable throwable) {
                error("Select all in action mode failed", throwable);
            }
        });
    }

    /**
     * Injects "Select All" button into the action mode bar for the download page.
     * When clicked, it selects all download items.
     */
    private void injectSelectAllIntoActionModeForDownload(ViewGroup menuView, ViewGroup fragmentView) {
        View button = menuView.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            Context context = menuView.getContext();
            CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(label);
            created.setTextSize(14);
            created.setTextColor(0xFFFFFFFF);
            created.setPadding(dp(context, 16), 0, dp(context, 16), 0);
            created.setGravity(android.view.Gravity.CENTER);
            // No background - match the delete button style
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            menuView.addView(created, lp);
            info("SelectAll: injected into action mode bar for download page");
            button = created;
        }
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                info("SelectAll: download page Select All clicked in action mode");
                selectAllDownloadItems(fragmentView);
            } catch (Throwable t) {
                error("SelectAll: download select all failed: " + t.getMessage(), t);
            }
        });
    }

    private void selectAllInActionMode(ViewGroup menuView) {
        // Walk up from the ActionBarMenu to find the chat RecyclerView
        Object parent = menuView.getParent();
        RecyclerView recyclerView = null;
        while (parent instanceof View) {
            if (parent instanceof ViewGroup) {
                recyclerView = findRecyclerView((ViewGroup) parent);
                if (recyclerView != null) {
                    break;
                }
            }
            parent = ((View) parent).getParent();
        }
        if (recyclerView == null) {
            info("SelectAll: RecyclerView not found in action mode");
            return;
        }

        // Get adapter to access ChatActivity
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) {
            info("SelectAll: adapter is null");
            return;
        }
        info("SelectAll: adapter class=" + adapter.getClass().getSimpleName());

        // Find ChatActivity instance via adapter.this$0
        Object chatActivity = Reflect.field(adapter, "this$0");
        if (chatActivity == null) {
            info("SelectAll: chatActivity not found via this$0, trying other fields");
            chatActivity = Reflect.field(adapter, "fragment");
        }
        if (chatActivity == null) {
            info("SelectAll: chatActivity not found, dumping adapter fields");
            dumpAllFieldsDeep(adapter, "adapter", 1);
            fallbackSelectAll(recyclerView);
            return;
        }
        info("SelectAll: chatActivity class=" + chatActivity.getClass().getSimpleName());

        // Find the messages list on ChatActivity
        Object messagesObj = Reflect.field(chatActivity, "messages");
        if (!(messagesObj instanceof java.util.ArrayList)) {
            info("SelectAll: messages field not found, dumping chatActivity fields");
            dumpAllFieldsDeep(chatActivity, "chatActivity", 1);
            fallbackSelectAll(recyclerView);
            return;
        }

        // Find selectedIds on ChatActivity
        Object selectedIdsObj = findSelectionField(chatActivity);
        if (!(selectedIdsObj instanceof java.util.ArrayList)) {
            info("SelectAll: selectedIds not found as ArrayList, dumping collection fields");
            dumpCollectionFields(chatActivity, "chatActivity");
            dumpAllFieldsDeep(chatActivity, "chatActivity", 1);
            fallbackSelectAll(recyclerView);
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.ArrayList<Integer> selectedIds = (java.util.ArrayList<Integer>) selectedIdsObj;
        @SuppressWarnings("unchecked")
        java.util.ArrayList<?> messages = (java.util.ArrayList<?>) messagesObj;
        int initialSize = selectedIds.size();

        // Add all message IDs to selectedIds
        int added = 0;
        for (Object msg : messages) {
            if (msg == null) continue;
            Object idObj = Reflect.invokeIfExists(msg, "getId", new Class<?>[0]);
            int id = idObj instanceof Integer ? (Integer) idObj : 0;
            if (id > 0 && !selectedIds.contains(id)) {
                selectedIds.add(id);
                added++;
            }
        }
        info("SelectAll: added " + added + " IDs, selectedIds " + initialSize + " -> " + selectedIds.size());

        // Update the action bar counter
        updateActionBarSelectionCount(chatActivity, selectedIds.size());

        // Refresh adapter to sync visual check state with data model
        adapter.notifyDataSetChanged();

        // Force re-bind visible cells to update checkmarks
        final RecyclerView rv = recyclerView;
        uiCallbacks.post(rv, () -> {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                if (child != null) {
                    child.invalidate();
                }
            }
        });
    }

    /**
     * Finds the selection tracking field (selectedIds, etc.) on ChatActivity.
     */
    private Object findSelectionField(Object chatActivity) {
        String[] fieldNames = {"selectedIds", "selectedMessagesIds", "selectedMessages", "selectedObjectIds"};
        for (String name : fieldNames) {
            Object field = Reflect.field(chatActivity, name);
            if (field instanceof java.util.ArrayList) {
                info("SelectAll: found selection field '" + name + "' size=" + ((java.util.ArrayList<?>) field).size());
                return field;
            }
        }
        // Broader search: find any ArrayList<Integer> field on ChatActivity
        Class<?> clazz = chatActivity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (java.util.ArrayList.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(chatActivity);
                        if (value instanceof java.util.ArrayList) {
                            java.util.ArrayList<?> list = (java.util.ArrayList<?>) value;
                            if (!list.isEmpty() && list.get(0) instanceof Integer) {
                                info("SelectAll: found ArrayList<Integer> field '" + field.getName() + "' size=" + list.size());
                                return value;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Updates the action bar selection count display.
     */
    private void updateActionBarSelectionCount(Object chatActivity, int count) {
        // Try updateSelectedCount() with no args
        Object result = Reflect.invokeIfExists(chatActivity, "updateSelectedCount", new Class<?>[0]);
        if (result != null) {
            info("SelectAll: updateSelectedCount() succeeded");
            return;
        }
        // Try updateSelectedCount(int)
        result = Reflect.invokeIfExists(chatActivity, "updateSelectedCount", new Class<?>[]{int.class}, count);
        if (result != null) {
            info("SelectAll: updateSelectedCount(int) succeeded");
            return;
        }
        // Try showOrUpdateActionMode on ChatActivity
        result = Reflect.invokeIfExists(chatActivity, "showOrUpdateActionMode", new Class<?>[0]);
        if (result != null) {
            info("SelectAll: showOrUpdateActionMode() succeeded");
            return;
        }
        // Try actionBar.setSubTitle with count
        Object actionBar = Reflect.field(chatActivity, "actionBar");
        if (actionBar instanceof ViewGroup) {
            result = Reflect.invokeIfExists(actionBar, "setSubTitle",
                    new Class<?>[]{CharSequence.class}, String.valueOf(count));
            if (result != null) {
                info("SelectAll: actionBar.setSubTitle succeeded with count=" + count);
                return;
            }
        }
        info("SelectAll: WARNING - could not update action bar counter");
    }

    /**
     * Fallback: try to select all via performLongClick on visible children.
     */
    private void fallbackSelectAll(RecyclerView recyclerView) {
        info("SelectAll: using performLongClick fallback on " + recyclerView.getChildCount() + " children");
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
            }
        }
    }

    private void injectSelectAllIntoActionBar(Object actionBar) {
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) actionBar;
        View button = bar.findViewWithTag(MENU_ID_SELECT_ALL);
        if (button == null) {
            Context context = bar.getContext();
            CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
            TextView created = new TextView(context);
            created.setTag(MENU_ID_SELECT_ALL);
            created.setText(label);
            created.setTextSize(14);
            created.setTextColor(0xFFFFFFFF);
            created.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            created.setGravity(android.view.Gravity.CENTER);
            created.setBackgroundColor(0x33FFFFFF);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            boolean added = false;
            int childCount = bar.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = bar.getChildAt(i);
                if (child instanceof ViewGroup && child.getVisibility() == View.VISIBLE
                        && child.getWidth() > 100) {
                    ViewGroup content = (ViewGroup) child;
                    int insertIndex = content.getChildCount();
                    content.addView(created, insertIndex, lp);
                    content.invalidate();
                    content.requestLayout();
                    info("SelectAll: added to child[" + i + "] at " + insertIndex
                            + " broughtToFront");
                    added = true;
                    break;
                }
            }
            if (!added) {
                bar.addView(created, lp);
            }
            button = created;
        }
        button.bringToFront();
        button.setClickable(true);
        button.setFocusable(true);
        View boundButton = button;
        uiCallbacks.setClickListener(boundButton, v -> {
            try {
                info("SelectAll: button clicked!");
                selectAllFromActionBar(bar);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
        uiCallbacks.setTouchListener(boundButton, (v, event) -> {
            info("SelectAll: touch event=" + event.getAction());
            return false;
        });
    }

    private View findCountTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (text.matches("\\d+") && child.getVisibility() == View.VISIBLE) {
                    return child;
                }
            }
            if (child instanceof ViewGroup) {
                View found = findCountTextView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private int findCountViewIndex(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (text.matches("\\d+.*")) {
                    return i;
                }
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                int found = findCountViewIndex((ViewGroup) child);
                if (found >= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void selectAllFromActionBar(ViewGroup bar) {
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        if (parent == null) {
            info("SelectAll: no parent");
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != bar && child instanceof ViewGroup) {
                RecyclerView rv = findRecyclerView((ViewGroup) child);
                if (rv != null) {
                    selectAllByLongClick(rv);
                    return;
                }
            }
        }
        ViewGroup grandParent = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
        if (grandParent != null) {
            for (int i = 0; i < grandParent.getChildCount(); i++) {
                View child = grandParent.getChildAt(i);
                if (child != parent && child instanceof ViewGroup) {
                    RecyclerView rv = findRecyclerView((ViewGroup) child);
                    if (rv != null) {
                        selectAllByLongClick(rv);
                        return;
                    }
                }
            }
        }
        info("SelectAll: RecyclerView not found among siblings");
    }

    private void selectAllInActionBar(ViewGroup bar) {
        View current = bar;
        for (int depth = 0; depth < 10; depth++) {
            View parent = (View) current.getParent();
            if (!(parent instanceof ViewGroup)) {
                break;
            }
            RecyclerView recyclerView = findRecyclerView((ViewGroup) parent);
            if (recyclerView != null) {
                info("SelectAll: found RecyclerView at depth " + depth + " in " + parent.getClass().getSimpleName());
                selectAllByLongClick(recyclerView);
                return;
            }
            current = parent;
        }
        info("SelectAll: RecyclerView not found");
    }

    private void selectAllByLongClick(ViewGroup listView) {
        info("SelectAll: selectAllByLongClick view=" + listView.getClass().getName());
        Object adapter = Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0]);
        if (adapter != null) {
            info("SelectAll: adapter class=" + adapter.getClass().getName());
            Class<?> clazz = adapter.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (java.util.Set.class.isAssignableFrom(f.getType()) || java.util.List.class.isAssignableFrom(f.getType())
                            || android.util.LongSparseArray.class.isAssignableFrom(f.getType())
                            || android.util.SparseArray.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        info("SelectAll: field " + f.getName() + " type=" + f.getType().getSimpleName());
                    }
                }
                clazz = clazz.getSuperclass();
            }
            Object selectedField = Reflect.field(adapter, "selectedDialogs");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedIds");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedFiles");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedDocuments");
            info("SelectAll: selectedField=" + (selectedField == null ? "null" : selectedField.getClass().getSimpleName()));
            if (selectedField instanceof java.util.Collection) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> collection = (java.util.Collection<Object>) selectedField;
                info("SelectAll: selectedDialogs size=" + collection.size());
                Object hostFragment = Reflect.field(adapter, "this$0");
                info("SelectAll: hostFragment=" + (hostFragment == null ? "null" : hostFragment.getClass().getSimpleName()));
                Object itemInternals = Reflect.field(adapter, "itemInternals");
                if (itemInternals instanceof java.util.ArrayList) {
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<?> items = (java.util.ArrayList<?>) itemInternals;
                    info("SelectAll: itemInternals size=" + items.size());
                    int added = 0;
                    for (Object item : items) {
                        if (item == null) continue;
                        Object dialog = Reflect.field(item, "dialog");
                        if (dialog == null) continue;
                        long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                        if (dialogId != 0) {
                            collection.add(dialogId);
                            added++;
                        }
                    }
                    if (added > 0) {
                        for (Object item : items) {
                            if (item == null) continue;
                            Object dialog = Reflect.field(item, "dialog");
                            if (dialog == null) continue;
                            long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                            if (dialogId != 0) {
                                Reflect.invokeIfExists(hostFragment, "onItemLongClick", new Class<?>[]{long.class}, dialogId);
                            }
                        }
                        info("SelectAll: toggled " + added + " items via onItemLongClick");
                        return;
                    }
                    info("SelectAll: no dialogs found in items");
                }
                info("SelectAll: no items found, fallback");
            }
        }
        int count = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
                count++;
            }
        }
        info("SelectAll: fallback long-click on " + count + " items");
    }

    private void selectAllDownloadItems(Object activity) {
        View fragmentView = resolveFragmentView(activity);
        if (!(fragmentView instanceof ViewGroup)) {
            return;
        }
        RecyclerView recyclerView = findRecyclerView((ViewGroup) fragmentView);
        if (recyclerView == null) {
            return;
        }
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) {
            return;
        }
        boolean selected = tryAdapterSelectAll(adapter);
        if (selected) {
            adapter.notifyDataSetChanged();
            return;
        }
        fallbackClickSelectAll(recyclerView);
    }

    @SuppressWarnings("unchecked")
    private boolean tryAdapterSelectAll(RecyclerView.Adapter<?> adapter) {
        Object selectedField = Reflect.field(adapter, "selectedIds");
        if (selectedField == null) {
            selectedField = Reflect.field(adapter, "selectedFiles");
        }
        if (selectedField == null) {
            selectedField = Reflect.field(adapter, "selectedMessages");
        }
        if (selectedField instanceof java.util.Set) {
            java.util.Set<Number> set = (java.util.Set<Number>) selectedField;
            int itemCount = adapter.getItemCount();
            for (int i = 0; i < itemCount; i++) {
                long id = adapter.getItemId(i);
                if (id != RecyclerView.NO_ID) {
                    set.add(id);
                }
            }
            return true;
        }
        if (selectedField instanceof android.util.LongSparseArray) {
            @SuppressWarnings("unchecked")
            android.util.LongSparseArray<Object> array = (android.util.LongSparseArray<Object>) selectedField;
            int itemCount = adapter.getItemCount();
            for (int i = 0; i < itemCount; i++) {
                long id = adapter.getItemId(i);
                if (id != RecyclerView.NO_ID) {
                    array.put(id, Boolean.TRUE);
                }
            }
            return true;
        }
        return false;
    }

    private void fallbackClickSelectAll(RecyclerView recyclerView) {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
            }
        }
    }

    private View resolveFragmentView(Object activity) {
        Object view = Reflect.field(activity, "fragmentView");
        if (view instanceof View) {
            return (View) view;
        }
        return Reflect.invokeIfExists(activity, "getFragmentView", new Class<?>[0]) instanceof View
                ? (View) Reflect.invokeIfExists(activity, "getFragmentView", new Class<?>[0])
                : null;
    }

    private RecyclerView findRecyclerView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RecyclerView) {
                return (RecyclerView) child;
            }
            if (child instanceof ViewGroup) {
                RecyclerView found = findRecyclerView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object findActionBarOverflow(ViewGroup actionBar) {
        for (int i = actionBar.getChildCount() - 1; i >= 0; i--) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                Object found = findActionBarMenuItemRecursive((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object findActionBarMenuItemRecursive(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                Object found = findActionBarMenuItemRecursive((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void hookSettingsActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> settingsActivityClass = classLoader.loadClass("org.telegram.ui.SettingsActivity");
            hookSettingsActivityListRow(classLoader, settingsActivityClass);
            hookSettingsActivityBack(settingsActivityClass);
            try {
                Method onResume = Reflect.method(settingsActivityClass, "onResume");
                hook(onResume, chain -> {
                    Object result = chain.proceed();
                    try {
                        refreshSettingsList(chain.getThisObject());
                    } catch (Throwable throwable) {
                        error("SettingsActivity row resume refresh failed", throwable);
                    }
                    return result;
                });
            } catch (NoSuchMethodException ignored) {
                info("SettingsActivity.onResume unavailable for row refresh");
            }
            info("Hooked SettingsActivity host entry");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity host entry", throwable);
        }
    }

    private void hookSettingsActivityListRow(ClassLoader classLoader, Class<?> settingsActivityClass) {
        try {
            Class<?> universalAdapterClass = classLoader.loadClass("org.telegram.ui.Components.UniversalAdapter");
            Class<?> uItemClass = classLoader.loadClass("org.telegram.ui.Components.UItem");
            Class<?> settingCellFactoryClass = classLoader.loadClass("org.telegram.ui.SettingsActivity$SettingCell$Factory");
            Method factoryOf = Reflect.method(
                    settingCellFactoryClass,
                    "of",
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    CharSequence.class,
                    CharSequence.class
            );
            Method fillItems = Reflect.method(settingsActivityClass, "fillItems", ArrayList.class, universalAdapterClass);
            hook(fillItems, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof ArrayList<?>) {
                        @SuppressWarnings("unchecked")
                        ArrayList<Object> items = (ArrayList<Object>) arg;
                        injectSettingsListRow(chain.getThisObject(), items, factoryOf);
                    }
                } catch (Throwable throwable) {
                    error("SettingsActivity row injection failed", throwable);
                }
                return result;
            });

            Method onClick = Reflect.method(settingsActivityClass, "onClick", uItemClass, View.class, int.class, float.class, float.class);
            hook(onClick, chain -> {
                Object item = chain.getArg(0);
                if (!isGramSieveSettingsItem(item)) {
                    return chain.proceed();
                }
                Context context = contextFromSettingsClick(chain.getThisObject(), chain.getArg(1));
                if (context == null) {
                    info("SettingsActivity row click ignored: context unavailable");
                    return null;
                }
                openConfigFromHost(chain.getThisObject(), context, CONFIG_MODE_GLOBAL, 0L, "");
                return null;
            });
            info("Hooked SettingsActivity list row");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity list row", throwable);
        }
    }

    private void hookSettingsActivityBack(Class<?> settingsActivityClass) {
        try {
            Method onBackPressed = Reflect.method(settingsActivityClass, "onBackPressed", boolean.class);
            hook(onBackPressed, chain -> {
                try {
                    if (closeHostConfigPanel(chain.getThisObject())) {
                        return false;
                    }
                } catch (Throwable throwable) {
                    error("SettingsActivity host panel back handling failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked SettingsActivity back for host panel");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity back", throwable);
        }
    }

    private static volatile int bindingCallCount = 0;

    private Object handleMessageBinding(XposedInterface.Chain chain) throws Throwable {
        int count = ++bindingCallCount;
        if (count <= 3) {
            info("handleMessageBinding #" + count);
        }
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            Object messageObject = chain.getArg(0);
            if (cell instanceof View && messageObject != null) {
                View cellView = (View) cell;
                clearAntiRecallCellVisualState(cellView);
                applyDecision(cellView, cellView, messageObject, groupedMessagesFromArgs(chain.getArgs(), cellView));
                cacheAndApplyAntiRecall(cellView, messageObject);
            }
        } catch (Throwable throwable) {
            error("Message filtering failed", throwable);
        }
        return result;
    }

    private static volatile int cacheCallCount = 0;

    private void cacheAndApplyAntiRecall(View cell, Object messageObject) {
        int count = ++cacheCallCount;
        if (messageObject == null || messageCache == null || mediaCache == null) return;
        try {
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
            long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
            if (dialogId == 0L || messageId == 0L) return;
            Object owner = Reflect.field(messageObject, "messageOwner");
            int accountId = TelegramAccountResolver.resolveWithFallback(
                    savedClassLoader, messageObject, owner);
            boolean antiRecallEnabled = backgroundMessageLoader != null
                    && !usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL)
                    && backgroundMessageLoader.isChatEnabled(dialogId);
            boolean editHistoryEnabled = editHistoryPolicyStore != null
                    && !usesModuleFallback(ModuleConflictDetector.ConflictKind.EDIT_HISTORY)
                    && editHistoryPolicyStore.shouldRecord(accountId, dialogId);
            if (count <= 10 || count % 200 == 0) {
                info("cacheAndApply #" + count + " account=" + accountId + " dialogId="
                        + dialogId + " msgId=" + messageId + " antiRecall=" + antiRecallEnabled
                        + " editHistory=" + editHistoryEnabled);
            }
            if (!antiRecallEnabled && !editHistoryEnabled) return;

            String fullContent = "";
            String caption = "";
            String mediaType = null;
            String mediaId = null;
            String cachedMediaPath = null;
            Object mediaAttachment = null;
            String mediaKind = null;
            Object media = null;
            if (owner != null) {
                String text = Reflect.asString(Reflect.field(owner, "message"));
                media = Reflect.field(owner, "media");
                if (media != null) {
                    caption = Reflect.asString(Reflect.field(media, "caption"));
                    mediaType = media.getClass().getSimpleName();
                    // Try to get photo ID or video ID
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                        mediaAttachment = photo;
                        mediaKind = "photo";
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                            mediaAttachment = video;
                            mediaKind = "video";
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                                mediaAttachment = document;
                                mediaKind = "document";
                            }
                        }
                    }
                }
                fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
            }

            MessageCache.CachedMessage existing = messageCache.get(accountId, dialogId, messageId);

            // Build a content fingerprint for comparison (text + mediaType + mediaId)
            String contentFingerprint = fullContent + "|" + (mediaType != null ? mediaType : "") + "|" + (mediaId != null ? mediaId : "");

            // Detect edit: cached content exists, is different from current, and wasn't already marked
            if (existing != null && !existing.isEdited && !existing.isRecalled) {
                String existingFingerprint = (existing.text != null ? existing.text : "") + "|"
                        + (existing.mediaType != null ? existing.mediaType : "") + "|"
                        + (existing.mediaId != null ? existing.mediaId : "");
                if (!existingFingerprint.isEmpty() && !existingFingerprint.equals(contentFingerprint)) {
                    if (editHistoryEnabled) {
                        messageCache.markEdited(accountId, dialogId, messageId, fullContent);
                        info("EditHistory: detected edit account=" + accountId + " msgId=" + messageId);
                    } else {
                        messageCache.putFresh(accountId, dialogId, messageId, fullContent, caption, 0L,
                                mediaType, mediaId, existing.cachedMediaPath,
                                TelegramMessageSerializer.serialize(messageObject));
                    }
                } else if (existingFingerprint.isEmpty() || existingFingerprint.equals(contentFingerprint)) {
                    cachedMediaPath = tryCacheMedia(cell, accountId, dialogId, messageId,
                            owner, mediaAttachment, mediaKind);
                    messageCache.putFresh(accountId, dialogId, messageId, fullContent, caption, 0L,
                            mediaType, mediaId, cachedMediaPath,
                            existing.rawMessageBlob == null
                                    ? TelegramMessageSerializer.serialize(messageObject) : null);
                    messageCache.putMediaObject(accountId, dialogId, messageId, media);
                    prefetchMediaIfNeeded(accountId, dialogId, messageId,
                            messageObject, media, cachedMediaPath);
                }
            } else if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                cachedMediaPath = tryCacheMedia(cell, accountId, dialogId, messageId,
                        owner, mediaAttachment, mediaKind);
                messageCache.putFresh(accountId, dialogId, messageId, fullContent, caption, 0L,
                        mediaType, mediaId, cachedMediaPath,
                        existing == null || existing.rawMessageBlob == null
                                ? TelegramMessageSerializer.serialize(messageObject) : null);
                messageCache.putMediaObject(accountId, dialogId, messageId, media);
                prefetchMediaIfNeeded(accountId, dialogId, messageId,
                        messageObject, media, cachedMediaPath);
            }

            MessageCache.CachedMessage cached = messageCache.get(accountId, dialogId, messageId);
            if (cached != null && cached.isEdited) {
                showEditedMark(cell, cached);
            } else if (cached != null && cached.isRecalled) {
                showRecalledMark(cell, cached);
            }
        } catch (Throwable t) {
            error("Anti-recall: cacheAndApply failed", t);
        }
    }

    private void prefetchMediaIfNeeded(int accountId, long dialogId, long messageId, Object messageLike,
                                       Object media, String cachedMediaPath) {
        if (cachedMediaPath != null || mediaPrefetcher == null || messageLike == null || media == null) {
            return;
        }
        mediaPrefetcher.prefetchFromMessage(accountId, dialogId, messageId, messageLike, media);
    }

    private String tryCacheMedia(View cell, int accountId, long dialogId, long messageId,
                                 Object messageOwner, Object mediaObj, String mediaKind) {
        if (mediaObj == null || mediaKind == null) {
            return null;
        }
        try {
            // Check if we already cached this media
            String extension = mediaKind.equals("photo") ? ".jpg" : mediaKind.equals("video") ? ".mp4" : ".bin";
            if (mediaCache.hasMedia(accountId, dialogId, messageId, extension)) {
                return mediaCache.getMediaFile(accountId, dialogId, messageId, extension).getAbsolutePath();
            }

            // Try to get the file path from Telegram's FileLoader
            ClassLoader classLoader = savedClassLoader != null ? savedClassLoader : cell.getContext().getClassLoader();
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            Object fileLoader = Reflect.invokeStatic(fileLoaderClass, "getInstance",
                    new Class<?>[]{int.class}, accountId);

            java.io.File srcFile = resolveTelegramMediaPath(fileLoaderClass, fileLoader, messageOwner, mediaObj);
            if (srcFile != null && srcFile.exists() && srcFile.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(srcFile)) {
                    java.io.File cached = mediaCache.saveMedia(
                            accountId, dialogId, messageId, extension, fis);
                    if (cached != null) {
                        info("Anti-recall: cached media msgId=" + messageId + " kind=" + mediaKind);
                        return cached.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            info("Anti-recall: media cache miss kind=" + mediaKind + " reason=" + t.getClass().getSimpleName());
        }
        return null;
    }

    private java.io.File resolveTelegramMediaPath(Class<?> fileLoaderClass, Object fileLoader, Object messageOwner, Object mediaObj) {
        java.io.File file = invokePathMethod(fileLoaderClass, null, "getPathToMessage", messageOwner);
        if (file != null) return file;
        file = invokePathMethod(fileLoaderClass, fileLoader, "getPathToMessage", messageOwner);
        if (file != null) return file;
        file = invokePathMethod(fileLoaderClass, null, "getPathToAttach", mediaObj);
        if (file != null) return file;
        return invokePathMethod(fileLoaderClass, fileLoader, "getPathToAttach", mediaObj);
    }

    private java.io.File invokePathMethod(Class<?> type, Object target, String name, Object payload) {
        if (payload == null) {
            return null;
        }
        boolean needStatic = target == null;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != needStatic) {
                    continue;
                }
                Object[] args = buildFilePathArgs(method.getParameterTypes(), payload);
                if (args == null) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(target, args);
                    if (result instanceof java.io.File) {
                        return (java.io.File) result;
                    }
                } catch (Throwable ignored) {
                    // Try the next overload.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    static Object[] buildFilePathArgs(Class<?>[] parameterTypes, Object payload) {
        if (parameterTypes.length == 1) {
            return new Object[]{payload};
        }
        if (parameterTypes.length == 2 && (parameterTypes[1] == boolean.class || parameterTypes[1] == Boolean.class)) {
            return new Object[]{payload, false};
        }
        return null;
    }

    private void addColorIndicator(View cell, int color) {
        info("Anti-recall: addColorIndicator called, cell class=" + cell.getClass().getSimpleName());
        if (!(cell instanceof ViewGroup)) {
            info("Anti-recall: cell is NOT a ViewGroup, cannot add indicator");
            return;
        }
        ViewGroup group = (ViewGroup) cell;
        if (group.findViewWithTag("gramsieve_indicator") != null) {
            info("Anti-recall: indicator already exists");
            return;
        }
        Context context = cell.getContext();
        View indicator = new View(context);
        indicator.setTag("gramsieve_indicator");
        indicator.setBackgroundColor(color);
        int height = (int) (3 * context.getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        group.addView(indicator, lp);
        info("Anti-recall: indicator added, height=" + height);
    }

    private void applyOverlay(View cell, int color) {
        try {
            android.graphics.drawable.ColorDrawable drawable = new android.graphics.drawable.ColorDrawable(color);
            drawable.setBounds(0, 0, cell.getWidth(), cell.getHeight());
            cell.getOverlay().add(drawable);
        } catch (Throwable t) {
            error("Anti-recall: overlay failed", t);
        }
    }

    private void showOriginalContentDialog(View anchor, String originalText, boolean isRecalled) {
        if (originalText == null || originalText.isEmpty()) return;
        Context context = anchor.getContext();
        String title = isRecalled
                ? (isChineseLocale(context) ? "此消息已被撤回" : "Message Recalled")
                : (isChineseLocale(context) ? "原始消息内容" : "Original Message");
        new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(originalText)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showEditDialog(View anchor, String originalText, String editedText) {
        info("Anti-recall: showEditDialog orig=" + (originalText == null ? "NULL" : originalText.length() + "chars") + " edit=" + (editedText == null ? "NULL" : editedText.length() + "chars"));
        Context context = anchor.getContext();
        String title = isChineseLocale(context) ? "消息已编辑" : "Message Edited";
        String orig = sanitizeDisplayText(originalText);
        String edit = sanitizeDisplayText(editedText);
        if (orig == null && edit == null) {
            Toast.makeText(context, isChineseLocale(context) ? "无编辑历史" : "No edit history", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder body = new StringBuilder();
        if (orig != null) {
            body.append(isChineseLocale(context) ? "原始内容：\n" : "Original:\n");
            body.append(orig);
        }
        if (edit != null) {
            if (body.length() > 0) body.append("\n\n");
            body.append(isChineseLocale(context) ? "编辑后：\n" : "Edited:\n");
            body.append(edit);
        }
        if (body.length() == 0) return;
        new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showEditHistoryDialog(View anchor, List<MessageCache.CachedMessage> history,
                                       MessageCache.CachedMessage fallback) {
        if (history == null || history.isEmpty()) {
            showEditDialog(anchor, firstNonEmpty(fallback.text, fallback.caption), fallback.editedText);
            return;
        }
        if (history.size() == 1) {
            MessageCache.CachedMessage only = history.get(0);
            showEditDialog(anchor, firstNonEmpty(only.text, only.caption), only.editedText);
            return;
        }
        Context context = anchor.getContext();
        StringBuilder body = new StringBuilder();
        int version = 1;
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageCache.CachedMessage item = history.get(i);
            String before = sanitizeDisplayText(firstNonEmpty(item.text, item.caption));
            String after = sanitizeDisplayText(item.editedText);
            if (before == null && after == null) {
                continue;
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(isChineseLocale(context) ? "版本 " : "Version ")
                    .append(version++)
                    .append(":\n");
            if (before != null) {
                body.append(isChineseLocale(context) ? "编辑前：\n" : "Before:\n")
                        .append(before);
            }
            if (after != null) {
                if (before != null) {
                    body.append("\n\n");
                }
                body.append(isChineseLocale(context) ? "编辑后：\n" : "After:\n")
                        .append(after);
            }
        }
        if (body.length() == 0) {
            showEditDialog(anchor, firstNonEmpty(fallback.text, fallback.caption), fallback.editedText);
            return;
        }
        new android.app.AlertDialog.Builder(context)
                .setTitle(isChineseLocale(context) ? "编辑历史" : "Edit History")
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private static String sanitizeDisplayText(String text) {
        if (text == null || text.isEmpty() || "null".equals(text)) return null;
        return text;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        if (second != null && !second.isEmpty()) {
            return second;
        }
        return null;
    }

    private void replaceTextInCell(View cell, String originalText) {
        if (originalText == null || originalText.isEmpty()) return;
        try {
            if (cell instanceof ViewGroup) {
                TextView tv = findLargestTextView((ViewGroup) cell);
                if (tv != null) {
                    String current = tv.getText().toString();
                    if (!originalText.equals(current)) {
                        tv.setText(originalText);
                        info("Anti-recall: replaced text in cell");
                    }
                }
            }
        } catch (Throwable throwable) {
            error("Anti-recall: replaceTextInCell failed", throwable);
        }
    }

    private TextView findLargestTextView(ViewGroup group) {
        TextView largest = null;
        int maxLen = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && child.getVisibility() == View.VISIBLE) {
                String text = ((TextView) child).getText().toString();
                if (text.length() > maxLen) {
                    maxLen = text.length();
                    largest = (TextView) child;
                }
            } else if (child instanceof ViewGroup) {
                TextView found = findLargestTextView((ViewGroup) child);
                if (found != null) {
                    String text = found.getText().toString();
                    if (text.length() > maxLen) {
                        maxLen = text.length();
                        largest = found;
                    }
                }
            }
        }
        return largest;
    }

    private void applyEditedMessageVisuals(View cell, Object messageObject, MessageCache.CachedMessage cachedMessage) {
        showEditedMark(cell, cachedMessage);
        applyRestoredContent(cell, messageObject, cachedMessage);
    }

    private void applyRestoredContent(View cell, Object messageObject, MessageCache.CachedMessage cachedMessage) {
        interceptAndRestoreContent(messageObject, cachedMessage);
        replaceTextInCell(cell, firstNonEmpty(cachedMessage.text, cachedMessage.caption));
        replaceCachedMediaInCell(cell, cachedMessage);
    }

    private void replaceCachedMediaInCell(View cell, MessageCache.CachedMessage cachedMessage) {
        if (!(cell instanceof ViewGroup) || cachedMessage.cachedMediaPath == null) {
            return;
        }
        try {
            java.io.File file = new java.io.File(cachedMessage.cachedMediaPath);
            if (!file.exists() || file.length() <= 0) {
                return;
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                return;
            }
            ImageView imageView = findLargestImageView((ViewGroup) cell);
            if (imageView != null && imageView.getWidth() > 0 && imageView.getHeight() > 0) {
                imageView.setImageBitmap(bitmap);
                info("Anti-recall: restored cached media via ImageView for msg " + cachedMessage.messageId);
            }
            if (applyCachedMediaOverlay(cell, bitmap)) {
                info("Anti-recall: restored cached media via overlay for msg " + cachedMessage.messageId);
            }
        } catch (Throwable throwable) {
            error("Anti-recall: replaceCachedMediaInCell failed", throwable);
        }
    }

    private boolean applyCachedMediaOverlay(View cell, android.graphics.Bitmap bitmap) {
        if (cell.getWidth() <= 0 || cell.getHeight() <= 0) {
            return false;
        }
        removeCachedMediaOverlay(cell);
        android.graphics.drawable.Drawable drawable = createCenterCropDrawable(bitmap);
        int inset = dp(cell.getContext(), 6f);
        drawable.setBounds(inset, inset, Math.max(inset, cell.getWidth() - inset), Math.max(inset, cell.getHeight() - inset));
        cell.getOverlay().add(drawable);
        cell.setTag(R.id.gramsieve_cached_media_overlay, drawable);
        cell.invalidate();
        return true;
    }

    private void removeCachedMediaOverlay(View cell) {
        if (cell == null) {
            return;
        }
        Object tagged = cell.getTag(R.id.gramsieve_cached_media_overlay);
        boolean removed = false;
        if (tagged instanceof android.graphics.drawable.Drawable) {
            cell.getOverlay().remove((android.graphics.drawable.Drawable) tagged);
            removed = true;
        }
        if (tagged != null) {
            cell.setTag(R.id.gramsieve_cached_media_overlay, null);
            removed = true;
        }
        // Versions before the dedicated tag used the filtering state key for this drawable.
        // Remove that legacy value without touching a real UiMutation.ViewState.
        Object legacy = cell.getTag(R.id.gramsieve_view_state);
        if (legacy instanceof android.graphics.drawable.Drawable) {
            cell.getOverlay().remove((android.graphics.drawable.Drawable) legacy);
            cell.setTag(R.id.gramsieve_view_state, null);
            removed = true;
        }
        if (removed) {
            cell.invalidate();
        }
    }

    private android.graphics.drawable.Drawable createCenterCropDrawable(android.graphics.Bitmap bitmap) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds();
                if (bounds.isEmpty()) {
                    return;
                }
                float scale = Math.max(
                        bounds.width() / (float) bitmap.getWidth(),
                        bounds.height() / (float) bitmap.getHeight()
                );
                float scaledWidth = bitmap.getWidth() * scale;
                float scaledHeight = bitmap.getHeight() * scale;
                float left = bounds.left + (bounds.width() - scaledWidth) / 2f;
                float top = bounds.top + (bounds.height() - scaledHeight) / 2f;
                android.graphics.RectF dst = new android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight);
                canvas.save();
                canvas.clipRect(bounds);
                canvas.drawBitmap(bitmap, null, dst, paint);
                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
                invalidateSelf();
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
                invalidateSelf();
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.OPAQUE;
            }
        };
    }

    private ImageView findLargestImageView(ViewGroup group) {
        ImageView largest = null;
        int maxArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) {
                int area = child.getWidth() * child.getHeight();
                if (area > maxArea) {
                    maxArea = area;
                    largest = (ImageView) child;
                }
            } else if (child instanceof ViewGroup) {
                ImageView found = findLargestImageView((ViewGroup) child);
                if (found != null) {
                    int area = found.getWidth() * found.getHeight();
                    if (area > maxArea) {
                        maxArea = area;
                        largest = found;
                    }
                }
            }
        }
        return largest;
    }

    private void applyAntiRecallMark(View cell, Object messageObject) {
        if (messageObject == null || messageCache == null) {
            return;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
        long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
        if (dialogId == 0L || messageId == 0L) {
            return;
        }
        int accountId = TelegramAccountResolver.resolveWithFallback(
                savedClassLoader, messageObject, Reflect.field(messageObject, "messageOwner"));
        MessageCache.CachedMessage cachedMessage = messageCache.get(accountId, dialogId, messageId);
        if (cachedMessage == null) {
            return;
        }
        if (cachedMessage.isRecalled) {
            showRecalledMark(cell, cachedMessage);
            replaceCachedMediaInCell(cell, cachedMessage);
        } else if (cachedMessage.isEdited) {
            showEditedMark(cell, cachedMessage);
        }
    }

    private void interceptAndRestoreContent(Object messageObject, MessageCache.CachedMessage cachedMessage) {
        try {
            // Get the messageOwner object
            Object messageOwner = Reflect.field(messageObject, "messageOwner");
            if (messageOwner == null) {
                return;
            }
            
            // Restore original text content
            String currentText = Reflect.asString(Reflect.field(messageOwner, "message"));
            if (cachedMessage.text != null && !cachedMessage.text.equals(currentText)) {
                Reflect.setField(messageOwner, "message", cachedMessage.text);
                info("Anti-recall: restored original text for msg " + cachedMessage.messageId);
            }
            
            // Restore original caption if it exists
            Object media = Reflect.field(messageOwner, "media");
            if (media != null) {
                String currentCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (cachedMessage.caption != null && !cachedMessage.caption.equals(currentCaption)) {
                    Reflect.setField(media, "caption", cachedMessage.caption);
                    info("Anti-recall: restored original caption for msg " + cachedMessage.messageId);
                }
            }
        } catch (Throwable throwable) {
            error("Anti-recall: interceptAndRestoreContent failed", throwable);
        }
    }

    private void clearAntiRecallCellVisualState(View cell) {
        removeCachedMediaOverlay(cell);
        Object mark = cell.getTag(R.id.gramsieve_menu_item_id);
        if (!(mark instanceof String)) {
            return;
        }
        String value = (String) mark;
        if (!value.startsWith("recalled_") && !value.startsWith("edited_")) {
            return;
        }
        cell.setTag(R.id.gramsieve_menu_item_id, null);
        if (value.startsWith("recalled_")) {
            cell.setBackground(null);
        }
    }

    private void showRecalledMark(View cell, MessageCache.CachedMessage cachedMessage) {
        Context context = cell.getContext();
        String originalContent = cachedMessage.text != null && !cachedMessage.text.isEmpty() 
            ? cachedMessage.text 
            : (cachedMessage.caption != null && !cachedMessage.caption.isEmpty() ? cachedMessage.caption : "");
        
        final String markText;
        if (isChineseLocale(context)) {
            markText = "[此消息已被撤回]" + (originalContent.isEmpty() ? "" : "\n原内容: " + originalContent);
        } else {
            markText = "[This message was recalled]" + (originalContent.isEmpty() ? "" : "\nOriginal: " + originalContent);
        }
        
        cell.setTag(R.id.gramsieve_menu_item_id, "recalled_" + cachedMessage.messageId);
        uiCallbacks.post(cell, () -> Toast.makeText(context, markText, Toast.LENGTH_LONG).show());
    }

    private void showEditedMark(View cell, MessageCache.CachedMessage cachedMessage) {
        cell.setTag(R.id.gramsieve_menu_item_id, "edited_" + cachedMessage.messageId);
    }

    private Object handleCellLifecycle(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("lifecycle", chain.getThisObject(), null);
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            if (cell instanceof View) {
                View cellView = (View) cell;
                trackTopmostMessage(cellView);
                Object messageObject = resolveMessageObject(cell);
                if (messageObject != null) {
                    applyDecision(cellView, cellView, messageObject,
                            groupedMessagesFromArgs(chain.getArgs(), cellView));
                } else {
                    applyDecisionToBoundViews(cell);
                }
            }
        } catch (Throwable throwable) {
            error("Cell lifecycle filtering failed", throwable);
        }
        return result;
    }

    private Object handleCellMeasure(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            if (cell instanceof View) {
                View messageView = (View) cell;
                int measuredWidthBefore = messageView.getMeasuredWidth();
                int measuredHeightBefore = messageView.getMeasuredHeight();
                DecisionContext context = evaluateDecisionContext(
                        messageView,
                        resolveMessageObject(cell),
                        groupedMessagesFromArgs(chain.getArgs(), messageView)
                );
                UiMutation.overrideMeasuredHeight(messageView, context.decision);
                emitCellMeasureTrace(
                        messageView,
                        context,
                        measuredWidthBefore,
                        measuredHeightBefore,
                        messageView.getMeasuredWidth(),
                        messageView.getMeasuredHeight()
                );
            }
        } catch (Throwable throwable) {
            error("Cell measure filtering failed", throwable);
        }
        return result;
    }

    private Object handleChatRowBinding(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("chatRow", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("Chat row filtering failed", throwable);
        }
        return result;
    }

    private Object handleRecyclerViewBinding(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("recycler", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object adapter = chain.getThisObject();
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            if (applyLocalDialogHide(adapter, itemView)) {
                return result;
            }
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("RecyclerView binding filter failed", throwable);
        }
        return result;
    }

    private Object handleRecyclerViewAttachment(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("attach", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object adapter = chain.getThisObject();
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            if (applyLocalDialogHide(adapter, itemView)) {
                return result;
            }
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("RecyclerView attachment filter failed", throwable);
        }
        return result;
    }

    private boolean applyLocalDialogHide(Object adapter, Object itemView) {
        if (!(itemView instanceof View)) {
            return false;
        }
        View row = (View) itemView;
        if (!isDialogListRow(adapter, row)) {
            return false;
        }
        return applyLocalDialogHideToRow(row, resolveDialogDeletionAccount(adapter));
    }

    private boolean applyLocalDialogHideToRow(View row, int account) {
        long dialogId = resolveDialogIdFromView(row);
        if (dialogId == 0L) {
            UiMutation.apply(row, FilterDecision.allow(), "");
            return false;
        }
        if (!isLocallyHiddenDialog(dialogId, account)) {
            UiMutation.apply(row, FilterDecision.allow(), "dialog:" + dialogId);
            return false;
        }
        UiMutation.apply(row,
                FilterDecision.matched(FilterConfig.Action.HIDE, "local-dialog-delete", "local dialog delete"),
                "dialog:" + dialogId);
        if (shouldLogLocalDialogHide(dialogId)) {
            info("DialogDeleteTrace: hiding dialog row dialogId=" + dialogId
                    + " account=" + account
                    + " row=" + row.getClass().getName());
        }
        return true;
    }

    private boolean isDialogsAdapter(Object adapter) {
        if (adapter == null) {
            return false;
        }
        String className = adapter.getClass().getName();
        return "org.telegram.ui.Adapters.DialogsAdapter".equals(className)
                || className.startsWith("org.telegram.ui.DialogsActivity$");
    }

    private boolean isDialogListRow(Object adapter, View row) {
        return isDialogsAdapter(adapter)
                || containsDialogListCell(row);
    }

    private boolean containsDialogListCell(View view) {
        if (isDialogListCellClass(view.getClass().getName())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsDialogListCell(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDialogListCellClass(String className) {
        return "org.telegram.ui.Cells.DialogCell".equals(className)
                || "org.telegram.ui.Cells.ProfileSearchCell".equals(className);
    }

    private long resolveDialogIdFromView(View view) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(view, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId != 0L) {
            return dialogId;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dialogId = resolveDialogIdFromView(group.getChildAt(i));
                if (dialogId != 0L) {
                    return dialogId;
                }
            }
        }
        return 0L;
    }

    private boolean shouldLogLocalDialogHide(long dialogId) {
        long now = System.currentTimeMillis();
        String key = "local-dialog-hide:" + dialogId;
        synchronized (recentDiagnosticKeys) {
            Long previous = recentDiagnosticKeys.get(key);
            if (previous != null && now - previous < 10_000L) {
                return false;
            }
            recentDiagnosticKeys.put(key, now);
            return true;
        }
    }

    private void applyDecisionToBoundViews(Object rootCandidate) {
        if (!(rootCandidate instanceof View)) {
            return;
        }
        View rowRoot = (View) rootCandidate;
        final DecisionContext[] matchedContext = new DecisionContext[1];
        final DecisionContext[] firstContext = new DecisionContext[1];
        final View[] matchedView = new View[1];
        final List<View> boundViews = new ArrayList<>();
        BoundMessageViewWalker.visit(
                rowRoot,
                (messageView, messageObject) -> {
                    boundViews.add(messageView);
                    DecisionContext context = evaluateDecisionContext(messageView, messageObject);
                    if (firstContext[0] == null) {
                        firstContext[0] = context;
                    }
                    if (context.decision.matched && matchedContext[0] == null) {
                        matchedContext[0] = context;
                        matchedView[0] = messageView;
                    }
                }
        );
        if (matchedContext[0] != null) {
            applyDecisionContext(matchedView[0], rowRoot, matchedContext[0]);
            // A recycled grouped row may still contain a child mutation from a previous bind.
            // Release only those child states that are not the group decision; UiMutation makes
            // the calls no-ops for untouched Telegram views.
            for (View boundView : boundViews) {
                if (boundView != matchedView[0]) {
                    emitUiMutationTrace(
                            boundView,
                            matchedContext[0],
                            UiMutation.apply(boundView, FilterDecision.allow(), "")
                    );
                }
            }
            return;
        }
        for (View boundView : boundViews) {
            emitUiMutationTrace(
                    boundView,
                    firstContext[0] == null ? DecisionContext.allow() : firstContext[0],
                    UiMutation.apply(boundView, FilterDecision.allow(), "")
            );
        }
        emitUiMutationTrace(
                rowRoot,
                firstContext[0] == null ? DecisionContext.allow() : firstContext[0],
                UiMutation.apply(rowRoot, FilterDecision.allow(), "")
        );
    }

    private void applyDecision(View messageView, View mutationTarget, Object messageObject) {
        applyDecision(messageView, mutationTarget, messageObject, null);
    }

    private void applyDecision(View messageView, View mutationTarget, Object messageObject,
                               Object explicitGroupedMessages) {
        if (messageView == null) {
            return;
        }
        if (mutationTarget == null) {
            mutationTarget = messageView;
        }
        DecisionContext context = evaluateDecisionContext(messageView, messageObject, explicitGroupedMessages);
        applyDecisionContext(messageView, mutationTarget, context);
    }

    private void applyDecisionContext(View messageView, View mutationTarget, DecisionContext context) {
        if (mutationTarget == null) {
            return;
        }
        String mutationKey = context.mutationKey();
        emitUiMutationTrace(
                mutationTarget,
                context,
                UiMutation.apply(mutationTarget, context.decision, mutationKey)
        );
        if (messageView != null && mutationTarget != messageView) {
            emitUiMutationTrace(
                    messageView,
                    context,
                    UiMutation.apply(messageView, context.decision, mutationKey)
            );
        }
        View recyclerRow = findRecyclerDirectChild(messageView);
        if (recyclerRow != null && recyclerRow != mutationTarget) {
            emitUiMutationTrace(
                    recyclerRow,
                    context,
                    UiMutation.apply(recyclerRow, context.decision, mutationKey)
            );
        }
        if (context.snapshot == null || context.config == null) {
            return;
        }
        emitDecisionProbe(context.config, context.snapshot, context.decision);
        emitBindingProbe(context.config, mutationTarget, context.snapshot, context.decision);
        persistDiagnostic(mutationTarget.getContext().getApplicationContext(), context);
        if (context.config.debugLogging && (context.decision.matched || context.decision.excluded)) {
            info("Decision=" + context.decision.reason + " dialog=" + context.snapshot.dialogId + " sender=" + context.snapshot.senderId + " msg=" + context.snapshot.messageId);
        }
    }

    private DecisionContext evaluateDecisionContext(View messageView, Object messageObject) {
        return evaluateDecisionContext(messageView, messageObject, null);
    }

    private DecisionContext evaluateDecisionContext(View messageView, Object messageObject,
                                                    Object explicitGroupedMessages) {
        if (messageView == null) {
            return DecisionContext.allow();
        }
        Object groupedMessages = explicitGroupedMessages == null
                ? groupedMessagesFromArgs(null, messageView, messageObject)
                : explicitGroupedMessages;
        GroupInfo groupInfo = resolveGroupInfo(groupedMessages, messageObject);
        if (!groupInfo.grouped || groupInfo.messages.isEmpty()) {
            return evaluateSingleDecisionContext(messageView, messageObject, groupInfo, true);
        }

        List<DecisionContext> memberContexts = new ArrayList<>();
        boolean currentIncluded = false;
        for (Object member : groupInfo.messages) {
            if (member == null) {
                continue;
            }
            if (member == messageObject) {
                currentIncluded = true;
            }
            memberContexts.add(evaluateSingleDecisionContext(messageView, member, groupInfo, false));
        }
        if (!currentIncluded && messageObject != null) {
            memberContexts.add(evaluateSingleDecisionContext(messageView, messageObject, groupInfo, false));
        }
        List<FilterDecision> decisions = new ArrayList<>();
        for (DecisionContext memberContext : memberContexts) {
            decisions.add(memberContext.decision);
        }
        GroupedDecisionSelector.Selection selection = GroupedDecisionSelector.select(decisions);
        DecisionContext selected = selection.index >= 0 && selection.index < memberContexts.size()
                ? memberContexts.get(selection.index)
                : (memberContexts.isEmpty()
                ? DecisionContext.allow(groupInfo)
                : memberContexts.get(0));
        // Read marking is deliberately delayed until the group-level precedence decision is
        // known. An exclusion keeps the entire group and must not mark an earlier match read.
        if (selected.decision.matched && !selected.decision.excluded) {
            markFilteredMessageRead(selected.messageObject, selected.snapshot, selected.decision);
        }
        return selected;
    }

    private DecisionContext evaluateSingleDecisionContext(View messageView, Object messageObject,
                                                          GroupInfo groupInfo, boolean markRead) {
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        if (snapshot == null) {
            return DecisionContext.allow(groupInfo, messageObject);
        }
        FilterConfig config = configProvider.getConfig(messageView.getContext().getApplicationContext());
        FilterDecision decision = decisionCache.get(config, snapshot, () -> filterEngine.evaluate(config, snapshot));
        if (markRead) {
            markFilteredMessageRead(messageObject, snapshot, decision);
        }
        return new DecisionContext(config, snapshot, decision, groupInfo, messageObject);
    }

    private Object groupedMessagesFromArgs(List<?> args, View cell) {
        return groupedMessagesFromArgs(args, cell, null);
    }

    private Object groupedMessagesFromArgs(List<?> args, View cell, Object messageObject) {
        if (args != null) {
            for (Object arg : args) {
                if (isGroupedMessagesType(arg)) {
                    return arg;
                }
            }
        }
        Object grouped = cell == null ? null : Reflect.field(cell, "currentMessagesGroup");
        if (isGroupedMessages(grouped)) {
            return grouped;
        }
        grouped = messageObject == null ? null : Reflect.field(messageObject, "currentMessagesGroup");
        return isGroupedMessages(grouped) ? grouped : null;
    }

    private boolean isGroupedMessages(Object value) {
        if (value == null) {
            return false;
        }
        String name = value.getClass().getName();
        if (isGroupedMessagesType(value)) {
            return true;
        }
        return Reflect.field(value, "messages") instanceof List<?>
                || Reflect.field(value, "posArray") instanceof List<?>;
    }

    private boolean isGroupedMessagesType(Object value) {
        if (value == null) {
            return false;
        }
        String name = value.getClass().getName();
        return name.endsWith("MessageObject$GroupedMessages") || name.contains("GroupedMessages");
    }

    private GroupInfo resolveGroupInfo(Object groupedMessages, Object currentMessage) {
        if (!isGroupedMessages(groupedMessages)) {
            return GroupInfo.NONE;
        }
        List<?> messages = asObjectList(Reflect.field(groupedMessages, "messages"));
        if (messages.isEmpty()) {
            messages = asObjectList(Reflect.field(groupedMessages, "posArray"));
        }
        long groupId = firstLongField(groupedMessages, "groupId", "group_id", "id");
        if (groupId == 0L) {
            groupId = Integer.toUnsignedLong(System.identityHashCode(groupedMessages));
        }
        int position = -1;
        for (int i = 0; i < messages.size(); i++) {
            Object candidate = messages.get(i);
            if (candidate == currentMessage || (candidate != null && candidate.equals(currentMessage))) {
                position = i;
                break;
            }
        }
        return new GroupInfo(groupId, messages.size(), position, messages);
    }

    private List<?> asObjectList(Object value) {
        return value instanceof List<?> ? (List<?>) value : Collections.emptyList();
    }

    private long firstLongField(Object value, String... names) {
        for (String name : names) {
            long candidate = Reflect.asLong(Reflect.field(value, name), 0L);
            if (candidate != 0L) {
                return candidate;
            }
        }
        return 0L;
    }

    private void emitUiMutationTrace(View view, DecisionContext context,
                                     UiMutation.MutationResult result) {
        if (view == null || result == null || result.transition == UiMutation.MutationTransition.NOOP) {
            return;
        }
        GroupInfo group = context == null || context.groupInfo == null
                ? GroupInfo.NONE
                : context.groupInfo;
        FilterDecision decision = context == null ? null : context.decision;
        String stableKey = context == null ? "" : context.stableKey;
        String action = decision != null && decision.excluded
                ? "EXCLUDED"
                : decision != null && decision.matched && decision.action != null
                ? decision.action.name()
                : "ALLOW";
        if (!group.grouped
                && result.transition == UiMutation.MutationTransition.APPLIED
                && (context == null || context.config == null || !context.config.debugLogging)) {
            return;
        }
        String dedupKey = "mutation|" + System.identityHashCode(view)
                + "|" + result.transition + "|" + result.key + "|" + action;
        UiTraceRateLimiter.Permit permit = acquireUiTracePermit(dedupKey);
        if (permit == null || !permit.emit) {
            return;
        }
        info("FilterUiTrace mutation transition=" + result.transition
                + " view=" + view.getClass().getSimpleName()
                + " groupId=" + (group.grouped ? group.groupId : 0L)
                + " groupCount=" + (group.grouped ? group.count : 0)
                + " groupPosition=" + (group.grouped ? group.position : -1)
                + " dialog=" + (context == null || context.snapshot == null ? 0L : context.snapshot.dialogId)
                + " message=" + (context == null || context.snapshot == null ? 0L : context.snapshot.messageId)
                + " stableKey=" + stableKey
                + " decision=" + (decision == null ? "ALLOW" : decision.reason)
                + " action=" + action
                + " size=" + view.getWidth() + "x" + view.getHeight()
                + suppressedSummary(permit));
    }

    private void emitCellMeasureTrace(View view, DecisionContext context,
                                      int measuredWidthBefore, int measuredHeightBefore,
                                      int measuredWidthAfter, int measuredHeightAfter) {
        if (view == null) {
            return;
        }
        int viewWidth = view.getWidth();
        boolean widthMismatch = measuredWidthBefore > 0 && viewWidth > 0
                && (measuredWidthBefore * 100 < viewWidth * 72
                || viewWidth * 100 < measuredWidthBefore * 72);
        boolean invalidSize = measuredWidthBefore < 0 || measuredHeightBefore < 0
                || measuredWidthAfter < 0 || measuredHeightAfter < 0;
        if (!widthMismatch && !invalidSize) {
            return;
        }
        GroupInfo group = context == null || context.groupInfo == null
                ? GroupInfo.NONE
                : context.groupInfo;
        String stableKey = context == null ? "" : context.stableKey;
        String dedupKey = "measure|" + System.identityHashCode(view)
                + "|" + measuredWidthBefore + "x" + measuredHeightBefore
                + "|" + measuredWidthAfter + "x" + measuredHeightAfter;
        UiTraceRateLimiter.Permit permit = acquireUiTracePermit(dedupKey);
        if (permit == null || !permit.emit) {
            return;
        }
        FilterDecision decision = context == null ? null : context.decision;
        String action = decision != null && decision.excluded
                ? "EXCLUDED"
                : decision != null && decision.matched && decision.action != null
                ? decision.action.name()
                : "ALLOW";
        info("FilterUiTrace measure-anomaly view=" + view.getClass().getSimpleName()
                + " groupId=" + (group.grouped ? group.groupId : 0L)
                + " groupCount=" + (group.grouped ? group.count : 0)
                + " groupPosition=" + (group.grouped ? group.position : -1)
                + " dialog=" + (context == null || context.snapshot == null ? 0L : context.snapshot.dialogId)
                + " message=" + (context == null || context.snapshot == null ? 0L : context.snapshot.messageId)
                + " stableKey=" + stableKey
                + " decision=" + (decision == null ? "ALLOW" : decision.reason)
                + " action=" + action
                + " measuredBefore=" + measuredWidthBefore + "x" + measuredHeightBefore
                + " measuredAfter=" + measuredWidthAfter + "x" + measuredHeightAfter
                + " viewSize=" + viewWidth + "x" + view.getHeight()
                + suppressedSummary(permit));
    }

    private String suppressedSummary(UiTraceRateLimiter.Permit permit) {
        return permit.suppressed > 0 ? " suppressed=" + permit.suppressed : "";
    }

    private UiTraceRateLimiter.Permit acquireUiTracePermit(String key) {
        long now = System.currentTimeMillis();
        synchronized (recentUiTraceKeys) {
            Long previous = recentUiTraceKeys.get(key);
            if (previous != null && now - previous < 10_000L) {
                return null;
            }
            recentUiTraceKeys.put(key, now);
            return uiTraceRateLimiter.acquire(now);
        }
    }

    private void markFilteredMessageRead(Object messageObject, MessageSnapshot snapshot, FilterDecision decision) {
        if (!shouldMarkFilteredMessageRead(messageObject, snapshot, decision)) {
            return;
        }
        int messageId = safeMessageId(snapshot);
        String key = snapshot.dialogId + ":" + messageId + ":" + decision.ruleId;
        if (!rememberReadMarkKey(key)) {
            return;
        }
        try {
            Object controller = resolveMessagesController(messageObject);
            if (controller == null) {
                emitReadMarkProbe("controller-missing", snapshot, decision, null);
                return;
            }
            Reflect.invokeIfExists(messageObject, "setIsRead", new Class<?>[0]);
            long topicId = resolveTopicId(messageObject);
            if (invokeMarkDialogAsRead(controller, snapshot.dialogId, messageId, topicId)) {
                decrementDialogUnreadCount(controller, snapshot.dialogId);
                emitReadMarkProbe("marked", snapshot, decision, null);
            } else {
                emitReadMarkProbe("method-missing", snapshot, decision, null);
            }
        } catch (RuntimeException exception) {
            emitReadMarkProbe("failed", snapshot, decision, exception);
        }
    }

    private void markLoadedFilteredMessagesAsRead(Object chatActivity) {
        try {
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
            if (dialogId == 0L) {
                return;
            }
            Object messages = Reflect.field(chatActivity, "messages");
            if (!(messages instanceof java.util.List)) {
                return;
            }
            java.util.List<?> msgList = (java.util.List<?>) messages;
            if (msgList.isEmpty()) {
                return;
            }
            FilterConfig config = configProvider.getConfig(resolveContextFromActivity(chatActivity));
            if (config == null || !config.enabled) {
                return;
            }
            int filteredCount = 0;
            int latestMessageId = 0;
            Object anyMessageObject = null;
            for (Object msg : msgList) {
                if (msg == null) continue;
                if (anyMessageObject == null) {
                    anyMessageObject = msg;
                }
                int id = resolveMessageId(msg);
                if (id > latestMessageId) {
                    latestMessageId = id;
                }
                MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(null, msg);
                if (snapshot == null) continue;
                FilterDecision decision = decisionCache.get(config, snapshot, () -> filterEngine.evaluate(config, snapshot));
                if (decision.matched && !decision.excluded) {
                    filteredCount++;
                }
            }
            if (filteredCount > 0 && anyMessageObject != null && latestMessageId > 0) {
                Object controller = resolveMessagesController(anyMessageObject);
                if (controller != null) {
                    long topicId = 0;
                    invokeMarkDialogAsRead(controller, dialogId, latestMessageId, topicId);
                    decrementDialogUnreadByFilteredCount(controller, dialogId, filteredCount);
                    info("Pause: markDialogAsRead dialog=" + dialogId + " maxId=" + latestMessageId + " filtered=" + filteredCount);
                }
            }
        } catch (Throwable throwable) {
            error("Pause filtered message scan failed", throwable);
        }
    }

    private void decrementDialogUnreadByFilteredCount(Object controller, long dialogId, int filteredCount) {
        try {
            Object dialog = resolveDialog(controller, dialogId);
            if (dialog == null) {
                return;
            }
            int currentUnread = Reflect.asInt(Reflect.field(dialog, "unread_count"), 0);
            if (currentUnread <= 0) {
                return;
            }
            int decrement = Math.min(currentUnread, filteredCount);
            int newCount = currentUnread - decrement;
            java.lang.reflect.Field unreadField = findDialogUnreadCountField(dialog.getClass());
            if (unreadField == null) {
                return;
            }
            unreadField.setAccessible(true);
            Class<?> type = unreadField.getType();
            if (type == int.class) {
                unreadField.setInt(dialog, newCount);
            } else if (type == Integer.class) {
                unreadField.set(dialog, newCount);
            } else if (type == long.class) {
                unreadField.setLong(dialog, newCount);
            } else if (type == Long.class) {
                unreadField.set(dialog, (long) newCount);
            }
            if (decrement > 0) {
                info("ReadMark-decr: unread_count " + currentUnread + " -> " + newCount + " (filtered=" + filteredCount + ") dialog=" + dialogId);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldMarkFilteredMessageRead(Object messageObject, MessageSnapshot snapshot, FilterDecision decision) {
        if (messageObject == null || snapshot == null || decision == null || !decision.matched || decision.excluded) {
            return false;
        }
        if (decision.action == FilterConfig.Action.DEBUG_MARK || safeMessageId(snapshot) <= 0 || snapshot.dialogId == 0L) {
            return false;
        }
        Object out = Reflect.invokeIfExists(messageObject, "isOut", new Class<?>[0]);
        if (Boolean.TRUE.equals(out)) {
            return false;
        }
        Object outOwner = Reflect.invokeIfExists(messageObject, "isOutOwner", new Class<?>[0]);
        if (Boolean.TRUE.equals(outOwner)) {
            return false;
        }
        Object unread = Reflect.invokeIfExists(messageObject, "isUnread", new Class<?>[0]);
        if (Boolean.FALSE.equals(unread)) {
            info("ReadMark-skip: isUnread=false dialog=" + snapshot.dialogId + " msg=" + snapshot.messageId);
            return false;
        }
        Object messageOwner = Reflect.field(messageObject, "messageOwner");
        Object ownerUnread = Reflect.field(messageOwner, "unread");
        if (Boolean.FALSE.equals(ownerUnread)) {
            info("ReadMark-skip: ownerUnread=false dialog=" + snapshot.dialogId + " msg=" + snapshot.messageId);
            return false;
        }
        return true;
    }

    private boolean rememberReadMarkKey(String key) {
        synchronized (recentReadMarkKeys) {
            if (recentReadMarkKeys.containsKey(key)) {
                return false;
            }
            recentReadMarkKeys.put(key, System.currentTimeMillis());
            return true;
        }
    }

    private Object resolveMessagesController(Object messageObject) {
        try {
            int account = Reflect.asInt(Reflect.field(messageObject, "currentAccount"), 0);
            ClassLoader classLoader = messageObject.getClass().getClassLoader();
            Class<?> controllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            return Reflect.invokeStatic(controllerClass, "getInstance", new Class<?>[]{int.class}, account);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }

    private long resolveTopicId(Object messageObject) {
        Object topicId = Reflect.invokeIfExists(messageObject, "getTopicId", new Class<?>[0]);
        return Reflect.asLong(topicId, 0L);
    }

    private boolean invokeMarkDialogAsRead(Object controller, long dialogId, int messageId, long topicId) {
        try {
            Method method = Reflect.method(
                    controller.getClass(),
                    "markDialogAsRead",
                    long.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    long.class,
                    int.class,
                    boolean.class,
                    int.class
            );
            Reflect.invoke(method, controller, dialogId, messageId, messageId, 0, true, topicId, 0, true, 0);
            return true;
        } catch (NoSuchMethodException ignored) {
            return invokeCompatibleMarkDialogAsRead(controller, dialogId, messageId, topicId);
        }
    }

    private boolean invokeCompatibleMarkDialogAsRead(Object controller, long dialogId, int messageId, long topicId) {
        Class<?> current = controller.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"markDialogAsRead".equals(method.getName())) {
                    continue;
                }
                Object[] args = buildMarkDialogAsReadArgs(method.getParameterTypes(), dialogId, messageId, topicId);
                if (args == null) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Reflect.invoke(method, controller, args);
                    return true;
                } catch (RuntimeException ignored) {
                    // Try the next overload if Telegram changed the parameter semantics.
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private void decrementDialogUnreadCount(Object controller, long dialogId) {
        try {
            Object dialog = resolveDialog(controller, dialogId);
            if (dialog == null) {
                info("ReadMark-decr: dialog not found dialog=" + dialogId);
                return;
            }
            int currentUnread = Reflect.asInt(Reflect.field(dialog, "unread_count"), 0);
            info("ReadMark-decr: dialog found unread_count=" + currentUnread + " dialog=" + dialogId);
            if (currentUnread <= 0) {
                return;
            }
            java.lang.reflect.Field unreadField = findDialogUnreadCountField(dialog.getClass());
            if (unreadField != null) {
                unreadField.setAccessible(true);
                Class<?> type = unreadField.getType();
                int newCount = Math.max(0, currentUnread - 1);
                if (type == int.class) {
                    unreadField.setInt(dialog, newCount);
                } else if (type == Integer.class) {
                    unreadField.set(dialog, newCount);
                } else if (type == long.class) {
                    unreadField.setLong(dialog, newCount);
                } else if (type == Long.class) {
                    unreadField.set(dialog, (long) newCount);
                }
                info("ReadMark-decr: updated unread_count " + currentUnread + " -> " + newCount + " dialog=" + dialogId);
            } else {
                info("ReadMark-decr: unread_count field not found class=" + dialog.getClass().getName());
            }
        } catch (Throwable throwable) {
            info("ReadMark-decr: exception " + throwable.getMessage());
        }
    }

    private java.lang.reflect.Field findDialogUnreadCountField(Class<?> clazz) {
        String[] names = {"unread_count", "unreadCount"};
        for (String name : names) {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object resolveDialog(Object controller, long dialogId) {
        Object dialog = Reflect.invokeIfExists(controller, "getDialog", new Class<?>[]{long.class}, dialogId);
        if (dialog != null) {
            return dialog;
        }
        Object dialogsStorage = Reflect.invokeIfExists(controller, "getDialogsStorage", new Class<?>[0]);
        if (dialogsStorage != null) {
            dialog = Reflect.invokeIfExists(dialogsStorage, "getDialog", new Class<?>[]{long.class}, dialogId);
            if (dialog != null) {
                return dialog;
            }
        }
        Object dialogsDict = Reflect.field(controller, "dialogs_dict");
        if (dialogsDict != null) {
            try {
                java.lang.reflect.Method getMethod = dialogsDict.getClass().getMethod("get", long.class);
                dialog = getMethod.invoke(dialogsDict, dialogId);
            } catch (Throwable ignored) {
            }
        }
        if (dialog == null) {
            info("ReadMark-resolve: all lookup methods failed for dialog=" + dialogId);
        }
        return dialog;
    }

    private Object[] buildMarkDialogAsReadArgs(Class<?>[] parameterTypes, long dialogId, int messageId, long topicId) {
        Object[] args = new Object[parameterTypes.length];
        int longCount = 0;
        int intCount = 0;
        int booleanCount = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType == long.class || parameterType == Long.class) {
                longCount++;
                args[i] = longCount == 1 ? dialogId : (longCount == 2 ? topicId : 0L);
            } else if (parameterType == int.class || parameterType == Integer.class) {
                intCount++;
                args[i] = intCount <= 2 ? messageId : 0;
            } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                booleanCount++;
                args[i] = booleanCount <= 2;
            } else {
                return null;
            }
        }
        return longCount >= 1 && intCount >= 1 ? args : null;
    }

    private int safeMessageId(MessageSnapshot snapshot) {
        if (snapshot == null || snapshot.messageId <= 0L || snapshot.messageId > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) snapshot.messageId;
    }

    private void emitReadMarkProbe(String state, MessageSnapshot snapshot, FilterDecision decision, RuntimeException exception) {
        int remaining = readMarkProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        String message = "ReadMark state=" + state
                + " dialog=" + (snapshot == null ? 0L : snapshot.dialogId)
                + " msg=" + (snapshot == null ? 0L : snapshot.messageId)
                + " ruleId=" + (decision == null ? "" : decision.ruleId);
        if (exception == null) {
            info(message);
        } else {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, message + " error=" + exception.getMessage());
        }
    }

    private Object resolveMessageObject(Object cell) {
        Object messageObject = Reflect.invokeIfExists(cell, "getMessageObject", new Class<?>[0]);
        if (messageObject != null) {
            return messageObject;
        }
        Object currentMessageObject = Reflect.field(cell, "currentMessageObject");
        if (currentMessageObject != null) {
            return currentMessageObject;
        }
        Object fieldMessageObject = Reflect.field(cell, "messageObject");
        if (fieldMessageObject != null) {
            return fieldMessageObject;
        }
        return null;
    }

    private void emitHookEntry(String source, Object target, Object payload) {
        int remaining = hookEntryBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "HookEntry source=" + source
                        + " target=" + classNameOf(target)
                        + " payload=" + classNameOf(payload)
        );
    }

    private String classNameOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private void emitDecisionProbe(FilterConfig config, MessageSnapshot snapshot, FilterDecision decision) {
        int remaining = decisionProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "DecisionProbe action=" + config.action
                        + " debug=" + config.debugLogging
                        + " globalRules=" + config.globalRules.size()
                        + " chatRules=" + config.chatRules.size()
                        + " updatedAt=" + config.updatedAtEpochMs
                        + " matched=" + decision.matched
                        + " excluded=" + decision.excluded
                        + " reason=" + preview(decision.reason)
                        + " " + LogPrivacy.field("chat", snapshot.chatName)
                        + " " + LogPrivacy.field("sender", snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " " + LogPrivacy.field("text", snapshot.text)
        );
    }

    private void emitBindingProbe(FilterConfig config, Object cell, MessageSnapshot snapshot, FilterDecision decision) {
        if (!config.debugLogging) {
            return;
        }
        int remaining = bindingProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "BindProbe cell=" + cell.getClass().getSimpleName()
                        + " " + LogPrivacy.field("chat", snapshot.chatName)
                        + " " + LogPrivacy.field("sender", snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " msg=" + snapshot.messageId
                        + " " + LogPrivacy.field("text", snapshot.text)
                        + " " + LogPrivacy.field("caption", snapshot.caption)
                        + " " + LogPrivacy.field("buttons", snapshot.buttonText)
                        + " decision=" + decision.reason
        );
    }

    private void persistDiagnostic(Context context, DecisionContext decisionContext) {
        if (context == null || decisionContext == null || decisionContext.config == null || decisionContext.snapshot == null) {
            return;
        }
        if (!decisionContext.config.debugLogging) {
            return;
        }
        if (persistentDiagnosticsUnavailable) {
            return;
        }
        if (!shouldPersistDiagnostic(decisionContext)) {
            return;
        }
        DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();
        entry.timestampEpochMs = System.currentTimeMillis();
        entry.category = "decision";
        entry.matched = decisionContext.decision.matched;
        entry.excluded = decisionContext.decision.excluded;
        entry.action = decisionContext.decision.matched ? decisionContext.decision.action.name() : "";
        entry.ruleId = decisionContext.decision.ruleId;
        entry.reason = decisionContext.decision.reason;
        entry.likelyGambling = FilterEngine.isLikelyGamblingPromotion(decisionContext.snapshot);
        entry.globalRuleCount = decisionContext.config.globalRules.size();
        entry.chatRuleSetCount = decisionContext.config.chatRules.size();
        entry.dialogId = decisionContext.snapshot.dialogId;
        entry.senderId = decisionContext.snapshot.senderId;
        entry.messageId = decisionContext.snapshot.messageId;
        entry.stableKey = decisionContext.stableKey;
        DiagnosticLogStore.setMessageDetails(
                entry,
                decisionContext.snapshot.chatName,
                decisionContext.snapshot.senderName,
                decisionContext.snapshot.text,
                decisionContext.snapshot.caption,
                decisionContext.snapshot.buttonText
        );
        entry.hasInlineButtons = decisionContext.snapshot.hasInlineButtons;
        Bundle extras = new Bundle();
        extras.putString(ConfigContentProvider.KEY_DIAGNOSTIC_ENTRY_JSON, DiagnosticLogStore.entryToJson(entry));
        try {
            context.getContentResolver().call(
                    ConfigContentProvider.CONTENT_URI,
                    ConfigContentProvider.METHOD_APPEND_DIAGNOSTIC,
                    null,
                    extras
            );
        } catch (RuntimeException exception) {
            persistentDiagnosticsUnavailable = true;
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, "Persistent diagnostic append disabled: " + exception.getMessage());
        }
    }

    private boolean shouldPersistDiagnostic(DecisionContext decisionContext) {
        MessageSnapshot snapshot = decisionContext.snapshot;
        FilterDecision decision = decisionContext.decision;
        boolean interesting = decision.matched
                || decision.excluded
                || FilterEngine.isLikelyGamblingPromotion(snapshot)
                || !snapshot.caption.isBlank()
                || !snapshot.buttonText.isBlank();
        if (!interesting) {
            return false;
        }
        String key = decisionContext.stableKey + "|" + decision.reason + "|" + decision.matched + "|" + decision.excluded;
        long now = System.currentTimeMillis();
        synchronized (recentDiagnosticKeys) {
            Long previous = recentDiagnosticKeys.get(key);
            if (previous != null && now - previous < 120_000L) {
                return false;
            }
            recentDiagnosticKeys.put(key, now);
            return true;
        }
    }

    private String preview(String value) {
        if (value == null) {
            return "\"\"";
        }
        String normalized = value.replace('\n', ' ').trim();
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48) + "...";
        }
        return "\"" + normalized + "\"";
    }

    private static final class DecisionContext {
        final FilterConfig config;
        final MessageSnapshot snapshot;
        final FilterDecision decision;
        final String stableKey;
        final GroupInfo groupInfo;
        final Object messageObject;

        DecisionContext(FilterConfig config, MessageSnapshot snapshot, FilterDecision decision,
                        GroupInfo groupInfo, Object messageObject) {
            this.config = config;
            this.snapshot = snapshot;
            this.decision = decision;
            this.stableKey = snapshot == null ? "" : snapshot.stableKey();
            this.groupInfo = groupInfo == null ? GroupInfo.NONE : groupInfo;
            this.messageObject = messageObject;
        }

        static DecisionContext allow() {
            return allow(GroupInfo.NONE);
        }

        static DecisionContext allow(GroupInfo groupInfo) {
            return allow(groupInfo, null);
        }

        static DecisionContext allow(GroupInfo groupInfo, Object messageObject) {
            return new DecisionContext(null, null, FilterDecision.allow(), groupInfo, messageObject);
        }

        String mutationKey() {
            if (!groupInfo.grouped) {
                return stableKey;
            }
            return "group:" + groupInfo.groupId + ":"
                    + (snapshot == null ? 0L : snapshot.dialogId);
        }
    }

    private static final class GroupInfo {
        static final GroupInfo NONE = new GroupInfo(false, 0L, 0, -1, Collections.emptyList());

        final boolean grouped;
        final long groupId;
        final int count;
        final int position;
        final List<?> messages;

        GroupInfo(long groupId, int count, int position, List<?> messages) {
            this(true, groupId, count, position, messages);
        }

        GroupInfo(boolean grouped, long groupId, int count, int position, List<?> messages) {
            this.grouped = grouped;
            this.groupId = groupId;
            this.count = Math.max(0, count);
            this.position = position;
            this.messages = messages == null ? Collections.emptyList() : messages;
        }
    }

    private void injectMessageBlockMenu(Object chatActivity, View messageView) {
        if (chatActivity == null || messageView == null) {
            return;
        }
        Object popupWindow = Reflect.field(chatActivity, "scrimPopupWindow");
        Object contentView = Reflect.invokeIfExists(popupWindow, "getContentView", new Class<?>[0]);
        if (!(contentView instanceof View) || hasTaggedChild((View) contentView, MENU_ID_BLOCK_MESSAGE)) {
            return;
        }

        Object messageObject = Reflect.field(chatActivity, "selectedObject");
        if (messageObject == null) {
            messageObject = resolveMessageObject(messageView);
        }
        if (messageObject == null) {
            return;
        }

        View blockItem = createMessageBlockMenuItem(((View) contentView).getContext(), chatActivity);
        if (blockItem == null) {
            return;
        }
        Object selectedMessageObject = messageObject;
        blockItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_BLOCK_MESSAGE);
        uiCallbacks.setClickListener(blockItem, v -> {
            dismissScrimPopup(chatActivity);
            addRuleForSelectedMessage(v.getContext(), messageView, selectedMessageObject);
        });

        View markItem = createMessageMarkMenuItem(((View) contentView).getContext(), chatActivity);
        if (markItem != null) {
            markItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_MARK_MESSAGE);
            uiCallbacks.setClickListener(markItem, v -> {
                dismissScrimPopup(chatActivity);
                markSelectedMessage(v.getContext(), chatActivity, selectedMessageObject);
            });
        }
        View editHistoryItem = !usesModuleFallback(ModuleConflictDetector.ConflictKind.EDIT_HISTORY)
                && resolveSelectedEditHistory(chatActivity, selectedMessageObject) != null
                ? createEditHistoryMenuItem(((View) contentView).getContext(), chatActivity)
                : null;
        if (editHistoryItem != null) {
            editHistoryItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_EDIT_HISTORY);
            uiCallbacks.setClickListener(editHistoryItem, v -> {
                dismissScrimPopup(chatActivity);
                showSelectedMessageEditHistory(v, chatActivity, selectedMessageObject);
            });
        }
        View reloadItem = isEnhancementEnabled(
                ((View) contentView).getContext(),
                EnhancementConfig.Feature.RELOAD_MESSAGE
        ) ? createReloadMessageMenuItem(((View) contentView).getContext(), chatActivity) : null;
        if (reloadItem != null) {
            reloadItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_RELOAD_MESSAGE);
            uiCallbacks.setClickListener(reloadItem, v -> {
                dismissScrimPopup(chatActivity);
                reloadSelectedMessage(v.getContext(), chatActivity, selectedMessageObject);
            });
        }

        View popupContent = (View) contentView;
        View nativeDeleteItem = findNativeDeleteMenuItem(popupContent);
        MenuInsertionPoint reportPoint = findReportInsertionPoint(popupContent);
        ViewGroup nativeItemParent = nativeDeleteItem != null
                && nativeDeleteItem.getParent() instanceof ViewGroup
                ? (ViewGroup) nativeDeleteItem.getParent()
                : null;
        ViewGroup targetContainer = nativeItemParent != null
                ? nativeItemParent
                : reportPoint != null
                ? reportPoint.parent
                : resolvePopupLinearLayout(contentView);
        if (targetContainer == null) {
            return;
        }

        // Telegram measures and positions this popup before createMenu returns. Keep every native
        // item at its original index and append our entries, then explicitly remeasure the window.
        // Inserting in the middle can move a visible native item outside the original touch region.
        targetContainer.addView(blockItem);
        if (markItem != null) {
            targetContainer.addView(markItem);
        }
        if (editHistoryItem != null) {
            targetContainer.addView(editHistoryItem);
        }
        if (reloadItem != null) {
            targetContainer.addView(reloadItem);
        }

        int deleteItemIndex = nativeDeleteItem != null
                && nativeDeleteItem.getParent() == targetContainer
                ? targetContainer.indexOfChild(nativeDeleteItem)
                : -1;
        messageDeleteDiagnostics.recordPopup(
                trackedDialogId,
                resolveMessageId(selectedMessageObject),
                nativeDeleteItem != null,
                nativeDeleteItem != null && nativeDeleteItem.isClickable(),
                nativeDeleteItem != null && nativeDeleteItem.hasOnClickListeners(),
                deleteItemIndex,
                targetContainer.getChildCount(),
                popupContent.getMeasuredWidth(),
                popupContent.getMeasuredHeight()
        );
        info("MessageDeleteFlow: popup injected nativeDelete=" + (nativeDeleteItem != null)
                + " clickable=" + (nativeDeleteItem != null && nativeDeleteItem.isClickable())
                + " listener=" + (nativeDeleteItem != null && nativeDeleteItem.hasOnClickListeners())
                + " index=" + deleteItemIndex
                + " items=" + targetContainer.getChildCount());
        refreshMessagePopup(popupContent, blockItem, popupWindow);
    }

    private ViewGroup resolvePopupLinearLayout(Object contentView) {
        if (!(contentView instanceof ViewGroup)) {
            return null;
        }
        // Look for ActionBarPopupWindowLayout in children
        ViewGroup group = (ViewGroup) contentView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().equals("ActionBarPopupWindowLayout")) {
                return (ViewGroup) child;
            }
        }
        Object linearLayout = Reflect.field(contentView, "linearLayout");
        if (linearLayout instanceof ViewGroup) {
            return (ViewGroup) linearLayout;
        }
        return group;
    }

    private View createMessageBlockMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = localizedBlockMessageLabel(context);
            int iconRes = resolveBlockMessageIcon(context);
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create block-message menu item", throwable);
            return null;
        }
    }

    private int resolveBlockMessageIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("report", "drawable", telegramResourcePackageName);
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_close_clear_cancel;
    }

    private View createMessageMarkMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = localizedMarkMessageLabel(context);
            int iconRes = resolveMarkMessageIcon(context);
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create mark-message menu item", throwable);
            return null;
        }
    }

    private int resolveMarkMessageIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_bookmark", "drawable", telegramResourcePackageName);
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
    }

    private View createEditHistoryMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = isChineseLocale(context) ? "编辑历史" : "Edit History";
            int iconRes = context.getResources().getIdentifier("msg_edit", "drawable", telegramResourcePackageName);
            if (iconRes == 0) iconRes = android.R.drawable.ic_menu_edit;
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create edit-history menu item", throwable);
            return null;
        }
    }

    private View createReloadMessageMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = isChineseLocale(context) ? "重新加载这条消息" : "Reload this message";
            int iconRes = context.getResources().getIdentifier("msg_retry", "drawable", telegramResourcePackageName);
            if (iconRes == 0) {
                iconRes = android.R.drawable.ic_popup_sync;
            }
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create reload-message menu item", throwable);
            return null;
        }
    }

    private void reloadSelectedMessage(Context context, Object chatActivity, Object messageObject) {
        int messageId = resolveMessageId(messageObject);
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        if (messageId <= 0 || dialogId == 0L) {
            return;
        }
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> controllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            int account = resolveSelectedTelegramAccount(classLoader);
            Object controller = Reflect.invokeStatic(
                    controllerClass,
                    "getInstance",
                    new Class<?>[]{int.class},
                    account
            );
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(messageId);
            boolean invoked = false;
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!"reloadMessages".equals(method.getName())) {
                    continue;
                }
                Object[] args = buildReloadMessageArgs(method.getParameterTypes(), ids, dialogId);
                if (args == null) {
                    continue;
                }
                method.setAccessible(true);
                method.invoke(controller, args);
                invoked = true;
                break;
            }
            if (!invoked) {
                throw new NoSuchMethodException("MessagesController.reloadMessages");
            }
            Toast.makeText(
                    context,
                    isChineseLocale(context) ? "已请求重新加载" : "Reload requested",
                    Toast.LENGTH_SHORT
            ).show();
            info("Reloaded message " + messageId + " for dialog " + dialogId);
        } catch (Throwable throwable) {
            error("Reload selected message failed", throwable);
            Toast.makeText(
                    context,
                    isChineseLocale(context) ? "当前 Telegram 版本不支持重新加载" : "Reload is unavailable in this Telegram version",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private Object[] buildReloadMessageArgs(Class<?>[] parameterTypes, ArrayList<Integer> ids, long dialogId) {
        Object[] args = new Object[parameterTypes.length];
        boolean hasIds = false;
        boolean hasDialog = false;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> type = parameterTypes[i];
            if (List.class.isAssignableFrom(type) || ArrayList.class.isAssignableFrom(type)) {
                args[i] = ids;
                hasIds = true;
            } else if (type == long.class || type == Long.class) {
                args[i] = hasDialog ? 0L : dialogId;
                hasDialog = true;
            } else if (type == int.class || type == Integer.class) {
                args[i] = 0;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = true;
            } else {
                args[i] = null;
            }
        }
        return hasIds && hasDialog ? args : null;
    }

    private boolean isEnhancementEnabled(Context context, EnhancementConfig.Feature feature) {
        if (context == null || configProvider == null) {
            return false;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        FilterConfig config = configProvider.getConfig(appContext);
        return config.enhancements != null
                && config.enhancements.isEnabledForGramSieve(feature);
    }

    private void showSelectedMessageEditHistory(View anchor, Object chatActivity, Object messageObject) {
        if (usesModuleFallback(ModuleConflictDetector.ConflictKind.EDIT_HISTORY)) {
            return;
        }
        MessageCache.CachedMessage cached = resolveSelectedEditHistory(chatActivity, messageObject);
        if (cached == null) {
            Toast.makeText(anchor.getContext(), localizedNoEditHistory(anchor.getContext()), Toast.LENGTH_SHORT).show();
            return;
        }
        List<MessageCache.CachedMessage> history = messageCache != null
                ? messageCache.getEditHistory(cached.accountId, cached.dialogId, cached.messageId)
                : java.util.Collections.emptyList();
        if (history.size() > 1) {
            showEditHistoryDialog(anchor, history, cached);
            return;
        }
        if (showOriginalMediaHistory(anchor, chatActivity, messageObject, cached)) {
            return;
        }
        showEditHistoryDialog(anchor, history, cached);
    }

    private MessageCache.CachedMessage resolveSelectedEditHistory(Object chatActivity, Object messageObject) {
        if (messageCache == null || messageObject == null) {
            return null;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId == 0L) {
            dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        }
        int messageId = resolveMessageId(messageObject);
        if (dialogId == 0L || messageId <= 0) {
            return null;
        }
        int accountId = TelegramAccountResolver.resolveWithFallback(
                savedClassLoader, messageObject, chatActivity,
                Reflect.field(messageObject, "messageOwner"));
        MessageCache.CachedMessage cached = messageCache.get(accountId, dialogId, messageId);
        if (hasRenderableEditHistory(cached)) {
            return cached;
        }
        List<MessageCache.CachedMessage> history = messageCache.getEditHistory(accountId, dialogId, messageId);
        return history.isEmpty() ? null : history.get(0);
    }

    private boolean showOriginalMediaHistory(View anchor, Object chatActivity, Object selectedMessageObject,
                                             MessageCache.CachedMessage cachedMessage) {
        Object mediaObject = messageCache != null
                ? messageCache.getMediaObject(cachedMessage.accountId,
                        cachedMessage.dialogId, cachedMessage.messageId)
                : null;
        java.io.File file = resolveCachedMediaFile(cachedMessage);
        if (file == null) {
            info("Anti-recall: original media history file missing msgId=" + cachedMessage.messageId
                    + " mediaType=" + cachedMessage.mediaType
                    + " mediaId=" + cachedMessage.mediaId
                    + " cachedPath=" + cachedMessage.cachedMediaPath
                    + " hasTelegramMedia=" + (mediaObject != null));
            if (mediaObject != null && mediaPrefetcher != null && selectedMessageObject != null) {
                mediaPrefetcher.prefetchFromMessage(
                        cachedMessage.accountId,
                        cachedMessage.dialogId,
                        cachedMessage.messageId,
                        selectedMessageObject,
                        mediaObject
                );
                Toast.makeText(anchor.getContext(), localizedMediaHistoryPreparing(anchor.getContext()), Toast.LENGTH_SHORT).show();
                return true;
            }
            if (!hasDisplayText(cachedMessage.text)
                    && !hasDisplayText(cachedMessage.caption)
                    && !hasDisplayText(cachedMessage.editedText)) {
                Toast.makeText(anchor.getContext(), localizedOriginalMediaUnavailable(anchor.getContext()), Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }
        if (mediaObject != null
                && showOriginalMediaMessageInPhotoViewer(anchor, chatActivity, selectedMessageObject, cachedMessage, file, mediaObject, false)) {
            return true;
        }
        if (!isVideoHistoryFile(cachedMessage, file)
                && showOriginalSyntheticPhotoInPhotoViewer(anchor, chatActivity, selectedMessageObject, cachedMessage, file)) {
            return true;
        }
        info("Anti-recall: using local media history preview msgId=" + cachedMessage.messageId
                + " hasTelegramMedia=" + (mediaObject != null));
        return showOriginalMediaDialog(anchor, cachedMessage, file);
    }

    private boolean hasRenderableEditHistory(MessageCache.CachedMessage cachedMessage) {
        if (cachedMessage == null || !cachedMessage.isEdited) {
            return false;
        }
        if (hasDisplayText(cachedMessage.text)
                || hasDisplayText(cachedMessage.caption)
                || hasDisplayText(cachedMessage.editedText)) {
            return true;
        }
        if (resolveCachedMediaFile(cachedMessage) != null) {
            return true;
        }
        return messageCache != null
                && messageCache.getMediaObject(cachedMessage.accountId,
                        cachedMessage.dialogId, cachedMessage.messageId) != null;
    }

    private static boolean hasDisplayText(String value) {
        return sanitizeDisplayText(value) != null;
    }

    private boolean showOriginalSyntheticPhotoInPhotoViewer(View anchor, Object chatActivity, Object selectedMessageObject,
                                                           MessageCache.CachedMessage cachedMessage, java.io.File file) {
        try {
            ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
            Object mediaObject = createSyntheticHistoryPhotoMedia(classLoader, cachedMessage, file);
            boolean opened = showOriginalMediaMessageInPhotoViewer(
                    anchor,
                    chatActivity,
                    selectedMessageObject,
                    cachedMessage,
                    file,
                    mediaObject,
                    true
            );
            if (opened) {
                info("Anti-recall: opened original image history with synthetic Telegram photo msgId="
                        + cachedMessage.messageId + " file=" + file.getName());
            }
            return opened;
        } catch (Throwable throwable) {
            error("Anti-recall: synthetic Telegram photo open failed", throwable);
            return false;
        }
    }

    private boolean showOriginalMediaMessageInPhotoViewer(View anchor, Object chatActivity, Object selectedMessageObject,
                                                         MessageCache.CachedMessage cachedMessage, java.io.File file,
                                                         Object mediaObject, boolean isolateIdentity) {
        try {
            ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
            if (mediaObject == null) {
                return false;
            }

            int account = cachedMessage.accountId;
            Object message = createSyntheticHistoryMessage(classLoader, selectedMessageObject, cachedMessage, mediaObject, file, isolateIdentity);
            java.io.File telegramPath = syncHistoryFileToTelegramPath(classLoader, account, message, file);
            if (telegramPath == null) {
                return false;
            }
            Object messageObject = createTelegramMessageObject(classLoader, account, message);
            if (messageObject == null) {
                return false;
            }

            boolean opened = openTelegramPhotoViewerWithMessage(anchor, chatActivity, cachedMessage, messageObject, isolateIdentity);
            if (opened) {
                info("Anti-recall: opened original media history in official PhotoViewer msgId="
                        + cachedMessage.messageId + " file=" + file.getName()
                        + " isolated=" + isolateIdentity
                        + " telegramPath=" + telegramPath.getAbsolutePath());
            }
            return opened;
        } catch (Throwable throwable) {
            error("Anti-recall: official PhotoViewer message open failed", throwable);
            return false;
        }
    }

    private boolean showOriginalMediaDialog(View anchor, MessageCache.CachedMessage cachedMessage, java.io.File file) {
        if (isVideoHistoryFile(cachedMessage, file)) {
            return showOriginalVideoHistory(anchor, cachedMessage, file);
        }
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            return false;
        }
        Context context = anchor.getContext();
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        uiCallbacks.trackDialog(dialog);
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        ImageView imageView = new ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        root.addView(imageView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        uiCallbacks.setClickListener(root, v -> dialog.dismiss());
        dialog.setContentView(root);
        dialog.show();
        info("Anti-recall: opened original image history locally msgId=" + cachedMessage.messageId + " file=" + file.getName());
        return true;
    }

    private boolean showOriginalVideoHistory(View anchor, MessageCache.CachedMessage cachedMessage, java.io.File file) {
        Context context = anchor.getContext();
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        uiCallbacks.trackDialog(dialog);
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        android.widget.VideoView videoView = new android.widget.VideoView(context);
        android.widget.MediaController controller = new android.widget.MediaController(context);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoPath(file.getAbsolutePath());
        root.addView(videoView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ImageButton closeButton = new ImageButton(context);
        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        closeButton.setColorFilter(android.graphics.Color.WHITE);
        uiCallbacks.setClickListener(closeButton, v -> dialog.dismiss());
        android.widget.FrameLayout.LayoutParams closeParams = new android.widget.FrameLayout.LayoutParams(
                dp(context, 48f),
                dp(context, 48f),
                android.view.Gravity.TOP | android.view.Gravity.END
        );
        closeParams.topMargin = dp(context, 12f);
        closeParams.rightMargin = dp(context, 12f);
        root.addView(closeButton, closeParams);

        videoView.setOnPreparedListener(player -> {
            videoView.start();
            controller.show(3000);
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(context, localizedNoEditHistory(context), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            return true;
        });
        dialog.setOnDismissListener(ignored -> videoView.stopPlayback());
        dialog.setContentView(root);
        dialog.show();
        info("Anti-recall: opened original video history locally msgId=" + cachedMessage.messageId + " file=" + file.getName());
        return true;
    }

    private ClassLoader resolveTelegramClassLoader(Context context, Object chatActivity) {
        if (savedClassLoader != null) {
            return savedClassLoader;
        }
        if (chatActivity != null && chatActivity.getClass().getClassLoader() != null) {
            return chatActivity.getClass().getClassLoader();
        }
        return context.getClassLoader();
    }

    private Object getTelegramPhotoViewer(ClassLoader classLoader) throws Throwable {
        Class<?> photoViewerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer");
        Method getInstance = photoViewerClass.getMethod("getInstance");
        return getInstance.invoke(null);
    }

    private boolean setTelegramPhotoViewerParent(Object photoViewer, Object chatActivity) {
        if (photoViewer == null || chatActivity == null) {
            return false;
        }
        Object resourcesProvider = Reflect.field(chatActivity, "themeDelegate");
        for (Method method : photoViewer.getClass().getMethods()) {
            if (!method.getName().equals("setParentActivity") || method.getParameterCount() != 2 || resourcesProvider == null) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types[0].isAssignableFrom(chatActivity.getClass()) && types[1].isAssignableFrom(resourcesProvider.getClass())) {
                try {
                    method.invoke(photoViewer, chatActivity, resourcesProvider);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        for (Method method : photoViewer.getClass().getMethods()) {
            if (!method.getName().equals("setParentActivity") || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            if (type.isAssignableFrom(chatActivity.getClass())) {
                try {
                    method.invoke(photoViewer, chatActivity);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private Object createTelegramPhotoViewerProvider(ClassLoader classLoader) throws Throwable {
        Class<?> providerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer$EmptyPhotoViewerProvider");
        Constructor<?> constructor = providerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private Object createSyntheticHistoryMessage(ClassLoader classLoader, Object selectedMessageObject,
                                                 MessageCache.CachedMessage cachedMessage, Object mediaObject,
                                                 java.io.File file, boolean isolateIdentity) throws Throwable {
        Class<?> messageClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$TL_message");
        Object message = messageClass.getDeclaredConstructor().newInstance();
        Object selectedOwner = Reflect.field(selectedMessageObject, "messageOwner");
        Reflect.setField(message, "id", isolateIdentity ? syntheticHistoryMessageId(cachedMessage, file) : (int) cachedMessage.messageId);
        Reflect.setField(message, "date", (int) Math.max(0L, cachedMessage.timestamp / 1000L));
        Reflect.setField(message, "dialog_id", isolateIdentity ? 0L : cachedMessage.dialogId);
        Reflect.setField(message, "from_id", Reflect.field(selectedOwner, "from_id"));
        Reflect.setField(message, "peer_id", firstNonNull(Reflect.field(selectedOwner, "peer_id"), createPeerForDialogId(classLoader, cachedMessage.dialogId)));
        Reflect.setField(message, "message", firstNonEmpty(cachedMessage.text, cachedMessage.caption));
        Reflect.setField(message, "attachPath", file.getAbsolutePath());
        Reflect.setField(message, "media", mediaObject);
        return message;
    }

    private int syntheticHistoryMessageId(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        long id = syntheticHistoryId(cachedMessage, file) & 0x3fffffffL;
        int value = (int) Math.max(1L, id);
        return -value;
    }

    private Object createSyntheticHistoryPhotoMedia(ClassLoader classLoader, MessageCache.CachedMessage cachedMessage,
                                                   java.io.File file) throws Throwable {
        int[] dimensions = readImageBounds(file);
        long id = syntheticHistoryId(cachedMessage, file);

        Object location = newTelegramObject(
                classLoader,
                "org.telegram.tgnet.TLRPC$TL_fileLocationToBeDeprecated",
                "org.telegram.tgnet.TLRPC$TL_fileLocation"
        );
        Reflect.setField(location, "volume_id", id);
        Reflect.setField(location, "local_id", (int) (id & 0x7fffffff));
        Reflect.setField(location, "secret", 0L);
        Reflect.setField(location, "dc_id", 0);
        Reflect.setField(location, "file_reference", new byte[0]);

        Object photoSize = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_photoSize");
        Reflect.setField(photoSize, "type", "x");
        Reflect.setField(photoSize, "w", Math.max(1, dimensions[0]));
        Reflect.setField(photoSize, "h", Math.max(1, dimensions[1]));
        Reflect.setField(photoSize, "size", (int) Math.min(Integer.MAX_VALUE, Math.max(1L, file.length())));
        Reflect.setField(photoSize, "location", location);

        ArrayList<Object> sizes = new ArrayList<>();
        sizes.add(photoSize);

        Object photo = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_photo");
        Reflect.setField(photo, "id", id);
        Reflect.setField(photo, "access_hash", 0L);
        Reflect.setField(photo, "file_reference", new byte[0]);
        Reflect.setField(photo, "date", (int) Math.max(0L, cachedMessage.timestamp / 1000L));
        Reflect.setField(photo, "sizes", sizes);
        Reflect.setField(photo, "dc_id", 0);
        Reflect.setField(photo, "has_stickers", false);

        Object media = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_messageMediaPhoto");
        Reflect.setField(media, "photo", photo);
        Reflect.setField(media, "caption", firstNonEmpty(cachedMessage.caption, cachedMessage.text));
        Reflect.setField(media, "ttl_seconds", 0);
        return media;
    }

    private Object newTelegramObject(ClassLoader classLoader, String... classNames) throws Throwable {
        Throwable last = null;
        for (String className : classNames) {
            try {
                Constructor<?> constructor = classLoader.loadClass(className).getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Throwable throwable) {
                last = throwable;
            }
        }
        throw last != null ? last : new ClassNotFoundException("No Telegram class candidates");
    }

    private long syntheticHistoryId(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        long hash = 1469598103934665603L;
        String key = "gramsieve-photo:" + cachedMessage.dialogId + ":" + cachedMessage.messageId
                + ":" + file.length() + ":" + file.getName();
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 1099511628211L;
        }
        hash &= Long.MAX_VALUE;
        return hash == 0L ? 1L : hash;
    }

    private Object createPeerForDialogId(ClassLoader classLoader, long dialogId) throws Throwable {
        long normalizedId = Math.abs(dialogId);
        String className = dialogId < 0 ? "org.telegram.tgnet.TLRPC$TL_peerChannel" : "org.telegram.tgnet.TLRPC$TL_peerUser";
        Object peer = classLoader.loadClass(className).getDeclaredConstructor().newInstance();
        if (dialogId < 0) {
            Reflect.setField(peer, "channel_id", normalizedId);
            Reflect.setField(peer, "chat_id", normalizedId);
        } else {
            Reflect.setField(peer, "user_id", normalizedId);
        }
        return peer;
    }

    private java.io.File syncHistoryFileToTelegramPath(ClassLoader classLoader, int account, Object message,
                                                       java.io.File sourceFile) throws Throwable {
        Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
        Class<?> messageBaseClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
        Object fileLoader = Reflect.invokeStatic(fileLoaderClass, "getInstance", new Class<?>[]{int.class}, account);
        if (fileLoader == null) {
            return null;
        }
        Method getPathToMessage = fileLoaderClass.getMethod("getPathToMessage", messageBaseClass);
        java.io.File targetFile = (java.io.File) getPathToMessage.invoke(fileLoader, message);
        if (targetFile == null || targetFile.getPath().isEmpty()) {
            info("Anti-recall: FileLoader returned empty path for original history media");
            return null;
        }
        if (!sourceFile.getCanonicalPath().equals(targetFile.getCanonicalPath())
                && (!targetFile.exists() || targetFile.length() != sourceFile.length())) {
            copyFile(sourceFile, targetFile);
        }
        return targetFile.exists() && targetFile.length() > 0 ? targetFile : null;
    }

    private void copyFile(java.io.File sourceFile, java.io.File targetFile) throws java.io.IOException {
        java.io.File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("Failed to create " + parent);
        }
        try (java.io.FileInputStream input = new java.io.FileInputStream(sourceFile);
             java.io.FileOutputStream output = new java.io.FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private Object createTelegramMessageObject(ClassLoader classLoader, int account, Object message) throws Throwable {
        Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
        Class<?> messageBaseClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
        Constructor<?> constructor = messageObjectClass.getConstructor(int.class, messageBaseClass, boolean.class, boolean.class);
        return constructor.newInstance(account, message, false, true);
    }

    private boolean openTelegramPhotoViewerWithMessage(View anchor, Object chatActivity,
                                                      MessageCache.CachedMessage cachedMessage, Object messageObject,
                                                      boolean isolateIdentity) throws Throwable {
        ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
        Object photoViewer = getTelegramPhotoViewer(classLoader);
        if (photoViewer == null || !setTelegramPhotoViewerParent(photoViewer, chatActivity)) {
            return false;
        }
        Object provider = createTelegramPhotoViewerProvider(classLoader);
        Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
        Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
        Class<?> providerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer$PhotoViewerProvider");
        Method openPhoto = photoViewer.getClass().getMethod(
                "openPhoto",
                messageObjectClass,
                chatActivityClass,
                long.class,
                long.class,
                long.class,
                providerClass
        );
        Object result = openPhoto.invoke(photoViewer, messageObject, chatActivity, 0L, isolateIdentity ? 0L : cachedMessage.dialogId, 0L, provider);
        return Boolean.TRUE.equals(result);
    }

    private int resolveSelectedTelegramAccount(ClassLoader classLoader) {
        try {
            Class<?> userConfigClass = classLoader.loadClass("org.telegram.messenger.UserConfig");
            return Reflect.asInt(Reflect.staticField(userConfigClass, "selectedAccount"), 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private int[] readImageBounds(java.io.File file) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return new int[]{Math.max(0, options.outWidth), Math.max(0, options.outHeight)};
    }

    private boolean isVideoHistoryFile(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        String path = file.getName().toLowerCase(Locale.ROOT);
        String mediaType = cachedMessage.mediaType != null ? cachedMessage.mediaType.toLowerCase(Locale.ROOT) : "";
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")
                || path.endsWith(".webm") || path.endsWith(".mkv") || mediaType.contains("video");
    }

    private java.io.File resolveCachedMediaFile(MessageCache.CachedMessage cachedMessage) {
        if (cachedMessage.cachedMediaPath != null) {
            java.io.File file = new java.io.File(cachedMessage.cachedMediaPath);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        if (mediaCache == null) {
            return null;
        }
        java.io.File file = mediaCache.getMedia(cachedMessage.accountId,
                cachedMessage.dialogId, cachedMessage.messageId, ".jpg");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".png");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".webp");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".gif");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".mp4");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".m4v");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".mov");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".webm");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.accountId, cachedMessage.dialogId, cachedMessage.messageId, ".mkv");
        if (file != null) return file;
        return mediaCache.getMedia(cachedMessage.accountId,
                cachedMessage.dialogId, cachedMessage.messageId, ".bin");
    }

    private String localizedNoEditHistory(Context context) {
        return isChineseLocale(context) ? "无编辑历史" : "No edit history";
    }

    private String localizedMediaHistoryPreparing(Context context) {
        return isChineseLocale(context)
                ? "原始媒体仍在准备，请稍后再试"
                : "Original media is still being prepared. Try again shortly.";
    }

    private String localizedOriginalMediaUnavailable(Context context) {
        return isChineseLocale(context)
                ? "原始媒体未缓存"
                : "Original media was not cached";
    }

    private void markSelectedMessage(Context context, Object chatActivity, Object messageObject) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int messageId = resolveMessageId(messageObject);
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        int account = resolveSelectedTelegramAccount(savedClassLoader);
        markStore(context).add(account, dialogId, messageId, resolveMarkPreview(messageObject));
        Toast.makeText(context, localizedMarkSavedToast(context), Toast.LENGTH_SHORT).show();
        info("Marked message " + messageId + " for dialog " + dialogId);
    }

    private MessageMarkStore markStore(Context context) {
        if (messageMarkStore == null) {
            messageMarkStore = new MessageMarkStore(context.getApplicationContext());
        }
        return messageMarkStore;
    }

    private String resolveMarkPreview(Object messageObject) {
        String preview = Reflect.asString(Reflect.field(messageObject, "messageText"));
        if (preview.isBlank()) {
            preview = Reflect.asString(Reflect.field(messageObject, "caption"));
        }
        if (preview.isBlank()) {
            preview = Reflect.asString(Reflect.field(Reflect.field(messageObject, "messageOwner"), "message"));
        }
        return preview;
    }

    private MenuInsertionPoint findReportInsertionPoint(View root) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        List<String> reportLabels = reportLabels(root.getContext());
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isTelegramMenuSubItem(child) && textMatchesAny(child, reportLabels)) {
                return new MenuInsertionPoint(group, i);
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            MenuInsertionPoint nested = findReportInsertionPoint(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private boolean isTelegramMenuSubItem(View view) {
        return view != null && "org.telegram.ui.ActionBar.ActionBarMenuSubItem".equals(view.getClass().getName());
    }

    private List<String> reportLabels(Context context) {
        List<String> labels = new ArrayList<>();
        addTelegramString(labels, context, "Report2");
        addTelegramString(labels, context, "ReportMessagesNoCaps");
        addTelegramString(labels, context, "ReportSpamNoCaps");
        addTelegramString(labels, context, "DeleteReportSpam");
        addTelegramString(labels, context, "ProfileActionsReport");
        labels.add("Report");
        labels.add("Report Spam");
        labels.add("举报");
        return labels;
    }

    private View findNativeDeleteMenuItem(View root) {
        if (root == null) {
            return null;
        }
        if (isTelegramMenuSubItem(root) && textMatchesAny(root, deleteLabels(root.getContext()))) {
            return root;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View match = findNativeDeleteMenuItem(group.getChildAt(i));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private List<String> deleteLabels(Context context) {
        List<String> labels = new ArrayList<>();
        addTelegramString(labels, context, "Delete");
        addTelegramString(labels, context, "DeleteMessage");
        addTelegramString(labels, context, "DeleteMessages");
        addTelegramString(labels, context, "DeleteForMe");
        labels.add("Delete");
        labels.add("删除");
        labels.add("删除消息");
        return labels;
    }

    private void addTelegramString(List<String> labels, Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", telegramResourcePackageName);
        if (id == 0) {
            return;
        }
        try {
            String label = context.getString(id).trim();
            if (!label.isBlank() && !labels.contains(label)) {
                labels.add(label);
            }
        } catch (Resources.NotFoundException ignored) {
        }
    }

    private boolean textMatchesAny(View view, List<String> labels) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString().trim();
            for (String label : labels) {
                if (!label.isBlank() && (text.equals(label) || text.contains(label))) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (textMatchesAny(group.getChildAt(i), labels)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasTaggedChild(View view, int targetId) {
        Object keyedTag = view.getTag(R.id.gramsieve_menu_item_id);
        if (keyedTag instanceof Integer && ((Integer) keyedTag) == targetId) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasTaggedChild(group.getChildAt(i), targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void refreshMessagePopup(View popupContent, View insertedItem, Object popupWindow) {
        refreshRadialSelectors(insertedItem);
        popupContent.requestLayout();
        popupContent.invalidate();
        uiCallbacks.post(popupContent, () -> {
            refreshRadialSelectors(insertedItem);
            Reflect.invokeIfExists(popupContent, "precalculateHeight", new Class<?>[0]);
            popupContent.forceLayout();
            popupContent.requestLayout();
            int maxWidth = popupContent.getResources().getDisplayMetrics().widthPixels;
            int maxHeight = popupContent.getResources().getDisplayMetrics().heightPixels;
            popupContent.measure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            );
            popupContent.invalidate();
            if (popupWindow instanceof PopupWindow) {
                PopupWindow window = (PopupWindow) popupWindow;
                int width = popupContent.getMeasuredWidth();
                int height = popupContent.getMeasuredHeight();
                if (width > 0 && height > 0) {
                    window.update(width, height);
                    messageDeleteDiagnostics.recordPopupSize(width, height);
                    info("MessageDeleteFlow: popup touch region resized width=" + width
                            + " height=" + height);
                }
            }
        });
    }

    private void refreshRadialSelectors(View view) {
        View current = view;
        while (current != null) {
            Reflect.invokeIfExists(current, "updateRadialSelectors", new Class<?>[0]);
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    private static final class MenuInsertionPoint {
        final ViewGroup parent;
        final int index;

        MenuInsertionPoint(ViewGroup parent, int index) {
            this.parent = parent;
            this.index = index;
        }
    }

    private void addRuleForSelectedMessage(Context context, View messageView, Object messageObject) {
        info("Block-message menu clicked");
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        List<FilterConfig.RuleSpec> rules = MessageRuleFactory.automaticRules(snapshot);
        if (snapshot == null || rules.isEmpty()) {
            Toast.makeText(context, localizedNoTextToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        FilterConfig updated = configProvider.getConfig(context).deepCopy();
        updated.enabled = true;
        FilterConfig.ChatRuleSet chatRuleSet = updated.getOrCreateChatRuleSet(snapshot.dialogId);
        chatRuleSet.enabled = true;
        int added = 0;
        for (FilterConfig.RuleSpec rule : rules) {
            if (!MessageRuleFactory.containsEquivalentRule(chatRuleSet.rules, rule)) {
                chatRuleSet.rules.add(rule);
                added++;
            }
        }
        updated.sanitize();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        updated = saveUpdatedConfig(context, updated);
        FilterDecision decision = filterEngine.evaluate(updated, snapshot);
        decisionCache.clear();
        if (messageView != null) {
            UiMutation.apply(messageView, decision, snapshot.stableKey());
            messageView.requestLayout();
        }
        refreshFilteringAround(messageView);
        info(
                "Added block-message rules added=" + added
                        + " candidates=" + rules.size()
                        + " dialog=" + snapshot.dialogId
                        + " matchedNow=" + decision.matched
                        + " ruleId=" + decision.ruleId
                        + " updatedAt=" + updated.updatedAtEpochMs
        );
        Toast.makeText(context, localizedSavedToast(context), Toast.LENGTH_SHORT).show();
    }

    private FilterConfig saveUpdatedConfig(Context hostContext, FilterConfig updated) {
        FilterConfig saved = configProvider.persistHostConfig(hostContext, updated);
        if (saved == null) {
            saved = saveToRemotePreferences(updated);
        }
        if (saved == null && isModuleProviderVisible(hostContext)) {
            saved = saveToContentProvider(hostContext, updated);
        }
        if (saved != null) {
            updated = saved;
        } else {
            persistToModuleProcess(hostContext, updated);
        }
        configProvider.replaceCachedConfig(updated);
        invalidateModuleFallbackSnapshot();
        keepDownloadButtonVisibleEnabled = updated.enhancements != null
                && updated.enhancements.isEnabledForGramSieve(
                EnhancementConfig.Feature.KEEP_DOWNLOAD_BUTTON_VISIBLE);
        refreshPersistentDownloadButtonUi("config-save");
        return updated;
    }

    private boolean isModuleProviderVisible(Context hostContext) {
        return hostContext != null
                && hostContext.getPackageManager().resolveContentProvider(
                ConfigContentProvider.AUTHORITY,
                0
        ) != null;
    }

    private void persistToModuleProcess(Context hostContext, FilterConfig updated) {
        try {
            if (hostContext == null || updated == null) {
                return;
            }
            String encodedConfig = encodedConfig(updated);
            Intent intent = new Intent(ConfigUpdateReceiver.ACTION_SAVE_CONFIG);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".config.ConfigUpdateReceiver"));
            intent.putExtra(ConfigUpdateReceiver.EXTRA_CONFIG_JSON_BASE64, encodedConfig);
            hostContext.sendBroadcast(intent);
            info("Requested module-local config persistence broadcast updatedAt=" + updated.updatedAtEpochMs);
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to request module-local config persistence: " + exception.getMessage());
        }
    }

    private String encodedConfig(FilterConfig config) {
        return Base64.encodeToString(
                ModuleConfigStore.toJson(config).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
    }

    private FilterConfig saveToRemotePreferences(FilterConfig updated) {
        try {
            if (updated == null) {
                return null;
            }
            updated.sanitize();
            if (updated.updatedAtEpochMs <= 0L) {
                updated.updatedAtEpochMs = System.currentTimeMillis();
            }
            android.content.SharedPreferences remotePreferences = module.getRemotePreferences(ModuleConfigStore.PREFS_NAME);
            if (remotePreferences == null) {
                return null;
            }
            boolean committed = remotePreferences.edit()
                    .putString(ModuleConfigStore.KEY_CONFIG_JSON, ModuleConfigStore.toJson(updated))
                    .commit();
            if (!committed) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: commit=false");
                return null;
            }
            String savedJson = remotePreferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null);
            FilterConfig saved = ModuleConfigStore.fromJson(savedJson);
            if (!sameConfigExceptTimestamp(updated, saved)) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: readback mismatch");
                return null;
            }
            info("Saved message rule through remote preferences updatedAt=" + saved.updatedAtEpochMs);
            return saved.deepCopy();
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: " + exception.getMessage());
            return null;
        }
    }

    private FilterConfig saveToContentProvider(Context hostContext, FilterConfig updated) {
        try {
            Bundle extras = new Bundle();
            extras.putString(ConfigContentProvider.KEY_CONFIG_JSON, ModuleConfigStore.toJson(updated));
            Bundle result = hostContext.getContentResolver().call(
                    ConfigContentProvider.CONTENT_URI,
                    ConfigContentProvider.METHOD_SAVE_CONFIG,
                    null,
                    extras
            );
            if (result == null) {
                return null;
            }
            String json = result.getString(ConfigContentProvider.KEY_CONFIG_JSON, null);
            FilterConfig saved = ModuleConfigStore.fromJson(json);
            long updatedAt = result.getLong(ConfigContentProvider.KEY_UPDATED_AT_EPOCH_MS, saved.updatedAtEpochMs);
            if (updatedAt > 0L) {
                saved.updatedAtEpochMs = updatedAt;
            }
            saved = saved.sanitize();
            if (!sameConfigExceptTimestamp(updated, saved)) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through content provider: readback mismatch");
                return null;
            }
            info("Saved message rule through content provider updatedAt=" + saved.updatedAtEpochMs);
            return saved;
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through content provider: " + exception.getMessage());
            return null;
        }
    }

    private boolean sameConfigExceptTimestamp(FilterConfig expected, FilterConfig actual) {
        if (expected == null || actual == null) {
            return false;
        }
        FilterConfig expectedCopy = expected.deepCopy().sanitize();
        FilterConfig actualCopy = actual.deepCopy().sanitize();
        actualCopy.updatedAtEpochMs = expectedCopy.updatedAtEpochMs;
        return ModuleConfigStore.toJson(expectedCopy).equals(ModuleConfigStore.toJson(actualCopy));
    }

    private void refreshFilteringAround(View anchor) {
        decisionCache.clear();
        if (anchor == null) {
            return;
        }
        View refreshRoot = findRefreshRoot(anchor);
        int refreshed = refreshBoundMessages(refreshRoot);
        refreshRoot.requestLayout();
        refreshRoot.invalidate();
        Object parent = refreshRoot.getParent();
        if (parent instanceof View) {
            ((View) parent).requestLayout();
        }
        uiCallbacks.post(refreshRoot, () -> {
            int postRefreshed = refreshBoundMessages(refreshRoot);
            refreshRoot.requestLayout();
            refreshRoot.invalidate();
            int postRemaining = refreshProbeBudget.getAndDecrement();
            if (postRemaining > 0) {
                info(
                        "Post refresh root=" + refreshRoot.getClass().getSimpleName()
                                + " refreshed=" + postRefreshed
                );
            }
        });
        int remaining = refreshProbeBudget.getAndDecrement();
        if (remaining > 0) {
            info(
                    "Immediate refresh root=" + refreshRoot.getClass().getSimpleName()
                            + " refreshed=" + refreshed
            );
        }
    }

    private void refreshChatActivityFiltering(Object chatActivity) {
        decisionCache.clear();
        View root = resolveChatActivityRoot(chatActivity);
        if (root == null) {
            return;
        }
        int refreshed = refreshBoundMessages(root);
        root.requestLayout();
        root.invalidate();
        int remaining = refreshProbeBudget.getAndDecrement();
        if (remaining > 0) {
            info(
                    "Resume refresh root=" + root.getClass().getSimpleName()
                            + " refreshed=" + refreshed
            );
        }
    }

    private View resolveChatActivityRoot(Object chatActivity) {
        Object fragmentView = Reflect.field(chatActivity, "fragmentView");
        if (fragmentView instanceof View) {
            return (View) fragmentView;
        }
        Object contentView = Reflect.invokeIfExists(chatActivity, "getFragmentView", new Class<?>[0]);
        if (contentView instanceof View) {
            return (View) contentView;
        }
        return null;
    }

    private View findRefreshRoot(View anchor) {
        View current = anchor;
        View best = anchor;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("RecyclerView")) {
                return current;
            }
            if (current instanceof ViewGroup) {
                best = current;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return best;
    }

    private View findRecyclerDirectChild(View descendant) {
        if (descendant == null) {
            return null;
        }
        View current = descendant;
        View child = descendant;
        while (current != null) {
            Object parent = current.getParent();
            if (!(parent instanceof View)) {
                return null;
            }
            View parentView = (View) parent;
            if (isLikelyRecyclerView(parentView)) {
                return child;
            }
            child = parentView;
            current = parentView;
        }
        return null;
    }

    private int refreshBoundMessages(View root) {
        if (root == null) {
            return 0;
        }
        Object messageObject = resolveMessageObject(root);
        if (messageObject != null) {
            applyDecision(root, root, messageObject);
            return 1;
        }
        if (!(root instanceof ViewGroup)) {
            return 0;
        }
        if (isLikelyRecyclerView(root)) {
            return refreshRecyclerRows((ViewGroup) root);
        }
        ViewGroup group = (ViewGroup) root;
        int refreshed = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            refreshed += refreshBoundMessages(group.getChildAt(i));
        }
        return refreshed;
    }

    private int refreshRecyclerRows(ViewGroup recyclerView) {
        int refreshed = 0;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (BoundMessageViewWalker.visit(child, (messageView, messageObject) -> {
            })) {
                applyDecisionToBoundViews(child);
                child.requestLayout();
                child.invalidate();
                refreshed++;
            }
        }
        return refreshed;
    }

    private boolean isLikelyRecyclerView(View view) {
        if (view == null) {
            return false;
        }
        Class<?> current = view.getClass();
        while (current != null) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return view.getClass().getName().contains("RecyclerView");
    }

    private void dismissScrimPopup(Object chatActivity) {
        Object popupWindow = Reflect.field(chatActivity, "scrimPopupWindow");
        Reflect.invokeIfExists(popupWindow, "dismiss", new Class<?>[0]);
    }

    private void injectChatMenu(Object chatActivity) {
        Object headerItem = Reflect.field(chatActivity, "headerItem");
        if (headerItem == null) {
            return;
        }
        View chatMenuView = reconcileMenuItemView(headerItem, MENU_ID_CHAT);
        if (chatMenuView == null) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveIcon(context);
            Object subItem = addMenuSubItem(headerItem, MENU_ID_CHAT, iconRes, localizedChatMenuLabel(context));
            if (subItem instanceof View) {
                chatMenuView = (View) subItem;
                chatMenuView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_CHAT);
            } else {
                info("ChatActivity menu addSubItem unavailable on " + headerItem.getClass().getName());
            }
        }
        if (chatMenuView != null) {
            View boundChatMenuView = chatMenuView;
            uiCallbacks.setClickListener(boundChatMenuView, v -> {
                try {
                    long dialogId = Reflect.asLong(Reflect.invokeIfExists(
                            chatActivity, "getDialogId", new Class<?>[0]), 0L);
                    String title = resolveChatTitle(chatActivity);
                    openConfigFromHost(chatActivity, v.getContext(), CONFIG_MODE_CHAT, dialogId, title);
                } finally {
                    Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                }
            });
        }
        injectScrollToTopMenu(chatActivity, headerItem);
        injectFirstMessageMenu(chatActivity, headerItem);
        injectJumpToMarkMenu(chatActivity, headerItem);
        injectAntiRecallMenu(chatActivity, headerItem);
        injectCleanupModeMenu(chatActivity, headerItem);
    }

    private void beginReadPositionTracking(Object chatActivity) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        trackedDialogId = dialogId;
        lastTopmostMessageId = 0;
        readPositionDirty = false;
        jumpDetected = false;
    }

    private void flushReadPosition(Object chatActivity) {
        long dialogId = trackedDialogId;
        int messageId = lastTopmostMessageId;
        boolean dirty = readPositionDirty;
        boolean jumped = jumpDetected;
        trackedDialogId = 0L;
        lastTopmostMessageId = 0;
        readPositionDirty = false;
        jumpDetected = false;
        info("FlushReadPos: dialogId=" + dialogId + " msgId=" + messageId + " dirty=" + dirty + " jumped=" + jumped);
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        if (dirty && !jumped) {
            Context context = resolveContextFromActivity(chatActivity);
            if (context != null) {
                ChatReadPositionStore.save(context.getApplicationContext(), dialogId, messageId);
                info("FlushReadPos: saved " + messageId + " for dialog " + dialogId);
            }
        }
    }

    private void trackTopmostMessage(View cell) {
        long dialogId = trackedDialogId;
        if (dialogId == 0L) {
            return;
        }
        if (cell.getTop() > 0) {
            return;
        }
        Object parent = cell.getParent();
        if (parent == null) {
            return;
        }
        Object messageObject = resolveMessageObject(cell);
        if (messageObject == null) {
            return;
        }
        int messageId = resolveMessageId(messageObject);
        if (messageId <= 0) {
            return;
        }
        if (messageId != lastTopmostMessageId) {
            int oldId = lastTopmostMessageId;
            if (oldId > 0 && messageId > oldId && (messageId - oldId) >= SCROLL_JUMP_THRESHOLD) {
                ChatReadPositionStore.save(cell.getContext(), dialogId, oldId);
                jumpDetected = true;
                info("JumpDetected: saved old position " + oldId + " before jump to " + messageId + " (delta=" + (messageId - oldId) + ") dialog=" + dialogId);
            }
            lastTopmostMessageId = messageId;
            readPositionDirty = true;
        }
    }

    private int resolveMessageId(Object messageObject) {
        Object directId = Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]);
        if (directId instanceof Integer) {
            return (Integer) directId;
        }
        Object owner = Reflect.field(messageObject, "messageOwner");
        if (owner != null) {
            Object ownerId = Reflect.field(owner, "id");
            if (ownerId instanceof Integer) {
                return (Integer) ownerId;
            }
        }
        return 0;
    }

    private Context resolveContextFromActivity(Object chatActivity) {
        Object fragmentView = Reflect.field(chatActivity, "fragmentView");
        if (fragmentView instanceof View) {
            return ((View) fragmentView).getContext();
        }
        Object contentView = Reflect.invokeIfExists(chatActivity, "getFragmentView", new Class<?>[0]);
        if (contentView instanceof View) {
            return ((View) contentView).getContext();
        }
        return null;
    }

    private void injectScrollToTopMenu(Object chatActivity, Object headerItem) {
        View subItemView = reconcileMenuItemView(headerItem, MENU_ID_SCROLL_TOP);
        if (subItemView == null) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveScrollTopIcon(context);
            Object subItem = addMenuSubItem(
                    headerItem, MENU_ID_SCROLL_TOP, iconRes, localizedScrollTopLabel(context));
            if (!(subItem instanceof View)) {
                info("Scroll-to-top addSubItem unavailable on " + headerItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_SCROLL_TOP);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                info("ScrollToTop menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                scrollChatToTop(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Scroll to top failed", throwable);
            }
        });
    }

    private void injectFirstMessageMenu(Object chatActivity, Object headerItem) {
        View subItemView = reconcileMenuItemView(headerItem, MENU_ID_FIRST_MESSAGE);
        if (subItemView == null) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveFirstMessageIcon(context);
            Object subItem = addMenuSubItem(
                    headerItem,
                    MENU_ID_FIRST_MESSAGE,
                    iconRes,
                    localizedFirstMessageLabel(context)
            );
            if (!(subItem instanceof View)) {
                info("First-message addSubItem unavailable on "
                        + headerItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_FIRST_MESSAGE);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                info("JumpToFirst menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                jumpToFirstMessage(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Jump to first message failed", throwable);
            }
        });
    }

    private void injectJumpToMarkMenu(Object chatActivity, Object headerItem) {
        View subItemView = reconcileMenuItemView(headerItem, MENU_ID_JUMP_TO_MARK);
        if (subItemView == null) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveJumpToMarkIcon(context);
            Object subItem = addMenuSubItem(
                    headerItem, MENU_ID_JUMP_TO_MARK, iconRes, localizedJumpToMarkLabel(context));
            if (!(subItem instanceof View)) {
                info("Jump-to-mark addSubItem unavailable on " + headerItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_JUMP_TO_MARK);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                info("JumpToMark menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                jumpToMarkedPosition(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Jump to mark failed", throwable);
            }
        });
    }

    private void injectAntiRecallMenu(Object chatActivity, Object headerItem) {
        if (usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL)) {
            return;
        }
        if (backgroundMessageLoader == null) {
            // Try deferred initialization with chat context
            initAntiRecallFromChat(chatActivity);
        }
        if (backgroundMessageLoader == null) {
            info("Anti-recall: backgroundMessageLoader is null after init");
            return;
        }
        if (backgroundMessageLoader == null) {
            info("Anti-recall: backgroundMessageLoader is null after deferred init");
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);

        // Debug: check all possible ID fields
        Object currentChat = Reflect.field(chatActivity, "currentChat");
        long chatId = currentChat != null ? Reflect.asLong(Reflect.field(currentChat, "id"), 0L) : 0L;
        long channelId = currentChat != null ? Reflect.asLong(Reflect.field(currentChat, "channel_id"), 0L) : 0L;
        info("Anti-recall: dialogId=" + dialogId + " chatId=" + chatId + " channelId=" + channelId);

        info("Anti-recall: injecting menu for dialogId=" + dialogId);
        int iconRes = resolveAntiRecallIcon(context);
        CharSequence label = antiRecallStatusLabel(context, dialogId);
        info("Anti-recall: label=" + label);
        View subItemView = reconcileMenuItemView(headerItem, MENU_ID_ANTI_RECALL);
        if (subItemView == null) {
            Object subItem = addMenuSubItem(headerItem, MENU_ID_ANTI_RECALL, iconRes, label);
            if (!(subItem instanceof View)) {
                info("Anti-recall addSubItem unavailable on " + headerItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_ANTI_RECALL);
            info("Anti-recall: menu item created, class=" + subItemView.getClass().getName());
        } else {
            Reflect.invokeIfExists(subItemView, "setText", new Class<?>[]{CharSequence.class}, label);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                info("Anti-recall: onClick fired");
                boolean wasEnabled = backgroundMessageLoader.isChatEnabled(dialogId);
                info("Anti-recall: wasEnabled=" + wasEnabled + " dialogId=" + dialogId);
                if (wasEnabled) {
                    backgroundMessageLoader.disableChat(dialogId);
                } else {
                    backgroundMessageLoader.enableChat(dialogId);
                }
                boolean nowEnabled = backgroundMessageLoader.isChatEnabled(dialogId);
                info("Anti-recall: nowEnabled=" + nowEnabled);
                CharSequence newLabel = antiRecallStatusLabel(v.getContext(), dialogId);
                info("Anti-recall: newLabel=" + newLabel);
                Reflect.invokeIfExists(v, "setText", new Class<?>[]{CharSequence.class}, newLabel);
                info("Anti-recall: setText called");
            } catch (Throwable throwable) {
                error("Anti-recall click failed", throwable);
            }
        });
        info("Anti-recall: menu injection complete");
    }

    private int resolveAntiRecallIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", telegramResourcePackageName);
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
    }

    private CharSequence antiRecallStatusLabel(Context context, long dialogId) {
        boolean enabled = backgroundMessageLoader.isChatEnabled(dialogId);
        if (isChineseLocale(context)) {
            return enabled ? "主动加载已打开" : "主动加载未打开";
        }
        return enabled ? "Proactive Loading: ON" : "Proactive Loading: OFF";
    }

    private void injectCleanupModeMenu(Object chatActivity, Object headerItem) {
        if (usesModuleFallback(ModuleConflictDetector.ConflictKind.ANTI_RECALL)) {
            return;
        }
        if (recallDetector == null) {
            initAntiRecallFromChat(chatActivity);
        }
        if (recallDetector == null) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId == 0L) {
            return;
        }
        int iconRes = resolveCleanupModeIcon(context);
        CharSequence label = cleanupModeStatusLabel(context, dialogId);
        View subItemView = reconcileMenuItemView(headerItem, MENU_ID_CLEANUP_MODE);
        if (subItemView == null) {
            Object subItem = addMenuSubItem(headerItem, MENU_ID_CLEANUP_MODE, iconRes, label);
            if (!(subItem instanceof View)) {
                info("CleanupMode addSubItem unavailable on " + headerItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_CLEANUP_MODE);
        } else {
            Reflect.invokeIfExists(subItemView, "setText", new Class<?>[]{CharSequence.class}, label);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                boolean enabled = recallDetector.toggleCleanupMode(dialogId, CLEANUP_MODE_DURATION_MS);
                Reflect.invokeIfExists(v, "setText", new Class<?>[]{CharSequence.class},
                        cleanupModeStatusLabel(v.getContext(), dialogId));
                Toast.makeText(v.getContext(), localizedCleanupModeToast(v.getContext(), enabled),
                        Toast.LENGTH_SHORT).show();
                info("CleanupMode: toggled dialogId=" + dialogId + " enabled=" + enabled);
            } catch (Throwable throwable) {
                error("Cleanup mode click failed", throwable);
            }
        });
    }

    private int resolveCleanupModeIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_delete", "drawable", telegramResourcePackageName);
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_delete;
    }

    private CharSequence cleanupModeStatusLabel(Context context, long dialogId) {
        boolean enabled = recallDetector != null && recallDetector.isCleanupModeActive(dialogId);
        if (isChineseLocale(context)) {
            return enabled ? "清理模式已打开" : "清理模式未打开";
        }
        return enabled ? "Cleanup Mode: ON" : "Cleanup Mode: OFF";
    }

    private CharSequence localizedCleanupModeToast(Context context, boolean enabled) {
        if (isChineseLocale(context)) {
            return enabled ? "清理模式已打开，5 分钟内删除会放行" : "清理模式已关闭";
        }
        return enabled ? "Cleanup mode is on for 5 minutes" : "Cleanup mode is off";
    }

    private void jumpToMarkedPosition(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int account = resolveSelectedTelegramAccount(savedClassLoader);
        List<MessageMarkStore.Mark> marks = markStore(context).list(account, dialogId);
        if (marks.isEmpty()) {
            Toast.makeText(context, localizedNoMarkToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        if (marks.size() == 1) {
            jumpToMarkedMessage(chatActivity, context, marks.get(0).messageId);
            return;
        }
        CharSequence[] labels = new CharSequence[marks.size()];
        for (int i = 0; i < marks.size(); i++) {
            MessageMarkStore.Mark mark = marks.get(i);
            labels[i] = "#" + mark.messageId + (mark.preview.isBlank() ? "" : "  ·  " + mark.preview);
        }
        new android.app.AlertDialog.Builder(context)
                .setTitle(isChineseLocale(context) ? "标记消息" : "Marked messages")
                .setItems(labels, (dialog, which) -> jumpToMarkedMessage(
                        chatActivity,
                        context,
                        marks.get(which).messageId
                ))
                .setNeutralButton(isChineseLocale(context) ? "清空" : "Clear", (dialog, which) -> {
                    markStore(context).clear(account, dialogId);
                    Toast.makeText(
                            context,
                            isChineseLocale(context) ? "已清空当前聊天的标记" : "Marks cleared for this chat",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton(isChineseLocale(context) ? "取消" : "Cancel", null)
                .show();
    }

    private void jumpToMarkedMessage(Object chatActivity, Context context, int markedMessageId) {
        Toast.makeText(context, localizedJumpToMarkStarted(context), Toast.LENGTH_SHORT).show();
        boolean invoked = invokeScrollToMessageId(chatActivity, markedMessageId);
        if (invoked) {
            info("Called scrollToMessageId(" + markedMessageId + ") for marked position");
        } else {
            info("scrollToMessageId failed for marked position, falling back to scrollToLastMessage");
            suppressNextSaveBeforeJump = true;
            try {
                Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                        new Class<?>[]{boolean.class, boolean.class}, false, false);
            } finally {
                suppressNextSaveBeforeJump = false;
            }
        }
        Toast.makeText(context, localizedJumpToMarkDone(context), Toast.LENGTH_SHORT).show();
    }

    private int resolveJumpToMarkIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_go_down", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_down", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_mylocation;
    }

    private volatile boolean suppressNextSaveBeforeJump;

    private void scrollChatToTop(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        ChatReadPositionStore.ReadPosition popped = dialogId != 0L
                ? ChatReadPositionStore.pop(context.getApplicationContext(), dialogId)
                : null;
        int targetMessageId = popped != null ? popped.messageId : 0;
        info("ScrollToTop: dialogId=" + dialogId + " targetMsgId=" + targetMessageId + " popped=" + (popped != null));
        if (targetMessageId > 0) {
            Toast.makeText(context, localizedScrollToLastStarted(context), Toast.LENGTH_SHORT).show();
            boolean invoked = invokeScrollToMessageId(chatActivity, targetMessageId);
            if (invoked) {
                info("Called scrollToMessageId(" + targetMessageId + ")");
            } else {
                info("scrollToMessageId failed, falling back to scrollToLastMessage");
                suppressNextSaveBeforeJump = true;
                try {
                    Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                            new Class<?>[]{boolean.class, boolean.class}, false, false);
                } finally {
                    suppressNextSaveBeforeJump = false;
                }
            }
            Toast.makeText(context, localizedScrollToLastDone(context), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, localizedScrollToTopStarted(context), Toast.LENGTH_SHORT).show();
            suppressNextSaveBeforeJump = true;
            try {
                Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                        new Class<?>[]{boolean.class, boolean.class}, false, false);
            } finally {
                suppressNextSaveBeforeJump = false;
            }
            Toast.makeText(context, localizedScrollToTopDone(context), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean invokeScrollToMessageId(Object chatActivity, int messageId) {
        try {
            Class<?> clazz = chatActivity.getClass();
            Method method = Reflect.method(
                    clazz,
                    "scrollToMessageId",
                    int.class, int.class, boolean.class, int.class, boolean.class, int.class
            );
            Reflect.invoke(method, chatActivity, messageId, 0, true, 0, true, 0);
            return true;
        } catch (NoSuchMethodException ignored) {
            info("scrollToMessageId(IIZIZI) not found");
            return false;
        } catch (Throwable throwable) {
            error("scrollToMessageId invoke failed", throwable);
            return false;
        }
    }

    private boolean jumpToFirstMessage(Object chatActivity, Context context) {
        Toast.makeText(context, localizedFirstMessageStarted(context), Toast.LENGTH_SHORT).show();
        boolean invoked = invokeScrollToMessageId(chatActivity, 1);
        info("JumpToFirst: targetMsgId=1 invoked=" + invoked
                + " dialog=" + Reflect.asLong(Reflect.invokeIfExists(
                chatActivity, "getDialogId", new Class<?>[0]), 0L));
        Toast.makeText(
                context,
                invoked
                        ? localizedFirstMessageRequested(context)
                        : localizedScrollUnavailable(context),
                Toast.LENGTH_SHORT
        ).show();
        return invoked;
    }

    private int resolveScrollTopIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_go_up", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_up", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_upload;
    }

    private int resolveFirstMessageIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier(
                "msg_arrow_up", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier(
                "msg_go_up", "drawable", telegramResourcePackageName);
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_upload;
    }

    private CharSequence localizedFirstMessageLabel(Context context) {
        return isChineseLocale(context) ? "跳转到第一条消息" : "Jump to first message";
    }

    private CharSequence localizedFirstMessageStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到第一条消息…" : "Jumping to first message…";
    }

    private CharSequence localizedFirstMessageRequested(Context context) {
        return isChineseLocale(context) ? "已请求第一条消息" : "First message requested";
    }

    private CharSequence localizedScrollTopLabel(Context context) {
        return isChineseLocale(context) ? "跳转到上次浏览" : "Jump to last viewed";
    }

    private CharSequence localizedScrollToTopStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到频道顶部…" : "Jumping to channel top…";
    }

    private CharSequence localizedScrollToLastStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到上次浏览的消息…" : "Jumping to last viewed message…";
    }

    private CharSequence localizedScrollToTopDone(Context context) {
        return isChineseLocale(context) ? "已到达频道顶部" : "Reached channel top";
    }

    private CharSequence localizedScrollToLastDone(Context context) {
        return isChineseLocale(context) ? "已到达上次浏览的消息" : "Reached last viewed message";
    }

    private CharSequence localizedScrollToLastNotFound(Context context) {
        return isChineseLocale(context) ? "未找到上次浏览的消息" : "Last viewed message not found";
    }

    private CharSequence localizedScrollGaveUp(Context context) {
        return isChineseLocale(context) ? "跳转失败" : "Could not reach target";
    }

    private CharSequence localizedScrollUnavailable(Context context) {
        return isChineseLocale(context) ? "无法获取消息列表" : "Cannot access message list";
    }

    private void injectGlobalSettingsMenu(Object host, boolean requireSettingsFlag) {
        if (requireSettingsFlag) {
            Object isSettings = Reflect.invokeIfExists(host, "isSettings", new Class<?>[0]);
            if (isSettings instanceof Boolean) {
                if (!((Boolean) isSettings)) {
                    return;
                }
            } else {
                info(host.getClass().getSimpleName() + ".isSettings unavailable; attempting fallback menu injection");
            }
        }
        Object otherItem = resolveOverflowMenuItem(host);
        if (otherItem == null) {
            info(host.getClass().getSimpleName() + " overflow menu item not found");
            return;
        }
        Context context = contextFromMenuItem(otherItem);
        int iconRes = resolveIcon(context);
        View subItemView = reconcileMenuItemView(otherItem, MENU_ID_GLOBAL);
        if (subItemView == null) {
            Object subItem = addMenuSubItem(
                    otherItem, MENU_ID_GLOBAL, iconRes, localizedGlobalMenuLabel(context));
            if (!(subItem instanceof View)) {
                info(host.getClass().getSimpleName()
                        + " menu addSubItem unavailable on " + otherItem.getClass().getName());
                return;
            }
            subItemView = (View) subItem;
            subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_GLOBAL);
        }
        View boundSubItemView = subItemView;
        uiCallbacks.setClickListener(boundSubItemView, v -> {
            try {
                openConfigFromHost(host, v.getContext(), CONFIG_MODE_GLOBAL, 0L, "");
            } finally {
                Reflect.invokeIfExists(otherItem, "toggleSubMenu", new Class<?>[0]);
            }
        });
    }

    private void injectSettingsListRow(Object host, ArrayList<Object> items, Method factoryOf) {
        if (items == null || containsGramSieveSettingsItem(items)) {
            return;
        }
        Context context = contextFromSettingsHost(host);
        Object item = Reflect.invoke(
                factoryOf,
                null,
                SETTINGS_ROW_GRAMSIEVE,
                SETTINGS_ROW_COLOR_START,
                SETTINGS_ROW_COLOR_END,
                resolveIcon(context),
                localizedSettingsRowLabel(context),
                localizedSettingsRowSubtitle(context)
        );
        if (item == null) {
            return;
        }
        int index = settingsRowInsertIndex(items);
        items.add(index, item);
        if (!settingsListRowLogged) {
            settingsListRowLogged = true;
            info("Injected GramSieve settings row at index=" + index);
        }
    }

    private boolean containsGramSieveSettingsItem(List<?> items) {
        for (Object item : items) {
            if (isGramSieveSettingsItem(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGramSieveSettingsItem(Object item) {
        if (settingsItemId(item) == SETTINGS_ROW_GRAMSIEVE) {
            return true;
        }
        String text = settingsItemText(item, "text");
        return "GramSieve".contentEquals(text);
    }

    private int settingsRowInsertIndex(List<?> items) {
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String text = settingsItemText(item, "text");
            String subtext = settingsItemText(item, "subtext");
            if (!text.isBlank() && !subtext.isBlank()) {
                return i;
            }
        }
        return items.size();
    }

    private int settingsItemId(Object item) {
        if (item == null) {
            return Integer.MIN_VALUE;
        }
        return Reflect.asInt(Reflect.field(item, "id"), Integer.MIN_VALUE);
    }

    private String settingsItemText(Object item, String fieldName) {
        return Reflect.asString(Reflect.field(item, fieldName)).trim();
    }

    private Context contextFromSettingsClick(Object host, Object clickedView) {
        if (clickedView instanceof View) {
            Context context = ((View) clickedView).getContext();
            if (context != null) {
                return context;
            }
        }
        return contextFromSettingsHost(host);
    }

    private Context contextFromSettingsHost(Object host) {
        View view = resolveHostFragmentView(host);
        if (view != null && view.getContext() != null) {
            return view.getContext();
        }
        Object context = Reflect.invokeIfExists(host, "getContext", new Class<?>[0]);
        if (context instanceof Context) {
            return (Context) context;
        }
        Object activity = Reflect.invokeIfExists(host, "getParentActivity", new Class<?>[0]);
        if (activity instanceof Context) {
            return (Context) activity;
        }
        return resolveHostApplication();
    }

    private boolean closeHostConfigPanel(Object host) {
        Context context = contextFromSettingsHost(host);
        ViewGroup root = resolveHostConfigRoot(host, context);
        boolean closed = HostConfigPanel.closeExisting(root);
        if (closed) {
            info("Closed host config panel from SettingsActivity back");
        }
        return closed;
    }

    private Object resolveOverflowMenuItem(Object host) {
        Object direct = Reflect.field(host, "otherItem");
        if (direct != null) {
            return direct;
        }
        Object actionBar = Reflect.field(host, "actionBar");
        if (actionBar == null) {
            return null;
        }
        Object menu = Reflect.field(actionBar, "menu");
        if (menu instanceof ViewGroup) {
            Object lastItem = lastActionBarMenuItem((ViewGroup) menu);
            if (lastItem != null) {
                return lastItem;
            }
        }
        if (actionBar instanceof ViewGroup) {
            return findMenuItemFromActionBar((ViewGroup) actionBar);
        }
        return null;
    }

    private Object findMenuItemFromActionBar(ViewGroup actionBar) {
        for (int i = actionBar.getChildCount() - 1; i >= 0; i--) {
            View child = actionBar.getChildAt(i);
            if (child instanceof ViewGroup) {
                Object lastItem = lastActionBarMenuItem((ViewGroup) child);
                if (lastItem != null) {
                    return lastItem;
                }
            }
        }
        return null;
    }

    private Object lastActionBarMenuItem(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
        }
        return null;
    }

    private View findMenuItemView(Object menuItem, int targetId) {
        ViewGroup popupLayout = menuPopupLayout(menuItem);
        if (popupLayout == null) {
            return null;
        }
        List<View> matches = new ArrayList<>();
        collectTaggedMenuItemViews(popupLayout, targetId, matches);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private View reconcileMenuItemView(Object menuItem, int targetId) {
        ViewGroup popupLayout = menuPopupLayout(menuItem);
        if (popupLayout == null) {
            return null;
        }
        List<View> matches = new ArrayList<>();
        collectTaggedMenuItemViews(popupLayout, targetId, matches);
        if (matches.isEmpty()) {
            return null;
        }
        View retained = matches.get(0);
        int removed = 0;
        for (int i = 1; i < matches.size(); i++) {
            View duplicate = matches.get(i);
            ViewParent parent = duplicate.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(duplicate);
                removed++;
            }
        }
        if (removed > 0) {
            info("MenuRebind: removed duplicates id=" + targetId + " count=" + removed);
        }
        return retained;
    }

    private ViewGroup menuPopupLayout(Object menuItem) {
        Object popupLayout = Reflect.invokeIfExists(menuItem, "getPopupLayout", new Class<?>[0]);
        if (!(popupLayout instanceof ViewGroup)) {
            popupLayout = Reflect.field(menuItem, "popupLayout");
        }
        return popupLayout instanceof ViewGroup ? (ViewGroup) popupLayout : null;
    }

    private void collectTaggedMenuItemViews(ViewGroup group, int targetId, List<View> matches) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object keyedTag = child.getTag(R.id.gramsieve_menu_item_id);
            Object tag = child.getTag();
            if ((keyedTag instanceof Integer && ((Integer) keyedTag) == targetId)
                    || (tag instanceof Integer && ((Integer) tag) == targetId)) {
                matches.add(child);
            }
            if (child instanceof ViewGroup) {
                collectTaggedMenuItemViews((ViewGroup) child, targetId, matches);
            }
        }
    }

    private Object addMenuSubItem(Object menuItem, int menuId, int iconRes, CharSequence title) {
        Object subItem = Reflect.invokeIfExists(
                menuItem,
                "addSubItem",
                new Class<?>[]{int.class, int.class, CharSequence.class},
                menuId,
                iconRes,
                title
        );
        if (subItem != null) {
            return subItem;
        }
        return Reflect.invokeIfExists(
                menuItem,
                "addSubItem",
                new Class<?>[]{int.class, CharSequence.class},
                menuId,
                title
        );
    }

    private Context contextFromMenuItem(Object menuItem) {
        if (menuItem instanceof View) {
            return ((View) menuItem).getContext();
        }
        Object context = Reflect.invokeIfExists(menuItem, "getContext", new Class<?>[0]);
        if (context instanceof Context) {
            return (Context) context;
        }
        throw new IllegalStateException("Menu item is not a view: " + menuItem);
    }

    private int resolveIcon(Context context) {
        if (context == null) {
            return android.R.drawable.ic_menu_manage;
        }
        int telegramIcon = context.getResources().getIdentifier("msg_settings", "drawable", telegramResourcePackageName);
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_manage;
    }

    private void adjustMenuItemShape(View view, boolean top, boolean bottom) {
        if (view == null) {
            return;
        }
        Reflect.invokeIfExists(view, "updateSelectorBackground", new Class<?>[]{boolean.class, boolean.class}, top, bottom);
    }

    private CharSequence localizedBlockMessageLabel(Context context) {
        return isChineseLocale(context) ? "屏蔽此消息" : "Block this message";
    }

    private CharSequence localizedMarkMessageLabel(Context context) {
        return isChineseLocale(context) ? "标记此消息" : "Mark this message";
    }

    private CharSequence localizedJumpToMarkLabel(Context context) {
        return isChineseLocale(context) ? "跳转到标记位置" : "Jump to marked position";
    }

    private CharSequence localizedMarkSavedToast(Context context) {
        return isChineseLocale(context) ? "已标记此消息" : "Message marked";
    }

    private CharSequence localizedNoMarkToast(Context context) {
        return isChineseLocale(context) ? "没有标记位置" : "No marked position";
    }

    private CharSequence localizedJumpToMarkStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到标记位置…" : "Jumping to marked position…";
    }

    private CharSequence localizedJumpToMarkDone(Context context) {
        return isChineseLocale(context) ? "已到达标记位置" : "Reached marked position";
    }

    private CharSequence localizedChatMenuLabel(Context context) {
        return isChineseLocale(context) ? "聊天过滤规则" : "Chat filters";
    }

    private CharSequence localizedGlobalMenuLabel(Context context) {
        return isChineseLocale(context) ? "过滤规则" : "Filters";
    }

    private CharSequence localizedSettingsRowLabel(Context context) {
        return "GramSieve";
    }

    private CharSequence localizedSettingsRowSubtitle(Context context) {
        return isChineseLocale(context) ? "过滤、反撤回、编辑历史" : "Filters, anti-recall, edit history";
    }

    private CharSequence localizedSavedToast(Context context) {
        return isChineseLocale(context) ? "已把这条消息加入屏蔽规则" : "Added a rule for this message";
    }

    private CharSequence localizedNoTextToast(Context context) {
        return isChineseLocale(context) ? "这条消息没有可提取的文字" : "This message has no text to extract";
    }

    private boolean isChineseLocale(Context context) {
        try {
            Locale locale = context.getResources().getConfiguration().locale;
            return locale != null && "zh".equalsIgnoreCase(locale.getLanguage());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private String resolveChatTitle(Object chatActivity) {
        Object currentChat = Reflect.field(chatActivity, "currentChat");
        String chatTitle = Reflect.asString(Reflect.field(currentChat, "title")).trim();
        if (!chatTitle.isBlank()) {
            return chatTitle;
        }
        Object currentUser = Reflect.field(chatActivity, "currentUser");
        String first = Reflect.asString(Reflect.field(currentUser, "first_name")).trim();
        String last = Reflect.asString(Reflect.field(currentUser, "last_name")).trim();
        String full = (first + " " + last).trim();
        if (!full.isBlank()) {
            return full;
        }
        return Reflect.asString(Reflect.field(currentUser, "username")).trim();
    }

    private void openConfigFromHost(Object host, Context context, String mode, long dialogId, String title) {
        try {
            if (showHostConfigPanel(host, context, mode, dialogId, title)) {
                return;
            }
        } catch (Throwable throwable) {
            error("Host config panel failed", throwable);
        }
        if (context != null) {
            Toast.makeText(
                    context,
                    isChineseLocale(context)
                            ? "无法在 Telegram 内打开 GramSieve 设置"
                            : "Could not open GramSieve settings in Telegram",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean showHostConfigPanel(Object host, Context context, String mode, long dialogId, String title) {
        if (context == null || configProvider == null) {
            return false;
        }
        boolean chatMode = CONFIG_MODE_CHAT.equals(mode);
        if (chatMode && backgroundMessageLoader == null) {
            initAntiRecallFromChat(host);
        } else if (!chatMode && antiRecallConfigStore == null) {
            initAntiRecallDeferred();
        }

        ViewGroup root = resolveHostConfigRoot(host, context);
        if (root == null) {
            info("Host config panel root unavailable for " + (host == null ? "null" : host.getClass().getName()));
            return false;
        }

        Context hostContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        AntiRecallConfigStore antiStore = antiRecallConfigStore != null
                ? antiRecallConfigStore
                : new AntiRecallConfigStore(hostContext);
        FilterConfig config = configProvider.getConfig(hostContext).deepCopy();
        int accountId = TelegramAccountResolver.resolveHost(host, savedClassLoader);
        boolean shown = HostConfigPanel.show(
                context,
                root,
                config,
                chatMode,
                dialogId,
                title,
                accountId,
                antiStore,
                backgroundMessageLoader,
                editHistoryPolicyStore,
                updated -> {
                    FilterConfig saved = saveUpdatedConfig(hostContext, updated);
                    decisionCache.clear();
                    return saved;
                },
                () -> {
                    decisionCache.clear();
                    if (chatMode) {
                        refreshChatActivityFiltering(host);
                    }
                },
                module
        );
        if (shown) {
            activeConfigRoot = new WeakReference<>(root);
            info("Opened host config panel mode=" + mode + " dialogId=" + dialogId);
        }
        return shown;
    }

    private ViewGroup resolveHostConfigRoot(Object host, Context context) {
        View anchor = resolveHostFragmentView(host);
        if (anchor != null) {
            View rootView = anchor.getRootView();
            if (rootView instanceof ViewGroup) {
                return (ViewGroup) rootView;
            }
            if (anchor instanceof ViewGroup) {
                return (ViewGroup) anchor;
            }
        }

        Activity activity = activityFromContext(context);
        if (activity != null && activity.getWindow() != null) {
            View decorView = activity.getWindow().getDecorView();
            if (decorView instanceof ViewGroup) {
                return (ViewGroup) decorView;
            }
        }
        return null;
    }

    private View resolveHostFragmentView(Object host) {
        if (host instanceof View) {
            return (View) host;
        }
        Object direct = Reflect.field(host, "fragmentView");
        if (direct instanceof View) {
            return (View) direct;
        }
        Object getter = Reflect.invokeIfExists(host, "getFragmentView", new Class<?>[0]);
        if (getter instanceof View) {
            return (View) getter;
        }
        Object contentView = Reflect.field(host, "contentView");
        if (contentView instanceof View) {
            return (View) contentView;
        }
        Object listView = Reflect.field(host, "listView");
        if (listView instanceof View) {
            return (View) listView;
        }
        return null;
    }

    private Activity activityFromContext(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private void logTelegramVersion(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        String buildVersion = "";
        try {
            Class<?> buildVarsClass = classLoader.loadClass("org.telegram.messenger.BuildVars");
            Object raw = Reflect.staticField(buildVarsClass, "BUILD_VERSION_STRING");
            buildVersion = Reflect.asString(raw).trim();
        } catch (Throwable ignored) {
            buildVersion = "";
        }
        String suffix = buildVersion.isBlank() ? "" : " build=" + buildVersion;
        info("Target Telegram package=" + applicationInfo.packageName + suffix + " source=" + applicationInfo.sourceDir);
    }

    private void hook(Method method, XposedInterface.Hooker hooker) {
        hook(method, XposedInterface.PRIORITY_LOWEST, hooker);
    }

    private void hook(Method method, int priority, XposedInterface.Hooker hooker) {
        module.hook(method)
                .setId(HookIdentity.forCaller("telegram", method))
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(chain -> retiring ? chain.proceed() : hooker.intercept(chain));
    }

    private void deoptimize(Method method, String label) {
        try {
            boolean changed = module.deoptimize(method);
            info((changed ? "Deoptimized " : "Deopt not needed for ") + label);
        } catch (Throwable throwable) {
            error("Failed to deoptimize " + label, throwable);
        }
    }

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }
}
