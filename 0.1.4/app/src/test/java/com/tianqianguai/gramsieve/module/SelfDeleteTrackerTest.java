package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

public class SelfDeleteTrackerTest {
    @Test
    public void recordedUserDeleteExpires() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);

        tracker.recordUserDelete(100L, ids);

        assertTrue(tracker.allowsAll(100L, ids));
        clock.now = 1_001L;
        assertFalse(tracker.allowsAll(100L, ids));
    }

    @Test
    public void cleanupModeAllowsAllIdsUntilDisabled() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);
        ids.add(8);

        assertTrue(tracker.enableCleanupMode(100L, 60_000L));

        assertTrue(tracker.allowsAll(100L, ids));
        tracker.disableCleanupMode(100L);
        assertFalse(tracker.allowsAll(100L, ids));
    }

    @Test
    public void globalDeleteWithoutDialogIsNeverInferredFromMessageIdOnly() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);
        ArrayList<Integer> ids = new ArrayList<>();
        ids.add(7);

        tracker.recordUserDelete(100L, ids);

        assertFalse(tracker.allowsAny(0L, ids));
        assertFalse(tracker.allowsAll(0L, ids));
    }

    @Test
    public void globalDeleteInfersOnlyDialogContainingAllIds() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);
        ArrayList<Integer> allIds = ids(7, 8);

        tracker.recordUserDelete(100L, allIds);
        tracker.recordUserDelete(200L, ids(7));

        assertEquals(100L, tracker.inferUniqueDialog(allIds));
        clock.now = 1_001L;
        assertEquals(0L, tracker.inferUniqueDialog(allIds));
    }

    @Test
    public void globalDeleteDoesNotInferWhenAllIdsMatchMultipleDialogs() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);
        ArrayList<Integer> allIds = ids(7, 8);

        tracker.recordUserDelete(100L, allIds);
        tracker.recordUserDelete(200L, allIds);

        assertEquals(0L, tracker.inferUniqueDialog(allIds));
    }

    @Test
    public void globalDeleteDoesNotInferWhenAnyIdHasNoIntent() {
        FakeClock clock = new FakeClock();
        SelfDeleteTracker tracker = new SelfDeleteTracker(clock, 1_000L);

        tracker.recordUserDelete(100L, ids(7));

        assertEquals(0L, tracker.inferUniqueDialog(ids(7, 8)));
    }

    private static ArrayList<Integer> ids(int... values) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (int value : values) {
            ids.add(value);
        }
        return ids;
    }

    private static final class FakeClock implements SelfDeleteTracker.Clock {
        long now;

        @Override
        public long now() {
            return now;
        }
    }
}
