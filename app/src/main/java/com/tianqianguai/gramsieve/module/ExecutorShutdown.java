package com.tianqianguai.gramsieve.module;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded executor shutdown used while retiring one API-102 module generation. */
final class ExecutorShutdown {
    private ExecutorShutdown() {
    }

    static boolean gracefulThenNow(ExecutorService executor, long timeoutMs) {
        if (executor == null) {
            return true;
        }
        long boundedTimeout = Math.max(0L, timeoutMs);
        long gracefulMs = boundedTimeout * 2L / 3L;
        long forcedMs = boundedTimeout - gracefulMs;
        executor.shutdown();
        try {
            if (executor.awaitTermination(gracefulMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
            executor.shutdownNow();
            return executor.awaitTermination(forcedMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    static boolean now(ExecutorService executor, long timeoutMs) {
        if (executor == null) {
            return true;
        }
        executor.shutdownNow();
        try {
            return executor.awaitTermination(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }
}
