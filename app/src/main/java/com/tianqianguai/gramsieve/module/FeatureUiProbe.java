package com.tianqianguai.gramsieve.module;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded inspection of known Telegram UI owners. No arbitrary method execution. */
final class FeatureUiProbe {
    static Map<String, Object> inspect(Object foreground, Object chat) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("foregroundClass", foreground == null ? "" : foreground.getClass().getName());
        Object visible = isOwner(foreground, "MainTabsActivity")
                ? Reflect.invokeIfExists(foreground, "getCurrentVisibleFragment", new Class<?>[0]) : foreground;
        result.put("visibleFragmentClass", visible == null ? "" : visible.getClass().getName());
        Object home = isOwner(foreground, "DialogsActivity") ? foreground
                : isOwner(foreground, "MainTabsActivity") ? Reflect.field(foreground, "dialogsActivity") : null;
        Object profile = isOwner(visible, "ProfileActivity") ? visible : null;
        result.put("home", fields(home, "dialogStoriesCell", "dialogStoriesCellVisible", "hasStories",
                "progressToShowStories", "scrollYOffset", "floatingButton", "floatingButtonContainer",
                "floatingButton2", "contactsItem", "contactsButton", "contactsTab"));
        result.put("tabs", fields(isOwner(foreground, "MainTabsActivity") ? foreground : null, "tabs"));
        result.put("profile", fields(profile, "phoneTextView", "phoneRow", "nameTextView", "onlineTextView"));
        result.put("chat", fields(chat, "pinnedMessageView", "pinnedMessageViewAnimator", "avatarContainer"));
        result.put("subtitle", fields(Reflect.field(chat, "avatarContainer"), "subtitleTextView"));
        List<Map<String, Object>> cells = new ArrayList<>();
        Object list = Reflect.field(chat, "chatListView");
        if (list instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) list;
            for (int i = 0; i < group.getChildCount() && cells.size() < 30; i++) {
                View child = group.getChildAt(i);
                Object message = Reflect.field(child, "currentMessageObject");
                if (message == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                Object id = Reflect.field(Reflect.field(message, "messageOwner"), "id");
                item.put("messageId", id instanceof Number ? id : null);
                Object time = Reflect.field(child, "currentTimeString");
                item.put("timeLabel", time instanceof CharSequence ? time.toString() : "");
                item.put("view", value(child));
                cells.add(item);
            }
        }
        result.put("visibleMessageCells", cells);
        result.put("note", "Unavailable owners and null fields are reported explicitly; this does not navigate or mutate the UI.");
        return result;
    }

    private static boolean isOwner(Object value, String name) {
        for (Class<?> c = value == null ? null : value.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("org.telegram.ui." + name)) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> fields(Object owner, String... names) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", owner != null);
        result.put("className", owner == null ? "" : owner.getClass().getName());
        List<Map<String, Object>> items = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            Field field = owner == null ? null : FeatureProbe.field(owner.getClass(), name);
            item.put("present", field != null);
            if (field != null) {
                item.put("declaredType", field.getType().getName());
                try {
                    field.setAccessible(true);
                    item.put("value", value(field.get(owner)));
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    item.put("error", failure.getClass().getSimpleName());
                }
            }
            items.add(item);
        }
        result.put("fields", items);
        return result;
    }

    static Object value(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value.getClass().isArray()) {
            Map<String, Object> result = new LinkedHashMap<>();
            int length = Array.getLength(value);
            result.put("length", length);
            List<Object> items = new ArrayList<>();
            // Only View arrays are expanded. Other arrays remain metadata, never a recursive graph.
            if (View.class.isAssignableFrom(value.getClass().getComponentType())) {
                for (int i = 0; i < Math.min(length, 12); i++) {
                    items.add(value(Array.get(value, i)));
                }
            }
            result.put("items", items);
            result.put("truncated", length > 12);
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", value.getClass().getName());
        if (value instanceof View) {
            View view = (View) value;
            result.put("visibility", view.getVisibility());
            result.put("shown", view.isShown());
            result.put("attached", view.isAttachedToWindow());
            result.put("enabled", view.isEnabled());
            result.put("clickable", view.isClickable());
            result.put("longClickable", view.isLongClickable());
            result.put("alpha", view.getAlpha());
            result.put("width", view.getWidth());
            result.put("height", view.getHeight());
            result.put("measuredHeight", view.getMeasuredHeight());
            ViewGroup.LayoutParams params = view.getLayoutParams();
            result.put("layoutHeight", params == null ? null : params.height);
            Rect bounds = new Rect();
            result.put("globallyVisible", view.getGlobalVisibleRect(bounds));
            result.put("bounds", new int[]{bounds.left, bounds.top, bounds.right, bounds.bottom});
            Object rawText = view instanceof TextView ? ((TextView) view).getText()
                    : view.getClass().getName().startsWith("org.telegram.ui.ActionBar.SimpleTextView")
                    ? Reflect.invokeIfExists(view, "getText", new Class<?>[0]) : null;
            if (rawText instanceof CharSequence) {
                CharSequence text = (CharSequence) rawText;
                result.put("textLength", text == null ? 0 : text.length());
                result.put("hasIdMarker", text != null && text.toString().contains("ID"));
            }
        }
        return result;
    }
}
