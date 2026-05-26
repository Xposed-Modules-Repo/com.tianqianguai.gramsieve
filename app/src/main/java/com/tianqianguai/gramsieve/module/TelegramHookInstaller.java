package com.tianqianguai.gramsieve.module;

import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupWindow;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.ChatReadPositionStore;
import com.tianqianguai.gramsieve.config.ConfigContentProvider;
import com.tianqianguai.gramsieve.config.ConfigUpdateReceiver;
import com.tianqianguai.gramsieve.config.DiagnosticLogStore;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.config.XposedConfigProvider;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageRuleFactory;
import com.tianqianguai.gramsieve.core.MessageSnapshot;
import com.tianqianguai.gramsieve.ui.ConfigDialogActivity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class TelegramHookInstaller {
    private static final String TAG = "GramSieve";
    private static final String MODULE_PACKAGE = "com.tianqianguai.gramsieve";
    private static final int MENU_ID_CHAT = 0x47530011;
    private static final int MENU_ID_GLOBAL = 0x47530012;
    private static final int MENU_ID_BLOCK_MESSAGE = 0x47530013;
    private static final int MENU_ID_SCROLL_TOP = 0x47530014;
    private static final int SCROLL_JUMP_THRESHOLD = 50;

    private final XposedModule module;
    private XposedConfigProvider configProvider;
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
    private boolean installed;
    private boolean persistentDiagnosticsUnavailable;
    private volatile long trackedDialogId;
    private volatile int lastTopmostMessageId;
    private volatile boolean readPositionDirty;
    private volatile boolean jumpDetected;

    TelegramHookInstaller(XposedModule module) {
        this.module = module;
    }

    synchronized void install(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        if (installed) {
            return;
        }
        if (configProvider == null) {
            configProvider = new XposedConfigProvider(
                    MODULE_PACKAGE,
                    () -> module.getRemotePreferences(ModuleConfigStore.PREFS_NAME)
            );
        }
        logRemoteCapabilities();
        logTelegramVersion(classLoader, applicationInfo);
        hookTaggedViewMeasure();
        hookChatMessageCell(classLoader);
        hookRecyclerViewBinding(classLoader);
        hookChatActivityAdapter(classLoader);
        hookChatActivityMenu(classLoader);
        hookChatActivityResume(classLoader);
        hookChatActivityPause(classLoader);
        hookScrollToLastMessage(classLoader);
        hookMessageContextMenu(classLoader);
        hookSettingsActivityMenu(classLoader);
        hookProfileSettingsMenu(classLoader);
        installed = true;
        info("Installed Telegram hooks");
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
            info("ChatMessageCell." + signature + " not present in this Telegram build");
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
            info("ChatMessageCell." + signature + " not present in this Telegram build");
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
            info("ChatMessageCell." + signature + " not present in this Telegram build");
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
            info("Hooked ProfileActivity settings menu");
        } catch (Throwable throwable) {
            error("Failed to hook ProfileActivity menu", throwable);
        }
    }

    private void hookSettingsActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> settingsActivityClass = classLoader.loadClass("org.telegram.ui.SettingsActivity");
            Method createView = Reflect.method(settingsActivityClass, "createView", Context.class);
            hook(createView, chain -> {
                Object result = chain.proceed();
                try {
                    injectGlobalSettingsMenu(chain.getThisObject(), false);
                } catch (Throwable throwable) {
                    error("SettingsActivity menu injection failed", throwable);
                }
                return result;
            });
            info("Hooked SettingsActivity menu");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity menu", throwable);
        }
    }

    private Object handleMessageBinding(XposedInterface.Chain chain) throws Throwable {
        Object cell = chain.getThisObject();
        Object messageObject = chain.getArg(0);
        emitHookEntry("message", cell, messageObject);
        Object result = chain.proceed();
        try {
            if (cell instanceof View) {
                applyDecision((View) cell, (View) cell, messageObject);
            }
        } catch (Throwable throwable) {
            error("Message filtering failed", throwable);
        }
        return result;
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
                    applyDecision(cellView, cellView, messageObject);
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
                DecisionContext context = evaluateDecisionContext(messageView, resolveMessageObject(cell));
                UiMutation.overrideMeasuredHeight(messageView, context.decision);
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
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
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
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("RecyclerView attachment filter failed", throwable);
        }
        return result;
    }

    private void applyDecisionToBoundViews(Object rootCandidate) {
        if (!(rootCandidate instanceof View)) {
            return;
        }
        View rowRoot = (View) rootCandidate;
        final DecisionContext[] matchedContext = new DecisionContext[1];
        final View[] matchedView = new View[1];
        boolean found = BoundMessageViewWalker.visit(
                rowRoot,
                (messageView, messageObject) -> {
                    DecisionContext context = evaluateDecisionContext(messageView, messageObject);
                    if (context.decision.matched && matchedContext[0] == null) {
                        matchedContext[0] = context;
                        matchedView[0] = messageView;
                    }
                }
        );
        if (matchedContext[0] != null) {
            applyDecisionContext(matchedView[0], rowRoot, matchedContext[0]);
            return;
        }
        if (!found) {
            UiMutation.apply(rowRoot, FilterDecision.allow(), "");
            return;
        }
        UiMutation.apply(rowRoot, FilterDecision.allow(), "");
    }

    private void applyDecision(View messageView, View mutationTarget, Object messageObject) {
        if (messageView == null) {
            return;
        }
        if (mutationTarget == null) {
            mutationTarget = messageView;
        }
        DecisionContext context = evaluateDecisionContext(messageView, messageObject);
        applyDecisionContext(messageView, mutationTarget, context);
    }

    private void applyDecisionContext(View messageView, View mutationTarget, DecisionContext context) {
        if (mutationTarget == null) {
            return;
        }
        UiMutation.apply(mutationTarget, context.decision, context.stableKey);
        if (messageView != null && mutationTarget != messageView && context.decision.matched) {
            UiMutation.apply(messageView, context.decision, context.stableKey);
        }
        View recyclerRow = findRecyclerDirectChild(messageView);
        if (recyclerRow != null && recyclerRow != mutationTarget && context.decision.matched) {
            UiMutation.apply(recyclerRow, context.decision, context.stableKey);
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
        if (messageView == null) {
            return DecisionContext.allow();
        }
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        if (snapshot == null) {
            return DecisionContext.allow();
        }
        FilterConfig config = configProvider.getConfig(messageView.getContext().getApplicationContext());
        FilterDecision decision = decisionCache.get(config, snapshot, () -> filterEngine.evaluate(config, snapshot));
        markFilteredMessageRead(messageObject, snapshot, decision);
        return new DecisionContext(config, snapshot, decision);
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
            Log.w(TAG, message + " error=" + exception.getMessage());
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
                        + " chat=" + preview(snapshot.chatName)
                        + " sender=" + preview(snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " text=" + preview(snapshot.text)
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
                        + " chat=" + preview(snapshot.chatName)
                        + " sender=" + preview(snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " msg=" + snapshot.messageId
                        + " text=" + preview(snapshot.text)
                        + " caption=" + preview(snapshot.caption)
                        + " buttons=" + preview(snapshot.buttonText)
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
        entry.chatName = decisionContext.snapshot.chatName;
        entry.senderName = decisionContext.snapshot.senderName;
        entry.text = decisionContext.snapshot.text;
        entry.caption = decisionContext.snapshot.caption;
        entry.buttonText = decisionContext.snapshot.buttonText;
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
            Log.w(TAG, "Persistent diagnostic append disabled: " + exception.getMessage());
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

        DecisionContext(FilterConfig config, MessageSnapshot snapshot, FilterDecision decision) {
            this.config = config;
            this.snapshot = snapshot;
            this.decision = decision;
            this.stableKey = snapshot == null ? "" : snapshot.stableKey();
        }

        static DecisionContext allow() {
            return new DecisionContext(null, null, FilterDecision.allow());
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
        blockItem.setOnClickListener(v -> {
            dismissScrimPopup(chatActivity);
            addRuleForSelectedMessage(v.getContext(), messageView, selectedMessageObject);
        });

        MenuInsertionPoint insertionPoint = findReportInsertionPoint((View) contentView);
        if (insertionPoint != null) {
            insertionPoint.parent.addView(
                    blockItem,
                    Math.min(insertionPoint.index + 1, insertionPoint.parent.getChildCount())
            );
        } else {
            ViewGroup fallbackContainer = resolvePopupLinearLayout(contentView);
            if (fallbackContainer == null) {
                return;
            }
            fallbackContainer.addView(blockItem);
        }
        refreshMessagePopup((View) contentView, blockItem, popupWindow);
        info("Injected block-message menu item");
    }

    private ViewGroup resolvePopupLinearLayout(Object contentView) {
        if (!(contentView instanceof ViewGroup)) {
            return null;
        }
        Object linearLayout = Reflect.field(contentView, "linearLayout");
        if (linearLayout instanceof ViewGroup) {
            return (ViewGroup) linearLayout;
        }
        return (ViewGroup) contentView;
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
        int telegramIcon = context.getResources().getIdentifier("report", "drawable", "org.telegram.messenger");
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_close_clear_cancel;
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

    private void addTelegramString(List<String> labels, Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", "org.telegram.messenger");
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
        popupContent.post(() -> {
            refreshRadialSelectors(insertedItem);
            popupContent.requestLayout();
            popupContent.invalidate();
            if (popupWindow instanceof PopupWindow) {
                PopupWindow window = (PopupWindow) popupWindow;
                window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                window.update();
            }
        });
        if (popupWindow instanceof PopupWindow) {
            PopupWindow window = (PopupWindow) popupWindow;
            window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            window.update();
        }
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
        FilterConfig saved = saveToRemotePreferences(updated);
        if (saved == null) {
            saved = saveToContentProvider(hostContext, updated);
        }
        if (saved != null) {
            updated = saved;
        }
        persistToModuleProcess(hostContext, updated);
        configProvider.replaceCachedConfig(updated);
        return updated;
    }

    private void persistToModuleProcess(Context hostContext, FilterConfig updated) {
        try {
            if (hostContext == null || updated == null) {
                return;
            }
            String encodedConfig = encodedConfig(updated);
            Intent activityIntent = new Intent();
            activityIntent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".config.ConfigPersistActivity"));
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(ConfigUpdateReceiver.EXTRA_CONFIG_JSON_BASE64, encodedConfig);
            hostContext.startActivity(activityIntent);
            info("Requested module-local config persistence activity updatedAt=" + updated.updatedAtEpochMs);

            Intent intent = new Intent(ConfigUpdateReceiver.ACTION_SAVE_CONFIG);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".config.ConfigUpdateReceiver"));
            intent.putExtra(ConfigUpdateReceiver.EXTRA_CONFIG_JSON_BASE64, encodedConfig);
            hostContext.sendBroadcast(intent);
            info("Requested module-local config persistence broadcast updatedAt=" + updated.updatedAtEpochMs);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Failed to request module-local config persistence: " + exception.getMessage());
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
                Log.w(TAG, "Failed to save message rule through remote preferences: commit=false");
                return null;
            }
            String savedJson = remotePreferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null);
            FilterConfig saved = ModuleConfigStore.fromJson(savedJson);
            if (!sameConfigExceptTimestamp(updated, saved)) {
                Log.w(TAG, "Failed to save message rule through remote preferences: readback mismatch");
                return null;
            }
            info("Saved message rule through remote preferences updatedAt=" + saved.updatedAtEpochMs);
            return saved.deepCopy();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Failed to save message rule through remote preferences: " + exception.getMessage());
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
                Log.w(TAG, "Failed to save message rule through content provider: readback mismatch");
                return null;
            }
            info("Saved message rule through content provider updatedAt=" + saved.updatedAtEpochMs);
            return saved;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Failed to save message rule through content provider: " + exception.getMessage());
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
        refreshRoot.post(() -> {
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
        if (!hasMenuItem(headerItem, MENU_ID_CHAT)) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveIcon(context);
            Object subItem = addMenuSubItem(headerItem, MENU_ID_CHAT, iconRes, localizedChatMenuLabel(context));
            if (subItem instanceof View) {
                View subItemView = (View) subItem;
                subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_CHAT);
                subItemView.setOnClickListener(v -> {
                    try {
                        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
                        String title = resolveChatTitle(chatActivity);
                        launchConfig(v.getContext(), ConfigDialogActivity.MODE_CHAT, dialogId, title);
                    } finally {
                        Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                    }
                });
            } else {
                info("ChatActivity menu addSubItem unavailable on " + headerItem.getClass().getName());
            }
        }
        injectScrollToTopMenu(chatActivity, headerItem);
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
        if (hasMenuItem(headerItem, MENU_ID_SCROLL_TOP)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        int iconRes = resolveScrollTopIcon(context);
        Object subItem = addMenuSubItem(headerItem, MENU_ID_SCROLL_TOP, iconRes, localizedScrollTopLabel(context));
        if (!(subItem instanceof View)) {
            info("Scroll-to-top addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_SCROLL_TOP);
        subItemView.setOnClickListener(v -> {
            try {
                info("ScrollToTop menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                scrollChatToTop(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Scroll to top failed", throwable);
            }
        });
    }

    private volatile boolean suppressNextSaveBeforeJump;

    private void scrollChatToTop(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        ChatReadPositionStore.ReadPosition saved = dialogId != 0L
                ? ChatReadPositionStore.load(context.getApplicationContext(), dialogId)
                : null;
        int targetMessageId = saved != null ? saved.messageId : 0;
        info("ScrollToTop: dialogId=" + dialogId + " targetMsgId=" + targetMessageId + " saved=" + (saved != null));
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

    private int resolveScrollTopIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_go_up", "drawable", "org.telegram.messenger");
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_up", "drawable", "org.telegram.messenger");
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_upload;
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
        if (hasMenuItem(otherItem, MENU_ID_GLOBAL)) {
            return;
        }
        Context context = contextFromMenuItem(otherItem);
        int iconRes = resolveIcon(context);
        Object subItem = addMenuSubItem(otherItem, MENU_ID_GLOBAL, iconRes, localizedGlobalMenuLabel(context));
        if (!(subItem instanceof View)) {
            info(host.getClass().getSimpleName() + " menu addSubItem unavailable on " + otherItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_GLOBAL);
        subItemView.setOnClickListener(v -> {
            try {
                launchConfig(v.getContext(), ConfigDialogActivity.MODE_GLOBAL, 0L, "");
            } finally {
                Reflect.invokeIfExists(otherItem, "toggleSubMenu", new Class<?>[0]);
            }
        });
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

    private boolean hasMenuItem(Object menuItem, int targetId) {
        Object popupLayout = Reflect.invokeIfExists(menuItem, "getPopupLayout", new Class<?>[0]);
        if (!(popupLayout instanceof ViewGroup)) {
            return false;
        }
        ViewGroup group = (ViewGroup) popupLayout;
        for (int i = 0; i < group.getChildCount(); i++) {
            Object keyedTag = group.getChildAt(i).getTag(R.id.gramsieve_menu_item_id);
            if (keyedTag instanceof Integer && ((Integer) keyedTag) == targetId) {
                return true;
            }
            Object tag = group.getChildAt(i).getTag();
            if (tag instanceof Integer && ((Integer) tag) == targetId) {
                return true;
            }
        }
        return false;
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
        int telegramIcon = context.getResources().getIdentifier("msg_settings", "drawable", "org.telegram.messenger");
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

    private CharSequence localizedChatMenuLabel(Context context) {
        return isChineseLocale(context) ? "聊天过滤规则" : "Chat filters";
    }

    private CharSequence localizedGlobalMenuLabel(Context context) {
        return isChineseLocale(context) ? "过滤规则" : "Filters";
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

    private void launchConfig(Context context, String mode, long dialogId, String title) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE, ConfigDialogActivity.class.getName()));
        intent.putExtra(ConfigDialogActivity.EXTRA_MODE, mode);
        if (ConfigDialogActivity.MODE_CHAT.equals(mode)) {
            intent.putExtra(ConfigDialogActivity.EXTRA_DIALOG_ID, dialogId);
            intent.putExtra(ConfigDialogActivity.EXTRA_DIALOG_TITLE, title);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
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
        module.hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(hooker);
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
        Log.i(TAG, message);
        module.log(Log.INFO, TAG, message);
    }

    private void error(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
        module.log(Log.ERROR, TAG, message, throwable);
    }
}
