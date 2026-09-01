package com.tianqianguai.gramsieve.module;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReliableDownloadDiagnosticsTest {
    @Test
    public void progressMetadataReportsGrowthAndElapsedTime() {
        ReliableDownloadDiagnostics.ProgressMetadata first =
                ReliableDownloadDiagnostics.observeProgress(
                        0L, true, 1_000L, 0, 20L, 100L, 1_500L, "FileLoader");
        ReliableDownloadDiagnostics.ProgressMetadata second =
                ReliableDownloadDiagnostics.observeProgress(
                        first.currentBytes, true, 1_500L, first.eventCount,
                        32L, 100L, 2_250L, "FileLoader");

        assertEquals(20L, first.currentBytes);
        assertEquals(100L, first.totalBytes);
        assertEquals(20L, first.deltaBytes);
        assertEquals(-1L, first.elapsedSincePreviousMs);
        assertEquals(1, first.eventCount);
        assertTrue(first.advanced);
        assertEquals(12L, second.deltaBytes);
        assertEquals(750L, second.elapsedSincePreviousMs);
        assertEquals(2, second.eventCount);
        assertTrue(second.advanced);
    }

    @Test
    public void progressMetadataKeepsNonAdvancingAndMalformedEventsObservable() {
        ReliableDownloadDiagnostics.ProgressMetadata duplicate =
                ReliableDownloadDiagnostics.observeProgress(
                        20L, true, 1_500L, 1, 20L, null, 2_000L, "NotificationCenter");
        ReliableDownloadDiagnostics.ProgressMetadata malformed =
                ReliableDownloadDiagnostics.observeProgress(
                        duplicate.currentBytes, true, 2_000L, duplicate.eventCount,
                        null, null, 2_500L, "NotificationCenter");

        assertEquals(0L, duplicate.deltaBytes);
        assertFalse(duplicate.advanced);
        assertFalse(duplicate.totalBytesKnown);
        assertEquals(500L, duplicate.elapsedSincePreviousMs);
        assertFalse(malformed.currentBytesKnown);
        assertEquals(Long.MIN_VALUE, malformed.deltaBytes);
        assertFalse(malformed.advanced);
        assertEquals(3, malformed.eventCount);
        assertEquals(500L, malformed.elapsedSincePreviousMs);
    }

    @Test
    public void cancelSourceUsesThreadOriginBeforeExplicitCancelState() {
        assertEquals(ReliableDownloadDiagnostics.ORIGIN_STALL_RECOVERY,
                ReliableDownloadDiagnostics.cancelSource(
                        ReliableDownloadDiagnostics.ORIGIN_STALL_RECOVERY, true));
        assertEquals(ReliableDownloadDiagnostics.ORIGIN_EXPLICIT_CANCEL,
                ReliableDownloadDiagnostics.cancelSource(
                        ReliableDownloadDiagnostics.ORIGIN_EXPLICIT_CANCEL, false));
        assertEquals(ReliableDownloadDiagnostics.SOURCE_TELEGRAM_AFTER_X,
                ReliableDownloadDiagnostics.cancelSource(null, true));
        assertEquals(ReliableDownloadDiagnostics.SOURCE_UNKNOWN,
                ReliableDownloadDiagnostics.cancelSource(null, false));
    }

    @Test
    public void argumentSummariesRetainShapeAndBoundStrings() {
        String longValue = new String(new char[120]).replace('\0', 'x');
        assertEquals("String,Long,null", ReliableDownloadDiagnostics.argumentShape(
                new Object[]{"video.mp4", 1L, null}));
        String summary = ReliableDownloadDiagnostics.argumentSummary(
                new Object[]{longValue, 1});
        assertTrue(summary.startsWith("[String(\""));
        assertTrue(summary.endsWith("...\"), Integer(1)]"));
    }
}
