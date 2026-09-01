package com.tianqianguai.gramsieve.config;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Bounded access to the host-process persistent log.
 *
 * <p>This class intentionally reads the host application's app-specific file first. The module
 * process writes the same file under Telegram's package, so the in-host settings panel can read
 * it without root or a storage permission. The module application's private file is only used as
 * a fallback when the host file is unavailable.</p>
 */
public final class LogFileSupport {
    public static final String LOG_DIRECTORY = "GramSieve";
    public static final String LOG_FILE_NAME = "gramsieve.log";
    public static final int DEFAULT_TAIL_BYTES = 64 * 1024;
    public static final int MAX_TAIL_BYTES = 128 * 1024;
    public static final int DEFAULT_TAIL_LINES = 300;
    public static final int MAX_TAIL_LINES = 1200;
    private static final int COPY_BUFFER_BYTES = 32 * 1024;

    private LogFileSupport() {
    }

    /** Returns the preferred existing log, or the preferred host path if no file exists yet. */
    public static File preferredLogFile(Context context) {
        if (context == null) {
            return null;
        }
        File external = externalLogFile(context);
        if (external != null && external.isFile() && external.canRead()) {
            return external;
        }
        File internal = internalLogFile(context);
        if (internal != null && internal.isFile() && internal.canRead()) {
            return internal;
        }
        return external != null ? external : internal;
    }

    public static File externalLogFile(Context context) {
        if (context == null) {
            return null;
        }
        File externalDir = context.getExternalFilesDir(null);
        return externalDir == null
                ? null
                : new File(new File(externalDir, LOG_DIRECTORY), LOG_FILE_NAME);
    }

    public static File internalLogFile(Context context) {
        if (context == null || context.getFilesDir() == null) {
            return null;
        }
        return new File(context.getFilesDir(), LOG_FILE_NAME);
    }

    /**
     * Reads a tail on the caller's thread. UI and broadcast callers must invoke this from a
     * worker thread because the source can be several megabytes after rotation is disabled by a
     * host ROM.
     */
    public static TailResult readTail(Context context, int requestedLines, int requestedBytes) {
        File file = preferredLogFile(context);
        if (file == null) {
            return TailResult.failure("No application context or log path");
        }
        return readTail(file, requestedLines, requestedBytes);
    }

