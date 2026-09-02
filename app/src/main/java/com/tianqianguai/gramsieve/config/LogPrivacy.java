package com.tianqianguai.gramsieve.config;

import com.tianqianguai.gramsieve.BuildConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Build-aware formatting for values that may contain user or chat content. */
public final class LogPrivacy {
    private static final int DEBUG_PREVIEW_CHARS = 48;
    private static final Pattern QUOTED_SENSITIVE_FIELD = Pattern.compile(
            "(?<![A-Za-z0-9_])(chat|chatName|sender|senderName|text|messageText|caption|buttons|buttonText)=\\\"(.*?)\\\""
                    + "(?=\\s+[A-Za-z][A-Za-z0-9_]*=|\\r?$)",
            Pattern.MULTILINE
    );

    private LogPrivacy() {
    }

    public static boolean allowsSensitiveContent() {
        return BuildConfig.DEBUG;
    }

    public static String field(String name, String value) {
        return fieldForBuild(name, value, allowsSensitiveContent());
    }

    static String fieldForBuild(String name, String value, boolean allowSensitiveContent) {
        String safeName = safeFieldName(name);
        String normalized = normalize(value);
        if (!allowSensitiveContent) {
            return safeName + "Present=" + !normalized.isEmpty()
                    + " " + safeName + "Chars=" + normalized.length();
        }
        String preview = normalized;
        if (preview.length() > DEBUG_PREVIEW_CHARS) {
            preview = preview.substring(0, DEBUG_PREVIEW_CHARS) + "...";
        }
        return safeName + "=\"" + preview.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static String sanitizeMessage(String message) {
        return sanitizeMessageForBuild(message, allowsSensitiveContent());
    }

    static String sanitizeMessageForBuild(String message, boolean allowSensitiveContent) {
        String source = message == null ? "" : message;
        if (allowSensitiveContent || source.isEmpty()) {
            return source;
        }
        Matcher matcher = QUOTED_SENSITIVE_FIELD.matcher(source);
        StringBuffer sanitized = new StringBuffer(source.length());
        while (matcher.find()) {
            matcher.appendReplacement(
                    sanitized,
                    Matcher.quoteReplacement(fieldForBuild(matcher.group(1), matcher.group(2), false))
            );
        }
        matcher.appendTail(sanitized);
        return sanitized.toString();
    }

    public static int characterCount(String value) {
        return normalize(value).length();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String safeFieldName(String name) {
        String source = name == null ? "value" : name;
        StringBuilder safe = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                safe.append(c);
            }
        }
        return safe.length() == 0 ? "value" : safe.toString();
    }
}
