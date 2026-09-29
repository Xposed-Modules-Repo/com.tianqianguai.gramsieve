package com.tianqianguai.gramsieve.module;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SelfDeleteTracker {
    static final long DEFAULT_INTENT_TTL_MS = 30_000L;
    static final long DEFAULT_CLEANUP_MODE_MS = 5 * 60_000L;

    private final Clock clock;
    private final long intentTtlMs;
    private final Map<Long, Map<Integer, Long>> deleteIntents = new HashMap<>();
    private final Map<Long, Long> cleanupModeExpirations = new HashMap<>();

    SelfDeleteTracker() {
        this(System::currentTimeMillis, DEFAULT_INTENT_TTL_MS);
    }

    SelfDeleteTracker(Clock clock, long intentTtlMs) {
        this.clock = clock;
        this.intentTtlMs = intentTtlMs;
    }

    synchronized void recordUserDelete(long dialogId, List<?> messageIds) {
        if (dialogId == 0L || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long expiresAt = clock.now() + intentTtlMs;
        pruneExpiredLocked();
        for (Object messageIdObj : messageIds) {
            int messageId = Reflect.asInt(messageIdObj, 0);
            if (messageId > 0) {
                deleteIntents.computeIfAbsent(dialogId, ignored -> new HashMap<>())
                        .put(messageId, expiresAt);
            }
        }
    }

    synchronized long inferUniqueDialog(List<?> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0L;
        }
        pruneExpiredLocked();
        long uniqueDialogId = 0L;
        for (Map.Entry<Long, Map<Integer, Long>> entry : deleteIntents.entrySet()) {
            boolean sawMessage = false;
            boolean containsAll = true;
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId <= 0) {
                    continue;
                }
                sawMessage = true;
                if (!entry.getValue().containsKey(messageId)) {
                    containsAll = false;
                    break;
                }
            }
            if (!sawMessage || !containsAll) {
                continue;
            }
            if (uniqueDialogId != 0L && uniqueDialogId != entry.getKey()) {
                return 0L;
            }
            uniqueDialogId = entry.getKey();
        }
        return uniqueDialogId;
    }

    synchronized boolean allowsAny(long dialogId, List<?> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return false;
        }
        pruneExpiredLocked();
        if (dialogId != 0L) {
            if (isCleanupModeActiveLocked(dialogId)) {
                return true;
            }
            for (Object messageIdObj : messageIds) {
                int messageId = Reflect.asInt(messageIdObj, 0);
                if (messageId > 0 && isUserDeleteLocked(dialogId, messageId)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    synchronized boolean allowsAll(long dialogId, List<?> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return false;
        }
        pruneExpiredLocked();
        if (dialogId != 0L && isCleanupModeActiveLocked(dialogId)) {
            return true;
        }
        boolean sawMessage = false;
        for (Object messageIdObj : messageIds) {
            int messageId = Reflect.asInt(messageIdObj, 0);
            if (messageId <= 0) {
                continue;
            }
            sawMessage = true;
            if (dialogId == 0L) {
                return false;
            } else if (!isUserDeleteLocked(dialogId, messageId)) {
                return false;
            }
        }
        return sawMessage;
    }

    synchronized boolean enableCleanupMode(long dialogId, long durationMs) {
        if (dialogId == 0L) {
            return false;
        }
        cleanupModeExpirations.put(dialogId, clock.now() + durationMs);
        return true;
    }

    synchronized void disableCleanupMode(long dialogId) {
        cleanupModeExpirations.remove(dialogId);
    }

    synchronized boolean toggleCleanupMode(long dialogId, long durationMs) {
        if (isCleanupModeActive(dialogId)) {
            disableCleanupMode(dialogId);
            return false;
        }
        return enableCleanupMode(dialogId, durationMs);
    }

    synchronized boolean isCleanupModeActive(long dialogId) {
        pruneExpiredLocked();
        return isCleanupModeActiveLocked(dialogId);
    }

    private boolean isUserDeleteLocked(long dialogId, int messageId) {
        Map<Integer, Long> dialogIntents = deleteIntents.get(dialogId);
        Long expiresAt = dialogIntents == null ? null : dialogIntents.get(messageId);
        return expiresAt != null && expiresAt > clock.now();
    }

    private boolean isCleanupModeActiveLocked(long dialogId) {
        Long expiresAt = cleanupModeExpirations.get(dialogId);
        return expiresAt != null && expiresAt > clock.now();
    }

    private void pruneExpiredLocked() {
        long now = clock.now();
        for (Map<Integer, Long> dialogIntents : deleteIntents.values()) {
            dialogIntents.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
        deleteIntents.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        cleanupModeExpirations.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    interface Clock {
        long now();
    }
}
