package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Installs the Telegram hooks used by reliable video downloads. */
final class ReliableDownloadHooks {
    private static final String TAG = "GramSieve";

    private final XposedModule module;
    private final DownloadCancellationRegistry cancellationRegistry;
    private final BooleanSupplier useExternalDownload;
    private ReliableVideoDownloadManager downloadManager;
    private volatile boolean active = true;

    ReliableDownloadHooks(XposedModule module, DownloadCancellationRegistry cancellationRegistry) {
        this(module, cancellationRegistry, () -> false);
    }

    ReliableDownloadHooks(XposedModule module, DownloadCancellationRegistry cancellationRegistry,
                          BooleanSupplier useExternalDownload) {
        this.module = module;
        this.cancellationRegistry = cancellationRegistry;
        this.useExternalDownload = useExternalDownload == null ? () -> false : useExternalDownload;
    }

    void install(ClassLoader classLoader) {
        active = true;
        downloadManager = new ReliableVideoDownloadManager(cancellationRegistry, useExternalDownload);
        downloadManager.setClassLoader(classLoader);
        hookDownloadButton(classLoader);
        hookDownloadMiniButton(classLoader);
        hookDownloadTransport(classLoader);
        hookDownloadNotifications(classLoader);
    }

    boolean prepareForHotReload() {
        active = false;
        ReliableVideoDownloadManager manager = downloadManager;
        downloadManager = null;
        return manager == null || manager.prepareForHotReload();
    }

    private void hookDownloadButton(ClassLoader classLoader) {
        try {
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            Method method = Reflect.method(cellClass, "didPressButton", boolean.class, boolean.class);
            deoptimize(method, "ChatMessageCell.didPressButton(boolean, boolean)");
            hook(method, chain -> {
                if (usesExternalDownload()) {
                    return chain.proceed();
                }
                Object[] buttonArgs = chain.getArgs().toArray(new Object[0]);
                downloadManager.onUserButton(chain.getThisObject(), buttonArgs);
                Object result = chain.proceed();
                downloadManager.onUserButtonComplete(chain.getThisObject(), buttonArgs);
                return result;
            });
            info("ReliableDownload: hooked ChatMessageCell.didPressButton");
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook user download button", throwable);
        }
    }

    private void hookDownloadMiniButton(ClassLoader classLoader) {
        try {
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            Method method = Reflect.method(cellClass, "didPressMiniButton", boolean.class);
            deoptimize(method, "ChatMessageCell.didPressMiniButton(boolean)");
            hook(method, chain -> {
                if (usesExternalDownload()) {
                    return chain.proceed();
                }
                Object[] buttonArgs = chain.getArgs().toArray(new Object[0]);
                downloadManager.onUserMiniButton(chain.getThisObject(), buttonArgs);
                Object result = chain.proceed();
                downloadManager.onUserMiniButtonComplete(chain.getThisObject(), buttonArgs);
                return result;
            });
            info("ReliableDownload: hooked ChatMessageCell.didPressMiniButton");
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook user mini download button", throwable);
        }
    }

