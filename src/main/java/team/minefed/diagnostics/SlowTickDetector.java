package team.minefed.diagnostics;

/** Fixed, non-overlapping windows of completed tick work (not the sleep between ticks). Server-thread only. */
final class SlowTickDetector {
    record Window(int count, double averageMs, double maximumMs, boolean slow) {}
    private final int windowSize;
    private final long thresholdNanos;
    private int count;
    private long total, maximum;

    SlowTickDetector(int windowSize, long thresholdMs) {
        this.windowSize = windowSize;
        this.thresholdNanos = thresholdMs * 1_000_000L;
    }

    Window complete(long nanos) {
        total += Math.max(0, nanos);
        maximum = Math.max(maximum, nanos);
        if (++count < windowSize) return null;
        Window window = new Window(count, total / 1e6 / count, maximum / 1e6, total / count >= thresholdNanos);
        count = 0;
        total = maximum = 0;
        return window;
    }
}
