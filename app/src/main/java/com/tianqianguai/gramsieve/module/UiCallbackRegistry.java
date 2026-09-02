package com.tianqianguai.gramsieve.module;

import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Tracks callbacks retained by Telegram views so an old module generation can detach cleanly. */
final class UiCallbackRegistry {
    private final Set<View> clickViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> touchViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<ViewGroup> hierarchyViews = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Dialog> dialogs = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Runnable, WeakReference<View>> postedCallbacks = new IdentityHashMap<>();
    private volatile boolean accepting = true;

    void setClickListener(View view, View.OnClickListener listener) {
        if (view == null) {
            return;
        }
        synchronized (clickViews) {
            if (!accepting) {
                return;
            }
            clickViews.add(view);
            view.setOnClickListener(listener);
        }
    }

    void setTouchListener(View view, View.OnTouchListener listener) {
        if (view == null) {
            return;
        }
        synchronized (touchViews) {
            if (!accepting) {
                return;
            }
            touchViews.add(view);
            view.setOnTouchListener(listener);
        }
    }

    void setHierarchyListener(ViewGroup view, ViewGroup.OnHierarchyChangeListener listener) {
        if (view == null) {
            return;
        }
        synchronized (hierarchyViews) {
            if (!accepting) {
                return;
            }
            hierarchyViews.add(view);
            view.setOnHierarchyChangeListener(listener);
        }
    }

    boolean post(View view, Runnable callback, long delayMs) {
        if (view == null || callback == null || !accepting) {
            return false;
        }
        synchronized (postedCallbacks) {
            if (!accepting) {
                return false;
            }
            postedCallbacks.put(callback, new WeakReference<>(view));
        }
        return delayMs <= 0L ? view.post(callback) : view.postDelayed(callback, delayMs);
    }

    boolean post(View view, Runnable callback) {
        return post(view, callback, 0L);
    }

    void trackDialog(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        synchronized (dialogs) {
            if (accepting) {
                dialogs.add(dialog);
            }
        }
    }

    boolean prepareForHotReload(long timeoutMs) {
        accepting = false;
        Runnable cleanup = () -> {
            synchronized (postedCallbacks) {
                for (Map.Entry<Runnable, WeakReference<View>> entry
                        : new ArrayList<>(postedCallbacks.entrySet())) {
                    View view = entry.getValue().get();
                    if (view != null) {
                        view.removeCallbacks(entry.getKey());
                    }
                }
                postedCallbacks.clear();
            }
            synchronized (clickViews) {
                for (View view : new ArrayList<>(clickViews)) {
                    if (view != null) {
                        view.setOnClickListener(null);
                    }
                }
                clickViews.clear();
            }
            synchronized (touchViews) {
                for (View view : new ArrayList<>(touchViews)) {
                    if (view != null) {
                        view.setOnTouchListener(null);
                    }
                }
                touchViews.clear();
            }
            synchronized (hierarchyViews) {
                for (ViewGroup view : new ArrayList<>(hierarchyViews)) {
                    if (view != null) {
                        view.setOnHierarchyChangeListener(null);
                    }
                }
                hierarchyViews.clear();
            }
            synchronized (dialogs) {
                for (Dialog dialog : new ArrayList<>(dialogs)) {
                    if (dialog != null && dialog.isShowing()) {
                        dialog.dismiss();
                    }
                }
                dialogs.clear();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup.run();
            return true;
        }
        CountDownLatch completed = new CountDownLatch(1);
        Handler handler = new Handler(Looper.getMainLooper());
        if (!handler.post(() -> {
            try {
                cleanup.run();
            } finally {
                completed.countDown();
            }
        })) {
            return false;
        }
        try {
            return completed.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