    private void hookDownloadTransport(ClassLoader classLoader) {
        try {
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            boolean hooked = false;
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadFile".equals(method.getName()) || parameters.length != 4
                        || parameters[2] != int.class || parameters[3] != int.class
                        || method.getReturnType() != void.class) {
                    continue;
                }
                deoptimize(method, "FileLoader.loadFile(Document, Object, int, int)");
                hook(method, chain -> {
                    if (usesExternalDownload()) {
                        return chain.proceed();
                    }
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!downloadManager.onLoadFile(chain.getThisObject(), loadArgs)) {
                        return null;
                    }
                    return chain.proceed();
                });
                hooked = true;
            }
            if (!hooked) {
                throw new NoSuchMethodException("FileLoader.loadFile(Document,Object,int,int)");
            }
            info("ReliableDownload: hooked FileLoader video download transport");
            hookDownloadCancelTransport(fileLoaderClass);
            hookDownloadStreamTransport(fileLoaderClass);
            hookDownloadInternalTransport(fileLoaderClass);
            hookDownloadLoadingQuery(fileLoaderClass);
            hookDownloadVideoLoadingState(fileLoaderClass);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader", throwable);
        }
    }

    private void hookDownloadCancelTransport(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"cancelLoadFile".equals(method.getName()) || parameters.length == 0) {
                    continue;
                }
                String label = "FileLoader.cancelLoadFile" + signatureOf(parameters);
                deoptimize(method, label);
                hook(method, chain -> {
                    Object[] cancelArgs = chain.getArgs().toArray(new Object[0]);
                    // Capture the marker before proceed(): reflective GramSieve cancellation
                    // invokes this hook while its origin ThreadLocal is still active.
                    String origin = ReliableVideoDownloadManager.currentCancelOrigin();
                    try {
                        return chain.proceed();
                    } finally {
                        try {
                            downloadManager.onCancelLoadFileInvocation(
                                    chain.getThisObject(), cancelArgs, label, origin);
                        } catch (Throwable diagnosticsFailure) {
                            error("ReliableDownload: cancel diagnostics failed", diagnosticsFailure);
                        }
                    }
                });
                hooked++;
            }
            info("ReliableDownload: hooked FileLoader cancel overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader cancel transport", throwable);
        }
    }

    private void hookDownloadStreamTransport(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadStreamFile".equals(method.getName()) || parameters.length < 4
                        || !"org.telegram.tgnet.TLRPC$Document".equals(parameters[1].getName())) {
                    continue;
                }
                deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                hook(method, chain -> {
                    if (usesExternalDownload()) {
                        return chain.proceed();
                    }
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!downloadManager.onLoadStreamFile(chain.getThisObject(), loadArgs)) {
                        // AnimatedFileDrawableStream and FileStreamLoadOperation accept a null
                        // operation here and stop waiting for the canceled stream.
                        return null;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.loadStreamFile(...,Document,...)");
            }
            info("ReliableDownload: hooked FileLoader stream transport overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader stream transport", throwable);
        }
    }

    private void hookDownloadInternalTransport(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadFileInternal".equals(method.getName()) || parameters.length < 6
                        || !"org.telegram.tgnet.TLRPC$Document".equals(parameters[0].getName())
                        || !"org.telegram.messenger.FileLoadOperation".equals(method.getReturnType().getName())) {
                    continue;
                }
                deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                hook(method, chain -> {
                    if (usesExternalDownload()) {
                        return chain.proceed();
                    }
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!downloadManager.onLoadFileInternal(chain.getThisObject(), loadArgs)) {
                        // This is the FileLoader queue's operation-creation point. Returning null
                        // also releases the stream caller's CountDownLatch without reviving the
                        // canceled download.
                        return null;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.loadFileInternal(Document,...)");
            }
            info("ReliableDownload: hooked FileLoader internal transport overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader internal transport", throwable);
        }
    }

    private void hookDownloadLoadingQuery(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"isLoadingFile".equals(method.getName()) || parameters.length != 1
                        || parameters[0] != String.class || method.getReturnType() != boolean.class) {
                    continue;
                }
                deoptimize(method, "FileLoader.isLoadingFile(String)");
                hook(method, chain -> {
                    if (usesExternalDownload()) {
                        return chain.proceed();
                    }
                    Object[] queryArgs = chain.getArgs().toArray(new Object[0]);
                    if (!downloadManager.onIsLoadingFile(chain.getThisObject(), queryArgs)) {
                        // ChatMessageCell derives the mini X directly from this query. Returning
                        // false removes a stale operation from the UI and asks the manager to
                        // cancel it by file name as a final safety net.
                        return false;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.isLoadingFile(String)");
            }
            info("ReliableDownload: hooked FileLoader loading-state query overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader loading-state query", throwable);
        }
    }

    private void hookDownloadVideoLoadingState(Class<?> fileLoaderClass) {
        int queries = 0;
        int mutations = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                boolean documentFirst = parameters.length > 0
                        && "org.telegram.tgnet.TLRPC$Document".equals(parameters[0].getName());
                if ("isLoadingVideo".equals(method.getName()) && parameters.length == 2
                        && documentFirst && parameters[1] == boolean.class
                        && method.getReturnType() == boolean.class) {
                    deoptimize(method, "FileLoader.isLoadingVideo(Document, boolean)");
                    hook(method, chain -> {
                        if (usesExternalDownload()) {
                            return chain.proceed();
                        }
                        Object[] queryArgs = chain.getArgs().toArray(new Object[0]);
                        if (!downloadManager.onIsLoadingVideo(chain.getThisObject(), queryArgs)) {
                            return false;
                        }
                        return chain.proceed();
                    });
                    queries++;
                } else if ((("setLoadingVideo".equals(method.getName()) && parameters.length == 3)
                        || ("setLoadingVideoForPlayer".equals(method.getName()) && parameters.length == 2))
                        && documentFirst && parameters[1] == boolean.class
                        && method.getReturnType() == void.class) {
                    deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                    hook(method, chain -> {
                        if (usesExternalDownload()) {
                            return chain.proceed();
                        }
                        Object[] stateArgs = chain.getArgs().toArray(new Object[0]);
                        if (!downloadManager.onSetLoadingVideo(
                                chain.getThisObject(), stateArgs, method.getName())) {
                            return null;
                        }
                        return chain.proceed();
                    });
                    mutations++;
                }
            }
            if (queries == 0 || mutations == 0) {
                throw new NoSuchMethodException("FileLoader video loading-state methods queries="
                        + queries + " mutations=" + mutations);
            }
            info("ReliableDownload: hooked FileLoader player loading-state queries=" + queries
                    + " mutators=" + mutations);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader player loading-state", throwable);
        }
    }

    private void hookDownloadNotifications(ClassLoader classLoader) {
        try {
            Class<?> notificationClass = classLoader.loadClass("org.telegram.messenger.NotificationCenter");
            int progressId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoadProgressChanged"), -1);
            int loadedId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoaded"), -1);
            int failedId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoadFailed"), -1);
            Method method = Reflect.method(notificationClass, "postNotificationName", int.class, Object[].class);
            hook(method, chain -> {
                Object result = chain.proceed();
                if (usesExternalDownload()) {
                    return result;
                }
                List<Object> hookArgs = chain.getArgs();
                if (hookArgs != null && hookArgs.size() >= 2 && hookArgs.get(1) instanceof Object[]) {
                    downloadManager.onNotification(
                            chain.getThisObject(),
                            Reflect.asInt(hookArgs.get(0), -1),
                            (Object[]) hookArgs.get(1),
                            progressId, loadedId, failedId
                    );
                }
                return result;
            });
            info("ReliableDownload: hooked progress notifications ids="
                    + progressId + "/" + loadedId + "/" + failedId);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook progress notifications", throwable);
        }
    }

    private void hook(Method method, XposedInterface.Hooker hooker) {
        module.hook(method)
                .setId(HookIdentity.forCaller("download", method))
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(chain -> active ? hooker.intercept(chain) : chain.proceed());
    }

    private boolean usesExternalDownload() {
        try {
            return useExternalDownload.getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void deoptimize(Method method, String label) {
        try {
            boolean changed = module.deoptimize(method);
            info((changed ? "Deoptimized " : "Deopt not needed for ") + label);
        } catch (Throwable throwable) {
            error("Failed to deoptimize " + label, throwable);
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

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }
}
