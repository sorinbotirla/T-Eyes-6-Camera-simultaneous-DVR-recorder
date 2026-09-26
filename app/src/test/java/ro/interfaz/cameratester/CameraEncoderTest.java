package ro.interfaz.cameratester;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.view.Surface;
import android.graphics.SurfaceTexture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class CameraEncoderTest {
    private CameraEncoder encoder() {
        return new CameraEncoder(new CameraEncoder.Config("ADAS Front", 5, 1280, 720, 25, 3_000_000),
                new FileDescriptor(), new CameraEncoder.Listener() {});
    }

    @Test public void stoppingBeforeStartClosesWithoutProducingAValidFile() throws Exception {
        CameraEncoder encoder = encoder();
        CameraEncoder.StopResult stopped = encoder.stop();
        assertTrue(stopped.completed);
        assertFalse(stopped.valid);
        assertTrue(encoder.isClosed());
        assertNotNull(stopped.error);
        assertEquals(0, stopped.snapshot.frames);
        assertFalse(encoder.stop().valid);
        try { encoder.start(); fail("A stopped encoder cannot reuse the output file."); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("stopped")); }
    }

    @Test public void invalidOutputDoesNotLeakAnUnfinishedEncoderLifecycle() throws Exception {
        CameraEncoder encoder = encoder();
        try { encoder.start(); fail("A closed descriptor must not produce a Surface."); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("descriptor is closed")); }
        assertTrue(encoder.isClosed());
        CameraEncoder.StopResult stopped = encoder.stop();
        assertTrue(stopped.completed);
        assertFalse(stopped.valid);
        assertFalse(stopped.snapshot.finalized);
        assertEquals(-1, stopped.snapshot.firstFrameDelayMs, 0);
    }

    @Test public void invalidChannelAndUnsupportedFrameRateFailBeforeOutputAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CameraEncoder.Config("ADAS Front", 12, 1280, 720, 25, 3_000_000));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraEncoder.Config("ADAS Front", 5, 1280, 720, 0, 3_000_000));
        assertThrows(IllegalArgumentException.class,
                () -> new CameraEncoder.Config("ADAS Front", 5, 1280, 720, 61, 3_000_000));
    }

    @Test public void olderAndroidHardwareHintExplicitlyRecognizesSoftwareCodecs() {
        assertTrue(CameraEncoder.softwareName("OMX.google.h264.encoder"));
        assertTrue(CameraEncoder.softwareName("c2.android.avc.encoder"));
        assertTrue(CameraEncoder.softwareName("c2.google.avc.encoder"));
        assertTrue(CameraEncoder.softwareName("OMX.vendor.sw.h264.encoder"));
        assertFalse(CameraEncoder.softwareName("OMX.qcom.video.encoder.avc"));
        assertFalse(CameraEncoder.softwareName("c2.qti.avc.encoder"));
    }

    @Test public void cleanupErrorIsDeliveredBeforeClosedEvenWhenListenerThrows() throws Exception {
        CameraEncoder[] holder = new CameraEncoder[1];
        boolean[] called = new boolean[1];
        holder[0] = new CameraEncoder(new CameraEncoder.Config("ADAS Front", 5, 1280, 720, 25, 3_000_000),
                new FileDescriptor(), new CameraEncoder.Listener() {
                    @Override public void onError(String error) {
                        called[0] = true;
                        assertFalse("The next run must not start before this error is delivered.", holder[0].isClosed());
                        assertNotNull(error);
                        throw new IllegalStateException("Test listener failure");
                    }
                });
        ReflectionHelpers.setField(holder[0], "attempted", true);
        Thread cleanup = new Thread(() -> ReflectionHelpers.callInstanceMethod(holder[0], "cleanup", ReflectionHelpers.ClassParameter.from(boolean.class, true)));
        cleanup.start();
        holder[0].stop();
        cleanup.join(1000);
        assertTrue(called[0]);
        assertTrue(holder[0].isClosed());
        assertFalse(holder[0].stop().valid);
    }

    @Test public void workerFailureRetainsInputSurfaceUntilOwnerAuthorizesRelease() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        CameraEncoder encoder = new CameraEncoder(new CameraEncoder.Config("ADAS Front", 5, 1280, 720, 25, 3_000_000),
                new FileDescriptor(), new CameraEncoder.Listener() {
                    @Override public void onError(String error) { reported.countDown(); }
                });
        TrackingSurface input = new TrackingSurface();
        ReflectionHelpers.setField(encoder, "attempted", true);
        ReflectionHelpers.setField(encoder, "surface", input);
        ReflectionHelpers.callInstanceMethod(encoder, "fail", ReflectionHelpers.ClassParameter.from(String.class, "Synthetic worker failure"));
        Thread cleanup = new Thread(() -> ReflectionHelpers.callInstanceMethod(encoder, "cleanup", ReflectionHelpers.ClassParameter.from(boolean.class, true)));
        cleanup.start();
        try {
            assertTrue(reported.await(2, TimeUnit.SECONDS));
            assertFalse(encoder.isClosed());
            assertFalse(input.released);
        } finally {
            encoder.stop();
            cleanup.join(1000);
        }
        assertTrue(encoder.isClosed());
        assertTrue(input.released);
    }

    @Test public void pendingStopResultIsRefreshedAfterDelayedCleanupWithoutCreatingAFailure() throws Exception {
        CameraEncoder encoder = encoder();
        BlockingSurface input = new BlockingSurface();
        ReflectionHelpers.setField(encoder, "attempted", true);
        ReflectionHelpers.setField(encoder, "surface", input);
        Thread cleanup = new Thread(() -> ReflectionHelpers.callInstanceMethod(encoder, "cleanup", ReflectionHelpers.ClassParameter.from(boolean.class, true)));
        cleanup.start();
        try {
            encoder.requestStop();
            assertTrue(input.entered.await(2, TimeUnit.SECONDS));
            assertFalse(encoder.awaitClosed(0));
            CameraEncoder.StopResult pending = encoder.stopResult();
            assertFalse(pending.completed);
            assertFalse(pending.valid);
            assertTrue(pending.error.contains("pending"));
            assertNull("Waiting alone must not become a recording failure.", pending.snapshot.error);
            assertEquals("Releasing encoder input Surface", pending.snapshot.shutdownPhase);
            assertTrue(pending.snapshot.shutdownElapsedMs >= 0);
            assertEquals(-1, pending.snapshot.eosWaitMs, 0);
            assertTrue(encoder.describeShutdown().contains("EOS signal/drain=n/a"));
        } finally {
            input.finish.countDown();
            encoder.requestStop();
            cleanup.join(2000);
        }
        assertTrue(encoder.awaitClosed(0));
        CameraEncoder.StopResult completed = encoder.stopResult();
        assertTrue(completed.completed);
        assertFalse(completed.valid); // No actual muxer/video was supplied in this lifecycle test.
        assertFalse(completed.error.contains("pending"));
        assertNotNull(completed.snapshot.error);
        assertEquals("Closed", completed.snapshot.shutdownPhase);
        assertThrows(IllegalArgumentException.class, () -> encoder.awaitClosed(-1));
    }

    private static class TrackingSurface extends Surface {
        volatile boolean released;
        private final SurfaceTexture texture;
        TrackingSurface() { this(new SurfaceTexture(1)); }
        private TrackingSurface(SurfaceTexture texture) { super(texture); this.texture = texture; }
        @Override public void release() { released = true; super.release(); texture.release(); }
    }

    private static final class BlockingSurface extends TrackingSurface {
        final CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        @Override public void release() {
            entered.countDown();
            try { finish.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { super.release(); }
        }
    }
}
