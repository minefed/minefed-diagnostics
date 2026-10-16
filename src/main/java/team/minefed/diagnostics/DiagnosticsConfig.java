package team.minefed.diagnostics;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Thresholds for every detector. Values are read once at server start from
 * {@code config/minefed-diagnostics.properties}; missing keys are written back with defaults.
 */
public final class DiagnosticsConfig {
    /** How often the watchdog thread wakes up. */
    public final long pollIntervalMs;
    /** Time since the last finished tick before the server thread is considered stalled. */
    public final long stallThresholdMs;
    /** Stalls shorter than this are only counted, not written as report files. */
    public final long stallReportMinMs;
    /** Interval between server thread stack samples while stalled. */
    public final long stallSampleIntervalMs;
    /** A full thread dump is written while the stall is still in progress at these points. */
    public final long stallFirstDumpMs;
    public final long stallSecondDumpMs;
    public final int stallMaxSamples;
    /** Main-thread blocking chunk loads longer than this are recorded with their caller. */
    public final long syncLoadWarnMs;
    /** Off-thread getChunk calls waiting longer than this are recorded with their caller. */
    public final long offThreadWarnMs;
    /** An in-flight chunk load/generation step older than this is a chunk pipeline stall. */
    public final long chunkTaskStallMs;
    /** A player with pending chunks and no send progress for this long is a chunk send stall. */
    public final long chunkSendStallMs;
    /** Opt-in recovery: clear a lost client chunk-batch acknowledgement after this long. */
    public final boolean chunkSendResetStuckAcks;
    public final long chunkSendResetAfterMs;
    /** The watchdog oversleeping by this much means the whole JVM was frozen. */
    public final long jvmFreezeWarnMs;
    /** Minimum time between two chunk stall reports of the same kind. */
    public final long reportCooldownMs;
    /** Number of report files kept in logs/minefed-diagnostics. */
    public final int reportRetain;
    public final long summaryIntervalMs;

    private DiagnosticsConfig(Map<String, String> values) {
        pollIntervalMs = clamp(parseLong(values, "watchdog.pollIntervalMs"), 10, 1000);
        stallThresholdMs = clamp(parseLong(values, "stall.thresholdMs"), 200, 600_000);
        stallReportMinMs = clamp(parseLong(values, "stall.reportMinMs"), stallThresholdMs, 600_000);
        stallSampleIntervalMs = clamp(parseLong(values, "stall.sampleIntervalMs"), 10, 5_000);
        stallFirstDumpMs = clamp(parseLong(values, "stall.firstDumpMs"), stallThresholdMs, 600_000);
        stallSecondDumpMs = clamp(parseLong(values, "stall.secondDumpMs"), stallFirstDumpMs, 600_000);
        stallMaxSamples = (int) clamp(parseLong(values, "stall.maxSamples"), 10, 100_000);
        syncLoadWarnMs = clamp(parseLong(values, "syncLoad.warnMs"), 1, 600_000);
        offThreadWarnMs = clamp(parseLong(values, "offThread.warnMs"), 1, 600_000);
        chunkTaskStallMs = clamp(parseLong(values, "chunkTask.stallMs"), 1_000, 3_600_000);
        chunkSendStallMs = clamp(parseLong(values, "chunkSend.stallMs"), 1_000, 3_600_000);
        chunkSendResetStuckAcks = Boolean.parseBoolean(values.get("chunkSend.resetStuckAcks"));
        chunkSendResetAfterMs = clamp(parseLong(values, "chunkSend.resetAfterMs"), chunkSendStallMs, 3_600_000);
        jvmFreezeWarnMs = clamp(parseLong(values, "jvmFreeze.warnMs"), 100, 600_000);
        reportCooldownMs = clamp(parseLong(values, "report.cooldownMs"), 0, 86_400_000);
        reportRetain = (int) clamp(parseLong(values, "report.retain"), 1, 100_000);
        summaryIntervalMs = clamp(parseLong(values, "summary.intervalMinutes"), 1, 1_440) * 60_000L;
    }

    static Map<String, String> defaults() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("watchdog.pollIntervalMs", "50");
        d.put("stall.thresholdMs", "1000");
        d.put("stall.reportMinMs", "3000");
        d.put("stall.sampleIntervalMs", "50");
        d.put("stall.firstDumpMs", "5000");
        d.put("stall.secondDumpMs", "40000");
        d.put("stall.maxSamples", "2400");
        d.put("syncLoad.warnMs", "100");
        d.put("offThread.warnMs", "500");
        d.put("chunkTask.stallMs", "20000");
        d.put("chunkSend.stallMs", "10000");
        d.put("chunkSend.resetStuckAcks", "false");
        d.put("chunkSend.resetAfterMs", "30000");
        d.put("jvmFreeze.warnMs", "1000");
        d.put("report.cooldownMs", "300000");
        d.put("report.retain", "200");
        d.put("summary.intervalMinutes", "10");
        return d;
    }

    public static DiagnosticsConfig defaultsOnly() {
        return new DiagnosticsConfig(defaults());
    }

    /** Values that are missing or unparsable fall back to the defaults. */
    public static DiagnosticsConfig fromProperties(Properties properties) {
        Map<String, String> merged = defaults();
        for (String key : merged.keySet()) {
            String value = properties.getProperty(key);
            if (value != null && isValid(key, value.trim())) merged.put(key, value.trim());
        }
        return new DiagnosticsConfig(merged);
    }

    public static DiagnosticsConfig load(Path file) {
        Properties properties = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                MinefedDiagnostics.LOGGER.warn("Could not read {}, using defaults", file, e);
            }
        }
        boolean missing = false;
        for (Map.Entry<String, String> entry : defaults().entrySet()) {
            if (!properties.containsKey(entry.getKey())) {
                properties.setProperty(entry.getKey(), entry.getValue());
                missing = true;
            }
        }
        if (missing) {
            try {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    properties.store(writer, "Minefed Diagnostics. Times are milliseconds. Restart the server to apply.");
                }
            } catch (IOException e) {
                MinefedDiagnostics.LOGGER.warn("Could not write {}", file, e);
            }
        }
        return fromProperties(properties);
    }

    private static boolean isValid(String key, String value) {
        if (key.equals("chunkSend.resetStuckAcks")) return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long parseLong(Map<String, String> values, String key) {
        return Long.parseLong(values.get(key));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
