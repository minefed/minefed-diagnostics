package team.minefed.diagnostics.mixin;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.network.ChunkDataSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkDataSender.class)
public interface ChunkDataSenderAccessor {
    @Accessor("chunks")
    LongSet minefedDiagnostics$chunks();

    @Accessor("unacknowledgedBatches")
    int minefedDiagnostics$unacknowledgedBatches();

    @Accessor("unacknowledgedBatches")
    void minefedDiagnostics$setUnacknowledgedBatches(int value);

    @Accessor("maxUnacknowledgedBatches")
    int minefedDiagnostics$maxUnacknowledgedBatches();

    @Accessor("desiredBatchSize")
    float minefedDiagnostics$desiredBatchSize();

    @Accessor("pending")
    float minefedDiagnostics$pending();
}
