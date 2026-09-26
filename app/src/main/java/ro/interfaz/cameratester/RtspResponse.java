package ro.interfaz.cameratester;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class RtspResponse {
    /** Read one RTSP response by Content-Length; servers normally keep the socket open. */
    static String read(InputStream input)throws IOException{
        ByteArrayOutputStream header=new ByteArrayOutputStream();int n,tail=0;
        while((n=input.read())!=-1){header.write(n);tail=(tail<<8)|n;if(tail==0x0d0a0d0a)break;if(header.size()>8192)throw new IOException("RTSP headers too large");}
        if(tail!=0x0d0a0d0a)throw new EOFException("Incomplete RTSP header");
        String text=header.toString(StandardCharsets.US_ASCII.name());int length=0;
        for(String line:text.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("content-length:")){
            try{length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());}catch(NumberFormatException e){throw new IOException("Invalid RTSP length",e);}
        }
        if(length<0||length>65536)throw new IOException("RTSP body too large");
        byte[] body=new byte[length];int total=0;while(total<length){n=input.read(body,total,length-total);if(n<0)throw new EOFException("Truncated SDP");total+=n;}
        return text+new String(body,StandardCharsets.US_ASCII);
    }
}
