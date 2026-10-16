package team.minefed.diagnostics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import team.minefed.diagnostics.Hooks;

/**
 * Records which chunk the server thread asked for (so a following blocking wait can name it) and times
 * getChunk calls made from other threads, which block until the server thread serves them.
 * The handlers avoid CallbackInfo allocation because getChunk is very hot. Lithium overwrites this
 * method; injectors are applied after overwrites, so its body is covered as well.
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
            at = @At("HEAD"), argsOnly = true)
    private ChunkStatus minefedDiagnostics$beginGetChunk(ChunkStatus status,
                                                         @Local(argsOnly = true, ordinal = 0) int x,
                                                         @Local(argsOnly = true, ordinal = 1) int z,
                                                         @Local(argsOnly = true) boolean create) {
        Thread current = Thread.currentThread();
        if (current == this.serverThread) Hooks.mainThreadRequest(this.world, x, z, status, create);
        else Hooks.beginOffThread(current, this.world, x, z, status, create);
        return status;
    }

    @ModifyReturnValue(method = "getChunk(IILnet/minecraft/world/chunk/ChunkStatus;Z)Lnet/minecraft/world/chunk/Chunk;",
            at = @At("RETURN"))
    private Chunk minefedDiagnostics$endGetChunk(Chunk chunk) {
        Thread current = Thread.currentThread();
        if (current != this.serverThread) Hooks.endOffThread(current);
        return chunk;
    }
}
