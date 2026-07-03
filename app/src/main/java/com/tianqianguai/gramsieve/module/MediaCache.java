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
        String filename = dialogId + "_" + messageId + (extension != null ? extension : "");
        return new File(mediaDir, filename);
    }

    public boolean hasMedia(long dialogId, long messageId, String extension) {
        File file = getMediaFile(dialogId, messageId, extension);
        return file.exists() && file.length() > 0;
    }

    public File saveMedia(long dialogId, long messageId, String extension, InputStream inputStream) {
        File file = getMediaFile(dialogId, messageId, extension);
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
        File file = getMediaFile(dialogId, messageId, extension);
        if (file.exists() && file.length() > 0) {
            return file;
        }
        return null;
    }

    public void deleteMedia(long dialogId, long messageId, String extension) {
        File file = getMediaFile(dialogId, messageId, extension);
        if (file.exists()) {
            file.delete();
        }
    }
}
