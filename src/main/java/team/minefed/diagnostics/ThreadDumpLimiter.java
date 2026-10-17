package team.minefed.diagnostics;

import java.util.function.LongSupplier;

/** One full snapshot at a time, with a cooldown measured from completion, including failures. */
final class ThreadDumpLimiter {
    private final long cooldownNanos;
    private final LongSupplier clock;
    private boolean running, completed;
    private long lastCompleted;

    ThreadDumpLimiter(long cooldownNanos, LongSupplier clock) {
        this.cooldownNanos = cooldownNanos;
        this.clock = clock;
    }

    boolean run(Runnable action) {
        synchronized (this) {
            if (running || (completed && clock.getAsLong() - lastCompleted < cooldownNanos)) return false;
            running = true;
        }
        try {
            action.run();
            return true;
        } finally {
            synchronized (this) {
                lastCompleted = clock.getAsLong();
                completed = true;
                running = false;
            }
        }
    }
}
