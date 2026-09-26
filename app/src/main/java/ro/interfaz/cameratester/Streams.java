package ro.interfaz.cameratester;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.Camera;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.hardware.usb.*;
import android.os.*;
import android.util.Size;
import android.view.Surface;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import com.herohan.uvcapp.*;
import com.serenegiant.utils.UVCUtils;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
final class Streams implements AutoCloseable {
    interface Events {void verified(Source source);void failed(Source source,String message);}
    private final Context context;
    private final Registry registry;
    private final List<StreamTile> tiles;
    private final Events events;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Runnable> closers=new ArrayList<>();
    private final Map<String,Runnable> failureClosers=new HashMap<>();
    private final Map<String,Long> vendorAcceptedAt=new HashMap<>();
    private final Set<String> failed=new HashSet<>(),verified=new HashSet<>();
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final AtomicBoolean stopped=new AtomicBoolean();
    private boolean started;
    Streams(Context context,Registry registry,List<StreamTile> tiles,Events events){
        this.context=context;this.registry=registry;this.tiles=tiles;this.events=events;
        for(StreamTile tile:tiles)tile.listen(new StreamTile.Listener(){
            public void ready(){maybeStart();}
            public void lost(){close();}
            public void frame(StreamTile view){
                if(stopped.get()||failed.contains(tile.source.key)||!verified.add(tile.source.key))return;
                boolean vendor=tile.source.kind.equals("teyes");
                registry.result(tile.source.key,"Frames received",true,vendor?0:tile.frameWidth(),vendor?0:tile.frameHeight());events.verified(tile.source);
            }
        });
        maybeStart();
    }
    private void maybeStart(){
        if(started||stopped.get()||tiles.isEmpty())return;
        if(deferForRecording())return;
        for(StreamTile t:tiles)if(!t.ready())return;
        started=true;
        Map<String,List<StreamTile>> cameraGroups=new LinkedHashMap<>();
        List<StreamTile> vendorTiles=new ArrayList<>();
        for(StreamTile tile:tiles){
            if(tile.source.kind.equals("camera2"))cameraGroups.computeIfAbsent(tile.source.address,k->new ArrayList<>()).add(tile);
            else if(tile.source.kind.equals("teyes"))vendorTiles.add(tile);
            else startOther(tile);
        }
        if(!vendorTiles.isEmpty())teyes(vendorTiles);
        for(List<StreamTile> group:cameraGroups.values())new CameraGroup(group).open();
        main.postDelayed(new Runnable(){public void run(){
            if(stopped.get())return;long now=SystemClock.elapsedRealtime();
            for(StreamTile tile:tiles){
                if(failed.contains(tile.source.key))continue;
                Long accepted=vendorAcceptedAt.get(tile.source.key);
                if(tile.source.kind.equals("teyes")&&(accepted==null||(tile.lastFrame()==0&&now-accepted<10000)))continue;
                if(tile.lastFrame()==0||now-tile.lastFrame()>10000)fail(tile,"No frames for 10 s · busy, unavailable or unsupported stream");
            }
            main.postDelayed(this,10000);
        }},10000);
    }
    private boolean deferForRecording(){
        if(!RecordingService.isCaptureActive())return false;
        // The vendor owns one Surface per channel. Never replace a recorder's
        // encoder input with a UI preview, including a late service connection.
        // This is a temporary UI restriction, not evidence of a broken source.
        close();
        for(StreamTile tile:tiles)tile.message("Preview paused while DVR is recording or finishing. Stop recording, then open cameras again.");
        return true;
    }
    private void fail(StreamTile tile,String reason){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->fail(tile,reason));return;}
        if(stopped.get()||!failed.add(tile.source.key))return;
        Runnable release=failureClosers.get(tile.source.key);if(release!=null)release.run();
        tile.message(reason);registry.result(tile.source.key,reason,false,0,0);events.failed(tile.source,reason);
    }
    private boolean allowed(){return context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    private void teyes(List<StreamTile> vendorTiles){
        TeyesDvr client=new TeyesDvr(context);List<Surface> owned=new ArrayList<>();
        closers.add(()->{client.close();for(Surface surface:owned)surface.release();owned.clear();});
        client.connect(new TeyesDvr.Callback(){
            public void connected(TeyesDvr.Info info){
                if(stopped.get()||deferForRecording())return;
                Map<Integer,List<StreamTile>> groups=new LinkedHashMap<>();
                for(StreamTile tile:vendorTiles){
                    if(failed.contains(tile.source.key))continue;
                    try{
                        int requested=Integer.parseInt(tile.source.address);
                        if(requested<0||requested>=12)throw new IllegalArgumentException("Channel must be 0–11");
                        int actual=TeyesChannels.canonical(requested,info.avmEnable,info.avmHwSupport,info.arFormat,info.reverseFormat);
                        if(actual<0&&vendorTiles.size()>1){fail(tile,"Channel 7 routing unknown · test it individually or select a direct channel");continue;}
                        groups.computeIfAbsent(actual<0?requested:actual,k->new ArrayList<>()).add(tile);
                    }catch(Exception e){fail(tile,e.toString());}
                }
                for(Map.Entry<Integer,List<StreamTile>> entry:groups.entrySet()){
                    if(stopped.get()||deferForRecording())return;
                    List<StreamTile> group=entry.getValue();StreamTile primary=group.get(0);
                    for(int i=1;i<group.size();i++)primary.mirrorTo(group.get(i));
                    primary.dimensions(1280,720);primary.texture.getSurfaceTexture().setDefaultBufferSize(1280,720);
                    Surface surface=new Surface(primary.texture.getSurfaceTexture());owned.add(surface);
                    int hash=nextTeyesHash();
                    AtomicBoolean released=new AtomicBoolean();
                    Runnable release=()->{if(released.compareAndSet(false,true))client.stop(entry.getKey(),hash,error->{
                        if(!stopped.get()&&error!=null)registry.log("TEYES stop channel="+entry.getKey()+": "+error);
                    });};
                    for(StreamTile tile:group)failureClosers.put(tile.source.key,release);
                    registry.log("TEYES start channel="+entry.getKey()+" clientHash="+hash+" views="+group.size()+" (1280x720 requested buffer; native resolution unverified)");
                    client.start(entry.getKey(),surface,hash,error->{
                        if(stopped.get())return;
                        if(error!=null){registry.log("TEYES start failed: "+error);for(StreamTile tile:group)fail(tile,error);}
                        else for(StreamTile tile:group){
                            vendorAcceptedAt.put(tile.source.key,SystemClock.elapsedRealtime());
                            if(!verified.contains(tile.source.key))tile.message("TEYES accepted channel "+entry.getKey()+" · waiting for frames"+(group.size()>1?" · shared":""));
                        }
                    });
                }
            }
            public void failed(String error){for(StreamTile tile:vendorTiles)fail(tile,"TEYES · "+error);}
        });
    }
    private static final java.util.concurrent.atomic.AtomicInteger teyesHash=new java.util.concurrent.atomic.AtomicInteger((int)(System.nanoTime()&0x3fffffff)+1);
    private static int nextTeyesHash(){int value=teyesHash.incrementAndGet();return value==0?teyesHash.incrementAndGet():value;}
    static Size choose(Size[] sizes){
        if(sizes==null||sizes.length==0)throw new IllegalArgumentException("No preview sizes");
        return Collections.min(Arrays.asList(sizes),Comparator.comparingDouble(s->Math.abs(Math.log((double)s.getWidth()*s.getHeight()/(640*480)))+Math.abs((double)s.getWidth()/s.getHeight()-4.0/3)));
    }
    private final class CameraGroup {
        final List<StreamTile> group;
        final List<Surface> surfaces=new ArrayList<>();
        CameraDevice device;CameraCaptureSession session;boolean closed;
        CameraGroup(List<StreamTile> group){this.group=group;closers.add(this::close);}
        @SuppressLint("MissingPermission") void open(){
            if(!allowed()){error("Camera permission denied");return;}
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            try{
                for(StreamTile tile:group){
                    CameraCharacteristics c=manager.getCameraCharacteristics(tile.source.address);
                    if(!tile.source.physical.isEmpty())try{c=manager.getCameraCharacteristics(tile.source.physical);}catch(Exception ignored){}
                    StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size size=choose(map==null?null:map.getOutputSizes(SurfaceTexture.class));
                    tile.dimensions(size.getWidth(),size.getHeight());tile.texture.getSurfaceTexture().setDefaultBufferSize(size.getWidth(),size.getHeight());
                    surfaces.add(new Surface(tile.texture.getSurfaceTexture()));
                }
                manager.openCamera(group.get(0).source.address,new CameraDevice.StateCallback(){
                    @Override public void onOpened(CameraDevice camera){
                        if(closed||stopped.get()){camera.close();return;}device=camera;
                        CameraCaptureSession.StateCallback cb=new CameraCaptureSession.StateCallback(){
                            @Override public void onConfigured(CameraCaptureSession configured){
                                if(closed||stopped.get()){configured.close();return;}session=configured;
                                try{CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);for(Surface s:surfaces)request.addTarget(s);configured.setRepeatingRequest(request.build(),null,main);}
                                catch(Exception e){error(e.toString());}
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession failedSession){failedSession.close();if(!closed)error("Output combination rejected by camera HAL");}
                        };
                        try{
                            if(Build.VERSION.SDK_INT>=28){
                                List<OutputConfiguration> outputs=new ArrayList<>();for(int i=0;i<group.size();i++){
                                    OutputConfiguration output=new OutputConfiguration(surfaces.get(i));String physical=group.get(i).source.physical;
                                    if(!physical.isEmpty())output.setPhysicalCameraId(physical);outputs.add(output);
                                }
                                camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,context.getMainExecutor(),cb));
                            }else camera.createCaptureSession(surfaces,cb,main);
                        }catch(Exception e){error(e.toString());}
                    }
                    @Override public void onDisconnected(CameraDevice camera){camera.close();if(!closed)error("Camera disconnected / taken by another client");}
                    @Override public void onError(CameraDevice camera,int code){camera.close();if(!closed)error("Camera2 error "+code+" (1 busy, 2 concurrency limit, 3 disabled, 4 device, 5 service)");}
                },main);
            }catch(Exception e){error(e.toString());}
        }
        void error(String error){for(StreamTile t:group)fail(t,error);close();}
        void close(){closed=true;if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}for(Surface s:surfaces)s.release();surfaces.clear();}
    }
    private void startOther(StreamTile tile){
        switch(tile.source.kind){
            case "legacy":legacy(tile);break;
            case "uvc":uvc(tile);break;
            case "network":network(tile);break;
            case "v4l2":workers.execute(()->V4l2.capture(tile.source.address,stopped,new V4l2.Frames(){public void frame(Bitmap image){draw(tile,image);}public void error(String e){fail(tile,e);}}));break;
            default:fail(tile,"Evidence only — vendor protocol not implemented");
        }
    }
    private void legacy(StreamTile tile){
        if(!allowed()){fail(tile,"Camera permission denied");return;}
        // Legacy Camera.open is synchronous. Keep vendor driver waits away from the UI thread.
        HandlerThread thread=new HandlerThread("legacy-"+tile.source.address);thread.start();Handler handler=new Handler(thread.getLooper());
        final Camera[] holder=new Camera[1];
        closers.add(()->{handler.post(()->{Camera camera=holder[0];holder[0]=null;if(camera!=null){try{camera.stopPreview();}catch(Exception ignored){}camera.release();}thread.quitSafely();});});
        handler.post(()->{
            Camera camera=null;
            try{
                camera=Camera.open(Integer.parseInt(tile.source.address));holder[0]=camera;
                if(stopped.get()){camera.release();holder[0]=null;return;}
                camera.setErrorCallback((error,cam)->fail(tile,"Legacy error="+error));
                Camera.Parameters params=camera.getParameters();List<Camera.Size> list=params.getSupportedPreviewSizes();
                Size[] sizes=new Size[list.size()];for(int i=0;i<list.size();i++)sizes[i]=new Size(list.get(i).width,list.get(i).height);
                Size size=choose(sizes);params.setPreviewSize(size.getWidth(),size.getHeight());camera.setParameters(params);
                main.post(()->{if(!stopped.get())tile.dimensions(size.getWidth(),size.getHeight());});
                camera.setPreviewTexture(tile.texture.getSurfaceTexture());camera.startPreview();
            }catch(Exception e){if(camera!=null){camera.release();holder[0]=null;}fail(tile,e.toString());}
        });
    }
    private void uvc(StreamTile tile){
        UsbManager usb=(UsbManager)context.getSystemService(Context.USB_SERVICE);UsbDevice device=usb.getDeviceList().get(tile.source.address);
        if(device==null){fail(tile,"USB device detached. Run detection again.");return;}
        if(!tile.source.physical.equals(device.getVendorId()+":"+device.getProductId())){fail(tile,"Different USB device at this address. Run detection again.");return;}
        if(!allowed()||!usb.hasPermission(device)){fail(tile,"USB / Camera permission required · select Test to grant it");return;}
        try{
            UVCUtils.init(context.getApplicationContext());CameraHelper helper=new CameraHelper();Surface surface=new Surface(tile.texture.getSurfaceTexture());
            closers.add(()->{helper.removeSurface(surface);helper.closeCamera();helper.release();surface.release();});
            helper.setStateCallback(new ICameraHelper.StateCallback(){
                @Override public void onAttach(UsbDevice d){}
                @Override public void onDeviceOpen(UsbDevice d,boolean first){if(!stopped.get())helper.openCamera();}
                @Override public void onCameraOpen(UsbDevice d){
                    if(stopped.get())return;
                    com.serenegiant.usb.Size size=helper.getPreviewSize();if(size!=null)tile.dimensions(size.width,size.height);
                    helper.startPreview();helper.addSurface(surface,false);
                }
                @Override public void onCameraClose(UsbDevice d){}
                @Override public void onDeviceClose(UsbDevice d){if(!stopped.get())fail(tile,"USB device closed");}
                @Override public void onDetach(UsbDevice d){if(device.equals(d))fail(tile,"USB device detached");}
                @Override public void onCancel(UsbDevice d){fail(tile,"USB permission cancelled");}
                @Override public void onError(UsbDevice d,CameraException e){fail(tile,"UVC: "+e.getMessage());}
            });helper.selectDevice(device);
        }catch(Throwable e){fail(tile,"UVC unavailable: "+e);}
    }
    private void network(StreamTile tile){
        if(tile.source.evidence.toLowerCase(Locale.ROOT).contains("multipart/x-mixed-replace")){mjpeg(tile);return;}
        try{
            ExoPlayer player=new ExoPlayer.Builder(context).build();Surface videoSurface=new Surface(tile.texture.getSurfaceTexture());
            closers.add(()->{player.release();videoSurface.release();});
            player.setVolume(0);player.setVideoSurface(videoSurface);
            player.addListener(new Player.Listener(){
                @Override public void onVideoSizeChanged(VideoSize size){if(!stopped.get()&&size.width>0&&size.height>0)tile.dimensions(size.width,size.height);}
                @Override public void onPlayerError(PlaybackException error){fail(tile,"Video decoder: "+error.getMessage());}
            });player.setMediaItem(MediaItem.fromUri(tile.source.address));player.prepare();player.play();
        }catch(Exception e){fail(tile,e.toString());}
    }
    private void mjpeg(StreamTile tile){workers.execute(()->{
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(tile.source.address).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
            try(InputStream input=new BufferedInputStream(connection.getInputStream())){
                while(!stopped.get()){byte[] bytes=JpegStream.next(input,4*1024*1024);Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(image==null)throw new IOException("Invalid JPEG frame");draw(tile,image);}
            }
        }catch(Exception e){fail(tile,"MJPEG: "+e.getMessage());}finally{if(connection!=null)connection.disconnect();}
    });}
    private void draw(StreamTile tile,Bitmap image){
        // One pending image per producer; no unbounded UI queue on slow head units.
        CountDownLatch drawn=new CountDownLatch(1);
        main.post(()->{try{if(!stopped.get())tile.draw(image);}finally{image.recycle();drawn.countDown();}});
        try{drawn.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    @Override public void close(){if(!stopped.compareAndSet(false,true))return;main.removeCallbacksAndMessages(null);
        for(Runnable closer:closers)try{closer.run();}catch(Exception ignored){}closers.clear();workers.shutdownNow();
        for(StreamTile tile:tiles){tile.clearMirrors();tile.listen(new StreamTile.Listener(){public void ready(){}public void frame(StreamTile t){}public void lost(){}});}
    }
}
