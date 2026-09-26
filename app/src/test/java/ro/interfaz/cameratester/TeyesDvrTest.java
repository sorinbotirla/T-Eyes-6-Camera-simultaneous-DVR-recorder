package ro.interfaz.cameratester;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class TeyesDvrTest {
    @Test public void connectOnlyQueries22AndBoundsTheStablePrefix() {
        Fixture f = new Fixture();
        f.open();
        assertEquals(TeyesDvr.COMPONENT, f.context.intent.getComponent());
        assertEquals(Context.BIND_AUTO_CREATE, f.context.flags);
        assertTrue(f.binder.calls.isEmpty()); // no synchronous RPC from connect/main
        f.queue.drain();
        assertEquals(Arrays.asList(22), f.binder.calls);
        assertNotNull(f.callback.info);
        assertEquals(12, f.callback.info.channelCount);
        assertTrue(f.callback.info.cameraExist[4]);
        assertFalse(f.callback.info.cameraExist[3]);
        assertEquals(3, f.callback.info.avmEnable);
        assertEquals(8, f.callback.info.avmHwSupport);
        assertEquals(100, f.callback.info.csi0Mode);
        assertEquals(101, f.callback.info.csi1Mode);
        assertEquals(-1, f.callback.info.rawArFormat);
        assertEquals(-1, f.callback.info.rawReverseFormat);
        assertEquals(-1, f.callback.info.arFormat);
        f.close();
    }

    @Test public void startAndStopUseTypedSurfaceAndSameNonzeroOwnershipHash() {
        Fixture f = new Fixture(); f.ready();
        SurfaceTexture texture = new SurfaceTexture(0); Surface surface = new Surface(texture);
        try {
            List<String> results = new ArrayList<>();
            f.client.start(4, surface, 192837, results::add); f.queue.drain();
            assertEquals(Arrays.asList(22,31), f.binder.calls);
            assertEquals(4, f.binder.startChannel); assertEquals(192837, f.binder.startHash);
            assertEquals(Arrays.asList((String)null), results);
            f.client.stop(4, 192837, results::add); f.queue.drain();
            assertEquals(Arrays.asList(22,31,5), f.binder.calls);
            assertEquals(1012, f.binder.stopCommand); assertEquals(4, f.binder.stopChannel);
            assertEquals(192837, f.binder.stopHash); assertEquals(Arrays.asList(null,null), results);
            f.close();
            assertEquals(Arrays.asList(22,31,5), f.binder.calls); // no duplicate close stop
        } finally { surface.release(); texture.release(); }
    }

    @Test public void rejectsWildcardInvalidChannelAndUnownedStopBeforeRpc() {
        Fixture f = new Fixture(); f.ready();
        SurfaceTexture texture = new SurfaceTexture(0); Surface surface = new Surface(texture);
        try {
            List<String> results = new ArrayList<>();
            f.client.start(0, surface, 0, results::add);
            f.client.start(12, surface, 4, results::add);
            f.client.start(-1, surface, 4, results::add);
            f.client.stop(0, 4, results::add); f.queue.drain();
            assertEquals(4, results.size()); for (String error : results) assertNotNull(error);
            assertEquals(Arrays.asList(22), f.binder.calls);
        } finally { f.close(); surface.release(); texture.release(); }
    }

    @Test public void closeDuringAcceptedStartQueuesMatchingStopAndNotifiesOnlyOnce() {
        Fixture f = new Fixture(); f.ready();
        SurfaceTexture texture = new SurfaceTexture(0); Surface surface = new Surface(texture);
        try {
            List<String> results = new ArrayList<>();
            f.binder.onStart = f.client::close;
            f.client.start(5, surface, 777, results::add); f.queue.drain();
            assertEquals(Arrays.asList(22,31,5), f.binder.calls);
            assertEquals(5, f.binder.stopChannel); assertEquals(777, f.binder.stopHash);
            assertEquals(1, results.size()); assertNotNull(results.get(0));
            assertEquals(1, f.context.unbinds);
            f.close(); assertEquals(1, f.context.unbinds);
        } finally { surface.release(); texture.release(); }
    }

    @Test public void closeBeforeQueuedStartDoesNotAcquireAndLateConnectionIsIgnored() {
        Fixture f = new Fixture(); f.ready();
        SurfaceTexture texture = new SurfaceTexture(0); Surface surface = new Surface(texture);
        try {
            List<String> results = new ArrayList<>();
            f.client.start(0, surface, 999, results::add); f.client.close(); f.queue.drain();
            assertEquals(Arrays.asList(22), f.binder.calls);
            assertEquals(1, results.size()); assertNotNull(results.get(0));
            f.context.connection.onServiceConnected(TeyesDvr.COMPONENT, f.binder); f.queue.drain();
            assertEquals(Arrays.asList(22), f.binder.calls); assertEquals(1, f.context.unbinds);
        } finally { surface.release(); texture.release(); }
    }

    @Test public void replacementClientCannotOvertakeOldInFlightPreviewCleanup() {
        Fixture first=new Fixture(); first.ready();
        FakeContext nextContext=new FakeContext(); FakeDvr nextBinder=new FakeDvr();
        TeyesDvr next=new TeyesDvr(nextContext,first.queue);
        SurfaceTexture texture=new SurfaceTexture(0); Surface surface=new Surface(texture);
        List<String> order=new ArrayList<>();
        try {
            first.binder.onStart=() -> {
                first.client.close();
                next.connect(new TeyesDvr.Callback() {
                    @Override public void connected(TeyesDvr.Info info) {
                        assertEquals(Arrays.asList(22,31,5),first.binder.calls);
                        order.add("new connected after old stop");
                        next.start(4,surface,444,error -> assertNull(error));
                    }
                    @Override public void failed(String error) { fail(error); }
                });
                nextContext.connection.onServiceConnected(TeyesDvr.COMPONENT,nextBinder);
            };
            first.client.start(4,surface,333,error -> assertNotNull(error)); first.queue.drain();
            assertEquals(Arrays.asList("new connected after old stop"),order);
            assertEquals(Arrays.asList(22,31),nextBinder.calls); assertEquals(333,first.binder.stopHash);
            assertEquals(444,nextBinder.startHash);
        } finally { next.close(); first.close(); first.queue.drain(); surface.release(); texture.release(); }
    }

    @Test public void bindTimeoutClosesAndRejectsLateRepliesWithoutAutomaticRetry() {
        Fixture f = new Fixture(); f.open();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TeyesDvr.DEADLINE_MS));
        assertEquals(1, f.callback.errors.size()); assertTrue(f.callback.errors.get(0).contains("timed out"));
        f.queue.drain();
        assertTrue(f.binder.calls.isEmpty()); assertNull(f.callback.info); assertEquals(1, f.context.binds);
        assertEquals(1, f.context.unbinds);
    }

    @Test public void incompatibleDescriptorNeverIssuesVendorTransactions() {
        Fixture f = new Fixture(); f.binder.attachInterface(null, "wrong.interface");
        f.ready();
        assertNull(f.callback.info); assertEquals(1, f.callback.errors.size());
        assertTrue(f.binder.calls.isEmpty()); assertEquals(1, f.context.unbinds);
    }

    @Test public void deniedQueryPropagatesExceptionAndReleasesBinding() {
        Fixture f = new Fixture(); f.binder.denyQuery = true; f.ready();
        assertNull(f.callback.info); assertEquals(1, f.callback.errors.size());
        assertTrue(f.callback.errors.get(0).contains("SecurityException")); assertEquals(1, f.context.unbinds);
    }

    @Test public void disconnectCleansOwnedPreviewAndNotifiesFailureOnce() {
        Fixture f = new Fixture(); f.ready();
        SurfaceTexture texture = new SurfaceTexture(0); Surface surface = new Surface(texture);
        try {
            f.client.start(2, surface, 92, error -> assertNull(error)); f.queue.drain();
            f.context.connection.onServiceDisconnected(TeyesDvr.COMPONENT);
            f.context.connection.onBindingDied(TeyesDvr.COMPONENT); f.queue.drain();
            assertEquals(1, f.callback.errors.size()); assertEquals(Arrays.asList(22,31,5), f.binder.calls);
            assertEquals(92, f.binder.stopHash); assertEquals(1, f.context.unbinds);
        } finally { f.close(); surface.release(); texture.release(); }
    }

    @Test public void malformedPrefixRejectsHugeNegativeAndTruncatedArrays() throws Exception {
        for (int count : new int[]{-1,17,Integer.MAX_VALUE}) {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInt(1); parcel.writeInt(count); parcel.setDataPosition(0);
                try { TeyesDvr.readInfo(parcel,-1,-1); fail("Invalid length must be rejected"); }
                catch (RemoteException expected) { assertTrue(expected.getMessage().contains("length")); }
            } finally { parcel.recycle(); }
        }
        Parcel truncated = Parcel.obtain();
        try {
            truncated.writeInt(1); truncated.writeInt(12); truncated.writeInt(1); truncated.setDataPosition(0);
            try { TeyesDvr.readInfo(truncated,-1,-1); fail("Truncation must be rejected"); }
            catch (RemoteException expected) { assertTrue(expected.getMessage().contains("Truncated")); }
        } finally { truncated.recycle(); }
    }

    @Test public void knownVendorTailSeparatesRawSettingsFromDerivedFormats() throws Exception {
        Parcel parcel = vendorInfoTail(false);
        try {
            TeyesDvr.Info info = TeyesDvr.readInfo(parcel,2,6);
            assertEquals(2,info.rawArFormat); assertEquals(6,info.rawReverseFormat);
            assertEquals(1,info.arFormat); assertEquals(4,info.reverseFormat);
            assertEquals(5,info.avmHwSupport);
        } finally { parcel.recycle(); }
    }

    @Test public void invalidOptionalRadarArrayKeepsPrefixWithoutGuessingAliases() throws Exception {
        Parcel parcel = vendorInfoTail(true);
        try {
            TeyesDvr.Info info = TeyesDvr.readInfo(parcel,2,6);
            assertEquals(5,info.avmHwSupport); assertEquals(12,info.channelCount);
            assertEquals(-1,info.arFormat); assertEquals(-1,info.reverseFormat);
        } finally { parcel.recycle(); }
    }

    private static Parcel vendorInfoTail(boolean malformedRadar) {
        Parcel out=Parcel.obtain(); out.writeInt(1); out.writeBooleanArray(new boolean[12]);
        // Exact writer order from extracted ReverseAVMInfo.writeToParcel.
        for (int value : new int[]{4,5,100,101,102,0,0,0,1,1,1,1}) out.writeInt(value);
        out.writeLong(128); out.writeLong(128); out.writeLong(128); out.writeLong(128);
        out.writeInt(0); out.writeFloat(12.5f); out.writeFloat(-24.25f);
        // Six doors, lightState, two park fields, 4 radar display, 4 radar counts,
        // and 4 radar levels. Distinct values reveal offsets across earlier types.
        for (int value : new int[]{1,0,0,0,0,0,3,0,1,2,3,4,5,6,7,8,9,10,11,12,13}) out.writeInt(value);
        if (malformedRadar) {
            out.writeInt(Integer.MAX_VALUE);
        } else {
            out.writeIntArray(new int[]{1,2,3,4}); out.writeIntArray(null);
            out.writeIntArray(new int[]{9,8}); out.writeIntArray(new int[0]);
            out.writeByte((byte)1); out.writeByte((byte)0);
            for (int value : new int[]{0,1,1,30,2,7,1,128,129,0}) out.writeInt(value);
            out.writeLong(0x123456789abcdefL);
            out.writeInt(2); out.writeInt(3); out.writeInt(4); // lvds, aux, reverseFormat
            out.writeInt(1); out.writeInt(0); out.writeInt(1); // arPreview, power, derived arFormat
            out.writeInt(2); // next adasFormat field, intentionally different
        }
        out.setDataPosition(0); return out;
    }

    private static final class Fixture {
        final ManualExecutor queue = new ManualExecutor();
        final FakeContext context = new FakeContext();
        final FakeDvr binder = new FakeDvr();
        final Capture callback = new Capture();
        final TeyesDvr client = new TeyesDvr(context, queue);
        void open() { client.connect(callback); context.connection.onServiceConnected(TeyesDvr.COMPONENT,binder); }
        void ready() { open(); queue.drain(); }
        void close() { client.close(); queue.drain(); }
    }
    private static final class Capture implements TeyesDvr.Callback {
        TeyesDvr.Info info; final List<String> errors = new ArrayList<>();
        @Override public void connected(TeyesDvr.Info value) { assertEquals(Looper.getMainLooper(),Looper.myLooper()); info=value; }
        @Override public void failed(String error) { assertEquals(Looper.getMainLooper(),Looper.myLooper()); errors.add(error); }
    }
    private static final class FakeContext extends ContextWrapper {
        Intent intent; ServiceConnection connection; int flags, binds, unbinds;
        FakeContext() { super(RuntimeEnvironment.getApplication()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent service, ServiceConnection conn, int flags) {
            intent=service; connection=conn; this.flags=flags; binds++; return true;
        }
        @Override public void unbindService(ServiceConnection conn) { assertSame(connection,conn); unbinds++; }
    }
    private static final class FakeDvr extends Binder {
        final List<Integer> calls = new ArrayList<>();
        int startChannel, startHash, stopCommand, stopChannel, stopHash;
        boolean denyQuery; Runnable onStart;
        FakeDvr() { attachInterface(null,TeyesDvr.DESCRIPTOR); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            data.enforceInterface(TeyesDvr.DESCRIPTOR); assertEquals(0,flags); calls.add(code);
            if (code == 22) {
                assertEquals(0,data.dataAvail());
                if (denyQuery) { reply.writeException(new SecurityException("test denial")); return true; }
                reply.writeNoException(); reply.writeInt(1);
                boolean[] exist = new boolean[12]; exist[0]=true; exist[4]=true;
                reply.writeBooleanArray(exist); reply.writeInt(3); reply.writeInt(8); reply.writeInt(100); reply.writeInt(101);
                reply.writeLong(0x123456789abcdefL); // ignored mixed-format suffix
            } else if (code == 31) {
                startChannel=data.readInt(); assertEquals(1,data.readInt());
                Surface received=Surface.CREATOR.createFromParcel(data);
                try { startHash=data.readInt(); assertNotEquals(0,startHash); assertEquals(0,data.dataAvail()); }
                finally { received.release(); }
                if (onStart != null) onStart.run(); reply.writeNoException();
            } else if (code == 5) {
                stopCommand=data.readInt(); stopChannel=data.readInt(); stopHash=data.readInt();
                assertNotEquals(0,stopHash); assertEquals(0,data.dataAvail()); reply.writeNoException();
            } else { fail("Unexpected vendor transaction " + code); }
            return true;
        }
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        @Override public void execute(Runnable command) { jobs.add(command); }
        void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return false; }
    }
}
