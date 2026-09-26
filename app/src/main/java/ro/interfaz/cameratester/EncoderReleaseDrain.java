package ro.interfaz.cameratester;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Keeps a failed recording's codec consumer moving until its GL producer closes. */
final class EncoderReleaseDrain {
    private EncoderReleaseDrain() {}

    static void await(CountDownLatch releaseAuthorized, Runnable discardOutput) {
        boolean interrupted = false;
        while (releaseAuthorized.getCount() != 0) {
            try {
                if (discardOutput == null) releaseAuthorized.await();
                else {
                    try { discardOutput.run(); }
                    catch (RuntimeException codecUnavailable) {
                        // A stopped/broken codec may no longer dequeue. Keep its
                        // lifetime protected without repeatedly spinning on it.
                        releaseAuthorized.await(25, TimeUnit.MILLISECONDS);
                    }
                }
            } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
