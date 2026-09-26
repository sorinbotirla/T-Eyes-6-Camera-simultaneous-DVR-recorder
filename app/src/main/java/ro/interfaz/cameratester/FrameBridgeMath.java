package ro.interfaz.cameratester;

/** Geometry and timestamp policy shared by the GL bridge and local tests. */
final class FrameBridgeMath {
    private FrameBridgeMath() {}

    static int[] fitViewport(int sourceWidth, int sourceHeight, int outWidth, int outHeight) {
        if (sourceWidth < 1 || sourceHeight < 1 || outWidth < 1 || outHeight < 1)
            throw new IllegalArgumentException("Frame dimensions must be positive.");
        double scale = Math.min(outWidth / (double) sourceWidth, outHeight / (double) sourceHeight);
        int width = Math.max(1, Math.min(outWidth, (int) Math.round(sourceWidth * scale)));
        int height = Math.max(1, Math.min(outHeight, (int) Math.round(sourceHeight * scale)));
        return new int[]{(outWidth - width) / 2, (outHeight - height) / 2, width, height};
    }

    static final class PresentationClock {
        private long sourceBase, presentationBase, previousSource, previousPresentation;
        private boolean started, sourceStarted;
        long corrections;

        long next(long sourceNs, long arrivalNs) {
            long arrival = Math.max(1, arrivalNs);
            long candidate = arrival;
            if (sourceNs > 0) {
                if (!sourceStarted || sourceNs <= previousSource) {
                    if (sourceStarted) corrections++;
                    sourceBase = sourceNs; presentationBase = arrival; sourceStarted = true;
                }
                candidate = presentationBase + Math.max(0, sourceNs - sourceBase);
                previousSource = sourceNs;
            } else {
                corrections++;
                sourceStarted = false;
            }
            if (started && candidate <= previousPresentation) {
                candidate = Math.max(arrival, previousPresentation + 1000);
                corrections++;
            }
            started = true; previousPresentation = candidate;
            return candidate;
        }
    }
}
