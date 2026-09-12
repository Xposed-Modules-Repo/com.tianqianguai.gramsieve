package com.tianqianguai.gramsieve.module;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import com.tianqianguai.gramsieve.core.EnhancementConfig;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Local menu actions; use Telegram's own saving implementation, never a send/forward API. */
final class EnhancementMediaActions {
    static final String COPY_TAG = "gramsieve.allow_copy";
    static final String VOICE_TAG = "gramsieve.save_voice_messages";
    static final String STORY_TAG = "gramsieve.save_stories";
    private static volatile Map<String, Object> lastResult = new LinkedHashMap<>();
    private static volatile WeakReference<Object> storyOwner = new WeakReference<>(null);

    static void observeStory(Object owner) { storyOwner = new WeakReference<>(owner); }
    static Object storyOwner() { return storyOwner.get(); }

    static Map<String, Object> result() {
        return new LinkedHashMap<>(lastResult);
    }

    static void record(String action, String status, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("status", status);
        result.put("value", value);
        lastResult = result;
    }

    static String copyText(Object message) {
        Object owner = Reflect.field(message, "messageOwner");
        String text = Reflect.asString(Reflect.field(owner, "message"));
        if (text.isBlank()) {
            text = Reflect.asString(Reflect.field(message, "caption"));
        }
        if (text.isBlank() && Reflect.field(owner, "media") == null) {
            text = Reflect.asString(Reflect.field(message, "messageText"));
        }
        return text;
    }

