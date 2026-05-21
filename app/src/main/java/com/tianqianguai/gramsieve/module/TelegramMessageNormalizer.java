package com.tianqianguai.gramsieve.module;

import com.tianqianguai.gramsieve.core.MessageSnapshot;

import java.util.List;
import java.util.regex.Pattern;

final class TelegramMessageNormalizer {
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TelegramMessageNormalizer() {
    }

    static MessageSnapshot normalize(Object cell, Object messageObject) {
        if (messageObject == null) {
            return null;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
        long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
        int currentAccount = Reflect.asInt(Reflect.field(messageObject, "currentAccount"), 0);
        Object messageOwner = Reflect.field(messageObject, "messageOwner");

        String text = normalizeText(Reflect.field(messageObject, "messageText"));
        String caption = normalizeText(Reflect.field(messageObject, "caption"));
        String buttonText = collectInlineButtons(messageOwner);
        long senderId = resolveSenderId(messageObject, messageOwner);
        String senderName = resolveSenderName(cell, messageObject, currentAccount, senderId);
        String chatName = resolveChatName(cell, messageObject, currentAccount, dialogId);
        PinnedMessageContext pinnedContext = resolvePinnedMessageContext(cell, messageObject, currentAccount);
        text = mergeNormalized(text, pinnedContext.text);
        caption = mergeNormalized(caption, pinnedContext.caption);
        buttonText = mergeNormalized(buttonText, pinnedContext.buttonText);
        senderName = mergeNormalized(senderName, pinnedContext.senderName);

        return new MessageSnapshot(
                dialogId,
                senderId,
                messageId,
                text,
                caption,
                buttonText,
                senderName,
                chatName,
                !buttonText.isBlank()
        );
    }

    private static PinnedMessageContext resolvePinnedMessageContext(Object cell, Object messageObject, int account) {
        Object pinnedMessageObject = resolvePinnedMessageObject(messageObject);
        if (pinnedMessageObject == null || pinnedMessageObject == messageObject) {
            return PinnedMessageContext.EMPTY;
        }
        Object pinnedOwner = Reflect.field(pinnedMessageObject, "messageOwner");
        long pinnedSenderId = resolveSenderId(pinnedMessageObject, pinnedOwner);
        return new PinnedMessageContext(
                normalizeText(Reflect.field(pinnedMessageObject, "messageText")),
                normalizeText(Reflect.field(pinnedMessageObject, "caption")),
                collectInlineButtons(pinnedOwner),
                resolveSenderName(cell, pinnedMessageObject, account, pinnedSenderId)
        );
    }

    private static Object resolvePinnedMessageObject(Object messageObject) {
        Object messageOwner = Reflect.field(messageObject, "messageOwner");
        Object action = Reflect.field(messageOwner, "action");
        if (!isPinnedMessageAction(action)) {
            return null;
        }
        Object replyMessageObject = Reflect.field(messageObject, "replyMessageObject");
        if (replyMessageObject != null) {
            return replyMessageObject;
        }
        return Reflect.invokeIfExists(messageObject, "getReplyMessageObject", new Class<?>[0]);
    }

    private static boolean isPinnedMessageAction(Object action) {
        if (action == null) {
            return false;
        }
        String className = action.getClass().getName();
        return className.contains("PinMessage");
    }

    private static long resolveSenderId(Object messageObject, Object messageOwner) {
        Object fromId = Reflect.field(messageOwner, "from_id");
        long userId = Reflect.asLong(Reflect.field(fromId, "user_id"), 0L);
        if (userId != 0L) {
            return userId;
        }
        long chatId = Reflect.asLong(Reflect.field(fromId, "chat_id"), 0L);
        if (chatId != 0L) {
            return -chatId;
        }
        long channelId = Reflect.asLong(Reflect.field(fromId, "channel_id"), 0L);
        if (channelId != 0L) {
            return -channelId;
        }
        Object fallback = Reflect.invokeIfExists(messageObject, "getFromChatId", new Class<?>[0]);
        return Reflect.asLong(fallback, 0L);
    }

    private static String resolveSenderName(Object cell, Object messageObject, int account, long senderId) {
        String fromCell = resolveDisplayName(Reflect.field(cell, "currentUser"), Reflect.field(cell, "currentChat"));
        if (!fromCell.isBlank()) {
            return fromCell;
        }
        return resolveDisplayName(lookupDialogObject(messageObject, account, senderId), null);
    }

    private static String resolveChatName(Object cell, Object messageObject, int account, long dialogId) {
        Object currentChat = Reflect.field(cell, "currentChat");
        Object currentUser = Reflect.field(cell, "currentUser");
        String fromCell = resolveDialogDisplayName(currentUser, currentChat, dialogId);
        if (!fromCell.isBlank()) {
            return fromCell;
        }
        Object dialogObject = lookupDialogObject(messageObject, account, dialogId);
        return resolveDisplayName(dialogObject, null);
    }

    private static Object lookupDialogObject(Object messageObject, int account, long peerId) {
        if (peerId == 0L) {
            return null;
        }
        ClassLoader classLoader = messageObject.getClass().getClassLoader();
        try {
            Class<?> messagesControllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            Object messagesController = Reflect.invokeStatic(messagesControllerClass, "getInstance", new Class<?>[]{int.class}, account);
            if (messagesController == null) {
                return null;
            }
            if (peerId > 0L) {
                Object user = Reflect.invokeIfExists(messagesController, "getUser", new Class<?>[]{long.class}, peerId);
                if (user != null) {
                    return user;
                }
                return Reflect.invokeIfExists(messagesController, "getChat", new Class<?>[]{long.class}, peerId);
            }
            Object chat = Reflect.invokeIfExists(messagesController, "getChat", new Class<?>[]{long.class}, -peerId);
            if (chat != null) {
                return chat;
            }
            return Reflect.invokeIfExists(messagesController, "getUser", new Class<?>[]{long.class}, -peerId);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String resolveDialogDisplayName(Object userObject, Object chatObject, long dialogId) {
        String chatName = resolveDisplayName(null, chatObject);
        if (!chatName.isBlank()) {
            return chatName;
        }
        return resolveDisplayName(userObject, null);
    }

    private static String resolveDisplayName(Object userObject, Object chatObject) {
        if (userObject != null) {
            String firstName = Reflect.asString(Reflect.field(userObject, "first_name")).trim();
            String lastName = Reflect.asString(Reflect.field(userObject, "last_name")).trim();
            String fullName = (firstName + " " + lastName).trim();
            if (!fullName.isBlank()) {
                return normalizeText(fullName);
            }
            String username = Reflect.asString(Reflect.field(userObject, "username")).trim();
            if (!username.isBlank()) {
                return normalizeText(username);
            }
        }
        if (chatObject != null) {
            String title = Reflect.asString(Reflect.field(chatObject, "title")).trim();
            if (!title.isBlank()) {
                return normalizeText(title);
            }
            String username = Reflect.asString(Reflect.field(chatObject, "username")).trim();
            if (!username.isBlank()) {
                return normalizeText(username);
            }
        }
        return "";
    }

    private static String collectInlineButtons(Object messageOwner) {
        Object replyMarkup = Reflect.field(messageOwner, "reply_markup");
        Object rowsObject = Reflect.field(replyMarkup, "rows");
        if (!(rowsObject instanceof List<?>)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object row : (List<?>) rowsObject) {
            Object buttonsObject = Reflect.field(row, "buttons");
            if (!(buttonsObject instanceof List<?>)) {
                continue;
            }
            for (Object button : (List<?>) buttonsObject) {
                append(builder, Reflect.field(button, "text"));
                append(builder, Reflect.field(button, "url"));
            }
        }
        return normalizeText(builder.toString());
    }

    private static void append(StringBuilder builder, Object value) {
        String text = Reflect.asString(value).trim();
        if (text.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(text);
    }

    private static String mergeNormalized(String primary, String secondary) {
        if (secondary == null || secondary.isBlank()) {
            return primary == null ? "" : primary;
        }
        if (primary == null || primary.isBlank()) {
            return secondary;
        }
        if (primary.equals(secondary) || primary.contains(secondary)) {
            return primary;
        }
        if (secondary.contains(primary)) {
            return secondary;
        }
        return primary + " " + secondary;
    }

    private static String normalizeText(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw);
        value = ZERO_WIDTH.matcher(value).replaceAll("");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return value;
    }

    private static final class PinnedMessageContext {
        static final PinnedMessageContext EMPTY = new PinnedMessageContext("", "", "", "");

        final String text;
        final String caption;
        final String buttonText;
        final String senderName;

        PinnedMessageContext(String text, String caption, String buttonText, String senderName) {
            this.text = text;
            this.caption = caption;
            this.buttonText = buttonText;
            this.senderName = senderName;
        }
    }
}
