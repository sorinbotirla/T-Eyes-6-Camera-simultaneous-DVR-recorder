package ro.interfaz.cameratester;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class ParserTest {
    @Test public void jpegFramesSurviveHeadersAndSequentialReads()throws Exception{
        byte[] first={(byte)255,(byte)216,10,20,(byte)255,(byte)217};
        ByteArrayOutputStream wire=new ByteArrayOutputStream();wire.write("--frame\r\nContent-Type: image/jpeg\r\n\r\n".getBytes());wire.write(first);wire.write("\r\n--frame\r\n".getBytes());wire.write(first);
        InputStream in=new ByteArrayInputStream(wire.toByteArray());assertArrayEquals(first,JpegStream.next(in,1024));assertArrayEquals(first,JpegStream.next(in,1024));
    }
    @Test(expected=EOFException.class) public void jpegTruncationDoesNotBecomeAFrame()throws Exception{JpegStream.next(new ByteArrayInputStream(new byte[]{(byte)255,(byte)216,10}),100);}
    @Test(expected=IOException.class) public void oversizedJpegIsRejected()throws Exception{JpegStream.next(new ByteArrayInputStream(new byte[]{(byte)255,(byte)216,1,2,3,4,5}),4);}
    @Test public void rtspReadsDeclaredBodyWithoutWaitingForDisconnect()throws Exception{
        String sdp="v=0\r\nm=video 0 RTP/AVP 96\r\n";String response="RTSP/1.0 200 OK\r\nContent-Length: "+sdp.length()+"\r\n\r\n"+sdp;
        InputStream in=new ByteArrayInputStream((response+"NEXT").getBytes(StandardCharsets.US_ASCII));assertEquals(response,RtspResponse.read(in));assertEquals('N',in.read());
    }
    @Test(expected=EOFException.class) public void truncatedRtspFails()throws Exception{RtspResponse.read(new ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: 5\r\n\r\nx".getBytes()));}
    @Test(expected=IOException.class) public void largeSdpIsRejected()throws Exception{RtspResponse.read(new ByteArrayInputStream("RTSP/1.0 200 OK\r\nContent-Length: 10000000\r\n\r\n".getBytes()));}
    @Test public void networkAutoDiscoveryStaysOnLoopback(){
        assertTrue(Discovery.isLoopback("rtsp://127.0.0.1:554/live"));assertTrue(Discovery.isLoopback("http://localhost/video"));
        assertFalse(Discovery.isLoopback("http://127.0.0.1.attacker.example/video"));assertFalse(Discovery.isLoopback("http://192.168.1.1/video"));assertFalse(Discovery.isLoopback("not a URL"));
    }
}
