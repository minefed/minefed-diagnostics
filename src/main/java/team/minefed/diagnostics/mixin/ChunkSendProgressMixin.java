package team.minefed.diagnostics.mixin;

import java.util.List;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import team.minefed.diagnostics.ChunkSendProgress;

@Mixin(ChunkDataSender.class)
public abstract class ChunkSendProgressMixin implements ChunkSendProgress {
    @Unique private long minefedDiagnostics$activity;

    @Inject(method = "makeBatch", at = @At("RETURN"), require = 0)
    private void minefedDiagnostics$batch(ThreadedAnvilChunkStorage storage, ChunkPos pos,
            CallbackInfoReturnable<List<WorldChunk>> cir) {
        List<WorldChunk> batch = cir.getReturnValue();
        if (batch != null) minefedDiagnostics$activity += batch.size();
    }

    @Inject(method = "onAcknowledgeChunks", at = @At("RETURN"), require = 0)
    private void minefedDiagnostics$ack(float desiredBatchSize, CallbackInfo ci) { minefedDiagnostics$activity++; }

    @Override public long minefedDiagnostics$progress() { return minefedDiagnostics$activity; }
}
