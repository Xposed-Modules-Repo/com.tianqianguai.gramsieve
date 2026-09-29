package com.tianqianguai.gramsieve.module;

/** Pure state holder for a user-requested, restartable download. */
final class ReliableDownloadState {
    private boolean desired;
    private long generation;
    private long downloadedBytes;
    private long lastProgressAtMs;

    synchronized long start(long nowMs) {
        desired = true;
        generation++;
        downloadedBytes = 0L;
        lastProgressAtMs = nowMs;
        return generation;
    }

    synchronized void cancel() {
        desired = false;
        generation++;
    }

    synchronized boolean progress(long bytes, long nowMs) {
        if (!desired || bytes <= downloadedBytes) {
            return false;
        }
        downloadedBytes = bytes;
        lastProgressAtMs = nowMs;
        return true;
    }

    synchronized void markAttempt(long expectedGeneration, long nowMs) {
        if (desired && generation == expectedGeneration) {
            lastProgressAtMs = nowMs;
        }
    }

    synchronized boolean shouldRecover(long expectedGeneration, long nowMs, long stallTimeoutMs) {
        return desired && generation == expectedGeneration
                && nowMs - lastProgressAtMs >= stallTimeoutMs;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return desired && generation == expectedGeneration;
    }

    synchronized long generation() {
        return generation;
    }

    synchronized long downloadedBytes() {
        return downloadedBytes;
    }
}
