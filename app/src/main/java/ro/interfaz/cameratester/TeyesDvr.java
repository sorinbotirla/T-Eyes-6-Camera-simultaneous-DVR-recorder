package ro.interfaz.cameratester;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.view.Surface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal client for the DVRService APK extracted from this CC4 Pro firmware.
 * Only getReverseInfo (22), startPreviewByChannelHash (31), and matching stop
 * dvrControlCmd (5/1012) are used. An acknowledgement does NOT prove frame arrival.
 * A channel has one vendor preview Surface: our preview can replace another app's.
 * The vendor's hash guard also cannot guarantee perfect isolation of its backups.
 * Binder RPC cannot be forcibly interrupted. A deadline closes this client, queues
 * cleanup behind any in-flight call, and never starts another attempt automatically.
 */
final class TeyesDvr implements AutoCloseable {
    static final String DESCRIPTOR = "com.spd.dvr.aidl.IDVRService";
    static final ComponentName COMPONENT = new ComponentName("com.spd.dvr", "com.spd.dvr.service.DVRService");
    static final long DEADLINE_MS = 5000;
    // Shared ordering is essential: an old grid's late start/stop must not run
    // after its replacement client starts the same vendor channel.
    private static final ExecutorService RPC_QUEUE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "teyes-dvr-binder"); t.setDaemon(true); return t;
    });

    interface Callback { void connected(Info info); void failed(String error); }
    interface Result { void done(String error); }

    static final class Info {
        final boolean[] cameraExist;
        final int channelCount, avmEnable, avmHwSupport, csi0Mode, csi1Mode;
        // Read-only settings used by the vendor ADAS caller's channel selection.
        final int rawArFormat, rawReverseFormat;
        // -1 when the optional known-layout suffix could not be parsed. Raw
        // SETTING_AR_FORMAT is adasFormat, NOT the server's derived arFormat.
        final int arFormat, reverseFormat;
        Info(boolean[] cameras, int avm, int hardware, int csi0, int csi1, int ar, int reverse, int serverAr, int serverReverse) {
            cameraExist = cameras.clone(); channelCount = Math.min(12, cameras.length);
            avmEnable = avm; avmHwSupport = hardware; csi0Mode = csi0; csi1Mode = csi1;
            rawArFormat = ar; rawReverseFormat = reverse;
            arFormat = serverAr; reverseFormat = serverReverse;
        }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final Map<Integer, Session> sessions = new LinkedHashMap<>();
    private final ArrayList<Operation> operations = new ArrayList<>();
    private Callback callback;
    private IBinder binder;
    private boolean attemptedBind, bound, ready, failureReported;
    private volatile boolean closed;
    private final Runnable connectDeadline = () -> fail("TEYES DVR connection/state query timed out (5 s). No automatic retry.");
    private final Runnable releaseBinding = this::unbind;
    private final IBinder.DeathRecipient death = () -> onMain(() -> fail("TEYES DVR service process died."));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            onMain(() -> serviceConnected(service));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            onMain(() -> fail("TEYES DVR service disconnected."));
        }
        @Override public void onBindingDied(ComponentName name) {
            onMain(() -> fail("TEYES DVR binding died. Start a new test after the service recovers."));
        }
        @Override public void onNullBinding(ComponentName name) {
            onMain(() -> fail("TEYES DVR returned a null binding."));
        }
    };

    TeyesDvr(Context context) {
        this(context, RPC_QUEUE);
    }
    // Executor injection keeps lifecycle and actual Parcel contract tests deterministic.
    TeyesDvr(Context context, ExecutorService worker) {
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application; this.worker = worker;
    }

    void connect(Callback cb) {
        onMain(() -> {
            if (closed || attemptedBind) { cb.failed("This TEYES DVR client is already used or closed."); return; }
            callback = cb; attemptedBind = true;
            main.postDelayed(connectDeadline, DEADLINE_MS);
            try {
                Intent intent = new Intent("com.spd.service.dvrservice").setComponent(COMPONENT);
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound) fail("Cannot bind TEYES DVRService: unavailable or denied.");
            } catch (RuntimeException error) { fail(message("TEYES DVR bind failed", error)); }
        });
    }

    private void serviceConnected(IBinder service) {
        if (closed) { unbind(); return; }
        if (binder != null) { fail("Unexpected replacement of the TEYES DVR connection."); return; }
        if (service == null) { fail("TEYES DVR returned no Binder."); return; }
        binder = service;
        worker.execute(() -> {
            if (closed) return;
            try {
                if (!DESCRIPTOR.equals(service.getInterfaceDescriptor())) throw new RemoteException("Unexpected Binder descriptor");
                service.linkToDeath(death, 0);
                if (closed) return;
                Info info = query(service, setting("SETTING_AR_FORMAT"), setting("SETTING_REVERSE_CAMERA_FORMAT"));
                onMain(() -> {
                    if (closed) return;
                    ready = true; main.removeCallbacks(connectDeadline); callback.connected(info);
                });
            } catch (Exception error) { onMain(() -> fail(message("TEYES DVR state query failed", error))); }
        });
    }

    void start(int channel, Surface surface, int hash, Result cb) {
        onMain(() -> {
            String invalid = validate(channel, hash);
            if (invalid != null) { cb.done(invalid); return; }
            if (surface == null || !surface.isValid()) { cb.done("Preview Surface is unavailable."); return; }
            if (sessions.containsKey(channel)) { cb.done("A preview request already owns this channel; stop it first."); return; }
            Session session = new Session(channel, hash);
            sessions.put(channel, session);
            Operation op = new Operation(cb);
            IBinder target = binder;
            worker.execute(() -> {
                if (closed || session.stopQueued) { finish(op, "Preview cancelled before start."); return; }
                String error = null;
                try {
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR); data.writeInt(channel);
                        data.writeInt(1); surface.writeToParcel(data, 0); data.writeInt(hash);
                        // Even a thrown/late reply can follow server-side acceptance.
                        session.attempted = true;
                        transact(target, 31, data, reply);
                    } finally { data.recycle(); reply.recycle(); }
                } catch (Exception e) { error = message("TEYES preview start failed", e); }
                finish(op, error);
            });
        });
    }

    void stop(int channel, int hash, Result cb) {
        onMain(() -> {
            String invalid = validate(channel, hash);
            if (invalid != null) { cb.done(invalid); return; }
            Session session = sessions.get(channel);
            if (session == null || session.hash != hash) { cb.done("No matching preview session; refusing an unowned stop."); return; }
            if (session.stopQueued) { cb.done("Stop is already pending for this preview."); return; }
            session.stopQueued = true;
            Operation op = new Operation(cb);
            IBinder target = binder;
            worker.execute(() -> {
                String error = null;
                try { if (session.attempted) stopRemote(target, channel, hash); session.released = true; }
                catch (Exception e) { error = message("TEYES preview stop failed", e); }
                final String result = error;
                onMain(() -> {
                    // Keep uncertain stops owned for one final close cleanup.
                    if (result == null && sessions.get(channel) == session) sessions.remove(channel);
                    op.finish(result);
                });
            });
        });
    }

    private String validate(int channel, int hash) {
        if (closed || !ready) return "TEYES DVR is not connected.";
        if (channel < 0 || channel >= 12) return "Invalid TEYES channel (expected 0–11).";
        if (hash == 0) return "A nonzero preview ownership hash is required.";
        return null;
    }

    private void fail(String error) {
        if (closed) return;
        Callback notify = failureReported ? null : callback;
        failureReported = true;
        close();
        if (notify != null) notify.failed(error);
    }

    @Override public void close() {
        onMain(() -> {
            if (closed) return;
            closed = true; ready = false; main.removeCallbacks(connectDeadline);
            ArrayList<Session> cleanup = new ArrayList<>(sessions.values()); sessions.clear();
            IBinder target = binder;
            // This job follows start/stop calls on the same queue. Do not cancel
            // the worker: a late accepted start still needs its matching stop.
            worker.execute(() -> {
                for (Session session : cleanup) {
                    if (session.attempted && !session.released && target != null) {
                        try { stopRemote(target, session.channel, session.hash); session.released = true; }
                        catch (Exception ignored) { /* Device death/denial can make cleanup impossible. */ }
                    }
                }
                if (target != null) {
                    try { target.unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
                }
                onMain(this::unbind);
            });
            // Queue cleanup before callbacks: one may synchronously open a new
            // client whose requests must remain behind this cleanup.
            for (Operation op : new ArrayList<>(operations)) op.finish("TEYES DVR client closed; cleanup queued.");
            // Do not retain an Activity binding forever when vendor Binder hangs.
            // Cleanup remains queued and can still run if its call eventually returns.
            main.postDelayed(releaseBinding, DEADLINE_MS);
        });
    }

    private void unbind() {
        main.removeCallbacks(releaseBinding);
        if (bound) {
            bound = false;
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        }
    }

    private final class Operation {
        final Result callback;
        boolean completed;
        final Runnable deadline;
        Operation(Result callback) {
            this.callback = callback;
            deadline = () -> {
                fail("TEYES DVR request timed out (5 s); the vendor may still complete it. This session is closed; cleanup queued, no automatic retry.");
            };
            operations.add(this); main.postDelayed(deadline, DEADLINE_MS);
        }
        void finish(String error) {
            if (completed) return;
            completed = true; main.removeCallbacks(deadline); operations.remove(this); callback.done(error);
        }
    }

    private static final class Session {
        final int channel, hash;
        volatile boolean attempted, stopQueued, released;
        Session(int channel, int hash) { this.channel = channel; this.hash = hash; }
    }
    private void finish(Operation op, String error) { onMain(() -> op.finish(error)); }
    private void onMain(Runnable work) {
        if (Looper.myLooper() == Looper.getMainLooper()) work.run(); else main.post(work);
    }
    private int setting(String name) {
        try { return Settings.System.getInt(context.getContentResolver(), name, -1); }
        catch (RuntimeException unavailable) { return -1; }
    }
    private static String message(String prefix, Exception error) {
        return prefix + ": " + error.getClass().getSimpleName() + (error.getMessage() == null ? "" : " — " + error.getMessage());
    }

    static Info query(IBinder target, int ar, int reverse) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); transact(target, 22, data, reply);
            return readInfo(reply, ar, reverse);
        } finally { data.recycle(); reply.recycle(); }
    }
    static Info readInfo(Parcel reply, int ar, int reverse) throws RemoteException {
        requireBytes(reply, 8);
        if (reply.readInt() != 1) throw new RemoteException("DVR returned no supported ReverseAVMInfo object");
        int count = reply.readInt();
        if (count < 0 || count > 16) throw new RemoteException("Invalid cameraExist array length: " + count);
        requireBytes(reply, 4 * count + 16);
        boolean[] exist = new boolean[count];
        for (int i = 0; i < count; i++) {
            int value = reply.readInt();
            if (value != 0 && value != 1) throw new RemoteException("Invalid cameraExist boolean");
            exist[i] = value != 0;
        }
        int avm = reply.readInt(), hardware = reply.readInt(), csi0 = reply.readInt(), csi1 = reply.readInt();
        int serverAr = -1, serverReverse = -1;
        try {
            // Optional exact layout from the extracted ReverseAVMInfo constructor.
            // An absent/incompatible suffix does not invalidate the stable prefix.
            skip(reply, 8 * 4);  // csi2..parkingRadarUIEnable
            skip(reply, 4 * 8);  // brightness, contrast, saturation, hue: longs
            skip(reply, 4 + 2 * 4); // mirror int, speed and angle floats
            skip(reply, 21 * 4); // doors/light, park hints and radar scalar fields
            for (int i = 0; i < 4; i++) {
                requireBytes(reply, 4);
                int length = reply.readInt();
                if (length < -1 || length > 64) throw new RemoteException("Unsupported radar array length");
                if (length >= 0) skip(reply, length * 4);
            }
            skip(reply, 2 * 4);  // writeByte encodes each radar enable as a Parcel int
            skip(reply, 10 * 4); // sleepStatus..carInfoAvm
            skip(reply, 8);      // sharp: long
            skip(reply, 2 * 4);  // lvds_mode and auxFormat
            requireBytes(reply, 4); int parsedReverse = reply.readInt();
            skip(reply, 2 * 4);  // arPreview and cameraPowerMode
            requireBytes(reply, 4); int parsedAr = reply.readInt();
            if (parsedReverse >= 0 && parsedReverse <= 6 && parsedAr >= 0 && parsedAr <= 1) {
                serverAr = parsedAr; serverReverse = parsedReverse;
            }
        } catch (RemoteException | RuntimeException unsupportedTail) {
            // Prefix-only state remains useful; ambiguous alias7 stays unresolved.
        }
        return new Info(exist, avm, hardware, csi0, csi1, ar, reverse, serverAr, serverReverse);
    }
    private static void skip(Parcel data, int bytes) throws RemoteException {
        requireBytes(data, bytes); data.setDataPosition(data.dataPosition() + bytes);
    }
    private static void requireBytes(Parcel data, int bytes) throws RemoteException {
        if (data.dataAvail() < bytes) throw new RemoteException("Truncated ReverseAVMInfo reply");
    }
    private static void stopRemote(IBinder target, int channel, int hash) throws RemoteException {
        if (hash == 0) throw new IllegalArgumentException("Refusing wildcard hash");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(1012); data.writeInt(channel); data.writeInt(hash);
            transact(target, 5, data, reply);
        } finally { data.recycle(); reply.recycle(); }
    }
    private static void transact(IBinder target, int code, Parcel data, Parcel reply) throws RemoteException {
        if (!target.transact(code, data, reply, 0)) throw new RemoteException("DVR rejected transaction " + code);
        if (reply.dataAvail() < 4) throw new RemoteException("Empty DVR reply to transaction " + code);
        reply.readException();
    }
}
