package ro.interfaz.cameratester;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Read-only snapshots: exporting must not change scan state or saved camera assignments. */
final class ExportData {
    static final int ALL_REPORTS=0,DISCOVERY_REPORT=1,TEYES_REPORT=2,RECORDING_REPORT=3;
    static final String[] REPORT_NAMES={"All available reports","Camera detection report","TEYES preview report","Recording / benchmark report"};

    static String config(Context context)throws Exception {
        JSONObject output=new JSONObject().put("schema","ro.interfaz.cameratester.camera-config").put("schemaVersion",1)
                .put("exportedAtUtc",now()).put("app",app(context)).put("device",device());
        JSONObject registry=registry(context);
        output.put("cameraRegistry",registry);
        SharedPreferences channels=context.getSharedPreferences("teyes-channels",Context.MODE_PRIVATE);
        SharedPreferences recording=context.getSharedPreferences("six-camera-dvr",Context.MODE_PRIVATE);
        output.put("preferences",new JSONObject().put("teyes-channels",preferences(channels,false))
                .put("six-camera-dvr",preferences(recording,true)));
        JSONArray slots=new JSONArray();JSONArray savedMappings=registry.optJSONArray("slots");
        for(int i=0;i<Source.SLOTS.length;i++) {
            int choice=channels.getInt("slot-"+i,-2);
            slots.put(new JSONObject().put("index",i).put("name",Source.SLOTS[i])
                    .put("sourceKey",savedMappings==null?"":savedMappings.optString(i,""))
                    .put("teyesChannelChoice",choice).put("choiceMeaning",choice==-2?"auto":choice==-1?"unassigned":"manual channel")
                    .put("selectedForRecording",recording.getBoolean("selected-"+i,true))
                    .put("recordingFilename",RecordingStorage.FILES[i]));
        }
        output.put("cameraSlots",slots);
        int resolution=recording.getInt("resolution",0),fps=recording.getInt("fps",0),bitrate=recording.getInt("bitrate",2);
        int[][] sizes={{1280,720},{960,540},{640,480}};int[] rates={1,2,3,4,6};
        JSONObject profile=new JSONObject().put("resolutionIndex",resolution).put("fpsIndex",fps).put("bitrateIndex",bitrate)
                .put("segmentSeconds",180).put("container","MP4").put("videoCodec","AVC").put("audio",false);
        if(resolution>=0&&resolution<sizes.length)profile.put("width",sizes[resolution][0]).put("height",sizes[resolution][1]);
        if(fps>=0&&fps<=1)profile.put("requestedFramesPerSecond",fps==0?25:30);
        if(bitrate>=0&&bitrate<rates.length)profile.put("bitsPerSecondPerCamera",rates[bitrate]*1_000_000);
        output.put("recordingProfile",profile);
        output.put("storageSelection",new JSONObject().put("treeUriReference",recording.getString("usb-tree",""))
                .put("volumeUuid",recording.getString("volume-uuid",""))
                .put("volumeName",recording.getString("volume-name",""))
                .put("authorizationIncluded",false).put("mustChooseFolderAgainOnAnotherInstallation",true));
        output.put("notes","Preferences and saved source evidence are exported without starting cameras. Auto choices remain auto; the registry retains the last observed TEYES routing/status. USB URI text is a reference, not a transferable read/write grant. Snapshot files and preferences are read independently.");
        return output.toString(2)+"\n";
    }

