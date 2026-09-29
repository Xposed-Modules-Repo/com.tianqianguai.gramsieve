package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts Telegram's own media download path for cached messages and mirrors the
 * finished file into GramSieve's private cache.
 */
public final class MediaPrefetcher {
    private static final String TAG = "MediaPrefetcher";
    private static final int DOWNLOAD_PRIORITY = 3;
    private static final int DOWNLOAD_TYPE = 0;
    private static final int MAX_POLL_ATTEMPTS = 20;
    private static final long POLL_DELAY_MS = 1500L;

    private final MessageCache messageCache;
    private final MediaCache mediaCache;
    private final ScheduledExecutorService scheduler;
    private final Set<String> pendingKeys;
    private volatile ClassLoader telegramClassLoader;

    public MediaPrefetcher(MessageCache messageCache, MediaCache mediaCache) {
        this.messageCache = messageCache;
        this.mediaCache = mediaCache;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "GramSieve-MediaPrefetcher");
            thread.setDaemon(true);
            return thread;
        });
        this.pendingKeys = ConcurrentHashMap.newKeySet();
    }

    public void setTelegramClassLoader(ClassLoader telegramClassLoader) {
        this.telegramClassLoader = telegramClassLoader;
    }

    public void prefetchFromMessage(long dialogId, long messageId, Object messageLike, Object mediaObject) {
        if (dialogId == 0 || messageId == 0 || messageLike == null || mediaObject == null) {
            return;
        }
        Object messageOwner = messageOwner(messageLike);

        MediaTarget target = resolveTarget(mediaObject);
        if (target == null) {
            return;
        }

        String key = dialogId + ":" + messageId + ":" + target.extension;
        if (cacheAlreadyPresent(dialogId, messageId, target)) {
            return;
        }

        int account = resolveAccount(messageLike, messageOwner);
        Object parentObject = createParentObject(account, messageLike, messageOwner);
        PendingMedia pending = new PendingMedia(key, dialogId, messageId,
                account, messageOwner, parentObject, target);
        if (tryCopyTelegramFile(pending)) {
            return;
        }

        if (!pendingKeys.add(key)) {
            return;
        }

        try {
            if (!requestTelegramDownload(pending)) {
                pendingKeys.remove(key);
                return;
            }
            ModuleLogger.hook(TAG, "requested Telegram media download dialogId=" + dialogId
                    + " msgId=" + messageId + " kind=" + target.kind + " account=" + account);
            schedulePoll(pending, 1);
        } catch (Throwable t) {
            pendingKeys.remove(key);
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "failed to request Telegram media download: " + t.getMessage());
        }
    }

    private boolean cacheAlreadyPresent(long dialogId, long messageId, MediaTarget target) {
        if (!mediaCache.hasMedia(dialogId, messageId, target.extension)) {
            return false;
        }
        updateCachedPath(dialogId, messageId, target,
                mediaCache.getMediaFile(dialogId, messageId, target.extension).getAbsolutePath());
        return true;
    }

    private void schedulePoll(PendingMedia pending, int attempt) {
        scheduler.schedule(() -> {
            try {
                if (tryCopyTelegramFile(pending)) {
                    pendingKeys.remove(pending.key);
                    return;
                }
                if (attempt >= MAX_POLL_ATTEMPTS) {
                    pendingKeys.remove(pending.key);
                    ModuleLogger.hook(TAG, "Telegram media download not ready after polling dialogId="
                            + pending.dialogId + " msgId=" + pending.messageId);
                    return;
                }
                schedulePoll(pending, attempt + 1);
            } catch (Throwable t) {
                pendingKeys.remove(pending.key);
                ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                        "media prefetch poll failed: " + t.getMessage());
            }
        }, POLL_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private boolean tryCopyTelegramFile(PendingMedia pending) {
        File source = resolveTelegramMediaPath(pending.account,
                pending.messageOwner, pending.target.fileObject);
        if (source == null || !source.exists() || source.length() <= 0) {
            return false;
        }

        try (InputStream input = new FileInputStream(source)) {
            File cached = mediaCache.saveMedia(pending.dialogId, pending.messageId,
                    pending.target.extension, input);
            updateCachedPath(pending.dialogId, pending.messageId, pending.target, cached.getAbsolutePath());
            ModuleLogger.hook(TAG, "prefetched media cached dialogId=" + pending.dialogId
                    + " msgId=" + pending.messageId + " path=" + cached.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG,
                    "failed to copy prefetched media: " + t.getMessage());
            return false;
        }
    }

    private void updateCachedPath(long dialogId, long messageId, MediaTarget target, String cachedPath) {
        MessageCache.CachedMessage existing = messageCache.get(dialogId, messageId);
        if (existing != null) {
            String mediaType = existing.mediaType != null ? existing.mediaType : target.mediaType;
            String mediaId = existing.mediaId != null ? existing.mediaId : target.mediaId;
            messageCache.putFresh(dialogId, messageId, existing.text, existing.caption,
                    existing.senderId, mediaType, mediaId, cachedPath);
            return;
        }

        messageCache.putFresh(dialogId, messageId, "", "", 0L,
                target.mediaType, target.mediaId, cachedPath);
    }

    private boolean requestTelegramDownload(PendingMedia pending) throws Exception {
        ClassLoader classLoader = telegramClassLoader;
        if (classLoader == null) {
            return false;
        }

        Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
        Object fileLoader = getFileLoader(fileLoaderClass, pending.account);
        if (fileLoader == null) {
            return false;
        }

        if (pending.target.isPhoto) {
            Object imageLocation = createImageLocation(classLoader,
                    pending.target.photoSize, pending.target.photoObject);
            if (imageLocation == null) {
                return false;
            }
            return invokeImageLocationLoad(fileLoaderClass, fileLoader, imageLocation,
                    pending.parentObject, pending.target.extension);
        }

        return invokeObjectLoad(fileLoaderClass, fileLoader,
                pending.target.fileObject, pending.parentObject);
    }

    private Object getFileLoader(Class<?> fileLoaderClass, int account) throws Exception {
        Method getInstance = findMethod(fileLoaderClass, "getInstance", 1);
        if (getInstance == null) {
            return null;
        }
        getInstance.setAccessible(true);
        return getInstance.invoke(null, account);
    }

    private int resolveAccount(Object messageLike, Object messageOwner) {
        int account = Reflect.asInt(Reflect.field(messageLike, "currentAccount"), -1);
        if (account >= 0) {
            return account;
        }
        account = Reflect.asInt(Reflect.field(messageOwner, "currentAccount"), -1);
        if (account >= 0) {
            return account;
        }
        ClassLoader classLoader = telegramClassLoader;
        if (classLoader != null) {
            try {
                Class<?> userConfigClass = classLoader.loadClass("org.telegram.messenger.UserConfig");
                return Reflect.asInt(Reflect.staticField(userConfigClass, "selectedAccount"), 0);
            } catch (Throwable ignored) {
                // Fall through to Telegram's first account.
            }
        }
        return 0;
    }

    private Object createParentObject(int account, Object messageLike, Object messageOwner) {
        if (Reflect.field(messageLike, "messageOwner") != null) {
            return messageLike;
        }
        ClassLoader classLoader = telegramClassLoader;
        if (classLoader == null) {
            return messageOwner;
        }
        try {
            Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
            if (messageObjectClass.isInstance(messageLike)) {
                return messageLike;
            }
            Class<?> messageBaseClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
            return messageObjectClass
                    .getConstructor(int.class, messageBaseClass, boolean.class, boolean.class)
                    .newInstance(account, messageOwner, false, true);
        } catch (Throwable t) {
            ModuleLogger.hook(TAG, "create MessageObject parent failed: " + t.getMessage());
            return messageOwner;
        }
    }

    private Object messageOwner(Object messageLike) {
        Object owner = Reflect.field(messageLike, "messageOwner");
        return owner != null ? owner : messageLike;
    }

    private Object createImageLocation(ClassLoader classLoader, Object photoSize, Object photo) throws Exception {
        if (photoSize == null || photo == null) {
            return null;
        }
        Class<?> imageLocationClass = classLoader.loadClass("org.telegram.messenger.ImageLocation");
        for (Method method : imageLocationClass.getDeclaredMethods()) {
            if (!"getForPhoto".equals(method.getName())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getParameterTypes().length != 2) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            method.setAccessible(true);
            if (params[0].isAssignableFrom(photoSize.getClass())
                    && params[1].isAssignableFrom(photo.getClass())) {
                return method.invoke(null, photoSize, photo);
            }
            if (params[0].isAssignableFrom(photo.getClass())
                    && params[1].isAssignableFrom(photoSize.getClass())) {
                return method.invoke(null, photo, photoSize);
            }
        }
        return null;
    }

    private boolean invokeImageLocationLoad(Class<?> fileLoaderClass, Object fileLoader,
                                            Object imageLocation, Object parentObject,
                                            String extension) throws Exception {
        String extensionName = extension.startsWith(".") ? extension.substring(1) : extension;
        for (Method method : fileLoaderClass.getMethods()) {
            if (!"loadFile".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 5
                    || !params[0].isAssignableFrom(imageLocation.getClass())
                    || !params[1].isAssignableFrom(Object.class)
                    || params[2] != String.class
                    || params[3] != int.class
                    || params[4] != int.class) {
                continue;
            }
            method.setAccessible(true);
            method.invoke(fileLoader, imageLocation, parentObject, extensionName,
                    DOWNLOAD_PRIORITY, DOWNLOAD_TYPE);
            return true;
        }
        return false;
    }

    private boolean invokeObjectLoad(Class<?> fileLoaderClass, Object fileLoader,
                                     Object fileObject, Object parentObject) throws Exception {
        if (fileObject == null) {
            return false;
        }
        for (Method method : fileLoaderClass.getMethods()) {
            if (!"loadFile".equals(method.getName())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 4
                    || !params[0].isAssignableFrom(fileObject.getClass())
                    || !params[1].isAssignableFrom(Object.class)
                    || params[2] != int.class
                    || params[3] != int.class) {
                continue;
            }
            method.setAccessible(true);
            method.invoke(fileLoader, fileObject, parentObject, DOWNLOAD_PRIORITY, DOWNLOAD_TYPE);
            return true;
        }
        return false;
    }

    private File resolveTelegramMediaPath(int account, Object messageOwner, Object mediaAttachment) {
        ClassLoader classLoader = telegramClassLoader;
        if (classLoader == null) {
            return null;
        }

        try {
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            Object fileLoader = getFileLoader(fileLoaderClass, account);
            if (fileLoader == null) {
                return null;
            }

            File byMessage = invokePathMethod(fileLoaderClass, fileLoader,
                    "getPathToMessage", messageOwner);
            if (byMessage != null) {
                return byMessage;
            }

            return invokePathMethod(fileLoaderClass, fileLoader,
                    "getPathToAttach", mediaAttachment);
        } catch (Throwable t) {
            ModuleLogger.hook(TAG, "resolve Telegram media path failed: " + t.getMessage());
            return null;
        }
    }

    private File invokePathMethod(Class<?> fileLoaderClass, Object fileLoader,
                                  String methodName, Object arg) {
        if (arg == null) {
            return null;
        }
        for (Method method : fileLoaderClass.getMethods()) {
            if (!methodName.equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(arg.getClass())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object result = method.invoke(fileLoader, arg);
                if (result instanceof File) {
                    return (File) result;
                }
            } catch (Throwable ignored) {
                // Try the next overload.
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String name, int parameterCount) {
        for (Method method : clazz.getMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private MediaTarget resolveTarget(Object mediaObject) {
        Object photo = Reflect.field(mediaObject, "photo");
        if (photo != null) {
            Object photoSize = selectBestPhotoSize(photo);
            if (photoSize != null) {
                return new MediaTarget("photo", ".jpg", mediaObject.getClass().getName(),
                        mediaId(photo), photoSize, photo, photoSize, true);
            }
        }

        Object video = Reflect.field(mediaObject, "video");
        if (video != null) {
            return new MediaTarget("video", ".mp4", mediaObject.getClass().getName(),
                    mediaId(video), video, null, null, false);
        }

        Object document = Reflect.field(mediaObject, "document");
        if (document == null || !isSupportedDocument(document)) {
            return null;
        }
        String extension = extensionForDocument(document);
        String kind = isVideoDocument(document) ? "video" : "document";
        return new MediaTarget(kind, extension, mediaObject.getClass().getName(),
                mediaId(document), document, null, null, false);
    }

    private Object selectBestPhotoSize(Object photo) {
        Object sizesObject = Reflect.field(photo, "sizes");
        if (!(sizesObject instanceof List<?>)) {
            return null;
        }

        Object best = null;
        long bestScore = -1L;
        for (Object size : (List<?>) sizesObject) {
            if (size == null || Reflect.field(size, "location") == null) {
                continue;
            }
            String simpleName = size.getClass().getSimpleName();
            if (simpleName.contains("Stripped") || simpleName.contains("Cached")) {
                continue;
            }
            long score = Reflect.asLong(Reflect.field(size, "size"), 0L);
            if (score <= 0) {
                long width = Reflect.asLong(Reflect.field(size, "w"), 0L);
                long height = Reflect.asLong(Reflect.field(size, "h"), 0L);
                score = width * height;
            }
            if (score > bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best;
    }

    private boolean isSupportedDocument(Object document) {
        String mime = lower(Reflect.asString(Reflect.field(document, "mime_type")));
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            return true;
        }
        String name = lower(documentFileName(document));
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".webp")
                || name.endsWith(".gif") || name.endsWith(".mp4")
                || name.endsWith(".m4v") || name.endsWith(".mov")
                || name.endsWith(".webm") || name.endsWith(".mkv");
    }

    private boolean isVideoDocument(Object document) {
        String mime = lower(Reflect.asString(Reflect.field(document, "mime_type")));
        String name = lower(documentFileName(document));
        return mime.startsWith("video/") || name.endsWith(".mp4")
                || name.endsWith(".m4v") || name.endsWith(".mov")
                || name.endsWith(".webm") || name.endsWith(".mkv");
    }

    static String extensionForDocumentMetadata(String mimeType, String fileName) {
        String name = lower(fileName);
        String fromName = extensionFromName(name);
        if (fromName != null) {
            return fromName;
        }

        String mime = lower(mimeType);
        if ("image/png".equals(mime)) {
            return ".png";
        }
        if ("image/webp".equals(mime)) {
            return ".webp";
        }
        if ("image/gif".equals(mime)) {
            return ".gif";
        }
        if (mime.startsWith("image/")) {
            return ".jpg";
        }
        if ("video/webm".equals(mime)) {
            return ".webm";
        }
        if ("video/quicktime".equals(mime)) {
            return ".mov";
        }
        if (mime.startsWith("video/")) {
            return ".mp4";
        }
        return ".bin";
    }

    private String extensionForDocument(Object document) {
        return extensionForDocumentMetadata(
                Reflect.asString(Reflect.field(document, "mime_type")),
                documentFileName(document));
    }

    private static String extensionFromName(String name) {
        if (name == null) {
            return null;
        }
        String[] extensions = {".jpg", ".jpeg", ".png", ".webp", ".gif",
                ".mp4", ".m4v", ".mov", ".webm", ".mkv"};
        for (String extension : extensions) {
            if (name.endsWith(extension)) {
                return ".jpeg".equals(extension) ? ".jpg" : extension;
            }
        }
        return null;
    }

    private String documentFileName(Object document) {
        Object attributesObject = Reflect.field(document, "attributes");
        if (!(attributesObject instanceof List<?>)) {
            return null;
        }

        for (Object attribute : (List<?>) attributesObject) {
            if (attribute == null) {
                continue;
            }
            String fileName = Reflect.asString(Reflect.field(attribute, "file_name"));
            if (fileName != null && !fileName.isEmpty()) {
                return fileName;
            }
        }
        return null;
    }

    private String mediaId(Object media) {
        long id = Reflect.asLong(Reflect.field(media, "id"), 0L);
        if (id != 0L) {
            return String.valueOf(id);
        }
        long volumeId = Reflect.asLong(Reflect.field(media, "volume_id"), 0L);
        long localId = Reflect.asLong(Reflect.field(media, "local_id"), 0L);
        if (volumeId != 0L || localId != 0L) {
            return volumeId + "_" + localId;
        }
        return media.getClass().getName() + "@" + System.identityHashCode(media);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static final class PendingMedia {
        final String key;
        final long dialogId;
        final long messageId;
        final int account;
        final Object messageOwner;
        final Object parentObject;
        final MediaTarget target;

        PendingMedia(String key, long dialogId, long messageId, int account,
                     Object messageOwner, Object parentObject, MediaTarget target) {
            this.key = key;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.account = account;
            this.messageOwner = messageOwner;
            this.parentObject = parentObject;
            this.target = target;
        }
    }

    private static final class MediaTarget {
        final String kind;
        final String extension;
        final String mediaType;
        final String mediaId;
        final Object fileObject;
        final Object photoObject;
        final Object photoSize;
        final boolean isPhoto;

        MediaTarget(String kind, String extension, String mediaType, String mediaId,
                    Object fileObject, Object photoObject, Object photoSize, boolean isPhoto) {
            this.kind = kind;
            this.extension = extension;
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.fileObject = fileObject;
            this.photoObject = photoObject;
            this.photoSize = photoSize;
            this.isPhoto = isPhoto;
        }
    }
}
