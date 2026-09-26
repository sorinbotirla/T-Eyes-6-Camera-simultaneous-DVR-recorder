package ro.interfaz.cameratester;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingMetricsTest {
    @Test public void emptyCaptureHasNoInventedFrameRateOrFirstFrame() {
        RecordingMetrics metrics = new RecordingMetrics(1_000_000_000L);
        RecordingMetrics.Data data = metrics.snapshot(3_000_000_000L);
        assertEquals(0, data.frames);
        assertEquals(0, data.observedFps, 0);
        assertEquals(0, data.p95WriteMs, 0);
        assertEquals(-1, data.firstFrameDelayMs, 0);
        assertEquals(2, data.elapsedSeconds, 0);
    }

    @Test public void startupDelayIsSeparateAndOutputStallsReduceObservedFps() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        metrics.sample(metrics.normalizePts(9_000_000), 100, 2_000_000, 2_000_000_000L);
        metrics.sample(metrics.normalizePts(9_040_000), 200, 4_000_000, 2_040_000_000L);
        RecordingMetrics.Data running = metrics.snapshot(2_040_000_000L);
        assertEquals(25, running.observedFps, 0.001);
        assertEquals(25, running.ptsFps, 0.001);
        assertEquals(2000, running.firstFrameDelayMs, 0);
        assertEquals(300, running.bytes);
        assertEquals(3, running.meanWriteMs, 0);
        assertEquals(4, running.p95WriteMs, 0);
        assertEquals(40, running.maxPtsGapMs, 0);
        assertEquals(40, running.maxOutputGapMs, 0);
        assertEquals(1, metrics.snapshot(3_000_000_000L).observedFps, 0);
    }

    @Test public void timestampsBecomeLocalAndRegressionsAreCounted() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        long first = metrics.normalizePts(9_000_000);
        assertEquals(0, first);
        metrics.sample(first, 100, 0, 0);
        long repeated = metrics.normalizePts(9_000_000);
        assertEquals(1, repeated);
        metrics.sample(repeated, 100, 0, 1);
        long backward = metrics.normalizePts(8_999_000);
        assertEquals(2, backward);
        metrics.sample(backward, 100, 0, 2);
        long recovered = metrics.normalizePts(9_040_000);
        assertEquals(40_000, recovered);
        metrics.sample(recovered, 100, 0, 3);
        assertEquals(2, metrics.snapshot(3).ptsCorrections);
    }

    @Test public void finalMeasurementsDoNotChangeWhenReadLater() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        metrics.sample(0, 1, 0, 0);
        metrics.sample(1_000_000, 1, 0, 1_000_000_000L);
        metrics.finish(1_000_000_000L);
        metrics.finish(9_000_000_000L);
        assertEquals(1, metrics.snapshot(100_000_000_000L).elapsedSeconds, 0);
        assertEquals(1, metrics.snapshot(100_000_000_000L).observedFps, 0);
    }

    @Test public void finalizedCadenceExcludesShutdownDelayButLiveCadenceIncludesAStall() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        metrics.sample(0, 100, 0, 2_000_000_000L);
        metrics.sample(40_000, 100, 0, 2_040_000_000L);
        RecordingMetrics.Data live = metrics.snapshot(3_000_000_000L);
        assertEquals(1, live.observedFps, 0);
        assertEquals("first encoded frame to current wall time", live.fpsMeasurement);
        metrics.finish(9_000_000_000L);
        RecordingMetrics.Data finished = metrics.snapshot(100_000_000_000L);
        assertEquals(25, finished.observedFps, 0.001);
        assertEquals(9, finished.elapsedSeconds, 0);
        assertEquals(2000, finished.firstFrameDelayMs, 0);
        assertEquals("first to last encoded frame arrival; excludes finalization", finished.fpsMeasurement);
    }

    @Test public void finalizedCadenceStillIncludesAStallBetweenReceivedFrames() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        metrics.sample(0, 100, 0, 0);
        metrics.sample(40_000, 100, 0, 5_000_000_000L);
        metrics.finish(10_000_000_000L);
        RecordingMetrics.Data finished = metrics.snapshot(20_000_000_000L);
        assertEquals(.2, finished.observedFps, 0);
        assertEquals(5000, finished.maxOutputGapMs, 0);
        assertEquals(25, finished.ptsFps, 0);
    }

    @Test public void p95UsesNearestRankAndKeepsLifetimeMeanAndMaximum() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        for (int i = 1; i <= 100; i++) metrics.sample(i, 10, i * 1_000_000L, i);
        RecordingMetrics.Data data = metrics.snapshot(100);
        assertEquals(95, data.p95WriteMs, 0);
        assertEquals(50.5, data.meanWriteMs, 0);
        assertEquals(100, data.maxWriteMs, 0);
        assertEquals(100, data.writeSamples);
    }

    @Test public void longSessionsBoundPercentileMemoryButRetainByteAndFrameTotals() {
        RecordingMetrics metrics = new RecordingMetrics(0);
        metrics.sample(0, 5, 100_000_000, 0);
        for (int i = 1; i <= RecordingMetrics.WRITE_WINDOW; i++) metrics.sample(i, 5, 1_000_000, i);
        RecordingMetrics.Data data = metrics.snapshot(RecordingMetrics.WRITE_WINDOW);
        assertEquals(RecordingMetrics.WRITE_WINDOW + 1, data.frames);
        assertEquals(5L * (RecordingMetrics.WRITE_WINDOW + 1), data.bytes);
        assertEquals(RecordingMetrics.WRITE_WINDOW, data.writeSamples);
        assertEquals(1, data.p95WriteMs, 0);
        assertEquals(100, data.maxWriteMs, 0);
        assertTrue(data.meanWriteMs > 1);
    }
}
