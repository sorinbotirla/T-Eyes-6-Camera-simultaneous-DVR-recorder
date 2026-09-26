package ro.interfaz.cameratester;

import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ExportDataTest {
    private Context context;
    @Before public void reset()throws Exception {
        context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("teyes-channels",0).edit().clear().commit();context.getSharedPreferences("six-camera-dvr",0).edit().clear().commit();
        for(String name:new String[]{"camera-registry.json","camera-registry.json.bak","camera-registry.json.new","last-recording-report.txt","last-recording-report.txt.bak","last-recording-report.txt.new"})new File(context.getFilesDir(),name).delete();
    }
    @Test public void configurationPreservesSourcesAssignmentsChannelsAndRecordingSettingsWithoutGrantTransfer()throws Exception {
        JSONObject registry=new JSONObject().put("version",1).put("scanState","Running")
                .put("sources",new JSONArray().put(new JSONObject().put("key","teyes:5").put("kind","teyes").put("address","5").put("verifiedAt",1234)))
                .put("slots",new JSONArray().put("teyes:5").put("").put("").put("").put("").put(""));
        String original=registry.toString();write("camera-registry.json",original);
        context.getSharedPreferences("teyes-channels",0).edit().putInt("slot-0",5).putInt("slot-1",7).putInt("slot-2",-1).commit();
        context.getSharedPreferences("six-camera-dvr",0).edit().putBoolean("selected-2",false).putInt("resolution",2).putInt("fps",1).putInt("bitrate",4)
                .putString("usb-tree","content://com.android.externalstorage.documents/tree/AAAA-BBBB%3A").putString("volume-uuid","AAAA-BBBB").commit();
        JSONObject config=new JSONObject(ExportData.config(context));
        assertEquals("ro.interfaz.cameratester.camera-config",config.getString("schema"));assertEquals(1,config.getInt("schemaVersion"));
        assertEquals("teyes:5",config.getJSONObject("cameraRegistry").getJSONArray("slots").getString(0));
        assertEquals(5,config.getJSONArray("cameraSlots").getJSONObject(0).getInt("teyesChannelChoice"));assertFalse(config.getJSONArray("cameraSlots").getJSONObject(2).getBoolean("selectedForRecording"));
        assertEquals(640,config.getJSONObject("recordingProfile").getInt("width"));assertEquals(30,config.getJSONObject("recordingProfile").getInt("requestedFramesPerSecond"));assertEquals(6000000,config.getJSONObject("recordingProfile").getInt("bitsPerSecondPerCamera"));
        assertFalse(config.getJSONObject("storageSelection").getBoolean("authorizationIncluded"));assertTrue(config.getJSONObject("storageSelection").getBoolean("mustChooseFolderAgainOnAnotherInstallation"));
        assertFalse(config.getJSONObject("preferences").getJSONObject("six-camera-dvr").has("usb-tree"));
        assertEquals(original,ExportData.readUtf8(new File(context.getFilesDir(),"camera-registry.json")));
    }
    @Test public void reportsIncludeSavedTeyesStatusAndRecordingWithoutChangingRunningRegistry()throws Exception {
        JSONObject registry=new JSONObject().put("scanState","Running").put("sources",new JSONArray().put(new JSONObject().put("kind","teyes").put("key","teyes:7").put("lastResult","Frames received")))
                .put("logs",new JSONArray().put("TEYES status: channel 7 available").put("USB device discovered"));
        String original=registry.toString();write("camera-registry.json",original);write("last-recording-report.txt","Last video: all six finalized");
        String all=ExportData.report(context,ExportData.ALL_REPORTS);assertTrue(all.contains("channel 7 available"));assertTrue(all.contains("all six finalized"));assertTrue(all.contains("USB device discovered"));
        String preview=ExportData.report(context,ExportData.TEYES_REPORT);assertTrue(preview.contains("channel 7 available"));assertFalse(preview.contains("USB device discovered"));assertFalse(preview.contains("all six finalized"));
        assertEquals(original,ExportData.readUtf8(new File(context.getFilesDir(),"camera-registry.json")));
    }
    @Test public void missingReportsAndRegistryAreExplicitAndDefaultsRecoverSixSlots()throws Exception {
        JSONObject config=new JSONObject(ExportData.config(context));assertFalse(config.getJSONObject("cameraRegistry").getBoolean("available"));assertEquals(6,config.getJSONArray("cameraSlots").length());
        assertEquals(-2,config.getJSONArray("cameraSlots").getJSONObject(5).getInt("teyesChannelChoice"));assertTrue(config.getJSONArray("cameraSlots").getJSONObject(5).getBoolean("selectedForRecording"));
        assertTrue(ExportData.report(context,ExportData.RECORDING_REPORT).contains("No saved recording / benchmark report"));
    }
    @Test public void aLegacyRegistryWriteExportsItsCompleteBackupWithoutMutatingWriterFiles()throws Exception {
        write("camera-registry.json","{\"scanState\":");
        write("camera-registry.json.bak","{\"scanState\":\"Running\",\"slots\":[\"teyes:5\"]}");
        JSONObject config=new JSONObject(ExportData.config(context));
        assertEquals("Running",config.getJSONObject("cameraRegistry").getString("scanState"));
        assertEquals("teyes:5",config.getJSONObject("cameraRegistry").getJSONArray("slots").getString(0));
        assertEquals("{\"scanState\":",ExportData.readUtf8(new File(context.getFilesDir(),"camera-registry.json")));
        assertTrue(new File(context.getFilesDir(),"camera-registry.json.bak").isFile());
    }
    @Test public void exportWhileDiscoveryWritesAlwaysReadsCompleteJsonAndNeverEndsTheScan()throws Exception {
        Registry registry=new Registry(context);Source source=new Source("teyes","5","","ADAS Front","Initial evidence");
        registry.discovered(source);registry.map(0,source.key);registry.begin();
        AtomicReference<Throwable> failed=new AtomicReference<>();
        Thread writer=new Thread(()->{try{for(int i=0;i<30;i++){registry.log("TEYES frame "+i);registry.result(source.key,"Frames "+i,true,1280,720);}}catch(Throwable error){failed.set(error);}});
        writer.start();
        for(int i=0;i<30;i++) {
            JSONObject saved=new JSONObject(ExportData.config(context)).getJSONObject("cameraRegistry");
            assertEquals("Running",saved.getString("scanState"));assertEquals(source.key,saved.getJSONArray("slots").getString(0));
            assertEquals(1,saved.getJSONArray("sources").length());
        }
        writer.join(5000);assertFalse(writer.isAlive());assertNull(failed.get());assertEquals("Running",registry.state());
    }
    private void write(String name,String value)throws IOException{Files.write(new File(context.getFilesDir(),name).toPath(),value.getBytes(StandardCharsets.UTF_8));}
}
