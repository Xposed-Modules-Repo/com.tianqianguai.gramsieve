package com.tianqianguai.gramsieve.config;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** Inclusive local-time bounds for persistent GramSieve log entries. */
public final class LogTimeRange {
    private static final DateTimeFormatter LOG_TIMESTAMP = formatter("uuuu-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter SECOND_INPUT = formatter("uuuu-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MINUTE_INPUT = formatter("uuuu-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_INPUT = formatter("uuuu-MM-dd");
    private static final long EPOCH_SECONDS_CUTOFF = 100_000_000_000L;

    public final long fromInclusiveMs;
    public final long toInclusiveMs;

    private LogTimeRange(long fromInclusiveMs, long toInclusiveMs) {
        if (fromInclusiveMs > toInclusiveMs) {
            throw new IllegalArgumentException("Start time must not be after end time");
        }
        this.fromInclusiveMs = fromInclusiveMs;
        this.toInclusiveMs = toInclusiveMs;
    }

    public static LogTimeRange unbounded() {
        return new LogTimeRange(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static LogTimeRange of(long fromInclusiveMs, long toInclusiveMs) {
        return new LogTimeRange(fromInclusiveMs, toInclusiveMs);
    }

    public static LogTimeRange parse(String fromInput, String toInput) {
        long from = parseBound(fromInput, false);
        long to = parseBound(toInput, true);
        return new LogTimeRange(from, to);
    }

    public boolean includes(long timestampMs) {
        return timestampMs >= fromInclusiveMs && timestampMs <= toInclusiveMs;
    }

    public boolean isUnbounded() {
        return fromInclusiveMs == Long.MIN_VALUE && toInclusiveMs == Long.MAX_VALUE;
    }

    public String fromDisplay() {
        return fromInclusiveMs == Long.MIN_VALUE ? "" : formatInput(fromInclusiveMs);
    }

    public String toDisplay() {
        return toInclusiveMs == Long.MAX_VALUE ? "" : formatInput(toInclusiveMs);
    }

    public static String formatInput(long timestampMs) {
        return SECOND_INPUT.format(Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    public static long startOfToday(long timestampMs) {
        return Instant.ofEpochMilli(timestampMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    public static long timestampFromLogLine(String line) {
        if (line == null || line.length() < 23) {
            return Long.MIN_VALUE;
        }
        try {
            LocalDateTime timestamp = LocalDateTime.parse(line.substring(0, 23), LOG_TIMESTAMP);
            return timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static long parseBound(String raw, boolean endInclusive) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return endInclusive ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        if (value.matches("[0-9]{1,17}")) {
            try {
                long parsed = Long.parseLong(value);
                return parsed < EPOCH_SECONDS_CUTOFF ? Math.multiplyExact(parsed, 1_000L) : parsed;
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid epoch time: " + value, exception);
            }
        }

        String normalized = value.replace('T', ' ');
        try {
            if (normalized.length() == 10) {
                LocalDate date = LocalDate.parse(normalized, DATE_INPUT);
                if (endInclusive) {
                    return date.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli() - 1L;
                }
                return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            LocalDateTime dateTime;
            if (normalized.length() == 16) {
                dateTime = LocalDateTime.parse(normalized, MINUTE_INPUT);
                if (endInclusive) {
                    dateTime = dateTime.plusMinutes(1).minusNanos(1_000_000L);
                }
            } else if (normalized.length() == 19) {
                dateTime = LocalDateTime.parse(normalized, SECOND_INPUT);
                if (endInclusive) {
                    dateTime = dateTime.plusSeconds(1).minusNanos(1_000_000L);
                }
            } else if (normalized.length() == 23) {
                dateTime = LocalDateTime.parse(normalized, LOG_TIMESTAMP);
            } else {
                throw new IllegalArgumentException("Expected yyyy-MM-dd[ HH:mm[:ss[.SSS]]]");
            }
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid local time: " + value, exception);
        }
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US)
                .withResolverStyle(ResolverStyle.STRICT);
    }
}
