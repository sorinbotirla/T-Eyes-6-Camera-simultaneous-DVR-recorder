package ro.interfaz.cameratester;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.widget.PopupMenu;

/** Shared navigation. Navigating never stops the foreground recording service. */
final class AppMenu {
    static final int DETECT=1, CAMERAS=2, RECORD=3, CONFIG=4, REPORTS=5, APPS=6, ABOUT=7;
    static final int CHANNELS=8, STOP_PREVIEW=9, REFRESH=10;
    static final String ABOUT_TEXT="V 0.4.2 Made by Sorin Botirla (and your AI Friend Chat GPT 6 Astra Ultra)";

    static void show(Activity activity,View anchor){
        if(current(activity)!=null){dismiss(activity);return;}
        PopupMenu popup=new PopupMenu(activity,anchor,Gravity.END);
        populate(popup.getMenu(),activity instanceof TeyesActivity);
        View decor=activity.getWindow().getDecorView();
        decor.setTag(R.id.navigation_popup,popup);
        popup.setOnDismissListener(menu->{if(decor.getTag(R.id.navigation_popup)==menu)decor.setTag(R.id.navigation_popup,null);});
        popup.setOnMenuItemClickListener(item->select(activity,item.getItemId()));
        popup.show();
    }

    static void populate(Menu menu,boolean cameraOptions){
        menu.add(0,DETECT,0,"Detect cameras");
        menu.add(0,CAMERAS,1,"Show cameras");
        menu.add(0,RECORD,2,"Record");
        SubMenu export=menu.addSubMenu(0,0,3,"Export");
        export.add(0,CONFIG,0,"Export config");
        export.add(0,REPORTS,1,"Export reports");
        export.add(0,APPS,2,"Export all TEYES apps and services");
        if(cameraOptions){
            SubMenu cameras=menu.addSubMenu(0,0,4,"Camera options");
            cameras.add(0,CHANNELS,0,"Choose channels");
            cameras.add(0,STOP_PREVIEW,1,"Stop preview");
            cameras.add(0,REFRESH,2,"Refresh status");
        }
        menu.add(0,ABOUT,5,"About");
    }

    static boolean select(Activity activity,int action){
        Intent intent;
        switch(action){
            case DETECT:
                if(activity instanceof MainActivity){((MainActivity)activity).openDetection();return true;}
                intent=new Intent(activity,MainActivity.class).putExtra("screen","detect");break;
            case CAMERAS:
                if(activity instanceof TeyesActivity){((TeyesActivity)activity).showAll();return true;}
                intent=new Intent(activity,TeyesActivity.class).putExtra("show_all",true);break;
            case RECORD:
                if(activity instanceof RecordingActivity)return true;
                intent=new Intent(activity,RecordingActivity.class);break;
            case CONFIG: case REPORTS: case APPS:
                intent=new Intent(activity,ExportActivity.class).putExtra("export_mode",action==CONFIG?"config":action==REPORTS?"reports":"apps");break;
            case ABOUT:
                new AlertDialog.Builder(activity).setMessage(ABOUT_TEXT).setPositiveButton("Close",null).show();return true;
            case CHANNELS: case STOP_PREVIEW: case REFRESH:
                if(activity instanceof TeyesActivity)((TeyesActivity)activity).cameraAction(action);
                return true;
            default:return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(activity instanceof MainActivity)((MainActivity)activity).navigateTo(intent);
        else{
            if(activity instanceof TeyesActivity)((TeyesActivity)activity).beforeNavigation();
            activity.startActivity(intent);
        }
        return true;
    }

    private static PopupMenu current(Activity activity){
        return (PopupMenu)activity.getWindow().getDecorView().getTag(R.id.navigation_popup);
    }
    static boolean dismiss(Activity activity){
        PopupMenu popup=current(activity);if(popup==null)return false;
        activity.getWindow().getDecorView().setTag(R.id.navigation_popup,null);
        popup.dismiss();return true;
    }
    private AppMenu(){}
}
