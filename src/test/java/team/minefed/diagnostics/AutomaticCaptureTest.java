package team.minefed.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomaticCaptureTest {
    private static DiagnosticsConfig config(String... pairs) {
        Properties values = new Properties();
        for (int i = 0; i < pairs.length; i += 2) values.setProperty(pairs[i], pairs[i + 1]);
        return DiagnosticsConfig.fromProperties(values);
    }

    @Test void slowWorkWindowsPreserveThresholdAndReset() {
        SlowTickDetector detector = new SlowTickDetector(20, 75);
        for (int i = 0; i < 19; i++) assertNull(detector.complete(75_000_000));
        var first = detector.complete(75_000_000);
        assertTrue(first.slow());
        assertEquals(75, first.averageMs());
        for (int i = 0; i < 19; i++) assertNull(detector.complete(10_000_000));
        var next = detector.complete(10_000_000);
        assertFalse(next.slow());
        assertEquals(10, next.maximumMs());
    }

    @Test void captureIsAutomaticBoundedAndKeepsOtherFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("operator-notes.txt"), "keep");
        var capture = new AutomaticCapture(config("capture.jfrEnabled", "false", "capture.retain", "1", "capture.cooldownMs", "10000"),
                dir, () -> "sample-marker", () -> "dump-marker", ignored -> {});
        try {
            capture.start();
            capture.awaitIdle();
            assertTrue(capture.request("server-stall", 0));
            capture.awaitIdle();
            assertFalse(capture.request("slow-ticks", 9_999_000_000L), "one shared cooldown for all causes");
            assertTrue(capture.request("watchdog-delay", 10_000_000_000L));
            capture.awaitIdle();
            try (var paths = Files.list(dir)) {
                List<Path> reports = paths.filter(p -> p.getFileName().toString().startsWith("capture-")).toList();
                assertEquals(1, reports.size());
                String text = Files.readString(reports.get(0));
                assertTrue(text.contains("watchdog-delay"));
                assertTrue(text.contains("sample-marker"));
                assertTrue(text.contains("dump-marker"));
            }
            assertEquals("keep", Files.readString(dir.resolve("operator-notes.txt")));
        } finally { capture.close(); }
        assertFalse(capture.request("after-close", 30_000_000_000L));
    }

    @Test void realRollingJfrContainsTickWorkAndExcludesMetadata(@TempDir Path dir) throws Exception {
        var capture = new AutomaticCapture(config(), dir, () -> "os sample", () -> "thread dump", ignored -> {});
        try {
            capture.start();
            capture.awaitIdle();
            assertEquals("rolling JFR active", capture.status());
            TickWindowEvent.record(new SlowTickDetector.Window(100, 90, 120, true));
            assertTrue(capture.request("slow-ticks", 0));
            capture.awaitIdle();
            Path jfr;
            try (var files = Files.list(dir)) { jfr = files.filter(p -> p.toString().endsWith(".jfr")).findFirst().orElseThrow(); }
            var events = RecordingFile.readAllEvents(jfr);
            assertTrue(events.stream().anyMatch(e -> e.getEventType().getName().equals("minefed.TickWindow")
                    && e.getDouble("averageMs") == 90));
            assertFalse(events.stream().anyMatch(e -> List.of("jdk.InitialEnvironmentVariable", "jdk.InitialSystemProperty", "jdk.JVMInformation")
                    .contains(e.getEventType().getName())));
        } finally { capture.close(); }
    }

    @Test void retentionKeepsNewestTwoWithIdenticalDetectionTimes(@TempDir Path dir) throws Exception {
        assertNewestTwoRetained(dir, List.of(Instant.parse("2026-10-17T01:02:03Z"),
                Instant.parse("2026-10-17T01:02:03Z"), Instant.parse("2026-10-17T01:02:03Z")));
    }

    @Test void retentionKeepsNewestTwoWhenWallClockMovesBack(@TempDir Path dir) throws Exception {
        assertNewestTwoRetained(dir, List.of(Instant.parse("2026-10-17T01:02:03Z"),
                Instant.parse("2026-10-17T00:02:03Z"), Instant.parse("2026-10-16T23:02:03Z")));
    }

    private void assertNewestTwoRetained(Path dir, List<Instant> detections) throws Exception {
        var capture = new AutomaticCapture(config("capture.retain", "2"), dir, () -> "", ignored -> {});
        try {
            var bases = new java.util.ArrayList<String>();
            for (int i = 0; i < detections.size(); i++) {
                String base = capture.nextCaptureBase(detections.get(i));
                bases.add(base);
                Files.writeString(dir.resolve(base + ".txt"), "report " + i);
                Files.writeString(dir.resolve(base + ".jfr"), "recording " + i);
                capture.prune();
            }
            for (int i = 0; i < bases.size(); i++) {
                assertEquals(i > 0, Files.exists(dir.resolve(bases.get(i) + ".txt")));
                assertEquals(i > 0, Files.exists(dir.resolve(bases.get(i) + ".jfr")));
            }
        } finally { capture.close(); }
    }

    @Test void retentionContinuesFilenameOrderAfterRestart(@TempDir Path dir) throws Exception {
        String old = "capture-20261017T010203456Z-" + UUID.randomUUID();
        Files.writeString(dir.resolve(old + ".txt"), "previous process");
        var capture = new AutomaticCapture(config("capture.jfrEnabled", "false", "capture.retain", "1"),
                dir, () -> "", ignored -> {});
        try {
            capture.start();
            capture.awaitIdle();
            String current = capture.nextCaptureBase(Instant.parse("2026-10-16T01:02:03Z"));
            Files.writeString(dir.resolve(current + ".txt"), "current process");
            capture.prune();
            assertTrue(Files.exists(dir.resolve(current + ".txt")));
            assertFalse(Files.exists(dir.resolve(old + ".txt")));
        } finally { capture.close(); }
    }

    @Test void disabledCaptureDoesNotCreateFiles(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("disabled");
        var capture = new AutomaticCapture(config("capture.enabled", "false"), output, () -> fail("unexpected dump"), ignored -> {});
        try {
            capture.start();
            assertFalse(capture.request("server-stall", 0));
            capture.awaitIdle();
            assertFalse(Files.exists(output));
        } finally { capture.close(); }
    }

    @Test void captureFailureDoesNotEscapeOrQueueEndlessly(@TempDir Path dir) throws Exception {
        var capture = new AutomaticCapture(config("capture.jfrEnabled", "false"), dir,
                () -> "metrics", () -> { throw new IllegalStateException("fake dump failure"); }, ignored -> {});
        try {
            capture.start();
            capture.awaitIdle();
            assertTrue(capture.request("server-stall", 0));
            capture.awaitIdle();
            try (var files = Files.list(dir)) {
                String report = Files.readString(files.filter(p -> p.toString().endsWith(".txt")).findFirst().orElseThrow());
                assertTrue(report.contains("metrics"));
                assertTrue(report.contains("UNAVAILABLE: IllegalStateException"));
            }
            assertFalse(capture.request("repeat", 1));
        } finally { capture.close(); }
    }

    @Test void prunesOnlyOwnFilesByByteLimit(@TempDir Path dir) throws Exception {
        String name = "capture-20261017T010203456Z-" + UUID.randomUUID();
        Path large = dir.resolve(name + ".jfr");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(33L * 1024 * 1024); }
        Files.writeString(dir.resolve(name + ".txt"), "report");
        Files.writeString(dir.resolve("someone-elses.jfr"), "preserve");
        var capture = new AutomaticCapture(config("capture.retainMiB", "32"), dir, () -> "", ignored -> {});
        try {
            capture.prune();
            assertFalse(Files.exists(large));
            assertFalse(Files.exists(dir.resolve(name + ".txt")));
            assertTrue(Files.exists(dir.resolve("someone-elses.jfr")));
        } finally { capture.close(); }
    }

    @Test void restartRemovesOnlyOwnedInterruptedDumps(@TempDir Path dir) throws Exception {
        Path interrupted = dir.resolve("capture-20261017T010203456Z-" + UUID.randomUUID() + ".jfr.part");
        Files.writeString(interrupted, "interrupted");
        Files.writeString(dir.resolve("operator.jfr.part"), "keep");
        var capture = new AutomaticCapture(config("capture.jfrEnabled", "false"), dir, () -> "", ignored -> {});
        try {
            capture.start();
            capture.awaitIdle();
            assertFalse(Files.exists(interrupted));
            assertTrue(Files.exists(dir.resolve("operator.jfr.part")));
        } finally { capture.close(); }
    }

    @Test void linuxSamplesBoundInputAndNeverReadEnvironment(@TempDir Path dir) throws Exception {
        Path proc = dir.resolve("proc");
        Files.createDirectories(proc.resolve("self"));
        Files.writeString(proc.resolve("self/cgroup"), "0::/server\n");
        Files.writeString(proc.resolve("self/status"), "x".repeat(20_000));
        Files.writeString(proc.resolve("self/environ"), "SECRET_CANARY");
        Path cg = dir.resolve("cgroup");
        Files.createDirectories(cg.resolve("server"));
        Files.writeString(cg.resolve("server/cpu.stat"), "nr_throttled 3\n");
        String sample = new SystemMetrics(proc, cg).sample();
        assertTrue(sample.contains("nr_throttled 3"));
        assertTrue(sample.contains("[truncated]"));
        assertTrue(sample.contains("MISSING_OR_UNREADABLE"));
        assertFalse(sample.contains("SECRET_CANARY"));
    }
}
