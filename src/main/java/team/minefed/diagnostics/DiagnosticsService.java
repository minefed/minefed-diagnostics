package team.minefed.diagnostics;

import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.Util;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageIoWorker;
import team.minefed.diagnostics.mixin.ChunkDataSenderAccessor;
import team.minefed.diagnostics.mixin.StorageIoWorkerAccessor;
import team.minefed.diagnostics.mixin.ThreadedAnvilChunkStorageAccessor;
import team.minefed.diagnostics.mixin.VersionedChunkStorageAccessor;

/**
 * Owns all detectors for one server run. The server thread only updates a heartbeat and checks chunk
 * sending once a second; everything expensive (sampling, dumps, file writes) runs on the watchdog and
 * reporter threads. JVM sampling and JFR still have measurement overhead.
 */
public final class DiagnosticsService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final int TICK_HISTORY = 200;

    private final DiagnosticsConfig config;
    private final MinecraftServer server;
    private final Thread serverThread;
    private final ModAttribution attribution = new ModAttribution();
    private final ReportWriter writer;
    private final ChunkTaskTracker chunkTasks;
    private final BlockingCallTracker syncWaits;
    private final BlockingCallTracker offThreadWaits;
    private final ChunkSendMonitor sendMonitor;
    private final StallDetector stallDetector;
    private final SlowTickDetector slowTicks;
    private final AutomaticCapture automaticCapture;
    private final ThreadDumpLimiter threadDumpLimiter = new ThreadDumpLimiter(30_000_000_000L, System::nanoTime);
    private final ExecutorService reporter;
    private final Map<String, Long> lastLog = new ConcurrentHashMap<>();
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();
    private final long startedMillis = System.currentTimeMillis();

    private volatile boolean running;
    private volatile boolean stopping;
    private volatile long lastTickEnd;
    private volatile long tickStart;
    private volatile int ticks;
    private final long[] tickNanos = new long[TICK_HISTORY];
    private Thread watchdog;

    // Counters shown by the status command and the periodic summary.
    private final LongAdder stalls = new LongAdder();
    private final LongAdder reportedStalls = new LongAdder();
    private final AtomicLong maxStallNanos = new AtomicLong();
    private final LongAdder longTicks = new LongAdder();
    private final LongAdder jvmFreezes = new LongAdder();
    private final AtomicLong maxFreezeNanos = new AtomicLong();
    private volatile long lastFreezeEnd;
    private volatile long lastFreezeNanos;
    private final LongAdder pipelineStalls = new LongAdder();
    private final LongAdder sendStalls = new LongAdder();
    private final LongAdder ackResets = new LongAdder();
    private final LongAdder internalErrors = new LongAdder();
    private volatile String lastStallCulprit = "-";

    // Owned by the watchdog thread.
    private StallRecord stall;

    public DiagnosticsService(DiagnosticsConfig config, MinecraftServer server, Path reportDirectory) {
        this.config = config;
        this.server = server;
        this.serverThread = server.getThread();
        this.writer = new ReportWriter(reportDirectory, config.reportRetain);
        this.chunkTasks = new ChunkTaskTracker(System::nanoTime, config.chunkTaskStallMs / 4);
        this.syncWaits = new BlockingCallTracker("Server thread waits in chunk executor (load or save)", attribution);
        this.offThreadWaits = new BlockingCallTracker("Other threads blocked in getChunk", attribution);
        this.sendMonitor = new ChunkSendMonitor(config.chunkSendStallMs, config.chunkSendResetAfterMs);
        this.stallDetector = new StallDetector(config);
        this.slowTicks = new SlowTickDetector(config.slowTickWindow, config.slowTickThresholdMs);
        this.automaticCapture = new AutomaticCapture(config, reportDirectory.resolve("captures"), () -> {
            StringBuilder text = new StringBuilder();
            appendThreadDump(text);
            return text.toString();
        }, message -> MinefedDiagnostics.LOGGER.info("{}", message));
        this.reporter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Minefed-Diagnostics-Reporter");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        running = true;
        Hooks.install(this);
        automaticCapture.start();
        reporter.execute(this::indexMods);
        watchdog = new Thread(this::watchdogLoop, "Minefed-Diagnostics-Watchdog");
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.MAX_PRIORITY);
        watchdog.start();
        MinefedDiagnostics.LOGGER.info("Diagnostics active: stall >= {}ms, sync chunk wait >= {}ms, chunk task stall >= {}ms, "
                        + "chunk send stall >= {}ms, reports in {}", config.stallThresholdMs, config.syncLoadWarnMs,
                config.chunkTaskStallMs, config.chunkSendStallMs, writer.directory());
    }

    public void beginStopping() {
        stopping = true;
        logSummary("server stopping");
    }

    public void stop() {
        running = false;
        Hooks.uninstall();
        if (watchdog != null) watchdog.interrupt();
        automaticCapture.close();
        reporter.shutdown();
    }

    private void indexMods() {
        long start = System.nanoTime();
        Map<String, String> index = ModAttribution.newIndex();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) ModAttribution.indexRoot(mod.getMetadata().getId(), root, index);
        }
        attribution.setPackages(index);
        MinefedDiagnostics.LOGGER.info("Indexed {} packages of {} mods for stack attribution in {}ms", index.size(),
                FabricLoader.getInstance().getAllMods().size(), (System.nanoTime() - start) / 1_000_000);
    }

    boolean isServerThread() {
        return Thread.currentThread() == serverThread;
    }

    ChunkTaskTracker chunkTasks() {
        return chunkTasks;
    }

    // ------------------------------------------------------------------ server thread

    public void onTickStart() {
        tickStart = System.nanoTime();
        if (lastTickEnd == 0) lastTickEnd = tickStart;
    }

    public void onTickEnd() {
        long now = System.nanoTime();
        long length = now - tickStart;
        int tick = ticks;
        tickNanos[tick % TICK_HISTORY] = length;
        if (length > 250_000_000L) longTicks.increment();
        lastTickEnd = now;
        ticks = tick + 1;
        if (!stopping) {
            try {
                SlowTickDetector.Window window = slowTicks.complete(length);
                if (window != null) {
                    TickWindowEvent.record(window);
                    if (window.slow()) automaticCapture.request("slow-ticks: " + window.count()
                            + " completed ticks, avg=" + window.averageMs() + "ms max=" + window.maximumMs() + "ms", now);
                }
            } catch (RuntimeException | LinkageError e) {
                internalError("automaticTickCapture", e);
            }
        }
        if (tick % 20 == 0) {
            try {
                checkChunkSending(now);
            } catch (RuntimeException e) {
                internalError("checkChunkSending", e);
            }
        }
    }

    void onSyncWait(Hooks.SyncWait wait, long nanos) {
        if (nanos < config.syncLoadWarnMs * 1_000_000L) return;
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            BlockingCallTracker.CallerStats caller = syncWaits.record(stack, nanos, wait.describe());
            if (caller != null && shouldLog("sync:" + caller.caller, 10_000)) {
                MinefedDiagnostics.LOGGER.warn("Server thread waited {}ms in chunk executor; last observed chunk request {}; caller [{}] {}",
                        nanos / 1_000_000, wait.describe(), caller.mod, caller.caller);
            }
        } catch (RuntimeException e) {
            internalError("onSyncWait", e);
        }
    }

    void onOffThreadWait(Hooks.OffThreadWait wait, long nanos) {
        if (nanos < config.offThreadWarnMs * 1_000_000L) return;
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            BlockingCallTracker.CallerStats caller = offThreadWaits.record(stack, nanos, wait.describe());
            if (caller != null && shouldLog("off:" + caller.caller, 10_000)) {
                MinefedDiagnostics.LOGGER.warn("Thread {} waited {}ms in getChunk for the server thread ({}); caller [{}] {}",
                        wait.thread().getName(), nanos / 1_000_000, wait.describe(), caller.mod, caller.caller);
            }
        } catch (RuntimeException e) {
            internalError("onOffThreadWait", e);
        }
    }

    private void checkChunkSending(long now) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ChunkDataSenderAccessor sender = (ChunkDataSenderAccessor) player.networkHandler.chunkDataSender;
            LongSet pending = sender.minefedDiagnostics$chunks();
            ChunkSendMonitor.Snapshot snapshot = new ChunkSendMonitor.Snapshot(pending.size(),
                    sender.minefedDiagnostics$unacknowledgedBatches(), sender.minefedDiagnostics$maxUnacknowledgedBatches(),
                    sender.minefedDiagnostics$desiredBatchSize(), sender.minefedDiagnostics$pending(),
                    player.networkHandler.chunkDataSender instanceof ChunkSendProgress progress ? progress.minefedDiagnostics$progress() : -1);
            ChunkSendMonitor.Event event = sendMonitor.observe(player.getUuid(), snapshot, now);
            if (event == null) continue;
            if (event.firstReport()) onSendStall(player, event, pending);
            if (event.resetSuggested() && config.chunkSendResetStuckAcks) {
                sender.minefedDiagnostics$setUnacknowledgedBatches(0);
                sendMonitor.markReset(player.getUuid(), now);
                ackResets.increment();
                MinefedDiagnostics.LOGGER.warn("Cleared {} unacknowledged chunk batches of {} after {}s without an acknowledgement",
                        snapshot.unacknowledgedBatches(), player.getGameProfile().getName(), event.stuckNanos() / 1_000_000_000L);
            }
        }
    }

    public void onPlayerLeft(UUID player) {
        sendMonitor.forget(player);
    }

    public void onWorldUnload(ServerWorld world) {
        chunkTasks.forgetWorld(Hooks.worldName(world));
    }

    /** Runs on the server thread; collects what must be read there and hands the rest to the reporter. */
    private void onSendStall(ServerPlayerEntity player, ChunkSendMonitor.Event event, LongSet pending) {
        sendStalls.increment();
        automaticCapture.request("chunk-send observation: " + event.verdict(), System.nanoTime());
        String name = player.getGameProfile().getName();
        ChunkPos center = player.getChunkPos();
        ServerWorld world = player.getServerWorld();
        int latency = player.networkHandler.getLatency();
        MinefedDiagnostics.LOGGER.warn("Chunk sending to {} stuck for {}s: {} (pending {}, unacknowledged batches {}/{}, latency {}ms) at {} {}",
                name, event.stuckNanos() / 1_000_000_000L, event.verdict(), event.snapshot().pendingChunks(),
                event.snapshot().unacknowledgedBatches(), event.snapshot().maxUnacknowledgedBatches(), latency,
                Hooks.worldName(world), center);
        if (!cooldownPassed("send:" + player.getUuid())) return;

        StringBuilder head = new StringBuilder();
        head.append("Minefed Diagnostics - chunk send stall\n");
        head.append("Time: ").append(TIME.format(Instant.now())).append('\n');
        head.append("Player: ").append(name).append(" (").append(player.getUuid()).append(")\n");
        head.append("Verdict: ").append(event.verdict()).append(" for ").append(event.stuckNanos() / 1_000_000).append("ms\n");
        head.append(event.verdict() == ChunkSendMonitor.Verdict.WAITING_FOR_CLIENT_ACK
                ? "Observation: the sender's unacknowledged batch limit is reached.\n"
                + "         Correlate client/network activity and server scheduling; this does not prove a network fault.\n"
                : "Observation: pending chunks remain without observed batch preparation or ACK progress.\n"
                + "         Check chunk tasks, sender state and worker threads; queue counts alone cannot identify the cause.\n");
        head.append("Sender: ").append(event.snapshot()).append('\n');
        head.append("Latency: ").append(latency).append("ms, world ").append(Hooks.worldName(world)).append(", chunk ")
                .append(center).append(", view distance ").append(server.getPlayerManager().getViewDistance()).append('\n');
        head.append("\nNearest pending chunks (holder state read on the server thread):\n");
        ThreadedAnvilChunkStorage storage = world.getChunkManager().threadedAnvilChunkStorage;
        List<Long> nearest = new ArrayList<>(pending);
        nearest.sort((a, b) -> Integer.compare(distance(center, a), distance(center, b)));
        for (int i = 0; i < Math.min(8, nearest.size()); i++) {
            ChunkPos pos = new ChunkPos(nearest.get(i));
            head.append("  ").append(pos).append(" d=").append(distance(center, nearest.get(i))).append(": ")
                    .append(safe(() -> storage.getChunkLoadingDebugInfo(pos))).append('\n');
        }
        boolean withThreads = event.verdict() == ChunkSendMonitor.Verdict.NO_CHUNK_SEND_PROGRESS;
        reporter.execute(() -> {
            StringBuilder out = new StringBuilder(head);
            out.append('\n');
            appendPipeline(out, 40);
            appendBlockingCalls(out, false);
            if (withThreads) {
                out.append("\n=== Thread dump ===\n");
                appendThreadDump(out);
            }
            Path file = writer.write("chunk-send-stall", out.toString());
            if (file != null) MinefedDiagnostics.LOGGER.warn("Chunk send stall report: {}", file);
        });
    }

    private static int distance(ChunkPos center, long packed) {
        return Math.max(Math.abs(ChunkPos.getPackedX(packed) - center.x), Math.abs(ChunkPos.getPackedZ(packed) - center.z));
    }

    // ------------------------------------------------------------------ watchdog thread

    private void watchdogLoop() {
        long pollNanos = config.pollIntervalMs * 1_000_000L;
        long lastPipelineCheck = System.nanoTime();
        long lastSummary = System.nanoTime();
        while (running) {
            long before = System.nanoTime();
            try {
                Thread.sleep(config.pollIntervalMs);
            } catch (InterruptedException e) {
                if (!running) return;
            }
            long now = System.nanoTime();
            long overslept = now - before - pollNanos;
            try {
                if (overslept >= config.jvmFreezeWarnMs * 1_000_000L) onJvmFreeze(overslept, now);
                handleStall(now);
                if (now - lastPipelineCheck >= 1_000_000_000L) {
                    lastPipelineCheck = now;
                    checkPipeline(now);
                }
                if (now - lastSummary >= config.summaryIntervalMs * 1_000_000L) {
                    lastSummary = now;
                    logSummary("periodic");
                }
            } catch (Throwable t) {
                internalError("watchdog", t);
            }
        }
    }

    /**
     * A delayed watchdog is an observation, not proof that every JVM thread stopped.
     */
    private void onJvmFreeze(long nanos, long now) {
        jvmFreezes.increment();
        maxFreezeNanos.accumulateAndGet(nanos, Math::max);
        lastFreezeEnd = now;
        lastFreezeNanos = nanos;
        MinefedDiagnostics.LOGGER.warn("Watchdog scheduling delayed by about {}ms. "
                + "Correlate JFR/GC and OS counters to distinguish GC, CPU contention, swap or suspension. GC totals: {}",
                nanos / 1_000_000, ThreadDumps.gcTotals().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1] + "ms").toList());
        if (!stopping) automaticCapture.request("watchdog-delay: " + nanos / 1_000_000 + "ms", now);
    }

    private void handleStall(long now) {
        long last = stopping ? 0 : lastTickEnd;
        EnumSet<StallDetector.Action> actions = stallDetector.poll(now, last);
        if (actions.isEmpty()) return;
        if (actions.contains(StallDetector.Action.STARTED)) {
            stall = new StallRecord(stallDetector.stallTickEnd(), ticks);
            automaticCapture.request("server-stall: last completed tick " + ticks, now);
        }
        StallRecord record = stall;
        if (record == null) return;
        if (actions.contains(StallDetector.Action.SAMPLE)) {
            long sampleStarted = System.nanoTime();
            try {
                record.sample();
            } finally {
                long sampleEnded = System.nanoTime();
                stallDetector.sampleCompleted(sampleEnded, sampleEnded - sampleStarted);
            }
        }
        if (!stopping && lastTickEnd == record.tickEnd
                && (actions.contains(StallDetector.Action.FIRST_DUMP) || actions.contains(StallDetector.Action.SECOND_DUMP))) {
            long elapsedMs = (now - record.tickEnd) / 1_000_000;
            StringBuilder dump = new StringBuilder();
            dump.append("=== Thread dump at ").append(elapsedMs).append("ms into the stall ===\n");
            appendThreadDump(dump);
            record.dumps.add(dump.toString());
            record.pipelineAtDump = pipelineText(60);
            String text = renderStall(record, now, false);
            if (record.file == null) record.file = writer.write("server-stall", text);
            else writer.rewrite(record.file, text);
            MinefedDiagnostics.LOGGER.warn("Server thread has not finished a tick for {}ms; culprit so far: {}; in-progress report {}",
                    elapsedMs, culpritText(record), record.file);
        }
        if (actions.contains(StallDetector.Action.ENDED)) {
            stall = null;
            long nanos = stallDetector.endedDurationNanos();
            stalls.increment();
            maxStallNanos.accumulateAndGet(nanos, Math::max);
            if (stopping || (record.file == null && nanos < config.stallReportMinMs * 1_000_000L)) return;
            reportedStalls.increment();
            lastStallCulprit = culpritText(record);
            String text = renderStall(record, now, true);
            if (record.file == null) record.file = writer.write("server-stall", text);
            else writer.rewrite(record.file, text);
            MinefedDiagnostics.LOGGER.warn("Server thread stalled for {}ms (tick {}); culprit: {}; report {}",
                    nanos / 1_000_000, record.tick, lastStallCulprit, record.file);
        }
    }

    private String culpritText(StallRecord record) {
        String culprit = record.profile.topCulprit();
        Hooks.SyncWait wait = record.syncWait;
        if (wait != null) return (culprit == null ? "vanilla" : culprit)
                + ", chunk-executor wait; last observed chunk request " + wait.describe();
        return culprit == null ? "no mod frame on the stack (vanilla or JVM)" : culprit;
    }

    private void checkPipeline(long now) {
        if (stallDetector.isStalled()) return; // the stall report already covers the pipeline
        List<ChunkTaskTracker.Entry> stuck = chunkTasks.olderThan(config.chunkTaskStallMs * 1_000_000L, 30);
        if (stuck.isEmpty()) return;
        ChunkTaskTracker.Entry oldest = stuck.get(0);
        if (!cooldownPassed("pipeline")) return;
        automaticCapture.request("chunk-pipeline: tasks older than " + config.chunkTaskStallMs + "ms", now);
        pipelineStalls.increment();
        MinefedDiagnostics.LOGGER.warn("Chunk pipeline stall: {} chunk tasks older than {}s, oldest {} waiting {}ms",
                stuck.size(), config.chunkTaskStallMs / 1000, oldest.key(), chunkTasks.ageNanos(oldest) / 1_000_000);
        StringBuilder out = new StringBuilder();
        out.append("Minefed Diagnostics - chunk pipeline stall\n");
        out.append("Time: ").append(TIME.format(Instant.now())).append('\n');
        out.append("The server thread keeps ticking, but these chunk tasks have not completed for more than ")
                .append(config.chunkTaskStallMs).append("ms.\n");
        out.append("Players near them see missing chunks. Worker threads and queues below show where they wait.\n\n");
        out.append("Stuck tasks (oldest first):\n");
        for (int i = 0; i < stuck.size(); i++) {
            ChunkTaskTracker.Entry entry = stuck.get(i);
            out.append(String.format("  %6dms  %s%n", chunkTasks.ageNanos(entry) / 1_000_000, entry.key()));
            if (i < 10) out.append("           holder: ").append(holderInfo(entry.key())).append('\n');
        }
        out.append('\n');
        appendPipeline(out, 0);
        appendBlockingCalls(out, false);
        out.append("\n=== Thread dump ===\n");
        appendThreadDump(out);
        Path file = writer.write("chunk-pipeline-stall", out.toString());
        if (file != null) MinefedDiagnostics.LOGGER.warn("Chunk pipeline stall report: {}", file);
    }

    private String holderInfo(ChunkTaskTracker.Key key) {
        ServerWorld world = findWorld(key.world());
        if (world == null) return "(world not loaded)";
        ChunkPos pos = new ChunkPos(key.x(), key.z());
        return safe(() -> world.getChunkManager().threadedAnvilChunkStorage.getChunkLoadingDebugInfo(pos));
    }

    private ServerWorld findWorld(String name) {
        try {
            for (ServerWorld world : server.getWorlds()) {
                if (Hooks.worldName(world).equals(name)) return world;
            }
        } catch (RuntimeException ignored) {
            // the world map was modified concurrently
        }
        return null;
    }

    // ------------------------------------------------------------------ report sections

    /** Notes attached to threads in dumps: what each thread is known to be waiting for. */
    private Map<Long, String> threadNotes() {
        Map<Long, String> notes = new HashMap<>();
        Hooks.SyncWait wait = Hooks.currentSyncWait();
        if (wait != null) {
            notes.put(serverThread.getId(), "blocked " + (System.nanoTime() - wait.startNanos()) / 1_000_000
                    + "ms in chunk executor; last observed chunk request " + wait.describe());
        }
        for (Hooks.OffThreadWait off : Hooks.offThreadWaits()) {
            notes.put(off.thread().getId(), "in getChunk for " + (System.nanoTime() - off.startNanos()) / 1_000_000
                    + "ms waiting for the server thread: " + off.describe());
        }
        return notes;
    }

    private String pipelineText(int inFlightLimit) {
        StringBuilder out = new StringBuilder();
        appendPipeline(out, inFlightLimit);
        return out.toString();
    }

    /** Queue sizes of every stage of the chunk system. Read without locks; values are approximate. */
    void appendPipeline(StringBuilder out, int inFlightLimit) {
        out.append("=== Chunk system ===\n");
        try {
            for (ServerWorld world : server.getWorlds()) {
                ServerChunkManager manager = world.getChunkManager();
                ThreadedAnvilChunkStorage storage = manager.threadedAnvilChunkStorage;
                ThreadedAnvilChunkStorageAccessor access = (ThreadedAnvilChunkStorageAccessor) storage;
                out.append(Hooks.worldName(world)).append(": holders=").append(safe(() -> String.valueOf(manager.getLoadedChunkCount())))
                        .append(" fullChunks=").append(safe(() -> String.valueOf(storage.getTotalChunksLoadedCount())))
                        .append(" mainThreadQueue=").append(safe(() -> String.valueOf(manager.getPendingTasks())))
                        .append(" toUnload=").append(safe(() -> String.valueOf(access.minefedDiagnostics$chunksToUnload().size())))
                        .append(" unloadQueue=").append(safe(() -> String.valueOf(access.minefedDiagnostics$unloadTaskQueue().size())))
                        .append('\n');
                out.append("  priority queues: ").append(safe(() -> access.minefedDiagnostics$chunkTaskPrioritySystem().getDebugString()))
                        .append('\n');
                out.append("  region IO: ").append(safe(() -> {
                    StorageIoWorker worker = ((VersionedChunkStorageAccessor) storage).minefedDiagnostics$worker();
                    StorageIoWorkerAccessor io = (StorageIoWorkerAccessor) worker;
                    return "queued=" + io.minefedDiagnostics$executor().getQueueSize() + " pendingWrites="
                            + io.minefedDiagnostics$results().size();
                })).append('\n');
            }
        } catch (RuntimeException e) {
            out.append("(world list changed while reading: ").append(e).append(")\n");
        }
        out.append(ThreadDumps.describeExecutor("Worker-Main pool", Util.getMainWorkerExecutor())).append('\n');
        out.append(ThreadDumps.describeExecutor("IO-Worker pool", Util.getIoWorkerExecutor())).append('\n');
        out.append("In-flight chunk tasks: ").append(chunkTasks.inFlightCount()).append('\n');
        chunkTasks.render(out);
        if (inFlightLimit > 0) {
            for (ChunkTaskTracker.Entry entry : chunkTasks.oldest(inFlightLimit)) {
                out.append(String.format("  %6dms  %s%n", chunkTasks.ageNanos(entry) / 1_000_000, entry.key()));
            }
        }
        List<ChunkTaskTracker.Failure> failures = chunkTasks.recentFailures();
        if (!failures.isEmpty()) {
            out.append("Recent chunk task failures:\n");
            for (ChunkTaskTracker.Failure f : failures) {
                out.append("  ").append(TIME.format(Instant.ofEpochMilli(f.timeMillis()))).append(' ').append(f.key())
                        .append(": ").append(f.message()).append('\n');
            }
        }
    }

    void appendBlockingCalls(StringBuilder out, boolean withStacks) {
        out.append("\n=== Blocking chunk requests ===\n");
        Hooks.SyncWait wait = Hooks.currentSyncWait();
        if (wait != null) {
            out.append("Server thread is blocked right now for ").append((System.nanoTime() - wait.startNanos()) / 1_000_000)
                    .append("ms in chunk executor; last observed chunk request ").append(wait.describe()).append('\n');
        }
        List<Hooks.OffThreadWait> waiting = Hooks.offThreadWaits();
        if (!waiting.isEmpty()) {
            out.append("Threads waiting in getChunk for the server thread right now:\n");
            for (Hooks.OffThreadWait off : waiting) {
                out.append(String.format("  %6dms  %s%n", (System.nanoTime() - off.startNanos()) / 1_000_000, off.describe()));
            }
        }
        syncWaits.render(out, 10, withStacks);
        offThreadWaits.render(out, 10, withStacks);
        out.append("  getChunk calls from other threads, by thread: ");
        Map<String, Long> calls = new LinkedHashMap<>();
        Hooks.offThreadCallsByThread().forEach((k, v) -> calls.put(k, v.sum()));
        out.append(calls.isEmpty() ? "none" : calls.toString()).append('\n');
    }

    private String renderStall(StallRecord record, long now, boolean ended) {
        StringBuilder out = new StringBuilder();
        long elapsed = ended ? stallDetector.endedDurationNanos() : now - record.tickEnd;
        out.append("Minefed Diagnostics - server thread stall\n");
        out.append("State: ").append(ended ? "ENDED" : "IN PROGRESS (this file is rewritten when the stall ends)").append('\n');
        out.append("Last finished tick: ").append(TIME.format(Instant.ofEpochMilli(record.wallTickEnd)))
                .append(" (tick ").append(record.tick).append(")\n");
        out.append("Duration: ").append(elapsed / 1_000_000).append("ms").append(ended ? "" : " so far").append('\n');
        out.append("Culprit: ").append(culpritText(record)).append('\n');
        out.append("Players online: ").append(record.players).append(", ").append(record.tickStats).append('\n');
        if (record.syncWait != null) {
            out.append("Server thread was waiting in chunk executor; last observed chunk request ").append(record.syncWait.describe())
                    .append(" (the executor may be waiting for loading or a flushing save)\n");
        }
        if (lastFreezeEnd != 0 && lastFreezeEnd >= record.tickEnd) {
            out.append("Watchdog scheduling delay overlapped this stall: ").append(lastFreezeNanos / 1_000_000)
                    .append("ms; cause requires JFR/GC and OS correlation\n");
        }
        out.append("GC since stall detection (earlier pauses may be absent): ")
                .append(ThreadDumps.gcDelta(record.gcBefore, ThreadDumps.gcTotals())).append('\n');
        out.append("Attribution index: ").append(attribution.packageCount()).append(" packages\n");
        out.append("Names are intermediary (net.minecraft.class_XXXX.method_YYYY); map them with Yarn 1.20.4+build.3.\n");
        out.append("\n=== Server thread profile ===\n");
        record.profile.render(out, 5, 30);
        if (!record.lockOwners.isEmpty()) {
            out.append("\n=== Threads holding what the server thread waited for ===\n");
            record.lockOwners.forEach((name, text) -> out.append(text));
        }
        if (!record.offThreadAtStart.isEmpty()) {
            out.append("\nThreads waiting in getChunk when the stall started:\n");
            for (String line : record.offThreadAtStart) out.append("  ").append(line).append('\n');
        }
        out.append('\n');
        out.append(record.pipelineAtDump != null ? record.pipelineAtDump : pipelineText(30));
        appendBlockingCalls(out, true);
        out.append('\n');
        ThreadDumps.appendJvm(out);
        for (String dump : record.dumps) out.append('\n').append(dump);
        return out.toString();
    }

    private final class StallRecord {
        final long tickEnd;
        final long wallTickEnd;
        final int tick;
        final int players;
        final String tickStats;
        final Map<String, long[]> gcBefore = ThreadDumps.gcTotals();
        final StackAggregator profile = new StackAggregator(attribution);
        final Map<String, String> lockOwners = new LinkedHashMap<>();
        final List<String> dumps = new ArrayList<>();
        final List<String> offThreadAtStart = new ArrayList<>();
        Hooks.SyncWait syncWait;
        String pipelineAtDump;
        Path file;

        StallRecord(long tickEnd, int tick) {
            this.tickEnd = tickEnd;
            this.wallTickEnd = System.currentTimeMillis() - (System.nanoTime() - tickEnd) / 1_000_000;
            this.tick = tick;
            this.players = server.getCurrentPlayerCount();
            this.tickStats = tickStats();
            for (Hooks.OffThreadWait off : Hooks.offThreadWaits()) {
                offThreadAtStart.add((System.nanoTime() - off.startNanos()) / 1_000_000 + "ms " + off.describe());
            }
        }

        void sample() {
            ThreadInfo info = ThreadDumps.sample(serverThread.getId());
            if (info == null) return;
            profile.add(info.getStackTrace(), info.getThreadState().name(), ThreadDumps.waitDescription(info));
            Hooks.SyncWait wait = Hooks.currentSyncWait();
            if (wait != null && syncWait == null) syncWait = wait;
            long owner = info.getLockOwnerId();
            if (owner >= 0 && lockOwners.size() < 8 && !lockOwners.containsKey(info.getLockOwnerName())) {
                ThreadInfo ownerInfo = ThreadDumps.sample(owner);
                if (ownerInfo != null) {
                    StringBuilder text = new StringBuilder();
                    Hooks.OffThreadWait off = findOffThreadWait(owner);
                    ThreadDumps.appendThread(text, ownerInfo, attribution, off == null ? null
                            : "this thread is itself waiting in getChunk for the server thread: " + off.describe()
                            + " -> suspected circular wait; confirm with current stacks and JVM deadlock detection");
                    lockOwners.put(info.getLockOwnerName(), text.toString());
                }
            }
        }
    }

    private static Hooks.OffThreadWait findOffThreadWait(long threadId) {
        for (Hooks.OffThreadWait off : Hooks.offThreadWaits()) {
            if (off.thread().getId() == threadId) return off;
        }
        return null;
    }

    // ------------------------------------------------------------------ status, summary, commands

    String tickStats() {
        int n = Math.min(ticks, TICK_HISTORY);
        if (n == 0) return "no ticks yet";
        long sum = 0;
        long max = 0;
        for (int i = 0; i < n; i++) {
            long v = tickNanos[i];
            sum += v;
            max = Math.max(max, v);
        }
        return String.format("last %d ticks: avg %.1fms max %.1fms", n, sum / 1e6 / n, max / 1e6);
    }

    String status() {
        long now = System.nanoTime();
        StringBuilder b = new StringBuilder();
        long sinceTick = lastTickEnd == 0 ? -1 : (now - lastTickEnd) / 1_000_000;
        b.append("Minefed Diagnostics: tick ").append(ticks).append(", last tick ended ").append(sinceTick).append("ms ago, ")
                .append(tickStats()).append('\n');
        b.append(String.format("Stalls: %d (reported %d, max %dms, last culprit %s), ticks > 250ms: %d%n", stalls.sum(),
                reportedStalls.sum(), maxStallNanos.get() / 1_000_000, lastStallCulprit, longTicks.sum()));
        b.append(String.format("Watchdog delays: %d (max %dms), pipeline stalls: %d, send observations: %d, ack resets: %d%n",
                jvmFreezes.sum(), maxFreezeNanos.get() / 1_000_000, pipelineStalls.sum(), sendStalls.sum(), ackResets.sum()));
        b.append("Automatic capture: ").append(automaticCapture.status()).append('\n');
        List<ChunkTaskTracker.Entry> oldest = chunkTasks.oldest(1);
        b.append("In-flight chunk tasks: ").append(chunkTasks.inFlightCount());
        if (!oldest.isEmpty()) b.append(", oldest ").append(chunkTasks.ageNanos(oldest.get(0)) / 1_000_000).append("ms ").append(oldest.get(0).key());
        b.append('\n');
        b.append(String.format("Blocking waits: server thread %d (max %dms), other threads %d (max %dms)%n",
                syncWaits.events(), syncWaits.maxNanos() / 1_000_000, offThreadWaits.events(), offThreadWaits.maxNanos() / 1_000_000));
        StringBuilder hooks = new StringBuilder();
        int seen = Hooks.seenHooks();
        for (int i = 0; i < Hooks.HOOK_NAMES.length; i++) {
            hooks.append(Hooks.HOOK_NAMES[i]).append((seen & (1 << i)) != 0 ? "+ " : "- ");
        }
        b.append("Hooks fired: ").append(hooks.toString().trim()).append(" (- = not seen yet)\n");
        b.append("Internal errors: ").append(internalErrors.sum()).append(", reports: ").append(writer.directory());
        return b.toString();
    }

    String chunksText() {
        StringBuilder out = new StringBuilder();
        appendPipeline(out, 15);
        return out.toString();
    }

    String playersText() {
        StringBuilder out = new StringBuilder();
        long now = System.nanoTime();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ChunkSendMonitor.Snapshot s = sendMonitor.last(player.getUuid());
            out.append(player.getGameProfile().getName()).append(": ")
                    .append(s == null ? "no reading yet" : "pending=" + s.pendingChunks() + " unacked=" + s.unacknowledgedBatches()
                            + "/" + s.maxUnacknowledgedBatches() + " batch=" + String.format("%.1f", s.desiredBatchSize()))
                    .append(" stuck=").append(sendMonitor.stuckNanos(player.getUuid(), now) / 1_000_000).append("ms latency=")
                    .append(player.networkHandler.getLatency()).append("ms\n");
        }
        return out.length() == 0 ? "No players online" : out.toString().trim();
    }

    String callersText() {
        StringBuilder out = new StringBuilder();
        syncWaits.render(out, 5, false);
        offThreadWaits.render(out, 5, false);
        return out.toString().trim();
    }

    /** Writes a full snapshot report on the reporter thread. */
    void dumpNow(java.util.function.Consumer<Path> done) {
        reporter.execute(() -> {
            StringBuilder out = new StringBuilder();
            out.append("Minefed Diagnostics - manual snapshot\n");
            out.append("Time: ").append(TIME.format(Instant.now())).append('\n');
            out.append(status()).append("\n\n");
            appendPipeline(out, 60);
            appendBlockingCalls(out, true);
            out.append('\n');
            ThreadDumps.appendJvm(out);
            out.append("\n=== Thread dump ===\n");
            appendThreadDump(out);
            done.accept(writer.write("snapshot", out.toString()));
        });
    }

    void resetStatistics() {
        syncWaits.clear();
        offThreadWaits.clear();
        stalls.reset();
        reportedStalls.reset();
        maxStallNanos.set(0);
        longTicks.reset();
        jvmFreezes.reset();
        maxFreezeNanos.set(0);
        pipelineStalls.reset();
        sendStalls.reset();
        ackResets.reset();
        lastStallCulprit = "-";
    }

    private void appendThreadDump(StringBuilder out) {
        if (!threadDumpLimiter.run(() -> {
            ThreadDumps.appendDeadlocks(out, attribution);
            ThreadDumps.appendAllThreads(out, attribution, serverThread.getId(), threadNotes());
        })) {
            out.append("Thread snapshot skipped: another snapshot is running or completed less than 30s ago.\n");
        }
    }

    private void logSummary(String reason) {
        String text = status();
        MinefedDiagnostics.LOGGER.info("Summary ({}):\n{}", reason, text);
        StringBuilder out = new StringBuilder();
        out.append("---- ").append(TIME.format(Instant.now())).append(" ").append(reason).append(", up since ")
                .append(TIME.format(Instant.ofEpochMilli(startedMillis))).append('\n').append(text).append('\n');
        appendBlockingCalls(out, false);
        writer.appendSummary(out.toString());
    }

    // ------------------------------------------------------------------ helpers

    private boolean shouldLog(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long last = lastLog.get(key);
        if (last != null && now - last < intervalMs) return false;
        if (lastLog.size() > 2_000) lastLog.clear();
        lastLog.put(key, now);
        return true;
    }

    private boolean cooldownPassed(String key) {
        long now = System.currentTimeMillis();
        Long last = lastReport.get(key);
        if (last != null && now - last < config.reportCooldownMs) return false;
        lastReport.put(key, now);
        return true;
    }

    void internalError(String where, Throwable t) {
        internalErrors.increment();
        if (shouldLog("error:" + where, 60_000)) MinefedDiagnostics.LOGGER.error("Diagnostics error in {}", where, t);
    }

    private static String safe(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return "(unavailable: " + e + ")";
        }
    }
}
