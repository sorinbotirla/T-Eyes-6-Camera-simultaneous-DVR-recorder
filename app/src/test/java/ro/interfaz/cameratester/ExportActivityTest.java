package ro.interfaz.cameratester;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class ExportActivityTest {
    @Test public void configUsesCreateDocumentWithJsonFilenameAndUserChosenDestination() {
        try(ActivityController<ExportActivity> controller=Robolectric.buildActivity(ExportActivity.class,
                new Intent().putExtra("export_mode","config")).setup()) {
            ExportActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"Export config"));
            assertEquals(View.GONE,spinner(root).getVisibility());
            find(root,"Choose destination and export config").performClick();

            Intent picker=nextPicker(activity);
            assertPicker(picker,"application/json","camera-tester-config-\\d+\\.json");
            assertNull("Destination is chosen in Android's picker, not hard-coded",picker.getData());
            assertNull("Opening the picker must not start an export",ExportJob.current(ExportJob.TEXT));
        }
    }

    @Test public void selectedReportIsRetainedWhilePickerRequestsTextFilename() throws Exception {
        try(ActivityController<ExportActivity> controller=Robolectric.buildActivity(ExportActivity.class,
                new Intent().putExtra("export_mode","reports")).setup()) {
            ExportActivity activity=controller.get();View root=activity.getWindow().getDecorView();Spinner choices=spinner(root);
            assertEquals(ExportData.REPORT_NAMES.length,choices.getCount());
            choices.setSelection(ExportData.RECORDING_REPORT);
            find(root,"Choose destination and export report").performClick();

            assertPicker(nextPicker(activity),"text/plain","camera-tester-reports-\\d+\\.txt");
            assertEquals(ExportData.RECORDING_REPORT,field(activity,"pendingReport"));
            assertEquals("reports",field(activity,"pendingMode"));
            assertNull(ExportJob.current(ExportJob.TEXT));
        }
    }

    @Test public void reusedActivitySwitchesConfigAndReportPickerModes() {
        try(ActivityController<ExportActivity> controller=Robolectric.buildActivity(ExportActivity.class,
                new Intent().putExtra("export_mode","reports")).setup()) {
            ExportActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            spinner(root).setSelection(ExportData.TEYES_REPORT);

            controller.newIntent(new Intent().putExtra("export_mode","config"));
            assertNotNull(find(root,"Export config"));assertEquals(View.GONE,spinner(root).getVisibility());
            find(root,"Choose destination and export config").performClick();
            assertPicker(nextPicker(activity),"application/json","camera-tester-config-\\d+\\.json");

            controller.newIntent(new Intent().putExtra("export_mode","reports"));
            assertNotNull(find(root,"Export reports"));assertEquals(View.VISIBLE,spinner(root).getVisibility());
            assertEquals(ExportData.TEYES_REPORT,spinner(root).getSelectedItemPosition());
            find(root,"Choose destination and export report").performClick();
            assertPicker(nextPicker(activity),"text/plain","camera-tester-reports-\\d+\\.txt");
            assertNull(ExportJob.current(ExportJob.TEXT));
        }
    }

    @Test public void archiveRequestsZipDestinationBeforeAnyCollection() {
        try(ActivityController<ExtractionActivity> controller=Robolectric.buildActivity(ExtractionActivity.class).setup()) {
            ExtractionActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            assertNotNull(find(root,"Export all apps and services"));
            assertNull(find(root,"1 · Collect files"));
            assertNull(ExportJob.current(ExportJob.ARCHIVE));
            find(root,"Choose destination and export ZIP").performClick();

            ShadowActivity.IntentForResult request=Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(request);assertPicker(request.intent,"application/zip","teyes-all-apps-services-\\d+\\.zip");
            assertNull("No worker should collect code before a destination is selected",ExportJob.current(ExportJob.ARCHIVE));
        }
    }

    private static Intent nextPicker(ExportActivity activity) {
        ShadowActivity.IntentForResult request=Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("The Android destination/filename chooser should open",request);return request.intent;
    }
    private static void assertPicker(Intent intent,String mime,String titlePattern) {
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));assertEquals(mime,intent.getType());
        String title=intent.getStringExtra(Intent.EXTRA_TITLE);assertNotNull(title);assertTrue(title,title.matches(titlePattern));
        assertNull("Keep the system document-provider chooser available",intent.getComponent());
    }
    private static Object field(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static Spinner spinner(View view) {
        if(view instanceof Spinner)return (Spinner)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Spinner result=spinner(((ViewGroup)view).getChildAt(i));if(result!=null)return result;}
        return null;
    }
    private static View find(View view,String text) {
        if(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}
        return null;
    }
}
