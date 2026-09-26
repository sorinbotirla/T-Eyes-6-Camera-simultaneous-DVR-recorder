package ro.interfaz.cameratester;

import java.util.HashSet;
import java.util.Set;

final class RecordingPolicy {
    static final long SEGMENT_MS = 180_000L;
    static final long BENCHMARK_MS = 30_000L;
    static final long RESERVE_BYTES = 32L * 1024 * 1024;

    static int[] channels(int[] slots, int[] choices, TeyesDvr.Info info) {
        if (slots == null || slots.length < 1 || slots.length > 6 || choices == null || choices.length != 6)
            throw new IllegalArgumentException("Select between one and six cameras.");
        if (info == null) throw new IllegalArgumentException("TEYES camera configuration is unavailable.");
        int[] auto = TeyesChannels.recommended(info.rawArFormat, info.rawReverseFormat);
        int[] result = new int[slots.length];
        Set<Integer> positions = new HashSet<>(), effective = new HashSet<>();
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            if (slot < 0 || slot >= 6 || !positions.add(slot)) throw new IllegalArgumentException("Camera selection contains an invalid or repeated position.");
            int requested = choices[slot] == -2 ? auto[slot] : choices[slot];
            if (requested < 0 || requested >= 12) throw new IllegalArgumentException(Source.SLOTS[slot] + ": select a valid channel in TEYES Cameras first.");
            int channel = TeyesChannels.canonical(requested, info.avmEnable, info.avmHwSupport, info.arFormat, info.reverseFormat);
            if (channel < 0) throw new IllegalArgumentException(Source.SLOTS[slot] + ": channel routing is unknown.");
            if (!effective.add(channel)) throw new IllegalArgumentException("Selected cameras share TEYES channel " + channel + ". Select only one position for that channel.");
            result[i] = channel;
        }
        return result;
    }

    static long requiredFreeBytes(int count, int bitrate, long durationMs) {
        if (count < 1 || count > 6 || bitrate <= 0 || durationMs <= 0) throw new IllegalArgumentException("Invalid recording size estimate.");
        // 25% margin over the configured aggregate bitrate, plus finalization reserve.
        long video = Math.multiplyExact(Math.multiplyExact((long) count, bitrate), durationMs) / 8000;
        return Math.addExact(Math.addExact(video, video / 4), RESERVE_BYTES);
    }
}
