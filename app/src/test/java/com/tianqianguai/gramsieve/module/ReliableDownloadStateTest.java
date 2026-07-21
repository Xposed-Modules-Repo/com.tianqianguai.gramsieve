package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReliableDownloadStateTest {
    @Test
    public void startKeepsRecoveringUntilExplicitCancel() {
        ReliableDownloadState state = new ReliableDownloadState();
        long generation = state.start(1_000L);

        assertFalse(state.shouldRecover(generation, 30_999L, 30_000L));
        assertTrue(state.shouldRecover(generation, 31_000L, 30_000L));

        state.cancel();
        assertFalse(state.shouldRecover(generation, 100_000L, 30_000L));
        assertFalse(state.isCurrent(generation));
    }

    @Test
    public void onlyIncreasingProgressResetsStallClock() {
        ReliableDownloadState state = new ReliableDownloadState();
        long generation = state.start(1_000L);

        assertTrue(state.progress(100L, 20_000L));
        assertFalse(state.progress(100L, 40_000L));
        assertFalse(state.progress(50L, 45_000L));
        assertFalse(state.shouldRecover(generation, 49_999L, 30_000L));
        assertTrue(state.shouldRecover(generation, 50_000L, 30_000L));
    }

    @Test
    public void restartAfterCancelUsesANewGeneration() {
        ReliableDownloadState state = new ReliableDownloadState();
        long oldGeneration = state.start(1_000L);
        state.cancel();
        long newGeneration = state.start(2_000L);

        assertFalse(state.isCurrent(oldGeneration));
        assertTrue(state.isCurrent(newGeneration));
    }
}
