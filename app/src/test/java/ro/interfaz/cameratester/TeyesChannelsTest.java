package ro.interfaz.cameratester;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeyesChannelsTest {
    @Test public void recommendationsPreserveSixSlotOrderAndConfiguredSharedFeeds() {
        assertArrayEquals(new int[]{5, 7, 0, 2, 3, 1}, TeyesChannels.recommended(1, 1));
        assertArrayEquals(new int[]{0, 1, 0, 2, 3, 1}, TeyesChannels.recommended(2, 4));
        assertArrayEquals(new int[]{-1, -1, 0, 2, 3, 1}, TeyesChannels.recommended(-1, -1));
        assertArrayEquals(new int[]{-1, 1, 0, 2, 3, 1}, TeyesChannels.recommended(-1, 4));
        assertEquals(TeyesChannels.SLOT_COUNT, Source.SLOTS.length);
    }

    @Test public void reverseFormatHardwareModesFollowVendorAvmConditions() {
        for (int hardware : new int[]{1, 14, 15, 18, 19}) {
            assertEquals(1, TeyesChannels.canonical(7, 0, hardware, 1, 4));
            assertEquals(1, TeyesChannels.canonical(7, 4, hardware, 1, 4));
            assertEquals(7, TeyesChannels.canonical(7, 1, hardware, 1, 4));
            assertEquals(7, TeyesChannels.canonical(7, 3, hardware, 1, 4));
            assertEquals(7, TeyesChannels.canonical(7, 0, hardware, 1, 3));
        }
    }

    @Test public void arFormatHardwareModesRequireAvmFourAndNonzeroArFormat() {
        for (int hardware : new int[]{3, 5}) {
            assertEquals(4, TeyesChannels.canonical(7, 4, hardware, 1, 4));
            assertEquals(4, TeyesChannels.canonical(7, 4, hardware, 2, 1));
            assertEquals(7, TeyesChannels.canonical(7, 4, hardware, 0, 4));
            assertEquals(7, TeyesChannels.canonical(7, 0, hardware, 1, 4));
            assertEquals(7, TeyesChannels.canonical(7, 3, hardware, 1, 4));
        }
    }

    @Test public void fixedModesDoNotRequireUnrelatedSettings() {
        assertEquals(4, TeyesChannels.canonical(7, -1, 8));
        for (int hardware : new int[]{0, 2, 4, 6, 7, 9, 12, 13, 16, 17, 20}) {
            assertEquals(7, TeyesChannels.canonical(7, -1, hardware));
        }
        assertEquals(7, TeyesChannels.canonical(7, 1, 14));
        assertEquals(7, TeyesChannels.canonical(7, 0, 3));
        assertEquals(7, TeyesChannels.canonical(7, -1, 3, 0, -1));
        assertEquals(7, TeyesChannels.canonical(7, -1, 14, -1, 3));
    }

    @Test public void missingRequiredFieldsRemainUnknown() {
        assertEquals(-1, TeyesChannels.canonical(7, 4, -1, 1, 4));
        assertEquals(-1, TeyesChannels.canonical(7, 4, 3));
        assertEquals(-1, TeyesChannels.canonical(7, -1, 3, 1, 4));
        assertEquals(-1, TeyesChannels.canonical(7, 0, 14));
        assertEquals(-1, TeyesChannels.canonical(7, -1, 14, 1, 4));
    }

    @Test public void onlyReverseAliasIsConvertedAndInvalidChannelsAreRejected() {
        for (int channel = 0; channel < 12; channel++) {
            if (channel != 7) assertEquals(channel, TeyesChannels.canonical(channel, 0, 1, 2, 4));
        }
        assertEquals(-1, TeyesChannels.canonical(-1, 0, 1, 2, 4));
        assertEquals(-1, TeyesChannels.canonical(12, 0, 1, 2, 4));
    }

    @Test public void simultaneousPreviewRejectsSharedChannelsAndResolvedAliases() {
        assertTrue(TeyesChannels.conflicts(0, 0, 0, 1, 2, 4));
        assertTrue(TeyesChannels.conflicts(1, 7, 0, 1, 1, 4));
        assertTrue(TeyesChannels.conflicts(7, 4, 4, 3, 1, 4));
        assertFalse(TeyesChannels.conflicts(1, 7, 3, 1, 1, 4));
        assertFalse(TeyesChannels.conflicts(4, 7, 4, 3, 0, 4));
        assertFalse(TeyesChannels.conflicts(2, 3, 0, 1, 1, 4));
    }

    @Test public void unresolvedAliasOnlyBlocksItsPossibleTargets() {
        for (int channel : new int[]{1, 4, 7}) {
            assertTrue(TeyesChannels.conflicts(7, channel, -1, -1, -1, -1));
        }
        for (int channel : new int[]{-1, 0, 2, 3, 5, 6, 8, 9, 10, 11, 12}) {
            assertFalse(TeyesChannels.conflicts(7, channel, -1, -1, -1, -1));
        }
        assertFalse(TeyesChannels.conflicts(7, 4, 0, 14, -1, -1));
        assertFalse(TeyesChannels.conflicts(7, 1, 4, 3, -1, -1));
    }

    @Test public void inspectedProfileRequiresMatchingPackageVersionAndCode() {
        assertTrue(TeyesChannels.knownProfile("com.spd.dvr", 33, "13"));
        assertFalse(TeyesChannels.knownProfile("com.spd.avm", 33, "13"));
        assertFalse(TeyesChannels.knownProfile("com.spd.dvr", 34, "13"));
        assertFalse(TeyesChannels.knownProfile("com.spd.dvr", 33, "14"));
        assertFalse(TeyesChannels.knownProfile(null, 33, null));
    }
}
