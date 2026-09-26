package ro.interfaz.cameratester;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Export workers retain application context and weak UI observers, never an Activity. */
final class ExportJob {
    interface Observer { void onExportChanged(ExportJob job); }
    static final String ARCHIVE="archive",TEXT="text";
    private static final Map<String,ExportJob> JOBS=new HashMap<>();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private final Context context;
    final String key,mode;
    final Uri destination;
    private final int reportKind;
    private final AtomicBoolean notificationPending=new AtomicBoolean();
    private volatile WeakReference<Observer> observer=new WeakReference<>(null);
    private volatile Thread thread;
    private volatile boolean running=true,cancelRequested;
    private volatile String message="Starting export…";

    private ExportJob(Context context,String key,String mode,Uri destination,int reportKind) {
        this.context=context.getApplicationContext();this.key=key;this.mode=mode;this.destination=destination;this.reportKind=reportKind;
    }
    static synchronized ExportJob current(String key) { return JOBS.get(key); }
    static synchronized ExportJob start(Context context,String key,String mode,Uri destination,int reportKind) {
        ExportJob previous=JOBS.get(key);
        if(previous!=null&&previous.running)throw new IllegalStateException("An export is already running.");
        ExportJob job=new ExportJob(context,key,mode,destination,reportKind);JOBS.put(key,job);
        job.preferences().edit().putBoolean(key+"-running",true).putString(key+"-destination",destination.toString()).apply();
        job.thread=new Thread(job::run,"camera-export-"+key);job.thread.start();return job;
    }
    static String previousMessage(Context context,String key) {
        SharedPreferences saved=context.getSharedPreferences("export-jobs",Context.MODE_PRIVATE);
        if(saved.getBoolean(key+"-running",false)&&current(key)==null) {
            String message="The previous export was interrupted when the app process stopped. Its destination may contain an incomplete file. Choose a new destination to retry.\n"+saved.getString(key+"-destination","");
            saved.edit().putBoolean(key+"-running",false).putString(key+"-message",message).apply();return message;
        }
        return saved.getString(key+"-message","Ready. Choose a destination and filename using Android's document picker.");
    }
    boolean running() { return running; }
    String message() { return message; }
    void attach(Observer observer) { this.observer=new WeakReference<>(observer);notifyObserver(); }
    void detach(Observer observer) { if(this.observer.get()==observer)this.observer=new WeakReference<>(null); }
    void cancel() {
        if(!running)return;cancelRequested=true;message="Cancellation requested. Waiting for the current storage operation to return…";
        Thread worker=thread;if(worker!=null)worker.interrupt();notifyObserver();
    }
    private void progress(String value) { if(!cancelRequested){message=value;notifyObserver();} }
    private SharedPreferences preferences() { return context.getSharedPreferences("export-jobs",Context.MODE_PRIVATE); }
    private void run() {
        try {
            ExtractionBundle.checkCancelled();
            try(OutputStream output=context.getContentResolver().openOutputStream(destination,"wt")) {
                if(output==null)throw new IOException("The selected destination is unavailable.");
                if("apps".equals(mode)) {
                    ExtractionBundle.Result result=ExtractionBundle.write(context,output,this::progress);
                    message=result.summary();
                } else {
                    progress("Preparing "+("config".equals(mode)?"camera configuration":"camera reports")+"…");
                    String text="config".equals(mode)?ExportData.config(context):ExportData.report(context,reportKind);
                    byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
                    for(int offset=0;offset<bytes.length;offset+=32*1024){ExtractionBundle.checkCancelled();output.write(bytes,offset,Math.min(32*1024,bytes.length-offset));}
                    output.flush();message="Export saved successfully. The chosen filename and destination were used.";
                }
            }
            if(cancelRequested)message="Export cancelled. The destination may contain an incomplete file. No existing source files were deleted.";
        } catch(Exception error) {
            message=cancelRequested||error instanceof InterruptedIOException?"Export cancelled. The destination may contain an incomplete file. Choose a new filename when retrying.":"Export failed: "+detail(error)+"\nThe destination may contain an incomplete file. No existing source files were deleted.";
        } finally {
            running=false;thread=null;
            preferences().edit().putBoolean(key+"-running",false).putString(key+"-message",message).apply();notifyObserver();
        }
    }
    private void notifyObserver() {
        if(!notificationPending.compareAndSet(false,true))return;
        MAIN.post(()->{notificationPending.set(false);Observer current=observer.get();if(current!=null)current.onExportChanged(this);});
    }
    private static String detail(Exception error) { return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); }
}
