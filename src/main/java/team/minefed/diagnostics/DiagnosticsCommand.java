package team.minefed.diagnostics;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/** {@code /mfdiag} for operators and the console. */
final class DiagnosticsCommand {
    private DiagnosticsCommand() {}

    static void register(CommandDispatcher<ServerCommandSource> dispatcher, Supplier<DiagnosticsService> service) {
        dispatcher.register(literal("mfdiag")
                .requires(source -> source.hasPermissionLevel(4))
                .executes(ctx -> reply(ctx, service, DiagnosticsService::status))
                .then(literal("status").executes(ctx -> reply(ctx, service, DiagnosticsService::status)))
                .then(literal("chunks").executes(ctx -> reply(ctx, service, DiagnosticsService::chunksText)))
                .then(literal("players").executes(ctx -> reply(ctx, service, DiagnosticsService::playersText)))
                .then(literal("callers").executes(ctx -> reply(ctx, service, DiagnosticsService::callersText)))
                .then(literal("reset").executes(ctx -> reply(ctx, service, s -> {
                    s.resetStatistics();
                    return "Statistics cleared";
                })))
                .then(literal("dump").executes(ctx -> {
                    DiagnosticsService s = service.get();
                    if (s == null) return notRunning(ctx);
                    ServerCommandSource source = ctx.getSource();
                    source.sendFeedback(() -> Text.literal("Writing snapshot report..."), false);
                    s.dumpNow(file -> source.getServer().execute(() -> source.sendFeedback(
                            () -> Text.literal(file == null ? "Snapshot failed, see the server log" : "Snapshot written to " + file), false)));
                    return 1;
                }))
                .then(literal("selftest").then(literal("stall").then(argument("milliseconds", IntegerArgumentType.integer(1, 30_000))
                        .executes(ctx -> {
                            int ms = IntegerArgumentType.getInteger(ctx, "milliseconds");
                            MinefedDiagnostics.LOGGER.warn("Self test: blocking the server thread for {}ms on request of {}", ms,
                                    ctx.getSource().getName());
                            selfTestStall(ms);
                            ctx.getSource().sendFeedback(() -> Text.literal("Blocked the server thread for " + ms + "ms"), true);
                            return 1;
                        })))));
    }

    /** A recognisable frame for the self test, so its report can be checked for this method name. */
    private static void selfTestStall(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            try {
                Thread.sleep(Math.max(1, (end - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static int reply(CommandContext<ServerCommandSource> ctx, Supplier<DiagnosticsService> service,
                             Function<DiagnosticsService, String> text) {
        DiagnosticsService s = service.get();
        if (s == null) return notRunning(ctx);
        String message = text.apply(s);
        ctx.getSource().sendFeedback(() -> Text.literal(message), false);
        return 1;
    }

    private static int notRunning(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendError(Text.literal("Minefed Diagnostics is not running"));
        return 0;
    }
}
