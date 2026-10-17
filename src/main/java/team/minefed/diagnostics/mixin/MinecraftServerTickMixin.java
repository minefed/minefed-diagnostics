package team.minefed.diagnostics.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import team.minefed.diagnostics.Hooks;

/** Includes Fabric START/END listeners inside tickWorlds, independent of listener registration order. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void minefedDiagnostics$tickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) { Hooks.tick(true); }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void minefedDiagnostics$tickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) { Hooks.tick(false); }
}
