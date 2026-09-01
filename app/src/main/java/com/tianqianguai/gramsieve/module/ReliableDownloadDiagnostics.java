package com.tianqianguai.gramsieve.module;

import java.util.ArrayList;
import java.util.List;

/** Pure formatting and attribution helpers for reliable-download diagnostics. */
final class ReliableDownloadDiagnostics {
    static final String ORIGIN_STALL_RECOVERY = "GramSieve stall recovery";
    static final String ORIGIN_EXPLICIT_CANCEL = "GramSieve explicit-cancel reinforcement";
    static final String SOURCE_TELEGRAM_AFTER_X = "Telegram after explicit user X";
    static final String SOURCE_UNKNOWN = "unknown Telegram/external";

    private static final int MAX_VALUE_LENGTH = 96;
    private static final int MAX_STACK_FRAMES = 5;

    private ReliableDownloadDiagnostics() {
    }

    static ProgressMetadata observeProgress(long previousBytes, boolean previousBytesKnown,
                                            long previousAtMs, int previousEventCount,
                                            Long currentBytes, Long totalBytes, long nowMs,
                                            String threadName) {
        boolean currentKnown = currentBytes != null;
        long deltaBytes = currentKnown
                ? (previousBytesKnown ? currentBytes - previousBytes : currentBytes)
                : Long.MIN_VALUE;
        long elapsedMs = previousEventCount == 0
                ? -1L : Math.max(0L, nowMs - previousAtMs);
        return new ProgressMetadata(
                currentKnown,
                currentKnown ? currentBytes : 0L,
                totalBytes != null,
                totalBytes == null ? 0L : totalBytes,
                deltaBytes,
                elapsedMs,
                previousEventCount + 1,
                deltaBytes != Long.MIN_VALUE && deltaBytes > 0L,
                threadName == null || threadName.isEmpty() ? "unknown" : threadName
        );
    }

    static String cancelSource(String origin, boolean explicitlyCancelled) {
        if (ORIGIN_STALL_RECOVERY.equals(origin)) {
            return ORIGIN_STALL_RECOVERY;
        }
        if (ORIGIN_EXPLICIT_CANCEL.equals(origin)) {
            return ORIGIN_EXPLICIT_CANCEL;
        }
        return explicitlyCancelled ? SOURCE_TELEGRAM_AFTER_X : SOURCE_UNKNOWN;
    }

    static String argumentShape(Object[] args) {
        if (args == null) {
            return "null";
        }
        if (args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            Object value = args[i];
            if (value == null) {
                builder.append("null");
            } else {
                builder.append(value.getClass().getSimpleName());
            }
        }
        return builder.toString();
    }

    static String argumentSummary(Object[] args) {
        if (args == null) {
            return "null";
        }
        if (args.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(safeValue(args[i]));
        }
        return builder.append(']').toString();
    }

    static String filteredStackSummary(StackTraceElement[] stack) {
        if (stack == null || stack.length == 0) {
            return "none";
        }
        List<String> frames = new ArrayList<>();
        for (StackTraceElement element : stack) {
            if (element == null) {
                continue;
            }
            String className = element.getClassName();
            if (className.startsWith("java.")
                    || className.startsWith("android.")
                    || className.startsWith("dalvik.")
                    || className.startsWith("libcore.")
                    || className.startsWith("io.github.libxposed.")
                    || className.contains("ReliableDownload")) {
                continue;
            }
            frames.add(className + "." + element.getMethodName());
            if (frames.size() >= MAX_STACK_FRAMES) {
                break;
            }
        }
        return frames.isEmpty() ? "none" : String.join(" <- ", frames);
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }
        if (value instanceof String) {
            String text = (String) value;
            if (text.length() > MAX_VALUE_LENGTH) {
                text = text.substring(0, MAX_VALUE_LENGTH) + "...";
            }
            return "String(\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\")";
        }
        return value.getClass().getName();
    }

    static final class ProgressMetadata {
        final boolean currentBytesKnown;
        final long currentBytes;
        final boolean totalBytesKnown;
        final long totalBytes;
        final long deltaBytes;
        final long elapsedSincePreviousMs;
        final int eventCount;
        final boolean advanced;
        final String threadName;

        private ProgressMetadata(boolean currentBytesKnown, long currentBytes,
                                 boolean totalBytesKnown, long totalBytes, long deltaBytes,
                                 long elapsedSincePreviousMs, int eventCount, boolean advanced,
                                 String threadName) {
            this.currentBytesKnown = currentBytesKnown;
            this.currentBytes = currentBytes;
            this.totalBytesKnown = totalBytesKnown;
            this.totalBytes = totalBytes;
            this.deltaBytes = deltaBytes;
            this.elapsedSincePreviousMs = elapsedSincePreviousMs;
            this.eventCount = eventCount;
            this.advanced = advanced;
            this.threadName = threadName;
        }
    }
}
