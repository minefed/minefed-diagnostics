package team.minefed.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Owns one bounded rolling recording. All IO and dumps run off the server and watchdog threads. */
final class AutomaticCapture implements AutoCloseable {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern OWN_FILE = Pattern.compile("capture-\\d{8}T\\d{9}Z-[a-f0-9-]{36}\\.(txt|jfr)");
    private static final Pattern OWN_PART = Pattern.compile("capture-\\d{8}T\\d{9}Z-[a-f0-9-]{36}\\.jfr\\.part");
    private final DiagnosticsConfig config;
    private final Path directory;
    private final Supplier<String> sample, dump;
    private final Consumer<String> log;
    private final ScheduledThreadPoolExecutor worker;
    private final ArrayDeque<String> history = new ArrayDeque<>(); // worker thread only
    private Recording recording;
    private boolean pending, hasCaptured;
    private long lastAccepted;
    private volatile boolean started, closed;
    private volatile String state = "not started";

    AutomaticCapture(DiagnosticsConfig config, Path directory, Supplier<String> dump, Consumer<String> log) {
        this(config, directory, new SystemMetrics()::sample, dump, log);
    }

    AutomaticCapture(DiagnosticsConfig config, Path directory, Supplier<String> sample, Supplier<String> dump, Consumer<String> log) {
        this.config = config;
        this.directory = directory;
        this.sample = sample;
        this.dump = dump;
        this.log = log;
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Minefed-Diagnostics-Capture");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    synchronized void start() {
        if (started || closed) return;
        started = true;
        if (!config.captureEnabled) { state = "disabled"; return; }
        worker.execute(() -> {
            try {
                Files.createDirectories(directory);
                removeInterruptedDumps();
                prune();
                if (config.captureJfrEnabled) {
                    recording = new Recording(Configuration.getConfiguration("default"));
                    recording.setName("Minefed automatic diagnostics " + UUID.randomUUID());
                    recording.setToDisk(true);
                    recording.setMaxAge(Duration.ofSeconds(config.captureHistorySeconds));
                    recording.setMaxSize(config.captureMaxMiB * 1024L * 1024L);
                    recording.disable("jdk.InitialEnvironmentVariable");
                    recording.disable("jdk.InitialSystemProperty");
                    recording.disable("jdk.JVMInformation");
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
                    recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(10));
                    recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(10));
                    recording.enable(TickWindowEvent.class);
                    recording.start();
                }
                state = config.captureJfrEnabled ? "rolling JFR active" : "OS/thread capture active (JFR disabled)";
            } catch (Exception | LinkageError e) {
                closeRecording();
                state = "JFR unavailable; OS/thread capture active: " + e.getClass().getSimpleName();
                log.accept(state);
            }
        });
        worker.scheduleWithFixedDelay(this::sampleSafely, 0, config.captureSampleMs, TimeUnit.MILLISECONDS);
    }

    /** Constant bounded enqueue; safe from the tick thread. Cooldown is shared across incident kinds. */
    synchronized boolean request(String reason, long now) {
        if (!started || closed || !config.captureEnabled || pending) return false;
        if (hasCaptured && now - lastAccepted < config.captureCooldownMs * 1_000_000L) return false;
        pending = hasCaptured = true;
        lastAccepted = now;
        Instant detected = Instant.now();
        worker.execute(() -> {
            try { capture(reason, detected); }
            catch (Exception | LinkageError e) {
                state = "capture failed: " + e.getClass().getSimpleName();
                log.accept(state);
            } finally { synchronized (AutomaticCapture.this) { pending = false; } }
        });
        return true;
    }

    private void sampleSafely() {
        try {
            history.addLast(sample.get());
            int limit = (int) (config.captureHistorySeconds * 1000 / config.captureSampleMs) + 1;
            while (history.size() > limit) history.removeFirst();
        } catch (Exception e) { log.accept("OS sample unavailable: " + e.getClass().getSimpleName()); }
    }

    private void capture(String reason, Instant detected) throws IOException {
        Files.createDirectories(directory);
        String base = "capture-" + STAMP.format(detected) + "-" + UUID.randomUUID();
        Path report = directory.resolve(base + ".txt");
        // Write the text first so it survives even if JFR dump fails or the JVM exits during the dump.
        StringBuilder text = new StringBuilder("Minefed automatic incident capture\nDetected UTC: ").append(detected)
                .append("\nCaptured UTC: ").append(Instant.now()).append("\nTrigger: ").append(reason)
                .append("\nJFR state: ").append(state).append('\n')
                .append("Labels are observations, not root-cause verdicts. OS counters may cover host/shared cgroups.\n")
                .append("\n=== Pre-incident OS/JVM samples (oldest first) ===\n");
        history.forEach(s -> text.append(s).append('\n'));
        text.append("\n=== Current OS/JVM sample ===\n").append(observe(sample));
        text.append("\n=== Threads at capture time ===\n").append(observe(dump));
        Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try {
            if (recording != null) {
                Path temporary = directory.resolve(base + ".jfr.part");
                try {
                    recording.dump(temporary);
                    if (Files.size(temporary) == 0) throw new IOException("Empty JFR dump");
                    Path target = directory.resolve(base + ".jfr");
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target); }
                } finally { Files.deleteIfExists(temporary); }
            }
            state = recording != null ? "rolling JFR active" : "OS/thread capture active";
            log.accept("Automatic incident captured: " + report);
        } catch (IOException | RuntimeException e) {
            Files.writeString(report, "\nJFR dump failed: " + e.getClass().getSimpleName() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            throw e;
        } finally { prune(); }
    }

    private static String observe(Supplier<String> observation) {
        try { return observation.get(); }
        catch (Exception | LinkageError e) { return "UNAVAILABLE: " + e.getClass().getSimpleName() + "\n"; }
    }

    /** Runs only at startup, before this instance can have an active dump. */
    void removeInterruptedDumps() throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p)
                    && OWN_PART.matcher(p.getFileName().toString()).matches()).toList()) Files.deleteIfExists(path);
        }
    }

    /** Only this module's exact capture filenames are eligible; unrelated files are never deleted. */
    void prune() throws IOException {
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(p -> Files.isRegularFile(p) && OWN_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted().toList();
        }
        List<String> groups = new ArrayList<>();
        long bytes = 0;
        for (Path path : files) {
            String name = path.getFileName().toString();
            String base = name.substring(0, name.lastIndexOf('.'));
            if (groups.isEmpty() || !groups.get(groups.size() - 1).equals(base)) groups.add(base);
            bytes += Files.size(path);
        }
        long limit = config.captureRetainMiB * 1024L * 1024L;
        for (int i = 0; i < groups.size() && (groups.size() - i > config.captureRetain || bytes > limit); i++) {
            String oldest = groups.get(i);
            for (Path path : files) if (path.getFileName().toString().startsWith(oldest + ".")) {
                bytes -= Files.size(path);
                Files.deleteIfExists(path);
            }
        }
    }

    String status() { return state; }
    void awaitIdle() throws Exception { worker.submit(() -> {}).get(20, TimeUnit.SECONDS); }
    private void closeRecording() {
        if (recording != null) { recording.close(); recording = null; }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.execute(() -> { closeRecording(); state = "stopped"; });
        worker.shutdown();
    }
}
