package com.tianqianguai.gramsieve.config;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

public final class ModuleLogger {
    public static final String TAG = "GramSieve";
    public static final String CAT_LIFECYCLE = "lifecycle";
    public static final String CAT_HOOK = "hook";
    public static final String CAT_CONFIG = "config";
    public static final String CAT_ERROR = "error";
    public static final String CAT_DECISION = "decision";

    private static final String LOG_DIR = "GramSieve";
    private static final String LOG_FILE = "gramsieve.log";
    private static final int MAX_PENDING_FILE_LINES = 200;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile Context appContext;
    private static volatile boolean hookProcessMode;
    private static volatile XposedModule xposedModule;
    private static volatile File[] logFiles = new File[0];
    private static final ArrayDeque<String> pendingFileLines = new ArrayDeque<>(MAX_PENDING_FILE_LINES);
    private static String activeLogFileDescription = "";

    private ModuleLogger() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
        if (logFiles.length == 0) {
            initLogFile();
        }
    }

    public static void setHookProcessMode(XposedModule module) {
        hookProcessMode = true;
        xposedModule = module;
        if (logFiles.length == 0) {
            initLogFile();
        }
    }

    private static void initLogFile() {
        synchronized (ModuleLogger.class) {
            initLogFileLocked();
        }
    }

    private static void initLogFileLocked() {
        try {
            Context context = appContext;
            if (context == null) {
                return;
            }
            List<File> prepared = new ArrayList<>();
            Set<String> seenPaths = new HashSet<>();

            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                addPreparedLogFile(prepared, seenPaths, new File(new File(externalDir, LOG_DIR), LOG_FILE));
            }

            File internalDir = context.getFilesDir();
            if (internalDir != null) {
                addPreparedLogFile(prepared, seenPaths, new File(internalDir, LOG_FILE));
            }

            if (prepared.isEmpty()) {
                return;
            }

            File[] files = prepared.toArray(new File[0]);
            logFiles = files;
            flushPendingFileLinesLocked(files);

            String description = describeLogFiles(files);
            if (!description.equals(activeLogFileDescription)) {
                activeLogFileDescription = description;
                appendLineToFilesLocked(files, formatLine(
                        "INFO",
                        CAT_LIFECYCLE,
                        TAG,
                        "Persistent log file active: " + description,
                        null
                ));
            }
        } catch (Exception e) {
            logError("Failed to init log file", e);
        }
    }

    private static void writeToFile(String level, String category, String tag, String message, String throwableStr) {
        String line = formatLine(level, category, tag, message, throwableStr);
        synchronized (ModuleLogger.class) {
            File[] files = logFiles;
            if (files.length == 0 && appContext != null) {
                initLogFileLocked();
                files = logFiles;
            }
            if (files.length == 0) {
                enqueuePendingFileLineLocked(line);
                return;
            }
            flushPendingFileLinesLocked(files);
            appendLineToFilesLocked(files, line);
        }
    }

    public static void appendPersistedEntryToFile(Context context, PersistentLogStore.LogEntry entry) {
        if (context == null || entry == null) {
            return;
        }
        init(context);
        PersistentLogStore.LogEntry normalized = entry.sanitize();
        writeToFile(normalized.level, normalized.category, normalized.tag, normalized.message, normalized.throwable);
    }

    private static void addPreparedLogFile(List<File> prepared, Set<String> seenPaths, File candidate) {
        if (candidate == null) {
            return;
        }
        try {
            File file = candidate.getCanonicalFile();
            String path = file.getAbsolutePath();
            if (!seenPaths.add(path)) {
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                logWarn("Persistent log directory unavailable: " + parent.getAbsolutePath());
                return;
            }
            if (!file.exists() && !file.createNewFile()) {
                logWarn("Persistent log file unavailable: " + path);
                return;
            }
            FileWriter writer = new FileWriter(file, true);
            try {
                writer.write("");
            } finally {
                writer.close();
            }
            prepared.add(file);
        } catch (IOException | RuntimeException exception) {
            logWarn("Persistent log unavailable at " + candidate.getAbsolutePath() + ": " + exception.getMessage());
        }
    }

    private static String formatLine(String level, String category, String tag, String message, String throwableStr) {
        String timestamp;
        synchronized (DATE_FORMAT) {
            timestamp = DATE_FORMAT.format(new Date());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp).append(" ").append(level).append("/");
        sb.append(category).append(": ").append(tag).append(": ").append(message);
        if (throwableStr != null && !throwableStr.isEmpty()) {
            sb.append("\n").append(throwableStr);
        }
        sb.append("\n");
        return sb.toString();
    }

    private static void enqueuePendingFileLineLocked(String line) {
        if (pendingFileLines.size() >= MAX_PENDING_FILE_LINES) {
            pendingFileLines.removeFirst();
        }
        pendingFileLines.addLast(line);
    }

    private static void flushPendingFileLinesLocked(File[] files) {
        if (pendingFileLines.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        while (!pendingFileLines.isEmpty()) {
            sb.append(pendingFileLines.removeFirst());
        }
        appendLineToFilesLocked(files, sb.toString());
    }

    private static void appendLineToFilesLocked(File[] files, String line) {
        for (File file : files) {
            try {
                FileWriter writer = new FileWriter(file, true);
                try {
                    writer.write(line);
                } finally {
                    writer.close();
                }
            } catch (IOException | RuntimeException exception) {
                logError("Failed to write to log file " + file.getAbsolutePath(), exception);
            }
        }
    }

    private static String describeLogFiles(File[] files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(files[i].getAbsolutePath());
        }
        return sb.toString();
    }

    public static void info(String category, String tag, String message) {
        logInfo("[" + category + "] " + tag + ": " + message);
        if (xposedModule != null) {
            xposedModule.log(Log.INFO, TAG, "[" + category + "] " + tag + ": " + message);
        }
        writeToFile("INFO", category, tag, message, null);
        persist("INFO", category, tag, message, (String) null);
    }

    public static void warn(String category, String tag, String message) {
        logWarn("[" + category + "] " + tag + ": " + message);
        if (xposedModule != null) {
            xposedModule.log(Log.WARN, TAG, "[" + category + "] " + tag + ": " + message);
        }
        writeToFile("WARN", category, tag, message, null);
        persist("WARN", category, tag, message, (String) null);
    }

    public static void error(String category, String tag, String message, Throwable throwable) {
        logError("[" + category + "] " + tag + ": " + message, throwable);
        if (xposedModule != null) {
            xposedModule.log(Log.ERROR, TAG, "[" + category + "] " + tag + ": " + message, throwable);
        }
        writeToFile("ERROR", category, tag, message, throwableToString(throwable));
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

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (RuntimeException ignored) {
        }
    }

    private static void logError(String message, Throwable throwable) {
        try {
            Log.e(TAG, message, throwable);
        } catch (RuntimeException ignored) {
        }
    }
}
