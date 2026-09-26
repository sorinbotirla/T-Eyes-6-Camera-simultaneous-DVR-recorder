package ro.interfaz.cameratester;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressLint("SetTextI18n")
public final class RecordingActivity extends Activity {
    private static final int PICK_USB=60, FORMAT_USB=61, CAMERA_PERMISSION=63, NOTIFICATION_PERMISSION=64;
    private static final int GRANTS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final String[] RESOLUTIONS={"1280 × 720","960 × 540","640 × 480"};
    private static final int[][] SIZES={{1280,720},{960,540},{640,480}};
    private static final int[] BITRATES={1,2,3,4,6};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<View> configurationControls=new ArrayList<>();
    private final CheckBox[] cameras=new CheckBox[6];
    private SharedPreferences preferences;
    private RecordingService service;
    private boolean bound,visible,awaitingCamera,pendingBenchmark,startAfterPermission;
    private final RecordingService.Listener serviceListener=this::renderSnapshot;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            if(!bound)return;
            service=((RecordingService.LocalBinder)binder).getService();service.addListener(serviceListener);
        }
        @Override public void onServiceDisconnected(ComponentName name){service=null;updateControls();}
        @Override public void onBindingDied(ComponentName name){detachService();if(visible)attachService();}
        @Override public void onNullBinding(ComponentName name){detachService();details.setText("Recording service connection is unavailable. Reopen this screen to try again.");updateControls();}
    };
    private RecordControlView record;
    private TextView stateLabel,details,count,driveLabel,usbInventory,statistics,transportStatistics,reportStatus;
    private Button benchmark,format,chooseDrive,viewReport;
    private Spinner resolution,fps,bitrate;
    private Uri tree;
    private String volumeUuid="",volumeName="",pendingUuid="",pendingName="",state="IDLE",report="";
    private boolean formatFlow,formatLeft,resumed,waitingForOther,pendingTreeClear;
    private final Runnable lifecycleRefresh=new Runnable() {
        @Override public void run() {
            if(!resumed||isDestroyed())return;
            if(!RecordingService.isCaptureActive()&&(waitingForOther||pendingTreeClear)) {
                if(pendingTreeClear){pendingTreeClear=false;clearTree();}
                details.setText("Previous recording finished. USB access is available.");refreshStorage();
            }
            if(report.isEmpty()&&service!=null&&!service.snapshot().report.isEmpty()) {
                report=service.snapshot().report;reportStatus.setText("A previous recording / benchmark report is available.");
            }
            updateControls();main.postDelayed(this,500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences=getSharedPreferences("six-camera-dvr",MODE_PRIVATE);
        volumeUuid=preferences.getString("volume-uuid",""); volumeName=preferences.getString("volume-name","");
        String savedTree=preferences.getString("usb-tree","");
        if (!savedTree.isEmpty()) tree=Uri.parse(savedTree);
        if (saved!=null) {
            pendingUuid=saved.getString("pending-uuid",""); pendingName=saved.getString("pending-name","");
            formatFlow=saved.getBoolean("format-flow"); formatLeft=saved.getBoolean("format-left");
            awaitingCamera=saved.getBoolean("awaiting-camera");pendingBenchmark=saved.getBoolean("pending-benchmark");
            startAfterPermission=saved.getBoolean("start-after-permission");
        }
        createUi();
        updateControls();
    }

    private void attachService() {
        if(bound)return;
        bound=bindService(new Intent(this,RecordingService.class),connection,BIND_AUTO_CREATE);
        if(!bound)details.setText("Cannot connect to the recording service. Reopen this screen to try again.");
    }
    private void detachService() {
        if(service!=null)service.removeListener(serviceListener);
        service=null;if(bound){bound=false;unbindService(connection);}
    }
    private void renderSnapshot(RecordingService.Snapshot value) {
        if(isDestroyed())return;
        state=value.state;stateLabel.setText(stateTitle(state));details.setText(value.message);
        StringBuilder rows=new StringBuilder();
        for(CameraEncoder.Snapshot snapshot:value.stats) {
            double mbps=snapshot.elapsedSeconds>0?snapshot.encodedBytes*8.0/snapshot.elapsedSeconds/1_000_000:0;
            rows.append(String.format(Locale.US,"%s · ch %d\n%.1f FPS · %.2f Mbps · %,d frames\nWrite mean %.2f ms · p95 %.2f ms · max %.2f ms\n%s%s\n\n",
                    snapshot.config.slot,snapshot.config.channel,snapshot.observedFps,mbps,snapshot.frames,
                    snapshot.meanWriteMs,snapshot.p95WriteMs,snapshot.maxWriteMs,snapshot.codecName,
                    snapshot.error==null||snapshot.error.isEmpty()?"":"\n"+snapshot.error));
        }
        statistics.setText(rows.length()==0?"No encoded frames yet.":rows.toString().trim());
        if(!value.transport.isEmpty())transportStatistics.setText(value.transport);
        report=value.report;
        if(!report.isEmpty())reportStatus.setText("Latest recording / benchmark report is saved on this device.");
        if(value.busy) {
            boolean[] selected=new boolean[6];for(int slot:value.selectedSlots())selected[slot]=true;
            for(int i=0;i<6;i++)cameras[i].setChecked(selected[i]);
            for(int i=0;i<SIZES.length;i++)if(SIZES[i][0]==value.width&&SIZES[i][1]==value.height)resolution.setSelection(i);
            fps.setSelection(value.fps==30?1:0);
            for(int i=0;i<BITRATES.length;i++)if(BITRATES[i]*1_000_000==value.bitrate)bitrate.setSelection(i);
        }
        updateControls();
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(219,231,237)); view.setPadding(0,dp(4),0,dp(4)); return view;
    }
    private Button button(String label,Runnable action) {
        Button view=new Button(this); view.setAllCaps(false); view.setText(label); view.setMinHeight(dp(48));
        view.setOnClickListener(v->action.run()); return view;
    }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private Spinner spinner(String[] items,int selected) {
        Spinner view=new Spinner(this); view.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));
        view.setSelection(Math.max(0,Math.min(items.length-1,selected))); view.setMinimumHeight(dp(48));
        configurationControls.add(view); return view;
    }
    private void createUi() {
        LinearLayout root=column(); root.setBackgroundColor(Color.rgb(12,22,29)); root.setPadding(dp(12),dp(6),dp(12),dp(6)); setContentView(root);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("6-camera DVR",24); title.setTextColor(Color.rgb(99,215,206));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=button("☰",()->{});menu.setId(R.id.hamburger);menu.setContentDescription("Toggle navigation menu");
        menu.setOnClickListener(v->AppMenu.show(this,v));header.addView(menu);root.addView(header);
        LinearLayout transport=new LinearLayout(this); transport.setGravity(Gravity.CENTER_VERTICAL);
        record=new RecordControlView(this); record.setOnClickListener(v->{if("RECORDING".equals(state))stopRecording();else if("IDLE".equals(state))start(false);});
        transport.addView(record,new LinearLayout.LayoutParams(dp(88),dp(64)));
        LinearLayout status=column(); stateLabel=text("Ready",20); details=text("Choose a USB drive, then select the cameras to record.",13);
        status.addView(stateLabel); status.addView(details); transport.addView(status,new LinearLayout.LayoutParams(0,-2,1)); root.addView(transport);
        ScrollView scroll=new ScrollView(this); LinearLayout body=column(); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(text("Three-minute loop. Active clips: 6camdvr/current. Previous completed clips: 6camdvr/last. Each camera keeps its fixed filename.",13));
        body.addView(text("Recording continues when you leave this app. Return here or use Stop in the recording notification to finish saving clips.",13));
        driveLabel=text("No USB drive selected",16); body.addView(driveLabel);
        LinearLayout storageButtons=new LinearLayout(this);
        chooseDrive=button("Choose USB drive",this::chooseUsb); format=button("Format USB…",this::formatUsb);
        storageButtons.addView(chooseDrive,new LinearLayout.LayoutParams(0,-2,1)); storageButtons.addView(format,new LinearLayout.LayoutParams(0,-2,1)); body.addView(storageButtons);
        Button refresh=button("Refresh USB list",()->{if(!busy())refreshStorage();});
        configurationControls.add(refresh);body.addView(refresh);
        usbInventory=text("",12); body.addView(usbInventory);
        count=text("6 cameras selected",16); body.addView(count);
        for (int row=0;row<3;row++) {
            LinearLayout line=new LinearLayout(this);
            for (int col=0;col<2;col++) {
                final int slot=row*2+col; CheckBox check=new CheckBox(this); check.setText(Source.SLOTS[slot]);
                check.setTextColor(Color.WHITE); check.setMinHeight(dp(48)); check.setChecked(preferences.getBoolean("selected-"+slot,true));
                check.setOnCheckedChangeListener((button,checked)->{preferences.edit().putBoolean("selected-"+slot,checked).apply();updateControls();});
                cameras[slot]=check; configurationControls.add(check); line.addView(check,new LinearLayout.LayoutParams(0,-2,1));
            }
            body.addView(line);
        }
        LinearLayout settings=new LinearLayout(this);
        LinearLayout resolutionColumn=column(); resolutionColumn.addView(text("Resolution",13));
        resolution=spinner(RESOLUTIONS,preferences.getInt("resolution",0)); resolutionColumn.addView(resolution); settings.addView(resolutionColumn,new LinearLayout.LayoutParams(0,-2,2));
        LinearLayout fpsColumn=column(); fpsColumn.addView(text("FPS target",13));
        fps=spinner(new String[]{"25","30"},preferences.getInt("fps",0)); fpsColumn.addView(fps); settings.addView(fpsColumn,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout bitrateColumn=column(); bitrateColumn.addView(text("Mbps / camera",13));
        bitrate=spinner(new String[]{"1","2","3","4","6"},preferences.getInt("bitrate",2)); bitrateColumn.addView(bitrate); settings.addView(bitrateColumn,new LinearLayout.LayoutParams(0,-2,1)); body.addView(settings);
        body.addView(text("Uses the channel choices saved in TEYES Cameras. Auto choices use the current TEYES configuration. Preview is paused while recording.",12));
        body.addView(text("Files: adas_front.mp4 · adas_rear.mp4 · 360front.mp4 · 360left.mp4 · 360right.mp4 · 360rear.mp4",12));
        benchmark=button("Benchmark selected cameras + USB",()->start(true)); body.addView(benchmark);
        body.addView(text("Benchmark: 64 MiB USB write test, then 30 seconds of selected camera encoding. Temporary benchmark clips are discarded; previous recordings are kept.",12));
        statistics=text("Live statistics will appear here.",13); body.addView(statistics);
        transportStatistics=text("Camera input and encoder input measurements will appear here.",12); body.addView(transportStatistics);
        reportStatus=text("No recording / benchmark report yet.",12); body.addView(reportStatus);
        viewReport=button("View recording / benchmark report",this::viewReport);body.addView(viewReport);
    }

    private boolean busy() { return service!=null&&service.snapshot().busy || !"IDLE".equals(state); }
    private int selectedCount() { int result=0; for(CheckBox camera:cameras)if(camera!=null&&camera.isChecked())result++; return result; }
    private void updateControls() {
        if(record==null||benchmark==null)return;
        boolean busy=busy(),locked=busy||RecordingService.isCaptureActive();int selected=selectedCount();count.setText(selected+" camera"+(selected==1?"":"s")+" selected");
        boolean other=!busy&&RecordingService.isCaptureActive();
        if(other&&!waitingForOther){stateLabel.setText("Connecting to active recording…");details.setText("Restoring the background recording controls.");}
        else if(!other&&waitingForOther)stateLabel.setText(stateTitle(state));
        waitingForOther=other;
        for(View control:configurationControls)control.setEnabled(!locked);
        chooseDrive.setEnabled(!locked); format.setEnabled(!locked&&tree!=null);
        viewReport.setEnabled(!report.isEmpty());
        benchmark.setEnabled(!locked&&tree!=null&&selected>0);
        record.setState(state); record.setEnabled("RECORDING".equals(state)||(!locked&&tree!=null&&selected>0));
        if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private static String stateTitle(String value) {
        if("PREPARING".equals(value))return "Preparing…";
        if("RECORDING".equals(value))return "Recording · tap the red square to stop";
        if("STOPPING".equals(value))return "Finishing clips…";
        return "Ready";
    }
    private void start(boolean benchmarkMode) {
        if(RecordingService.isCaptureActive()){toast("Wait for the previous recording to finish before starting another.");return;}
        if(busy())return;
        if(tree==null){toast("Choose a USB drive first.");return;}
        int n=selectedCount();if(n==0){toast("Select at least one camera.");return;}
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            if(!awaitingCamera){awaitingCamera=true;pendingBenchmark=benchmarkMode;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);}
            return;
        }
        int[] slots=new int[n];int index=0;for(int i=0;i<6;i++)if(cameras[i].isChecked())slots[index++]=i;
        int[] choices=new int[6];SharedPreferences channels=getSharedPreferences("teyes-channels",MODE_PRIVATE);
        for(int i=0;i<6;i++)choices[i]=channels.getInt("slot-"+i,-2);
        int size=resolution.getSelectedItemPosition(), rate=fps.getSelectedItemPosition(), quality=bitrate.getSelectedItemPosition();
        preferences.edit().putInt("resolution",size).putInt("fps",rate).putInt("bitrate",quality).apply();
        statistics.setText("Waiting for encoded frames…");
        transportStatistics.setText("Waiting for camera input frames…");
        try {
            RecordingService.start(this,new DvrRecorder.Config(tree,slots,choices,SIZES[size][0],SIZES[size][1],rate==0?25:30,BITRATES[quality]*1_000_000,benchmarkMode));
            state="PREPARING";stateLabel.setText(stateTitle(state));details.setText("Starting the background recording service…");
            if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                    &&!preferences.getBoolean("notification-permission-requested",false)) {
                preferences.edit().putBoolean("notification-permission-requested",true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);
            }
        } catch(RuntimeException error){if(!RecordingService.isCaptureActive())state="IDLE";details.setText("Cannot start: "+error.getMessage());}
        updateControls();
    }
    private void stopRecording() {
        if(service!=null)service.stopRecording();else RecordingService.requestStop(this);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==CAMERA_PERMISSION) {
            awaitingCamera=false;
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) {
                startAfterPermission=true;if(resumed){startAfterPermission=false;start(pendingBenchmark);}
            } else details.setText("Camera permission is required for background recording. Tap Record to try again.");
        } else if(request==NOTIFICATION_PERMISSION&&Build.VERSION.SDK_INT>=33
                &&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            toast("Recording can continue without notifications. Return to this screen to stop it.");
    }

    private List<StorageVolume> volumes() {
        List<StorageVolume> result=new ArrayList<>();StorageManager manager=(StorageManager)getSystemService(STORAGE_SERVICE);
        if(manager!=null)for(StorageVolume volume:manager.getStorageVolumes())
            if(volume.isRemovable()&&Environment.MEDIA_MOUNTED.equals(volume.getState())&&volume.getUuid()!=null)result.add(volume);
        return result;
    }
    private void refreshStorage() {
        StringBuilder inventory=new StringBuilder("Mounted removable drives:\n"); boolean found=false;
        try {
            for(StorageVolume volume:volumes()) {
                inventory.append(volume.getDescription(this)).append(" · UUID ").append(volume.getUuid()).append('\n');
                if(volume.getUuid().equalsIgnoreCase(volumeUuid))found=true;
            }
            if(inventory.toString().endsWith("drives:\n"))inventory.append("None\n");
            UsbManager usb=(UsbManager)getSystemService(USB_SERVICE);inventory.append("\nUSB mass-storage device inventory:\n");int devices=0;
            if(usb!=null)for(UsbDevice device:usb.getDeviceList().values()) {
                boolean storage=device.getDeviceClass()==UsbConstants.USB_CLASS_MASS_STORAGE;
                for(int i=0;i<device.getInterfaceCount();i++)storage|=device.getInterface(i).getInterfaceClass()==UsbConstants.USB_CLASS_MASS_STORAGE;
                if(storage){devices++;inventory.append(device.getProductName()==null?"USB storage":device.getProductName()).append(" · ").append(device.getDeviceName()).append('\n');}
            }
            if(devices==0)inventory.append("None reported\n");
        }catch(RuntimeException error){inventory.append("USB inventory unavailable: ").append(error.getMessage()).append('\n');}
        inventory.append("Select by drive label and UUID. Android does not provide a verified physical USB port mapping here.");usbInventory.setText(inventory);
        // A newly opened Activity may overlap the previous instance's final flush.
        // Its saved permission must remain valid until that owner releases USB.
        if(!RecordingService.isCaptureActive()&&tree!=null&&(!found||treeError(tree,volumeUuid)!=null||!hasGrant(tree))) {
            clearTree();details.setText("The previous USB selection is unavailable. Reconnect it and choose the drive again.");
        }
        driveLabel.setText(tree==null?"No USB drive selected":volumeName+" · UUID "+volumeUuid+"\nFolder permission saved");updateControls();
    }
    private boolean hasGrant(Uri uri) {
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions())
            if(permission.getUri().equals(uri)&&permission.isReadPermission()&&permission.isWritePermission())return true;
        return false;
    }
    private void chooseUsb() {
        if(RecordingService.isCaptureActive()){toast("Wait for all recording files to finish before changing USB access.");return;}
        if(busy())return;List<StorageVolume> choices;
        try {choices=volumes();}catch(RuntimeException e){toast("Cannot list USB drives: "+e.getMessage());return;}
        if(choices.isEmpty()){refreshStorage();toast("Insert a mounted USB drive, then refresh the list.");return;}
        String[] labels=new String[choices.size()];for(int i=0;i<labels.length;i++)labels[i]=choices.get(i).getDescription(this)+" · UUID "+choices.get(i).getUuid();
        new AlertDialog.Builder(this).setTitle("Choose the USB drive").setItems(labels,(dialog,which)->{
            StorageVolume volume=choices.get(which);pendingUuid=volume.getUuid();pendingName=volume.getDescription(this);
            Intent intent=Build.VERSION.SDK_INT>=29?volume.createOpenDocumentTreeIntent():new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(GRANTS|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            toast("Select this USB drive's root or its 6camdvr folder, then tap Use this folder.");
            try{startActivityForResult(intent,PICK_USB);}catch(ActivityNotFoundException e){toast("Android folder picker is unavailable.");}
        }).setNegativeButton("Cancel",null).show();
    }
    static String treeError(Uri uri,String expectedUuid) {
        if(uri==null||!"content".equals(uri.getScheme())||!"com.android.externalstorage.documents".equals(uri.getAuthority())||!DocumentsContract.isTreeUri(uri))
            return "Choose a folder on the selected USB drive using Android's storage picker.";
        try {
            String id=DocumentsContract.getTreeDocumentId(uri);int colon=id.indexOf(':');
            if(colon<1||expectedUuid==null||expectedUuid.isEmpty()||!id.substring(0,colon).equalsIgnoreCase(expectedUuid))
                return "That folder belongs to a different drive. Choose the selected drive's UUID.";
            String path=id.substring(colon+1);
            if(!path.isEmpty()&&!"6camdvr".equals(path))return "Choose the USB root or the 6camdvr folder directly inside it.";
            return null;
        }catch(IllegalArgumentException error){return "Android returned an invalid USB folder.";}
    }
    private void clearTree() {
        if(RecordingService.isCaptureActive()){pendingTreeClear=true;return;}
        pendingTreeClear=false;
        if(tree!=null)try{getContentResolver().releasePersistableUriPermission(tree,GRANTS);}catch(RuntimeException ignored){}
        tree=null;volumeUuid="";volumeName="";preferences.edit().remove("usb-tree").remove("volume-uuid").remove("volume-name").apply();
    }
    private void formatUsb() {
        if(RecordingService.isCaptureActive()){toast("Wait for all recording files to finish before opening format settings.");return;}
        if(busy()||tree==null)return;
        new AlertDialog.Builder(this).setTitle("Format USB in Android settings")
                .setMessage("Selected drive: "+volumeName+"\nUUID: "+volumeUuid+"\n\nFormatting erases all files on the drive. Android will open storage settings; select this same drive and use Android's format confirmation. This app does not format the drive itself.\n\nAfter returning, choose the USB folder again.")
                .setPositiveButton("Open Android storage settings",(dialog,which)->{
                    if(RecordingService.isCaptureActive()){toast("Recording is still finishing. Open format settings after it stops.");return;}
                    formatFlow=true;formatLeft=false;
                    try{startActivityForResult(new Intent(Settings.ACTION_MEMORY_CARD_SETTINGS),FORMAT_USB);}
                    catch(ActivityNotFoundException error){
                        try{startActivityForResult(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),FORMAT_USB);}
                        catch(ActivityNotFoundException unavailable){formatFlow=false;toast("Android storage settings are unavailable on this firmware.");}
                    }
                }).setNegativeButton("Cancel",null).show();
    }
    private void finishFormatFlow() {
        if(!formatFlow)return;formatFlow=false;formatLeft=false;clearTree();
        details.setText("Storage settings closed. Formatting was not verified. Choose the USB drive and folder again.");refreshStorage();
    }
    private void viewReport() {
        if(report.isEmpty())return;
        TextView contents=text(report,13);contents.setTextIsSelectable(true);contents.setPadding(dp(14),dp(8),dp(14),dp(8));
        ScrollView scroll=new ScrollView(this);scroll.addView(contents);
        new AlertDialog.Builder(this).setTitle("Recording / benchmark report").setView(scroll).setPositiveButton("Close",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==FORMAT_USB){finishFormatFlow();return;}
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        if(request==PICK_USB) {
            if(RecordingService.isCaptureActive()){toast("Recording is still finishing. Choose the USB folder again after it stops.");return;}
            String invalid=treeError(uri,pendingUuid);if(invalid!=null){toast(invalid);return;}
            boolean mounted=false;
            try{for(StorageVolume volume:volumes())if(volume.getUuid().equalsIgnoreCase(pendingUuid))mounted=true;}
            catch(RuntimeException unavailable){toast("Cannot verify the selected USB drive. Refresh the list and try again.");return;}
            if(!mounted){toast("The selected USB drive was disconnected. Select it again.");return;}
            if((data.getFlags()&GRANTS)!=GRANTS){toast("Read and write folder permission is required.");return;}
            try {
                getContentResolver().takePersistableUriPermission(uri,GRANTS);
                if(!hasGrant(uri))throw new SecurityException("Persistent folder access was not granted.");
                if(tree!=null&&!tree.equals(uri))try{getContentResolver().releasePersistableUriPermission(tree,GRANTS);}catch(RuntimeException ignored){}
                tree=uri;volumeUuid=pendingUuid;volumeName=pendingName;
                preferences.edit().putString("usb-tree",tree.toString()).putString("volume-uuid",volumeUuid).putString("volume-name",volumeName).apply();
                details.setText("USB folder selected. Ready for recording or a benchmark.");refreshStorage();
            }catch(RuntimeException error){toast("Cannot save USB permission: "+error.getMessage());}
        }
    }
    private void leave() { finish(); }
    @Override public void onBackPressed() { if(!AppMenu.dismiss(this))leave(); }
    @Override protected void onStart(){super.onStart();visible=true;attachService();}
    @Override protected void onStop(){visible=false;detachService();super.onStop();}
    @Override protected void onResume() { super.onResume();resumed=true;if(formatFlow&&formatLeft)finishFormatFlow();else refreshStorage();main.removeCallbacks(lifecycleRefresh);main.post(lifecycleRefresh);if(startAfterPermission){startAfterPermission=false;start(pendingBenchmark);} }
    @Override protected void onPause() { resumed=false;AppMenu.dismiss(this);main.removeCallbacks(lifecycleRefresh);if(formatFlow)formatLeft=true;super.onPause(); }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pending-uuid",pendingUuid);out.putString("pending-name",pendingName);out.putBoolean("format-flow",formatFlow);out.putBoolean("format-left",formatLeft);
        out.putBoolean("awaiting-camera",awaitingCamera);out.putBoolean("pending-benchmark",pendingBenchmark);out.putBoolean("start-after-permission",startAfterPermission);super.onSaveInstanceState(out);
    }
    @Override protected void onDestroy() { AppMenu.dismiss(this);main.removeCallbacksAndMessages(null);detachService();super.onDestroy(); }
    private void toast(String message) { Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }
}
