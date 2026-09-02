package com.tianqianguai.gramsieve.module;

/** In-memory, content-free diagnostics for the native Telegram message deletion flow. */
final class MessageDeleteFlowDiagnostics {
    private int popupCount;
    private int alertEntryCount;
    private int alertReturnCount;
    private int controllerRequestCount;
    private int deleteRpcCount;
    private int originRecoveryCount;
    private int storageOriginCount;
    private int notificationOriginCount;
    private int networkOriginCount;
    private int nativeDispatchCount;
    private int deleteCallbackCount;
    private long lastUpdatedAtMs;
    private long lastAlertAtMs;
    private long lastAlertDialogId;
    private boolean lastAlertConsumed;
    private long lastDialogId;
    private int lastMessageId;
    private boolean lastDeleteItemPresent;
    private boolean lastDeleteItemClickable;
    private boolean lastDeleteItemHasListener;
    private int lastDeleteItemIndex = -1;
    private int lastMenuItemCount;
    private int lastPopupWidth;
    private int lastPopupHeight;
    private int lastAlertParameterCount;
    private int lastControllerMessageCount;
    private int lastRequestToken;
    private boolean lastCallbackSucceeded;
    private String lastRpcType = "";
    private String lastNetworkStage = "";
    private String lastResponseType = "";
    private int lastErrorCode;
    private String lastErrorText = "";

    synchronized void recordPopup(
            long dialogId,
            int messageId,
            boolean deleteItemPresent,
            boolean deleteItemClickable,
            boolean deleteItemHasListener,
            int deleteItemIndex,
            int menuItemCount,
            int popupWidth,
            int popupHeight
    ) {
        popupCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastDialogId = dialogId;
        lastMessageId = messageId;
        lastDeleteItemPresent = deleteItemPresent;
        lastDeleteItemClickable = deleteItemClickable;
        lastDeleteItemHasListener = deleteItemHasListener;
        lastDeleteItemIndex = deleteItemIndex;
        lastMenuItemCount = Math.max(0, menuItemCount);
        lastPopupWidth = Math.max(0, popupWidth);
        lastPopupHeight = Math.max(0, popupHeight);
    }

    synchronized void recordPopupSize(int popupWidth, int popupHeight) {
        lastUpdatedAtMs = System.currentTimeMillis();
        lastPopupWidth = Math.max(0, popupWidth);
        lastPopupHeight = Math.max(0, popupHeight);
    }

    synchronized void recordAlertEntry(long dialogId, int messageId, int parameterCount) {
        alertEntryCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastDialogId = dialogId;
        lastMessageId = messageId;
        lastAlertParameterCount = Math.max(0, parameterCount);
        lastAlertAtMs = lastUpdatedAtMs;
        lastAlertDialogId = dialogId;
        lastAlertConsumed = false;
    }

