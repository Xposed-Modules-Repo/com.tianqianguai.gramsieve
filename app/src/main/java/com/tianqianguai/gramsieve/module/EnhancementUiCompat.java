package com.tianqianguai.gramsieve.module;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Reversible UI mutations for the Telegram 12.10 component shapes. */
final class EnhancementUiCompat {
    private final Map<View, Integer> hidden = new WeakHashMap<>();
    private static final int ID_STATE_TAG = 0x47534944;
    private static final int COPY_STATE_TAG = 0x47534350;
    private static final int CONTACT_STATE_TAG = 0x47534354;
    private static final int CAMERA_STATE_TAG = 0x47534343;
    private final Map<Object, Boolean> videoModes = new WeakHashMap<>();

    void visibility(Object candidate, boolean hide) {
        if (!(candidate instanceof View)) {
            return;
        }
        View view = (View) candidate;
        if (hide) {
            if (view.getVisibility() != View.GONE) {
                hidden.putIfAbsent(view, view.getVisibility());
                view.setVisibility(View.GONE);
            }
        } else if (hidden.containsKey(view)) {
            view.setVisibility(hidden.remove(view));
        }
    }

    void profileIds(Object profile, boolean show) {
        long id = Reflect.asLong(Reflect.field(profile, "userId"), 0L);
        if (id == 0) {
            id = Reflect.asLong(Reflect.field(profile, "chatId"), 0L);
        }
        for (Object target : elements(Reflect.field(profile, "onlineTextView"))) {
            if (!(target instanceof View)) {
                continue;
            }
            View view = (View) target;
            Object raw = Reflect.invokeIfExists(view, "getText", new Class<?>[0]);
            if (!(raw instanceof CharSequence)) {
                continue;
            }
            String current = raw.toString();
            Object stored = view.getTag(ID_STATE_TAG);
            String[] previous = stored instanceof String[] ? (String[]) stored : null;
            String original = previous != null && current.equals(previous[1]) ? previous[0] : current;
            if (show && id != 0L) {
                String rendered = original + " · ID " + id;
                view.setTag(ID_STATE_TAG, new String[]{original, rendered});
                Reflect.invokeIfExists(view, "setText", new Class<?>[]{CharSequence.class}, rendered);
            } else if (previous != null) {
                if (current.equals(previous[1])) {
                    Reflect.invokeIfExists(view, "setText", new Class<?>[]{CharSequence.class}, previous[0]);
                }
                view.setTag(ID_STATE_TAG, null);
            }
        }
    }

