package com.tianqianguai.gramsieve.module;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TelegramDialogPrunePlan {
    private TelegramDialogPrunePlan() {
    }

    static List<Operation> directDialogOperations() {
        return DIRECT_DIALOG_OPERATIONS;
    }

    private static final List<Operation> DIRECT_DIALOG_OPERATIONS = Collections.unmodifiableList(Arrays.asList(
            new Operation("dialogs", "did"),
            new Operation("dialog_settings", "did"),
            new Operation("search_recent", "did"),
            new Operation("shortcut_widget", "did"),
            new Operation("topics", "did"),
            new Operation("bot_keyboard", "uid"),
            new Operation("bot_keyboard_topics", "uid"),
            new Operation("chat_pinned_count", "uid"),
            new Operation("chat_pinned_v2", "uid"),
            new Operation("enc_tasks_v4", "uid"),
            new Operation("media_counts_topics", "uid"),
            new Operation("media_counts_v2", "uid"),
            new Operation("media_holes_topics", "uid"),
            new Operation("media_holes_v2", "uid"),
            new Operation("media_topics", "uid"),
            new Operation("media_v4", "uid"),
            new Operation("messages_holes", "uid"),
            new Operation("messages_holes_topics", "uid"),
            new Operation("messages_topics", "uid"),
            new Operation("messages_v2", "uid"),
            new Operation("polls_v2", "uid"),
            new Operation("randoms_v2", "uid"),
            new Operation("reaction_mentions", "dialog_id"),
            new Operation("reaction_mentions_topics", "dialog_id"),
            new Operation("poll_votes_mentions", "dialog_id"),
            new Operation("poll_votes_mentions_topics", "dialog_id"),
            new Operation("scheduled_messages_v2", "uid"),
            new Operation("sharing_locations", "uid"),
            new Operation("unread_push_messages", "uid"),
            new Operation("webpage_pending_v2", "uid")
    ));

    static final class Operation {
        final String table;
        final String column;

        Operation(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }
}
