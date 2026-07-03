package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

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
    public void testBuildLoadMessagesArgsUsesDialogIdAndCount() throws Exception {
        Method method = FakeMessagesController.class.getDeclaredMethod(
                "loadMessages",
                long.class,
                int.class,
                int.class,
                boolean.class,
                Object.class
        );

        Object[] args = BackgroundMessageLoader.buildLoadMessagesArgs(method, -123L);

        assertNotNull(args);
        assertEquals(-123L, args[0]);
        assertEquals(50, args[1]);
        assertEquals(0, args[2]);
        assertEquals(false, args[3]);
        assertNull(args[4]);
    }

    @Test
    public void testBuildLoadMessagesArgsRejectsMethodWithoutDialogId() throws Exception {
        Method method = FakeMessagesController.class.getDeclaredMethod("loadMessages", int.class, boolean.class);

        assertNull(BackgroundMessageLoader.buildLoadMessagesArgs(method, 123L));
    }

    private static final class FakeMessagesController {
        @SuppressWarnings("unused")
        void loadMessages(long dialogId, int count, int maxId, boolean fromCache, Object placeholder) {}

        @SuppressWarnings("unused")
        void loadMessages(int count, boolean fromCache) {}
    }
}
