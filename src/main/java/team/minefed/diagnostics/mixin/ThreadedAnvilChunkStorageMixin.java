package team.minefed.diagnostics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Either;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import team.minefed.diagnostics.ChunkTaskTracker.Stage;
import team.minefed.diagnostics.Hooks;

/**
 * Tracks the futures of the three chunk pipeline stages. ModernFix's legacy scheduling replaces the
 * method that calls these, but still goes through them, so tracking stays complete.
 */
@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageMixin {
    @Shadow
    @Final
    ServerWorld world;

    @ModifyReturnValue(method = "loadChunk", at = @At("RETURN"))
    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> minefedDiagnostics$trackLoad(
            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future, @Local(argsOnly = true) ChunkPos pos) {
        Hooks.trackChunkTask(this.world, pos, Stage.LOAD, "", future);
        return future;
    }

    @ModifyReturnValue(method = "upgradeChunk", at = @At("RETURN"))
    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> minefedDiagnostics$trackGenerate(
            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future, @Local(argsOnly = true) ChunkHolder holder,
            @Local(argsOnly = true) ChunkStatus status) {
        Hooks.trackChunkTask(this.world, holder.getPos(), Stage.GENERATE, status.toString(), future);
        return future;
    }

    @ModifyReturnValue(method = "convertToFullChunk", at = @At("RETURN"))
    private CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> minefedDiagnostics$trackFull(
            CompletableFuture<Either<Chunk, ChunkHolder.Unloaded>> future, @Local(argsOnly = true) ChunkHolder holder) {
        Hooks.trackChunkTask(this.world, holder.getPos(), Stage.FULL, "", future);
        return future;
    }
}
