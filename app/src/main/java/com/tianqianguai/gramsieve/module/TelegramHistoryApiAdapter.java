package com.tianqianguai.gramsieve.module;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Capability-bound adapter for Telegram's private history API.
 *
 * <p>This adapter intentionally selects by runtime shape rather than Telegram version number.
 * A version with an unknown shape is reported as unsupported; callers must never fall back to
 * {@code MessagesController.loadMessages}, because that path mutates Telegram pagination state.</p>
 */
final class TelegramHistoryApiAdapter {
    private static final String STRATEGY = "tlrpc-getHistory-v1";
    private static final String CONTROLLER_CLASS = "org.telegram.messenger.MessagesController";
    private static final String REQUEST_CLASS = "org.telegram.tgnet.TLRPC$TL_messages_getHistory";
    private static final String DELEGATE_CLASS = "org.telegram.tgnet.RequestDelegate";
    private static final String TL_OBJECT_CLASS = "org.telegram.tgnet.TLObject";
    private static final String BUILD_VARS_CLASS = "org.telegram.messenger.BuildVars";

    private final Constructor<?> requestConstructor;
    private final Class<?> delegateClass;
    private final Method controllerFactory;
    private final Method getConnectionsManager;
    private final Method sendRequest;
    private final Method cancelRequest;
    private final boolean sendUsesFlags;
    private final boolean peerHydrationSupported;
    private final String telegramVersion;

    private TelegramHistoryApiAdapter(Constructor<?> requestConstructor,
                                      Class<?> delegateClass,
                                      Method controllerFactory,
                                      Method getConnectionsManager,
                                      Method sendRequest,
                                      Method cancelRequest,
                                      boolean sendUsesFlags,
                                      boolean peerHydrationSupported,
                                      String telegramVersion) {
        this.requestConstructor = requestConstructor;
        this.delegateClass = delegateClass;
        this.controllerFactory = controllerFactory;
        this.getConnectionsManager = getConnectionsManager;
        this.sendRequest = sendRequest;
        this.cancelRequest = cancelRequest;
        this.sendUsesFlags = sendUsesFlags;
        this.peerHydrationSupported = peerHydrationSupported;
        this.telegramVersion = telegramVersion;
    }

    static ProbeResult probe(ClassLoader classLoader) {
        String version = detectTelegramVersion(classLoader);
        try {
            Class<?> controllerClass = classLoader.loadClass(CONTROLLER_CLASS);
            Class<?> requestClass = classLoader.loadClass(REQUEST_CLASS);
            Class<?> delegateClass = classLoader.loadClass(DELEGATE_CLASS);
            Class<?> tlObjectClass = classLoader.loadClass(TL_OBJECT_CLASS);

            Method controllerFactory = requireMethod(controllerClass, "getInstance", int.class);
            if (!Modifier.isStatic(controllerFactory.getModifiers())) {
                return ProbeResult.unsupported(version, "MessagesController.getInstance(int) is not static");
            }
            requireMethod(controllerClass, "getInputPeer", long.class);
            Method getConnectionsManager = requireMethod(controllerClass, "getConnectionsManager");

            Constructor<?> requestConstructor = requestClass.getDeclaredConstructor();
            requestConstructor.setAccessible(true);
            requireField(requestClass, "peer");
            Field limitField = requireField(requestClass, "limit");
            if (limitField.getType() != int.class && limitField.getType() != Integer.class) {
                return ProbeResult.unsupported(version, "getHistory.limit is not int");
            }

            if (!delegateClass.isInterface() || findRunMethod(delegateClass) == null) {
                return ProbeResult.unsupported(version, "RequestDelegate.run(response,error) is unavailable");
            }

            Class<?> connectionsManagerClass = getConnectionsManager.getReturnType();
            Method sendRequest = findSendRequest(connectionsManagerClass, tlObjectClass, delegateClass, 2);
            boolean sendUsesFlags = false;
            if (sendRequest == null) {
                sendRequest = findSendRequest(connectionsManagerClass, tlObjectClass, delegateClass, 3);
                sendUsesFlags = sendRequest != null;
            }
            if (sendRequest == null) {
                return ProbeResult.unsupported(version, "safe sendRequest overload is unavailable");
            }

            Method cancelRequest = findMethod(connectionsManagerClass, "cancelRequest",
                    int.class, boolean.class);
            if (cancelRequest == null) {
                cancelRequest = findMethod(connectionsManagerClass, "cancelRequest", int.class);
            }

            boolean hydration = hasPeerHydrationShape(controllerClass);
            TelegramHistoryApiAdapter adapter = new TelegramHistoryApiAdapter(
                    requestConstructor,
                    delegateClass,
                    controllerFactory,
                    getConnectionsManager,
                    sendRequest,
                    cancelRequest,
                    sendUsesFlags,
                    hydration,
                    version
            );
            return ProbeResult.supported(adapter);
        } catch (Throwable throwable) {
            return ProbeResult.unsupported(version, conciseFailure(throwable));
        }
    }

    Object getController(int account) throws ReflectiveOperationException {
        return controllerFactory.invoke(null, account);
    }

    Object getConnectionsManager(Object controller) throws ReflectiveOperationException {
        return getConnectionsManager.invoke(controller);
    }

