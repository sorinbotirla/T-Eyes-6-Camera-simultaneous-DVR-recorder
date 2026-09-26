package ro.interfaz.cameratester;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Started by visible UI; owns capture until the recorder has released every worker/file. */
public class RecordingService extends Service {
    static final String ACTION_START="ro.interfaz.cameratester.START_RECORDING";
    static final String ACTION_STOP="ro.interfaz.cameratester.STOP_RECORDING";
    static final String CHANNEL="camera-recording";
    static final int NOTIFICATION_ID=6042;
    private static final AtomicBoolean PENDING_START=new AtomicBoolean();
    interface Listener { void onSnapshot(Snapshot value); }

    /** All observer calls and service commands run on the main thread. */
    static final class Snapshot {
        final String state,message,transport,report;
        final List<CameraEncoder.Snapshot> stats;
        final boolean busy,benchmark;
        final int width,height,fps,bitrate;
        private final int[] selected;
        Snapshot(String state,String message,String transport,String report,
                 List<CameraEncoder.Snapshot> stats,boolean busy,DvrRecorder.Config config) {
            this.state=state;this.message=message;this.transport=transport;this.report=report;
            this.stats=Collections.unmodifiableList(new ArrayList<>(stats));this.busy=busy;
            benchmark=config!=null&&config.benchmark;
            width=config==null?0:config.width;height=config==null?0:config.height;
            fps=config==null?0:config.fps;bitrate=config==null?0:config.bitrate;
            selected=config==null?new int[0]:config.slots.clone();
        }
        int[] selectedSlots() {return selected.clone();}
    }

    // Small ownership seam: tests exercise service lifecycle without starting native codecs.
    interface Engine extends AutoCloseable {
        void start(DvrRecorder.Config config);
        void stop();
        boolean isBusy();
        String lastReport();
        @Override void close();
    }
    protected Engine createEngine(DvrRecorder.Listener listener) {
        DvrRecorder recorder=new DvrRecorder(getApplicationContext(),listener);
        return new Engine() {
            @Override public void start(DvrRecorder.Config config){recorder.start(config);}
            @Override public void stop(){recorder.stop();}
            @Override public boolean isBusy(){return recorder.isBusy();}
            @Override public String lastReport(){return recorder.lastReport();}
            @Override public void close(){recorder.close();}
        };
    }
    final class LocalBinder extends Binder { RecordingService getService(){return RecordingService.this;} }
    private final LocalBinder binder=new LocalBinder();
    private final List<Listener> listeners=new ArrayList<>();
    private Engine engine;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground,destroyed,engineClosed;
    private String state="IDLE",message="Choose a USB drive, then select the cameras to record.",transport="",report="";
    private List<CameraEncoder.Snapshot> stats=Collections.emptyList();
    private DvrRecorder.Config config;

