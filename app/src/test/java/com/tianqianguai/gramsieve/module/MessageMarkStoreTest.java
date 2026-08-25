package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MessageMarkStoreTest {
    @Test
    public void previewIsSingleLineAndBounded() {
        String preview = MessageMarkStore.normalizePreview("  first\nsecond   " + "x".repeat(120));

        assertTrue(preview.startsWith("first second"));
        assertEquals(96, preview.length());
        assertTrue(preview.endsWith("…"));
    }
}
