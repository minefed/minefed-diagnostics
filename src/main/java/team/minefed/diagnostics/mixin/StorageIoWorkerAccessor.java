package team.minefed.diagnostics.mixin;

import java.util.Map;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StorageIoWorker.class)
public interface StorageIoWorkerAccessor {
    @Accessor("executor")
    TaskExecutor<?> minefedDiagnostics$executor();

    /** Chunk data waiting to be written to region files. */
    @Accessor("results")
    Map<ChunkPos, ?> minefedDiagnostics$results();
}
