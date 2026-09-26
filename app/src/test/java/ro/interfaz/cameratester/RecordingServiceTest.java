package ro.interfaz.cameratester;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class RecordingServiceTest {
    static void resetPendingStart() throws Exception {
        Field field=RecordingService.class.getDeclaredField("PENDING_START");field.setAccessible(true);((AtomicBoolean)field.get(null)).set(false);
    }
    @After public void clearQueuedStart() throws Exception {resetPendingStart();}
    public static class TestService extends RecordingService {
        FakeEngine fake;
        @Override protected Engine createEngine(DvrRecorder.Listener listener){return fake=new FakeEngine(listener);}
    }
    static final class ServiceScope implements AutoCloseable {
        private final ServiceController<TestService> controller=Robolectric.buildService(TestService.class).create();
        TestService get(){return controller.get();}
        @Override public void close(){TestService service=get();if(service.fake.busy)service.fake.finish("Test resources released.");controller.destroy();}
    }
    static final class FakeEngine implements RecordingService.Engine {
        final DvrRecorder.Listener listener;
        boolean busy;
        int starts,stops,closes;
        String saved="";
        DvrRecorder.Config config;
        FakeEngine(DvrRecorder.Listener listener){this.listener=listener;}
        @Override public void start(DvrRecorder.Config config){this.config=config;starts++;busy=true;listener.onState("RECORDING","Recording selected cameras.");}
        @Override public void stop(){stops++;listener.onState("STOPPING","Finalizing files.");}
        @Override public boolean isBusy(){return busy;}
        @Override public String lastReport(){return saved;}
        @Override public void close(){closes++;}
        void finish(String message){busy=false;listener.onState("IDLE",message);}
    }
    static DvrRecorder.Config config(boolean benchmark){
        return new DvrRecorder.Config(Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3A"),
                new int[]{0,2,5},new int[]{5,7,0,2,3,1},1280,720,30,6_000_000,benchmark);
    }
    static void grantCamera(){Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);}
    static void start(TestService service,boolean benchmark){service.onStartCommand(RecordingService.startIntent(service,config(benchmark)),0,1);}

    @Test public void queuedStartImmediatelyBlocksPreviewAndDenialReleasesTheReservation(){
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();RecordingService.start(service,config(false));
            assertTrue("Preview must be blocked before Android delivers onStartCommand",RecordingService.isCaptureActive());
            assertEquals(0,service.fake.starts);
            Intent queued=Shadows.shadowOf(service).getNextStartedService();assertNotNull(queued);
            service.onStartCommand(queued,0,1);assertFalse(RecordingService.isCaptureActive());
            assertEquals(0,service.fake.starts);assertTrue(service.snapshot().message.contains("Camera permission"));
        }
    }

    @Test public void bindingToConfigureDoesNotStartCaptureForegroundOrWakeLock(){
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();
            RecordingService.LocalBinder binder=(RecordingService.LocalBinder)service.onBind(new Intent());
            assertSame(service,binder.getService());assertEquals(0,service.fake.starts);
            assertNull(Shadows.shadowOf(service).getLastForegroundNotification());
            assertNull(ShadowPowerManager.getLatestWakeLock());assertFalse(service.snapshot().busy);
        }
    }
    @Test public void explicitStartShowsOngoingNotificationAndStopWaitsForEveryWorker(){
        grantCamera();
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();
            int restart=service.onStartCommand(RecordingService.startIntent(service,config(false)),0,1);
            assertEquals(Service.START_NOT_STICKY,restart);assertEquals(1,service.fake.starts);
            Notification notification=Shadows.shadowOf(service).getLastForegroundNotification();
            assertNotNull(notification);assertTrue((notification.flags&Notification.FLAG_ONGOING_EVENT)!=0);
            assertEquals("Open",notification.actions[0].title);assertEquals("Stop",notification.actions[1].title);
            assertEquals(RecordingActivity.class.getName(),Shadows.shadowOf(notification.contentIntent).getSavedIntent().getComponent().getClassName());
            assertEquals(RecordingService.ACTION_STOP,Shadows.shadowOf(notification.actions[1].actionIntent).getSavedIntent().getAction());
            assertTrue(Shadows.shadowOf(notification.actions[1].actionIntent).isImmutable());
            PowerManager.WakeLock wake=ShadowPowerManager.getLatestWakeLock();assertTrue(wake.isHeld());
            service.onStartCommand(new Intent().setAction(RecordingService.ACTION_STOP),0,2);
            assertEquals(1,service.fake.stops);assertEquals("STOPPING",service.snapshot().state);
            assertTrue("Final file writers still need CPU time",wake.isHeld());
            assertFalse(Shadows.shadowOf(service).isForegroundStopped());assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
            service.fake.finish("All MP4 files finalized.");
            assertFalse(wake.isHeld());assertTrue(Shadows.shadowOf(service).isForegroundStopped());
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf());assertFalse(service.snapshot().busy);
        }
    }
    @Test public void unbindTaskRemovalAndNullRestartDoNotStopOrRestartActiveRecording(){
        grantCamera();
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();start(service,false);
            service.onUnbind(new Intent());service.onTaskRemoved(new Intent());
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,2));
            assertEquals(1,service.fake.starts);assertEquals(0,service.fake.stops);assertEquals(0,service.fake.closes);
            assertTrue(service.snapshot().busy);assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());
            service.fake.finish("Done.");
        }
    }
    @Test public void emptyRestartNeverResumesCameraCapture(){
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();assertEquals(Service.START_NOT_STICKY,service.onStartCommand(null,0,1));
            assertEquals(0,service.fake.starts);assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        }
    }
    @Test public void missingCameraPermissionRejectsForegroundStartBeforeCapture(){
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();start(service,false);
            assertEquals(0,service.fake.starts);assertEquals("IDLE",service.snapshot().state);
            assertTrue(service.snapshot().message.contains("Camera permission"));
            assertNull(Shadows.shadowOf(service).getLastForegroundNotification());assertNull(ShadowPowerManager.getLatestWakeLock());
        }
    }
    @Test public void benchmarkSurvivesObserverDetachAndFinalReportIsReplayed(){
        grantCamera();
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();List<RecordingService.Snapshot> first=new ArrayList<>(),second=new ArrayList<>();
            RecordingService.Listener observer=first::add;service.addListener(observer);start(service,true);
            service.removeListener(observer);int count=first.size();
            service.fake.listener.onTransportStats("Input 30 FPS; encoder input 30 FPS.");
            service.fake.saved="All selected files finalized.";service.fake.listener.onReport(service.fake.saved);
            service.fake.finish("Benchmark complete.");
            assertEquals(count,first.size());assertEquals(0,service.fake.stops);
            service.addListener(second::add);RecordingService.Snapshot restored=second.get(0);
            assertEquals("IDLE",restored.state);assertTrue(restored.benchmark);
            assertEquals(service.fake.saved,restored.report);assertTrue(restored.transport.contains("30 FPS"));
            assertArrayEquals(new int[]{0,2,5},restored.selectedSlots());
            int[] changed=restored.selectedSlots();changed[0]=4;assertEquals(0,restored.selectedSlots()[0]);
        }
    }
    @Test public void captureFailureReleasesForegroundOnlyAfterTerminalCleanup(){
        grantCamera();
        try(ServiceScope controller=new ServiceScope()) {
            TestService service=controller.get();start(service,false);
            service.fake.listener.onState("STOPPING","USB disconnected; finishing remaining workers.");
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());assertFalse(Shadows.shadowOf(service).isForegroundStopped());
            service.fake.finish("Recording failed: USB disconnected.");
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld());assertTrue(Shadows.shadowOf(service).isForegroundStopped());
            assertTrue(service.snapshot().message.contains("USB disconnected"));
        }
    }
    @Test public void unexpectedServiceDestructionKeepsWakeLockUntilPendingCleanupCompletes(){
        grantCamera();ServiceController<TestService> controller=Robolectric.buildService(TestService.class).create();
        TestService service=controller.get();start(service,false);PowerManager.WakeLock wake=ShadowPowerManager.getLatestWakeLock();
        controller.destroy();assertEquals(1,service.fake.stops);assertEquals(0,service.fake.closes);assertTrue(wake.isHeld());
        service.fake.finish("Resources released.");assertFalse(wake.isHeld());assertEquals(1,service.fake.closes);
    }
}
