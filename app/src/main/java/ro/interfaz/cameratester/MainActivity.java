package ro.interfaz.cameratester;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private Registry registry;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LinearLayout root,body,sourceList,previewHost;
    private TextView detectionStatus,summary;
    private Button detectButton;
    private Intent pendingNavigation;
    private Discovery discovery;
    private Streams streams;
    private String screen="home",pendingUsb="",pendingCamera="";
    private int selected=-1,previewGeneration;
    private boolean resumed,scanning,autoTesting,testResolved;
    private final ArrayDeque<Source> testQueue=new ArrayDeque<>();
    private final BroadcastReceiver usbPermission=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            String key=pendingUsb;pendingUsb="";
            if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)){pendingCamera=key;if(resumed)resumePending();}
            else toast("USB permission denied. Other discoveries are retained.");
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);registry=new Registry(this);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(usbPermission,new IntentFilter(getPackageName()+".USB_PERMISSION"),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbPermission,new IntentFilter(getPackageName()+".USB_PERMISSION"));
        if(state!=null){screen=state.getString("screen","home");selected=state.getInt("selected",-1);}
        if("detect".equals(getIntent().getStringExtra("screen")))screen="detect";
        createChrome();if(screen.equals("detect"))showDetection();else showHome();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(215,229,234));return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private void createChrome(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));setContentView(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);TextView title=text("Camera Tester",24);title.setTextColor(Color.rgb(99,215,206));bar.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        Button hamburger=button("☰",()->AppMenu.show(this,findViewById(R.id.hamburger)));hamburger.setContentDescription("Toggle navigation menu");hamburger.setId(R.id.hamburger);bar.addView(hamburger,new LinearLayout.LayoutParams(dp(64),dp(52)));root.addView(bar);
        summary=text("Six camera positions · sources saved locally",12);root.addView(summary);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
    }
    void openDetection(){pendingNavigation=null;cancelDetection();showDetection();}
    void navigateTo(Intent intent){
        cancelDetection();stopStreams();
        if(scanning){pendingNavigation=intent;return;}
        pendingNavigation=null;startActivity(intent);
    }
    private void showHome(){
        pendingNavigation=null;screen="home";clearBody();
        summary.setText("Six camera positions · sources saved locally");
        TextView welcome=text("Open the menu to show all six cameras, record, detect sources or export data.",20);
        welcome.setPadding(dp(12),dp(28),dp(12),dp(24));body.addView(welcome);
        body.addView(text(RecordingService.isCaptureActive()
            ?"Recording is active in the background. Open Record to view its status or stop it."
            :"Recording continues when you leave the app. Start and stop it from Record.",16));
        body.addView(text("ADAS Front · ADAS Rear · 360 Front · 360 Left · 360 Right · 360 Rear",14));
    }
    private boolean captureAvailable(){
        if(!RecordingService.isCaptureActive())return true;
        toast("Recording is active. Open Record to stop it before testing or previewing cameras.");return false;
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if("detect".equals(intent.getStringExtra("screen")))openDetection();else showHome();
    }
    private void clearBody(){stopStreams();body.removeAllViews();sourceList=null;previewHost=null;detectionStatus=null;detectButton=null;}
    private void showDetection(){
        autoTesting=false;testQueue.clear();screen="detect";clearBody();
        LinearLayout actions=new LinearLayout(this);
        detectButton=button(scanning?"Stop detection":"Detect camera streams",()->{if(scanning)cancelDetection();else requestDetection();});actions.addView(detectButton,new LinearLayout.LayoutParams(0,dp(52),1));
        actions.addView(button("Add URL",this::addUrl));body.addView(actions);
        detectionStatus=text(registry.state(),14);body.addView(detectionStatus);
        body.addView(text("Park before testing. Detection opens sources sequentially; DVR/ADAS/360 may pause. Assign each image to its position after checking it.",13));
        previewHost=new LinearLayout(this);previewHost.setOrientation(LinearLayout.VERTICAL);previewHost.setVisibility(View.GONE);body.addView(previewHost,new LinearLayout.LayoutParams(-1,dp(190)));
        ScrollView scroll=new ScrollView(this);sourceList=new LinearLayout(this);sourceList.setOrientation(LinearLayout.VERTICAL);scroll.addView(sourceList);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));renderSources();
    }
    private void requestDetection(){
        if(!captureAvailable())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){pendingCamera="DETECT";requestPermissions(new String[]{Manifest.permission.CAMERA},10);return;}
        startDetection();
    }
    private void addUrl(){
        EditText input=new EditText(this);input.setSingleLine();input.setHint("rtsp://127.0.0.1:8554/live");
        Spinner format=new Spinner(this);format.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"RTSP / HTTP video / HLS","HTTP MJPEG"}));
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.addView(input);fields.addView(format);
        new AlertDialog.Builder(this).setTitle("Add a known stream URL").setView(fields).setPositiveButton("Save",(d,w)->{
            String url=input.getText().toString().trim();
            try{java.net.URI uri=new java.net.URI(url);String scheme=uri.getScheme();if(uri.getHost()==null||!("rtsp".equals(scheme)||"http".equals(scheme)||"https".equals(scheme)))throw new IllegalArgumentException();
                registry.discovered(new Source("network",url,"","Stream · "+url,format.getSelectedItemPosition()==1?"User supplied · multipart/x-mixed-replace":"User supplied URL — unverified"));renderSources();
            }catch(Exception e){toast("Enter a valid rtsp://, http:// or https:// URL");}
        }).setNegativeButton("Cancel",null).show();
    }
    private void startDetection(){
        if(scanning||!captureAvailable())return;autoTesting=false;testQueue.clear();stopStreams();scanning=true;detectButton.setText("Stop detection");
        discovery=new Discovery(this,registry,new Discovery.Listener(){
            @Override public void progress(String message){main.post(()->{if(detectionStatus!=null)detectionStatus.setText(message);});}
            @Override public void source(Source source){scheduleRefresh();}
            @Override public void finished(){main.post(()->{
                if(isDestroyed())return;scanning=false;if(detectButton!=null)detectButton.setText("Detect camera streams");renderSources();
                if(pendingNavigation!=null&&resumed){navigateTo(pendingNavigation);return;}
                if(discovery.cancelled.get()||!screen.equals("detect")){return;}
                for(Source s:registry.all())if(s.present&&s.playable())testQueue.add(s);
                if(resumed){autoTesting=true;nextTest();}else registry.end("Discovery saved — choose a source to test after returning");
            });}
        });worker.execute(discovery);
    }
    private final Runnable refresh=()->{if(!isDestroyed())renderSources();};
    private void scheduleRefresh(){main.removeCallbacks(refresh);main.postDelayed(refresh,150);}
    private void cancelDetection(){if(discovery!=null)discovery.cancelled.set(true);autoTesting=false;testQueue.clear();stopStreams();if(detectionStatus!=null)detectionStatus.setText("Stopping — discoveries retained");}
    private void renderSources(){
        if(sourceList==null)return;sourceList.removeAllViews();int playable=0,verified=0;
        for(Source s:registry.all()){
            if(s.playable())playable++;if(s.verifiedAt>0)verified++;
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(6),dp(8),dp(6),dp(8));
            row.addView(text(s.label+(s.present?"":" · not seen in latest scan"),16));row.addView(text(s.lastResult,12));
            LinearLayout actions=new LinearLayout(this);
            if(s.playable()){
                Button test=button("Test",()->manualTest(s));test.setEnabled(!scanning);actions.addView(test);
                Button assign=button("Assign position",()->assign(s));assign.setEnabled(!scanning);actions.addView(assign);
            }
            actions.addView(button("Details",()->new AlertDialog.Builder(this).setTitle(s.label).setMessage(s.evidence+"\n\n"+s.lastResult).setPositiveButton("Close",null).show()));
            if(s.kind.equals("vendor-clue"))actions.addView(button("Open app",()->openVendor(s.address)));
            row.addView(actions);sourceList.addView(row);
        }
        if(registry.all().isEmpty())sourceList.addView(text("No discoveries yet. Tap Detect camera streams.",17));
        summary.setText(playable+" stream candidates · "+verified+" with previously verified frames · six positions");
        if(detectionStatus!=null&&!scanning&&!autoTesting)detectionStatus.setText(registry.state());
    }
    private void nextTest(){
        stopStreams();if(!autoTesting||!resumed||!screen.equals("detect"))return;
        Source source=testQueue.poll();
        if(source==null){autoTesting=false;registry.end("Detection complete — sources and test results saved");if(previewHost!=null)previewHost.setVisibility(View.GONE);renderSources();return;}
        if(source.kind.equals("uvc")&&!usbAllowed(source)){
            registry.result(source.key,"USB permission needed — tap Test",false,0,0);main.postDelayed(this::nextTest,80);return;
        }
        test(source,true);
    }
    private boolean usbAllowed(Source source){UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);UsbDevice device=manager.getDeviceList().get(source.address);return device!=null&&manager.hasPermission(device);}
    private void manualTest(Source source){
        if(!captureAvailable())return;
        autoTesting=false;testQueue.clear();
        if(!source.kind.equals("teyes")&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){pendingCamera=source.key;requestPermissions(new String[]{Manifest.permission.CAMERA},10);return;}
        if(source.kind.equals("uvc")&&!usbAllowed(source)){
            UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);UsbDevice device=manager.getDeviceList().get(source.address);
            if(device==null){toast("USB device detached. Detect again.");return;}
            pendingUsb=source.key;Intent request=new Intent(getPackageName()+".USB_PERMISSION").setPackage(getPackageName());
            manager.requestPermission(device,PendingIntent.getBroadcast(this,0,request,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));return;
        }
        if(!screen.equals("detect"))showDetection();test(source,false);
    }
    private void test(Source source,boolean automatic){
        if(!captureAvailable()){autoTesting=false;testQueue.clear();return;}
        stopStreams();int generation=previewGeneration;testResolved=false;previewHost.removeAllViews();previewHost.setVisibility(View.VISIBLE);
        StreamTile tile=new StreamTile(this,source.label,source,()->assign(source));previewHost.addView(tile,new LinearLayout.LayoutParams(-1,-1));
        detectionStatus.setText((automatic?"Testing · ":"Preview · ")+source.label);
        streams=new Streams(this,registry,Collections.singletonList(tile),new Streams.Events(){
            public void verified(Source s){resolve("Frames received · tap Assign position");}
            public void failed(Source s,String error){resolve(error);}
            private void resolve(String message){
                if(testResolved||generation!=previewGeneration)return;testResolved=true;scheduleRefresh();if(detectionStatus!=null)detectionStatus.setText(message);
                if(automatic)main.postDelayed(()->{if(autoTesting&&generation==previewGeneration)nextTest();},600);
            }
        });
    }
    private void assign(Source source){
        autoTesting=false;testQueue.clear();
        new AlertDialog.Builder(this).setTitle("Which camera is this?").setItems(Source.SLOTS,(dialog,which)->{
            registry.map(which,source.key);toast("Saved as "+Source.SLOTS[which]);if(screen.equals("streams"))showStreams(selected);
        }).setNegativeButton("Cancel",null).show();
    }
    private void chooseSource(int slot){
        List<Source> candidates=new ArrayList<>();for(Source s:registry.all())if(s.playable())candidates.add(s);
        String[] labels=new String[candidates.size()+1];labels[0]="Unassigned";
        for(int i=0;i<candidates.size();i++){Source s=candidates.get(i);labels[i+1]=s.label+(s.verifiedAt>0?" · frames verified":" · unverified");}
        new AlertDialog.Builder(this).setTitle(Source.SLOTS[slot]+" · source").setItems(labels,(dialog,which)->{
            registry.map(slot,which==0?"":candidates.get(which-1).key);showStreams(selected);
        }).setNegativeButton("Cancel",null).show();
    }
    private void showStreams(int slot){
        autoTesting=false;testQueue.clear();screen="streams";selected=slot;clearBody();
        LinearLayout actions=new LinearLayout(this);actions.addView(button("All cameras",()->showStreams(-1)));
        if(slot>=0)actions.addView(button("Assign source",()->chooseSource(slot)));
        else actions.addView(text("Tap a tile for individual view",13));
        body.addView(actions);List<StreamTile> playable=new ArrayList<>();
        if(slot>=0){
            Source s=registry.get(registry.mapping(slot));if(s!=null&&!s.playable())s=null;StreamTile tile=new StreamTile(this,Source.SLOTS[slot],s,()->chooseSource(slot));
            body.addView(tile,new LinearLayout.LayoutParams(-1,0,1));if(s!=null)playable.add(tile);
        }else{
            int columns=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?3:2;
            for(int row=0;row<6/columns;row++){
                LinearLayout line=new LinearLayout(this);body.addView(line,new LinearLayout.LayoutParams(-1,0,1));
                for(int col=0;col<columns;col++){
                    int index=row*columns+col;Source stored=registry.get(registry.mapping(index));Source s=stored!=null&&stored.playable()?stored:null;
                    StreamTile tile=new StreamTile(this,Source.SLOTS[index],s,()->{if(s==null)chooseSource(index);else showStreams(index);});
                    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);params.setMargins(dp(3),dp(3),dp(3),dp(3));line.addView(tile,params);if(s!=null)playable.add(tile);
                }
            }
        }
        summary.setText(slot<0?"All six positions · simultaneous playback where supported":Source.SLOTS[slot]+" · tap image to change source");
        if(resumed&&!RecordingService.isCaptureActive()&&!playable.isEmpty())streams=new Streams(this,registry,playable,new Streams.Events(){
            public void verified(Source s){}
            public void failed(Source s,String message){registry.log("Grid "+s.key+": "+message);}
        });
    }
    private void openVendor(String packageName){
        Intent intent=getPackageManager().getLaunchIntentForPackage(packageName);if(intent==null){toast("No launchable activity exposed");return;}
        try{startActivity(intent);}catch(Exception e){toast(e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==10){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){if(resumed)resumePending();}
            else{String action=pendingCamera;pendingCamera="";toast("Camera permission denied. Hardware inventory remains available.");if(action.equals("DETECT"))startDetection();}
        }
    }
    private void resumePending(){String action=pendingCamera;pendingCamera="";if(action.equals("DETECT"))startDetection();else if(!action.isEmpty()){Source s=registry.get(action);if(s!=null)manualTest(s);}}
    private void stopStreams(){previewGeneration++;if(streams!=null){streams.close();streams=null;}}
    private void toast(String message){if(!isDestroyed())Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void exitApp(){cancelDetection();stopStreams();finishAndRemoveTask();}
    @Override protected void onResume(){super.onResume();if(!scanning)registry=new Registry(this);resumed=true;if(pendingNavigation!=null&&!scanning)navigateTo(pendingNavigation);else if(!pendingCamera.isEmpty()&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)resumePending();else if(screen.equals("streams"))showStreams(selected);else if(screen.equals("home"))showHome();}
    @Override protected void onPause(){AppMenu.dismiss(this);resumed=false;autoTesting=false;testQueue.clear();stopStreams();super.onPause();}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if(screen.equals("streams"))showStreams(selected);}
    @Override public void onBackPressed(){if(AppMenu.dismiss(this))return;if(!screen.equals("home"))showHome();else exitApp();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("screen",screen);out.putInt("selected",selected);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){AppMenu.dismiss(this);if(discovery!=null)discovery.cancelled.set(true);stopStreams();main.removeCallbacksAndMessages(null);worker.shutdownNow();unregisterReceiver(usbPermission);super.onDestroy();}
}
