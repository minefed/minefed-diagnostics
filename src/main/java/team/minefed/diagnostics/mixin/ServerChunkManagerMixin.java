package team.minefed.diagnostics.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import team.minefed.diagnostics.Hooks;

/**
 * Records which chunk the server thread asked for (so a following blocking wait can name it) and times
 * getChunk calls made from other threads, which block until the server thread serves them.
 * Main-thread cache hits use only a primitive/reference argument observer. Exception-safe wrappers
 * cover the off-thread branches of Minecraft 1.20.4 and the pinned Lithium 0.12.1 implementation.
 */
@Mixin(value = ServerChunkManager.class, priority = 1500)
public abstract class ServerChunkManagerMixin {
    @Shadow
    @Final
    Thread serverThread;

    @Shadow
    @Final
    ServerWorld world;

    @ModifyVariable(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private ChunkStatus minefedDiagnostics$observeMainThread(ChunkStatus status,
            @Local(argsOnly = true, ordinal = 0) int x,
            @Local(argsOnly = true, ordinal = 1) int z,
            @Local(argsOnly = true) boolean create) {
        if (Thread.currentThread() == this.serverThread) {
            try { Hooks.mainThreadRequest(this.world, x, z, status, create); }
            catch (RuntimeException | LinkageError ignored) {
                // Observation failures must not prevent the original chunk request.
            }
        }
        return status;
    }

    // Vanilla 1.20.4 schedules a request only in its off-thread branch. Its first join below owns
    // normal/exceptional completion cleanup; submission failures must be cleaned here instead.
    @WrapOperation(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            require = 0)
    private <T> CompletableFuture<T> minefedDiagnostics$submitVanilla(Supplier<T> supplier, Executor executor,
            Operation<CompletableFuture<T>> original,
            @Local(argsOnly = true, ordinal = 0) int x,
            @Local(argsOnly = true, ordinal = 1) int z,
            @Local(argsOnly = true) ChunkStatus status,
            @Local(argsOnly = true) boolean create) {
        Thread current = Thread.currentThread();
        minefedDiagnostics$beginOffThread(current, x, z, status, create);
        try { return original.call(supplier, executor); }
        catch (RuntimeException | Error failure) {
            minefedDiagnostics$endOffThread(current);
            throw failure;
        }
    }

    @WrapOperation(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;join()Ljava/lang/Object;", ordinal = 0),
            require = 0)
    private Object minefedDiagnostics$joinVanilla(CompletableFuture<?> future, Operation<Object> original) {
        try { return original.call(future); }
        finally { minefedDiagnostics$endOffThread(Thread.currentThread()); }
    }

    // This private helper is added by the pinned Lithium mixin and does not exist in vanilla.
    // Its name is not a Minecraft mapping. Optional matching preserves vanilla compatibility.
    @WrapMethod(method = "getChunkOffThread", remap = false, require = 0)
    private Chunk minefedDiagnostics$getChunkOffThreadLithium(int x, int z, ChunkStatus status, boolean create,
                                                             Operation<Chunk> original) {
        Thread current = Thread.currentThread();
        minefedDiagnostics$beginOffThread(current, x, z, status, create);
        try { return original.call(x, z, status, create); }
        finally { minefedDiagnostics$endOffThread(current); }
    }

    @Unique
    private void minefedDiagnostics$beginOffThread(Thread current, int x, int z, ChunkStatus status, boolean create) {
        if (current == this.serverThread) return;
        try {
            Hooks.beginOffThread(current, this.world, x, z, status, create);
        } catch (RuntimeException | LinkageError ignored) {
            // Observation failures must not prevent the original chunk request.
        }
    }

    @Unique
    private void minefedDiagnostics$endOffThread(Thread current) {
        if (current == this.serverThread) return;
        try { Hooks.endOffThread(current); }
        catch (RuntimeException | LinkageError ignored) {
            // Preserve the original result or exception if diagnostics itself fails.
        }
    }
}
