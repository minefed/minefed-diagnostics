package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import team.minefed.diagnostics.ChunkSendMonitor.Snapshot;
import team.minefed.diagnostics.ChunkSendMonitor.Verdict;
import team.minefed.diagnostics.ChunkTaskTracker.Key;
import team.minefed.diagnostics.ChunkTaskTracker.Stage;

class ChunkTrackingTest {
    private static final long MS = 1_000_000L;

    @Test
    void tracksInFlightTasksUntilTheyComplete() {
        AtomicLong clock = new AtomicLong(0);
        ChunkTaskTracker tracker = new ChunkTaskTracker(clock::get, 1000);
        CompletableFuture<String> load = new CompletableFuture<>();
        CompletableFuture<String> gen = new CompletableFuture<>();
        Key loadKey = new Key("minecraft:overworld", 1, 2, Stage.LOAD, "");
        tracker.track(loadKey, load, "unloaded"::equals);
        clock.set(500 * MS);
        tracker.track(new Key("minecraft:overworld", 1, 2, Stage.GENERATE, "minecraft:features"), gen, "unloaded"::equals);

        assertEquals(2, tracker.inFlightCount());
        clock.set(3000 * MS);
        assertEquals(loadKey, tracker.oldest(1).get(0).key());
        assertEquals(3000 * MS, tracker.ageNanos(tracker.oldest(1).get(0)));
        assertEquals(2, tracker.olderThan(2000 * MS, 10).size());
        assertEquals(1, tracker.olderThan(2600 * MS, 10).size());

        load.complete("chunk");
        gen.complete("unloaded");
        assertEquals(0, tracker.inFlightCount());
        assertEquals(1, tracker.stats(Stage.LOAD).completed.sum());
        assertEquals(1, tracker.stats(Stage.LOAD).slow.sum());
        assertEquals(3000 * MS, tracker.stats(Stage.LOAD).maxNanos.get());
        assertEquals(1, tracker.stats(Stage.GENERATE).unloaded.sum());
    }

    @Test
    void recordsFailuresAndAlreadyDoneFutures() {
        ChunkTaskTracker tracker = new ChunkTaskTracker(System::nanoTime, 1000);
        tracker.track(new Key("w", 0, 0, Stage.FULL, ""), CompletableFuture.completedFuture("x"), s -> false);
        CompletableFuture<String> failing = new CompletableFuture<>();
        tracker.track(new Key("w", 5, 5, Stage.LOAD, ""), failing, s -> false);
        failing.completeExceptionally(new IllegalStateException("corrupt chunk"));

        assertEquals(1, tracker.stats(Stage.FULL).completed.sum());
        assertEquals(1, tracker.stats(Stage.LOAD).failed.sum());
        assertEquals(0, tracker.inFlightCount());
        assertTrue(tracker.recentFailures().get(0).message().contains("corrupt chunk"));
    }

    @Test
    void duplicateRequestKeepsOldestStart() {
        AtomicLong clock = new AtomicLong(0);
        ChunkTaskTracker tracker = new ChunkTaskTracker(clock::get, 1000);
        Key key = new Key("w", 0, 0, Stage.LOAD, "");
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        tracker.track(key, first, s -> false);
        clock.set(100 * MS);
        tracker.track(key, second, s -> false);
        assertEquals(0, tracker.oldest(1).get(0).startNanos());
        second.complete("a");
        assertEquals(1, tracker.inFlightCount(), "the first request is still pending");
        first.complete("a");
        assertEquals(0, tracker.inFlightCount());
    }

    @Test
    void forgetsUnloadedWorld() {
        ChunkTaskTracker tracker = new ChunkTaskTracker(System::nanoTime, 1000);
        tracker.track(new Key("a", 0, 0, Stage.LOAD, ""), new CompletableFuture<String>(), s -> false);
        tracker.track(new Key("b", 0, 0, Stage.LOAD, ""), new CompletableFuture<String>(), s -> false);
        tracker.forgetWorld("a");
        assertEquals(1, tracker.inFlightCount());
    }

    @Test
    void sendMonitorDetectsLostAcknowledgements() {
        ChunkSendMonitor monitor = new ChunkSendMonitor(10_000, 30_000);
        UUID player = UUID.randomUUID();
        Snapshot blocked = new Snapshot(40, 10, 10, 9f, 0f);
        assertNull(monitor.observe(player, blocked, 0));
        assertNull(monitor.observe(player, blocked, 9_000 * MS));

        ChunkSendMonitor.Event event = monitor.observe(player, blocked, 10_000 * MS);
        assertEquals(Verdict.WAITING_FOR_CLIENT_ACK, event.verdict());
        assertTrue(event.firstReport());
        assertFalse(event.resetSuggested());

        ChunkSendMonitor.Event later = monitor.observe(player, blocked, 31_000 * MS);
        assertFalse(later.firstReport(), "reported once per incident");
        assertTrue(later.resetSuggested());
        monitor.markReset(player, 31_000 * MS);
        assertNull(monitor.observe(player, blocked, 32_000 * MS), "reset restarts the timer");
    }

    @Test
    void sendMonitorDetectsChunksThatNeverBecomeReady() {
        ChunkSendMonitor monitor = new ChunkSendMonitor(10_000, 30_000);
        UUID player = UUID.randomUUID();
        Snapshot waiting = new Snapshot(25, 0, 10, 9f, 9f);
        monitor.observe(player, waiting, 0);
        ChunkSendMonitor.Event event = monitor.observe(player, waiting, 12_000 * MS);
        assertEquals(Verdict.CHUNKS_NOT_READY, event.verdict());
        assertFalse(event.resetSuggested(), "never reset when the client is not the problem");
    }

    @Test
    void sendMonitorTreatsShrinkingQueueAsProgress() {
        ChunkSendMonitor monitor = new ChunkSendMonitor(10_000, 30_000);
        UUID player = UUID.randomUUID();
        monitor.observe(player, new Snapshot(100, 10, 10, 9f, 0f), 0);
        assertNull(monitor.observe(player, new Snapshot(90, 10, 10, 9f, 0f), 11_000 * MS));
        assertNull(monitor.observe(player, new Snapshot(0, 0, 10, 9f, 0f), 30_000 * MS));
        assertNull(monitor.observe(player, new Snapshot(5, 0, 10, 9f, 0f), 50_000 * MS),
                "an empty queue resets the timer even when chunks are added later");
        assertEquals(0, monitor.stuckNanos(UUID.randomUUID(), 0));
    }
}
