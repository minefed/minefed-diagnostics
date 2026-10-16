package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttributionAndProfileTest {
    private static StackTraceElement frame(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 1);
    }

    private static ModAttribution attribution() {
        ModAttribution attribution = new ModAttribution();
        attribution.setPackages(Map.of(
                "org.mtr.mod.data", "mtr",
                "org.mtr", "mtr",
                "de.bluecolored.bluemap.core", "bluemap",
                "team.minefed.diagnostics", ModAttribution.SELF,
                "com.google.shaded", "somemod"));
        return attribution;
    }

    @Test
    void attributesByPackageMixinHandlerAndPlatform() {
        ModAttribution a = attribution();
        assertEquals("mtr", a.modOf(frame("org.mtr.mod.data.RailwayData", "simulate")));
        assertEquals("mtr", a.modOf(frame("org.mtr.mod.Unknown", "x")), "parent package fallback");
        assertEquals("lithium", a.modOf(frame("net.minecraft.class_3215", "handler$zza000$lithium$preTick")));
        assertEquals("fabric-lifecycle-events-v1",
                a.modOf(frame("net.minecraft.server.MinecraftServer", "modifyReturnValue$abc123$fabric-lifecycle-events-v1$x")));
        assertEquals(ModAttribution.VANILLA, a.modOf(frame("net.minecraft.class_3898", "method_17225")));
        assertEquals(ModAttribution.VANILLA, a.modOf(frame("java.util.concurrent.CompletableFuture", "join")));
        assertEquals(ModAttribution.VANILLA, a.modOf(frame("com.google.common.collect.Lists", "x")));
        assertNull(a.modOf(frame("com.example.Unknown", "x")));
        assertFalse(a.isModFrame(frame("team.minefed.diagnostics.Hooks", "beginSyncWait")), "own frames are not culprits");
    }

    @Test
    void culpritIsInnermostModFrame() {
        ModAttribution a = attribution();
        StackTraceElement[] stack = {
                frame("java.lang.Thread", "sleep"),
                frame("net.minecraft.class_3215", "method_12121"),
                frame("org.mtr.mod.data.RailwayData", "simulate"),
                frame("de.bluecolored.bluemap.core.Map", "render"),
                frame("net.minecraft.server.MinecraftServer", "method_3748")
        };
        assertEquals("mtr", a.culprit(stack));
        assertNull(a.culprit(new StackTraceElement[]{frame("net.minecraft.class_1", "a")}));
    }

    @Test
    void profileRanksStacksAndCulprits() {
        StackAggregator profile = new StackAggregator(attribution());
        StackTraceElement[] mtr = {frame("org.mtr.mod.data.RailwayData", "simulate"), frame("net.minecraft.server.MinecraftServer", "tick")};
        StackTraceElement[] vanilla = {frame("net.minecraft.class_1", "a"), frame("net.minecraft.server.MinecraftServer", "tick")};
        for (int i = 0; i < 7; i++) profile.add(mtr, "RUNNABLE", null);
        for (int i = 0; i < 3; i++) profile.add(vanilla, "WAITING", "java.util.concurrent.CompletableFuture$Signaller");

        assertEquals(10, profile.samples());
        assertEquals("mtr", profile.topCulprit());
        assertEquals(7, profile.topStacks(1).get(0).count);
        assertEquals("net.minecraft.server.MinecraftServer.tick", profile.topInclusiveFrames(1).get(0).getKey());
        assertEquals(10, profile.topInclusiveFrames(1).get(0).getValue());

        StringBuilder out = new StringBuilder();
        profile.render(out, 2, 5);
        String text = out.toString();
        assertTrue(text.contains(" 70.0%  mtr"), text);
        assertTrue(text.contains("CompletableFuture$Signaller"), text);
        assertTrue(text.contains("[mtr]"), text);
    }

    @Test
    void indexesPackagesOfModRoot(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("org/example/mod/sub"));
        Files.createDirectories(root.resolve("META-INF/versions/9"));
        Files.writeString(root.resolve("org/example/mod/Main.class"), "");
        Files.writeString(root.resolve("org/example/mod/sub/Util.class"), "");
        Files.writeString(root.resolve("META-INF/versions/9/module-info.class"), "");
        Map<String, String> index = ModAttribution.newIndex();
        ModAttribution.indexRoot("example", root, index);
        ModAttribution.indexRoot("minecraft", root, index);
        assertEquals(Map.of("org.example.mod", "example", "org.example.mod.sub", "example"), index);
    }

    @Test
    void callerKeyPrefersModFrames() {
        BlockingCallTracker tracker = new BlockingCallTracker("test", attribution());
        StackTraceElement[] stack = {
                frame("java.lang.Thread", "getStackTrace"),
                frame("team.minefed.diagnostics.Hooks", "endSyncWait"),
                frame("net.minecraft.class_3215", "method_12121"),
                frame("org.mtr.mod.data.RailwayData", "checkRail"),
                frame("org.mtr.mod.data.RailwayData", "simulate"),
        };
        assertEquals("org.mtr.mod.data.RailwayData.checkRail:1 <- org.mtr.mod.data.RailwayData.simulate:1", tracker.callerKey(stack));
        BlockingCallTracker.CallerStats stats = tracker.record(stack, 5_000_000, "overworld [1, 2]");
        tracker.record(stack, 9_000_000, "overworld [3, 4]");
        assertEquals("mtr", stats.mod);
        assertEquals(2, stats.count.sum());
        assertEquals(9_000_000, stats.maxNanos.get());
        assertEquals("overworld [3, 4]", stats.lastDetail);

        StackTraceElement[] vanillaOnly = {frame("java.lang.Thread", "getStackTrace"), frame("net.minecraft.class_1", "a")};
        assertEquals("net.minecraft.class_1.a:1", tracker.callerKey(vanillaOnly));
    }
}
