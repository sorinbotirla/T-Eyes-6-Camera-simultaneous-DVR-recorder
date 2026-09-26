package ro.interfaz.cameratester;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synchronous atomic commits: every source and every verified first frame survives process death. */
final class Registry {
    static final Object DISK_LOCK=new Object();
    private final AtomicFile file;
    private final String appVersion;
    private final LinkedHashMap<String,Source> sources=new LinkedHashMap<>();
    private final String[] slots=new String[6];
    private final ArrayList<String> logs=new ArrayList<>();
    private String scanState="Not started";
    Registry(Context c) {
        String version;
        try { version=c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; }
        catch(android.content.pm.PackageManager.NameNotFoundException e) { version="unknown"; }
        appVersion=version;
        file=new AtomicFile(new File(c.getFilesDir(),"camera-registry.json"));
        Arrays.fill(slots,"");
        synchronized(DISK_LOCK) { try {
            JSONObject root=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
            JSONArray list=root.optJSONArray("sources");
            if(list!=null) for(int i=0;i<list.length();i++){Source s=Source.from(list.getJSONObject(i)); sources.put(s.key,s);}
            JSONArray mapping=root.optJSONArray("slots");
            if(mapping!=null) for(int i=0;i<6;i++) slots[i]=mapping.optString(i,"");
            JSONArray savedLogs=root.optJSONArray("logs");
            if(savedLogs!=null) for(int i=0;i<savedLogs.length();i++) logs.add(savedLogs.optString(i));
            scanState=root.optString("scanState","Not started");
            if(scanState.startsWith("Running")) {scanState="Interrupted — saved discoveries retained"; commit();}
        } catch(FileNotFoundException ignored) { }
        catch(Exception e) {logs.add("Unable to load previous registry: "+e);} }
    }
    /** Reads a stable saved snapshot without constructing Registry or changing scan state. */
    static String readSavedJson(Context context)throws IOException {
        synchronized(DISK_LOCK) {
            File base=new File(context.getFilesDir(),"camera-registry.json");
            File backup=new File(context.getFilesDir(),"camera-registry.json.bak");
            // A leftover legacy backup is the last completed write. Reading it does not
            // restore/delete files as AtomicFile.openRead can do during crash recovery.
            File snapshot=backup.isFile()?backup:base;
            try(InputStream input=new FileInputStream(snapshot);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[32*1024];int count;
                while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }
    synchronized void begin() {
        for(Source s:sources.values()) s.present=false;
        scanState="Running"; log("Detection started. Existing mappings and evidence retained.");
    }
    synchronized void end(String message) {scanState=message; log(message);}
    synchronized String state(){return scanState;}
    synchronized void discovered(Source s) {
        if(s.kind.equals("node-clue")&&Source.isControlNode(s.evidence)){
            Source previous=sources.get("v4l2:"+s.address);
            if(previous!=null){previous.evidence=s.evidence;previous.present=false;previous.lastResult="Control/dummy node — not a video source";}
        }
        Source old=sources.get(s.key);
        if(old!=null){old.evidence=s.evidence;old.present=true;old.lastSeenAt=System.currentTimeMillis();}
        else sources.put(s.key,s);
        commit();
    }
    synchronized void result(String key,String result,boolean frames,int w,int h) {
        Source s=sources.get(key);if(s==null)return;
        s.lastResult=result;
        if(frames){s.verifiedAt=System.currentTimeMillis();s.width=w;s.height=h;s.present=true;}
        commit();
    }
    synchronized void map(int slot,String key) {
        if(slot<0||slot>=slots.length)throw new IllegalArgumentException("Unknown slot");
        if(!key.isEmpty()&&!sources.containsKey(key))throw new IllegalArgumentException("Unknown source");
        // One physical source cannot silently be represented as several different cameras.
        for(int i=0;i<slots.length;i++) if(!key.isEmpty()&&slots[i].equals(key)) slots[i]="";
        slots[slot]=key;commit();
    }
    synchronized String mapping(int slot){return slots[slot];}
    synchronized Source get(String key){return sources.get(key);}
    synchronized List<Source> all(){return new ArrayList<>(sources.values());}
    synchronized void log(String text){logs.add(new Date()+"  "+text);while(logs.size()>500)logs.remove(0);commit();}
    synchronized String report() {
        StringBuilder b=new StringBuilder("Camera Tester "+appVersion+"\n"+scanState+"\n");
        for(int i=0;i<6;i++)b.append(Source.SLOTS[i]).append(": ").append(slots[i].isEmpty()?"Unassigned":slots[i]).append('\n');
        for(Source s:sources.values())b.append("\n=== ").append(s.label).append(" ===\n").append(s.key)
            .append("\nLast result: ").append(s.lastResult).append("\nLast seen: ").append(new Date(s.lastSeenAt))
            .append("\nVerified frames: ").append(s.verifiedAt==0?"never":new Date(s.verifiedAt)).append("\n").append(s.evidence).append('\n');
        b.append("\n=== EVENTS ===\n");for(String log:logs)b.append(log).append('\n');return b.toString();
    }
    private void commit() {
        synchronized(DISK_LOCK) {
        FileOutputStream out=null;
        try {
            JSONArray list=new JSONArray();for(Source s:sources.values())list.put(s.json());
            JSONObject root=new JSONObject().put("version",1).put("scanState",scanState).put("sources",list).put("slots",new JSONArray(Arrays.asList(slots))).put("logs",new JSONArray(logs));
            out=file.startWrite();out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);
        }catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException("Could not save discoveries",e);}
        }
    }
}
