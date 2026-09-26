package ro.interfaz.cameratester;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingPolicyTest {
    private final int[] auto = {-2,-2,-2,-2,-2,-2};
    private TeyesDvr.Info profile(int rawAr) {
        return new TeyesDvr.Info(new boolean[12],1,19,4,135790691,rawAr,3,rawAr,3);
    }
    @Test public void verifiedProfileResolvesAllSixDistinctChannels() {
        assertArrayEquals(new int[]{5,7,0,2,3,1},RecordingPolicy.channels(new int[]{0,1,2,3,4,5},auto,profile(1)));
    }
    @Test public void preservesSelectedCameraOrder() {
        assertArrayEquals(new int[]{1,5},RecordingPolicy.channels(new int[]{5,0},auto,profile(1)));
    }
    @Test public void rejectsCameraAliasesBeforeAcquisition() {
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.channels(new int[]{0,2},auto,profile(2)));
    }
    @Test public void rejectsRepeatedPositionsAndUnassignedChannels() {
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.channels(new int[]{0,0},auto,profile(1)));
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.channels(new int[]{0},new int[]{-1,-2,-2,-2,-2,-2},profile(1)));
    }
    @Test public void refusesNoCamerasOrUnknownConfiguration() {
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.channels(new int[0],auto,profile(1)));
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.channels(new int[]{0},auto,null));
    }
    @Test public void budgetIncludesAllStreamsAndFinalizationReserve() {
        assertEquals(539_804_432L,RecordingPolicy.requiredFreeBytes(6,3_000_000,180_000));
        assertEquals(47_616_932L,RecordingPolicy.requiredFreeBytes(1,3_000_000,30_000));
        assertThrows(IllegalArgumentException.class,()->RecordingPolicy.requiredFreeBytes(0,3_000_000,180_000));
    }
}