    public static TailResult readTail(File file, int requestedLines, int requestedBytes) {
        int maxLines = normalizeLineLimit(requestedLines);
        int maxBytes = normalizeByteLimit(requestedBytes);
        if (file == null) {
            return TailResult.failure("Log path is unavailable");
        }
        if (!file.isFile() || !file.canRead()) {
            return TailResult.missing(file.getAbsolutePath());
        }
        try {
            long totalBytes = file.length();
            long offset = Math.max(0L, totalBytes - maxBytes);
            int readCapacity = (int) Math.min(Integer.MAX_VALUE, totalBytes - offset);
            byte[] bytes = new byte[readCapacity];
            int read = 0;
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                while (read < bytes.length) {
                    int count = input.read(bytes, read, bytes.length - read);
                    if (count < 0) {
                        break;
                    }
                    if (count == 0) {
                        continue;
                    }
                    read += count;
                }
            }
            String decoded = decodeUtf8Tail(bytes, 0, read);
            boolean byteTruncated = offset > 0L;
            if (byteTruncated) {
                int firstLineBreak = decoded.indexOf('\n');
            if (firstLineBreak >= 0 && firstLineBreak + 1 < decoded.length()) {
                decoded = decoded.substring(firstLineBreak + 1);
            } else if (firstLineBreak >= 0) {
                decoded = "";
                }
            }
            LineTail lineTail = limitLines(decoded, maxLines);
            boolean truncated = byteTruncated || lineTail.truncated;
            String text = lineTail.text;
            if (truncated) {
                text = "[... log tail truncated ...]\n" + text;
            }
            return TailResult.success(
                    file.getAbsolutePath(),
                    totalBytes,
                    read,
                    lineTail.lineCount,
                    truncated,
                    text
            );
        } catch (IOException | RuntimeException exception) {
            return TailResult.failure(file.getAbsolutePath() + ": " + exception.getClass().getSimpleName());
        }
    }

    /** Pure, bounded tail operation used by JVM tests and by file-read callers. */
    public static String tailText(String input, int requestedLines, int requestedBytes) {
        String source = input == null ? "" : input;
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        int maxBytes = normalizeByteLimit(requestedBytes);
        int offset = Math.max(0, bytes.length - maxBytes);
        String decoded = decodeUtf8Tail(bytes, offset, bytes.length - offset);
        if (offset > 0) {
            int firstLineBreak = decoded.indexOf('\n');
            if (firstLineBreak >= 0) {
                decoded = decoded.substring(Math.min(decoded.length(), firstLineBreak + 1));
            }
        }
        LineTail lineTail = limitLines(decoded, normalizeLineLimit(requestedLines));
        if (offset > 0 || lineTail.truncated) {
            return "[... log tail truncated ...]\n" + lineTail.text;
        }
        return lineTail.text;
    }

    public static String exportFileName(long timestampEpochMs) {
        long safeTimestamp = timestampEpochMs <= 0L ? System.currentTimeMillis() : timestampEpochMs;
        synchronized (LogFileSupport.class) {
            return "gramsieve-log-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                    .format(new Date(safeTimestamp))
                    + ".log";
        }
    }

    /**
     * Streams the complete source file to Downloads/GramSieve through MediaStore. No root and no
     * legacy storage permission are required on the project's minSdk (33).
     */
    public static ExportResult exportToDownloads(Context context) {
        File source = preferredLogFile(context);
        if (context == null) {
            return ExportResult.failure("Application context is unavailable");
        }
        if (source == null || !source.isFile() || !source.canRead()) {
            return ExportResult.failure("Persistent log file is unavailable");
        }
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) {
            return ExportResult.failure("Content resolver is unavailable");
        }
        String displayName = exportFileName(System.currentTimeMillis());
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/GramSieve");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return ExportResult.failure("MediaStore did not create a Downloads entry");
            }
            try (InputStream input = new BufferedInputStream(new FileInputStream(source));
                 OutputStream output = new BufferedOutputStream(resolver.openOutputStream(uri))) {
                if (output == null) {
                    throw new IOException("MediaStore output stream is unavailable");
                }
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw new IOException("MediaStore could not finalize the Downloads entry");
            }
            return ExportResult.success(displayName, uri, source.getAbsolutePath(), source.length());
        } catch (IOException | RuntimeException exception) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // Keep the original export error for the caller.
                }
            }
            return ExportResult.failure(exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : ": " + exception.getMessage()));
        }
    }

    public static int normalizeLineLimit(int requestedLines) {
        if (requestedLines <= 0) {
            return DEFAULT_TAIL_LINES;
        }
        return Math.min(requestedLines, MAX_TAIL_LINES);
    }

    public static int normalizeByteLimit(int requestedBytes) {
        if (requestedBytes <= 0) {
            return DEFAULT_TAIL_BYTES;
        }
        return Math.min(requestedBytes, MAX_TAIL_BYTES);
    }

    private static String decodeUtf8Tail(byte[] bytes, int offset, int length) {
        int sourceLength = bytes == null ? 0 : bytes.length;
        int safeOffset = Math.max(0, Math.min(offset, sourceLength));
        int safeLength = Math.max(0, Math.min(length, sourceLength - safeOffset));
        int start = safeOffset;
        int end = safeOffset + safeLength;
        while (start < end && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return new String(bytes == null ? new byte[0] : bytes, start, end - start,
                StandardCharsets.UTF_8);
    }

    private static LineTail limitLines(String input, int maxLines) {
        if (input == null || input.isEmpty()) {
            return new LineTail("", 0, false);
        }
        int lineCount = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                lineCount++;
            }
        }
        int logicalLineCount = lineCount + (input.endsWith("\n") ? 0 : 1);
        if (logicalLineCount <= maxLines) {
            return new LineTail(input, logicalLineCount, false);
        }
        int keepStart = 0;
        int seen = 0;
        int lastIndex = input.length() - 1;
        for (int i = lastIndex; i >= 0; i--) {
            if (input.charAt(i) == '\n') {
                if (i == lastIndex) {
                    continue;
                }
                seen++;
                if (seen == maxLines) {
                    keepStart = i + 1;
                    break;
                }
            }
        }
        String limited = input.substring(Math.min(keepStart, input.length()));
        return new LineTail(limited, maxLines, true);
    }

    private static final class LineTail {
        final String text;
        final int lineCount;
        final boolean truncated;

        LineTail(String text, int lineCount, boolean truncated) {
            this.text = text;
            this.lineCount = lineCount;
            this.truncated = truncated;
        }
    }

    public static final class TailResult {
        public final boolean available;
        public final String sourcePath;
        public final long totalBytes;
        public final int returnedBytes;
        public final int lineCount;
        public final boolean truncated;
        public final String text;
        public final String error;

        private TailResult(boolean available, String sourcePath, long totalBytes, int returnedBytes,
                           int lineCount, boolean truncated, String text, String error) {
            this.available = available;
            this.sourcePath = sourcePath == null ? "" : sourcePath;
            this.totalBytes = Math.max(0L, totalBytes);
            this.returnedBytes = Math.max(0, returnedBytes);
            this.lineCount = Math.max(0, lineCount);
            this.truncated = truncated;
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
        }

        static TailResult success(String sourcePath, long totalBytes, int returnedBytes,
                                  int lineCount, boolean truncated, String text) {
            return new TailResult(true, sourcePath, totalBytes, returnedBytes, lineCount, truncated, text, "");
        }

        static TailResult missing(String sourcePath) {
            return new TailResult(false, sourcePath, 0L, 0, 0, false, "", "Log file is not available");
        }

        static TailResult failure(String error) {
            return new TailResult(false, "", 0L, 0, 0, false, "", error);
        }
    }

    public static final class ExportResult {
        public final boolean exported;
        public final String displayName;
        public final Uri uri;
        public final String sourcePath;
        public final long bytes;
        public final String error;

        private ExportResult(boolean exported, String displayName, Uri uri, String sourcePath,
                             long bytes, String error) {
            this.exported = exported;
            this.displayName = displayName == null ? "" : displayName;
            this.uri = uri;
            this.sourcePath = sourcePath == null ? "" : sourcePath;
            this.bytes = Math.max(0L, bytes);
            this.error = error == null ? "" : error;
        }

        static ExportResult success(String displayName, Uri uri, String sourcePath, long bytes) {
            return new ExportResult(true, displayName, uri, sourcePath, bytes, "");
        }

        static ExportResult failure(String error) {
            return new ExportResult(false, "", null, "", 0L, error);
        }
    }
}
