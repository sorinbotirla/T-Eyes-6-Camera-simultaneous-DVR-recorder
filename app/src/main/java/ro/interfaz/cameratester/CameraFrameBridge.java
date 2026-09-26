package ro.interfaz.cameratester;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Converts a vendor preview Surface into an encoder Surface using an external
 * OES texture. EGL, texture updates and cleanup stay on one GL HandlerThread.
 * The encoder Surface is BORROWED: its owner must keep it alive until isClosed().
 * Stop the vendor producer, requestStop(), await closed, then stop the encoder.
 * A native GL hang leaves isClosed() false; do not release/reuse the encoder or
 * recording file while this bridge is quarantined. Listener errors run on the
 * GL thread and are delivered after cleanup, before isClosed becomes true.
 */
final class CameraFrameBridge {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final long START_TIMEOUT_MS = 8000;
    private static final String VERTEX =
            "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uTexMatrix; varying vec2 vTexCoord;"
            + "void main(){gl_Position=aPosition;vTexCoord=(uTexMatrix*aTexCoord).xy;}";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float; uniform samplerExternalOES uTexture; varying vec2 vTexCoord;"
            + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";

    interface Listener { void onError(String error); }

    static final class Snapshot {
        final int channel;
        final long frameCallbacks, consumedFrames, submittedFrames, coalescedCallbacks;
        final long sourceTimestampNs, lastPresentationNs, lastFrameElapsedMs, timestampCorrections;
        final double firstFrameDelayMs, consumedFps, sourceFps, renderMeanMs, renderMaxMs, swapMeanMs, swapMaxMs;
        final String error;
        final boolean closed;
        Snapshot(int channel, long callbacks, long consumed, long submitted, long coalesced,
                 long source, long presentation, long lastFrame, long corrections, double firstDelay,
                 double consumedFps, double sourceFps, double renderMean, double renderMax,
                 double swapMean, double swapMax, String error, boolean closed) {
            this.channel = channel; frameCallbacks = callbacks; consumedFrames = consumed; submittedFrames = submitted;
            coalescedCallbacks = coalesced; sourceTimestampNs = source; lastPresentationNs = presentation;
            lastFrameElapsedMs = lastFrame; timestampCorrections = corrections; firstFrameDelayMs = firstDelay;
            this.consumedFps = consumedFps; this.sourceFps = sourceFps; renderMeanMs = renderMean;
            renderMaxMs = renderMax; swapMeanMs = swapMean; swapMaxMs = swapMax; this.error = error; this.closed = closed;
        }
    }

    private final int channel, sourceWidth, sourceHeight, outWidth, outHeight;
    private final Surface encoderSurface;
    private final Listener listener;
    private final Object lifecycle = new Object(), statsLock = new Object();
    private final CountDownLatch ready = new CountDownLatch(1), closedLatch = new CountDownLatch(1);
    private final FrameBridgeMath.PresentationClock clock = new FrameBridgeMath.PresentationClock();
    private final float[] transform = new float[16];
    private final int[] viewport;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private volatile boolean stopRequested;
    private volatile String failure;
    private boolean attempted, cleaned, renderPending;
    private HandlerThread thread;
    private Handler handler;
    private volatile Surface inputSurface;
    private SurfaceTexture texture;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE, window = EGL14.EGL_NO_SURFACE;
    private boolean displayAcquired;
    private int textureId, program, positionLocation, texCoordLocation, matrixLocation, samplerLocation;
    private long startedNs, callbacks, consumed, submitted, coalesced, firstArrivalNs = -1, lastArrivalNs = -1;
    private long firstSourceNs = -1, lastSourceNs, lastPresentationNs, corrections;
    private long renderTotalNs, renderMaxNs, swapTotalNs, swapMaxNs;

    CameraFrameBridge(int channel, int sourceWidth, int sourceHeight, int outWidth, int outHeight,
                      Surface encoderSurface, Listener listener) {
        if (channel < 0 || channel > 11) throw new IllegalArgumentException("Camera channel must be between 0 and 11.");
        viewport = FrameBridgeMath.fitViewport(sourceWidth, sourceHeight, outWidth, outHeight);
        if (encoderSurface == null || listener == null) throw new IllegalArgumentException("Encoder Surface and error listener are required.");
        this.channel = channel; this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        this.outWidth = outWidth; this.outHeight = outHeight; this.encoderSurface = encoderSurface; this.listener = listener;
        vertices.put(new float[]{-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1}).position(0);
    }

