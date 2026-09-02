package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LogFileSupportTest {
    @Test
    public void tailTextKeepsNewestLinesAndMarksByteTruncation() {
        String tail = LogFileSupport.tailText("one\ntwo\nthree\nfour\n", 20, 8);

        assertTrue(tail.startsWith("[... log tail truncated ...]"));
        assertTrue(tail.contains("four"));
        assertFalse(tail.contains("one"));
    }

    @Test
    public void tailTextLimitsLinesWithoutDroppingUtf8Characters() {
        String tail = LogFileSupport.tailText("第一\n第二\n第三\n", 2, 1024);

        assertTrue(tail.startsWith("[... log tail truncated ...]"));
        assertTrue(tail.endsWith("第二\n第三\n"));
    }

    @Test
    public void tailTextCountsFinalLineWithoutNewline() {
        String tail = LogFileSupport.tailText("one\ntwo\nthree", 2, 1024);

        assertTrue(tail.startsWith("[... log tail truncated ...]"));
        assertTrue(tail.endsWith("two\nthree"));
    }

    @Test
    public void limitsAreBoundedAndDefaultsAreStable() {
        assertEquals(LogFileSupport.DEFAULT_TAIL_LINES,
                LogFileSupport.normalizeLineLimit(0));
        assertEquals(LogFileSupport.MAX_TAIL_LINES,
                LogFileSupport.normalizeLineLimit(Integer.MAX_VALUE));
        assertEquals(LogFileSupport.DEFAULT_TAIL_BYTES,
                LogFileSupport.normalizeByteLimit(0));
        assertEquals(LogFileSupport.MAX_TAIL_BYTES,
                LogFileSupport.normalizeByteLimit(Integer.MAX_VALUE));
    }

    @Test
    public void exportFileNameIsSafeAndTimestamped() {
        String name = LogFileSupport.exportFileName(0L);

        assertTrue(name.matches("gramsieve-log-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.log"));
        assertFalse(name.contains(":"));
    }

    @Test
    public void rangeFilterKeepsMatchingEntryContinuationLines() {
        String input = "2026-09-02 09:59:59.999 INFO/hook: GramSieve: before\n"
                + "2026-09-02 10:05:00.000 ERROR/hook: GramSieve: selected\n"
                + "java.lang.IllegalStateException: selected stack\n"
                + "    at sample.Trace.run(Trace.java:1)\n"
                + "2026-09-02 10:31:00.000 INFO/hook: GramSieve: after\n";

        String filtered = LogFileSupport.filterTextByTimeRange(
                input,
                LogTimeRange.parse("2026-09-02 10:00", "2026-09-02 10:30")
        );

        assertTrue(filtered.contains("selected"));
        assertTrue(filtered.contains("selected stack"));
        assertTrue(filtered.contains("sample.Trace.run"));
        assertFalse(filtered.contains("before"));
        assertFalse(filtered.contains("after"));
    }

    @Test
    public void unboundedRangePreservesAllLogicalLines() {
        String input = "plain preface\n"
                + "2026-09-02 10:05:00.000 INFO/hook: GramSieve: selected\n";

        assertEquals(input, LogFileSupport.filterTextByTimeRange(
                input, LogTimeRange.unbounded()));
    }

    @Test
    public void rangeFilePreviewReportsAllMatchesWhileBoundingReturnedText() throws Exception {
        File file = File.createTempFile("gramsieve-range", ".log");
        try {
            String input = "2026-09-02 10:00:00.000 INFO/hook: GramSieve: first matching entry\n"
                    + "first continuation line that makes the preview longer\n"
                    + "2026-09-02 10:01:00.000 INFO/hook: GramSieve: second matching entry\n"
                    + "second continuation line that makes the preview longer\n"
                    + "2026-09-02 11:00:00.000 INFO/hook: GramSieve: outside\n";
            Files.writeString(file.toPath(), input, StandardCharsets.UTF_8);

            LogFileSupport.RangeResult result = LogFileSupport.readRange(
                    file,
                    LogTimeRange.parse("2026-09-02 10:00", "2026-09-02 10:05"),
                    96
            );

            assertTrue(result.available);
            assertEquals(2, result.matchedEntries);
            assertTrue(result.truncated);
            assertFalse(result.text.contains("outside"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void rangePreviewLimitsAreBounded() {
        assertEquals(LogFileSupport.DEFAULT_RANGE_BYTES,
                LogFileSupport.normalizeRangeByteLimit(0));
        assertEquals(LogFileSupport.MAX_RANGE_BYTES,
                LogFileSupport.normalizeRangeByteLimit(Integer.MAX_VALUE));
    }
}
