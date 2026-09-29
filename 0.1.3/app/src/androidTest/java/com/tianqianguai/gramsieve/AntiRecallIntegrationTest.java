package com.tianqianguai.gramsieve;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tianqianguai.gramsieve.module.MessageCache;
import com.tianqianguai.gramsieve.module.MessageDatabaseHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AntiRecallIntegrationTest {
    private MessageDatabaseHelper databaseHelper;
    private MessageCache cache;

    @Before
    public void setUp() {
        Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseHelper = new MessageDatabaseHelper(context);
        cache = new MessageCache(databaseHelper);
    }

    @After
    public void tearDown() {
        databaseHelper.close();
    }

    @Test
    public void testPutAndGet() {
        cache.put(123, 456, "Hello", "World", 789);
        MessageCache.CachedMessage message = cache.get(123, 456);
        assertNotNull(message);
        assertEquals("Hello", message.text);
        assertEquals("World", message.caption);
        assertEquals(789, message.senderId);
    }

    @Test
    public void testMarkRecalled() {
        cache.put(123, 456, "Hello", "World", 789);
        cache.markRecalled(123, 456);
        MessageCache.CachedMessage message = cache.get(123, 456);
        assertNotNull(message);
        assertTrue(message.isRecalled);
    }

    @Test
    public void testMarkEdited() {
        cache.put(123, 456, "Hello", "World", 789);
        cache.markEdited(123, 456, "New Text");
        MessageCache.CachedMessage message = cache.get(123, 456);
        assertNotNull(message);
        assertTrue(message.isEdited);
        assertEquals("New Text", message.editedText);
    }

    @Test
    public void testGetRecalledMessages() {
        cache.put(1, 10, "msg1", null, 100);
        cache.put(1, 20, "msg2", null, 100);
        cache.put(1, 30, "msg3", null, 100);
        cache.markRecalled(1, 10);
        cache.markRecalled(1, 30);

        java.util.List<MessageCache.CachedMessage> recalled = cache.getRecalledMessages(1);
        assertEquals(2, recalled.size());
    }

    @Test
    public void testGetEditedMessages() {
        cache.put(1, 10, "msg1", null, 100);
        cache.put(1, 20, "msg2", null, 100);
        cache.markEdited(1, 10, "edited1");

        java.util.List<MessageCache.CachedMessage> edited = cache.getEditedMessages(1);
        assertEquals(1, edited.size());
        assertEquals("edited1", edited.get(0).editedText);
    }

    @Test
    public void testGetNonexistentReturnsNull() {
        MessageCache.CachedMessage message = cache.get(999, 999);
        assertNull(message);
    }

    @Test
    public void testPersistenceAcrossCacheInstances() {
        cache.put(1, 1, "persistent", null, 10);
        cache.markRecalled(1, 1);

        MessageCache newCache = new MessageCache(databaseHelper);
        MessageCache.CachedMessage message = newCache.get(1, 1);
        assertNotNull(message);
        assertEquals("persistent", message.text);
        assertTrue(message.isRecalled);
    }
}
