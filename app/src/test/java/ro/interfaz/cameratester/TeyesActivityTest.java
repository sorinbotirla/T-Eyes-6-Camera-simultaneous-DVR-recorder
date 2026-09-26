package ro.interfaz.cameratester;

import android.app.AlertDialog;
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
public class TeyesActivityTest {
    @Test public void opensWithSixPositionsWithoutStartingPreviews()throws Exception{
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class).setup()){
            TeyesActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            for(String slot:Source.SLOTS)assertNotNull(find(root,slot));
            assertNotNull(activity.findViewById(R.id.hamburger));assertNull(find(root,"Show all six"));
            assertFalse((boolean)field(activity,"playing"));assertNull(field(activity,"streams"));
        }
    }
    @Test public void pauseCancelsRequestedPreviewUntilUserStartsAgain()throws Exception{
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class).setup()){
            TeyesActivity activity=controller.get();AppMenu.select(activity,AppMenu.CAMERAS);
            assertTrue((boolean)field(activity,"playing"));controller.pause();
            assertFalse((boolean)field(activity,"playing"));assertNull(field(activity,"streams"));
        }
    }
    @Test public void channelSelectionsAreSavedAndCanBeCleared()throws Exception{
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class).setup()){
            TeyesActivity activity=controller.get();AppMenu.select(activity,AppMenu.CHANNELS);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            java.util.List<Spinner> spinners=new java.util.ArrayList<>();collect(dialog.getWindow().getDecorView(),spinners);assertEquals(6,spinners.size());
            spinners.get(0).setSelection(7);spinners.get(1).setSelection(1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(5,activity.getSharedPreferences("teyes-channels",0).getInt("slot-0",-9));
            assertEquals(-1,activity.getSharedPreferences("teyes-channels",0).getInt("slot-1",-9));
        }
    }
    @Test public void allCameraIntentDoesNotStartPreviewWhileDvrOwnsFeeds()throws Exception{
        java.lang.reflect.Field busyField=DvrRecorder.class.getDeclaredField("PROCESS_BUSY");busyField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean busy=(java.util.concurrent.atomic.AtomicBoolean)busyField.get(null);
        busy.set(true);
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class,
            new android.content.Intent().putExtra("show_all",true)).setup()){
            TeyesActivity activity=controller.get();AppMenu.select(activity,AppMenu.CAMERAS);
            assertFalse((boolean)field(activity,"playing"));assertNull(field(activity,"streams"));
            assertNotNull(find(activity.getWindow().getDecorView(),"Recording is active. Open Record to stop it before viewing live cameras."));
            assertTrue(busy.get());
        }finally{busy.set(false);}
    }
    @Test public void repeatedShowCamerasRequestReturnsFromIndividualViewToAllSix()throws Exception{
        android.content.Intent intent=new android.content.Intent().putExtra("show_all",true);
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class,intent).setup()){
            TeyesActivity activity=controller.get();
            java.lang.reflect.Field selected=TeyesActivity.class.getDeclaredField("selected");selected.setAccessible(true);selected.setInt(activity,3);
            controller.newIntent(new android.content.Intent().putExtra("show_all",true));
            for(String slot:Source.SLOTS)assertNotNull(find(activity.getWindow().getDecorView(),slot));
            assertEquals(-1,field(activity,"selected"));assertTrue((boolean)field(activity,"playing"));
        }
    }
    @Test public void stoppingPreviewSurvivesRecreationWithOriginalShowAllIntent()throws Exception{
        android.content.Intent intent=new android.content.Intent().putExtra("show_all",true);
        android.os.Bundle saved=new android.os.Bundle();
        try(ActivityController<TeyesActivity> controller=Robolectric.buildActivity(TeyesActivity.class,intent).setup()){
            AppMenu.select(controller.get(),AppMenu.STOP_PREVIEW);controller.saveInstanceState(saved);
        }
        try(ActivityController<TeyesActivity> restored=Robolectric.buildActivity(TeyesActivity.class,intent).create(saved).start().resume().visible()){
            assertFalse((boolean)field(restored.get(),"playing"));assertNull(field(restored.get(),"streams"));
        }
    }
    private Object field(Object object,String name)throws Exception{java.lang.reflect.Field f=TeyesActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private View find(View view,String value){if(view instanceof TextView&&((TextView)view).getText().toString().equals(value))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View result=find(group.getChildAt(i),value);if(result!=null)return result;}}return null;}
    private void collect(View view,java.util.List<Spinner> results){if(view instanceof Spinner)results.add((Spinner)view);else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),results);}}
}