    static String report(Context context,int kind)throws Exception {
        if(kind<ALL_REPORTS||kind>RECORDING_REPORT)throw new IllegalArgumentException("Unknown report selection.");
        StringBuilder out=new StringBuilder("Camera Tester reports\nExported: ").append(now()).append('\n')
                .append("App: ").append(app(context)).append("\nDevice: ").append(device()).append('\n');
        if(kind==ALL_REPORTS||kind==DISCOVERY_REPORT||kind==TEYES_REPORT) {
            JSONObject saved=registry(context);
            if(kind!=TEYES_REPORT)appendRegistry(out,saved,false);
            if(kind!=DISCOVERY_REPORT)appendRegistry(out,saved,true);
        }
        if(kind==ALL_REPORTS||kind==RECORDING_REPORT) {
            out.append("\n=== RECORDING / BENCHMARK REPORT ===\n");
            try {out.append(RecordingReportStore.read(context)).append('\n');}
            catch(FileNotFoundException e){out.append("No saved recording / benchmark report is available.\n");}
            catch(IOException e){out.append("Saved recording report is unavailable: ").append(e).append('\n');}
        }
        return out.toString();
    }

    private static void appendRegistry(StringBuilder out,JSONObject saved,boolean teyes) {
        out.append(teyes?"\n=== TEYES PREVIEW STATUS AND EVENTS ===\n":"\n=== CAMERA DETECTION AND ASSIGNMENTS ===\n");
        if(!saved.optBoolean("available",true)){out.append(saved.optString("error","No saved camera registry is available.")).append('\n');return;}
        out.append("Saved scan state: ").append(saved.optString("scanState","Not started")).append('\n');
        JSONArray slots=saved.optJSONArray("slots");
        if(!teyes)for(int i=0;i<Source.SLOTS.length;i++)out.append(Source.SLOTS[i]).append(": ").append(slots==null?"Unassigned":slots.optString(i,"Unassigned")).append('\n');
        JSONArray sources=saved.optJSONArray("sources");
        if(sources!=null)for(int i=0;i<sources.length();i++) {
            JSONObject source=sources.optJSONObject(i);if(source==null)continue;
            if(teyes&&!"teyes".equals(source.optString("kind")))continue;
            out.append("\n").append(source.toString()).append('\n');
        }
        JSONArray logs=saved.optJSONArray("logs");boolean found=false;
        if(logs!=null)for(int i=0;i<logs.length();i++) {
            String line=logs.optString(i,"");
            if(teyes&&!line.toLowerCase(Locale.ROOT).contains("teyes"))continue;
            out.append(line).append('\n');found=true;
        }
        if(teyes&&!found)out.append("No saved TEYES preview events are available.\n");
    }

    private static JSONObject registry(Context context)throws JSONException,IOException {
        // Do not instantiate Registry: its constructor can change a Running scan to Interrupted.
        ExtractionBundle.checkCancelled();
        try{return new JSONObject(Registry.readSavedJson(context));}
        catch(FileNotFoundException e){return new JSONObject().put("available",false).put("error","No saved camera registry is available.");}
        catch(IOException|JSONException e){return new JSONObject().put("available",false).put("error","Saved camera registry could not be read: "+e);}
    }
    private static JSONObject preferences(SharedPreferences source,boolean recording)throws JSONException {
        JSONObject out=new JSONObject();Map<String,?> values=new TreeMap<>(source.getAll());
        for(Map.Entry<String,?> entry:values.entrySet()) {
            if(recording&&"usb-tree".equals(entry.getKey()))continue;
            Object value=entry.getValue();
            if(value instanceof Set){List<String> strings=new ArrayList<>();for(Object item:(Set<?>)value)strings.add(String.valueOf(item));Collections.sort(strings);value=new JSONArray(strings);}
            out.put(entry.getKey(),value);
        }
        return out;
    }
    private static JSONObject app(Context context)throws JSONException {
        JSONObject out=new JSONObject().put("package",context.getPackageName());
        try{PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);out.put("versionName",p.versionName).put("versionCode",Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode);}
        catch(android.content.pm.PackageManager.NameNotFoundException e){out.put("versionName","unknown");}
        return out;
    }
    private static JSONObject device()throws JSONException {return new JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("androidRelease",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT);}
    private static String now(){SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.format(new Date());}
    static String readUtf8(File file)throws IOException {
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[32*1024];int count;while((count=input.read(buffer))!=-1){ExtractionBundle.checkCancelled();out.write(buffer,0,count);}return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
