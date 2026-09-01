package com.tianqianguai.gramsieve.module;

/**
 * Global time-window budget for FilterUiTrace. It keeps layout diagnostics useful without
 * allowing a scroll through many filtered rows to become a synchronous file-write burst.
 */
final class UiTraceRateLimiter {
    static final long WINDOW_MS = 10_000L;
    static final int MAX_EVENTS_PER_WINDOW = 12;

    private long windowStartMs = Long.MIN_VALUE;
    private int emittedInWindow;
    private int suppressedInWindow;

    synchronized Permit acquire(long nowMs) {
        long now = Math.max(0L, nowMs);
        int summary = 0;
        if (windowStartMs == Long.MIN_VALUE || now - windowStartMs >= WINDOW_MS) {
            summary = suppressedInWindow;
            windowStartMs = now;
            emittedInWindow = 0;
            suppressedInWindow = 0;
        }
        if (emittedInWindow >= MAX_EVENTS_PER_WINDOW) {
            suppressedInWindow++;
            return Permit.suppressed();
        }
        emittedInWindow++;
        return Permit.emit(summary);
    }

    static final class Permit {
        final boolean emit;
        final int suppressed;

        private Permit(boolean emit, int suppressed) {
            this.emit = emit;
            this.suppressed = Math.max(0, suppressed);
        }

        static Permit emit(int suppressed) {
            return new Permit(true, suppressed);
        }

        static Permit suppressed() {
            return new Permit(false, 0);
        }
    }
}
