package ro.interfaz.cameratester;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameBridgeMathTest {
    @Test public void widescreenPreviewFitsFourThreeWithoutCropping() {
        assertArrayEquals(new int[]{0, 60, 640, 360}, FrameBridgeMath.fitViewport(1920, 1080, 640, 480));
        assertArrayEquals(new int[]{0, 60, 640, 360}, FrameBridgeMath.fitViewport(1280, 720, 640, 480));
        assertArrayEquals(new int[]{0, 0, 1280, 720}, FrameBridgeMath.fitViewport(1920, 1080, 1280, 720));
    }
    @Test public void portraitSourceFitsWithSideBarsAndRejectsInvalidDimensions() {
        assertArrayEquals(new int[]{185, 0, 270, 480}, FrameBridgeMath.fitViewport(1080, 1920, 640, 480));
        assertThrows(IllegalArgumentException.class, () -> FrameBridgeMath.fitViewport(0, 720, 640, 480));
    }
    @Test public void sourceCadenceIsPreservedAcrossDifferentTimestampOrigins() {
        FrameBridgeMath.PresentationClock clock = new FrameBridgeMath.PresentationClock();
        assertEquals(7_000_000_000L, clock.next(1_000_000L, 7_000_000_000L));
        assertEquals(7_040_000_000L, clock.next(41_000_000L, 7_080_000_000L));
        assertEquals(0, clock.corrections);
    }
    @Test public void missingAndRepeatedTimestampsUseMonotonicArrivalTime() {
        FrameBridgeMath.PresentationClock clock = new FrameBridgeMath.PresentationClock();
        long a = clock.next(0, 100_000), b = clock.next(0, 100_000), c = clock.next(20, 140_000), d = clock.next(20, 180_000);
        assertTrue(b > a); assertTrue(c > b); assertTrue(d > c);
        assertEquals(180_000, d); assertTrue(clock.corrections >= 3);
    }
    @Test public void timestampResetDoesNotCreateBackwardPresentationTime() {
        FrameBridgeMath.PresentationClock clock = new FrameBridgeMath.PresentationClock();
        clock.next(10_000_000, 100_000_000);
        assertEquals(140_000_000, clock.next(50_000_000, 140_000_000));
        assertEquals(180_000_000, clock.next(1_000_000, 180_000_000));
        assertEquals(220_000_000, clock.next(41_000_000, 220_000_000));
        assertEquals(1, clock.corrections);
    }
}
