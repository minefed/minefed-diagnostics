package team.minefed.diagnostics;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinefedDiagnostics implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("MinefedDiagnostics");

    private static volatile DiagnosticsService service;

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DiagnosticsConfig config = DiagnosticsConfig.load(FabricLoader.getInstance().getConfigDir().resolve("minefed-diagnostics.properties"));
            DiagnosticsService created = new DiagnosticsService(config, server,
                    server.getRunDirectory().toPath().resolve("logs").resolve("minefed-diagnostics"));
            service = created;
            created.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DiagnosticsService s = service;
            if (s != null) s.beginStopping();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DiagnosticsService s = service;
            service = null;
            if (s != null) s.stop();
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            DiagnosticsService s = service;
            if (s != null) s.onWorldUnload(world);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DiagnosticsService s = service;
            if (s != null) s.onPlayerLeft(handler.player.getUuid());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DiagnosticsCommand.register(dispatcher, () -> service));
    }
}
