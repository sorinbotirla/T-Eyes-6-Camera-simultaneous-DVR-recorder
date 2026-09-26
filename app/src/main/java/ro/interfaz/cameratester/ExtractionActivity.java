package ro.interfaz.cameratester;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;

@SuppressLint("SetTextI18n")
public final class ExtractionActivity extends Activity implements ExportJob.Observer {
    private static final int SAVE_ARCHIVE=40;
    private TextView status;
    private Button save,cancel;
    private ExportJob job;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(14));root.setBackgroundColor(Color.rgb(15,20,29));setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Export all apps and services",24);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button menu=new Button(this);menu.setId(ro.interfaz.cameratester.R.id.hamburger);menu.setText("☰");menu.setContentDescription("Toggle navigation menu");menu.setOnClickListener(v->AppMenu.show(this,v));header.addView(menu,new LinearLayout.LayoutParams(dp(56),dp(50)));root.addView(header);
        root.addView(text("One ZIP containing every installed app exposed by Android, including system apps, base and split APKs, readable native libraries and system/vendor service code. No package selection or size/count limit.\n\nThe ZIP is written directly to your chosen destination. Android-protected files remain unavailable without root; the ZIP lists every encountered omission and partial file. No private app data or running service memory is copied.",16));
        save=new Button(this);save.setText("Choose destination and export ZIP");save.setOnClickListener(v->chooseDestination());root.addView(save);
        cancel=new Button(this);cancel.setText("Cancel export");cancel.setOnClickListener(v->{if(job!=null&&job.running())new AlertDialog.Builder(this).setMessage("Cancel this export? The destination may contain an incomplete ZIP.").setNegativeButton("Keep exporting",null).setPositiveButton("Cancel export",(dialog,which)->job.cancel()).show();});root.addView(cancel);
        ScrollView scroll=new ScrollView(this);status=text(ExportJob.previousMessage(this,ExportJob.ARCHIVE),15);status.setTextIsSelectable(true);scroll.addView(status);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        attachJob();
    }
    private void chooseDestination() {
        if(job!=null&&job.running())return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,"teyes-all-apps-services-"+System.currentTimeMillis()+".zip");
        try{startActivityForResult(intent,SAVE_ARCHIVE);}catch(ActivityNotFoundException e){status.setText("Android's document picker is unavailable on this firmware.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=SAVE_ARCHIVE||result!=RESULT_OK||data==null||data.getData()==null)return;
        try{job=ExportJob.start(this,ExportJob.ARCHIVE,"apps",data.getData(),ExportData.ALL_REPORTS);job.attach(this);render();}
        catch(RuntimeException e){status.setText("Cannot start export: "+e.getMessage());}
    }
    private void attachJob(){job=ExportJob.current(ExportJob.ARCHIVE);if(job!=null)job.attach(this);render();}
    private void render(){boolean running=job!=null&&job.running();save.setEnabled(!running);cancel.setEnabled(running);if(job!=null)status.setText(job.message());if(running)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override public void onExportChanged(ExportJob changed){if(!isDestroyed()&&job==changed)render();}
    @Override protected void onResume(){super.onResume();if(status!=null)attachJob();}
    @Override protected void onPause(){AppMenu.dismiss(this);if(job!=null)job.detach(this);super.onPause();}
    @Override protected void onDestroy(){AppMenu.dismiss(this);if(job!=null)job.detach(this);super.onDestroy();}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.WHITE);view.setPadding(0,dp(8),0,dp(8));return view;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