    Object newHistoryRequest(Object peer, int limit) throws ReflectiveOperationException {
        Object request = requestConstructor.newInstance();
        if (!populateHistoryRequest(request, peer, limit)) {
            throw new NoSuchFieldException("getHistory peer/limit fields rejected values");
        }
        return request;
    }

    Object newRequestDelegate(InvocationHandler handler) {
        return Proxy.newProxyInstance(delegateClass.getClassLoader(), new Class<?>[]{delegateClass}, handler);
    }

    int sendRequest(Object connectionsManager, Object request, Object delegate)
            throws ReflectiveOperationException {
        Object result = sendUsesFlags
                ? sendRequest.invoke(connectionsManager, request, delegate, 0)
                : sendRequest.invoke(connectionsManager, request, delegate);
        return Reflect.asInt(result, 0);
    }

    void cancelRequest(Object connectionsManager, int requestId) {
        if (connectionsManager == null || requestId <= 0 || cancelRequest == null) {
            return;
        }
        try {
            if (cancelRequest.getParameterCount() == 2) {
                cancelRequest.invoke(connectionsManager, requestId, true);
            } else {
                cancelRequest.invoke(connectionsManager, requestId);
            }
        } catch (ReflectiveOperationException ignored) {
            // A cancelled local delegate is still ignored even if Telegram removed cancellation.
        }
    }

    String describe() {
        return "strategy=" + STRATEGY
                + " telegram=" + telegramVersion
                + " sendParams=" + sendRequest.getParameterCount()
                + " cancelParams=" + (cancelRequest == null ? 0 : cancelRequest.getParameterCount())
                + " peerHydration=" + peerHydrationSupported;
    }

    static boolean populateHistoryRequest(Object request, Object peer, int limit) {
        if (request == null || peer == null || limit <= 0) {
            return false;
        }
        Reflect.setField(request, "peer", peer);
        Reflect.setField(request, "offset_id", 0);
        Reflect.setField(request, "offset_date", 0);
        Reflect.setField(request, "add_offset", 0);
        Reflect.setField(request, "limit", limit);
        Reflect.setField(request, "max_id", 0);
        Reflect.setField(request, "min_id", 0);
        Reflect.setField(request, "hash", 0L);
        return Reflect.field(request, "peer") == peer
                && Reflect.asInt(Reflect.field(request, "limit"), 0) == limit;
    }

    private static Method findSendRequest(Class<?> type, Class<?> tlObjectClass,
                                          Class<?> delegateClass, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!"sendRequest".equals(method.getName()) || params.length != parameterCount) {
                    continue;
                }
                if (!params[0].isAssignableFrom(tlObjectClass)
                        || !params[1].isAssignableFrom(delegateClass)) {
                    continue;
                }
                if (parameterCount == 3 && params[2] != int.class) {
                    continue;
                }
                if (method.getReturnType() != int.class && method.getReturnType() != Integer.class) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findRunMethod(Class<?> delegateClass) {
        for (Method method : delegateClass.getMethods()) {
            if ("run".equals(method.getName()) && method.getParameterCount() == 2) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasPeerHydrationShape(Class<?> controllerClass) {
        Method getChat = findMethod(controllerClass, "getChat", Long.class);
        Method getStorage = findMethod(controllerClass, "getMessagesStorage");
        Method putChat = findNamedMethod(controllerClass, "putChat", 2, boolean.class);
        if (getChat == null || getStorage == null || putChat == null) {
            return false;
        }
        return findMethod(getStorage.getReturnType(), "getChatSync", long.class) != null;
    }

    private static Method requireMethod(Class<?> type, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method method = findMethod(type, name, params);
        if (method == null) {
            throw new NoSuchMethodException(type.getName() + "." + name);
        }
        return method;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Method findNamedMethod(Class<?> type, String name, int parameterCount,
                                          Class<?> finalParameterType) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (name.equals(method.getName()) && params.length == parameterCount
                        && params[parameterCount - 1] == finalParameterType) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field requireField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static String detectTelegramVersion(ClassLoader classLoader) {
        try {
            Class<?> buildVars = classLoader.loadClass(BUILD_VARS_CLASS);
            int code = Reflect.asInt(Reflect.staticField(buildVars, "BUILD_VERSION"), 0);
            String name = Reflect.asString(Reflect.staticField(buildVars, "BUILD_VERSION_STRING"));
            if (!name.isEmpty() && code > 0) {
                return name + "(" + code + ")";
            }
            if (!name.isEmpty()) {
                return name;
            }
            return code > 0 ? String.valueOf(code) : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String conciseFailure(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    static final class ProbeResult {
        final TelegramHistoryApiAdapter adapter;
        final String telegramVersion;
        final String reason;

        private ProbeResult(TelegramHistoryApiAdapter adapter, String telegramVersion, String reason) {
            this.adapter = adapter;
            this.telegramVersion = telegramVersion;
            this.reason = reason;
        }

        static ProbeResult supported(TelegramHistoryApiAdapter adapter) {
            return new ProbeResult(adapter, adapter.telegramVersion, "");
        }

        static ProbeResult unsupported(String version, String reason) {
            return new ProbeResult(null, version, reason);
        }

        boolean isSupported() {
            return adapter != null;
        }
    }
}
