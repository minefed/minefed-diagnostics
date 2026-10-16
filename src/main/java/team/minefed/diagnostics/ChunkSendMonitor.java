package team.minefed.diagnostics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Watches the per-player chunk sender. Since 1.20.2 the server sends chunks in batches and stops once
 * the client has not acknowledged {@code maxUnacknowledgedBatches} batches; it also only sends chunks that
 * have finished loading. A player whose pending set does not shrink is therefore stuck for one of two
 * reasons, which this class tells apart.
 */
public final class ChunkSendMonitor {
    public enum Verdict {
        /** The server waits for the client to acknowledge earlier batches (network, proxy or client side). */
        WAITING_FOR_CLIENT_ACK,
        /** Acknowledgements are fine but the pending chunks never become ready (server chunk pipeline). */
        CHUNKS_NOT_READY
    }

    /** One reading of a player's {@code ChunkDataSender}. */
    public record Snapshot(int pendingChunks, int unacknowledgedBatches, int maxUnacknowledgedBatches,
                           float desiredBatchSize, float batchQuota) {
        boolean ackBlocked() {
            return unacknowledgedBatches >= maxUnacknowledgedBatches;
        }
    }

    /** Result of an observation that needs attention. */
    public record Event(Verdict verdict, long stuckNanos, Snapshot snapshot, boolean resetSuggested, boolean firstReport) {}

    private static final class State {
        Snapshot last;
        long lastProgress;
        boolean reported;
        boolean reset;
    }

    private final long stallNanos;
    private final long resetAfterNanos;
    private final Map<UUID, State> players = new HashMap<>();

    public ChunkSendMonitor(long stallMs, long resetAfterMs) {
        this.stallNanos = stallMs * 1_000_000L;
        this.resetAfterNanos = resetAfterMs * 1_000_000L;
    }

    /** @return an event when the player is stuck, otherwise {@code null} */
    public Event observe(UUID player, Snapshot now, long time) {
        State state = players.computeIfAbsent(player, p -> new State());
        Snapshot last = state.last;
        // An empty queue that fills again starts a new wait; growth of a non-empty queue does not.
        boolean progressed = last == null
                || now.pendingChunks() == 0
                || last.pendingChunks() == 0
                || now.pendingChunks() < last.pendingChunks()
                || now.unacknowledgedBatches() < last.unacknowledgedBatches();
        state.last = now;
        if (progressed) {
            state.lastProgress = time;
            state.reported = false;
            state.reset = false;
            return null;
        }
        long stuck = time - state.lastProgress;
        if (stuck < stallNanos) return null;
        Verdict verdict = now.ackBlocked() ? Verdict.WAITING_FOR_CLIENT_ACK : Verdict.CHUNKS_NOT_READY;
        boolean first = !state.reported;
        state.reported = true;
        boolean reset = verdict == Verdict.WAITING_FOR_CLIENT_ACK && stuck >= resetAfterNanos && !state.reset;
        return new Event(verdict, stuck, now, reset, first);
    }

    /** Called after the caller actually cleared the acknowledgement counter. */
    public void markReset(UUID player, long time) {
        State state = players.get(player);
        if (state != null) {
            state.reset = true;
            state.lastProgress = time;
            state.reported = false;
        }
    }

    public void forget(UUID player) {
        players.remove(player);
    }

    public Snapshot last(UUID player) {
        State state = players.get(player);
        return state == null ? null : state.last;
    }

    public long stuckNanos(UUID player, long time) {
        State state = players.get(player);
        if (state == null || state.last == null || state.last.pendingChunks() == 0) return 0;
        return time - state.lastProgress;
    }
}
