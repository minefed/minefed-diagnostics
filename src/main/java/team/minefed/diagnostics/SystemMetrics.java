package team.minefed.diagnostics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Bounded reads of counters, never environment variables or process command lines. No subprocesses. */
final class SystemMetrics {
    private static final int FILE_LIMIT = 8192;
    private final Path proc, cgroup;

    SystemMetrics() { this(Path.of("/proc"), Path.of("/sys/fs/cgroup")); }
    SystemMetrics(Path proc, Path cgroup) { this.proc = proc; this.cgroup = cgroup; }

    String sample() {
        StringBuilder out = new StringBuilder("UTC ").append(Instant.now()).append('\n');
        out.append("monotonicNanos=").append(System.nanoTime()).append('\n');
        ThreadDumps.appendJvm(out);
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            out.append("processCpuTimeNanos=").append(extended.getProcessCpuTime())
                    .append(" processCpuLoad=").append(extended.getProcessCpuLoad())
                    .append(" cpuLoad=").append(extended.getCpuLoad())
                    .append(" freePhysicalBytes=").append(extended.getFreeMemorySize())
                    .append(" freeSwapBytes=").append(extended.getFreeSwapSpaceSize()).append('\n');
        }
        if (!Files.isDirectory(proc.resolve("self"))) {
            return out.append("Linux /proc unavailable; OS/cgroup counters not measured\n").toString();
        }
        for (String name : new String[]{"self/stat", "self/status", "self/io", "self/schedstat", "self/net/dev",
                "pressure/cpu", "pressure/io", "pressure/memory"}) append(out, proc.resolve(name));
        String membership = read(proc.resolve("self/cgroup"));
        out.append("cgroup membership:\n").append(membership).append('\n');
        for (String line : membership.split("\\R")) {
            if (!line.startsWith("0::/")) continue;
            Path reported = cgroup.resolve(line.substring(4)).normalize();
            if (reported.startsWith(cgroup.normalize())) appendCgroup(out, reported);
            if (!reported.equals(cgroup)) appendCgroup(out, cgroup);
            return out.toString();
        }
        return out.append("No cgroup v2 membership; counters unavailable (not zero)\n").toString();
    }

    private void appendCgroup(StringBuilder out, Path base) {
        out.append("cgroup candidate (verify against membership): ").append(base).append('\n');
        for (String name : new String[]{"cgroup.procs", "cpu.stat", "cpu.max", "io.stat", "memory.current",
                "memory.max", "memory.events", "cpu.pressure", "io.pressure", "memory.pressure"}) append(out, base.resolve(name));
    }

    private static void append(StringBuilder out, Path path) { out.append(path).append(':').append('\n').append(read(path)).append('\n'); }
    private static String read(Path file) {
        try (var stream = Files.newInputStream(file)) {
            byte[] data = stream.readNBytes(FILE_LIMIT + 1);
            return new String(data, 0, Math.min(data.length, FILE_LIMIT), StandardCharsets.UTF_8)
                    + (data.length > FILE_LIMIT ? "\n[truncated]" : "");
        } catch (IOException | SecurityException e) { return "MISSING_OR_UNREADABLE"; }
    }
}
