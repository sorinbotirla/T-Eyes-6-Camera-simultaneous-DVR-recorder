package ro.interfaz.cameratester;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One AVC encoder and one MP4 segment. start() and stop() are background calls.
 * Give the returned Surface to TEYES and stop its producer before stop(). All
 * Listener methods run on the drain worker; implementations must post UI work.
 * The output descriptor is borrowed and is never closed here. Do not promote or
 * otherwise use its file until StopResult.valid is true. A timed-out stop leaves
 * cleanup with the worker; quarantine the file until isClosed() becomes true.
 */
final class CameraEncoder {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long EOS_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long STOP_TIMEOUT_MS = 8000;

    static final class Config {
        final String slot;
        final int channel, width, height, fps, bitrate;
        Config(String slot, int channel, int width, int height, int fps, int bitrate) {
            if (slot == null || slot.trim().isEmpty()) throw new IllegalArgumentException("Camera name is missing.");
            if (channel < 0 || channel > 11) throw new IllegalArgumentException("Camera channel must be between 0 and 11.");
            if (width <= 0 || height <= 0 || fps <= 0 || fps > 60 || bitrate <= 0)
                throw new IllegalArgumentException("Invalid recording resolution, frame rate, or bitrate.");
            this.slot = slot; this.channel = channel; this.width = width; this.height = height;
            this.fps = fps; this.bitrate = bitrate;
        }
    }

    interface Listener {
        default void onStats(Snapshot snapshot) {}
        default void onFirstFrame(Snapshot snapshot) {}
        default void onError(String error) {}
    }

    static final class Snapshot {
        final Config config;
        final long frames, encodedBytes, ptsCorrections;
        final double elapsedSeconds, durationSeconds, observedFps, ptsFps;
        final double meanWriteMs, p95WriteMs, maxWriteMs, maxPtsGapMs, maxOutputGapMs, firstFrameDelayMs;
        final String codecName, error, fpsMeasurement, shutdownPhase;
        final double shutdownElapsedMs, shutdownPhaseElapsedMs, eosWaitMs, muxerStopMs, muxerReleaseMs, codecStopMs, codecReleaseMs;
        final boolean hardwareAccelerated, hardwareDetectionExact, finalized;
        final int maxSupportedInstances, writeSamples;
        Snapshot(Config config, RecordingMetrics.Data data, CodecChoice choice, boolean finalized, String error, ShutdownDiagnostics.Data shutdown) {
            this.config = config; frames = data.frames; encodedBytes = data.bytes;
            elapsedSeconds = data.elapsedSeconds; durationSeconds = data.durationSeconds;
            observedFps = data.observedFps; ptsFps = data.ptsFps;
            fpsMeasurement = data.fpsMeasurement;
            meanWriteMs = data.meanWriteMs; p95WriteMs = data.p95WriteMs; maxWriteMs = data.maxWriteMs;
            maxPtsGapMs = data.maxPtsGapMs; maxOutputGapMs = data.maxOutputGapMs;
            firstFrameDelayMs = data.firstFrameDelayMs; ptsCorrections = data.ptsCorrections;
            writeSamples = data.writeSamples;
            codecName = choice == null ? "Not initialized" : choice.name;
            hardwareAccelerated = choice != null && choice.hardware;
            hardwareDetectionExact = Build.VERSION.SDK_INT >= 29;
            maxSupportedInstances = choice == null ? 0 : choice.maxInstances;
            this.finalized = finalized; this.error = error;
            shutdownPhase = shutdown.phase; shutdownElapsedMs = shutdown.totalMs; shutdownPhaseElapsedMs = shutdown.phaseMs;
            eosWaitMs = shutdown.durations[ShutdownDiagnostics.EOS]; muxerStopMs = shutdown.durations[ShutdownDiagnostics.MUXER_STOP];
            muxerReleaseMs = shutdown.durations[ShutdownDiagnostics.MUXER_RELEASE]; codecStopMs = shutdown.durations[ShutdownDiagnostics.CODEC_STOP];
            codecReleaseMs = shutdown.durations[ShutdownDiagnostics.CODEC_RELEASE];
        }
    }

