package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LogTimeRangeTest {
    @Test
    public void localMinuteBoundsAreInclusiveThroughTheMinute() {
        LogTimeRange range = LogTimeRange.parse(
                "2026-09-02 10:00",
                "2026-09-02 10:30"
        );

        assertTrue(range.includes(timestamp("2026-09-02 10:00:00.000")));
        assertTrue(range.includes(timestamp("2026-09-02 10:30:59.999")));
        assertFalse(range.includes(timestamp("2026-09-02 10:31:00.000")));
    }

    @Test
    public void dateOnlyEndIncludesTheWholeDay() {
        LogTimeRange range = LogTimeRange.parse("2026-09-02", "2026-09-02");

        assertTrue(range.includes(timestamp("2026-09-02 00:00:00.000")));
        assertTrue(range.includes(timestamp("2026-09-02 23:59:59.999")));
        assertFalse(range.includes(timestamp("2026-09-03 00:00:00.000")));
    }

    @Test
    public void epochSecondsAndMillisecondsAreAccepted() {
        LogTimeRange seconds = LogTimeRange.parse("100", "101");
        LogTimeRange millis = LogTimeRange.parse("100000000000", "100000000999");

        assertEquals(100_000L, seconds.fromInclusiveMs);
        assertEquals(101_000L, seconds.toInclusiveMs);
        assertEquals(100_000_000_000L, millis.fromInclusiveMs);
        assertEquals(100_000_000_999L, millis.toInclusiveMs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void reversedRangeIsRejected() {
        LogTimeRange.parse("2026-09-02 11:00", "2026-09-02 10:00");
    }

    private static long timestamp(String value) {
        return LogTimeRange.timestampFromLogLine(value + " INFO/test: tag: sample");
    }
}
