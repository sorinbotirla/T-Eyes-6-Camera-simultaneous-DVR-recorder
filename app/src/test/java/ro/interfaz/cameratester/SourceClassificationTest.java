package ro.interfaz.cameratester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SourceClassificationTest {
    @Test public void recognizesKnownControlAndDummyNamesAcrossFormatting(){
        for(String name:new String[]{"cam-req-mgr", "CAM_REQ_MGR\n", "cam_sync", "Cam-Sync", "Dummy video device", "dummy_video_device", "  Dummy   Video  Device\n"})
            assertTrue(name,Source.isControlNode(name));
    }
    @Test public void unrelatedNamesRemainCandidates(){
        for(String name:new String[]{"USB Camera", "TEYES AHD capture", "ais_camera", "cam_sync_capture", "cam-req-mgr-stream", "Unknown vendor video", "Unavailable: permission denied", ""})
            assertFalse(name,Source.isControlNode(name));
        assertFalse(Source.isControlNode(null));
        assertTrue(new Source("v4l2","/dev/video1","","V4L2 · video1","Unknown vendor video").playable());
    }
    @Test public void restoredControlSourcesCannotBePlayedEvenAfterVerification()throws Exception{
        for(String evidence:new String[]{"Sysfs name: cam_sync", "Driver=cam-req-mgr card=control", "card=Dummy video device\nCapabilities=0x1"}){
            Source source=new Source("v4l2","/dev/video1","","V4L2 · video1",evidence);
            source.verifiedAt=123L;
            assertFalse(Source.from(source.json()).playable());
        }
        assertFalse(new Source("v4l2","/dev/video1","","video1 · cam_sync","").playable());
    }
    @Test public void vendorTransportIsPlayableButUnverified(){
        Source source=new Source("teyes","front","","TEYES front","Service discovered");
        assertTrue(source.playable());assertEquals(0L,source.verifiedAt);
        assertFalse(new Source("node-clue","/dev/video1","","cam_sync","").playable());
    }
}
