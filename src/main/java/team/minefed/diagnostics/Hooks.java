package team.minefed.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * Static entry points called from mixins. Everything here must be cheap on the fast path and must never
 * throw into game code: when the service is not running the hooks return immediately.
 */
public final class Hooks {
    public static final int HOOK_GET_CHUNK = 1;
    public static final int HOOK_OFF_THREAD = 1 << 1;
    public static final int HOOK_SYNC_WAIT = 1 << 2;
    public static final int HOOK_LOAD = 1 << 3;
    public static final int HOOK_GENERATE = 1 << 4;
    public static final int HOOK_FULL = 1 << 5;
    public static final String[] HOOK_NAMES = {"getChunk", "offThreadGetChunk", "syncChunkWait", "loadChunk",
            "upgradeChunk", "convertToFullChunk"};

    private static volatile DiagnosticsService service;
    private static volatile int seenHooks;

    // Written and read only on the server thread.
    private static ServerWorld lastWorld;
    private static int lastX;
    private static int lastZ;
    private static ChunkStatus lastStatus;
    private static boolean lastCreate;
    private static int syncDepth;

    private static volatile SyncWait currentSyncWait;
    private static final ConcurrentHashMap<Thread, OffThreadWait> OFF_THREAD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> OFF_THREAD_CALLS = new ConcurrentHashMap<>();

    /** A wait of the server thread inside the chunk executor. */
    public record SyncWait(String world, int x, int z, String status, boolean create, long startNanos, int depth) {
        public String describe() {
            return world + " [" + x + ", " + z + "] status=" + status + (create ? "" : " (no create)");
        }
    }

    /** A getChunk call from another thread that waits for the server thread. */
    public record OffThreadWait(Thread thread, String world, int x, int z, String status, boolean create, long startNanos) {
        public String describe() {
            return "\"" + thread.getName() + "\" -> " + world + " [" + x + ", " + z + "] status=" + status
                    + (create ? "" : " (no create)");
        }
    }

    private Hooks() {}

    static void install(DiagnosticsService s) {
        service = s;
    }

    static void uninstall() {
        service = null;
        currentSyncWait = null;
        OFF_THREAD.clear();
    }

    private static void seen(int hook) {
        if ((seenHooks & hook) == 0) seenHooks |= hook;
    }

    public static int seenHooks() {
        return seenHooks;
    }

    public static void mainThreadRequest(ServerWorld world, int x, int z, ChunkStatus status, boolean create) {
        lastWorld = world;
        lastX = x;
        lastZ = z;
        lastStatus = status;
        lastCreate = create;
        if ((seenHooks & HOOK_GET_CHUNK) == 0) seen(HOOK_GET_CHUNK);
    }

    public static SyncWait beginSyncWait() {
        DiagnosticsService s = service;
        if (s == null || !s.isServerThread()) return null;
        seen(HOOK_SYNC_WAIT);
        ServerWorld world = lastWorld;
        SyncWait wait = new SyncWait(world == null ? "?" : worldName(world), lastX, lastZ,
                String.valueOf(lastStatus), lastCreate, System.nanoTime(), syncDepth);
        if (syncDepth++ == 0) currentSyncWait = wait;
        return wait;
    }

    public static void endSyncWait(SyncWait wait) {
        if (wait == null) return;
        if (--syncDepth <= 0) {
            syncDepth = 0;
            currentSyncWait = null;
        }
        DiagnosticsService s = service;
        if (s != null) s.onSyncWait(wait, System.nanoTime() - wait.startNanos());
    }

    public static SyncWait currentSyncWait() {
        return currentSyncWait;
    }

    public static void beginOffThread(Thread thread, ServerWorld world, int x, int z, ChunkStatus status, boolean create) {
        if (service == null) return;
        seen(HOOK_OFF_THREAD);
        OFF_THREAD.put(thread, new OffThreadWait(thread, worldName(world), x, z, String.valueOf(status), create, System.nanoTime()));
        OFF_THREAD_CALLS.computeIfAbsent(threadGroupName(thread.getName()), k -> new LongAdder()).increment();
    }

    public static void endOffThread(Thread thread) {
        OffThreadWait wait = OFF_THREAD.remove(thread);
        if (wait == null) return;
        DiagnosticsService s = service;
        if (s != null) s.onOffThreadWait(wait, System.nanoTime() - wait.startNanos());
    }

    /** Off-thread getChunk calls still waiting, oldest first. Entries of threads that died are dropped. */
    public static List<OffThreadWait> offThreadWaits() {
        List<OffThreadWait> waits = new ArrayList<>();
        for (OffThreadWait wait : OFF_THREAD.values()) {
            if (wait.thread().isAlive()) waits.add(wait);
            else OFF_THREAD.remove(wait.thread(), wait);
        }
        waits.sort(Comparator.comparingLong(OffThreadWait::startNanos));
        return waits;
    }

    public static OffThreadWait offThreadWait(Thread thread) {
        return OFF_THREAD.get(thread);
    }

    public static Map<String, LongAdder> offThreadCallsByThread() {
        return OFF_THREAD_CALLS;
    }

    public static <T> void trackChunkTask(ServerWorld world, ChunkPos pos, ChunkTaskTracker.Stage stage, String status,
                                          CompletableFuture<Either<T, net.minecraft.server.world.ChunkHolder.Unloaded>> future) {
        DiagnosticsService s = service;
        if (s == null || future == null) return;
        seen(switch (stage) {
            case LOAD -> HOOK_LOAD;
            case GENERATE -> HOOK_GENERATE;
            case FULL -> HOOK_FULL;
        });
        try {
            s.chunkTasks().track(new ChunkTaskTracker.Key(worldName(world), pos.x, pos.z, stage, status), future,
                    either -> either.right().isPresent());
        } catch (RuntimeException e) {
            s.internalError("trackChunkTask", e);
        }
    }

    public static String worldName(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    /** "Worker-Main-12" and "Worker-Main-3" are counted together. */
    static String threadGroupName(String name) {
        int end = name.length();
        while (end > 0 && (Character.isDigit(name.charAt(end - 1)) || name.charAt(end - 1) == '-' || name.charAt(end - 1) == '#')) end--;
        return end == 0 ? name : name.substring(0, end);
    }
}
