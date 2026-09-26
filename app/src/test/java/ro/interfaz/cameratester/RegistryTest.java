package ro.interfaz.cameratester;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RegistryTest {
    private Context context;
    @Before public void clear(){context=RuntimeEnvironment.getApplication();for(java.io.File f:context.getFilesDir().listFiles())f.delete();}
    private Source source(){return new Source("camera2","0","","Camera 0","Test metadata");}
    @Test public void discoveryIsCommittedImmediatelyAndDoesNotInferPosition(){
        Registry r=new Registry(context);r.begin();Source s=source();r.discovered(s);
        Registry reopened=new Registry(context);assertEquals(1,reopened.all().size());assertEquals("",reopened.mapping(0));assertEquals(0,reopened.get(s.key).verifiedAt);assertTrue(reopened.state().contains("Interrupted"));
    }
    @Test public void failedReprobePreservesMappingAndHistoricalVerification(){
        Registry r=new Registry(context);Source s=source();r.discovered(s);r.result(s.key,"Frames",true,640,480);r.map(0,s.key);r.begin();r.result(s.key,"Busy",false,0,0);
        Registry reopened=new Registry(context);assertEquals(s.key,reopened.mapping(0));assertTrue(reopened.get(s.key).verifiedAt>0);assertEquals("Busy",reopened.get(s.key).lastResult);assertFalse(reopened.get(s.key).present);
    }
    @Test public void assignmentMovesInsteadOfDuplicatingPhysicalSource(){Registry r=new Registry(context);Source s=source();r.discovered(s);r.map(0,s.key);r.map(5,s.key);assertEquals("",r.mapping(0));assertEquals(s.key,new Registry(context).mapping(5));}
    @Test(expected=IllegalArgumentException.class) public void unknownSourceCannotBeMapped(){new Registry(context).map(0,"missing");}
    @Test public void rediscoveredControlNodeDisablesOldSavedVideoCandidate(){
        Registry r=new Registry(context);Source old=new Source("v4l2","/dev/video1","","V4L2 video1","open errno=13");r.discovered(old);r.map(1,old.key);
        r.discovered(new Source("node-clue","/dev/video1","","video1","sysfs name=cam_sync"));
        Registry reopened=new Registry(context);assertFalse(reopened.get(old.key).playable());assertFalse(reopened.get(old.key).present);
        assertEquals(old.key,reopened.mapping(1));
    }
    @Test public void yuyvDecoderHonorsRowStride(){
        byte[] data={16,(byte)128,(byte)235,(byte)128,99,99,99,99};android.graphics.Bitmap b=V4l2.decodeYuyv(data,2,1,8);assertNotNull(b);assertEquals(0xff000000,b.getPixel(0,0));assertEquals(0xffffffff,b.getPixel(1,0));
    }
}
