package team.minefed.diagnostics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/** Thread dump and JVM state formatting. {@link ThreadInfo#toString()} cuts stacks at 8 frames, so this does it in full. */
public final class ThreadDumps {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private ThreadDumps() {}

    public static ThreadMXBean bean() {
        return THREADS;
    }

    /** Samples one thread with full depth and lock information, or returns {@code null} if it is gone. */
    public static ThreadInfo sample(long threadId) {
        ThreadInfo[] infos = THREADS.getThreadInfo(new long[]{threadId}, true, true);
        return infos.length == 0 ? null : infos[0];
    }

    /** What a blocked or waiting thread waits on, in one line; {@code null} for a running thread. */
    public static String waitDescription(ThreadInfo info) {
        if (info.getThreadState() == Thread.State.RUNNABLE || info.getLockName() == null) return null;
        String text = info.getLockName();
        if (info.getLockOwnerName() != null) text += " held by \"" + info.getLockOwnerName() + "\"";
        return text;
    }

    /** Full dump, the server thread first, then threads with mod or chunk work, then the rest by name. */
    public static void appendAllThreads(StringBuilder out, ModAttribution attribution, long serverThreadId,
                                        Map<Long, String> notes) {
        ThreadInfo[] infos = THREADS.dumpAllThreads(THREADS.isObjectMonitorUsageSupported(),
                THREADS.isSynchronizerUsageSupported());
        Arrays.sort(infos, Comparator.comparing((ThreadInfo t) -> t.getThreadId() != serverThreadId)
                .thenComparing(ThreadInfo::getThreadName));
        out.append("Threads: ").append(infos.length).append('\n');
        for (ThreadInfo info : infos) {
            appendThread(out, info, attribution, notes.get(info.getThreadId()));
        }
    }

    public static void appendThread(StringBuilder out, ThreadInfo info, ModAttribution attribution, String note) {
        out.append('"').append(info.getThreadName()).append("\" id=").append(info.getThreadId())
                .append(info.isDaemon() ? " daemon" : "").append(" prio=").append(info.getPriority())
                .append(' ').append(info.getThreadState());
        long cpu = cpuMillis(info.getThreadId());
        if (cpu >= 0) out.append(" cpu=").append(cpu).append("ms");
        if (info.getLockName() != null) out.append(" on ").append(info.getLockName());
        if (info.getLockOwnerName() != null) {
            out.append(" owned by \"").append(info.getLockOwnerName()).append("\" id=").append(info.getLockOwnerId());
        }
        if (info.isSuspended()) out.append(" (suspended)");
        if (info.isInNative()) out.append(" (in native)");
        out.append('\n');
        if (note != null) out.append("    >>> ").append(note).append('\n');
        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            StackTraceElement frame = stack[i];
            out.append("    at ").append(frame);
            String mod = attribution.modOf(frame);
            if (mod != null && !mod.equals(ModAttribution.VANILLA)) out.append("  [").append(mod).append(']');
            out.append('\n');
            if (i == 0 && info.getLockInfo() != null) {
                out.append("    - ").append(info.getThreadState() == Thread.State.BLOCKED ? "blocked on " : "waiting on ")
                        .append(info.getLockInfo()).append('\n');
            }
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) out.append("    - locked ").append(monitor).append('\n');
            }
        }
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            out.append("    Locked synchronizers:\n");
            for (LockInfo lock : synchronizers) out.append("    - ").append(lock).append('\n');
        }
        out.append('\n');
    }

    private static long cpuMillis(long threadId) {
        try {
            if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return -1;
            long nanos = THREADS.getThreadCpuTime(threadId);
            return nanos < 0 ? -1 : nanos / 1_000_000;
        } catch (UnsupportedOperationException e) {
            return -1;
        }
    }

    /** Monitor and ownable-synchronizer deadlocks found by the JVM, with the stacks of the threads involved. */
    public static void appendDeadlocks(StringBuilder out, ModAttribution attribution) {
        long[] ids = THREADS.isSynchronizerUsageSupported() ? THREADS.findDeadlockedThreads() : THREADS.findMonitorDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            out.append("JVM deadlock detection: none\n");
            return;
        }
        out.append("JVM deadlock detection: ").append(ids.length).append(" THREADS DEADLOCKED\n");
        for (ThreadInfo info : THREADS.getThreadInfo(ids, true, true)) {
            if (info != null) appendThread(out, info, attribution, "deadlocked");
        }
    }

    public static Map<String, long[]> gcTotals() {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            totals.put(gc.getName(), new long[]{gc.getCollectionCount(), gc.getCollectionTime()});
        }
        return totals;
    }

    /** Collections and collector time between two {@link #gcTotals()} readings. */
    public static String gcDelta(Map<String, long[]> before, Map<String, long[]> after) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, long[]> e : after.entrySet()) {
            long[] old = before.getOrDefault(e.getKey(), new long[]{0, 0});
            long count = e.getValue()[0] - old[0];
            long time = e.getValue()[1] - old[1];
            if (b.length() > 0) b.append(", ");
            b.append(e.getKey()).append(": ").append(count).append(" collections ").append(time).append("ms");
        }
        return b.toString();
    }

    public static void appendJvm(StringBuilder out) {
        Runtime runtime = Runtime.getRuntime();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        out.append(String.format("JVM: %s %s, uptime %ds, processors %d, system load %.2f%n",
                System.getProperty("java.vm.name"), System.getProperty("java.runtime.version"),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000, runtime.availableProcessors(),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
        out.append(String.format("Heap: used %dMiB committed %dMiB max %dMiB; non-heap used %dMiB%n",
                heap.getUsed() >> 20, heap.getCommitted() >> 20, heap.getMax() >> 20, nonHeap.getUsed() >> 20));
        out.append("GC totals:");
        for (Map.Entry<String, long[]> e : gcTotals().entrySet()) {
            out.append(' ').append(e.getKey()).append('=').append(e.getValue()[0]).append('/')
                    .append(e.getValue()[1]).append("ms");
        }
        out.append('\n');
    }

    public static String describeExecutor(String name, ExecutorService executor) {
        if (executor instanceof ForkJoinPool pool) {
            return String.format("%s: parallelism=%d pool=%d active=%d running=%d queuedSubmissions=%d queuedTasks=%d steals=%d",
                    name, pool.getParallelism(), pool.getPoolSize(), pool.getActiveThreadCount(),
                    pool.getRunningThreadCount(), pool.getQueuedSubmissionCount(), pool.getQueuedTaskCount(),
                    pool.getStealCount());
        }
        return name + ": " + executor.getClass().getName();
    }
}
