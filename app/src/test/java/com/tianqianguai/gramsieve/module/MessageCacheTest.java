package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MessageCacheTest {
    @Test
    public void testCachedMessageCreation() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertEquals(123, message.dialogId);
        assertEquals(456, message.messageId);
        assertEquals(789, message.senderId);
        assertEquals("Hello", message.text);
        assertEquals("World", message.caption);
        assertFalse(message.isRecalled);
        assertFalse(message.isEdited);
        assertNull(message.editedText);
    }

    @Test
    public void testMarkRecalled() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertFalse(message.isRecalled);
        message.isRecalled = true;
        assertTrue(message.isRecalled);
    }

    @Test
    public void testMarkEdited() {
        MessageCache.CachedMessage message = new MessageCache.CachedMessage(
                123, 456, 789, "Hello", "World", System.currentTimeMillis());
        assertFalse(message.isEdited);
        assertNull(message.editedText);
        message.isEdited = true;
        message.editedText = "New Text";
        assertTrue(message.isEdited);
        assertEquals("New Text", message.editedText);
    }
}
