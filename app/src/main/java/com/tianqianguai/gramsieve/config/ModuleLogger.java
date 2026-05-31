package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

public final class ModuleLogger {
    public static final String TAG = "GramSieve";
    public static final String CAT_LIFECYCLE = "lifecycle";
    public static final String CAT_HOOK = "hook";
    public static final String CAT_CONFIG = "config";
    public static final String CAT_ERROR = "error";
    public static final String CAT_DECISION = "decision";

    private static volatile Context appContext;
    private static volatile boolean hookProcessMode;
    private static volatile XposedModule xposedModule;

    private ModuleLogger() {
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static void setHookProcessMode(XposedModule module) {
        hookProcessMode = true;
        xposedModule = module;
    }

    public static void info(String category, String tag, String message) {
        Log.i(TAG, "[" + category + "] " + tag + ": " + message);
        if (xposedModule != null) {
            xposedModule.log(Log.INFO, TAG, "[" + category + "] " + tag + ": " + message);
        }
        persist("INFO", category, tag, message, (String) null);
    }

    public static void warn(String category, String tag, String message) {
        Log.w(TAG, "[" + category + "] " + tag + ": " + message);
        if (xposedModule != null) {
            xposedModule.log(Log.WARN, TAG, "[" + category + "] " + tag + ": " + message);
        }
        persist("WARN", category, tag, message, (String) null);
    }

    public static void error(String category, String tag, String message, Throwable throwable) {
        Log.e(TAG, "[" + category + "] " + tag + ": " + message, throwable);
        if (xposedModule != null) {
            xposedModule.log(Log.ERROR, TAG, "[" + category + "] " + tag + ": " + message, throwable);
        }
        persist("ERROR", category, tag, message, throwable);
    }

    public static void lifecycle(String tag, String message) {
        info(CAT_LIFECYCLE, tag, message);
    }

    public static void hook(String tag, String message) {
        info(CAT_HOOK, tag, message);
    }

    public static void hookError(String tag, String message, Throwable throwable) {
        error(CAT_HOOK, tag, message, throwable);
    }

    public static void config(String tag, String message) {
        info(CAT_CONFIG, tag, message);
    }

    public static void configError(String tag, String message, Throwable throwable) {
        error(CAT_CONFIG, tag, message, throwable);
    }

    public static void decision(String tag, String message) {
        info(CAT_DECISION, tag, message);
    }

    private static void persist(String level, String category, String tag, String message, String throwableStr) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        if (hookProcessMode) {
            persistViaContentProvider(context, level, category, tag, message, throwableStr);
        } else {
            PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry();
            entry.level = level;
            entry.category = category;
            entry.tag = tag;
            entry.message = message;
            entry.throwable = throwableStr == null ? "" : throwableStr;
            PersistentLogStore.append(context, entry);
        }
    }

    private static void persist(String level, String category, String tag, String message, Throwable throwable) {
        String throwableStr = throwableToString(throwable);
        persist(level, category, tag, message, throwableStr);
    }

    private static void persistViaContentProvider(Context context, String level, String category, String tag, String message, String throwableStr) {
        try {
            PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry();
            entry.level = level;
            entry.category = category;
            entry.tag = tag;
            entry.message = message;
            entry.throwable = throwableStr == null ? "" : throwableStr;
            Bundle extras = new Bundle();
            extras.putString(ConfigContentProvider.KEY_LOG_ENTRY_JSON, PersistentLogStore.entryToJson(entry));
            context.getContentResolver().call(
                    ConfigContentProvider.CONTENT_URI,
                    ConfigContentProvider.METHOD_APPEND_LOG,
                    null,
                    extras
            );
        } catch (RuntimeException ignored) {
        }
    }

    private static String throwableToString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.getClass().getName());
        if (throwable.getMessage() != null) {
            sb.append(": ").append(throwable.getMessage());
        }
        StackTraceElement[] trace = throwable.getStackTrace();
        int limit = Math.min(trace.length, 8);
        for (int i = 0; i < limit; i++) {
            sb.append("\n  at ").append(trace[i]);
        }
        return sb.toString();
    }
}
