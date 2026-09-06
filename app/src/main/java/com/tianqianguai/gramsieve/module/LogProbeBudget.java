package com.tianqianguai.gramsieve.module;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps routine hot-path diagnostics bounded while retaining a few useful samples. */
final class LogProbeBudget {
    enum Permit {
        SAMPLE,
        SUPPRESSION_NOTICE,
        DROP
    }

    private final int sampleLimit;
    private final AtomicInteger remaining;

    LogProbeBudget(int sampleLimit) {
        if (sampleLimit < 0) {
            throw new IllegalArgumentException("sampleLimit must not be negative");
        }
        this.sampleLimit = sampleLimit;
        this.remaining = new AtomicInteger(sampleLimit);
    }

    Permit acquire() {
        while (true) {
            int current = remaining.get();
            if (current < 0) {
                return Permit.DROP;
            }
            if (!remaining.compareAndSet(current, current - 1)) {
                continue;
            }
            return current > 0 ? Permit.SAMPLE : Permit.SUPPRESSION_NOTICE;
        }
    }

    int sampleLimit() {
        return sampleLimit;
    }

    void reset() {
        remaining.set(sampleLimit);
    }
}
