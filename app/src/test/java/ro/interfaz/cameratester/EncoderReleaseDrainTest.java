package ro.interfaz.cameratester;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class EncoderReleaseDrainTest {
    @Test(timeout = 2000) public void producerCanCloseAfterPendingEncodedOutputIsDiscarded() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queued = new AtomicInteger(3), discarded = new AtomicInteger();
        EncoderReleaseDrain.await(release, () -> {
            discarded.incrementAndGet();
            // Models a producer blocked until the encoded output queue drains.
            // Only then can bridge cleanup authorize codec/Surface release.
            if (queued.decrementAndGet() == 0) release.countDown();
        });
        assertEquals(3, discarded.get());
        assertEquals(0, queued.get());
    }

    @Test(timeout = 2000) public void temporaryCodecFailureDoesNotSkipReleaseAuthorization() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        EncoderReleaseDrain.await(release, () -> {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 2) throw new IllegalStateException("Codec temporarily unavailable");
            release.countDown();
        });
        assertEquals(3, attempts.get());
        assertEquals(0, release.getCount());
    }

    @Test public void authorizedCleanupDoesNotDrainAnotherBuffer() {
        AtomicInteger discarded = new AtomicInteger();
        EncoderReleaseDrain.await(new CountDownLatch(0), discarded::incrementAndGet);
        assertEquals(0, discarded.get());
    }
}
