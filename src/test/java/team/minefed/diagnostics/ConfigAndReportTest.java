package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigAndReportTest {
    @Test
    void invalidValuesFallBackAndRangesAreClamped() {
        Properties p = new Properties();
        p.setProperty("stall.thresholdMs", "abc");
        p.setProperty("stall.sampleIntervalMs", "1");
        p.setProperty("stall.firstDumpMs", "10");
        p.setProperty("chunkSend.resetStuckAcks", "TRUE");
        DiagnosticsConfig config = DiagnosticsConfig.fromProperties(p);
        assertEquals(1000, config.stallThresholdMs);
        assertEquals(250, config.stallSampleIntervalMs);
        assertEquals(1000, config.stallFirstDumpMs, "a dump cannot come before the stall threshold");
        assertTrue(config.chunkSendResetStuckAcks);
        assertFalse(DiagnosticsConfig.defaultsOnly().chunkSendResetStuckAcks, "recovery is opt-in");
    }

    @Test
    void legacyFastSamplingCannotBypassSafetyFloor() {
        Properties p = new Properties();
        p.setProperty("stall.sampleIntervalMs", "50");
        assertEquals(250, DiagnosticsConfig.fromProperties(p).stallSampleIntervalMs);
        assertEquals(250, DiagnosticsConfig.defaultsOnly().stallSampleIntervalMs);
    }

    @Test
    void loadWritesMissingDefaultsAndKeepsUserValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config/minefed-diagnostics.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "syncLoad.warnMs=250\n");
        DiagnosticsConfig config = DiagnosticsConfig.load(file);
        assertEquals(250, config.syncLoadWarnMs);
        String written = Files.readString(file);
        assertTrue(written.contains("syncLoad.warnMs=250"));
        assertTrue(written.contains("stall.thresholdMs=1000"));
    }

    @Test
    void reportWriterKeepsNewestReportsOnly(@TempDir Path dir) throws Exception {
        ReportWriter writer = new ReportWriter(dir, 3);
        Files.writeString(dir.resolve("unrelated.txt"), "keep me");
        for (int i = 0; i < 5; i++) {
            Path file = writer.write("server-stall", "report " + i);
            assertTrue(Files.exists(file));
        }
        writer.appendSummary("one\n");
        writer.appendSummary("two\n");
        List<String> reports;
        try (Stream<Path> files = Files.list(dir)) {
            reports = files.map(f -> f.getFileName().toString()).filter(n -> n.contains("server-stall")).sorted().toList();
        }
        assertEquals(3, reports.size());
        assertEquals("report 4", Files.readString(dir.resolve(reports.get(2))));
        assertTrue(Files.exists(dir.resolve("unrelated.txt")));
        assertEquals("one\ntwo\n", Files.readString(dir.resolve("summary.log")));
    }

    @Test
    void threadGroupNamesDropCounters() {
        assertEquals("Worker-Main", Hooks.threadGroupName("Worker-Main-12"));
        assertEquals("BlueMap-Render-Thread", Hooks.threadGroupName("BlueMap-Render-Thread-#3"));
        assertEquals("Server thread", Hooks.threadGroupName("Server thread"));
    }
}