    static final class StopResult {
        final Snapshot snapshot;
        final boolean completed, valid;
        final String error;
        StopResult(Snapshot snapshot, boolean completed, boolean valid, String error) {
            this.snapshot = snapshot; this.completed = completed; this.valid = valid; this.error = error;
        }
    }

    private final Object lifecycle = new Object();
    private final Config config;
    private final FileDescriptor output;
    private final Listener listener;
    private final CountDownLatch closed = new CountDownLatch(1);
    // A worker error may arrive while a GL bridge still uses our input Surface.
    // Only the owner, after closing that bridge, authorizes consumer release.
    private final CountDownLatch inputReleaseAuthorized = new CountDownLatch(1);
    private final ShutdownDiagnostics shutdown = new ShutdownDiagnostics();
    private volatile RecordingMetrics metrics = new RecordingMetrics(SystemClock.elapsedRealtimeNanos());
    private volatile CodecChoice choice;
    private volatile boolean stopRequested, finalized;
    private volatile String failure;
    private boolean attempted, errorNotified;
    // Only the start thread, then the drain worker, touches these resources.
    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface surface;
    private boolean codecStarted, muxerStarted, reachedEos;

    CameraEncoder(Config config, FileDescriptor output, Listener listener) {
        if (config == null || output == null || listener == null) throw new IllegalArgumentException("Recording configuration, output, and listener are required.");
        this.config = config; this.output = output; this.listener = listener;
    }

