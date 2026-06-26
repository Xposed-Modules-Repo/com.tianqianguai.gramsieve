package com.tianqianguai.gramsieve.module;

import org.junit.Test;
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
}
