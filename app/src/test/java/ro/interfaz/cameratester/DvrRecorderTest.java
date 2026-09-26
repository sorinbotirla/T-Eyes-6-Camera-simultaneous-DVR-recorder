package ro.interfaz.cameratester;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class DvrRecorderTest {
    @Test public void missingUsbFailsBeforeHardwareAndReleasesBothRecordingLocks() throws Exception {
        assertFalse("Previous test must not own the global recording lock",DvrRecorder.anyRecording());
        Context context=RuntimeEnvironment.getApplication();Capture capture=new Capture();
        DvrRecorder recorder=new DvrRecorder(context,capture);
        try {
            recorder.start(missingUsb(false));awaitCompletion(recorder,capture,1);
            assertFalse(recorder.isBusy());assertFalse(DvrRecorder.anyRecording());
            assertTrue(recorder.lastReport().contains("Choose USB storage and a valid recording profile."));
            assertTrue(recorder.lastReport().contains("Finalized recording segments: 0"));
            assertEquals(recorder.lastReport(),new String(Files.readAllBytes(new File(context.getFilesDir(),"last-recording-report.txt").toPath()),StandardCharsets.UTF_8));
            assertEquals(0,capture.statsCallbacks);
            recorder.start(missingUsb(true));awaitCompletion(recorder,capture,2);
            assertEquals("A second request must enter PREPARING rather than remain locked",2,capture.preparingStates);
            assertTrue(recorder.lastReport().contains("six-camera benchmark"));assertFalse(DvrRecorder.anyRecording());
        }finally{recorder.close();}
    }

    @Test public void reportDirectorySecurityFailureKeepsReadableReportAndAllowsRetry() throws Exception {
        assertFalse("Previous test must not own the global recording lock",DvrRecorder.anyRecording());
        DeniedFilesContext context=new DeniedFilesContext();Capture capture=new Capture();
        DvrRecorder recorder=new DvrRecorder(context,capture);
        try {
            // Neither this request nor the retry needs USB, a TEYES Binder or a codec.
            recorder.start(missingUsb(false));awaitCompletion(recorder,capture,1);
            assertTrue("Both the initial read and final report write were denied",context.attempts.get()>=2);
            assertFalse(recorder.isBusy());assertFalse(DvrRecorder.anyRecording());
            String report=recorder.lastReport();
            assertTrue(report.contains("Choose USB storage and a valid recording profile."));
            assertTrue(report.contains("Internal report save failed: Report directory denied for test"));
            assertTrue(report.contains("Finalized recording segments: 0"));
            assertEquals(report,capture.reports.get(capture.reports.size()-1));
            assertEquals(0,capture.statsCallbacks);

            recorder.start(missingUsb(true));awaitCompletion(recorder,capture,2);
            assertEquals(2,capture.preparingStates);assertEquals(2,capture.idleStates);
            assertTrue(recorder.lastReport().contains("six-camera benchmark"));
            assertTrue(recorder.lastReport().contains("Internal report save failed:"));
            assertFalse(recorder.isBusy());assertFalse(DvrRecorder.anyRecording());
        }finally{recorder.close();}
    }

    @Test public void pendingEncoderBecomesValidAfterItsOwnerActuallyFinishes() throws Exception {
        SyntheticEncoder encoder=new SyntheticEncoder(0,null);
        CountDownLatch pending=new CountDownLatch(1);
        FinishCall finish=new FinishCall(Arrays.asList(encoder.encoder),()->{
            encoder.assertReleaseRequested();pending.countDown();
        });
        try {
            CameraEncoder.StopResult before=encoder.encoder.stopResult();
            assertFalse(before.completed);assertFalse(before.valid);
            finish.start();
            assertTrue("Zero warning time must expose pending cleanup promptly",pending.await(2,TimeUnit.SECONDS));
            assertFalse("Do not return while the encoder owner still holds resources",finish.done.await(50,TimeUnit.MILLISECONDS));
            encoder.allowClose.countDown();
            List<CameraEncoder.StopResult> results=finish.awaitResults();
            assertEquals(1,results.size());CameraEncoder.StopResult actual=results.get(0);
            assertTrue(actual.completed);assertTrue("A delay alone must not invalidate a successful terminal result",actual.valid);
            assertNull(actual.error);assertTrue(actual.snapshot.finalized);assertEquals(2,actual.snapshot.frames);
            assertNotSame("Collect a fresh terminal result rather than the pending snapshot",before,actual);
        }finally{encoder.releaseAndJoin();finish.joinAfterRelease();}
    }

    @Test public void allEncodersAreAuthorizedTogetherAndOneUnclosedOwnerKeepsWholeGroupPending() throws Exception {
        SyntheticEncoder first=new SyntheticEncoder(0,null);
        SyntheticEncoder second=new SyntheticEncoder(1,"Synthetic MP4 finalization failure");
        CountDownLatch pending=new CountDownLatch(1);AtomicInteger warnings=new AtomicInteger();
        FinishCall finish=new FinishCall(Arrays.asList(first.encoder,second.encoder),()->{
            first.assertReleaseRequested();second.assertReleaseRequested();warnings.incrementAndGet();pending.countDown();
        });
        try {
            finish.start();assertTrue(pending.await(2,TimeUnit.SECONDS));
            first.allowClose.countDown();assertTrue(first.ownerFinished.await(2,TimeUnit.SECONDS));
            assertTrue(first.encoder.isClosed());assertFalse(second.encoder.isClosed());
            assertFalse("A closed first encoder must not permit release of the remaining owner",finish.done.await(50,TimeUnit.MILLISECONDS));
            second.allowClose.countDown();
            List<CameraEncoder.StopResult> results=finish.awaitResults();
            assertEquals(1,warnings.get());assertEquals(2,results.size());
            assertTrue(results.get(0).completed);assertTrue(results.get(0).valid);assertNull(results.get(0).error);
            assertTrue(results.get(1).completed);assertFalse(results.get(1).valid);
            assertEquals("Synthetic MP4 finalization failure",results.get(1).error);
        }finally{first.releaseAndJoin();second.releaseAndJoin();finish.joinAfterRelease();}
    }

    /** Lifecycle fixture only: no codec, Surface, USB or encoded hardware output is created. */
    private static final class SyntheticEncoder {
        final CameraEncoder encoder;
        final CountDownLatch allowClose=new CountDownLatch(1),ownerFinished=new CountDownLatch(1);
        final Thread owner;
        SyntheticEncoder(int slot,String failure) {
            encoder=new CameraEncoder(new CameraEncoder.Config(Source.SLOTS[slot],slot,640,480,25,1_000_000),
                    new FileDescriptor(),new CameraEncoder.Listener(){});
            ReflectionHelpers.setField(encoder,"attempted",true);
            RecordingMetrics metrics=new RecordingMetrics(1_000_000_000L);
            metrics.sample(0,100,1_000,1_000_000_000L);
            metrics.sample(40_000,100,1_000,1_040_000_000L);
            ReflectionHelpers.setField(encoder,"metrics",metrics);
            owner=new Thread(()->{
                try {
                    allowClose.await();
                    metrics.finish(1_080_000_000L);
                    ReflectionHelpers.setField(encoder,"failure",failure);
                    ReflectionHelpers.setField(encoder,"finalized",failure==null);
                    ((CountDownLatch)ReflectionHelpers.getField(encoder,"closed")).countDown();
                }catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally{ownerFinished.countDown();}
            },"synthetic-encoder-owner-"+slot);
            owner.setDaemon(true);owner.start();
        }
        void assertReleaseRequested() {
            assertTrue("Every encoder must receive stop before waiting for any one encoder",(Boolean)ReflectionHelpers.getField(encoder,"stopRequested"));
            assertEquals("GL ownership must authorize every encoder release before pending notification",0,
                    ((CountDownLatch)ReflectionHelpers.getField(encoder,"inputReleaseAuthorized")).getCount());
        }
        void releaseAndJoin() throws InterruptedException {allowClose.countDown();owner.join(2000);}
    }
    private static final class FinishCall {
        final CountDownLatch done=new CountDownLatch(1);
        final AtomicReference<List<CameraEncoder.StopResult>> results=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final Thread worker;
        FinishCall(List<CameraEncoder> encoders,Runnable onPending) {
            worker=new Thread(()->{
                try{results.set(DvrRecorder.finishEncoders(encoders,0,onPending));}
                catch(Throwable error){failure.set(error);}
                finally{done.countDown();}
            },"finish-encoder-regression");worker.setDaemon(true);
        }
        void start(){worker.start();}
        List<CameraEncoder.StopResult> awaitResults() throws InterruptedException {
            assertTrue("Final cleanup must finish after every owner releases",done.await(3,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("finishEncoders failed",failure.get());
            return results.get();
        }
        void joinAfterRelease() throws InterruptedException {if(worker.isAlive())worker.join(3000);}
    }

    private static DvrRecorder.Config missingUsb(boolean benchmark) {
        return new DvrRecorder.Config(null,new int[]{0},new int[]{-2,-2,-2,-2,-2,-2},1280,720,25,3_000_000,benchmark);
    }
    private static void awaitCompletion(DvrRecorder recorder,Capture capture,int idleCount) throws Exception {
        await(()->!recorder.isBusy()&&capture.idleStates>=idleCount);
        assertFalse("Global lock must be released before IDLE is delivered",DvrRecorder.anyRecording());
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(condition.getAsBoolean())return;
            Thread.sleep(5);
        }
        fail("Recorder worker did not finish and deliver its main-thread callback within five seconds");
    }
    private static final class DeniedFilesContext extends ContextWrapper {
        final AtomicInteger attempts=new AtomicInteger();
        DeniedFilesContext(){super(RuntimeEnvironment.getApplication());}
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){attempts.incrementAndGet();throw new SecurityException("Report directory denied for test");}
    }
    private static final class Capture implements DvrRecorder.Listener {
        int preparingStates,idleStates,statsCallbacks;
        final List<String> reports=new ArrayList<>();
        @Override public void onState(String state,String message) {
            assertEquals(Looper.getMainLooper(),Looper.myLooper());
            if("PREPARING".equals(state))preparingStates++;
            if("IDLE".equals(state))idleStates++;
        }
        @Override public void onStats(List<CameraEncoder.Snapshot> snapshots){statsCallbacks++;}
        @Override public void onReport(String report){assertEquals(Looper.getMainLooper(),Looper.myLooper());reports.add(report);}
    }
}
