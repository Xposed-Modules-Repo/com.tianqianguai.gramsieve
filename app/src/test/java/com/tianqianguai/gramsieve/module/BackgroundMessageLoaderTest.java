package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BackgroundMessageLoaderTest {
    @Test
    public void testEnableDisableChat() {
        BackgroundMessageLoader loader = new BackgroundMessageLoader(null, null);
        loader.enableChat(123);
        assertTrue(loader.isChatEnabled(123));
        loader.disableChat(123);
        assertFalse(loader.isChatEnabled(123));
    }

    @Test
    public void testPopulateHistoryRequestUsesLatestPageWithoutTelegramPaginationState() {
        FakeHistoryRequest request = new FakeHistoryRequest();
        Object peer = new Object();

        assertTrue(TelegramHistoryApiAdapter.populateHistoryRequest(request, peer, 50));

        assertSame(peer, request.peer);
        assertEquals(0, request.offset_id);
        assertEquals(0, request.offset_date);
        assertEquals(0, request.add_offset);
        assertEquals(50, request.limit);
        assertEquals(0, request.max_id);
        assertEquals(0, request.min_id);
        assertEquals(0L, request.hash);
    }

    @Test
    public void testPopulateHistoryRequestRejectsMissingPeer() {
        assertFalse(TelegramHistoryApiAdapter.populateHistoryRequest(
                new FakeHistoryRequest(), null, 50));
    }

    @Test
    public void testResolveInputPeerHydratesMissingChatFromTelegramStorage() {
        FakePeerController controller = new FakePeerController();

        Object peer = BackgroundMessageLoader.resolveInputPeer(controller, -3919763977L);

        assertTrue(peer instanceof TL_inputPeerChannel);
        assertSame(controller.storedChat, controller.memoryChat);
    }

    @Test
    public void testResolveInputPeerLeavesKnownBasicChatAlone() {
        FakePeerController controller = new FakePeerController();
        controller.memoryChat = new FakeChat(false);

        Object peer = BackgroundMessageLoader.resolveInputPeer(controller, -123L);

        assertTrue(peer instanceof TL_inputPeerChat);
        assertEquals(0, controller.storageReads);
    }

    @Test
    public void testHistoryMessagesFromResponseCopiesResponseList() {
        FakeMessage first = new FakeMessage(41, 1001);
        FakeMessage second = new FakeMessage(42, 1002);
        FakeHistoryResponse response = new FakeHistoryResponse(first, second);

        List<?> messages = BackgroundMessageLoader.historyMessagesFromResponse(response);

        assertEquals(Arrays.asList(first, second), messages);
        response.messages.clear();
        assertEquals(2, messages.size());
    }

    @Test
    public void testHistoryMessagesFromResponseRejectsUnexpectedShape() {
        assertNull(BackgroundMessageLoader.historyMessagesFromResponse(new Object()));
    }

    @Test
    public void testInspectLoadedRangeReportsIdsAndTelegramDates() {
        BackgroundMessageLoader.LoadedRange range = BackgroundMessageLoader.inspectLoadedRange(Arrays.asList(
                new FakeMessage(54, 1004),
                new FakeMessage(51, 1001),
                new FakeMessage(53, 1003)
        ));

        assertEquals(3, range.count);
        assertEquals(51, range.minId);
        assertEquals(54, range.maxId);
        assertEquals(1001, range.minDate);
        assertEquals(1004, range.maxDate);
    }

    @Test
    public void testLoadCycleDebounceCoversScheduledAndImmediateTriggers() {
        assertFalse(BackgroundMessageLoader.isLoadCycleTooSoon(10_000L, 0L));
        assertTrue(BackgroundMessageLoader.isLoadCycleTooSoon(11_499L, 10_000L));
        assertFalse(BackgroundMessageLoader.isLoadCycleTooSoon(11_500L, 10_000L));
    }

    @Test
    public void testFailureRetryUsesBoundedExponentialBackoff() {
        assertEquals(30_000L, BackgroundMessageLoader.retryDelayMs(1));
        assertEquals(60_000L, BackgroundMessageLoader.retryDelayMs(2));
        assertEquals(120_000L, BackgroundMessageLoader.retryDelayMs(3));
        assertEquals(900_000L, BackgroundMessageLoader.retryDelayMs(20));
    }

    private static final class FakeHistoryRequest {
        Object peer;
        int offset_id = -1;
        int offset_date = -1;
        int add_offset = -1;
        int limit = -1;
        int max_id = -1;
        int min_id = -1;
        long hash = -1L;
    }

    private static final class FakeHistoryResponse {
        final ArrayList<FakeMessage> messages = new ArrayList<>();

        FakeHistoryResponse(FakeMessage... messages) {
            this.messages.addAll(Arrays.asList(messages));
        }
    }

    private static final class FakeMessage {
        final int id;
        final int date;

        FakeMessage(int id, int date) {
            this.id = id;
            this.date = date;
        }
    }

    private static final class FakePeerController {
        final FakeChat storedChat = new FakeChat(true);
        FakeChat memoryChat;
        int storageReads;

        Object getInputPeer(long dialogId) {
            return memoryChat != null && memoryChat.channel
                    ? new TL_inputPeerChannel() : new TL_inputPeerChat();
        }

        Object getChat(Long chatId) {
            return memoryChat;
        }

        Object getMessagesStorage() {
            return new Object() {
                @SuppressWarnings("unused")
                Object getChatSync(long chatId) {
                    storageReads++;
                    return storedChat;
                }
            };
        }

        @SuppressWarnings("unused")
        void putChat(FakeChat chat, boolean fromCache) {
            memoryChat = chat;
        }
    }

    private static final class FakeChat {
        final boolean channel;

        FakeChat(boolean channel) {
            this.channel = channel;
        }
    }

    private static final class TL_inputPeerChat {
    }

    private static final class TL_inputPeerChannel {
    }
}
