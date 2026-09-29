package com.tianqianguai.gramsieve.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PersistentLogStoreTest {
    @Test
    public void appendPrependsNewestAndTrimsToLimit() {
        PersistentLogStore.LogSnapshot snapshot = new PersistentLogStore.LogSnapshot();
        for (int i = 0; i < 5; i++) {
            PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry();
            entry.message = "entry-" + i;
            snapshot = PersistentLogStore.append(snapshot, entry, 3);
        }

        assertEquals(3, snapshot.entries.size());
        assertEquals("entry-4", snapshot.entries.get(0).message);
        assertEquals("entry-3", snapshot.entries.get(1).message);
        assertEquals("entry-2", snapshot.entries.get(2).message);
    }

    @Test
    public void entryJsonRoundTripSanitizesFields() {
        PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry("INFO", "hook", "GramSieve", "  hook installed  ");

        PersistentLogStore.LogEntry parsed = PersistentLogStore.entryFromJson(
                PersistentLogStore.entryToJson(entry)
        );

        assertEquals("INFO", parsed.level);
        assertEquals("hook", parsed.category);
        assertEquals("GramSieve", parsed.tag);
        assertEquals("hook installed", parsed.message);
        assertTrue(parsed.timestampEpochMs > 0L);
    }

    @Test
    public void entrySanitizesLongFields() {
        PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry();
        entry.message = "A".repeat(600);
        entry.throwable = "B".repeat(1100);
        entry.category = "C".repeat(40);

        PersistentLogStore.LogEntry sanitized = entry.sanitize();

        assertEquals(512, sanitized.message.length());
        assertEquals(1024, sanitized.throwable.length());
        assertEquals(32, sanitized.category.length());
    }

    @Test
    public void snapshotSanitizesNullEntries() {
        PersistentLogStore.LogSnapshot snapshot = new PersistentLogStore.LogSnapshot();
        snapshot.entries.add(null);
        snapshot.entries.add(new PersistentLogStore.LogEntry());

        PersistentLogStore.LogSnapshot sanitized = snapshot.sanitize();

        assertEquals(1, sanitized.entries.size());
    }

    @Test
    public void fromJsonHandlesNullAndBlank() {
        PersistentLogStore.LogSnapshot nullSnapshot = PersistentLogStore.fromJson(null);
        PersistentLogStore.LogSnapshot blankSnapshot = PersistentLogStore.fromJson("  ");

        assertEquals(0, nullSnapshot.entries.size());
        assertEquals(0, blankSnapshot.entries.size());
    }

    @Test
    public void entryFromJsonHandlesMalformedJson() {
        PersistentLogStore.LogEntry entry = PersistentLogStore.entryFromJson("{invalid json");

        assertEquals("", entry.message);
        assertTrue(entry.timestampEpochMs > 0L);
    }

    @Test
    public void constructorWithThrowableSanitizes() {
        RuntimeException exception = new RuntimeException("test error");
        PersistentLogStore.LogEntry entry = new PersistentLogStore.LogEntry("ERROR", "error", "Tag", "failed", exception);

        assertTrue(entry.throwable.contains("RuntimeException"));
        assertTrue(entry.throwable.contains("test error"));
    }
}
