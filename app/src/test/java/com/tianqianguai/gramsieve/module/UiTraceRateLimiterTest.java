package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UiTraceRateLimiterTest {
    @Test
    public void capsEventsAndReportsSuppressedCountOnNextWindow() {
        UiTraceRateLimiter limiter = new UiTraceRateLimiter();
        for (int i = 0; i < UiTraceRateLimiter.MAX_EVENTS_PER_WINDOW; i++) {
            assertTrue(limiter.acquire(1_000L).emit);
        }
        assertFalse(limiter.acquire(1_000L).emit);
        assertFalse(limiter.acquire(1_001L).emit);

        UiTraceRateLimiter.Permit next = limiter.acquire(11_000L);

        assertTrue(next.emit);
        assertEquals(2, next.suppressed);
    }

    @Test
    public void newWindowWithoutSuppressionHasNoSummary() {
        UiTraceRateLimiter limiter = new UiTraceRateLimiter();

        UiTraceRateLimiter.Permit first = limiter.acquire(1_000L);
        UiTraceRateLimiter.Permit next = limiter.acquire(11_000L);

        assertTrue(first.emit);
        assertTrue(next.emit);
        assertEquals(0, next.suppressed);
    }
}
