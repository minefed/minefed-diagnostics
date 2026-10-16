package team.minefed.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maps stack frames to the mod that owns them, so that a report can say "MTR held the server thread"
 * instead of listing raw frames. Two sources are used: the packages found in each mod's jar, and the
 * mod id that Fabric's Mixin embeds in injected handler names ({@code handler$abc000$lithium$tick}).
 */
public final class ModAttribution {
    public static final String VANILLA = "minecraft";
    public static final String SELF = "minefed-diagnostics";

    private static final Pattern MIXIN_HANDLER = Pattern.compile(
            "^(?:handler|redirect|modify|wrapOperation|wrapWithCondition|modifyExpressionValue|modifyReturnValue|"
                    + "modifyReceiver|localvar|wrapMethod|constant|args)\\$[0-9a-z]+\\$([0-9a-z_\\-]+)\\$");
    /** Checked before the package index so that a mod shading these packages cannot claim them. */
    private static final String[] CORE_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "net.minecraft.", "com.mojang.", "org.spongepowered.",
            "net.fabricmc.loader.", "com.llamalad7.mixinextras.", "org.objectweb.asm."
    };
    /** Libraries that ship with the server; only used when no mod jar contains the package. */
    private static final String[] LIBRARY_PREFIXES = {
            "it.unimi.dsi.", "com.google.", "org.slf4j.", "org.apache.logging.", "io.netty."
    };
    private static final Set<String> IGNORED_MOD_IDS = Set.of("java", VANILLA, "fabricloader", "mixinextras");

    private volatile Map<String, String> packageToMod = Map.of();

    public void setPackages(Map<String, String> packages) {
        this.packageToMod = Map.copyOf(packages);
    }

    public int packageCount() {
        return packageToMod.size();
    }

    /** Returns the owning mod id, {@link #VANILLA} for platform code, or {@code null} if unknown. */
    public String modOf(StackTraceElement frame) {
        Matcher handler = MIXIN_HANDLER.matcher(frame.getMethodName());
        if (handler.find()) return handler.group(1);
        return modOfClass(frame.getClassName());
    }

    public String modOfClass(String className) {
        for (String prefix : CORE_PREFIXES) {
            if (className.startsWith(prefix)) return VANILLA;
        }
        Map<String, String> packages = this.packageToMod;
        for (String p = packageOf(className); !p.isEmpty(); p = packageOf(p)) {
            String mod = packages.get(p);
            if (mod != null) return mod;
        }
        for (String prefix : LIBRARY_PREFIXES) {
            if (className.startsWith(prefix)) return VANILLA;
        }
        return null;
    }

    /** True for frames that belong to a mod other than vanilla/the JDK/this mod. */
    public boolean isModFrame(StackTraceElement frame) {
        String mod = modOf(frame);
        return mod != null && !mod.equals(VANILLA) && !mod.equals(SELF);
    }

    /** The innermost mod that appears in the stack, or {@code null} when the stack is pure vanilla. */
    public String culprit(StackTraceElement[] frames) {
        for (StackTraceElement frame : frames) {
            if (isModFrame(frame)) return modOf(frame);
        }
        return null;
    }

    public static String frameLabel(StackTraceElement frame) {
        return frame.getClassName() + "." + frame.getMethodName();
    }

    static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /** Collects the package of every class file below a mod root. The first owner of a package wins. */
    public static void indexRoot(String modId, Path root, Map<String, String> into) {
        if (IGNORED_MOD_IDS.contains(modId)) return;
        try (Stream<Path> files = Files.walk(root)) {
            files.forEach(path -> {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (!name.endsWith(".class") || name.equals("module-info.class") || name.equals("package-info.class")) return;
                Path parent = root.relativize(path).getParent();
                if (parent == null) return;
                String pkg = parent.toString().replace('\\', '.').replace('/', '.');
                if (pkg.startsWith("META-INF")) return;
                into.putIfAbsent(pkg, modId);
            });
        } catch (IOException | RuntimeException e) {
            MinefedDiagnostics.LOGGER.debug("Could not index classes of {} at {}", modId, root, e);
        }
    }

    public static Map<String, String> newIndex() {
        return new HashMap<>();
    }
}
