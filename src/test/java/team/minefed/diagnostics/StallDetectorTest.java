package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import team.minefed.diagnostics.StallDetector.Action;

class StallDetectorTest {
    private static final long MS = 1_000_000L;

    @Test
    void ignoresServerBeforeFirstTick() {
        StallDetector detector = new StallDetector(1000, 50, 5000, 40000, 100);
        assertTrue(detector.poll(10_000 * MS, 0).isEmpty());
        assertFalse(detector.isStalled());
    }

    @Test
    void normalTicksNeverStall() {
        StallDetector detector = new StallDetector(1000, 50, 5000, 40000, 100);
        for (long t = 1; t < 200; t++) {
            assertTrue(detector.poll(t * 50 * MS + 10 * MS, t * 50 * MS).isEmpty());
        }
    }

    @Test
    void stallLifecycleSamplesDumpsAndEnds() {
        StallDetector detector = new StallDetector(1000, 50, 5000, 40000, 1000);
        long tickEnd = 100 * MS;
        assertTrue(detector.poll(tickEnd + 999 * MS, tickEnd).isEmpty());

        EnumSet<Action> start = detector.poll(tickEnd + 1000 * MS, tickEnd);
        assertEquals(EnumSet.of(Action.STARTED, Action.SAMPLE), start);
        assertEquals(tickEnd, detector.stallTickEnd());

        assertTrue(detector.poll(tickEnd + 1020 * MS, tickEnd).isEmpty(), "sample interval not reached");
        assertEquals(EnumSet.of(Action.SAMPLE), detector.poll(tickEnd + 1050 * MS, tickEnd));

        EnumSet<Action> first = detector.poll(tickEnd + 5000 * MS, tickEnd);
        assertTrue(first.contains(Action.FIRST_DUMP));
        assertFalse(detector.poll(tickEnd + 5100 * MS, tickEnd).contains(Action.FIRST_DUMP), "first dump only once");

        EnumSet<Action> second = detector.poll(tickEnd + 40_000 * MS, tickEnd);
        assertTrue(second.contains(Action.SECOND_DUMP));

        long nextTickEnd = tickEnd + 41_000 * MS;
        EnumSet<Action> end = detector.poll(nextTickEnd + 30 * MS, nextTickEnd);
        assertEquals(EnumSet.of(Action.ENDED), end);
        assertEquals(41_000 * MS, detector.endedDurationNanos(), "measured until the next tick end, not the poll");
        assertFalse(detector.isStalled());
    }

    @Test
    void sampleCountIsCapped() {
        StallDetector detector = new StallDetector(1000, 10, 500_000, 500_000, 3);
        long tickEnd = MS;
        int samples = 0;
        for (long t = 1000; t < 2000; t += 10) {
            if (detector.poll(tickEnd + t * MS, tickEnd).contains(Action.SAMPLE)) samples++;
        }
        assertEquals(3, samples);
    }

    @Test
    void stopTickingEndsStall() {
        StallDetector detector = new StallDetector(1000, 50, 5000, 40000, 100);
        detector.poll(2000 * MS, MS);
        assertTrue(detector.isStalled());
        assertEquals(EnumSet.of(Action.ENDED), detector.poll(3000 * MS, 0));
    }
}
