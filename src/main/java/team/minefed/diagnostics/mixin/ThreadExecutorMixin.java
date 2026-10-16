package team.minefed.diagnostics.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.util.thread.ThreadExecutor;
import org.spongepowered.asm.mixin.Mixin;
import team.minefed.diagnostics.Hooks;

/**
 * {@code runTasks} on a world's chunk executor is where the server thread blocks until a chunk it asked
 * for is ready (vanilla and Lithium getChunk) or until chunks are written during a flushing save.
 */
@Mixin(ThreadExecutor.class)
public abstract class ThreadExecutorMixin {
    @WrapMethod(method = "runTasks(Ljava/util/function/BooleanSupplier;)V")
    private void minefedDiagnostics$timeChunkWait(BooleanSupplier stopCondition, Operation<Void> original) {
        if (!((Object) this instanceof ServerChunkManager.MainThreadExecutor)) {
            original.call(stopCondition);
            return;
        }
        Hooks.SyncWait wait = Hooks.beginSyncWait();
        try {
            original.call(stopCondition);
        } finally {
            Hooks.endSyncWait(wait);
        }
    }
}
