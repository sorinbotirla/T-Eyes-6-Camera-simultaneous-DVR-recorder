package ro.interfaz.cameratester;

import java.io.*;

/** Bounded MJPEG JPEG-marker parser; survives multipart headers and arbitrary network chunk boundaries. */
final class JpegStream {
    static byte[] next(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream frame=new ByteArrayOutputStream();boolean started=false;int previous=-1,n,skipped=0;
        while((n=input.read())!=-1){
            if(!started){if(previous==0xff&&n==0xd8){started=true;frame.write(0xff);frame.write(0xd8);}else if(++skipped>maximum)throw new IOException("No JPEG marker within limit");}
            else{frame.write(n);if(frame.size()>maximum)throw new IOException("JPEG exceeds limit");if(previous==0xff&&n==0xd9)return frame.toByteArray();}
            previous=n;
        }
        throw new EOFException("MJPEG ended without a complete frame");
    }
}
