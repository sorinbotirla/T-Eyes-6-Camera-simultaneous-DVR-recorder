package ro.interfaz.cameratester;

import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;

import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RecordingActivityTest {
    private RecordingServiceTest.ServiceScope defaultService;
    @Before public void bindARealServiceBinderWithoutNativeCapture() {
        defaultService=new RecordingServiceTest.ServiceScope();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(RuntimeEnvironment.getApplication(),RecordingService.class),defaultService.get().onBind(new Intent()));
    }
    @After public void clearQueuedStart() throws Exception {if(defaultService!=null)defaultService.close();RecordingServiceTest.resetPendingStart();}
    @Test public void startsWithSixSelectedCamerasAndRequiresUsbBeforeRecording() throws Exception {
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            for(String name:Source.SLOTS)assertTrue(((CheckBox)find(root,name)).isChecked());
            assertNotNull(find(root,"6 cameras selected"));
            assertNotNull(find(root,"No USB drive selected"));
            RecordControlView record=(RecordControlView)field(activity,"record");
            assertFalse(record.isEnabled());assertEquals("Start recording",record.getContentDescription());
            assertFalse(find(root,"Benchmark selected cameras + USB").isEnabled());
            assertFalse(find(root,"Format USB…").isEnabled());
            ((CheckBox)find(root,Source.SLOTS[0])).setChecked(false);
            assertNotNull(find(root,"5 cameras selected"));
        }
    }

    @Test public void recordControlHasSolidRequestedColorsAndDistinctStopState() {
        RecordControlView view=new RecordControlView(RuntimeEnvironment.getApplication());
        assertEquals(Color.WHITE,view.symbolColor());assertFalse(view.showsStop());
        view.setState("RECORDING");assertEquals(Color.RED,view.symbolColor());assertTrue(view.showsStop());
        assertEquals("Stop recording",view.getContentDescription());
        view.setState("STOPPING");assertFalse(view.showsStop());assertEquals("Finishing recording",view.getContentDescription());
        assertTrue(view.getMinimumWidth()>=64);assertTrue(view.getMinimumHeight()>=48);
    }

    @Test public void folderValidationAllowsOnlyChosenVolumeRootOrDirect6camdvr() {
        assertNull(RecordingActivity.treeError(tree("ABCD-1234:"),"ABCD-1234"));
        assertNull(RecordingActivity.treeError(tree("ABCD-1234:6camdvr"),"abcd-1234"));
        assertNotNull(RecordingActivity.treeError(tree("ABCD-1234:Documents"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(tree("ABCD-1234:6camdvr/../private"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(tree("ABCD-1234:6camdvr/current"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(tree("DCBA-9876:"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(tree("primary:"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(Uri.parse("content://unknown.provider/tree/ABCD-1234%3A"),"ABCD-1234"));
        assertNotNull(RecordingActivity.treeError(Uri.parse("file:///storage/ABCD-1234/"),"ABCD-1234"));
    }

    @Test public void preparingAndStoppingDisableConfigurationAndRecordButton() throws Exception {
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();
            for(String state:new String[]{"PREPARING","STOPPING"}) {
                set(activity,"state",state);invoke(activity,"updateControls");
                assertFalse(((RecordControlView)field(activity,"record")).isEnabled());
                for(CheckBox camera:(CheckBox[])field(activity,"cameras"))assertFalse(camera.isEnabled());
                assertFalse(((View)field(activity,"chooseDrive")).isEnabled());
                assertFalse(((View)field(activity,"format")).isEnabled());
            }
            set(activity,"state","RECORDING");invoke(activity,"updateControls");
            assertTrue(((RecordControlView)field(activity,"record")).isEnabled());
            assertTrue(((RecordControlView)field(activity,"record")).showsStop());
            set(activity,"state","IDLE");
        }
    }

    @Test public void formatExplainsSelectedDriveAndUsesAndroidConfirmationFlow() throws Exception {
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();set(activity,"tree",tree("ABCD-1234:"));
            set(activity,"volumeUuid","ABCD-1234");set(activity,"volumeName","Test USB");
            invoke(activity,"formatUsb");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            String message=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();
            assertTrue(message.contains("Test USB"));assertTrue(message.contains("ABCD-1234"));
            assertTrue(message.contains("erases all files"));assertTrue(message.contains("app does not format"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Android storage settings should be opened",intent);
            assertEquals(Settings.ACTION_MEMORY_CARD_SETTINGS,intent.getAction());
            invoke(activity,"finishFormatFlow");
            assertNull(field(activity,"tree"));
            assertTrue(((TextView)field(activity,"details")).getText().toString().contains("not verified"));
        }
    }

    @Test public void mainEntryOpensRecorderWithoutStartingCapture() {
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();AppMenu.select(activity,AppMenu.RECORD);
            Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(RecordingActivity.class.getName(),intent.getComponent().getClassName());
        }
    }

    @Test public void leavingAndRecreatingScreenKeepsServiceRecordingAndRestoresStopButton() throws Exception {
        RecordingServiceTest.grantCamera();
        try(RecordingServiceTest.ServiceScope serviceController=new RecordingServiceTest.ServiceScope()) {
            RecordingServiceTest.TestService service=serviceController.get();RecordingServiceTest.start(service,false);
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                    new ComponentName(RuntimeEnvironment.getApplication(),RecordingService.class),service.onBind(new Intent()));
            ActivityController<RecordingActivity> first=Robolectric.buildActivity(RecordingActivity.class).setup();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            RecordControlView button=(RecordControlView)field(first.get(),"record");assertTrue(button.isEnabled());assertTrue(button.showsStop());
            first.get().onBackPressed();assertTrue(first.get().isFinishing());
            first.pause().stop().destroy();assertEquals(0,service.fake.stops);assertEquals(0,service.fake.closes);
            assertTrue(service.snapshot().busy);
            service.fake.listener.onTransportStats("Still recording while the screen is closed.");
            try(ActivityController<RecordingActivity> second=Robolectric.buildActivity(RecordingActivity.class).setup()) {
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                RecordingActivity activity=second.get();RecordControlView restored=(RecordControlView)field(activity,"record");
                assertTrue(restored.isEnabled());assertTrue(restored.showsStop());
                assertEquals("Still recording while the screen is closed.",((TextView)field(activity,"transportStatistics")).getText().toString());
                CheckBox[] selected=(CheckBox[])field(activity,"cameras");assertTrue(selected[0].isChecked());assertFalse(selected[1].isChecked());assertTrue(selected[2].isChecked());assertTrue(selected[5].isChecked());
                restored.performClick();assertEquals(1,service.fake.stops);assertEquals("STOPPING",field(activity,"state"));
                service.fake.finish("Files saved.");assertEquals("IDLE",field(activity,"state"));
            }
        }
    }

    @Test public void missingCameraPermissionIsRequestedBeforeForegroundServiceStart() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();assertConfigurationBindingOnly(activity);set(activity,"tree",tree("ABCD-1234:"));invoke(activity,"updateControls");
            ((View)field(activity,"record")).performClick();
            assertArrayEquals(new String[]{Manifest.permission.CAMERA},Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
            activity.onRequestPermissionsResult(63,new String[]{Manifest.permission.CAMERA},new int[]{-1});
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
            assertTrue(((TextView)field(activity,"details")).getText().toString().contains("Camera permission is required"));
        }
    }

    @Test @Config(sdk=33) public void optionalNotificationPermissionDoesNotBlockRequestedRecording() throws Exception {
        RecordingServiceTest.grantCamera();Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();assertConfigurationBindingOnly(activity);set(activity,"tree",tree("ABCD-1234:"));invoke(activity,"updateControls");
            ((View)field(activity,"record")).performClick();Intent request=Shadows.shadowOf(activity).getNextStartedService();
            assertNotNull(request);assertEquals(RecordingService.ACTION_START,request.getAction());
            assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS},Shadows.shadowOf(activity).getLastRequestedPermission().requestedPermissions);
            activity.onRequestPermissionsResult(64,new String[]{Manifest.permission.POST_NOTIFICATIONS},new int[]{-1});
            assertEquals("PREPARING",field(activity,"state"));assertNull(Shadows.shadowOf(activity).getNextStartedService());
        }
    }

    @Test public void anotherRecorderFinishingLocksStorageWithoutRevokingItsGrant() throws Exception {
        Field processField=DvrRecorder.class.getDeclaredField("PROCESS_BUSY");processField.setAccessible(true);
        AtomicBoolean processBusy=(AtomicBoolean)processField.get(null);boolean previous=processBusy.getAndSet(true);
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();Uri retained=tree("ABCD-1234:");
            set(activity,"tree",retained);set(activity,"volumeUuid","ABCD-1234");set(activity,"volumeName","Test USB");
            invoke(activity,"refreshStorage");
            assertEquals(retained,field(activity,"tree"));
            assertFalse(((View)field(activity,"chooseDrive")).isEnabled());
            assertFalse(((View)field(activity,"format")).isEnabled());
            assertFalse(((View)field(activity,"record")).isEnabled());
            invoke(activity,"clearTree");
            assertEquals("Do not revoke while a previous writer owns USB",retained,field(activity,"tree"));
            processBusy.set(false);invoke(activity,"clearTree");assertNull(field(activity,"tree"));
        }finally{processBusy.set(previous);}
    }

    @Test public void savedBenchmarkReportCanBeReadAndSelectedOnTheHeadUnit() throws Exception {
        try(ActivityController<RecordingActivity> controller=Robolectric.buildActivity(RecordingActivity.class).setup()) {
            RecordingActivity activity=controller.get();String saved="USB write: 24.5 MiB/s\nEncoder FPS: 25\nDiagnosis: measured write latency increased.";
            set(activity,"report",saved);invoke(activity,"updateControls");
            View button=find(activity.getWindow().getDecorView(),"View recording / benchmark report");
            assertTrue(button.isEnabled());button.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            TextView displayed=(TextView)find(dialog.getWindow().getDecorView(),saved);
            assertNotNull(displayed);assertTrue(displayed.isTextSelectable());
        }
    }

    private void assertConfigurationBindingOnly(RecordingActivity activity) {
        // Robolectric includes the BIND_AUTO_CREATE intent in its service queue.
        Intent binding=Shadows.shadowOf(activity).getNextStartedService();
        assertNotNull(binding);assertEquals(RecordingService.class.getName(),binding.getComponent().getClassName());
        assertNull(binding.getAction());assertEquals(0,defaultService.get().fake.starts);
        assertNull(Shadows.shadowOf(activity).getNextStartedService());
    }
    private static Uri tree(String id) {return DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents",id);}
    private static Object field(Object object,String name) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void set(Object object,String name,Object value) throws Exception {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static void invoke(Object object,String name) throws Exception {Method method=object.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(object);}
    private static View find(View view,String text) {
        if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View match=find(group.getChildAt(i),text);if(match!=null)return match;}}
        return null;
    }
}