    static Intent startIntent(Context context,DvrRecorder.Config config) {
        return new Intent(context,RecordingService.class).setAction(ACTION_START)
                .setData(config.tree).putExtra("slots",config.slots).putExtra("channels",config.channelChoices)
                .putExtra("width",config.width).putExtra("height",config.height).putExtra("fps",config.fps)
                .putExtra("bitrate",config.bitrate).putExtra("benchmark",config.benchmark);
    }
    /** Call only following a user action while an Activity is visible. */
    static void start(Context context,DvrRecorder.Config config) {
        if(DvrRecorder.anyRecording()||!PENDING_START.compareAndSet(false,true))
            throw new IllegalStateException("Recording is already starting or active.");
        try{context.startForegroundService(startIntent(context,config));}
        catch(RuntimeException error){PENDING_START.set(false);throw error;}
    }
    /** Covers the interval before Android delivers the explicit foreground-service command. */
    static boolean isCaptureActive(){return PENDING_START.get()||DvrRecorder.anyRecording();}
    static void requestStop(Context context) {
        context.startService(new Intent(context,RecordingService.class).setAction(ACTION_STOP));
    }
    private static DvrRecorder.Config decode(Intent intent) {
        Uri tree=intent.getData();int[] slots=intent.getIntArrayExtra("slots"),channels=intent.getIntArrayExtra("channels");
        if(tree==null||slots==null||slots.length<1||slots.length>6||channels==null||channels.length!=6)
            throw new IllegalArgumentException("Choose USB storage and between one and six cameras.");
        boolean[] seen=new boolean[6];for(int slot:slots){if(slot<0||slot>=6||seen[slot])throw new IllegalArgumentException("Invalid camera selection.");seen[slot]=true;}
        int width=intent.getIntExtra("width",0),height=intent.getIntExtra("height",0),fps=intent.getIntExtra("fps",0),bitrate=intent.getIntExtra("bitrate",0);
        if(width<1||height<1||fps<1||bitrate<1)throw new IllegalArgumentException("Invalid recording profile.");
        return new DvrRecorder.Config(tree,slots,channels,width,height,fps,bitrate,intent.getBooleanExtra("benchmark",false));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        if(notifications!=null) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Camera recording",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Recording and benchmark status, with an explicit Stop action.");
            notifications.createNotificationChannel(channel);
        }
        engine=createEngine(new DvrRecorder.Listener() {
            @Override public void onState(String next,String text) {
                if("IDLE".equals(next)&&engine!=null&&engine.isBusy())return;
                state=next;message=text;
                if("IDLE".equals(next)&&engine!=null&&!engine.isBusy())finishStartedWork();
                else if(foreground&&!destroyed)updateNotification();
                publish();
            }
            @Override public void onStats(List<CameraEncoder.Snapshot> values){stats=new ArrayList<>(values);publish();}
            @Override public void onReport(String value){report=value==null?"":value;publish();}
            @Override public void onTransportStats(String value){transport=value==null?"":value;publish();}
        });
    }
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){return true;}
    @Override public void onTaskRemoved(Intent rootIntent){/* The user explicitly chose background recording. */}

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){if(!engine.isBusy())stopSelf();return START_NOT_STICKY;}
        if(ACTION_STOP.equals(intent.getAction())){stopRecording();return START_NOT_STICKY;}
        if(!ACTION_START.equals(intent.getAction())){if(!engine.isBusy())stopSelf();return START_NOT_STICKY;}
        try {
        if(engine.isBusy()){updateNotification();publish();return START_NOT_STICKY;}
        try {
            if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Camera permission is required. Open the DVR screen and try again.");
            config=decode(intent);state="PREPARING";
            message=config.benchmark?"Preparing a background camera benchmark…":"Preparing background recording…";
            stats=Collections.emptyList();transport="Waiting for camera input frames…";
            beginForeground();acquireWakeLock();publish();engine.start(config);
            // A process-wide owner or scheduling failure can reject a start synchronously.
            if(!engine.isBusy()){state="IDLE";message="Recording could not start. Check the latest status and USB selection.";finishStartedWork();publish();}
        } catch(RuntimeException error) {
            message="Cannot start recording: "+detail(error);
            if(engine.isBusy()){state="STOPPING";engine.stop();}
            else{state="IDLE";finishStartedWork();}
            publish();
        }
        return START_NOT_STICKY;
        } finally {PENDING_START.set(false);publish();}
    }
    void stopRecording() {
        if(engine.isBusy()){
            state="STOPPING";message="Stopping camera feeds and finalizing MP4 files…";
            engine.stop();updateNotification();publish();
        } else finishStartedWork();
    }
    Snapshot snapshot() {
        if(report.isEmpty()&&engine!=null){String saved=engine.lastReport();if(saved!=null)report=saved;}
        return new Snapshot(state,message,transport,report,stats,engine!=null&&engine.isBusy(),config);
    }
    void addListener(Listener listener){if(!listeners.contains(listener))listeners.add(listener);listener.onSnapshot(snapshot());}
    void removeListener(Listener listener){listeners.remove(listener);}
    private void publish(){if(destroyed)return;Snapshot value=snapshot();for(Listener listener:new ArrayList<>(listeners))try{listener.onSnapshot(value);}catch(RuntimeException ignored){/* A detached UI must not interrupt recording cleanup. */}}

    private Notification notification() {
        int immutable=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,RecordingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),immutable);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,RecordingService.class).setAction(ACTION_STOP),immutable);
        String title="STOPPING".equals(state)?"Finishing camera clips":(config!=null&&config.benchmark?"Camera benchmark":"Camera recording");
        String text="PREPARING".equals(state)?"Preparing cameras and USB storage…":("STOPPING".equals(state)?"Keep the USB drive connected until saving finishes.":"Recording continues when you leave the app.");
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view,"Open",open).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stop).build()).build();
    }
    private void beginForeground() {
        if(Build.VERSION.SDK_INT>=30)startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        else startForeground(NOTIFICATION_ID,notification());
        foreground=true;
    }
    private void updateNotification() {
        if(!foreground||destroyed)return;
        // startForeground remains permitted when the optional notification permission is denied.
        try{beginForeground();}catch(RuntimeException ignored){/* Do not abandon files because a status update failed. */}
    }
    @SuppressLint("WakelockTimeout") // Held through native/file cleanup, including quarantined workers; released only at terminal IDLE.
    private void acquireWakeLock() {
        PowerManager power=getSystemService(PowerManager.class);
        if(power==null)throw new IllegalStateException("Device power management is unavailable.");
        if(wakeLock==null){wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CameraTester:Recording");wakeLock.setReferenceCounted(false);}
        if(!wakeLock.isHeld())wakeLock.acquire();
    }
    private void finishStartedWork() {
        if(engine!=null&&engine.isBusy())return;
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
        if(foreground){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;}
        if(destroyed)closeEngine();else stopSelf();
    }
    private void closeEngine(){if(!engineClosed&&engine!=null){engineClosed=true;engine.close();}}
    @Override public void onDestroy() {
        destroyed=true;listeners.clear();
        // Normal stopSelf happens only after IDLE. An unexpected destruction still requests
        // graceful cleanup and retains its wake lock until the final recorder callback.
        if(engine!=null&&engine.isBusy())engine.stop();else{finishStartedWork();closeEngine();}
        super.onDestroy();
    }
    private static String detail(Throwable value){return value.getMessage()==null?value.getClass().getSimpleName():value.getMessage();}
}
