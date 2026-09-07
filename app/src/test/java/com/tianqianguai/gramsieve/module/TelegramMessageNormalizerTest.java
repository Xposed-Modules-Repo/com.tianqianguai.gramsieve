package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageRuleFactory;
import com.tianqianguai.gramsieve.core.MessageSnapshot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TelegramMessageNormalizerTest {
    @Test
    public void mediaMessageWithEmptyOwnerMessageDoesNotUseSyntheticAlbumLabel() {
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(
                new FakeCell(),
                new FakeMessageObject(
                        -4004L,
                        44L,
                        "相册",
                        "",
                        new FakeMessageOwner(
                                new FakePeer(9001L, 0L, 0L),
                                null,
                                null,
                                "",
                                new FakeMedia()
                        )
                )
        );

        assertTrue(snapshot.text.isBlank());
        assertNull(MessageRuleFactory.exactContentRule(snapshot));
    }

    @Test
    public void mediaMessageUsesOwnerMessageInsteadOfSyntheticAlbumLabel() {
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(
                new FakeCell(),
                new FakeMessageObject(
                        -4004L,
                        45L,
                        "相册",
                        "",
                        new FakeMessageOwner(
                                new FakePeer(9001L, 0L, 0L),
                                null,
                                null,
                                "真实说明",
                                new FakeMedia()
                        )
                )
        );

        assertEquals("真实说明", snapshot.text);
        assertFalse(snapshot.text.contains("相册"));
    }

    @Test
    public void nonMediaMessageKeepsMessageObjectText() {
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(
                new FakeCell(),
                new FakeMessageObject(
                        -4004L,
                        46L,
                        "普通正文",
                        "",
                        new FakeMessageOwner(
                                new FakePeer(9001L, 0L, 0L),
                                null,
                                null
                        )
                )
        );

        assertEquals("普通正文", snapshot.text);
    }

    @Test
    public void quickBlockUsesAnotherTilesRealTextAndLeavesDifferentAlbumsAllowed() {
        FakeMessageObject clicked = mediaMessage(-4004L, 51L, "", "");
        FakeMessageObject captioned = mediaMessage(-4004L, 52L, "需要屏蔽的相册说明", "");
        FakeGroupedMessages group = new FakeGroupedMessages(clicked, captioned);

        List<MessageSnapshot> sources = TelegramMessageNormalizer.normalizeRuleSources(new FakeCell(), clicked, group);
        FilterConfig config = configFromSources(sources);

        assertEquals(2, sources.size());
        assertEquals("需要屏蔽的相册说明", sources.get(1).text);
        assertTrue(groupDecision(config, clicked, captioned).matched);
        assertFalse(groupDecision(config,
                mediaMessage(-4004L, 61L, "", ""),
                mediaMessage(-4004L, 62L, "今天的普通相册说明", "")).matched);
        assertFalse(groupDecision(config, mediaMessage(-4004L, 63L, "", "")).matched);
    }

    @Test
    public void quickBlockUsesRenderedCaptionOnAnotherTile() {
        FakeMessageObject clicked = mediaMessage(-4004L, 51L, "", "");
        FakeMessageObject captioned = mediaMessage(-4004L, 52L, "", "只针对这段媒体说明");
        List<MessageSnapshot> sources = TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), clicked, new FakeGroupedMessages(clicked, captioned));
        FilterConfig config = configFromSources(sources);

        assertEquals(FilterConfig.RuleTarget.CAPTION, config.getChatRuleSet(-4004L).rules.get(0).target);
        assertTrue(groupDecision(config, clicked, captioned).matched);
    }

    @Test
    public void quickBlockDoesNotCreateMediaTypeRulesForAnUncaptionedAlbum() {
        FakeMessageObject clicked = mediaMessage(-4004L, 51L, "", "");
        FakeMessageObject sibling = mediaMessage(-4004L, 52L, "", "");
        FilterConfig config = configFromSources(TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), clicked, new FakeGroupedMessages(clicked, sibling)));

        assertTrue(config.getChatRuleSet(-4004L).rules.isEmpty());
    }

    @Test
    public void quickBlockRejectsRecycledGroupAndSameIdFromAnotherChatOrAccount() {
        FakeMessageObject clicked = mediaMessage(-4004L, 51L, "", "");
        FakeMessageObject differentMessage = mediaMessage(-4004L, 52L, "不属于选中相册", "");
        FakeMessageObject otherChat = mediaMessage(-5005L, 51L, "另一个聊天", "");
        FakeMessageObject otherAccount = mediaMessage(-4004L, 51L, "另一个账号", "");
        otherAccount.currentAccount = 1;

        for (FakeMessageObject unrelated : List.of(differentMessage, otherChat, otherAccount)) {
            List<MessageSnapshot> sources = TelegramMessageNormalizer.normalizeRuleSources(
                    new FakeCell(), clicked, new FakeGroupedMessages(unrelated));
            assertEquals(1, sources.size());
            assertTrue(configFromSources(sources).getChatRuleSet(-4004L).rules.isEmpty());
        }
    }

    @Test
    public void quickBlockIgnoresForeignMembersEvenWhenSelectedMessageIsInTheGroup() {
        FakeMessageObject clicked = mediaMessage(-4004L, 51L, "", "");
        FakeMessageObject otherChat = mediaMessage(-5005L, 52L, "另一个聊天", "");
        FakeMessageObject otherAccount = mediaMessage(-4004L, 53L, "另一个账号", "");
        otherAccount.currentAccount = 1;
        List<MessageSnapshot> sources = TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), clicked, new FakeGroupedMessages(clicked, otherChat, otherAccount));

        assertEquals(1, sources.size());
        assertTrue(configFromSources(sources).getChatRuleSet(-4004L).rules.isEmpty());
    }

    @Test
    public void multipleCaptionsRemainIndividuallyMatchableAndExclusionsStillWin() {
        FakeMessageObject first = mediaMessage(-4004L, 51L, "第一条媒体说明", "");
        FakeMessageObject second = mediaMessage(-4004L, 52L, "第二条媒体说明", "");
        FilterConfig config = configFromSources(TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), first, new FakeGroupedMessages(first, second)));

        assertTrue(groupDecision(config, first).matched);
        assertTrue(groupDecision(config, second).matched);
        config.getChatRuleSet(-4004L).exclusions.add(MessageRuleFactory.exactContentRule(
                TelegramMessageNormalizer.normalize(new FakeCell(), second)));
        config.updatedAtEpochMs++;
        FilterDecision groupDecision = groupDecision(config, first, second);
        assertTrue(groupDecision.excluded);
        assertFalse(groupDecision.matched);
    }

    @Test
    public void plainTextAndLiteralAlbumCaptionStillCreateTextRules() {
        FakeMessageObject text = new FakeMessageObject(-4004L, 51L, "普通文字", "",
                new FakeMessageOwner(new FakePeer(9001L, 0L, 0L), null, null));
        FilterConfig textConfig = configFromSources(TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), text, null));
        assertTrue(groupDecision(textConfig, text).matched);

        FakeMessageObject captioned = mediaMessage(-4004L, 52L, "相册", "");
        FilterConfig captionConfig = configFromSources(TelegramMessageNormalizer.normalizeRuleSources(
                new FakeCell(), captioned, null));
        assertTrue(groupDecision(captionConfig, captioned).matched);
        assertFalse(groupDecision(captionConfig, mediaMessage(-4004L, 53L, "", "")).matched);
    }

    private static FakeMessageObject mediaMessage(long dialogId, long id, String text, String caption) {
        return new FakeMessageObject(dialogId, id, "相册", caption,
                new FakeMessageOwner(new FakePeer(9001L, 0L, 0L), null, null, text, new FakeMedia()));
    }

    private static FilterConfig configFromSources(List<MessageSnapshot> sources) {
        FilterConfig config = FilterConfig.createDefault();
        config.updatedAtEpochMs = 1L;
        FilterConfig.ChatRuleSet chatRules = config.getOrCreateChatRuleSet(-4004L);
        for (MessageSnapshot source : sources) {
            chatRules.rules.addAll(MessageRuleFactory.automaticRules(source));
        }
        return config;
    }

    private static FilterDecision groupDecision(FilterConfig config, FakeMessageObject... messages) {
        FilterEngine engine = new FilterEngine();
        List<FilterDecision> decisions = new ArrayList<>();
        for (FakeMessageObject message : messages) {
            decisions.add(engine.evaluate(config, TelegramMessageNormalizer.normalize(new FakeCell(), message)));
        }
        return GroupedDecisionSelector.select(decisions).decision;
    }

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
        int currentAccount = 0;
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

    private static final class FakeGroupedMessages {
        final List<FakeMessageObject> messages;

        FakeGroupedMessages(FakeMessageObject... messages) {
            this.messages = List.of(messages);
        }
    }

    private static final class FakeMessageOwner {
        final Object from_id;
        final Object reply_markup;
        final Object action;
        final String message;
        final Object media;

        FakeMessageOwner(Object fromId, Object replyMarkup, Object action) {
            this(fromId, replyMarkup, action, "", null);
        }

        FakeMessageOwner(Object fromId, Object replyMarkup, Object action, String message, Object media) {
            this.from_id = fromId;
            this.reply_markup = replyMarkup;
            this.action = action;
            this.message = message;
            this.media = media;
        }
    }

    private static final class FakeMedia {
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
