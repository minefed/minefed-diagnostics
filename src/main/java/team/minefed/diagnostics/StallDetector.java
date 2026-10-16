package team.minefed.diagnostics;

import java.util.EnumSet;

/**
 * Decides, from the time of the last finished server tick, when a stall starts, when the server
 * thread should be sampled, when in-progress dumps are due and when the stall is over.
 * Pure logic driven by the watchdog thread so that it can be tested with synthetic clocks.
 */
public final class StallDetector {
    public enum Action { STARTED, SAMPLE, FIRST_DUMP, SECOND_DUMP, ENDED }

    private final long thresholdNanos;
    private final long sampleIntervalNanos;
    private final long firstDumpNanos;
    private final long secondDumpNanos;
    private final int maxSamples;

    private boolean stalled;
    private long stallTickEnd;
    private long lastSample;
    private int sampleCount;
    private boolean firstDumpDone;
    private boolean secondDumpDone;
    private long endedDurationNanos;

    public StallDetector(DiagnosticsConfig config) {
        this(config.stallThresholdMs, config.stallSampleIntervalMs, config.stallFirstDumpMs,
                config.stallSecondDumpMs, config.stallMaxSamples);
    }

    StallDetector(long thresholdMs, long sampleIntervalMs, long firstDumpMs, long secondDumpMs, int maxSamples) {
        this.thresholdNanos = thresholdMs * 1_000_000L;
        this.sampleIntervalNanos = sampleIntervalMs * 1_000_000L;
        this.firstDumpNanos = firstDumpMs * 1_000_000L;
        this.secondDumpNanos = secondDumpMs * 1_000_000L;
        this.maxSamples = maxSamples;
    }

    /**
     * @param lastTickEnd {@link System#nanoTime()} at the end of the last tick, or 0 before the first tick
     */
    public EnumSet<Action> poll(long now, long lastTickEnd) {
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        if (lastTickEnd == 0) {
            if (stalled) end(now, actions);
            return actions;
        }
        if (stalled) {
            if (lastTickEnd != stallTickEnd) {
                end(lastTickEnd, actions);
                return actions;
            }
            long elapsed = now - stallTickEnd;
            if (sampleCount < maxSamples && now - lastSample >= sampleIntervalNanos) sample(now, actions);
            if (!firstDumpDone && elapsed >= firstDumpNanos) {
                firstDumpDone = true;
                actions.add(Action.FIRST_DUMP);
            }
            if (!secondDumpDone && elapsed >= secondDumpNanos) {
                secondDumpDone = true;
                actions.add(Action.SECOND_DUMP);
            }
            return actions;
        }
        if (now - lastTickEnd >= thresholdNanos) {
            stalled = true;
            stallTickEnd = lastTickEnd;
            sampleCount = 0;
            firstDumpDone = false;
            secondDumpDone = false;
            actions.add(Action.STARTED);
            sample(now, actions);
        }
        return actions;
    }

    private void sample(long now, EnumSet<Action> actions) {
        lastSample = now;
        sampleCount++;
        actions.add(Action.SAMPLE);
    }

    /** @param endTime the end of the first tick that finished after the stall, or now when ticking stopped */
    private void end(long endTime, EnumSet<Action> actions) {
        stalled = false;
        endedDurationNanos = endTime - stallTickEnd;
        actions.add(Action.ENDED);
    }

    public boolean isStalled() {
        return stalled;
    }

    /** When the last finished tick before the stall ended; the stall is measured from here. */
    public long stallTickEnd() {
        return stallTickEnd;
    }

    public long endedDurationNanos() {
        return endedDurationNanos;
    }
}
