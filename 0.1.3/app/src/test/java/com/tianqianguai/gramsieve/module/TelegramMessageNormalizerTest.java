package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.MessageSnapshot;

import org.junit.Test;

import java.util.List;

public class TelegramMessageNormalizerTest {
    @Test
    public void pinnedServiceMessageIncludesPinnedContentFields() {
        FakeMessageObject pinnedMessage = new FakeMessageObject(
                -4004L,
                42L,
                "原始推广正文",
                "置顶原文标题",
                new FakeMessageOwner(
                        new FakePeer(9001L, 0L, 0L),
                        new FakeReplyMarkup(List.of(new FakeButtonRow(List.of(new FakeButton("立即加入", "https://spam.example"))))),
                        null
                )
        );

        FakeMessageObject serviceMessage = new FakeMessageObject(
                -4004L,
                43L,
                "管理员置顶了一条消息",
                "",
                new FakeMessageOwner(
                        new FakePeer(8001L, 0L, 0L),
                        null,
                        new FakePinMessageAction()
                )
        );
        serviceMessage.replyMessageObject = pinnedMessage;

        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(new FakeCell(), serviceMessage);

        assertTrue(snapshot.text.contains("管理员置顶了一条消息"));
        assertTrue(snapshot.text.contains("原始推广正文"));
        assertTrue(snapshot.caption.contains("置顶原文标题"));
        assertTrue(snapshot.buttonText.contains("立即加入"));
        assertTrue(snapshot.buttonText.contains("https://spam.example"));
    }

    private static final class FakeCell {
        Object currentUser;
        Object currentChat;
    }

    private static final class FakeMessageObject {
        final long dialogId;
        final long id;
        final int currentAccount = 0;
        final Object messageOwner;
        final String messageText;
        final String caption;
        Object replyMessageObject;

        FakeMessageObject(long dialogId, long id, String messageText, String caption, Object messageOwner) {
            this.dialogId = dialogId;
            this.id = id;
            this.messageText = messageText;
            this.caption = caption;
            this.messageOwner = messageOwner;
        }

        long getDialogId() {
            return dialogId;
        }

        long getId() {
            return id;
        }
    }

    private static final class FakeMessageOwner {
        final Object from_id;
        final Object reply_markup;
        final Object action;

        FakeMessageOwner(Object fromId, Object replyMarkup, Object action) {
            this.from_id = fromId;
            this.reply_markup = replyMarkup;
            this.action = action;
        }
    }

    private static final class FakePeer {
        final long user_id;
        final long chat_id;
        final long channel_id;

        FakePeer(long userId, long chatId, long channelId) {
            this.user_id = userId;
            this.chat_id = chatId;
            this.channel_id = channelId;
        }
    }

    private static final class FakeReplyMarkup {
        final List<FakeButtonRow> rows;

        FakeReplyMarkup(List<FakeButtonRow> rows) {
            this.rows = rows;
        }
    }

    private static final class FakeButtonRow {
        final List<FakeButton> buttons;

        FakeButtonRow(List<FakeButton> buttons) {
            this.buttons = buttons;
        }
    }

    private static final class FakeButton {
        final String text;
        final String url;

        FakeButton(String text, String url) {
            this.text = text;
            this.url = url;
        }
    }

    private static final class FakePinMessageAction {
    }
}
