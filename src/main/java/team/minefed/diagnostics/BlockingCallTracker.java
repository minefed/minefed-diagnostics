package team.minefed.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregates slow blocking chunk requests by the code that made them. Two kinds are tracked:
 * the server thread waiting for a chunk that was not ready ("sync load"), and another thread
 * calling {@code getChunk} and waiting for the server thread to serve it ("off-thread").
 */
public final class BlockingCallTracker {
    private static final int MAX_CALLERS = 512;
    private static final int CALLER_FRAMES = 3;

    public static final class CallerStats {
        public final String caller;
        public final String mod;
        public final LongAdder count = new LongAdder();
        public final AtomicLong totalNanos = new AtomicLong();
        public final AtomicLong maxNanos = new AtomicLong();
        public volatile String lastDetail = "";
        /** Stack of the first occurrence, kept for the report. */
        public final StackTraceElement[] firstStack;

        CallerStats(String caller, String mod, StackTraceElement[] firstStack) {
            this.caller = caller;
            this.mod = mod;
            this.firstStack = firstStack;
        }
    }

    private final String name;
    private final ModAttribution attribution;
    private final ConcurrentHashMap<String, CallerStats> callers = new ConcurrentHashMap<>();
    private final LongAdder events = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();

    public BlockingCallTracker(String name, ModAttribution attribution) {
        this.name = name;
        this.attribution = attribution;
    }

    /** @return the caller entry, or {@code null} when the caller table is full */
    public CallerStats record(StackTraceElement[] stack, long nanos, String detail) {
        events.increment();
        totalNanos.addAndGet(nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
        String caller = callerKey(stack);
        CallerStats stats = callers.get(caller);
        if (stats == null) {
            if (callers.size() >= MAX_CALLERS) {
                dropped.increment();
                return null;
            }
            stats = callers.computeIfAbsent(caller, c -> new CallerStats(c, modOf(stack), stack));
        }
        stats.count.increment();
        stats.totalNanos.addAndGet(nanos);
        stats.maxNanos.accumulateAndGet(nanos, Math::max);
        stats.lastDetail = detail;
        return stats;
    }

    private String modOf(StackTraceElement[] stack) {
        String mod = attribution.culprit(stack);
        return mod == null ? "(vanilla)" : mod;
    }

    /**
     * The first few mod frames identify the caller. When no mod is on the stack the first frames
     * outside the chunk system are used instead, so that vanilla callers are still told apart.
     */
    String callerKey(StackTraceElement[] stack) {
        List<String> parts = new ArrayList<>(CALLER_FRAMES);
        for (StackTraceElement frame : stack) {
            if (attribution.isModFrame(frame)) {
                parts.add(ModAttribution.frameLabel(frame) + ":" + frame.getLineNumber());
                if (parts.size() == CALLER_FRAMES) break;
            }
        }
        if (parts.isEmpty()) {
            for (StackTraceElement frame : stack) {
                String cls = frame.getClassName();
                if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("team.minefed.diagnostics.")) continue;
                parts.add(ModAttribution.frameLabel(frame) + ":" + frame.getLineNumber());
                if (parts.size() == 6) break;
            }
        }
        return String.join(" <- ", parts);
    }

    public long events() {
        return events.sum();
    }

    public long maxNanos() {
        return maxNanos.get();
    }

    public List<CallerStats> topByTotal(int limit) {
        return callers.values().stream()
                .sorted(Comparator.comparingLong((CallerStats c) -> c.totalNanos.get()).reversed())
                .limit(limit)
                .toList();
    }

    public Map<String, CallerStats> callers() {
        return callers;
    }

    public void clear() {
        callers.clear();
        events.reset();
        dropped.reset();
        totalNanos.set(0);
        maxNanos.set(0);
    }

    public void render(StringBuilder out, int limit, boolean withStacks) {
        out.append(String.format("  %s: events=%d total=%.0fms max=%.1fms callers=%d%s%n", name, events.sum(),
                totalNanos.get() / 1e6, maxNanos.get() / 1e6, callers.size(),
                dropped.sum() > 0 ? " (dropped " + dropped.sum() + " events of new callers)" : ""));
        for (CallerStats c : topByTotal(limit)) {
            out.append(String.format("    [%s] count=%d total=%.0fms max=%.1fms last=%s%n      %s%n", c.mod, c.count.sum(),
                    c.totalNanos.get() / 1e6, c.maxNanos.get() / 1e6, c.lastDetail, c.caller));
            if (withStacks) {
                int shown = 0;
                for (StackTraceElement frame : c.firstStack) {
                    if (frame.getClassName().startsWith("team.minefed.diagnostics.")) continue;
                    out.append("        at ").append(frame).append('\n');
                    if (++shown == 40) {
                        out.append("        ...\n");
                        break;
                    }
                }
            }
        }
    }
}