    static void appendMessages(ViewGroup container, Object message, EnhancementConfig config,
                               ClassLoader loader, Runnable dismiss, BooleanSupplier active) {
        Context context = container.getContext();
        String text = copyText(message);
        if (config.isEnabledForGramSieve(EnhancementConfig.Feature.ALLOW_COPY) && !text.isBlank()) {
            add(container, loader, COPY_TAG, nativeLabel(context, loader, "Copy", label(context, "复制", "Copy")), () -> {
                if (!active.getAsBoolean()) return;
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram message", text));
                    record("copy", "copied", text.length());
                    dismiss.run();
                }
            });
        }
        if (config.isEnabledForGramSieve(EnhancementConfig.Feature.SAVE_VOICE_MESSAGES)
                && Boolean.TRUE.equals(Reflect.invokeIfExists(message, "isVoice", new Class<?>[0]))) {
            add(container, loader, VOICE_TAG, label(context, "保存语音到下载", "Save voice to Downloads"), () -> {
                if (!active.getAsBoolean()) return;
                dismiss.run();
                saveVoice(context, loader, message);
            });
        }
    }

    static void appendStory(ViewGroup layout, Object storyView, ClassLoader loader, Runnable dismiss, BooleanSupplier active) {
        Object story = Reflect.field(storyView, "currentStory");
        if (story == null || Boolean.TRUE.equals(Reflect.field(story, "isLive"))) return;
        add(layout, loader, STORY_TAG, nativeLabel(layout.getContext(), loader, "SaveToGallery", label(layout.getContext(), "保存到相册", "Save to Gallery")), () -> {
            if (!active.getAsBoolean()) return;
            dismiss.run();
            try {
                Method save = Reflect.method(loader.loadClass("org.telegram.ui.Stories.PeerStoriesView"), "saveToGallery");
                Reflect.invoke(save, storyView);
                record("save_story", "native_save_invoked", null);
            } catch (RuntimeException | ReflectiveOperationException failure) {
                record("save_story", "failed", failure.getClass().getSimpleName());
            }
        });
    }

    private static void saveVoice(Context context, ClassLoader loader, Object message) {
        try {
            int account = Reflect.asInt(Reflect.field(message, "currentAccount"), 0);
            Object owner = Reflect.field(message, "messageOwner");
            Class<?> fileLoader = loader.loadClass("org.telegram.messenger.FileLoader");
            Object instance = Reflect.invokeStatic(fileLoader, "getInstance", new Class<?>[]{int.class}, account);
            Object path = owner == null ? null : Reflect.invokeIfExists(instance, "getPathToMessage", new Class<?>[]{owner.getClass()}, owner);
            File file = path instanceof File ? (File) path : null;
            if (file == null || !file.isFile()) {
                String attached = Reflect.asString(Reflect.field(owner, "attachPath"));
                file = attached.isBlank() ? null : new File(attached);
            }
            if (file == null || !file.isFile()) {
                record("save_voice", "download_required", null);
                Toast.makeText(context, label(context, "请先下载语音", "Download the voice message first"), Toast.LENGTH_SHORT).show();
                return;
            }
            Class<?> callbackClass = loader.loadClass("org.telegram.messenger.Utilities$Callback");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackClass}, (proxy, method, args) -> {
                if (method.getName().equals("run")) {
                    Object uri = args == null || args.length == 0 ? null : args[0];
                    record("save_voice", uri instanceof Uri ? "saved" : "save_failed", uri == null ? null : uri.toString());
                } else if (method.getName().equals("toString")) return "GramSieveVoiceSaveCallback";
                else if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                else if (method.getName().equals("equals")) return args != null && args.length == 1 && proxy == args[0];
                return null;
            });
            Class<?> mediaController = loader.loadClass("org.telegram.messenger.MediaController");
            String mime = Reflect.asString(Reflect.invokeIfExists(message, "getMimeType", new Class<?>[0]));
            if (!mime.startsWith("audio/")) mime = "audio/ogg";
            String extension = mime.contains("mpeg") ? ".mp3" : mime.contains("mp4") ? ".m4a" : mime.contains("wav") ? ".wav" : ".ogg";
            String name = "Voice_" + Reflect.asInt(Reflect.field(owner, "id"), 0) + extension;
            Method save = Reflect.method(mediaController, "saveFile", String.class, Context.class, int.class, String.class, String.class, callbackClass);
            record("save_voice", "saving", null);
            Reflect.invoke(save, null, file.getAbsolutePath(), context, 2, name, mime, callback);
        } catch (RuntimeException | ReflectiveOperationException failure) {
            record("save_voice", "failed", failure.getClass().getSimpleName());
            Toast.makeText(context, label(context, "保存语音失败", "Could not save voice message"), Toast.LENGTH_SHORT).show();
        }
    }

    private static void add(ViewGroup container, ClassLoader loader, String tag, String title, Runnable action) {
        if (containsLabel(container, tag, title)) return;
        try {
            Class<?> type = loader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            View view = (View) type.getConstructor(Context.class, boolean.class, boolean.class)
                    .newInstance(container.getContext(), false, false);
            int icon = container.getResources().getIdentifier(tag.equals(COPY_TAG) ? "msg_copy" : "msg_download", "drawable", container.getContext().getPackageName());
            Reflect.invokeIfExists(view, "setTextAndIcon", new Class<?>[]{CharSequence.class, int.class}, title, icon);
            view.setTag(tag);
            view.setOnClickListener(clicked -> action.run());
            container.addView(view);
        } catch (ReflectiveOperationException failure) {
            record(tag, "menu_failed", failure.getClass().getSimpleName());
        }
    }

    private static boolean containsLabel(View root, String tag, String title) {
        if (root.getVisibility() != View.VISIBLE) return false;
        if (tag.equals(root.getTag())) return true;
        Object textView = Reflect.field(root, "textView");
        Object text = Reflect.invokeIfExists(textView, "getText", new Class<?>[0]);
        if (text != null && title.contentEquals(text.toString())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsLabel(group.getChildAt(i), tag, title)) return true;
            }
        }
        return false;
    }

    private static String label(Context context, String chinese, String english) {
        return TelegramLocale.isChinese(context, context.getClassLoader()) ? chinese : english;
    }

    private static String nativeLabel(Context context, ClassLoader loader, String name, String fallback) {
        int resource = context.getResources().getIdentifier(name, "string", context.getPackageName());
        if (resource != 0) {
            try {
                Object value = Reflect.invokeStatic(loader.loadClass("org.telegram.messenger.LocaleController"),
                        "getString", new Class<?>[]{int.class}, resource);
                if (value instanceof String && !((String) value).isBlank()) return (String) value;
            } catch (ClassNotFoundException ignored) { }
        }
        return fallback;
    }
}
