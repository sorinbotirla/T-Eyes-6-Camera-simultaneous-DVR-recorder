package ro.interfaz.cameratester;

import java.util.Arrays;

/** Thread-safe segment measurements. Encoded bytes exclude MP4 container overhead. */
final class RecordingMetrics {
    // Exact for a three-minute segment at up to 60 FPS. Longer captures use the
    // most recent window for p95 only; mean and maximum still cover all samples.
    static final int WRITE_WINDOW = 16384;
    private final long[] writeNanos = new long[WRITE_WINDOW];
    private final long startNanos;
    private long frames, bytes, writeTotalNanos, writeMaxNanos;
    private long firstFrameNanos = -1, lastFrameNanos = -1, endNanos = -1;
    private long firstRawPts, lastPts = -1, maxPtsGapUs, maxOutputGapNanos, ptsCorrections;
    private boolean haveRawPts;
    private int writeCount, nextWrite;

    RecordingMetrics(long startNanos) { this.startNanos = startNanos; }

    /** Make vendor timestamps local to this MP4 and strictly increasing. */
    synchronized long normalizePts(long rawPtsUs) {
        if (!haveRawPts) { firstRawPts = rawPtsUs; haveRawPts = true; }
        long pts = Math.max(0, rawPtsUs - firstRawPts);
        if (lastPts >= 0 && pts <= lastPts) {
            pts = lastPts + 1;
            ptsCorrections++;
        }
        return pts;
    }

    /** Called only after the encoded sample was successfully accepted by the muxer. */
    synchronized boolean sample(long ptsUs, int byteCount, long writeDurationNanos, long nowNanos) {
        if (byteCount <= 0 || writeDurationNanos < 0) throw new IllegalArgumentException("Invalid encoded sample measurement.");
        if (lastPts >= 0 && ptsUs <= lastPts) throw new IllegalArgumentException("Encoded timestamps must increase.");
        if (firstFrameNanos < 0) firstFrameNanos = nowNanos;
        if (lastFrameNanos >= 0) maxOutputGapNanos = Math.max(maxOutputGapNanos, Math.max(0, nowNanos - lastFrameNanos));
        if (lastPts >= 0) maxPtsGapUs = Math.max(maxPtsGapUs, ptsUs - lastPts);
        lastPts = ptsUs; lastFrameNanos = nowNanos;
        frames++; bytes += byteCount;
        writeTotalNanos += writeDurationNanos;
        writeMaxNanos = Math.max(writeMaxNanos, writeDurationNanos);
        writeNanos[nextWrite] = writeDurationNanos;
        nextWrite = (nextWrite + 1) % WRITE_WINDOW;
        writeCount = Math.min(WRITE_WINDOW, writeCount + 1);
        return frames == 1;
    }

    synchronized void finish(long nowNanos) { if (endNanos < 0) endNanos = nowNanos; }

    synchronized Data snapshot(long nowNanos) {
        long time = endNanos >= 0 ? endNanos : nowNanos;
        double elapsed = Math.max(0, time - startNanos) / 1_000_000_000d;
        // Live samples include the current idle interval so a stopped/stalled
        // producer reduces FPS. Once closed, use sample arrival cadence rather
        // than USB finalization or time spent stopping other encoders.
        long fpsEnd = endNanos >= 0 ? lastFrameNanos : time;
        double fromFirst = firstFrameNanos < 0 ? 0 : Math.max(0, fpsEnd - firstFrameNanos) / 1_000_000_000d;
        double fps = frames > 1 && fromFirst > 0 ? (frames - 1) / fromFirst : 0;
        double ptsSeconds = Math.max(0, lastPts) / 1_000_000d;
        double ptsFps = frames > 1 && ptsSeconds > 0 ? (frames - 1) / ptsSeconds : 0;
        long[] sorted = Arrays.copyOf(writeNanos, writeCount);
        Arrays.sort(sorted);
        double p95 = writeCount == 0 ? 0 : sorted[(int) Math.ceil(writeCount * .95) - 1] / 1_000_000d;
        return new Data(frames, bytes, elapsed, ptsSeconds, fps, ptsFps,
                frames == 0 ? 0 : writeTotalNanos / 1_000_000d / frames, p95, writeMaxNanos / 1_000_000d,
                maxPtsGapUs / 1000d, maxOutputGapNanos / 1_000_000d,
                firstFrameNanos < 0 ? -1 : Math.max(0, firstFrameNanos - startNanos) / 1_000_000d,
                ptsCorrections, writeCount, endNanos >= 0
                        ? "first to last encoded frame arrival; excludes finalization"
                        : "first encoded frame to current wall time");
    }

    static final class Data {
        final long frames, bytes, ptsCorrections;
        final double elapsedSeconds, durationSeconds, observedFps, ptsFps;
        final double meanWriteMs, p95WriteMs, maxWriteMs, maxPtsGapMs, maxOutputGapMs, firstFrameDelayMs;
        final int writeSamples;
        final String fpsMeasurement;
        Data(long frames, long bytes, double elapsed, double duration, double fps, double ptsFps,
             double mean, double p95, double max, double ptsGap, double outputGap, double first,
             long corrections, int samples, String fpsMeasurement) {
            this.frames = frames; this.bytes = bytes; elapsedSeconds = elapsed; durationSeconds = duration;
            observedFps = fps; this.ptsFps = ptsFps; meanWriteMs = mean; p95WriteMs = p95; maxWriteMs = max;
            maxPtsGapMs = ptsGap; maxOutputGapMs = outputGap; firstFrameDelayMs = first;
            ptsCorrections = corrections; writeSamples = samples; this.fpsMeasurement = fpsMeasurement;
        }
    }
}