    Surface start() throws IOException {
        synchronized (lifecycle) {
            if (attempted || stopRequested) throw new IOException("This encoder has already been started or stopped.");
            attempted = true;
        }
        metrics = new RecordingMetrics(SystemClock.elapsedRealtimeNanos());
        shutdown.phase("Initializing encoder");
        boolean handedToWorker = false;
        try {
            if (!output.valid()) throw new IOException("The recording output descriptor is closed.");
            List<CodecChoice> candidates = candidates(config);
            if (candidates.isEmpty()) throw new IOException("No AVC Surface encoder supports " + config.width + "x" + config.height + " at " + config.fps + " FPS and " + config.bitrate + " bit/s.");
            StringBuilder rejected = new StringBuilder();
            for (CodecChoice candidate : candidates) {
                checkCancelled();
                try {
                    choice = candidate;
                    codec = MediaCodec.createByCodecName(candidate.name);
                    codec.configure(format(candidate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = codec.createInputSurface();
                    codec.start(); codecStarted = true;
                    break;
                } catch (IOException | RuntimeException e) {
                    if (rejected.length() > 0) rejected.append("; ");
                    rejected.append(candidate.name).append(": ").append(detail(e));
                    releaseCodec();
                }
            }
            if (!codecStarted) throw new IOException("Cannot start an AVC encoder. Available hardware or memory may be exhausted. " + rejected);
            checkCancelled();
            muxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            checkCancelled();
            Surface input = surface;
            Thread worker = new Thread(this::drain, "record-" + config.channel);
            worker.setDaemon(true);
            worker.start();
            handedToWorker = true;
            return input;
        } catch (IOException | RuntimeException e) {
            fail("Cannot initialize " + config.slot + ": " + detail(e));
            throw new IOException(failure, e);
        } finally {
            if (!handedToWorker) cleanup(false);
        }
    }

    Snapshot snapshot() {
        return new Snapshot(config, metrics.snapshot(SystemClock.elapsedRealtimeNanos()), choice, finalized, failure, shutdown.snapshot());
    }

    boolean isClosed() { return closed.getCount() == 0; }

    /** Nonblocking; call only after all GL producers using this encoder have closed. */
    void requestStop() {
        synchronized (lifecycle) {
            shutdown.stopRequested();
            stopRequested = true;
            inputReleaseAuthorized.countDown();
            if (!attempted) { attempted = true; metrics.finish(SystemClock.elapsedRealtimeNanos()); shutdown.closed(); closed.countDown(); }
        }
    }

    boolean awaitClosed(long timeoutMs) {
        if (timeoutMs < 0) throw new IllegalArgumentException("Encoder cleanup timeout must not be negative.");
        try { return closed.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return isClosed(); }
    }

    /** A fresh result: pending is an observation, never a sticky recording failure. */
    StopResult stopResult() {
        boolean done = isClosed();
        Snapshot stats = snapshot();
        String error = !done ? "Encoder shutdown is still pending. The segment must not be promoted or reused."
                : failure != null ? failure : !finalized ? "No complete video segment was produced." : null;
        return new StopResult(stats, done, done && finalized && failure == null, error);
    }

    StopResult stop() {
        requestStop();
        awaitClosed(STOP_TIMEOUT_MS);
        return stopResult();
    }

    String describeShutdown() {
        Snapshot stats = snapshot();
        return String.format(Locale.US, "%s ch%d shutdown: phase=%s; phase elapsed=%s; since stop request=%s; EOS signal/drain=%s; muxer stop/release=%s/%s; codec stop/release=%s/%s; closed=%s; finalized=%s",
                config.slot, config.channel, stats.shutdownPhase, ms(stats.shutdownPhaseElapsedMs), ms(stats.shutdownElapsedMs),
                ms(stats.eosWaitMs), ms(stats.muxerStopMs), ms(stats.muxerReleaseMs), ms(stats.codecStopMs), ms(stats.codecReleaseMs),
                isClosed(), stats.finalized);
    }

    private static String ms(double value) { return value < 0 ? "n/a" : String.format(Locale.US, "%.1f ms", value); }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean signalledEos = false;
        long eosDeadline = Long.MAX_VALUE, nextStats = SystemClock.elapsedRealtimeNanos();
        try {
            while (true) {
                if (stopRequested && !signalledEos) {
                    shutdown.begin(ShutdownDiagnostics.EOS, "Signalling encoder EOS");
                    codec.signalEndOfInputStream();
                    signalledEos = true;
                    eosDeadline = SystemClock.elapsedRealtimeNanos() + EOS_TIMEOUT_NS;
                }
                if (signalledEos && SystemClock.elapsedRealtimeNanos() >= eosDeadline)
                    throw new IOException("Encoder did not finish its MP4 segment within 5 seconds of the stop request.");
                shutdown.phase(signalledEos ? "Waiting for encoder EOS" : "Waiting for encoder output");
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IOException("The encoder changed its output format after recording began.");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start(); muxerStarted = true;
                } else if (index >= 0) {
                    try {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            if (!muxerStarted) throw new IOException("Encoded video arrived before its MP4 track format.");
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            if (buffer == null) throw new IOException("The encoder returned an empty output buffer.");
                            buffer.position(info.offset); buffer.limit(info.offset + info.size);
                            info.presentationTimeUs = metrics.normalizePts(info.presentationTimeUs);
                            shutdown.phase("Writing MP4 sample");
                            long writeStart = SystemClock.elapsedRealtimeNanos();
                            muxer.writeSampleData(track, buffer, info);
                            long now = SystemClock.elapsedRealtimeNanos();
                            boolean first = metrics.sample(info.presentationTimeUs, info.size, now - writeStart, now);
                            if (first) notifyFirstFrame();
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            reachedEos = true;
                            shutdown.end(ShutdownDiagnostics.EOS);
                            if (!signalledEos) throw new IOException("The encoder ended its output before a stop was requested.");
                            break;
                        }
                    } finally { shutdown.phase("Releasing encoder output buffer"); codec.releaseOutputBuffer(index, false); }
                }
                long now = SystemClock.elapsedRealtimeNanos();
                if (now >= nextStats) { notifyStats(); nextStats = now + TimeUnit.SECONDS.toNanos(1); }
            }
        } catch (IOException | RuntimeException e) {
            fail("Recording failed for " + config.slot + ": " + detail(e));
            notifyError();
        } finally {
            shutdown.end(ShutdownDiagnostics.EOS);
            cleanup(true);
            notifyStats();
        }
    }

    private void checkCancelled() throws IOException {
        if (stopRequested) throw new IOException("Recording was cancelled during encoder initialization.");
    }

    private void cleanup(boolean reportFailure) {
        if (reportFailure) {
            // Expose a worker failure immediately, but retain the codec and its
            // Surface until DvrRecorder has stopped and closed every GL bridge.
            notifyError();
            // A USB/muxer error does not necessarily break the codec. Leaving
            // its output queue full would block eglSwapBuffers in the bridge,
            // preventing the owner from ever closing that producer. Discard
            // encoded output here until the owner authorizes consumer release.
            shutdown.phase("Waiting for camera producer release");
            MediaCodec.BufferInfo discarded = new MediaCodec.BufferInfo();
            EncoderReleaseDrain.await(inputReleaseAuthorized, codecStarted && codec != null ? () -> {
                int index = codec.dequeueOutputBuffer(discarded, 10_000);
                if (index >= 0) codec.releaseOutputBuffer(index, false);
            } : null);
        }
        boolean mp4Stopped = false;
        try {
            if (muxer != null && muxerStarted) {
                shutdown.begin(ShutdownDiagnostics.MUXER_STOP, "Stopping MP4 muxer");
                try { muxer.stop(); mp4Stopped = true; }
                catch (RuntimeException e) { fail("Cannot finalize " + config.slot + " MP4: " + detail(e)); }
                finally { shutdown.end(ShutdownDiagnostics.MUXER_STOP); }
            }
        } finally {
            try {
                if (muxer != null) {
                    shutdown.begin(ShutdownDiagnostics.MUXER_RELEASE, "Releasing MP4 muxer");
                    try { muxer.release(); }
                    catch (RuntimeException e) { fail("Cannot release " + config.slot + " MP4 writer: " + detail(e)); }
                    finally { shutdown.end(ShutdownDiagnostics.MUXER_RELEASE); }
                    muxer = null; muxerStarted = false;
                }
            } finally {
                releaseCodec();
                metrics.finish(SystemClock.elapsedRealtimeNanos());
                finalized = mp4Stopped && reachedEos && metrics.snapshot(SystemClock.elapsedRealtimeNanos()).frames > 0 && failure == null;
                if (!finalized && failure == null) fail("No complete video frames were finalized for " + config.slot + ".");
                // A cleanup-only error must reach this run before stop() can
                // return. Resources are already released, so even a failing
                // listener cannot interfere with their cleanup.
                try { shutdown.phase("Completing encoder cleanup"); if (reportFailure) notifyError(); }
                finally { shutdown.closed(); closed.countDown(); }
            }
        }
    }

    private void releaseCodec() {
        if (codec != null) {
            try {
                if (codecStarted) {
                    shutdown.begin(ShutdownDiagnostics.CODEC_STOP, "Stopping codec");
                    try { codec.stop(); }
                    finally { shutdown.end(ShutdownDiagnostics.CODEC_STOP); }
                }
            }
            catch (RuntimeException ignored) { /* Release is still required after a codec state failure. */ }
            finally {
                shutdown.begin(ShutdownDiagnostics.CODEC_RELEASE, "Releasing codec");
                try { codec.release(); }
                catch (RuntimeException ignored) { /* No caller may retry an already-released codec. */ }
                finally { shutdown.end(ShutdownDiagnostics.CODEC_RELEASE); }
                codec = null; codecStarted = false;
            }
        }
        if (surface != null) {
            shutdown.phase("Releasing encoder input Surface");
            try { surface.release(); }
            catch (RuntimeException ignored) { /* Cleanup must still complete after a detached vendor producer. */ }
            surface = null;
        }
    }

    private void fail(String error) { synchronized (lifecycle) { if (failure == null) failure = error; } }
    private void notifyStats() { try { listener.onStats(snapshot()); } catch (RuntimeException ignored) {} }
    private void notifyFirstFrame() { try { listener.onFirstFrame(snapshot()); } catch (RuntimeException ignored) {} }
    private void notifyError() {
        if (failure == null || errorNotified) return;
        errorNotified = true;
        try { listener.onError(failure); } catch (RuntimeException ignored) {}
    }

    private MediaFormat format(CodecChoice candidate) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, config.width, config.height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (candidate.baseline) format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        if (Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            // KEY_FRAME_RATE alone controls rate allocation; this also caps a
            // faster vendor Surface producer at the requested recording rate.
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps);
        }
        if (candidate.cbr) format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        return format;
    }

    private static List<CodecChoice> candidates(Config config) {
        List<CodecChoice> result = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            boolean avc = false;
            for (String type : info.getSupportedTypes()) if (MIME.equalsIgnoreCase(type)) avc = true;
            if (!avc) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME);
                boolean surface = false, baseline = false;
                for (int color : caps.colorFormats) if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                for (MediaCodecInfo.CodecProfileLevel profile : caps.profileLevels)
                    if (profile.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) baseline = true;
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (!surface || video == null || !video.areSizeAndRateSupported(config.width, config.height, config.fps)
                        || !video.getBitrateRange().contains(config.bitrate)) continue;
                boolean hardware = Build.VERSION.SDK_INT >= 29 ? info.isHardwareAccelerated() : !softwareName(info.getName());
                boolean cbr = caps.getEncoderCapabilities().isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                result.add(new CodecChoice(info.getName(), hardware, baseline, cbr, caps.getMaxSupportedInstances()));
            } catch (RuntimeException ignored) { /* A malformed advertised codec must not hide the remaining encoders. */ }
        }
        result.sort(Comparator.comparing((CodecChoice candidate) -> !candidate.hardware));
        return result;
    }

    static boolean softwareName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("c2.google.")
                || lower.contains(".sw.") || lower.contains("software");
    }

    private static final class CodecChoice {
        final String name;
        final boolean hardware, baseline, cbr;
        final int maxInstances;
        CodecChoice(String name, boolean hardware, boolean baseline, boolean cbr, int maxInstances) {
            this.name = name; this.hardware = hardware; this.baseline = baseline; this.cbr = cbr; this.maxInstances = maxInstances;
        }
    }

    /** A single lock provides a consistent phase/timing snapshot across native calls. */
    private static final class ShutdownDiagnostics {
        static final int EOS = 0, MUXER_STOP = 1, MUXER_RELEASE = 2, CODEC_STOP = 3, CODEC_RELEASE = 4;
        private final long[] starts = new long[5], ends = new long[5];
        private String phase = "Not started";
        private long phaseSince = SystemClock.elapsedRealtimeNanos(), stopSince = -1, closedAt = -1;
        ShutdownDiagnostics() { Arrays.fill(starts, -1); Arrays.fill(ends, -1); }
        synchronized void phase(String value) {
            if (!phase.equals(value)) { phase = value; phaseSince = SystemClock.elapsedRealtimeNanos(); }
        }
        synchronized void stopRequested() { if (stopSince < 0) stopSince = SystemClock.elapsedRealtimeNanos(); }
        synchronized void begin(int step, String value) {
            starts[step] = SystemClock.elapsedRealtimeNanos(); ends[step] = -1; phase(value);
        }
        synchronized void end(int step) {
            if (starts[step] >= 0 && ends[step] < 0) ends[step] = SystemClock.elapsedRealtimeNanos();
        }
        synchronized void closed() { closedAt = SystemClock.elapsedRealtimeNanos(); phase = "Closed"; phaseSince = closedAt; }
        synchronized Data snapshot() {
            long now = closedAt >= 0 ? closedAt : SystemClock.elapsedRealtimeNanos();
            double[] durations = new double[starts.length];
            for (int i = 0; i < starts.length; i++) durations[i] = elapsed(starts[i], ends[i] >= 0 ? ends[i] : now);
            return new Data(phase, elapsed(phaseSince, now), elapsed(stopSince, now), durations);
        }
        private static double elapsed(long from, long to) { return from < 0 ? -1 : Math.max(0, to - from) / 1_000_000d; }
        static final class Data {
            final String phase;
            final double phaseMs, totalMs;
            final double[] durations;
            Data(String phase, double phaseMs, double totalMs, double[] durations) {
                this.phase = phase; this.phaseMs = phaseMs; this.totalMs = totalMs; this.durations = durations;
            }
        }
    }

    private static String detail(Exception error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
