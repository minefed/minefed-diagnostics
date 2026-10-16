package team.minefed.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Follows every chunk load, generation step and full-chunk conversion from the moment its future is
 * created until it completes. A chunk that never finishes stays in {@link #inFlight} and is reported as
 * a pipeline stall with its age, which is exactly the state in which players see chunks stop loading.
 */
public final class ChunkTaskTracker {
    public enum Stage {
        /** Reading and deserialising the chunk from region files (or creating an empty proto chunk). */
        LOAD,
        /** One world generation status step, e.g. features or light. */
        GENERATE,
        /** Promoting the finished proto chunk to a level chunk on the server thread. */
        FULL
    }

    public record Key(String world, int x, int z, Stage stage, String status) {
        @Override
        public String toString() {
            return world + " [" + x + ", " + z + "] " + stage + (status.isEmpty() ? "" : "(" + status + ")");
        }
    }

    public record Entry(Key key, long startNanos) {}

    public record Failure(long timeMillis, Key key, String message) {}

    public static final class StageStats {
        public final LongAdder started = new LongAdder();
        public final LongAdder completed = new LongAdder();
        public final LongAdder unloaded = new LongAdder();
        public final LongAdder failed = new LongAdder();
        public final LongAdder slow = new LongAdder();
        public final AtomicLong totalNanos = new AtomicLong();
        public final AtomicLong maxNanos = new AtomicLong();
    }

    private static final int MAX_FAILURES = 32;

    private final ConcurrentHashMap<Key, Entry> inFlight = new ConcurrentHashMap<>();
    private final Map<Stage, StageStats> stats = new EnumMap<>(Stage.class);
    private final Deque<Failure> failures = new ArrayDeque<>();
    private final LongSupplier clock;
    private volatile long slowNanos;

    public ChunkTaskTracker(LongSupplier clock, long slowMs) {
        this.clock = clock;
        this.slowNanos = slowMs * 1_000_000L;
        for (Stage stage : Stage.values()) stats.put(stage, new StageStats());
    }

    /**
     * Starts tracking {@code future}. A second request for the same key while the first is pending keeps
     * the older start time, since the chunk has been waiting since then.
     *
     * @param unloadedCheck tells whether a successful result means "chunk unloaded before it finished"
     */
    public <T> void track(Key key, CompletableFuture<T> future, java.util.function.Predicate<T> unloadedCheck) {
        if (future == null) return;
        StageStats s = stats.get(key.stage());
        if (future.isDone()) {
            s.started.increment();
            record(key, 0, future, unloadedCheck);
            return;
        }
        long start = clock.getAsLong();
        Entry entry = new Entry(key, start);
        Entry existing = inFlight.putIfAbsent(key, entry);
        s.started.increment();
        Entry owner = existing == null ? entry : existing;
        future.whenComplete((result, error) -> {
            if (existing == null) inFlight.remove(key, owner);
            record(key, clock.getAsLong() - start, future, unloadedCheck);
        });
    }

    private <T> void record(Key key, long nanos, CompletableFuture<T> future, java.util.function.Predicate<T> unloadedCheck) {
        StageStats s = stats.get(key.stage());
        T result;
        try {
            result = future.getNow(null);
        } catch (RuntimeException e) {
            s.failed.increment();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            addFailure(new Failure(System.currentTimeMillis(), key, cause.toString()));
            return;
        }
        if (result != null && unloadedCheck != null && unloadedCheck.test(result)) s.unloaded.increment();
        else s.completed.increment();
        s.totalNanos.addAndGet(nanos);
        s.maxNanos.accumulateAndGet(nanos, Math::max);
        if (nanos >= slowNanos) s.slow.increment();
    }

    private void addFailure(Failure failure) {
        synchronized (failures) {
            if (failures.size() == MAX_FAILURES) failures.removeFirst();
            failures.addLast(failure);
        }
    }

    public List<Failure> recentFailures() {
        synchronized (failures) {
            return new ArrayList<>(failures);
        }
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    /** In-flight tasks ordered from the oldest. */
    public List<Entry> oldest(int limit) {
        return inFlight.values().stream()
                .sorted(Comparator.comparingLong(Entry::startNanos))
                .limit(limit)
                .toList();
    }

    /** In-flight tasks older than {@code ageNanos}, oldest first. */
    public List<Entry> olderThan(long ageNanos, int limit) {
        long now = clock.getAsLong();
        return inFlight.values().stream()
                .filter(e -> now - e.startNanos() >= ageNanos)
                .sorted(Comparator.comparingLong(Entry::startNanos))
                .limit(limit)
                .toList();
    }

    public long ageNanos(Entry entry) {
        return clock.getAsLong() - entry.startNanos();
    }

    public StageStats stats(Stage stage) {
        return stats.get(stage);
    }

    /** Drops entries of a world that was unloaded so they are not reported as stuck forever. */
    public void forgetWorld(String world) {
        inFlight.keySet().removeIf(k -> k.world().equals(world));
    }

    public void clear() {
        inFlight.clear();
    }

    public void render(StringBuilder out) {
        for (Stage stage : Stage.values()) {
            StageStats s = stats.get(stage);
            long done = s.completed.sum() + s.unloaded.sum();
            out.append(String.format("  %-8s started=%d completed=%d unloaded=%d failed=%d slow=%d avg=%.1fms max=%.1fms%n",
                    stage, s.started.sum(), s.completed.sum(), s.unloaded.sum(), s.failed.sum(), s.slow.sum(),
                    done == 0 ? 0.0 : s.totalNanos.get() / 1e6 / done, s.maxNanos.get() / 1e6));
        }
    }
}
