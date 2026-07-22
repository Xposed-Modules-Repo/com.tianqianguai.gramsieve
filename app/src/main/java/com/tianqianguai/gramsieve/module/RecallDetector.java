package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class RecallDetector {
    private static final String TAG = "GramSieve";
    private static final long LOCAL_DIALOG_DELETE_CLEANUP_MS = 2 * 60_000L;

    private final MessageCache messageCache;
    private final BackgroundMessageLoader loader;
    private final MediaPrefetcher mediaPrefetcher;
    private final TelegramMessageDatabaseBridge telegramDbBridge;
    private final SelfDeleteTracker selfDeleteTracker;

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader) {
        this(messageCache, loader, null);
    }

    public RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader,
                          MediaPrefetcher mediaPrefetcher) {
        this(messageCache, loader, mediaPrefetcher, new SelfDeleteTracker());
    }

    RecallDetector(MessageCache messageCache, BackgroundMessageLoader loader,
                   MediaPrefetcher mediaPrefetcher, SelfDeleteTracker selfDeleteTracker) {
        this.messageCache = messageCache;
        this.loader = loader;
        this.mediaPrefetcher = mediaPrefetcher;
        this.telegramDbBridge = new TelegramMessageDatabaseBridge();
        this.selfDeleteTracker = selfDeleteTracker;
    }

    boolean toggleCleanupMode(long dialogId, long durationMs) {
        return selfDeleteTracker != null && selfDeleteTracker.toggleCleanupMode(dialogId, durationMs);
    }

    boolean isCleanupModeActive(long dialogId) {
        return selfDeleteTracker != null && selfDeleteTracker.isCleanupModeActive(dialogId);
    }

    public void install(ClassLoader classLoader, XposedModule module) {
        ModuleLogger.hook(TAG, "RecallDetector: installing hooks...");
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            hookMethods(messagesControllerClass, module, classLoader);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to load MessagesController", throwable);
        }
        // Hook MessagesStorage to cache loaded messages
        try {
            Class<?> messagesStorageClass = classLoader.loadClass("org.telegram.messenger.MessagesStorage");
            hookStorageMethods(messagesStorageClass, module);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to load MessagesStorage", throwable);
        }
        try {
            hookNotificationDeletion(classLoader, module);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook notification deletion", throwable);
        }
        try {
            hookNotificationCenterDeletion(classLoader, module);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook NotificationCenter deletion", throwable);
        }
        ModuleLogger.hook(TAG, "RecallDetector: hooks installation complete; routine update tracing suppressed");
    }

    private void hookMethods(Class<?> messagesControllerClass, XposedModule module, ClassLoader classLoader) {
        for (java.lang.reflect.Method m : messagesControllerClass.getDeclaredMethods()) {
            String name = m.getName();
            if (name.equals("processUpdateArray")) {
                ModuleLogger.hook(TAG, "RecallDetector: found processUpdateArray with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "processUpdateArray");
            } else if (name.equals("deleteMessages")) {
                ModuleLogger.hook(TAG, "RecallDetector: found deleteMessages with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "deleteMessages");
            } else if (name.equals("deleteDialog")) {
                ModuleLogger.hook(TAG, "RecallDetector: found deleteDialog with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "deleteDialog");
            } else if (name.equals("deleteParticipantFromChat")) {
                ModuleLogger.hook(TAG, "RecallDetector: found deleteParticipantFromChat with "
                        + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "deleteParticipantFromChat");
            } else if (name.equals("blockPeer")) {
                ModuleLogger.hook(TAG, "RecallDetector: found blockPeer with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "blockPeer");
            } else if (name.equals("editMessage")) {
                ModuleLogger.hook(TAG, "RecallDetector: found editMessage with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "editMessage");
            } else if (name.contains("processLoadedMessages") || name.contains("LoadedMessages")) {
                ModuleLogger.hook(TAG, "RecallDetector: found " + name + " with " + m.getParameterCount() + " params");
                hookSingleMethod(module, m, "processLoadedMessages");
            }
        }
    }

    private void hookStorageMethods(Class<?> messagesStorageClass, XposedModule module) {
        ModuleLogger.hook(TAG, "RecallDetector: scanning MessagesStorage methods...");
        for (java.lang.reflect.Method m : messagesStorageClass.getDeclaredMethods()) {
            String name = m.getName();
            // Hook methods that store or update messages
            if (name.equals("putMessages") || name.equals("putMessagesInternal")
                    || name.equals("replaceMessageIfExists")) {
                ModuleLogger.hook(TAG, "RecallDetector: found storage." + name + " with " + m.getParameterCount() + " params");
                hookStoragePutMessages(module, m, name);
            } else if (name.equals("markMessagesAsDeleted") || name.equals("markMessagesAsDeletedInternal")) {
                ModuleLogger.hook(TAG, "RecallDetector: found storage." + name + " with " + m.getParameterCount() + " params");
                hookStorageDeleteMessages(module, m, name);
            }
        }
    }

    private void hookStoragePutMessages(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    telegramDbBridge.captureEditsBeforePutMessages(
                            chain.getThisObject(),
                            chain.getArgs(),
                            messageCache,
                            loader,
                            mediaPrefetcher
                    );
                    cacheMessagesFromStorageArgs(chain.getArgs());
                } catch (Throwable t) {
                    // Ignore
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hook storage." + methodName + " success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook storage." + methodName, throwable);
        }
    }

    private void hookStorageDeleteMessages(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    DeletionRequest deletion = deletionFromStorageArgs(chain.getArgs());
                    if (shouldAllowUserDeletion(deletion)) {
                        purgeDeletedMessages(deletion.dialogId, deletion.messageIds);
                        ModuleLogger.hook(TAG, "RecallDetector: allowed storage." + methodName
                                + " for user delete dialogId=" + deletion.dialogId
                                + " count=" + deletion.messageIds.size());
                        return chain.proceed();
                    }
                    if (shouldBlockDeletion(deletion)) {
                        processDeletions(deletion.dialogId, deletion.messageIds);
                        telegramDbBridge.markMessagesDeletedAsync(chain.getThisObject(),
                                deletion.dialogId, deletion.messageIds);
                        ModuleLogger.hook(TAG, "RecallDetector: blocked storage." + methodName
                                + " dialogId=" + deletion.dialogId
                                + " count=" + deletion.messageIds.size());
                        return skipResult(method.getReturnType());
                    }
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG,
                            "storage." + methodName + " anti-delete failed", throwable);
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hook storage." + methodName + " success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook storage." + methodName, throwable);
        }
    }

    private void hookNotificationDeletion(ClassLoader classLoader, XposedModule module) throws ClassNotFoundException {
        Class<?> notificationsControllerClass = classLoader.loadClass("org.telegram.messenger.NotificationsController");
        boolean hooked = false;
        for (java.lang.reflect.Method method : notificationsControllerClass.getDeclaredMethods()) {
            if (!"removeDeletedMessagesFromNotifications".equals(method.getName())) {
                continue;
            }
            hook(module, method, chain -> {
                try {
                    if (shouldSuppressDeletionNotification(chain.getArgs())) {
                        ModuleLogger.hook(TAG, "RecallDetector: blocked removeDeletedMessagesFromNotifications");
                        return skipResult(method.getReturnType());
                    }
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG,
                            "notification deletion suppress failed", throwable);
                }
                return chain.proceed();
            });
            hooked = true;
            ModuleLogger.hook(TAG, "RecallDetector: hook NotificationsController."
                    + method.getName() + " params=" + method.getParameterCount() + " success");
        }
        if (!hooked) {
            ModuleLogger.hook(TAG, "RecallDetector: no removeDeletedMessagesFromNotifications hook point");
        }
    }

    private boolean shouldSuppressDeletionNotification(java.util.List<Object> args) {
        if (loader == null || args == null) {
            return false;
        }
        for (Object arg : args) {
            if (containsEnabledDialogKey(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEnabledDialogKey(Object sparse) {
        if (sparse == null) {
            return false;
        }
        Object sizeObject = Reflect.invokeIfExists(sparse, "size", new Class<?>[0]);
        if (!(sizeObject instanceof Number)) {
            return false;
        }
        int size = ((Number) sizeObject).intValue();
        for (int i = 0; i < size; i++) {
            long key = Reflect.asLong(Reflect.invokeIfExists(sparse, "keyAt",
                    new Class<?>[]{int.class}, i), 0L);
            if (key != 0L && loader.isChatEnabled(key)) {
                List<?> messageIds = deletionIdsFromSparseValue(Reflect.invokeIfExists(sparse, "valueAt",
                        new Class<?>[]{int.class}, i));
                if (!messageIds.isEmpty() && shouldAllowUserDeletion(key, messageIds)) {
                    purgeDeletedMessages(key, messageIds);
                    continue;
                }
                if (selfDeleteTracker != null && selfDeleteTracker.isCleanupModeActive(key)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private List<?> deletionIdsFromSparseValue(Object value) {
        if (value instanceof List<?>) {
            return (List<?>) value;
        }
        if (value instanceof Integer) {
            return Collections.singletonList(value);
        }
        return Collections.emptyList();
    }

    private void hookNotificationCenterDeletion(ClassLoader classLoader, XposedModule module) throws ClassNotFoundException {
        Class<?> notificationCenterClass = classLoader.loadClass("org.telegram.messenger.NotificationCenter");
        int messagesDeletedId = Reflect.asInt(Reflect.staticField(notificationCenterClass, "messagesDeleted"), -1);
        if (messagesDeletedId < 0) {
            ModuleLogger.hook(TAG, "RecallDetector: NotificationCenter.messagesDeleted unavailable");
            return;
        }
        int hooked = 0;
        for (java.lang.reflect.Method method : notificationCenterClass.getDeclaredMethods()) {
            if (!method.getName().startsWith("postNotificationName") || method.getParameterCount() < 2) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[0] != int.class
                    || parameterTypes[parameterTypes.length - 1] != Object[].class) {
                continue;
            }
            hook(module, method, chain -> {
                try {
                    java.util.List<Object> args = chain.getArgs();
                    int id = args == null || args.isEmpty() ? -1 : Reflect.asInt(args.get(0), -1);
                    if (id == messagesDeletedId && shouldSuppressMessagesDeletedEvent(args)) {
                        ModuleLogger.hook(TAG, "RecallDetector: blocked NotificationCenter.messagesDeleted");
                        return skipResult(method.getReturnType());
                    }
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG,
                            "NotificationCenter deletion suppress failed", throwable);
                }
                return chain.proceed();
            });
            hooked++;
        }
        ModuleLogger.hook(TAG, "RecallDetector: NotificationCenter messagesDeleted hook count=" + hooked);
    }

    boolean shouldSuppressMessagesDeletedEvent(java.util.List<Object> hookArgs) {
        if (hookArgs == null) {
            return false;
        }
        Object[] eventArgs = null;
        for (Object hookArg : hookArgs) {
            if (hookArg instanceof Object[]) {
                eventArgs = (Object[]) hookArg;
                break;
            }
        }
        if (eventArgs == null) {
            return false;
        }
        ArrayList<?> messageIds = null;
        long dialogId = 0L;
        for (Object eventArg : eventArgs) {
            if (eventArg instanceof ArrayList<?> && messageIds == null) {
                messageIds = (ArrayList<?>) eventArg;
            } else if (eventArg instanceof Long && dialogId == 0L) {
                dialogId = (Long) eventArg;
            }
        }
        long selfDeleteDialogId = dialogId;
        if (dialogId == 0L && selfDeleteTracker != null) {
            selfDeleteDialogId = selfDeleteTracker.inferUniqueDialog(messageIds);
        }
        if (selfDeleteDialogId != 0L && shouldAllowUserDeletion(selfDeleteDialogId, messageIds)) {
            purgeDeletedMessages(selfDeleteDialogId, messageIds);
            return false;
        }
        return messageIds != null && isDeletionEnabled(dialogId, messageIds);
    }

    private DeletionRequest deletionFromStorageArgs(java.util.List<Object> args) {
        if (args == null) {
            return DeletionRequest.EMPTY;
        }
        long dialogId = 0L;
        ArrayList<Integer> messageIds = new ArrayList<>();
        boolean sawDialogId = false;
        for (Object arg : args) {
            if (arg instanceof Long && !sawDialogId) {
                dialogId = (Long) arg;
                sawDialogId = true;
                continue;
            }
            if (arg instanceof ArrayList<?>) {
                for (Object id : (ArrayList<?>) arg) {
                    int messageId = Reflect.asInt(id, 0);
                    if (messageId > 0) {
                        messageIds.add(messageId);
                    }
                }
            } else if (arg instanceof Integer && sawDialogId && messageIds.isEmpty()) {
                int messageId = (Integer) arg;
                if (messageId > 0) {
                    messageIds.add(messageId);
                }
            }
        }
        return new DeletionRequest(dialogId, messageIds);
    }

    private DeletionRequest deletionFromControllerArgs(java.util.List<Object> args) {
        if (args == null) {
            return DeletionRequest.EMPTY;
        }
        long dialogId = 0L;
        ArrayList<Integer> messageIds = new ArrayList<>();
        boolean sawMessageIds = false;
        boolean sawDialogId = false;
        for (Object arg : args) {
            if (arg instanceof ArrayList<?> && !sawMessageIds) {
                sawMessageIds = true;
                for (Object id : (ArrayList<?>) arg) {
                    if (id instanceof Integer && (Integer) id > 0) {
                        messageIds.add((Integer) id);
                    }
                }
            } else if (arg instanceof Long && !sawDialogId) {
                dialogId = (Long) arg;
                sawDialogId = true;
            }
        }
        return new DeletionRequest(dialogId, messageIds);
    }

    private boolean shouldBlockDeletion(DeletionRequest deletion) {
        return deletion != null
                && !deletion.messageIds.isEmpty()
                && !shouldAllowUserDeletion(deletion)
                && isDeletionEnabled(deletion.dialogId, deletion.messageIds);
    }

    private boolean shouldAllowUserDeletion(DeletionRequest deletion) {
        return deletion != null && shouldAllowUserDeletion(deletion.dialogId, deletion.messageIds);
    }

    private boolean shouldAllowUserDeletion(long dialogId, List<?> messageIds) {
        return selfDeleteTracker != null && selfDeleteTracker.allowsAll(dialogId, messageIds);
    }

    private boolean hasUserDeletionIntent(long dialogId, List<?> messageIds) {
        return selfDeleteTracker != null && selfDeleteTracker.allowsAny(dialogId, messageIds);
    }

    private Object skipResult(Class<?> returnType) {
        if (returnType == null || returnType == void.class || returnType == Void.TYPE) {
            return null;
        }
        if (ArrayList.class.isAssignableFrom(returnType)) {
            return new ArrayList<>();
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return 0;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        return null;
    }

    private void cacheMessagesFromStorageArgs(java.util.List<Object> args) {
        if (args == null || messageCache == null || loader == null) return;
        // Look for ArrayList of MessageObject or TLRPC.Message
        for (Object arg : args) {
            if (arg instanceof ArrayList) {
                ArrayList<?> list = (ArrayList<?>) arg;
                if (!list.isEmpty()) {
                    Object first = list.get(0);
                    if (first != null) {
                        String className = first.getClass().getSimpleName();
                        if (className.contains("Message")) {
                            cacheMessageList(list);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void cacheMessageList(ArrayList<?> messages) {
        int cached = 0;
        for (Object msgObj : messages) {
            try {
                if (msgObj == null) continue;
                Object messageOwner = messageOwner(msgObj);
                // Get dialogId from peer_id
                Object peerId = Reflect.field(messageOwner, "peer_id");
                long dialogId = 0L;
                if (peerId != null) {
                    long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                    long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                    long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                    if (channelId != 0L) dialogId = -channelId;
                    else if (chatId != 0L) dialogId = -chatId;
                    else if (userId != 0L) dialogId = userId;
                }
                if (dialogId == 0L || !loader.isChatEnabled(dialogId)) continue;

                int msgId = Reflect.asInt(Reflect.field(messageOwner, "id"), 0);
                if (msgId == 0) continue;

                String text = Reflect.asString(Reflect.field(messageOwner, "message"));
                String caption = "";
                String mediaType = null;
                String mediaId = null;
                Object media = Reflect.field(messageOwner, "media");
                if (media != null) {
                    String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                    if (mediaCaption != null && !mediaCaption.isEmpty()) {
                        caption = mediaCaption;
                    }
                    mediaType = media.getClass().getSimpleName();
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                            }
                        }
                    }
                }
                String fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
                MessageCache.CachedMessage existing = messageCache.get(dialogId, msgId);
                if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                    messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
                    messageCache.putMediaObject(dialogId, msgId, media);
                    prefetchMedia(dialogId, msgId, msgObj, media);
                    cached++;
                }
            } catch (Throwable t) {
                // Skip individual message errors
            }
        }
    }

    private void hookSingleMethod(XposedModule module, java.lang.reflect.Method method, String methodName) {
        try {
            hook(module, method, chain -> {
                try {
                    java.util.List<Object> args = chain.getArgs();
                    if (methodName.equals("processUpdateArray")) {
                        processUpdatesFromArgs(chain.getThisObject(), args);
                        return chain.proceed();
                    }
                    if (methodName.equals("deleteMessages")) {
                        processDeletionsFromArgs(chain.getThisObject(), args);
                        return chain.proceed();
                    }
                    if (methodName.equals("deleteDialog") || methodName.equals("blockPeer")) {
                        handleControllerDialogAction(methodName, args);
                        return chain.proceed();
                    }
                    if (methodName.equals("deleteParticipantFromChat")) {
                        ModuleLogger.hook(TAG, "RecallDetector: controller deleteParticipantFromChat "
                                + summarizeArgs(args));
                        return chain.proceed();
                    }
                    Object result = chain.proceed();
                    if (methodName.equals("editMessage")) {
                        processEditFromArgs(args);
                    } else if (methodName.equals("processLoadedMessages")) {
                        processLoadedMessagesFromArgs(args);
                    }
                    return result;
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, methodName + " hook failed", throwable);
                    return chain.proceed();
                }
            });
            ModuleLogger.hook(TAG, "RecallDetector: hook " + methodName + " success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook " + methodName, throwable);
        }
    }

    private void processUpdatesFromArgs(Object messagesController, java.util.List<Object> args) {
        if (args == null || args.isEmpty()) return;
        for (Object arg : args) {
            if (arg instanceof ArrayList) {
                processUpdates(messagesController, (ArrayList<?>) arg);
                return;
            }
        }
    }

    boolean processDeletionsFromArgs(Object messagesController, java.util.List<Object> args) {
        DeletionRequest deletion = deletionFromControllerArgs(args);
        if (deletion.dialogId != 0L && !deletion.messageIds.isEmpty()) {
            if (selfDeleteTracker != null) {
                selfDeleteTracker.recordUserDelete(deletion.dialogId, deletion.messageIds);
            }
            int removed = purgeDeletedMessages(deletion.dialogId, deletion.messageIds);
            ModuleLogger.hook(TAG, "RecallDetector: allowing local deleteMessages dialogId="
                    + deletion.dialogId + " count=" + deletion.messageIds.size()
                    + " purged=" + removed);
            return true;
        }
        return false;
    }

    private String summarizeArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "args=[]";
        }
        StringBuilder builder = new StringBuilder("args=[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Object arg = args.get(i);
            if (arg == null) {
                builder.append("null");
            } else if (arg instanceof Number || arg instanceof Boolean) {
                builder.append(arg.getClass().getSimpleName()).append('=').append(arg);
            } else if (arg instanceof CharSequence) {
                builder.append("String(len=").append(((CharSequence) arg).length()).append(')');
            } else if (arg instanceof List<?>) {
                builder.append(arg.getClass().getSimpleName())
                        .append("(size=").append(((List<?>) arg).size()).append(')');
            } else {
                builder.append(arg.getClass().getName());
            }
        }
        return builder.append(']').toString();
    }

    boolean processDialogDeletionFromArgs(java.util.List<Object> args) {
        DialogDeletionRequest deletion = dialogDeletionFromArgs(args);
        return processDialogDeletion(deletion.dialogId, deletion.mode, deletion.revoke, "deleteDialog");
    }

    boolean handleControllerDialogAction(String methodName, java.util.List<Object> args) {
        if ("deleteDialog".equals(methodName)) {
            boolean handled = processDialogDeletionFromArgs(args);
            if (!handled) {
                ModuleLogger.hook(TAG, "RecallDetector: deleteDialog args ignored " + summarizeArgs(args));
            }
            return handled;
        }
        if ("blockPeer".equals(methodName)) {
            ModuleLogger.hook(TAG, "RecallDetector: controller blockPeer " + summarizeArgs(args));
        }
        return false;
    }

    boolean processDialogDeletion(long dialogId, int mode, boolean revoke, String source) {
        if (dialogId == 0L) {
            return false;
        }
        if (selfDeleteTracker != null) {
            selfDeleteTracker.enableCleanupMode(dialogId, LOCAL_DIALOG_DELETE_CLEANUP_MS);
        }
        if (loader != null) {
            loader.disableChat(dialogId);
        }
        if (messageCache != null) {
            messageCache.removeDialog(dialogId);
        }
        String sourceName = source == null ? "deleteDialog" : source;
        ModuleLogger.hook(TAG, "RecallDetector: allowing local " + sourceName + " dialogId="
                + dialogId + " mode=" + mode + " revoke=" + revoke);
        return true;
    }

    private DialogDeletionRequest dialogDeletionFromArgs(java.util.List<Object> args) {
        if (args == null) {
            return DialogDeletionRequest.EMPTY;
        }
        long dialogId = 0L;
        int mode = 0;
        boolean sawMode = false;
        boolean revoke = false;
        for (Object arg : args) {
            if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            } else if (arg instanceof Integer && !sawMode) {
                mode = (Integer) arg;
                sawMode = true;
            } else if (arg instanceof Boolean) {
                revoke = (Boolean) arg;
            }
        }
        return new DialogDeletionRequest(dialogId, mode, revoke);
    }

    private void processEditFromArgs(java.util.List<Object> args) {
        if (args == null || args.size() < 3) return;
        long dialogId = 0L;
        int messageId = 0;
        String newText = null;
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            } else if (arg instanceof Integer && messageId == 0) {
                messageId = (Integer) arg;
            } else if (arg instanceof String && newText == null) {
                newText = (String) arg;
            }
        }
        if (dialogId != 0L && messageId != 0 && newText != null) {
            processEdit(dialogId, messageId, newText);
        }
    }

    private void processLoadedMessagesFromArgs(java.util.List<Object> args) {
        if (args == null || args.isEmpty()) return;
        // Look for ArrayList of messages and a dialogId
        ArrayList<?> messages = null;
        long dialogId = 0L;
        for (Object arg : args) {
            if (arg instanceof ArrayList && messages == null) {
                messages = (ArrayList<?>) arg;
            } else if (arg instanceof Long && dialogId == 0L) {
                dialogId = (Long) arg;
            }
        }
        if (messages != null && dialogId != 0L && loader.isChatEnabled(dialogId)) {
            cacheLoadedMessages(dialogId, messages);
        }
    }

    private void cacheLoadedMessages(long dialogId, ArrayList<?> messages) {
        if (messageCache == null) return;
        int cached = 0;
        for (Object msgObj : messages) {
            try {
                if (msgObj == null) continue;
                Object messageOwner = messageOwner(msgObj);
                int msgId = Reflect.asInt(Reflect.field(messageOwner, "id"), 0);
                if (msgId == 0) continue;

                String text = Reflect.asString(Reflect.field(messageOwner, "message"));
                String caption = "";
                String mediaType = null;
                String mediaId = null;
                Object media = Reflect.field(messageOwner, "media");
                if (media != null) {
                    String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                    if (mediaCaption != null && !mediaCaption.isEmpty()) {
                        caption = mediaCaption;
                    }
                    mediaType = media.getClass().getSimpleName();
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                            }
                        }
                    }
                }
                String fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
                // Only cache if there's content
                if (!fullContent.isEmpty() || mediaId != null) {
                    MessageCache.CachedMessage existing = messageCache.get(dialogId, msgId);
                    if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                        messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
                        messageCache.putMediaObject(dialogId, msgId, media);
                        prefetchMedia(dialogId, msgId, msgObj, media);
                        cached++;
                    }
                }
            } catch (Throwable t) {
                // Skip individual message errors
            }
        }
    }

    void cacheBackgroundMessages(long dialogId, List<?> messages) {
        if (messages == null || messages.isEmpty() || !loader.isChatEnabled(dialogId)) {
            return;
        }
        cacheLoadedMessages(dialogId, new ArrayList<>(messages));
    }

    private static void hook(XposedModule module, Method method, XposedInterface.Hooker hooker) {
        module.hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(hooker);
    }

    private void hookProcessUpdateArray(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method processUpdateArray = Reflect.method(messagesControllerClass, "processUpdateArray", ArrayList.class);
            hook(module, processUpdateArray, chain -> {
                Object result = chain.proceed();
                try {
                    ArrayList<?> updates = (ArrayList<?>) chain.getArg(0);
                    processUpdates(updates);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processUpdates failed", throwable);
                }
                return result;
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookProcessUpdateArray success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook processUpdateArray", throwable);
        }
    }

    private void hookDeleteMessages(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method deleteMessages = Reflect.method(messagesControllerClass, "deleteMessages",
                    ArrayList.class, ArrayList.class, ArrayList.class, long.class, int.class, boolean.class);
            hook(module, deleteMessages, chain -> {
                try {
                    processDeletionsFromArgs(chain.getThisObject(), chain.getArgs());
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processDeletionsFromArgs failed", throwable);
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookDeleteMessages success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook deleteMessages", throwable);
        }
    }

    private void hookEditMessage(ClassLoader classLoader, XposedModule module) {
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Method editMessage = Reflect.method(messagesControllerClass, "editMessage",
                    long.class, int.class, String.class, boolean.class, ArrayList.class, boolean.class, boolean.class);
            hook(module, editMessage, chain -> {
                try {
                    long dialogId = (long) chain.getArg(0);
                    int messageId = (int) chain.getArg(1);
                    String newText = (String) chain.getArg(2);
                    ModuleLogger.hook(TAG, "RecallDetector: editMessage called dialogId=" + dialogId + " msgId=" + messageId);
                    processEdit(dialogId, messageId, newText);
                } catch (Throwable throwable) {
                    ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "processEdit failed", throwable);
                }
                return chain.proceed();
            });
            ModuleLogger.hook(TAG, "RecallDetector: hookEditMessage success");
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to hook editMessage", throwable);
        }
    }

    void processUpdates(ArrayList<?> updates) {
        processUpdates(null, updates);
    }

    private void processUpdates(Object messagesController, ArrayList<?> updates) {
        if (messageCache == null || loader == null || updates == null) {
            return;
        }
        for (Object update : updates) {
            try {
                if (update == null) continue;

                String className = update.getClass().getSimpleName();

                Object message = Reflect.field(update, "message");
                if (message != null) {
                    if (className.contains("Edit")) {
                        // This is an edit update — but Telegram also sends TL_updateEditChannelMessage
                        // for non-content changes (reactions, views, etc.). Only mark as edited
                        // if the message text actually changed.
                        if (isRealEdit(message)) {
                            processEditedMessage(message);
                        } else {
                            // Not a real edit (e.g. reaction added) — refresh cache
                            processMessageFromUpdate(message);
                        }
                    } else {
                        // This is a new message
                        processMessageFromUpdate(message);
                    }
                    continue;
                }
                
                // Check for delete_messages field (TLRPC.TL_updateDeleteMessages, TL_updateDeleteChannelMessages)
                Object deleteMessages = Reflect.field(update, "messages");
                Object channelId = Reflect.field(update, "channel_id");
                if (className.contains("Delete") && deleteMessages instanceof ArrayList) {
                    long dialogId = 0L;
                    long channelIdValue = Reflect.asLong(channelId, 0L);
                    if (channelIdValue != 0L) {
                        dialogId = -channelIdValue;
                    }
                    ArrayList<?> msgIds = (ArrayList<?>) deleteMessages;
                    if (shouldAllowUserDeletion(dialogId, msgIds)) {
                        int removed = purgeDeletedMessages(dialogId, msgIds);
                        ModuleLogger.hook(TAG, "RecallDetector: allowed delete update for user delete dialogId="
                                + dialogId + " count=" + msgIds.size() + " purged=" + removed);
                    } else if (isDeletionEnabled(dialogId, msgIds)) {
                        int count = msgIds.size();
                        processDeletions(dialogId, msgIds);
                        Object storage = resolveMessagesStorage(messagesController);
                        telegramDbBridge.markMessagesDeletedAsync(storage, dialogId, msgIds);
                        if (count > 0) {
                            msgIds.clear();
                            ModuleLogger.hook(TAG, "RecallDetector: cleared delete update ids before Telegram dialogId="
                                    + dialogId + " count=" + count);
                        }
                    }
                }
            } catch (Throwable throwable) {
                ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process update entry", throwable);
            }
        }
    }

    private void processMessageFromUpdate(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) return;

            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }

            boolean enabled = loader.isChatEnabled(dialogId);
            if (!enabled) {
                return;
            }

            String text = Reflect.asString(Reflect.field(message, "message"));
            String caption = "";
            String mediaType = null;
            String mediaId = null;
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    caption = mediaCaption;
                }
                mediaType = media.getClass().getSimpleName();
                Object photo = Reflect.field(media, "photo");
                if (photo != null) {
                    mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                } else {
                    Object video = Reflect.field(media, "video");
                    if (video != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                    } else {
                        Object document = Reflect.field(media, "document");
                        if (document != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                        }
                    }
                }
            }

            String fullContent = text != null ? text : "";
            if (!caption.isEmpty()) {
                fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
            }

            // Fresh update from Telegram — this message is NOT edited/recalled at this point
            // Reset any stale flags from prior sessions
            messageCache.putFresh(dialogId, msgId, fullContent, caption, 0L, mediaType, mediaId);
            messageCache.putMediaObject(dialogId, msgId, media);
            prefetchMedia(dialogId, msgId, message, media);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process message", throwable);
        }
    }

    private Object messageOwner(Object messageLike) {
        Object owner = Reflect.field(messageLike, "messageOwner");
        return owner != null ? owner : messageLike;
    }

    private void prefetchMedia(long dialogId, long messageId, Object messageOwner, Object media) {
        if (mediaPrefetcher == null || media == null) {
            return;
        }
        mediaPrefetcher.prefetchFromMessage(dialogId, messageId, messageOwner, media);
    }

    private boolean isRealEdit(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) {
                return false;
            }

            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }
            if (dialogId == 0L) {
                return false;
            }

            MessageCache.CachedMessage cached = messageCache.get(dialogId, msgId);
            if (cached == null || cached.text == null) {
                return true;
            }

            String newText = Reflect.asString(Reflect.field(message, "message"));
            String newCaption = "";
            String newMediaType = null;
            String newMediaId = null;
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    newCaption = mediaCaption;
                }
                newMediaType = media.getClass().getSimpleName();
                Object photo = Reflect.field(media, "photo");
                if (photo != null) {
                    newMediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                } else {
                    Object video = Reflect.field(media, "video");
                    if (video != null) {
                        newMediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                    } else {
                        Object document = Reflect.field(media, "document");
                        if (document != null) {
                            newMediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                        }
                    }
                }
            }
            String newContent = newText != null ? newText : "";
            if (!newCaption.isEmpty()) {
                newContent = newContent.isEmpty() ? newCaption : newContent + "\n" + newCaption;
            }

            // Build fingerprints for comparison
            String cachedFingerprint = (cached.text != null ? cached.text : "") + "|"
                    + (cached.mediaType != null ? cached.mediaType : "") + "|"
                    + (cached.mediaId != null ? cached.mediaId : "");
            String newFingerprint = newContent + "|"
                    + (newMediaType != null ? newMediaType : "") + "|"
                    + (newMediaId != null ? newMediaId : "");

            return !cachedFingerprint.equals(newFingerprint);
        } catch (Throwable t) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "isRealEdit failed", t);
            return false;
        }
    }

    private void processEditedMessage(Object message) {
        try {
            int msgId = Reflect.asInt(Reflect.field(message, "id"), 0);
            if (msgId == 0) return;
            
            Object peerId = Reflect.field(message, "peer_id");
            long dialogId = 0L;
            if (peerId != null) {
                long userId = Reflect.asLong(Reflect.field(peerId, "user_id"), 0L);
                long chatId = Reflect.asLong(Reflect.field(peerId, "chat_id"), 0L);
                long channelId = Reflect.asLong(Reflect.field(peerId, "channel_id"), 0L);
                if (userId != 0L) dialogId = userId;
                else if (chatId != 0L) dialogId = -chatId;
                else if (channelId != 0L) dialogId = -channelId;
            }
            
            if (!loader.isChatEnabled(dialogId)) {
                return;
            }
            
            // Get the new content
            String newText = Reflect.asString(Reflect.field(message, "message"));
            String newCaption = "";
            
            // Check for media caption
            Object media = Reflect.field(message, "media");
            if (media != null) {
                String mediaCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (mediaCaption != null && !mediaCaption.isEmpty()) {
                    newCaption = mediaCaption;
                }
            }
            
            // Combine text and caption
            String newContent = newText;
            if (!newCaption.isEmpty()) {
                if (!newContent.isEmpty()) {
                    newContent += "\n" + newCaption;
                } else {
                    newContent = newCaption;
                }
            }
            
            // Mark as edited with new content
            messageCache.markEdited(dialogId, msgId, newContent);
            ModuleLogger.hook(TAG, "RecallDetector: marked edited dialogId=" + dialogId + " msgId=" + msgId);
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to process edited message", throwable);
        }
    }

    void processDeletions(long dialogId, ArrayList<?> messageIds) {
        if (messageCache == null || loader == null || messageIds == null) {
            return;
        }
        boolean enabled = isDeletionEnabled(dialogId, messageIds);
        ModuleLogger.hook(TAG, "RecallDetector: processDeletions dialogId=" + dialogId
                + " count=" + messageIds.size() + " enabled=" + enabled);
        if (!enabled) {
            return;
        }
        if (shouldAllowUserDeletion(dialogId, messageIds)) {
            int removed = purgeDeletedMessages(dialogId, messageIds);
            ModuleLogger.hook(TAG, "RecallDetector: purged user-deleted messages dialogId="
                    + dialogId + " count=" + messageIds.size() + " purged=" + removed);
            return;
        }
        if (dialogId != 0L) {
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0) {
                    messageCache.markRecalled(dialogId, messageId);
                    ModuleLogger.hook(TAG, "RecallDetector: marked recalled dialogId=" + dialogId + " msgId=" + messageId);
                }
            }
            return;
        }
        for (long enabledDialogId : loader.enabledChatIdsSnapshot()) {
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0 && messageCache.get(enabledDialogId, messageId) != null) {
                    messageCache.markRecalled(enabledDialogId, messageId);
                    ModuleLogger.hook(TAG, "RecallDetector: marked recalled dialogId="
                            + enabledDialogId + " msgId=" + messageId + " via global delete update");
                }
            }
        }
    }

    private int purgeDeletedMessages(long dialogId, List<?> messageIds) {
        if (messageCache == null || messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        if (dialogId != 0L) {
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0 && purgeDeletedMessage(dialogId, messageId)) {
                    removed++;
                }
            }
            return removed;
        }
        for (long enabledDialogId : loader.enabledChatIdsSnapshot()) {
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0
                        && hasUserDeletionIntent(enabledDialogId, Collections.singletonList(messageId))
                        && purgeDeletedMessage(enabledDialogId, messageId)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private boolean purgeDeletedMessage(long dialogId, int messageId) {
        MessageCache.CachedMessage removed = messageCache.remove(dialogId, messageId);
        deleteCachedMediaFile(removed);
        return removed != null;
    }

    private void deleteCachedMediaFile(MessageCache.CachedMessage message) {
        if (message == null || message.cachedMediaPath == null || message.cachedMediaPath.isEmpty()) {
            return;
        }
        try {
            File file = new File(message.cachedMediaPath);
            if (file.exists() && !file.delete()) {
                ModuleLogger.hook(TAG, "RecallDetector: cached media delete failed path=" + message.cachedMediaPath);
            }
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to delete cached media file", throwable);
        }
    }

    private boolean isDeletionEnabled(long dialogId, java.util.List<?> messageIds) {
        if (loader == null || messageIds == null || messageIds.isEmpty()) {
            return false;
        }
        if (dialogId != 0L) {
            return loader.isChatEnabled(dialogId);
        }
        for (long enabledDialogId : loader.enabledChatIdsSnapshot()) {
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0 && messageCache != null && messageCache.get(enabledDialogId, messageId) != null) {
                    return true;
                }
            }
        }
        for (long enabledDialogId : loader.enabledChatIdsSnapshot()) {
            if (enabledDialogId > 0L) {
                return true;
            }
        }
        return false;
    }

    private Object resolveMessagesStorage(Object messagesController) {
        if (messagesController == null) {
            return null;
        }
        Object storage = Reflect.invokeIfExists(messagesController, "getMessagesStorage", new Class<?>[0]);
        if (storage != null) {
            return storage;
        }
        return Reflect.field(messagesController, "messagesStorage");
    }

    void processEdit(long dialogId, int messageId, String newText) {
        if (messageCache == null || loader == null) {
            return;
        }
        ModuleLogger.hook(TAG, "RecallDetector: processEdit dialogId=" + dialogId + " msgId=" + messageId + " enabled=" + loader.isChatEnabled(dialogId));
        if (!loader.isChatEnabled(dialogId)) {
            return;
        }
        messageCache.markEdited(dialogId, messageId, newText);
        ModuleLogger.hook(TAG, "RecallDetector: marked edited dialogId=" + dialogId + " msgId=" + messageId);
    }

    private long resolveSenderId(Object message) {
        try {
            Object fromId = Reflect.field(message, "from_id");
            if (fromId == null) {
                Object messageOwner = Reflect.field(message, "messageOwner");
                if (messageOwner != null) {
                    fromId = Reflect.field(messageOwner, "from_id");
                }
            }
            if (fromId != null) {
                long userId = Reflect.asLong(Reflect.field(fromId, "user_id"), 0L);
                if (userId != 0L) {
                    return userId;
                }
                long chatId = Reflect.asLong(Reflect.field(fromId, "chat_id"), 0L);
                if (chatId != 0L) {
                    return -chatId;
                }
            }
        } catch (Throwable throwable) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "Failed to resolve sender id", throwable);
        }
        return 0L;
    }

    private static final class DeletionRequest {
        static final DeletionRequest EMPTY = new DeletionRequest(0L, new ArrayList<>());

        final long dialogId;
        final ArrayList<Integer> messageIds;

        DeletionRequest(long dialogId, ArrayList<Integer> messageIds) {
            this.dialogId = dialogId;
            this.messageIds = messageIds;
        }
    }

    private static final class DialogDeletionRequest {
        static final DialogDeletionRequest EMPTY = new DialogDeletionRequest(0L, 0, false);

        final long dialogId;
        final int mode;
        final boolean revoke;

        DialogDeletionRequest(long dialogId, int mode, boolean revoke) {
            this.dialogId = dialogId;
            this.mode = mode;
            this.revoke = revoke;
        }
    }
}
