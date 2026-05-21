package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.List;

public final class MessageSnapshot {
    public final long dialogId;
    public final long senderId;
    public final long messageId;
    public final String text;
    public final String caption;
    public final String buttonText;
    public final String senderName;
    public final String chatName;
    public final boolean hasInlineButtons;

    public MessageSnapshot(
            long dialogId,
            long senderId,
            long messageId,
            String text,
            String caption,
            String buttonText,
            String senderName,
            String chatName,
            boolean hasInlineButtons
    ) {
        this.dialogId = dialogId;
        this.senderId = senderId;
        this.messageId = messageId;
        this.text = normalized(text);
        this.caption = normalized(caption);
        this.buttonText = normalized(buttonText);
        this.senderName = normalized(senderName);
        this.chatName = normalized(chatName);
        this.hasInlineButtons = hasInlineButtons;
    }

    public List<String> valuesFor(FilterConfig.RuleTarget target) {
        List<String> values = new ArrayList<>();
        switch (target) {
            case TEXT:
                addTextTargetValues(values);
                break;
            case CAPTION:
                maybeAdd(values, caption);
                break;
            case BUTTONS:
                maybeAdd(values, buttonText);
                break;
            case SENDER:
                maybeAdd(values, senderName);
                maybeAdd(values, Long.toString(senderId));
                break;
            case CHAT:
                maybeAdd(values, chatName);
                maybeAdd(values, Long.toString(dialogId));
                break;
            case ANY:
            default:
                maybeAdd(values, text);
                maybeAdd(values, caption);
                maybeAdd(values, buttonText);
                maybeAdd(values, senderName);
                maybeAdd(values, chatName);
                maybeAdd(values, Long.toString(senderId));
                maybeAdd(values, Long.toString(dialogId));
                break;
        }
        return values;
    }

    public String combinedVisibleContent() {
        List<String> values = new ArrayList<>();
        addTextTargetValues(values);
        return String.join(" ", values).trim();
    }

    public String stableKey() {
        return dialogId
                + ":" + senderId
                + ":" + messageId
                + ":" + text.hashCode()
                + ":" + caption.hashCode()
                + ":" + buttonText.hashCode()
                + ":" + senderName.hashCode()
                + ":" + chatName.hashCode()
                + ":" + hasInlineButtons;
    }

    private static void maybeAdd(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private void addTextTargetValues(List<String> values) {
        maybeAdd(values, text);
        maybeAdd(values, caption);
        maybeAdd(values, buttonText);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
