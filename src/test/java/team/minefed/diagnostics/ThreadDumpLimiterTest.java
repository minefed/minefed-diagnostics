package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThreadDumpLimiterTest {
    @Test
    void slowDumpGetsFullCooldownAfterCompletion() {
        AtomicLong clock = new AtomicLong(100);
        ThreadDumpLimiter limiter = new ThreadDumpLimiter(30, clock::get);
        assertTrue(limiter.run(() -> clock.set(150)));
        clock.set(179);
        assertFalse(limiter.run(() -> fail("cooldown")));
        clock.set(180);
        assertTrue(limiter.run(() -> {}));
    }

    @Test
    void concurrentCaptureDoesNotWaitForActiveDump() throws Exception {
        AtomicLong clock = new AtomicLong();
        ThreadDumpLimiter limiter = new ThreadDumpLimiter(30, clock::get);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread worker = new Thread(() -> limiter.run(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.set(1000);
            assertFalse(limiter.run(() -> fail("concurrent dump")));
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
    }

    @Test
    void failedDumpStillEnforcesCooldownAndCanRecover() {
        AtomicLong clock = new AtomicLong();
        ThreadDumpLimiter limiter = new ThreadDumpLimiter(30, clock::get);
        assertThrows(IllegalStateException.class, () -> limiter.run(() -> { throw new IllegalStateException(); }));
        assertFalse(limiter.run(() -> fail("retry storm")));
        clock.set(30);
        assertTrue(limiter.run(() -> {}));
    }
}
