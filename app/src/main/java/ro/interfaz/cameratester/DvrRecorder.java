package ro.interfaz.cameratester;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Surface;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Owns one recording run. All storage/codec work stays off the Activity thread. */
final class DvrRecorder implements AutoCloseable {
    interface Listener {
        void onState(String state, String message);
        void onStats(List<CameraEncoder.Snapshot> snapshots);
        void onReport(String report);
        default void onTransportStats(String text) { }
    }
    static final class Config {
        final Uri tree;
        final int[] slots, channelChoices;
        final int width, height, fps, bitrate;
        final boolean benchmark;
        Config(Uri tree, int[] slots, int[] channelChoices, int width, int height, int fps, int bitrate, boolean benchmark) {
            this.tree = tree; this.slots = slots.clone(); this.channelChoices = channelChoices.clone();
            this.width = width; this.height = height; this.fps = fps; this.bitrate = bitrate; this.benchmark = benchmark;
        }
    }
    private static final AtomicInteger HASH = new AtomicInteger(0x6ca00000);
    private static final AtomicBoolean PROCESS_BUSY = new AtomicBoolean();
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> new Thread(r, "six-camera-recording"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<String> fault = new AtomicReference<>();
    private volatile boolean stopRequested, closed;
    private volatile String lastReport = "";

    DvrRecorder(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        control.execute(() -> {
            try {
                lastReport = RecordingReportStore.read(this.context);
                final String saved = lastReport;
                main.post(() -> { if (!closed) listener.onReport(saved); });
            }
            catch (IOException | RuntimeException ignored) { }
        });
    }
    boolean isBusy() { return busy.get(); }
    static boolean anyRecording() { return PROCESS_BUSY.get(); }
    String lastReport() { return lastReport; }
    void start(Config config) {
        if (closed || !busy.compareAndSet(false, true)) return;
        if (!PROCESS_BUSY.compareAndSet(false, true)) {
            busy.set(false);
            state("IDLE", "Another recording is still finishing. Wait before starting a new test.");
            return;
        }
        stopRequested = false; fault.set(null);
        state("PREPARING", config.benchmark ? "Testing USB write speed…" : "Preparing selected cameras and USB storage…");
        try { control.execute(() -> run(config)); }
        catch (RuntimeException failure) {
            busy.set(false); PROCESS_BUSY.set(false);
            state("IDLE", "Cannot schedule recording: " + detail(failure));
        }
    }
    void stop() {
        if (!busy.get()) return;
        stopRequested = true;
        state("STOPPING", "Stopping camera feeds and finalizing MP4 files…");
    }
    @Override public void close() { closed = true; stop(); control.shutdown(); }
    private void state(String state, String message) { main.post(() -> { if (!closed) listener.onState(state, message); }); }
    private void error(String message) { fault.compareAndSet(null, message); stopRequested = true; }
    private void checked() throws IOException {
        if (fault.get() != null) throw new IOException(fault.get());
        if (stopRequested) throw new Cancelled();
    }
    private static final class Cancelled extends IOException { }
    private void append(StringBuilder report, String line) {
        report.append(line).append('\n');
        if (report.length() > 192_000) { report.delete(0, report.length() - 128_000); report.insert(0, "Older report entries omitted.\n"); }
    }
    private void publishReport(StringBuilder report, RecordingStorage storage) {
        String text = report.toString();
        try { RecordingReportStore.write(context, text); }
        catch (IOException | RuntimeException e) { append(report, "Internal report save failed: " + detail(e)); }
        if (storage != null) try { storage.writeReport(report.toString()); }
        catch (IOException | RuntimeException e) { append(report, "USB report save failed: " + detail(e)); }
        lastReport = report.toString();
        final String published = lastReport;
        main.post(() -> { if (!closed) listener.onReport(published); });
    }

    private void run(Config config) {
        StringBuilder report = new StringBuilder();
        append(report, "Camera Tester 0.4.2 — six-camera " + (config.benchmark ? "benchmark" : "recording"));
        append(report, "Started: " + new Date() + "\nDevice: " + Build.MANUFACTURER + " / " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        append(report, "Selected slots: " + Arrays.toString(config.slots) + "; " + config.width + "x" + config.height + " / " + config.fps + " FPS / " + config.bitrate + " bit/s per camera / AVC / no audio");
        append(report, "Loop: 180 seconds. 6camdvr/last is the last finalized segment; current is in progress. Encoder restarts at each boundary; gaps are measured below.");
        append(report, "Benchmark output FPS includes the camera source and encoder. USB write timings include muxer/storage. Codec instance limits are advisory; app CPU excludes the vendor process.");
        append(report, "Image path: TEYES preview -> SurfaceTexture -> OpenGL scaling -> encoder Surface -> AVC -> USB MP4. Source input and encoded output are measured separately.");
        RecordingStorage storage = null;
        TeyesDvr dvr = null;
        int segments = 0;
        long previousEnd = 0;
        String outcome = "Recording stopped.";
        try {
            if (config.tree == null || config.width < 1 || config.height < 1 || config.fps < 1 || config.bitrate < 1)
                throw new IOException("Choose USB storage and a valid recording profile.");
            storage = new RecordingStorage(context, config.tree);
            append(report, config.benchmark ? storage.prepareForBenchmark() : storage.prepare()); checked();
            if (config.benchmark) {
                RecordingStorage.Benchmark disk = storage.benchmark(64);
                double required = config.slots.length * config.bitrate / 8.0 / (1024 * 1024);
                append(report, String.format(Locale.US, "USB test: %d bytes; write %.1f ms; fsync %.1f ms; total %.1f ms; %.2f MiB/s; configured video %.2f MiB/s; free bytes %d", disk.bytes, disk.writeMs, disk.syncMs, disk.totalMs, disk.mebibytesPerSecond, required, disk.freeBytes));
                append(report, disk.mebibytesPerSecond < required * 1.5 ? "USB throughput margin is low for this recording profile." : "Sequential USB throughput has margin; concurrent video test follows.");
                checked();
            }
            dvr = new TeyesDvr(context);
            TeyesDvr.Info info = connect(dvr); checked();
            append(report, "TEYES configuration: AVM=" + info.avmEnable + ", hardware=" + info.avmHwSupport
                    + ", CSI0=" + info.csi0Mode + ", CSI1=" + info.csi1Mode + ", rawAR=" + info.rawArFormat
                    + ", rawReverse=" + info.rawReverseFormat + ", serverAR=" + info.arFormat
                    + ", serverReverse=" + info.reverseFormat + ", signals=" + Arrays.toString(info.cameraExist));
            int[] channels = RecordingPolicy.channels(config.slots, config.channelChoices, info);
            append(report, "Effective TEYES channels: " + Arrays.toString(channels));
            long duration = config.benchmark ? RecordingPolicy.BENCHMARK_MS : RecordingPolicy.SEGMENT_MS;
            while (!stopRequested) {
                long free = storage.freeBytes();
                long needed = RecordingPolicy.requiredFreeBytes(config.slots.length, config.bitrate, duration);
                if (free >= 0 && free < needed) throw new IOException("Insufficient USB free space for the next segment. Free=" + free + ", required with reserve=" + needed);
                RecordingStorage.Segment segment = config.benchmark
                        ? storage.beginBenchmarkSegment(config.slots) : storage.beginSegment(config.slots);
                List<CameraEncoder> encoders = new ArrayList<>();
                List<CameraFrameBridge> bridges = new ArrayList<>();
                List<Surface> inputs = new ArrayList<>();
                int[] hashes = new int[channels.length];
                boolean allValid = true, producerStopped = true;
                long segmentStart = SystemClock.elapsedRealtime();
                long cpuStart = android.os.Process.getElapsedCpuTime();
                String segmentError = null;
                try {
                    state("PREPARING", "Starting " + channels.length + " recording channels…");
                    for (int i = 0; i < channels.length; i++) {
                        checked();
                        final String position = Source.SLOTS[config.slots[i]];
                        CameraEncoder encoder = new CameraEncoder(new CameraEncoder.Config(position, channels[i], config.width, config.height, config.fps, config.bitrate), segment.fd(config.slots[i]), new CameraEncoder.Listener() {
                            @Override public void onError(String message) { error(position + ": " + message); }
                        });
                        encoders.add(encoder);
                        Surface encoderInput = encoder.start(); checked();
                        // Keep the same SurfaceTexture consumer used by the verified preview route.
                        // The vendor chooses its own source buffer size; GL scales it independently.
                        CameraFrameBridge bridge = new CameraFrameBridge(channels[i], 1280, 720,
                                config.width, config.height, encoderInput,
                                message -> error(position + ": " + message));
                        bridges.add(bridge);
                        inputs.add(bridge.start()); checked();
                    }
                    for (int i = 0; i < channels.length; i++) {
                        checked();
                        int hash = HASH.incrementAndGet(); if (hash == 0) hash = HASH.incrementAndGet();
                        hashes[i] = hash;
                        startFeed(dvr, channels[i], inputs.get(i), hash);
                    }
                    long firstDeadline = SystemClock.elapsedRealtime() + 15_000;
                    boolean announced = false;
                    long captureStart = 0;
                    long[] frameCounts = new long[encoders.size()], lastProgress = new long[encoders.size()];
                    Arrays.fill(lastProgress, SystemClock.elapsedRealtime());
                    while (!stopRequested && (captureStart == 0 || SystemClock.elapsedRealtime() - captureStart < duration)) {
                        List<CameraEncoder.Snapshot> snapshots = snapshots(encoders);
                        boolean ready = true;
                        long now = SystemClock.elapsedRealtime();
                        for (int i = 0; i < snapshots.size(); i++) {
                            CameraEncoder.Snapshot snapshot = snapshots.get(i);
                            if (snapshot.frames == 0) ready = false;
                            if (snapshot.error != null) throw new IOException(snapshot.error);
                            if (snapshot.frames != frameCounts[i]) { frameCounts[i] = snapshot.frames; lastProgress[i] = now; }
                            if (announced && now - lastProgress[i] > 10_000) throw new IOException(snapshot.config.slot + ": no encoded frame for 10 seconds.");
                        }
                        String transport = describeBridges(config.slots, bridges, false);
                        if (!ready && now > firstDeadline) {
                            append(report, describeBridges(config.slots, bridges, true));
                            throw new IOException(firstFrameFailure(config.slots, bridges, snapshots));
                        }
                        if (ready && !announced) {
                            announced = true;
                            captureStart = now;
                            append(report, "Segment " + (segments + 1) + " first encoded frames ready after " + (now - segmentStart) + " ms" + (previousEnd == 0 ? "" : "; rollover gap until all channels ready " + (now - previousEnd) + " ms"));
                        }
                        if (announced) state("RECORDING", (config.benchmark ? "Benchmark" : "Recording") + " · " + channels.length + " cameras · " + Math.max(0, (duration - (now - captureStart)) / 1000) + " s remaining in segment");
                        main.post(() -> { if (!closed) { listener.onTransportStats(transport); listener.onStats(snapshots); } });
                        Thread.sleep(500);
                    }
                    if (fault.get() != null) throw new IOException(fault.get());
                } catch (Cancelled cancelled) { /* A user/lifecycle stop finalizes received frames. */ }
                catch (Exception e) { segmentError = detail(e); error(segmentError); }
                finally {
                    state("STOPPING", "Finalizing " + encoders.size() + " MP4 files…");
                    previousEnd = SystemClock.elapsedRealtime();
                    for (int i = 0; i < hashes.length; i++) if (hashes[i] != 0) {
                        try { stopFeed(dvr, channels[i], hashes[i]); }
                        catch (IOException e) { producerStopped = false; append(report, "Feed stop: " + e.getMessage()); }
                    }
                    // Stop every GL producer before any borrowed encoder Surface can be released.
                    for (CameraFrameBridge bridge : bridges) bridge.requestStop();
                    long bridgeDeadline = SystemClock.elapsedRealtime() + 6000;
                    for (CameraFrameBridge bridge : bridges)
                        bridge.awaitClosed(Math.max(0, bridgeDeadline - SystemClock.elapsedRealtime()));
                    if (bridges.stream().anyMatch(bridge -> !bridge.isClosed())) {
                        append(report, "Camera image cleanup exceeds 6 seconds; waiting for terminal results. Recording files and encoders remain open.");
                        append(report, describeBridges(config.slots, bridges, true));
                        publishReport(report, null);
                    }
                    while (bridges.stream().anyMatch(bridge -> !bridge.isClosed())) {
                        state("STOPPING", "Waiting for camera image processing to release the encoders…");
                        SystemClock.sleep(250);
                    }
                    String transport = describeBridges(config.slots, bridges, false);
                    append(report, describeBridges(config.slots, bridges, true));
                    main.post(() -> { if (!closed) listener.onTransportStats(transport); });
                    allValid = encoders.size() == channels.length;
                    for (CameraFrameBridge bridge : bridges) if (bridge.snapshot().error != null) allValid = false;
                    // All GL producers are closed. Request EOS on every encoder before
                    // waiting, and judge the files only after their owners have exited.
                    // An 8-second observation is diagnostic, not a terminal failure.
                    List<CameraEncoder.StopResult> finalResults = finishEncoders(encoders, 8000, () -> {
                        append(report, "Encoder finalization exceeds 8 seconds; waiting for terminal results. Output files remain open and are not reused.");
                        for (CameraEncoder encoder : encoders) append(report, encoder.describeShutdown());
                        state("STOPPING", "MP4 finalization is taking longer than expected. Waiting before starting the next segment…");
                        publishReport(report, null);
                    });
                    for (int i = 0; i < finalResults.size(); i++) {
                        CameraEncoder.StopResult stopped = finalResults.get(i);
                        allValid &= stopped.valid;
                        append(report, describe(stopped.snapshot));
                        append(report, encoders.get(i).describeShutdown());
                        if (stopped.error != null) append(report, "Finalization: " + stopped.error);
                    }
                    long bytes = 0;
                    for (int slot : config.slots) bytes += Math.max(0, segment.sizeBytes(slot));
                    append(report, String.format(Locale.US, "Segment wall %.2f s; files %d bytes; app CPU %.1f%% (one core=100%%); PSS %.1f MiB; thermal status %s", (SystemClock.elapsedRealtime() - segmentStart) / 1000.0, bytes, 100.0 * (android.os.Process.getElapsedCpuTime() - cpuStart) / Math.max(1, SystemClock.elapsedRealtime() - segmentStart), pssMiB(), thermal()));
                    if (allValid && !config.benchmark && segmentError == null) {
                        if (stopRequested) { segment.finishCurrent(); append(report, "Stopped segment finalized in 6camdvr/current; the previous complete segment remains in last."); }
                        else { segment.finish(); append(report, "Segment finalized and promoted to 6camdvr/last."); }
                        segments++;
                    }
                    else { segment.abort(); append(report, config.benchmark ? "Temporary benchmark videos removed; last recording preserved." : "Incomplete or failed segment removed; last complete recording preserved."); }
                    if (!producerStopped) error("TEYES feed cleanup was not acknowledged; restart recording only after the service recovers.");
                    if (!allValid && fault.get() == null) error("MP4 finalization failed. See the per-camera finalization details in the report. The previous complete recording was preserved.");
                    publishReport(report, storage);
                }
                if (config.benchmark) { outcome = stopRequested ? "Benchmark stopped early. Partial measurements saved." : "Benchmark complete. Review the measurements and export the report."; break; }
                checked();
            }
        } catch (Cancelled ignored) { }
        catch (Exception e) { error(detail(e)); }
        finally {
            try {
                try { if (dvr != null) dvr.close(); }
                catch (RuntimeException e) { error("Camera cleanup: " + detail(e)); }
                finally {
                    // Codec workers have exited or remain quarantined above before this scope is reached.
                    try { if (storage != null) storage.close(); }
                    catch (IOException | RuntimeException e) { error("Storage cleanup: " + detail(e)); append(report, "Storage cleanup: " + detail(e)); }
                }
                if (fault.get() != null) outcome = "Stopped: " + fault.get();
                append(report, "Result: " + outcome + "\nFinalized recording segments: " + segments);
                publishReport(report, storage);
            } finally {
                busy.set(false);
                PROCESS_BUSY.set(false);
                state("IDLE", outcome);
            }
        }
    }
    /** Called only after all producers stop using the encoders' input Surfaces. */
    static List<CameraEncoder.StopResult> finishEncoders(List<CameraEncoder> encoders, long warningMs, Runnable onPending) {
        for (CameraEncoder encoder : encoders) encoder.requestStop();
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, warningMs);
        for (CameraEncoder encoder : encoders)
            encoder.awaitClosed(Math.max(0, deadline - SystemClock.elapsedRealtime()));
        try {
            if (encoders.stream().anyMatch(encoder -> !encoder.isClosed())) onPending.run();
        } finally {
            // Native codec/USB calls cannot safely be interrupted. Keep all borrowed
            // descriptors alive until every worker exits, even after a user stop or
            // a diagnostic callback failure.
            while (encoders.stream().anyMatch(encoder -> !encoder.isClosed())) SystemClock.sleep(250);
        }
        List<CameraEncoder.StopResult> results = new ArrayList<>();
        for (CameraEncoder encoder : encoders) results.add(encoder.stopResult());
        return results;
    }
    private TeyesDvr.Info connect(TeyesDvr dvr) throws IOException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TeyesDvr.Info> info = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        dvr.connect(new TeyesDvr.Callback() {
            public void connected(TeyesDvr.Info value) { info.set(value); latch.countDown(); }
            public void failed(String message) { failure.set(message); error(message); latch.countDown(); }
        });
        await(latch, failure, "Connecting to TEYES");
        if (info.get() == null) throw new IOException("TEYES configuration is missing.");
        return info.get();
    }
    private void startFeed(TeyesDvr dvr, int channel, Surface input, int hash) throws IOException {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        dvr.start(channel, input, hash, message -> { failure.set(message); latch.countDown(); });
        await(latch, failure, "Starting channel " + channel);
    }
    private void stopFeed(TeyesDvr dvr, int channel, int hash) throws IOException {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        dvr.stop(channel, hash, message -> { failure.set(message); latch.countDown(); });
        await(latch, failure, "Stopping channel " + channel);
    }
    private static void await(CountDownLatch latch, AtomicReference<String> error, String operation) throws IOException {
        try { if (!latch.await(6500, TimeUnit.MILLISECONDS)) throw new IOException(operation + " timed out."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(operation + " interrupted.", e); }
        if (error.get() != null) throw new IOException(error.get());
    }
    private static List<CameraEncoder.Snapshot> snapshots(List<CameraEncoder> encoders) {
        List<CameraEncoder.Snapshot> snapshots = new ArrayList<>();
        for (CameraEncoder encoder : encoders) snapshots.add(encoder.snapshot());
        return snapshots;
    }
    private static String describeBridges(int[] slots, List<CameraFrameBridge> bridges, boolean diagnostics) {
        StringBuilder text = new StringBuilder("Camera input / encoder input:\n");
        for (int i = 0; i < bridges.size(); i++) {
            CameraFrameBridge.Snapshot s = bridges.get(i).snapshot();
            text.append(Source.SLOTS[slots[i]]).append(": received ").append(s.consumedFrames)
                    .append("; sent to encoder ").append(s.submittedFrames)
                    .append(String.format(Locale.US, "; input %.1f FPS; GPU submit mean/max %.2f/%.2f ms",
                            s.consumedFps, s.swapMeanMs, s.swapMaxMs));
            if (diagnostics) text.append("; callbacks=").append(s.frameCallbacks)
                    .append("; coalesced=").append(s.coalescedCallbacks).append("; first frame ms=").append(s.firstFrameDelayMs)
                    .append("; source timestamp ns=").append(s.sourceTimestampNs)
                    .append("; presentation ns=").append(s.lastPresentationNs)
                    .append("; timestamp corrections=").append(s.timestampCorrections)
                    .append(String.format(Locale.US, "; source PTS FPS=%.2f; render mean/max=%.2f/%.2f ms",
                            s.sourceFps, s.renderMeanMs, s.renderMaxMs));
            if (s.error != null) text.append("; error=").append(s.error);
            text.append('\n');
        }
        return text.toString();
    }
    private static String firstFrameFailure(int[] slots, List<CameraFrameBridge> bridges, List<CameraEncoder.Snapshot> encoders) {
        for (int i = 0; i < encoders.size(); i++) if (encoders.get(i).frames == 0) {
            CameraFrameBridge.Snapshot source = bridges.get(i).snapshot();
            String camera = Source.SLOTS[slots[i]];
            if (source.frameCallbacks == 0) return camera + ": TEYES supplied no preview frame within 15 seconds. Check the direct preview and export this report.";
            if (source.submittedFrames == 0) return camera + ": camera frames arrived but none reached the encoder input. Export the image-processing diagnostics.";
            return camera + ": " + source.submittedFrames + " frames reached the encoder input, but no encoded frame was returned within 15 seconds. Export the report.";
        }
        return "Not all selected cameras produced encoded frames within 15 seconds. Export the report.";
    }
    private String describe(CameraEncoder.Snapshot s) {
        String result = String.format(Locale.US, "%s ch%d: codec=%s hardware=%s maxInstances=%d; frames=%d bytes=%d outputFPS=%.2f PTS_FPS=%.2f mediaSeconds=%.3f; write mean/p95/max=%.3f/%.3f/%.3f ms; maxOutputGap=%.1f ms maxPtsGap=%.1f ms firstFrame=%.1f ms PTS corrections=%d; finalized=%s", s.config.slot, s.config.channel, s.codecName, s.hardwareAccelerated, s.maxSupportedInstances, s.frames, s.encodedBytes, s.observedFps, s.ptsFps, s.durationSeconds, s.meanWriteMs, s.p95WriteMs, s.maxWriteMs, s.maxOutputGapMs, s.maxPtsGapMs, s.firstFrameDelayMs, s.ptsCorrections, s.finalized);
        result += "\nOutput FPS basis: " + s.fpsMeasurement;
        if (s.frames > 10 && s.observedFps < s.config.fps * .85) result += "\nThroughput below target: camera cadence, encoder capacity or storage may be limiting. Compare USB test and write timings; this is not a unique diagnosis.";
        if (s.p95WriteMs > 1000.0 / s.config.fps) result += "\nMP4 write p95 exceeds one frame interval; storage/muxer stalls are a possible bottleneck.";
        if (!s.hardwareAccelerated) result += "\nSoftware encoder selected; CPU load may limit simultaneous recording.";
        return result;
    }
    private double pssMiB() { Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory); return memory.getTotalPss() / 1024.0; }
    private String thermal() {
        if (Build.VERSION.SDK_INT < 29) return "unavailable";
        PowerManager manager = context.getSystemService(PowerManager.class);
        return manager == null ? "unavailable" : Integer.toString(manager.getCurrentThermalStatus());
    }
    private static String detail(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
