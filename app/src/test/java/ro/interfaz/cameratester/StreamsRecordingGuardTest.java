package ro.interfaz.cameratester;

import android.content.Context;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class StreamsRecordingGuardTest {
    @Test public void queuedForegroundStartBlocksPreviewBeforeRecorderOwnsResources() throws Exception {
        AtomicBoolean busy=processBusy();boolean previousBusy=busy.getAndSet(false);
        Field pendingField=RecordingService.class.getDeclaredField("PENDING_START");pendingField.setAccessible(true);
        AtomicBoolean pending=(AtomicBoolean)pendingField.get(null);boolean previousPending=pending.getAndSet(false);
        Streams streams=null;
        try {
            CountingContext context=new CountingContext();Registry registry=new Registry(context);
            Source source=new Source("teyes","4","","Saved front camera","Existing camera evidence");
            registry.discovered(source);registry.result(source.key,"Previously received frames",true,1280,720);
            String before=registry.report();CountingEvents events=new CountingEvents();
            RecordingService.start(context,new DvrRecorder.Config(Uri.parse("content://test/tree/usb"),
                    new int[]{0},new int[]{4,5,0,1,2,3},1280,720,25,3_000_000,false));
            assertNotNull(context.foregroundIntent);assertEquals(RecordingService.ACTION_START,context.foregroundIntent.getAction());
            assertFalse("onStartCommand and the engine have not run",DvrRecorder.anyRecording());
            assertTrue("A queued start already reserves capture",RecordingService.isCaptureActive());

            StreamTile tile=new StreamTile(context,"ADAS Front",source,null);
            streams=new Streams(context,registry,Collections.singletonList(tile),events);
            assertTrue(stopped(streams));assertEquals(0,context.binds);
            assertTrue(text(tile).contains("Preview paused while DVR is recording or finishing"));
            assertEquals(before,registry.report());assertEquals(0,events.failures);assertEquals(0,events.verified);
        } finally {
            if(streams!=null)streams.close();pending.set(previousPending);busy.set(previousBusy);
        }
    }

    @Test public void activeRecordingBlocksPreviewBeforeResourcesOpenAndPreservesEvidence() throws Exception {
        AtomicBoolean busy=processBusy();boolean previous=busy.getAndSet(true);
        Streams streams=null;
        try {
            CountingContext context=new CountingContext();Registry registry=new Registry(context);
            Source source=new Source("teyes","4","","Saved front camera","Existing camera evidence");
            registry.discovered(source);registry.result(source.key,"Previously received frames",true,1280,720);
            String before=registry.report();CountingEvents events=new CountingEvents();
            StreamTile tile=new StreamTile(context,"ADAS Front",source,null);
            streams=new Streams(context,registry,Collections.singletonList(tile),events);

            assertTrue(stopped(streams));assertEquals(0,context.binds);
            assertTrue(text(tile).contains("Preview paused while DVR is recording or finishing"));
            assertEquals(before,registry.report());assertEquals(0,events.failures);assertEquals(0,events.verified);

            // Finishing the recording does not silently resurrect an old view.
            busy.set(false);invoke(streams,"maybeStart");
            assertEquals(0,context.binds);assertTrue(stopped(streams));
        } finally {if(streams!=null)streams.close();busy.set(previous);}
    }

    @Test public void recordingStartedDuringVendorBindPreventsSurfaceTransaction() throws Exception {
        AtomicBoolean busy=processBusy();boolean previous=busy.getAndSet(false);
        Streams streams=null;
        try {
            CountingContext context=new CountingContext();Registry registry=new Registry(context);
            Source source=new Source("teyes","4","","Saved front camera","Existing camera evidence");
            registry.discovered(source);registry.result(source.key,"Previously received frames",true,1280,720);
            String before=registry.report();CountingEvents events=new CountingEvents();
            StreamTile tile=new StreamTile(context,"ADAS Front",source,null);
            List<StreamTile> tiles=Collections.singletonList(tile);
            streams=new Streams(context,registry,tiles,events);
            // Request the vendor bind without depending on a real GPU texture.
            Method startVendor=Streams.class.getDeclaredMethod("teyes",List.class);startVendor.setAccessible(true);
            startVendor.invoke(streams,tiles);assertEquals(1,context.binds);

            busy.set(true);StateOnlyBinder binder=new StateOnlyBinder();
            context.connection.onServiceConnected(TeyesDvr.COMPONENT,binder);
            drainVendorQueue();Shadows.shadowOf(Looper.getMainLooper()).idle();
            drainVendorQueue();Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(Arrays.asList(22),binder.calls); // no Surface start31 or stop5
            assertTrue(stopped(streams));assertEquals(1,context.unbinds);
            assertTrue(text(tile).contains("Preview paused while DVR is recording or finishing"));
            assertEquals(before,registry.report());assertEquals(0,events.failures);assertEquals(0,events.verified);
        } finally {if(streams!=null)streams.close();busy.set(previous);}
    }

    private static AtomicBoolean processBusy() throws Exception {
        Field field=DvrRecorder.class.getDeclaredField("PROCESS_BUSY");field.setAccessible(true);return (AtomicBoolean)field.get(null);
    }
    private static boolean stopped(Streams streams) throws Exception {
        Field field=Streams.class.getDeclaredField("stopped");field.setAccessible(true);return ((AtomicBoolean)field.get(streams)).get();
    }
    private static void invoke(Streams streams,String name) throws Exception {
        Method method=Streams.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(streams);
    }
    private static void drainVendorQueue() throws Exception {
        Field field=TeyesDvr.class.getDeclaredField("RPC_QUEUE");field.setAccessible(true);
        ((ExecutorService)field.get(null)).submit(()->{}).get(3,TimeUnit.SECONDS);
    }
    private static String text(View view) {
        StringBuilder result=new StringBuilder();if(view instanceof TextView)result.append(((TextView)view).getText());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)result.append(text(((ViewGroup)view).getChildAt(i)));
        return result.toString();
    }
    private static final class CountingEvents implements Streams.Events {
        int failures,verified;
        public void verified(Source source){verified++;}
        public void failed(Source source,String message){failures++;}
    }
    private static final class CountingContext extends ContextWrapper {
        int binds,unbinds;ServiceConnection connection;Intent foregroundIntent;
        CountingContext(){super(RuntimeEnvironment.getApplication());}
        @Override public Context getApplicationContext(){return this;}
        @Override public ComponentName startForegroundService(Intent intent){foregroundIntent=intent;return intent.getComponent();}
        @Override public boolean bindService(Intent intent,ServiceConnection connection,int flags){binds++;this.connection=connection;return true;}
        @Override public void unbindService(ServiceConnection connection){unbinds++;}
    }
    private static final class StateOnlyBinder extends Binder {
        final List<Integer> calls=Collections.synchronizedList(new ArrayList<>());
        StateOnlyBinder(){attachInterface(null,TeyesDvr.DESCRIPTOR);}
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
            data.enforceInterface(TeyesDvr.DESCRIPTOR);calls.add(code);
            if(code!=22)throw new RemoteException("Preview must not start while DVR owns the camera");
            reply.writeNoException();reply.writeInt(1);reply.writeBooleanArray(new boolean[12]);
            reply.writeInt(3);reply.writeInt(8);reply.writeInt(100);reply.writeInt(101);return true;
        }
    }
}
