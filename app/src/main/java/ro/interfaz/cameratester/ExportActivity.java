package ro.interfaz.cameratester;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Config and report export entry points shared by every screen's hamburger menu. */
@SuppressLint("SetTextI18n")
public final class ExportActivity extends Activity implements ExportJob.Observer {
    private static final int SAVE_TEXT=50;
    private TextView title,help,status;
    private Spinner selection;
    private Button save,cancel;
    private String mode="reports",pendingMode="reports";
    private int pendingReport;
    private ExportJob job;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(14));root.setBackgroundColor(Color.rgb(15,20,29));setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);title=text("Export",24);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=new Button(this);menu.setId(ro.interfaz.cameratester.R.id.hamburger);menu.setText("☰");menu.setContentDescription("Toggle navigation menu");menu.setOnClickListener(v->AppMenu.show(this,v));header.addView(menu,new LinearLayout.LayoutParams(dp(56),dp(50)));root.addView(header);
        help=text("",16);root.addView(help);
        selection=new Spinner(this);selection.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ExportData.REPORT_NAMES));root.addView(selection);
        save=new Button(this);save.setOnClickListener(v->chooseDestination());root.addView(save);
        cancel=new Button(this);cancel.setText("Cancel export");cancel.setOnClickListener(v->{if(job!=null)job.cancel();});root.addView(cancel);
        ScrollView scroll=new ScrollView(this);status=text(ExportJob.previousMessage(this,ExportJob.TEXT),15);status.setTextIsSelectable(true);scroll.addView(status);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(saved!=null){pendingMode=saved.getString("pending-mode","reports");pendingReport=saved.getInt("pending-report",0);selection.setSelection(saved.getInt("report-selection",0));}
        applyMode(getIntent());attachJob();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);applyMode(intent);}
    private void applyMode(Intent intent) {
        String requested=intent.getStringExtra("export_mode");mode="config".equals(requested)?"config":"reports";
        if("apps".equals(requested)){startActivity(new Intent(this,ExtractionActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();return;}
        boolean config="config".equals(mode);title.setText(config?"Export config":"Export reports");
        help.setText(config?"Save the camera registry, manual source assignments, TEYES channel choices and recording settings as JSON. USB selection is included as a reference; access permission must be granted again on another installation.":"Choose a report, then choose its filename and destination. All available reports include saved camera detection, TEYES preview status and the latest recording / benchmark results. Exporting does not open any cameras.");
        selection.setVisibility(config?View.GONE:View.VISIBLE);save.setText(config?"Choose destination and export config":"Choose destination and export report");render();
    }
    private void chooseDestination() {
        if(job!=null&&job.running())return;pendingMode=mode;pendingReport=selection.getSelectedItemPosition();boolean config="config".equals(mode);
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(config?"application/json":"text/plain")
                .putExtra(Intent.EXTRA_TITLE,"camera-tester-"+(config?"config":"reports")+"-"+System.currentTimeMillis()+(config?".json":".txt"));
        try{startActivityForResult(intent,SAVE_TEXT);}catch(ActivityNotFoundException e){status.setText("Android's document picker is unavailable on this firmware.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=SAVE_TEXT||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{job=ExportJob.start(this,ExportJob.TEXT,pendingMode,data.getData(),pendingReport);job.attach(this);render();}catch(RuntimeException e){status.setText("Cannot start export: "+e.getMessage());}
    }
    private void attachJob(){job=ExportJob.current(ExportJob.TEXT);if(job!=null)job.attach(this);render();}
    private void render(){if(save==null)return;boolean running=job!=null&&job.running();save.setEnabled(!running);selection.setEnabled(!running);cancel.setEnabled(running);if(job!=null)status.setText(job.message());}
    @Override public void onExportChanged(ExportJob changed){if(!isDestroyed()&&job==changed)render();}
    @Override protected void onResume(){super.onResume();if(status!=null)attachJob();}
    @Override protected void onPause(){AppMenu.dismiss(this);if(job!=null)job.detach(this);super.onPause();}
    @Override protected void onDestroy(){AppMenu.dismiss(this);if(job!=null)job.detach(this);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle saved){saved.putString("pending-mode",pendingMode);saved.putInt("pending-report",pendingReport);saved.putInt("report-selection",selection.getSelectedItemPosition());super.onSaveInstanceState(saved);}
    private TextView text(String value,int size){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(Color.WHITE);text.setPadding(0,dp(8),0,dp(8));return text;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
