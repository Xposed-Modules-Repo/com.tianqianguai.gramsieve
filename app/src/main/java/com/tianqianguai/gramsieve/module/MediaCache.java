package com.tianqianguai.gramsieve.module;

import android.content.Context;

import com.tianqianguai.gramsieve.config.ModuleLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MediaCache {
    private static final String TAG = "GramSieve";
    private static final String MEDIA_DIR = "gramsieve_media";

    private final File mediaDir;

    public MediaCache(Context context) {
        this.mediaDir = new File(context.getFilesDir(), MEDIA_DIR);
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
    }

    public File getMediaFile(long dialogId, long messageId, String extension) {
        return getMediaFile(0, dialogId, messageId, extension);
    }

    public File getMediaFile(int accountId, long dialogId, long messageId, String extension) {
        String prefix = accountId == 0 ? "" : "account" + Math.max(0, accountId) + "_";
        String filename = prefix + dialogId + "_" + messageId + (extension != null ? extension : "");
        return new File(mediaDir, filename);
    }

    public boolean hasMedia(long dialogId, long messageId, String extension) {
        return hasMedia(0, dialogId, messageId, extension);
    }

    public boolean hasMedia(int accountId, long dialogId, long messageId, String extension) {
        File file = getMediaFile(accountId, dialogId, messageId, extension);
        return file.exists() && file.length() > 0;
    }

    public File saveMedia(long dialogId, long messageId, String extension, InputStream inputStream) {
        return saveMedia(0, dialogId, messageId, extension, inputStream);
    }

    public File saveMedia(int accountId, long dialogId, long messageId, String extension,
                          InputStream inputStream) {
        File file = getMediaFile(accountId, dialogId, messageId, extension);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            ModuleLogger.hook(TAG, "MediaCache: saved " + file.getName() + " size=" + file.length());
            return file;
        } catch (IOException e) {
            ModuleLogger.error(ModuleLogger.CAT_HOOK, TAG, "MediaCache: failed to save media", e);
            return null;
        }
    }

    public File getMedia(long dialogId, long messageId, String extension) {
        return getMedia(0, dialogId, messageId, extension);
    }

    public File getMedia(int accountId, long dialogId, long messageId, String extension) {
        File file = getMediaFile(accountId, dialogId, messageId, extension);
        if (file.exists() && file.length() > 0) {
            return file;
        }
        return null;
    }

    public void deleteMedia(long dialogId, long messageId, String extension) {
        deleteMedia(0, dialogId, messageId, extension);
    }

    public void deleteMedia(int accountId, long dialogId, long messageId, String extension) {
        File file = getMediaFile(accountId, dialogId, messageId, extension);
        if (file.exists()) {
            file.delete();
        }
    }
}
