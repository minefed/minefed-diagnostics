package team.minefed.diagnostics.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.Queue;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTaskPrioritySystem;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface ThreadedAnvilChunkStorageAccessor {
    @Accessor("chunkTaskPrioritySystem")
    ChunkTaskPrioritySystem minefedDiagnostics$chunkTaskPrioritySystem();

    @Accessor("chunksToUnload")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> minefedDiagnostics$chunksToUnload();

    @Accessor("unloadTaskQueue")
    Queue<Runnable> minefedDiagnostics$unloadTaskQueue();
}