    synchronized void recordAlertReturn() {
        alertReturnCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordControllerRequest(long dialogId, int messageCount) {
        controllerRequestCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastDialogId = dialogId;
        lastControllerMessageCount = Math.max(0, messageCount);
    }

    synchronized boolean consumeRecentAlert(long dialogId, long maxAgeMs) {
        long ageMs = System.currentTimeMillis() - lastAlertAtMs;
        if (lastAlertConsumed || dialogId == 0L || dialogId != lastAlertDialogId
                || ageMs < 0L || ageMs > Math.max(0L, maxAgeMs)) {
            return false;
        }
        lastAlertConsumed = true;
        return true;
    }

    synchronized void recordDeleteRpc(String rpcType) {
        deleteRpcCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastRpcType = rpcType == null ? "" : rpcType;
    }

    synchronized void recordOriginRecovery() {
        originRecoveryCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordStorageOrigin() {
        storageOriginCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordNotificationOrigin() {
        notificationOriginCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordNetworkOrigin(String stage) {
        networkOriginCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastNetworkStage = stage == null ? "" : stage;
    }

    synchronized void recordNativeDispatch() {
        nativeDispatchCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordRequestToken(int requestToken) {
        lastRequestToken = Math.max(0, requestToken);
        lastUpdatedAtMs = System.currentTimeMillis();
    }

    synchronized void recordDeleteCallback(
            boolean succeeded,
            String responseType,
            int errorCode,
            String errorText
    ) {
        deleteCallbackCount++;
        lastUpdatedAtMs = System.currentTimeMillis();
        lastResponseType = responseType == null ? "" : responseType;
        lastErrorCode = errorCode;
        lastErrorText = errorText == null ? "" : errorText;
        lastCallbackSucceeded = succeeded;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                popupCount,
                alertEntryCount,
                alertReturnCount,
                controllerRequestCount,
                deleteRpcCount,
                originRecoveryCount,
                storageOriginCount,
                notificationOriginCount,
                networkOriginCount,
                nativeDispatchCount,
                deleteCallbackCount,
                lastUpdatedAtMs,
                lastDialogId,
                lastMessageId,
                lastDeleteItemPresent,
                lastDeleteItemClickable,
                lastDeleteItemHasListener,
                lastDeleteItemIndex,
                lastMenuItemCount,
                lastPopupWidth,
                lastPopupHeight,
                lastAlertParameterCount,
                lastControllerMessageCount,
                lastRequestToken,
                lastCallbackSucceeded,
                lastRpcType,
                lastNetworkStage,
                lastResponseType,
                lastErrorCode,
                lastErrorText
        );
    }

    static final class Snapshot {
        final int popupCount;
        final int alertEntryCount;
        final int alertReturnCount;
        final int controllerRequestCount;
        final int deleteRpcCount;
        final int originRecoveryCount;
        final int storageOriginCount;
        final int notificationOriginCount;
        final int networkOriginCount;
        final int nativeDispatchCount;
        final int deleteCallbackCount;
        final long lastUpdatedAtMs;
        final long lastDialogId;
        final int lastMessageId;
        final boolean lastDeleteItemPresent;
        final boolean lastDeleteItemClickable;
        final boolean lastDeleteItemHasListener;
        final int lastDeleteItemIndex;
        final int lastMenuItemCount;
        final int lastPopupWidth;
        final int lastPopupHeight;
        final int lastAlertParameterCount;
        final int lastControllerMessageCount;
        final int lastRequestToken;
        final boolean lastCallbackSucceeded;
        final String lastRpcType;
        final String lastNetworkStage;
        final String lastResponseType;
        final int lastErrorCode;
        final String lastErrorText;

        Snapshot(
                int popupCount,
                int alertEntryCount,
                int alertReturnCount,
                int controllerRequestCount,
                int deleteRpcCount,
                int originRecoveryCount,
                int storageOriginCount,
                int notificationOriginCount,
                int networkOriginCount,
                int nativeDispatchCount,
                int deleteCallbackCount,
                long lastUpdatedAtMs,
                long lastDialogId,
                int lastMessageId,
                boolean lastDeleteItemPresent,
                boolean lastDeleteItemClickable,
                boolean lastDeleteItemHasListener,
                int lastDeleteItemIndex,
                int lastMenuItemCount,
                int lastPopupWidth,
                int lastPopupHeight,
                int lastAlertParameterCount,
                int lastControllerMessageCount,
                int lastRequestToken,
                boolean lastCallbackSucceeded,
                String lastRpcType,
                String lastNetworkStage,
                String lastResponseType,
                int lastErrorCode,
                String lastErrorText
        ) {
            this.popupCount = popupCount;
            this.alertEntryCount = alertEntryCount;
            this.alertReturnCount = alertReturnCount;
            this.controllerRequestCount = controllerRequestCount;
            this.deleteRpcCount = deleteRpcCount;
            this.originRecoveryCount = originRecoveryCount;
            this.storageOriginCount = storageOriginCount;
            this.notificationOriginCount = notificationOriginCount;
            this.networkOriginCount = networkOriginCount;
            this.nativeDispatchCount = nativeDispatchCount;
            this.deleteCallbackCount = deleteCallbackCount;
            this.lastUpdatedAtMs = lastUpdatedAtMs;
            this.lastDialogId = lastDialogId;
            this.lastMessageId = lastMessageId;
            this.lastDeleteItemPresent = lastDeleteItemPresent;
            this.lastDeleteItemClickable = lastDeleteItemClickable;
            this.lastDeleteItemHasListener = lastDeleteItemHasListener;
            this.lastDeleteItemIndex = lastDeleteItemIndex;
            this.lastMenuItemCount = lastMenuItemCount;
            this.lastPopupWidth = lastPopupWidth;
            this.lastPopupHeight = lastPopupHeight;
            this.lastAlertParameterCount = lastAlertParameterCount;
            this.lastControllerMessageCount = lastControllerMessageCount;
            this.lastRequestToken = lastRequestToken;
            this.lastCallbackSucceeded = lastCallbackSucceeded;
            this.lastRpcType = lastRpcType;
            this.lastNetworkStage = lastNetworkStage;
            this.lastResponseType = lastResponseType;
            this.lastErrorCode = lastErrorCode;
            this.lastErrorText = lastErrorText;
        }
    }

}
