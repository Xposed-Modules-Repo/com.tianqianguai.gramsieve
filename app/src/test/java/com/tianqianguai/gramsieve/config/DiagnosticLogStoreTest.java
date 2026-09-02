package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticLogStoreTest {
    @Test
    public void appendPrependsNewestAndTrimsToLimit() {
        DiagnosticLogStore.DiagnosticSnapshot snapshot = new DiagnosticLogStore.DiagnosticSnapshot();
        for (int i = 0; i < 5; i++) {
            DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();
            entry.messageId = i;
            entry.reason = "entry-" + i;
            snapshot = DiagnosticLogStore.append(snapshot, entry, 3);
        }

        assertEquals(3, snapshot.entries.size());
        assertEquals(4L, snapshot.entries.get(0).messageId);
        assertEquals(3L, snapshot.entries.get(1).messageId);
        assertEquals(2L, snapshot.entries.get(2).messageId);
    }

    @Test
    public void entryJsonRoundTripSanitizesFields() {
        DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();
        entry.reason = "  builtin match builtin_gambling_promo  ";
        entry.text = "  spam text  ";

        DiagnosticLogStore.DiagnosticEntry parsed = DiagnosticLogStore.entryFromJson(
                DiagnosticLogStore.entryToJson(entry)
        );

        assertEquals("builtin match builtin_gambling_promo", parsed.reason);
        assertEquals(LogPrivacy.allowsSensitiveContent() ? "spam text" : "", parsed.text);
        assertEquals(9, parsed.textChars);
        assertTrue(parsed.timestampEpochMs > 0L);
    }

    @Test
    public void releaseDetailsDropContentButKeepLengths() {
        DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();

        DiagnosticLogStore.setMessageDetailsForBuild(
                entry,
                "Private Group",
                "Alice",
                "Secret body",
                "Photo caption",
                "Open button",
                false
        );
        DiagnosticLogStore.DiagnosticEntry parsed = DiagnosticLogStore.entryFromJson(
                DiagnosticLogStore.entryToJson(entry)
        );

        assertEquals("", parsed.chatName);
        assertEquals("", parsed.senderName);
        assertEquals("", parsed.text);
        assertEquals("", parsed.caption);
        assertEquals("", parsed.buttonText);
        assertEquals(13, parsed.chatNameChars);
        assertEquals(5, parsed.senderNameChars);
        assertEquals(11, parsed.textChars);
        assertEquals(13, parsed.captionChars);
        assertEquals(11, parsed.buttonTextChars);
    }

    @Test
    public void debugDetailsKeepContentAndLengths() {
        DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();

        DiagnosticLogStore.setMessageDetailsForBuild(
                entry, "Group", "Bob", "Message", "Caption", "Button", true
        );

        assertEquals("Group", entry.chatName);
        assertEquals("Bob", entry.senderName);
        assertEquals("Message", entry.text);
        assertEquals(7, entry.textChars);
    }
}