    void profileCopy(Object profile, boolean enable, BooleanSupplier active) {
        for (Object target : elements(Reflect.field(profile, "nameTextView"))) {
            if (!(target instanceof View)) {
                continue;
            }
            View view = (View) target;
            Object stored = view.getTag(COPY_STATE_TAG);
            if (enable) {
                if (!(stored instanceof Object[])) {
                    Object listeners = Reflect.invokeIfExists(view, "getListenerInfo", new Class<?>[0]);
                    Object previous = Reflect.field(listeners, "mOnLongClickListener");
                    view.setTag(COPY_STATE_TAG, new Object[]{previous, view.isLongClickable()});
                }
                view.setOnLongClickListener(clicked -> {
                    if (!active.getAsBoolean()) return false;
                    String name = Reflect.asString(Reflect.invokeIfExists(clicked, "getText", new Class<?>[0]));
                    ClipboardManager clipboard = (ClipboardManager) clicked.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null || name.isBlank()) {
                        return false;
                    }
                    clipboard.setPrimaryClip(ClipData.newPlainText("Telegram profile name", name));
                    EnhancementMediaActions.record("copy_profile_name", "copied", name.length());
                    Toast.makeText(clicked.getContext(), TelegramLocale.isChinese(clicked.getContext(), clicked.getContext().getClassLoader())
                            ? "名称已复制" : "Name copied", Toast.LENGTH_SHORT).show();
                    return true;
                });
            } else if (stored instanceof Object[]) {
                Object[] previous = (Object[]) stored;
                view.setOnLongClickListener(previous[0] instanceof View.OnLongClickListener ? (View.OnLongClickListener) previous[0] : null);
                view.setLongClickable(Boolean.TRUE.equals(previous[1]));
                view.setTag(COPY_STATE_TAG, null);
            }
        }
    }

    void contacts(Object mainTabs, boolean hide) {
        List<Object> tabs = elements(Reflect.field(mainTabs, "tabs"));
        Object strip = Reflect.field(mainTabs, "tabsView");
        if (tabs.size() > 1 && tabs.get(1) instanceof View && strip != null) {
            View contacts = (View) tabs.get(1);
            Object previous = contacts.getTag(CONTACT_STATE_TAG);
            if (hide) {
                if (!(previous instanceof Boolean)) {
                    Object visible = Reflect.invokeIfExists(strip, "isViewVisible", new Class<?>[]{View.class}, contacts);
                    contacts.setTag(CONTACT_STATE_TAG, visible instanceof Boolean ? visible : contacts.getVisibility() == View.VISIBLE);
                }
                Reflect.invokeIfExists(strip, "setViewVisible", new Class<?>[]{View.class, boolean.class, boolean.class}, contacts, false, false);
            } else if (previous instanceof Boolean) {
                Reflect.invokeIfExists(strip, "setViewVisible", new Class<?>[]{View.class, boolean.class, boolean.class}, contacts, previous, false);
                contacts.setTag(CONTACT_STATE_TAG, null);
            }
        }
    }

    void premiumTab(Object emoji, boolean hide) {
        int index = Reflect.asInt(Reflect.field(emoji, "premiumTabNum"), -1);
        Object strip = Reflect.field(emoji, "stickersTab");
        Object container = Reflect.field(strip, "tabsContainer");
        if (container instanceof ViewGroup && index >= 0 && index < ((ViewGroup) container).getChildCount()) {
            visibility(((ViewGroup) container).getChildAt(index), hide);
        }
    }

    void cameraMode(Object enterView, boolean disable) {
        if (enterView == null || Boolean.TRUE.equals(Reflect.field(enterView, "recordingAudioVideo"))) {
            return;
        }
        if (disable) {
            rememberVideoMode(enterView, Boolean.TRUE.equals(Reflect.field(enterView, "isInVideoMode")));
            Reflect.invokeIfExists(enterView, "setRecordVideoButtonVisible", new Class<?>[]{boolean.class, boolean.class}, false, false);
        } else {
            Object oldMode = enterView instanceof View ? ((View) enterView).getTag(CAMERA_STATE_TAG) : videoModes.remove(enterView);
            if (oldMode instanceof Boolean) {
                if (enterView instanceof View) ((View) enterView).setTag(CAMERA_STATE_TAG, null);
                Reflect.invokeIfExists(enterView, "setRecordVideoButtonVisible", new Class<?>[]{boolean.class, boolean.class}, oldMode, false);
            }
        }
    }

    void rememberVideoMode(Object enterView, boolean requestedMode) {
        if (enterView instanceof View) {
            View view = (View) enterView;
            if (!(view.getTag(CAMERA_STATE_TAG) instanceof Boolean)) view.setTag(CAMERA_STATE_TAG, requestedMode);
        } else {
            videoModes.putIfAbsent(enterView, requestedMode);
        }
    }

    static List<Object> elements(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof Object[]) {
            for (Object element : (Object[]) value) {
                if (element != null) {
                    result.add(element);
                }
            }
        } else if (value != null) {
            result.add(value);
        }
        return result;
    }

    static boolean removePhoneRow(Object profile) {
        int removed = Reflect.asInt(Reflect.field(profile, "phoneRow"), -1);
        int count = Reflect.asInt(Reflect.field(profile, "rowCount"), 0);
        if (removed < 0 || removed >= count) {
            return false;
        }
        Field phone = FeatureProbe.field(profile.getClass(), "phoneRow");
        for (Field field : phone.getDeclaringClass().getDeclaredFields()) {
            String name = field.getName();
            if (field.getType() != int.class || !(name.endsWith("Row") || name.equals("rowCount"))) {
                continue;
            }
            try {
                field.setAccessible(true);
                int index = field.getInt(profile);
                // Equal start-boundary aliases still point at the next row after removal.
                if (name.equals("phoneRow")) {
                    field.setInt(profile, -1);
                } else if (index > removed) {
                    field.setInt(profile, index - 1);
                }
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return true;
    }
}