    /** Background call. A timeout requests cleanup; caller still waits for isClosed before releasing the encoder. */
    Surface start() throws IOException {
        synchronized (lifecycle) {
            if (attempted || stopRequested) throw new IOException("This camera frame bridge has already been started or stopped.");
            attempted = true; startedNs = SystemClock.elapsedRealtimeNanos();
            try {
                thread = new HandlerThread("camera-gl-" + channel);
                thread.start();
                handler = new Handler(thread.getLooper());
                if (!handler.post(this::initialize)) throw new IllegalStateException("GL initialization could not be queued.");
            } catch (RuntimeException e) {
                // No GL work was queued, so no EGL or camera resources exist.
                failure = "The camera GL thread could not start: " + detail(e);
                try { if (thread != null) thread.quitSafely(); }
                finally { closedLatch.countDown(); ready.countDown(); }
            }
        }
        try {
            if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                fail("Camera GL initialization timed out after 8 seconds."); requestStop();
                throw new IOException(failure);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); requestStop(); throw new IOException("Camera GL initialization was interrupted.", e);
        }
        if (failure != null) throw new IOException(failure);
        if (stopRequested || inputSurface == null) throw new IOException("Camera frame bridge initialization was cancelled.");
        return inputSurface;
    }

    void requestStop() {
        synchronized (lifecycle) {
            stopRequested = true;
            if (!attempted) { attempted = true; ready.countDown(); closedLatch.countDown(); return; }
            if (handler != null && !isClosed()) handler.post(this::cleanup);
        }
    }

    boolean awaitClosed(long timeoutMs) {
        if (timeoutMs < 0) throw new IllegalArgumentException("Cleanup timeout must not be negative.");
        try { return closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return isClosed(); }
    }

    boolean isClosed() { return closedLatch.getCount() == 0; }

    Snapshot snapshot() {
        synchronized (statsLock) {
            double span = firstArrivalNs < 0 ? 0 : Math.max(0, (isClosed() ? lastArrivalNs : SystemClock.elapsedRealtimeNanos()) - firstArrivalNs) / 1_000_000_000d;
            double sourceSpan = firstSourceNs <= 0 || lastSourceNs <= firstSourceNs ? 0 : (lastSourceNs - firstSourceNs) / 1_000_000_000d;
            return new Snapshot(channel, callbacks, consumed, submitted, coalesced, lastSourceNs, lastPresentationNs,
                    lastArrivalNs < 0 ? -1 : lastArrivalNs / 1_000_000, corrections,
                    firstArrivalNs < 0 ? -1 : (firstArrivalNs - startedNs) / 1_000_000d,
                    consumed > 1 && span > 0 ? (consumed - 1) / span : 0,
                    consumed > 1 && sourceSpan > 0 ? (consumed - 1) / sourceSpan : 0,
                    submitted == 0 ? 0 : renderTotalNs / 1_000_000d / submitted, renderMaxNs / 1_000_000d,
                    submitted == 0 ? 0 : swapTotalNs / 1_000_000d / submitted, swapMaxNs / 1_000_000d, failure, isClosed());
        }
    }

    private void initialize() {
        boolean success = false;
        try {
            checkStop();
            if (!encoderSurface.isValid()) throw new IOException("The encoder input Surface is unavailable.");
            display = SharedDisplay.acquire(); displayAcquired = true;
            checkStop();
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            int[] attributes = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                    EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
            require(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0, "No recordable EGL configuration is available");
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            require(eglContext != null && !eglContext.equals(EGL14.EGL_NO_CONTEXT), "Cannot create camera EGL context");
            pbuffer = EGL14.eglCreatePbufferSurface(display, configs[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            require(pbuffer != null && !pbuffer.equals(EGL14.EGL_NO_SURFACE), "Cannot create camera EGL pbuffer");
            require(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext), "Cannot activate camera EGL context");
            checkStop();
            int[] names = new int[1]; GLES20.glGenTextures(1, names, 0); textureId = names[0];
            if (textureId == 0) throw new IOException("Cannot allocate the camera external texture.");
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            program = createProgram();
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition"); texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix"); samplerLocation = GLES20.glGetUniformLocation(program, "uTexture");
            if (positionLocation < 0 || texCoordLocation < 0 || matrixLocation < 0 || samplerLocation < 0) throw new IOException("Camera shader bindings are unavailable.");
            texture = new SurfaceTexture(textureId);
            // A default request only: TEYES can override its native producer size.
            texture.setDefaultBufferSize(sourceWidth, sourceHeight);
            inputSurface = new Surface(texture);
            window = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
            require(window != null && !window.equals(EGL14.EGL_NO_SURFACE), "Cannot attach EGL to the encoder Surface");
            checkGl("Initializing camera bridge"); checkStop();
            texture.setOnFrameAvailableListener(ignored -> frameAvailable(), handler);
            success = true;
        } catch (IOException | RuntimeException e) {
            if (!stopRequested) fail("Camera GL initialization failed for channel " + channel + ": " + detail(e));
        } finally {
            if (!success || stopRequested) cleanup();
            ready.countDown();
        }
    }

    private void frameAvailable() {
        if (stopRequested || cleaned) return;
        synchronized (statsLock) { callbacks++; }
        if (renderPending) { synchronized (statsLock) { coalesced++; } return; }
        renderPending = true;
        handler.post(this::render);
    }

    private void render() {
        renderPending = false;
        if (stopRequested || cleaned) return;
        try {
            long renderStart = SystemClock.elapsedRealtimeNanos();
            require(EGL14.eglMakeCurrent(display, window, window, eglContext), "Cannot activate encoder EGL Surface");
            texture.updateTexImage();
            texture.getTransformMatrix(transform);
            long source = texture.getTimestamp(), arrival = SystemClock.elapsedRealtimeNanos();
            long presentation = clock.next(source, arrival);
            synchronized (statsLock) {
                consumed++; if (firstArrivalNs < 0) { firstArrivalNs = arrival; firstSourceNs = source; }
                lastArrivalNs = arrival; lastSourceNs = source; corrections = clock.corrections;
            }
            if (stopRequested) return;
            GLES20.glViewport(0, 0, outWidth, outHeight); GLES20.glClearColor(0, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(samplerLocation, 0); GLES20.glUniformMatrix4fv(matrixLocation, 1, false, transform, 0);
            vertices.position(0); GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2); GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(positionLocation); GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionLocation); GLES20.glDisableVertexAttribArray(texCoordLocation);
            checkGl("Drawing camera frame");
            require(EGLExt.eglPresentationTimeANDROID(display, window, presentation), "Cannot set encoder presentation timestamp");
            long swapStart = SystemClock.elapsedRealtimeNanos();
            require(EGL14.eglSwapBuffers(display, window), "Cannot submit camera frame to encoder");
            long completed = SystemClock.elapsedRealtimeNanos();
            synchronized (statsLock) {
                submitted++; lastPresentationNs = presentation;
                long renderNs = swapStart - renderStart, swapNs = completed - swapStart;
                renderTotalNs += renderNs; renderMaxNs = Math.max(renderMaxNs, renderNs);
                swapTotalNs += swapNs; swapMaxNs = Math.max(swapMaxNs, swapNs);
            }
        } catch (IOException | RuntimeException e) {
            fail("Camera frame bridge failed for channel " + channel + ": " + detail(e)); stopRequested = true; cleanup();
        }
    }

    private void cleanup() {
        if (cleaned) return;
        cleaned = true; stopRequested = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        try {
            safe(() -> { if (texture != null) texture.setOnFrameAvailableListener(null); });
            safe(() -> { if (inputSurface != null) inputSurface.release(); }); inputSurface = null;
            safe(() -> { if (texture != null) texture.release(); }); texture = null;
            boolean[] current = new boolean[1];
            safe(() -> {
                current[0] = displayAcquired && eglContext != null && !eglContext.equals(EGL14.EGL_NO_CONTEXT)
                        && pbuffer != null && !pbuffer.equals(EGL14.EGL_NO_SURFACE)
                        && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
            });
            if (current[0]) {
                safe(() -> { if (program != 0) GLES20.glDeleteProgram(program); });
                safe(() -> { if (textureId != 0) GLES20.glDeleteTextures(1, new int[]{textureId}, 0); });
            }
            program = 0; textureId = 0;
            if (displayAcquired) {
                safe(() -> { require(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "Cannot detach camera EGL context"); });
                safe(() -> { if (window != null && !window.equals(EGL14.EGL_NO_SURFACE)) require(EGL14.eglDestroySurface(display, window), "Cannot release encoder EGL Surface"); });
                safe(() -> { if (pbuffer != null && !pbuffer.equals(EGL14.EGL_NO_SURFACE)) require(EGL14.eglDestroySurface(display, pbuffer), "Cannot release camera EGL pbuffer"); });
                safe(() -> { if (eglContext != null && !eglContext.equals(EGL14.EGL_NO_CONTEXT)) require(EGL14.eglDestroyContext(display, eglContext), "Cannot release camera EGL context"); });
                safe(SharedDisplay::release); displayAcquired = false;
            }
            window = EGL14.EGL_NO_SURFACE; pbuffer = EGL14.EGL_NO_SURFACE; eglContext = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY;
            safe(() -> { require(EGL14.eglReleaseThread(), "Cannot release camera EGL thread state"); });
        } finally {
            // Native cleanup completes before callbacks and before the caller may release its encoder.
            try { if (failure != null) listener.onError(failure); }
            catch (RuntimeException ignored) { }
            finally { if (thread != null) thread.quitSafely(); closedLatch.countDown(); ready.countDown(); }
        }
    }

    private int createProgram() throws IOException {
        int vertex = 0, fragment = 0, linked = 0;
        try {
            vertex = shader(GLES20.GL_VERTEX_SHADER, VERTEX); fragment = shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            linked = GLES20.glCreateProgram();
            if (linked == 0) throw new IOException("Cannot allocate camera GL shader program.");
            GLES20.glAttachShader(linked, vertex); GLES20.glAttachShader(linked, fragment); GLES20.glLinkProgram(linked);
            int[] status = new int[1]; GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new IOException("Camera GL shader link failed: " + GLES20.glGetProgramInfoLog(linked));
            int result = linked; linked = 0; return result;
        } finally {
            if (vertex != 0) GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment);
            if (linked != 0) GLES20.glDeleteProgram(linked);
        }
    }

    private static int shader(int type, String source) throws IOException {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) throw new IOException("Cannot allocate a camera GL shader.");
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) { String log = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IOException("Camera GL shader compilation failed: " + log); }
        return shader;
    }

    private void checkStop() throws IOException { if (stopRequested) throw new IOException("Camera frame bridge was stopped."); }
    private void fail(String message) { synchronized (lifecycle) { if (failure == null) failure = message; } }
    private interface CleanupCall { void run() throws IOException; }
    private void safe(CleanupCall call) { try { call.run(); } catch (IOException | RuntimeException e) { fail("Camera GL cleanup: " + detail(e)); } }
    private static void require(boolean success, String operation) throws IOException {
        if (!success) throw new IOException(operation + String.format(Locale.US, " (EGL 0x%04x).", EGL14.eglGetError()));
    }
    private static void checkGl(String operation) throws IOException {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) throw new IOException(operation + String.format(Locale.US, " failed (GL 0x%04x).", error));
    }
    private static String detail(Exception error) { return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()); }

    /** Avoid eglTerminate in one channel tearing down another channel's shared display. */
    private static final class SharedDisplay {
        private static EGLDisplay shared = EGL14.EGL_NO_DISPLAY;
        private static int users;
        static synchronized EGLDisplay acquire() throws IOException {
            if (users == 0) {
                EGLDisplay candidate = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                require(candidate != null && !candidate.equals(EGL14.EGL_NO_DISPLAY), "Cannot open camera EGL display");
                int[] version = new int[2];
                require(EGL14.eglInitialize(candidate, version, 0, version, 1), "Cannot initialize camera EGL display");
                shared = candidate;
            }
            users++; return shared;
        }
        static synchronized void release() throws IOException {
            if (users <= 0) return;
            if (--users == 0) {
                EGLDisplay previous = shared; shared = EGL14.EGL_NO_DISPLAY;
                require(EGL14.eglTerminate(previous), "Cannot terminate camera EGL display");
            }
        }
    }
}
