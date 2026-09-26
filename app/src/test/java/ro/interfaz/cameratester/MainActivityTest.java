package ro.interfaz.cameratester;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class MainActivityTest {
    @Test public void navigationIsInHamburgerWithoutOldShortcutRows(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertEquals("Toggle navigation menu",activity.findViewById(R.id.hamburger).getContentDescription());
            assertNull(find(root,"TEYES cameras · direct preview"));
            assertNull(find(root,"6-camera DVR · recording + benchmark"));
            assertNull(find(root,"Export TEYES APKs + services (ZIP)"));
        }
    }
    @Test public void aboutHasExactlyRequestedText(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            AppMenu.select(controller.get(),AppMenu.ABOUT);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            assertEquals("V 0.4.2 Made by Sorin Botirla (and your AI Friend Chat GPT 6 Astra Ultra)",((TextView)dialog.findViewById(android.R.id.message)).getText().toString());
        }
    }
    @Test public void hamburgerContainsNestedExportsAndTogglesClosed()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();activity.findViewById(R.id.hamburger).performClick();
            View decor=activity.getWindow().getDecorView();
            PopupMenu popup=(PopupMenu)decor.getTag(R.id.navigation_popup);assertNotNull(popup);
            Menu menu=popup.getMenu();assertEquals(5,menu.size());
            String[] labels={"Detect cameras","Show cameras","Record","Export","About"};
            for(int i=0;i<labels.length;i++)assertEquals(labels[i],menu.getItem(i).getTitle().toString());
            SubMenu export=menu.getItem(3).getSubMenu();assertNotNull(export);assertEquals(3,export.size());
            assertEquals("Export config",export.getItem(0).getTitle().toString());
            assertEquals("Export reports",export.getItem(1).getTitle().toString());
            assertEquals("Export all TEYES apps and services",export.getItem(2).getTitle().toString());
            activity.findViewById(R.id.hamburger).performClick();assertNull(decor.getTag(R.id.navigation_popup));
        }
    }
    @Test public void camerasRouteUsesAllSixVendorPreviewAndExportsUseDocumentWorkflow(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();AppMenu.select(activity,AppMenu.CAMERAS);
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(TeyesActivity.class.getName(),intent.getComponent().getClassName());
            assertTrue(intent.getBooleanExtra("show_all",false));
            AppMenu.select(activity,AppMenu.REPORTS);
            intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(ExportActivity.class.getName(),intent.getComponent().getClassName());
            assertEquals("reports",intent.getStringExtra("export_mode"));
            assertNotEquals(0,intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
    }
    @Test public void changingDestinationWhileDiscoveryStopsDiscardsEarlierNavigation()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();
            java.lang.reflect.Field scanning=MainActivity.class.getDeclaredField("scanning");scanning.setAccessible(true);scanning.setBoolean(activity,true);
            AppMenu.select(activity,AppMenu.REPORTS);assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            activity.openDetection();
            java.lang.reflect.Field pending=MainActivity.class.getDeclaredField("pendingNavigation");pending.setAccessible(true);assertNull(pending.get(activity));
            assertNotNull(find(activity.getWindow().getDecorView(),"Stop detection"));
        }
    }
    private View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),text);if(result!=null)return result;}}return null;}
}
