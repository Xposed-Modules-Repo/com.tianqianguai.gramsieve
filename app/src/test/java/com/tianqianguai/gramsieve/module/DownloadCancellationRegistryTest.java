package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownloadCancellationRegistryTest {
    @Test
    public void explicitCancelBlocksUntilTheUserStartsTheSameFileAgain() {
        DownloadCancellationRegistry registry = new DownloadCancellationRegistry();
        String key = "0:video_123.mp4";

        registry.markCancelled(key);

        assertTrue(registry.isCancelled(key));
        registry.allow(key);
        assertFalse(registry.isCancelled(key));
    }

    @Test
    public void nullKeysAreNeverTreatedAsCancelled() {
        DownloadCancellationRegistry registry = new DownloadCancellationRegistry();

        registry.markCancelled(null);

        assertFalse(registry.isCancelled(null));
    }
}
