package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
