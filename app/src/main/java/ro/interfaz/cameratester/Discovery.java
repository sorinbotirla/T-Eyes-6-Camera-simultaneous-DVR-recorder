package ro.interfaz.cameratester;

import android.content.*;
import android.content.pm.*;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.*;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.zip.*;

@SuppressWarnings("deprecation")
final class Discovery implements Runnable {
    interface Listener {void progress(String text);void source(Source source);void finished();}
    private final Context c;
    private final Registry registry;
    private final Listener listener;
    final AtomicBoolean cancelled=new AtomicBoolean();
    private final Set<String> endpoints=new LinkedHashSet<>();
    Discovery(Context c,Registry registry,Listener listener){this.c=c.getApplicationContext();this.registry=registry;this.listener=listener;}
    private void stage(String text){if(cancelled.get())throw new java.util.concurrent.CancellationException();listener.progress(text);registry.log(text);}
    private void found(Source s){if(cancelled.get())return;registry.discovered(s);listener.source(s);}
    @Override public void run(){
        try{
            registry.begin();registry.log(Build.MANUFACTURER+" / "+Build.MODEL+" / "+Build.DISPLAY+" / Android "+Build.VERSION.RELEASE);
            stage("1/6 · Android Camera2 and physical cameras");camera2();
            stage("2/6 · Legacy camera API");legacy();
            stage("3/6 · USB video devices");usb();
            stage("4/6 · V4L2, sysfs and system interfaces");nodes();
            stage("5/6 · Vendor packages, components and APK clues");packages();
            stage("6/6 · Local HTTP / RTSP video endpoints");network();
            registry.end("Discovery complete — testing accessible sources");
        }catch(java.util.concurrent.CancellationException e){registry.end("Detection cancelled — discoveries retained");}
        catch(Exception e){registry.end("Detection stopped: "+e);}
        finally{listener.finished();}
    }
    private void camera2(){
        CameraManager manager=(CameraManager)c.getSystemService(Context.CAMERA_SERVICE);
        try{
            for(String id:manager.getCameraIdList()){
                if(cancelled.get())return;
                try{
                    CameraCharacteristics cc=manager.getCameraCharacteristics(id);
                    found(new Source("camera2",id,"","Camera2 · "+id,metadata(cc)));
                    if(Build.VERSION.SDK_INT>=28)for(String p:cc.getPhysicalCameraIds()){
                        String evidence="Physical camera of logical ID "+id+". Requires physical output routing.\n";
                        try{evidence+=metadata(manager.getCameraCharacteristics(p));}catch(Exception e){evidence+="Metadata unavailable: "+e;}
                        found(new Source("camera2",id,p,"Camera2 · "+id+" / physical "+p,evidence));
                    }
                }catch(Exception e){registry.log("Camera2 "+id+": "+e);}
            }
            if(Build.VERSION.SDK_INT>=30)registry.log("Declared concurrent sets: "+manager.getConcurrentCameraIds());
        }catch(Exception e){registry.log("Camera2: "+e);}
    }
    private String metadata(CameraCharacteristics cc){
        StringBuilder b=new StringBuilder("Hardware level: "+cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        b.append("\nLens facing: ").append(cc.get(CameraCharacteristics.LENS_FACING)).append(" (not vehicle mounting position)");
        b.append("\nOrientation: ").append(cc.get(CameraCharacteristics.SENSOR_ORIENTATION));
        b.append("\nCapabilities: ").append(Arrays.toString(cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)));
        b.append("\nAE FPS: ").append(Arrays.toString(cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));
        StreamConfigurationMap map=cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if(map!=null){b.append("\nPreview sizes: ").append(Arrays.toString(map.getOutputSizes(SurfaceTexture.class)));
            for(int f:map.getOutputFormats())b.append("\nFormat ").append(f).append(": ").append(Arrays.toString(map.getOutputSizes(f)));}
        // Key names only: OEM metadata is evidence, not an inferred protocol.
        for(CameraCharacteristics.Key<?> key:cc.getKeys())if(!key.getName().startsWith("android."))b.append("\nVendor key: ").append(key.getName());
        return b.toString();
    }
    private void legacy(){try{for(int i=0;i<Camera.getNumberOfCameras();i++){
        Camera.CameraInfo info=new Camera.CameraInfo();Camera.getCameraInfo(i,info);
        found(new Source("legacy",Integer.toString(i),"","Legacy · "+i,"Facing="+info.facing+" orientation="+info.orientation+". May alias a Camera2 camera."));
    }}catch(Exception e){registry.log("Legacy: "+e);}}
    private void usb(){try{
        UsbManager manager=(UsbManager)c.getSystemService(Context.USB_SERVICE);
        for(UsbDevice d:manager.getDeviceList().values()){
            boolean video=d.getDeviceClass()==UsbConstants.USB_CLASS_VIDEO;
            StringBuilder details=new StringBuilder("VID="+d.getVendorId()+" PID="+d.getProductId()+" product="+d.getProductName()+" manufacturer="+d.getManufacturerName());
            for(int i=0;i<d.getInterfaceCount();i++){
                UsbInterface it=d.getInterface(i);video|=it.getInterfaceClass()==UsbConstants.USB_CLASS_VIDEO;
                details.append("\nInterface ").append(i).append(" class=").append(it.getInterfaceClass()).append(" subclass=").append(it.getInterfaceSubclass());
                for(int e=0;e<it.getEndpointCount();e++){UsbEndpoint ep=it.getEndpoint(e);details.append(" endpoint=").append(ep.getAddress()).append(" type=").append(ep.getType()).append(" maxPacket=").append(ep.getMaxPacketSize());}
            }
            found(new Source(video?"uvc":"usb-clue",d.getDeviceName(),d.getVendorId()+":"+d.getProductId(),(video?"USB UVC · ":"USB device · ")+d.getProductName(),details.toString()));
        }
    }catch(Exception e){registry.log("USB: "+e);}}
    private void nodes(){
        File[] nodes=new File("/dev").listFiles();
        if(nodes==null)registry.log("/dev cannot be enumerated by this app.");
        else for(File node:nodes){
            if(cancelled.get())return;
            if(node.getName().matches("video[0-9]+")){
                String name=readFile(new File("/sys/class/video4linux/"+node.getName()+"/name"),2048).trim();
                if(cancelled.get())return;
                String evidence="Sysfs name: "+name;
                if(Source.isControlNode(name)){
                    found(new Source("node-clue",node.getAbsolutePath(),"",node.getName()+" · "+name,evidence+"\nKnown control/dummy node; not opened for video capture."));
                }else{
                    evidence+="\n"+V4l2.describe(node.getAbsolutePath());
                    found(new Source("v4l2",node.getAbsolutePath(),"","V4L2 · "+node.getName(),evidence));
                }
            }else if(node.getName().matches("(v4l-subdev|media)[0-9]+"))found(new Source("node-clue",node.getAbsolutePath(),"",node.getName(),"Control/media node. Not a standalone decoded stream. readable="+node.canRead()));
        }
        File[] sys=new File("/sys/class/video4linux").listFiles();
        if(sys!=null)for(File f:sys){if(cancelled.get())return;registry.log(f+" name="+readFile(new File(f,"name"),2048));}
        for(String path:new String[]{"/proc/net/unix","/proc/net/tcp","/proc/net/tcp6"})registry.log(path+"\n"+readFile(new File(path),24576));
        command("/system/bin/getprop");command("/system/bin/service","list");
        command("/system/bin/dumpsys","media.camera");command("/system/bin/lshal");
    }
    private void command(String...args){
        if(cancelled.get())return;
        File tmp=null;Process process=null;
        try{
            tmp=File.createTempFile("probe",".txt",c.getCacheDir());
            process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(tmp).start();
            boolean done=process.waitFor(3,TimeUnit.SECONDS);if(!done)process.destroyForcibly();
            String output=readFile(tmp,65536);
            if(args[0].endsWith("getprop")){
                StringBuilder relevant=new StringBuilder();for(String line:output.split("\n"))if(relevant(line)||line.contains("ro.board.platform")||line.contains("ro.hardware"))relevant.append(line).append('\n');output=relevant.toString();
            }
            registry.log(String.join(" ",args)+" / "+(done?"exit="+process.exitValue():"timeout")+"\n"+output);
        }catch(Exception e){registry.log(String.join(" ",args)+": "+e);}
        finally{if(process!=null)process.destroy();if(tmp!=null)tmp.delete();}
    }
    static boolean relevant(String text){return text.toLowerCase(Locale.ROOT).matches(".*(teyes|syu|fyt|adas|dvr|avm|camera|vision|360|ahd|surround|v4l|video|launcher).* ".trim());}
    static String readFile(File f,int max){try(InputStream in=new FileInputStream(f)){return new String(readBounded(in,max),StandardCharsets.UTF_8);}catch(Exception e){return "Unavailable: "+e.getMessage();}}
    static byte[] readBounded(InputStream in,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
        while(out.size()<max&&(n=in.read(b,0,Math.min(b.length,max-out.size())))!=-1)out.write(b,0,n);return out.toByteArray();
    }
    private void packages(){
        try{
            PackageManager pm=c.getPackageManager();int examined=0;
            for(PackageInfo p:pm.getInstalledPackages(PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_PERMISSIONS|PackageManager.GET_META_DATA)){
                if(cancelled.get())return;
                if(!relevant(p.packageName)||p.packageName.startsWith("ro.interfaz."))continue;
                StringBuilder evidence=new StringBuilder("Version: "+p.versionName+"\nPermissions: "+Arrays.toString(p.requestedPermissions));
                components(evidence,p.activities);components(evidence,p.services);components(evidence,p.providers);components(evidence,p.receivers);
                if(p.applicationInfo!=null){evidence.append("\nNative library dir: ").append(p.applicationInfo.nativeLibraryDir);
                    if(examined++<24){
                        scanApk(p.applicationInfo.sourceDir,evidence);
                        if(p.applicationInfo.splitSourceDirs!=null)for(String split:p.applicationInfo.splitSourceDirs){if(cancelled.get())return;scanApk(split,evidence);}
                    }else evidence.append("\nAPK inspection limit reached (24 packages)");
                }
                found(new Source("vendor-clue",p.packageName,"","Vendor package · "+p.packageName,evidence.toString()));
            }
        }catch(Exception e){registry.log("Package scan: "+e);}
    }
    private void components(StringBuilder b,ComponentInfo[] list){if(list==null)return;for(ComponentInfo i:list){
        String permission=i instanceof ServiceInfo?((ServiceInfo)i).permission:i instanceof ActivityInfo?((ActivityInfo)i).permission:i instanceof ProviderInfo?((ProviderInfo)i).readPermission:null;
        b.append("\n").append(i.getClass().getSimpleName()).append(' ').append(i.name).append(" exported=").append(i.exported).append(" permission=").append(permission);
        if(i instanceof ProviderInfo)b.append(" authority=").append(((ProviderInfo)i).authority);
    }}
    private static final Pattern URL=Pattern.compile("(?:rtsp|https?)://[a-zA-Z0-9_.:\\[\\]/?=&%+~@-]{3,240}");
    private void scanApk(String path,StringBuilder evidence){
        evidence.append("\nAPK: ").append(path);int budget=12*1024*1024;int clues=0;
        try(ZipFile zip=new ZipFile(path)){
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()&&budget>0&&clues<100&&!cancelled.get()){
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(!(name.endsWith(".dex")||name.endsWith(".so")||name.startsWith("assets/")))continue;
                byte[] data;try(InputStream in=zip.getInputStream(entry)){data=readBounded(in,Math.min(budget,4*1024*1024));}budget-=data.length;
                String binary=new String(data,StandardCharsets.ISO_8859_1);
                Matcher matcher=URL.matcher(binary);
                while(matcher.find()&&clues<100){String url=matcher.group();if(url.startsWith("rtsp")||relevant(url)||isLoopback(url)){
                    evidence.append("\nURL clue [").append(name).append("]: ").append(url);clues++;
                    if(isLoopback(url)&&endpoints.size()<32)endpoints.add(url);
                }}
                Matcher strings=Pattern.compile("[A-Za-z0-9_.$/;:-]{12,180}").matcher(binary);
                while(strings.find()&&clues<100){String value=strings.group();if(relevant(value)&&(value.contains("Service")||value.contains("native")||value.contains("/dev/")||value.contains("socket")||value.contains("IAvm")||value.contains("ICamera"))){evidence.append("\nAPI clue: ").append(value);clues++;}}
            }
            if(budget<=0||clues>=100)evidence.append("\nBounded inspection; remaining APK bytes not inspected.");
        }catch(Exception e){evidence.append("\nAPK inaccessible: ").append(e.getMessage());}
    }
    static boolean isLoopback(String url){try{String h=new URI(url).getHost();return "127.0.0.1".equals(h)||"localhost".equalsIgnoreCase(h)||"[::1]".equals(h)||"::1".equals(h);}catch(Exception e){return false;}}
    private void network(){
        for(String url:new ArrayList<>(endpoints)){if(cancelled.get())return;probeUrl(url);}
        for(int port:new int[]{554,8554,8080,8081,8090,8888}){
            if(cancelled.get())return;
            try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",port),250);}catch(IOException closed){continue;}
            registry.log("Loopback port open: "+port+" (not proof of video)");
            boolean rtsp=port==554||port==8554;
            for(String path:rtsp?new String[]{"/","/live","/stream"}:new String[]{"/","/video","/stream","/mjpeg"}){
                if(cancelled.get())return;probeUrl((rtsp?"rtsp":"http")+"://127.0.0.1:"+port+path);
            }
        }
    }
    private void probeUrl(String url){
        if(!isLoopback(url))return;
        try{
            if(url.startsWith("rtsp://")){
                URI uri=new URI(url);try(Socket s=new Socket()){
                    s.connect(new InetSocketAddress(uri.getHost(),uri.getPort()<0?554:uri.getPort()),500);s.setSoTimeout(700);
                    s.getOutputStream().write(("DESCRIBE "+url+" RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    String reply=RtspResponse.read(s.getInputStream());
                    if(reply.startsWith("RTSP/1.0 200")&&reply.contains("m=video"))found(new Source("network",url,"","RTSP · "+url,"Video SDP discovered\n"+reply));
                }
            }else{
                HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
                try{
                    conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(500);conn.setReadTimeout(700);conn.connect();
                    String type=String.valueOf(conn.getContentType()).toLowerCase(Locale.ROOT);
                    if(conn.getResponseCode()==200&&(type.contains("multipart/x-mixed-replace")||type.startsWith("video/")||type.contains("mpegurl")))
                        found(new Source("network",url,"","HTTP video · "+url,"Content-Type: "+type));
                }finally{conn.disconnect();}
            }
        }catch(Exception e){registry.log("Endpoint probe "+url+": "+e.getClass().getSimpleName());}
    }
}
