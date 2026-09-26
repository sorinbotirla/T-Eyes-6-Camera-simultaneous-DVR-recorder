package ro.interfaz.cameratester;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Explicit vendor preview workspace. Position labels are suggestions until checked visually. */
@SuppressLint("SetTextI18n")
public final class TeyesActivity extends Activity {
    private Registry registry;
    private android.content.SharedPreferences preferences;
    private LinearLayout root,host;
    private TextView status;
    private TeyesDvr inventory;
    private TeyesDvr.Info info;
    private Streams streams;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final int[] choices=new int[6];
    private int selected=-1,generation;
    private boolean resumed,playing;
    private String serviceStatus="Connecting to TEYES DVR…";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);registry=new Registry(this);
        preferences=getSharedPreferences("teyes-channels",MODE_PRIVATE);
        for(int i=0;i<6;i++)choices[i]=preferences.getInt("slot-"+i,-2);
        if(state!=null)selected=state.getInt("selected",-1);
        playing=(state==null?getIntent().getBooleanExtra("show_all",false):state.getBoolean("playing",false))&&!RecordingService.isCaptureActive();
        chrome();render();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(215,229,234));return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private void chrome(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(10),dp(6),dp(10),dp(6));setContentView(root);
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(text("TEYES Cameras",22),new LinearLayout.LayoutParams(0,dp(48),1));
        Button hamburger=button("☰",()->AppMenu.show(this,findViewById(R.id.hamburger)));
        hamburger.setId(R.id.hamburger);hamburger.setContentDescription("Toggle navigation menu");
        title.addView(hamburger,new LinearLayout.LayoutParams(dp(64),dp(48)));root.addView(title);
        status=text(serviceStatus,12);root.addView(status);
        root.addView(text("Park before preview. TEYES may replace another app's preview. Tap a position for individual view; channel suggestions need visual confirmation.",12));
        host=new LinearLayout(this);host.setOrientation(LinearLayout.VERTICAL);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void readInventory(){
        stop();if(inventory!=null)inventory.close();info=null;
        serviceStatus="Connecting to TEYES DVR…";render();
        final int request=++generation;inventory=new TeyesDvr(this);
        inventory.connect(new TeyesDvr.Callback(){
            public void connected(TeyesDvr.Info value){
                if(!resumed||request!=generation)return;info=value;
                String profile;
                try{
                    PackageInfo pkg=getPackageManager().getPackageInfo("com.spd.dvr",0);
                    long code=Build.VERSION.SDK_INT>=28?pkg.getLongVersionCode():pkg.versionCode;
                    profile=TeyesChannels.knownProfile(pkg.packageName,code,pkg.versionName)?"Known DVR profile":"Different DVR version · compatibility unverified";
                    registry.log("TEYES package "+pkg.packageName+" version="+pkg.versionName+" code="+code);
                }catch(Exception e){profile="DVR package version unavailable";}
                serviceStatus=profile+" · signal flags "+Arrays.toString(value.cameraExist)+" · labels are suggestions";
                String evidence="IDVRService acknowledged; no frames tested. cameraExist="+Arrays.toString(value.cameraExist)
                    +"\nRaw AR_FORMAT="+value.rawArFormat+" REVERSE_CAMERA_FORMAT="+value.rawReverseFormat
                    +"\nServer avmEnable="+value.avmEnable+" hardware="+value.avmHwSupport+" CSI modes="+value.csi0Mode+","+value.csi1Mode
                    +" server AR="+value.arFormat+" reverse="+value.reverseFormat;
                registry.log(evidence);
                // Flags are signals from the vendor, not proof of accessible video or position.
                for(int channel=0;channel<12;channel++){
                    String signal=channel<value.cameraExist.length?Boolean.toString(value.cameraExist[channel]):"unknown";
                    registry.discovered(new Source("teyes",Integer.toString(channel),"","TEYES channel "+channel,
                        evidence+"\nSignal flag for channel "+channel+": "+signal+". Preview is opt-in; indices may be aliases."));
                }
                logMapping();render();
            }
            public void failed(String error){
                if(!resumed||request!=generation)return;info=null;serviceStatus="TEYES: "+error;
                registry.log(serviceStatus);render();
            }
        });
    }
    private int channel(int slot){
        if(choices[slot]!=-2)return choices[slot];
        return info==null?-1:TeyesChannels.recommended(info.rawArFormat,info.rawReverseFormat)[slot];
    }
    private int effective(int requested){
        if(info==null||requested<0)return -1;
        return TeyesChannels.canonical(requested,info.avmEnable,info.avmHwSupport,info.arFormat,info.reverseFormat);
    }
    private void render(){
        stop();if(host==null)return;host.removeAllViews();
        boolean recording=RecordingService.isCaptureActive();
        status.setText(recording?"Recording is active. Open Record to stop it before viewing live cameras.":serviceStatus);
        List<StreamTile> tiles=new ArrayList<>();
        if(selected>=0)addTile(host,selected,tiles,new LinearLayout.LayoutParams(-1,0,1));
        else{
            int columns=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?3:2;
            for(int r=0;r<6/columns;r++){
                LinearLayout row=new LinearLayout(this);host.addView(row,new LinearLayout.LayoutParams(-1,0,1));
                for(int col=0;col<columns;col++){
                    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1);params.setMargins(dp(2),dp(2),dp(2),dp(2));
                    addTile(row,r*columns+col,tiles,params);
                }
            }
        }
        if(resumed&&playing&&!recording&&info!=null&&!tiles.isEmpty()){
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            streams=new Streams(this,registry,tiles,new Streams.Events(){
                public void verified(Source source){registry.log("TEYES frame received "+source.key);}
                public void failed(Source source,String error){registry.log("TEYES preview "+source.key+": "+error);}
            });
        }
    }
    private void addTile(LinearLayout parent,int slot,List<StreamTile> tiles,LinearLayout.LayoutParams params){
        int requested=channel(slot);Source source=requested<0?null:registry.get("teyes:"+requested);
        String label=Source.SLOTS[slot]+(requested<0?"":" · ch "+requested+(choices[slot]==-2?" (suggested)":" (manual)"));
        StreamTile tile=new StreamTile(this,label,source,()->{selected=slot;playing=!RecordingService.isCaptureActive();render();});parent.addView(tile,params);
        if(RecordingService.isCaptureActive()){tile.message("Recording in background · preview paused");return;}
        if(source==null){tile.message(info==null?"Waiting for service status":choices[slot]==-1?"Unassigned · Choose channels":"Auto channel unknown · Choose channels");return;}
        if(!playing)tile.message("Ready to preview · tap this position or choose Show cameras");
        int canonical=effective(requested);
        if(selected<0&&canonical>=0){
            for(int i=0;i<slot;i++)if(effective(channel(i))==canonical){
                tile.message("Shared channel with "+Source.SLOTS[i]+" · same camera image");break;
            }
        }
        tiles.add(tile);
    }
    private void chooseChannels(){
        playing=false;render();ScrollView scroll=new ScrollView(this);LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);scroll.addView(fields);
        Spinner[] spinners=new Spinner[6];String[] options=new String[14];options[0]="Auto (TEYES configuration)";options[1]="Unassigned";
        for(int i=0;i<12;i++)options[i+2]="Channel "+i;
        for(int i=0;i<6;i++){
            fields.addView(text(Source.SLOTS[i],16));Spinner spinner=new Spinner(this);
            spinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));
            spinner.setSelection(choices[i]+2);fields.addView(spinner);spinners[i]=spinner;
        }
        new AlertDialog.Builder(this).setTitle("Camera channel selection").setView(scroll).setPositiveButton("Save",(d,w)->{
            android.content.SharedPreferences.Editor editor=preferences.edit();
            for(int i=0;i<6;i++){choices[i]=spinners[i].getSelectedItemPosition()-2;editor.putInt("slot-"+i,choices[i]);}
            editor.apply();registry.log("TEYES slot choices (-2 auto, -1 none): "+Arrays.toString(choices));logMapping();render();
        }).setNegativeButton("Cancel",null).show();
    }
    private void logMapping(){
        StringBuilder mapping=new StringBuilder("TEYES positions (suggested until checked visually):\n");
        for(int i=0;i<6;i++)mapping.append(Source.SLOTS[i]).append(" requested=").append(channel(i)).append(" effective=").append(effective(channel(i))).append(" selection=").append(choices[i]).append('\n');
        registry.log(mapping.toString());
    }
    void showAll(){selected=-1;playing=!RecordingService.isCaptureActive();render();}
    void beforeNavigation(){playing=false;stop();}
    void cameraAction(int action){
        if(action==AppMenu.CHANNELS)chooseChannels();
        else if(action==AppMenu.STOP_PREVIEW){playing=false;render();}
        else if(action==AppMenu.REFRESH){playing=false;readInventory();}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(intent.getBooleanExtra("show_all",false))showAll();
    }
    private void stop(){if(streams!=null){streams.close();streams=null;}getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onResume(){super.onResume();resumed=true;readInventory();}
    @Override protected void onPause(){AppMenu.dismiss(this);resumed=false;playing=false;generation++;stop();if(inventory!=null){inventory.close();inventory=null;}super.onPause();}
    @Override protected void onDestroy(){AppMenu.dismiss(this);stop();if(inventory!=null)inventory.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);render();}
    @Override public void onBackPressed(){if(AppMenu.dismiss(this))return;if(selected>=0)showAll();else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("selected",selected);out.putBoolean("playing",playing);super.onSaveInstanceState(out);}
}
