package ro.interfaz.cameratester;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.sun.jna.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standard Linux query/read interface only. No vendor ioctls, privilege escalation or driver reconfiguration. */
final class V4l2 {
    interface LibC extends Library {
        int open(String path,int flags);
        int close(int fd);
        int ioctl(int fd,NativeLong request,Pointer data);
        NativeLong read(int fd,Pointer data,NativeLong count);
    }
    private static LibC libc(){return Native.load("c",LibC.class);}
    private static int query(LibC c,int fd,long request,Memory memory){return c.ioctl(fd,new NativeLong(request),memory);}
    static String describe(String path){
        int fd=-1;
        try{
            LibC c=libc();fd=c.open(path,0x800); // O_RDONLY | O_NONBLOCK
            if(fd<0)return "Open denied/unavailable, errno="+Native.getLastError()+". No root requested.";
            try(Memory cap=new Memory(104)){
                cap.clear();if(query(c,fd,0x80685600L,cap)<0)return "VIDIOC_QUERYCAP failed, errno="+Native.getLastError();
                int flags=cap.getInt(84);if((flags&0x80000000)!=0)flags=cap.getInt(88);
                return "Driver="+boundedString(cap,0,16)+" card="+boundedString(cap,16,32)+" bus="+boundedString(cap,48,32)
                    +"\nCapabilities=0x"+Integer.toHexString(flags)+"\nRead I/O="+((flags&0x01000000)!=0)+" streaming I/O="+((flags&0x04000000)!=0)
                    +"\nPreview supports current single-plane MJPEG/JPEG/YUYV format via read(). mmap/vendor formats require an adapter.";
            }
        }catch(Throwable e){return "V4L2 query unavailable: "+e;}
        finally{if(fd>=0)libc().close(fd);}
    }
    private static String boundedString(Memory memory,int offset,int length){byte[] b=memory.getByteArray(offset,length);int n=0;while(n<b.length&&b[n]!=0)n++;return new String(b,0,n,StandardCharsets.UTF_8);}
    interface Frames {void frame(Bitmap bitmap);void error(String error);}
    static void capture(String path,AtomicBoolean cancelled,Frames frames){
        LibC c=null;int fd=-1;
        try{
            c=libc();fd=c.open(path,0x800);
            if(fd<0)throw new IllegalStateException("V4L2 open denied, errno="+Native.getLastError());
            try(Memory cap=new Memory(104)){
                cap.clear();if(query(c,fd,0x80685600L,cap)<0)throw new IllegalStateException("VIDIOC_QUERYCAP errno="+Native.getLastError());
                int flags=cap.getInt(84);if((flags&0x80000000)!=0)flags=cap.getInt(88);
                if((flags&1)==0||(flags&0x01000000)==0)throw new IllegalStateException("Driver has no single-plane read() capture; mmap/vendor adapter required.");
            }
            int offset=Native.POINTER_SIZE==8?8:4,size=offset+200;
            try(Memory format=new Memory(size)){
                format.clear();format.setInt(0,1); // V4L2_BUF_TYPE_VIDEO_CAPTURE
                if(query(c,fd,0xc0005604L|((long)size<<16),format)<0)throw new IllegalStateException("VIDIOC_G_FMT errno="+Native.getLastError());
                int width=format.getInt(offset),height=format.getInt(offset+4),fourcc=format.getInt(offset+8);
                int stride=format.getInt(offset+16),bytes=format.getInt(offset+20);
                if(width<=0||height<=0||width>3840||height>2160||bytes<=0||bytes>16*1024*1024)throw new IllegalStateException("Unsupported buffer dimensions");
                boolean jpeg=fourcc==0x47504a4d||fourcc==0x4745504a;boolean yuyv=fourcc==0x56595559;
                if(!jpeg&&!yuyv)throw new IllegalStateException("Current V4L2 format unsupported: 0x"+Integer.toHexString(fourcc));
                if(yuyv&&(width%2!=0||stride<width*2||(long)stride*height>bytes))throw new IllegalStateException("Unsupported YUYV layout");
                try(Memory buffer=new Memory(bytes)){
                    long deadline=android.os.SystemClock.elapsedRealtime()+8000;
                    while(!cancelled.get()){
                        long n=c.read(fd,buffer,new NativeLong(bytes)).longValue();
                        if(n<=0){int errno=Native.getLastError();if(n<0&&errno!=11&&errno!=4)throw new IllegalStateException("read errno="+errno);
                            if(android.os.SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("No V4L2 frames for 8 seconds");Thread.sleep(20);continue;}
                        deadline=android.os.SystemClock.elapsedRealtime()+8000;byte[] data=buffer.getByteArray(0,(int)n);
                        Bitmap image=jpeg?BitmapFactory.decodeByteArray(data,0,data.length):decodeYuyv(data,width,height,stride);
                        if(image!=null)frames.frame(image);
                    }
                }
            }
        }catch(Throwable e){if(!cancelled.get())frames.error(e.toString());}
        finally{if(c!=null&&fd>=0)c.close(fd);}
    }
    static Bitmap decodeYuyv(byte[] data,int width,int height,int stride){
        if(data.length<(long)stride*height)return null;
        int[] pixels=new int[width*height];
        for(int row=0;row<height;row++)for(int x=0;x<width;x+=2){int p=row*stride+x*2;int u=(data[p+1]&255)-128,v=(data[p+3]&255)-128;
            pixels[row*width+x]=rgb((data[p]&255)-16,u,v);pixels[row*width+x+1]=rgb((data[p+2]&255)-16,u,v);}
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    private static int rgb(int y,int u,int v){int c=298*y;return 0xff000000|clamp((c+409*v+128)>>8)<<16|clamp((c-100*u-208*v+128)>>8)<<8|clamp((c+516*u+128)>>8);}
    private static int clamp(int x){return Math.max(0,Math.min(255,x));}
}
