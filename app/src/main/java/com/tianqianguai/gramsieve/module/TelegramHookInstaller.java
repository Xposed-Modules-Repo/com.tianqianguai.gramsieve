package com.tianqianguai.gramsieve.module;

import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
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
import com.tianqianguai.gramsieve.config.ModuleLogger;
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
    private static final int MENU_ID_SELECT_ALL = 0x47530015;
    private static final int MENU_ID_MARK_MESSAGE = 0x47530016;
    private static final int MENU_ID_JUMP_TO_MARK = 0x47530017;
    private ViewGroup downloadPageFragmentView = null;
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
        hookDownloadActivityMenu(classLoader);
        hookOnItemClickDiagnostic(classLoader);
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

    private void hookDownloadActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            Method createView = Reflect.method(dialogsClass, "createView", Context.class);
            hook(createView, chain -> {
                Object result = chain.proceed();
                try {
                    Object activity = chain.getThisObject();
                    Object actionBar = Reflect.field(activity, "actionBar");
                    if (actionBar instanceof ViewGroup) {
                        View bar = (ViewGroup) actionBar;
                        Object activityRef = activity;
                        bar.postDelayed(() -> {
                            try {
                                startKeepVisibleLoop((ViewGroup) bar);
                                installActionModeDetector((ViewGroup) bar);
                                // After download button auto-clicks, also install detector on
                                // the download page's internal ActionBarMenu
                                bar.postDelayed(() -> {
                                    try {
                                        installActionModeDetectorOnDownloadPage((ViewGroup) bar, activityRef);
                                    } catch (Throwable t) {
                                        error("SelectAll: download page detector failed", t);
                                    }
                                }, 8000);
                            } catch (Throwable t) {
                                error("SelectAll: loop failed", t);
                            }
                        }, 500);
                    }
                } catch (Throwable t) {
                    error("SelectAll: createView hook failed", t);
                }
                return result;
            });
            info("Hooked DialogsActivity.createView for download button");
        } catch (Throwable t) {
            error("Failed to hook DialogsActivity.createView", t);
        }
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
                    } else if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value);
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void startKeepVisibleLoop(ViewGroup actionBar) {
        // 不再添加自定义下载按钮，完全依赖原生逻辑
        info("SelectAll: skipping custom download button, using native");
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
        menu.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        menu.postDelayed(() -> {
                            try {
                                if (menu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
                                    // Check if we're on the download page
                                    if (downloadPageFragmentView != null) {
                                        info("SelectAll: on download page, injecting Select All into action mode");
                                        injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
                                    } else {
                                        injectSelectAllIntoActionMode(menu);
                                    }
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
        actionBar.postDelayed(new Runnable() {
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
                    if (actionModeActive && actionModeMenu != null && actionModeMenu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
                        info("SelectAll: action mode detected, injecting Select All into action mode menu");
                        injectSelectAllIntoActionModeForDownload(actionModeMenu, fragmentViewRef);
                    }
                    actionBar.postDelayed(this, 500);
                } catch (Throwable t) {
                    error("SelectAll: poll error", t);
                    actionBar.postDelayed(this, 1000);
                }
            }
        }, 500);
    }

    /**
     * Injects "Select All" button next to the action buttons (like delete)
     * that appear when selection mode is active.
     */
    private void injectSelectAllNextToActionButton(ViewGroup menu, ViewGroup actionBar, ViewGroup fragmentView) {
        if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = actionBar.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setBackgroundColor(0x33FFFFFF);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Inject into ActionBarMenu (not ActionBar container) so it appears
        // alongside the action buttons (delete, forward, etc.)
        int insertIndex = menu.getChildCount();
        menu.addView(button, insertIndex, lp);
        menu.invalidate();
        menu.requestLayout();
        info("SelectAll: injected into ActionBarMenu at index=" + insertIndex + " children=" + menu.getChildCount());
        button.setOnClickListener(v -> {
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
            return;
        }
        menu.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        menu.postDelayed(() -> {
                            try {
                                if (menu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
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
                    info("SelectAll: " + label + "[" + depth + "][" + i + "] text=\"" + text + "\"");
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
        if (view instanceof TextView) extra = " text=\"" + ((TextView) view).getText() + "\"";
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
            if (child instanceof TextView) text = " text=\"" + ((TextView) child).getText() + "\"";
            int id = child.getId();
            Object tag = child.getTag();
            info("SelectAll: menu[" + i + "]=" + name + text + " vis=" + child.getVisibility() + " id=" + id + " tag=" + tag);
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View sub = vg.getChildAt(j);
                    String subName = sub.getClass().getSimpleName();
                    String subText = "";
                    if (sub instanceof TextView) subText = " text=\"" + ((TextView) sub).getText() + "\"";
                    info("SelectAll:   [" + j + "]=" + subName + subText + " vis=" + sub.getVisibility());
                }
            }
        }
    }

    private void injectSelectAllIntoContentView(View actionBar) {
        if (!(actionBar instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) actionBar;
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) return;
        if (bar.getChildCount() < 5) return;
        View contentView = bar.getChildAt(4);
        if (!(contentView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) contentView;
        info("SelectAll: ActionBar content children=" + content.getChildCount());
        int xIndex = -1;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            String txt = "";
            if (c instanceof TextView) txt = " text=\"" + ((TextView) c).getText() + "\"";
            info("SelectAll:   [" + i + "]=" + c.getClass().getSimpleName() + txt + " vis=" + c.getVisibility() + " w=" + c.getWidth());
            if (c instanceof android.widget.ImageButton || (c.getClass().getSimpleName().contains("Item") && c.getWidth() < 200 && c.getWidth() > 0)) {
                xIndex = i;
            }
        }
        Context context = content.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int insertIdx = 2;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c.getClass().getSimpleName().contains("Number") || (c instanceof TextView && c.getWidth() > 200)) {
                insertIdx = i + 1;
                break;
            }
        }
        content.addView(button, insertIdx, lp);
        info("SelectAll: inserted at index " + insertIdx);
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        button.setOnClickListener(v -> {
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
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = bar.getContext();
        int selectAllIcon = context.getResources().getIdentifier("msg_select_all", "drawable", "org.telegram.messenger");
        if (selectAllIcon == 0) {
            selectAllIcon = android.R.drawable.ic_menu_agenda;
        }
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(isChineseLocale(context) ? "全选" : "Select All");
        button.setTextSize(14);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setOnClickListener(v -> {
            try {
                selectAllDownloadItems(activity);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
        bar.addView(button, 0);
        info("SelectAll: injected button into " + activity.getClass().getSimpleName() + " actionBar");
    }

    private void injectSelectAllIntoActionMode(Object menu) {
        if (!(menu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menuView = (ViewGroup) menu;
        if (menuView.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = menuView.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setOnClickListener(v -> {
            try {
                selectAllInActionMode(menuView);
            } catch (Throwable throwable) {
                error("Select all in action mode failed", throwable);
            }
        });
        menuView.addView(button);
        info("SelectAll: injected into action mode bar");
    }

    /**
     * Injects "Select All" button into the action mode bar for the download page.
     * When clicked, it selects all download items.
     */
    private void injectSelectAllIntoActionModeForDownload(ViewGroup menuView, ViewGroup fragmentView) {
        if (menuView.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = menuView.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        // No background - match the delete button style
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: download page Select All clicked in action mode");
                selectAllDownloadItems(fragmentView);
            } catch (Throwable t) {
                error("SelectAll: download select all failed: " + t.getMessage(), t);
            }
        });
        menuView.addView(button, lp);
        info("SelectAll: injected into action mode bar for download page");
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
        rv.post(() -> {
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
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = bar.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setBackgroundColor(0x33FFFFFF);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int childCount = bar.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = bar.getChildAt(i);
            if (child instanceof ViewGroup && child.getVisibility() == View.VISIBLE && child.getWidth() > 100) {
                ViewGroup content = (ViewGroup) child;
                int insertIndex = content.getChildCount() > 0 ? content.getChildCount() : 0;
                content.addView(button, insertIndex, lp);
                button.bringToFront();
                content.invalidate();
                content.requestLayout();
                info("SelectAll: added to child[" + i + "] at " + insertIndex + " broughtToFront");
                return;
            }
        }
        bar.addView(button, lp);
        button.bringToFront();
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: button clicked!");
                selectAllFromActionBar(bar);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
        button.setOnTouchListener((v, event) -> {
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

        View markItem = createMessageMarkMenuItem(((View) contentView).getContext(), chatActivity);
        if (markItem != null) {
            markItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_MARK_MESSAGE);
            markItem.setOnClickListener(v -> {
                dismissScrimPopup(chatActivity);
                markSelectedMessage(v.getContext(), chatActivity, selectedMessageObject);
            });
        }

        MenuInsertionPoint insertionPoint = findReportInsertionPoint((View) contentView);
        if (insertionPoint != null) {
            insertionPoint.parent.addView(
                    blockItem,
                    Math.min(insertionPoint.index + 1, insertionPoint.parent.getChildCount())
            );
            if (markItem != null) {
                insertionPoint.parent.addView(
                        markItem,
                        Math.min(insertionPoint.index + 2, insertionPoint.parent.getChildCount())
                );
            }
        } else {
            ViewGroup fallbackContainer = resolvePopupLinearLayout(contentView);
            if (fallbackContainer == null) {
                return;
            }
            fallbackContainer.addView(blockItem);
            if (markItem != null) {
                fallbackContainer.addView(markItem);
            }
        }
        refreshMessagePopup((View) contentView, blockItem, popupWindow);
        info("Injected block-message and mark-message menu items");
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
        int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", "org.telegram.messenger");
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_bookmark", "drawable", "org.telegram.messenger");
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
    }

    private void markSelectedMessage(Context context, Object chatActivity, Object messageObject) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int messageId = resolveMessageId(messageObject);
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        saveMarkedPosition(context.getApplicationContext(), dialogId, messageId);
        Toast.makeText(context, localizedMarkSavedToast(context), Toast.LENGTH_SHORT).show();
        info("Marked message " + messageId + " for dialog " + dialogId);
    }

    private void saveMarkedPosition(Context context, long dialogId, int messageId) {
        SharedPreferences prefs = context.getSharedPreferences("gramsieve_marked_positions", Context.MODE_PRIVATE);
        prefs.edit().putInt("marked_msg_" + dialogId, messageId).apply();
    }

    private int loadMarkedPosition(Context context, long dialogId) {
        SharedPreferences prefs = context.getSharedPreferences("gramsieve_marked_positions", Context.MODE_PRIVATE);
        return prefs.getInt("marked_msg_" + dialogId, 0);
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
        injectJumpToMarkMenu(chatActivity, headerItem);
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

    private void injectJumpToMarkMenu(Object chatActivity, Object headerItem) {
        if (hasMenuItem(headerItem, MENU_ID_JUMP_TO_MARK)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        int iconRes = resolveJumpToMarkIcon(context);
        Object subItem = addMenuSubItem(headerItem, MENU_ID_JUMP_TO_MARK, iconRes, localizedJumpToMarkLabel(context));
        if (!(subItem instanceof View)) {
            info("Jump-to-mark addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_JUMP_TO_MARK);
        subItemView.setOnClickListener(v -> {
            try {
                info("JumpToMark menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                jumpToMarkedPosition(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Jump to mark failed", throwable);
            }
        });
    }

    private void jumpToMarkedPosition(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int markedMessageId = loadMarkedPosition(context.getApplicationContext(), dialogId);
        if (markedMessageId <= 0) {
            Toast.makeText(context, localizedNoMarkToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
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
        int telegramIcon = context.getResources().getIdentifier("msg_go_down", "drawable", "org.telegram.messenger");
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_down", "drawable", "org.telegram.messenger");
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
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }
}
