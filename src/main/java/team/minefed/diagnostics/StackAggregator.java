package team.minefed.diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns repeated stack samples of one thread into a small profile: the most common full stacks,
 * the frames that were on the stack most often, and the mods that owned the innermost frames.
 * Not thread safe; one instance belongs to one stall.
 */
public final class StackAggregator {
    static final int KEY_DEPTH = 48;

    private final ModAttribution attribution;
    private final Map<List<String>, StackCount> stacks = new HashMap<>();
    private final Map<String, Integer> inclusiveFrames = new HashMap<>();
    private final Map<String, Integer> culprits = new HashMap<>();
    private final Map<String, Integer> inclusiveMods = new HashMap<>();
    private final Map<String, Integer> states = new LinkedHashMap<>();
    private final Map<String, Integer> waits = new HashMap<>();
    private int samples;

    public StackAggregator(ModAttribution attribution) {
        this.attribution = attribution;
    }

    /**
     * @param wait what the thread waited on when it was not running, e.g. "lock X held by Worker-Main-3";
     *             {@code null} when running
     */
    public void add(StackTraceElement[] frames, String state, String wait) {
        samples++;
        states.merge(state, 1, Integer::sum);
        if (wait != null) waits.merge(wait, 1, Integer::sum);

        int depth = Math.min(frames.length, KEY_DEPTH);
        List<String> key = new ArrayList<>(depth);
        for (int i = 0; i < depth; i++) key.add(frames[i].toString());
        stacks.computeIfAbsent(key, k -> new StackCount(frames, frames.length > KEY_DEPTH)).count++;

        Set<String> seenFrames = new HashSet<>();
        Set<String> seenMods = new HashSet<>();
        for (StackTraceElement frame : frames) {
            String label = ModAttribution.frameLabel(frame);
            if (seenFrames.add(label)) inclusiveFrames.merge(label, 1, Integer::sum);
            if (attribution.isModFrame(frame)) {
                String mod = attribution.modOf(frame);
                if (seenMods.add(mod)) inclusiveMods.merge(mod, 1, Integer::sum);
            }
        }
        String culprit = attribution.culprit(frames);
        culprits.merge(culprit == null ? "(vanilla only)" : culprit, 1, Integer::sum);
    }

    public int samples() {
        return samples;
    }

    /** The mod most often found as the innermost mod frame, or {@code null}. */
    public String topCulprit() {
        return culprits.entrySet().stream()
                .filter(e -> !e.getKey().equals("(vanilla only)"))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public List<Map.Entry<String, Integer>> topCulprits(int limit) {
        return top(culprits, limit);
    }

    public List<Map.Entry<String, Integer>> topInclusiveFrames(int limit) {
        return top(inclusiveFrames, limit);
    }

    public List<StackCount> topStacks(int limit) {
        return stacks.values().stream()
                .sorted(Comparator.comparingInt((StackCount s) -> s.count).reversed())
                .limit(limit)
                .toList();
    }

    public void render(StringBuilder out, int stackLimit, int frameLimit) {
        if (samples == 0) {
            out.append("  (no samples)\n");
            return;
        }
        out.append("  Samples: ").append(samples).append('\n');
        out.append("  Thread states: ").append(percentages(states)).append('\n');
        if (!waits.isEmpty()) {
            out.append("  Waiting on:\n");
            for (Map.Entry<String, Integer> e : top(waits, 8)) {
                out.append("    ").append(pct(e.getValue())).append("  ").append(e.getKey()).append('\n');
            }
        }
        out.append("  Innermost mod frame (who was running):\n");
        for (Map.Entry<String, Integer> e : top(culprits, 10)) {
            out.append("    ").append(pct(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        out.append("  Mods anywhere on the stack:\n");
        for (Map.Entry<String, Integer> e : top(inclusiveMods, 15)) {
            out.append("    ").append(pct(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        out.append("  Hottest frames (inclusive):\n");
        for (Map.Entry<String, Integer> e : top(inclusiveFrames, frameLimit)) {
            out.append("    ").append(pct(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        int n = 1;
        for (StackCount stack : topStacks(stackLimit)) {
            out.append("  Stack #").append(n++).append(" (").append(pct(stack.count)).append(", ")
                    .append(stack.count).append(" samples)\n");
            for (StackTraceElement frame : stack.frames) {
                String mod = attribution.modOf(frame);
                out.append("      at ").append(frame);
                if (mod != null && !mod.equals(ModAttribution.VANILLA)) out.append("  [").append(mod).append(']');
                out.append('\n');
            }
        }
    }

    private String percentages(Map<String, Integer> counts) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : top(counts, counts.size())) {
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey()).append(' ').append(pct(e.getValue()));
        }
        return b.toString();
    }

    private String pct(int count) {
        return String.format("%5.1f%%", 100.0 * count / samples);
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .toList();
    }

    public static final class StackCount {
        public final StackTraceElement[] frames;
        public final boolean truncatedKey;
        public int count;

        StackCount(StackTraceElement[] frames, boolean truncatedKey) {
            this.frames = Arrays.copyOf(frames, frames.length);
            this.truncatedKey = truncatedKey;
        }
    }
}
