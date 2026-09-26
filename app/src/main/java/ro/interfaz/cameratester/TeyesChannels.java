package ro.interfaz.cameratester;

/**
 * Channel evidence from the extracted CC4 Pro DVR (com.spd.dvr 13 / code 33),
 * ADAS AdasInitManager.cameraInfoInit, and AVM's colour-adjustment preview UI.
 * These are configured preview candidates, not proof of camera position or pixels.
 * Missing settings must be supplied as UNKNOWN, never a guessed Android default.
 */
final class TeyesChannels {
    static final int UNKNOWN = -1;
    static final int CHANNEL_COUNT = 12;
    static final int SLOT_COUNT = 6;
    private static final int REVERSE_POSSIBILITIES = (1 << 1) | (1 << 4) | (1 << 7);

    private TeyesChannels() { }

    /**
     * Reads the raw SETTING_AR_FORMAT / SETTING_REVERSE_CAMERA_FORMAT values.
     * Order: ADAS front/rear, 360 front/left/right/rear, matching Source.SLOTS.
     */
    static int[] recommended(int arFormat, int reverseFormat) {
        return new int[] {
            arFormat < 0 ? UNKNOWN : arFormat == 2 ? 0 : 5,
            reverseFormat < 0 ? UNKNOWN : reverseFormat == 4 ? 1 : 7,
            0, 2, 3, 1
        };
    }

    static boolean knownProfile(String packageName, long versionCode, String versionName) {
        return "com.spd.dvr".equals(packageName) && versionCode == 33 && "13".equals(versionName);
    }

    static boolean valid(int channel) {
        return channel >= 0 && channel < CHANNEL_COUNT;
    }

    /**
     * Resolve the server's convertReverseChn() alias. Only channel 7 is converted
     * by startPreviewByChannel; CSI mode does not establish the alias target.
     * Return UNKNOWN if required runtime fields are missing. Use fields from the
     * same current configuration snapshot; a later format change can redirect 7.
     * arFormat here is ReverseAVMInfo.arFormat, which the service derives and may
     * differ from the raw SETTING_AR_FORMAT consumed by recommended().
     */
    static int canonical(int channel, int avmEnable, int hardware,
                         int arFormat, int reverseFormat) {
        int mask = possibleMask(channel, avmEnable, hardware, arFormat, reverseFormat);
        return mask != 0 && (mask & (mask - 1)) == 0
            ? Integer.numberOfTrailingZeros(mask) : UNKNOWN;
    }

    /** Prefix-only ReverseAVMInfo parsing cannot resolve every hardware mode. */
    static int canonical(int channel, int avmEnable, int hardware) {
        return canonical(channel, avmEnable, hardware, UNKNOWN, UNKNOWN);
    }

    /**
     * Conservative overlap check before concurrent preview. An unresolved reverse
     * alias can conflict with 1, 4 or 7, but cannot conflict with front/side 0/2/3.
     * Invalid or unassigned channels have no preview and therefore no conflict.
     */
    static boolean conflicts(int first, int second, int avmEnable, int hardware,
                             int arFormat, int reverseFormat) {
        return (possibleMask(first, avmEnable, hardware, arFormat, reverseFormat)
            & possibleMask(second, avmEnable, hardware, arFormat, reverseFormat)) != 0;
    }

    private static int possibleMask(int channel, int avmEnable, int hardware,
                                    int arFormat, int reverseFormat) {
        if (!valid(channel)) return 0;
        if (channel != 7) return 1 << channel;
        if (hardware < 0) return REVERSE_POSSIBILITIES;
        if (hardware == 8) return 1 << 4;
        if (hardware == 3 || hardware == 5) {
            if ((avmEnable >= 0 && avmEnable != 4) || arFormat == 0) return 1 << 7;
            if (avmEnable == 4 && arFormat > 0) return 1 << 4;
            return (1 << 4) | (1 << 7);
        }
        if (hardware == 1 || hardware == 14 || hardware == 15
                || hardware == 18 || hardware == 19) {
            if (avmEnable == 1 || avmEnable == 3
                    || (reverseFormat >= 0 && reverseFormat != 4)) return 1 << 7;
            if (avmEnable >= 0 && reverseFormat == 4) return 1 << 1;
            return (1 << 1) | (1 << 7);
        }
        return 1 << 7;
    }
}
