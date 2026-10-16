package team.minefed.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Writes report files to {@code logs/minefed-diagnostics} and keeps only the newest ones. */
public final class ReportWriter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final long SUMMARY_ROTATE_BYTES = 8L * 1024 * 1024;
    private static final Pattern REPORT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}_[a-z0-9-]+\\.txt");

    private final Path directory;
    private final int retain;

    public ReportWriter(Path directory, int retain) {
        this.directory = directory;
        this.retain = retain;
    }

    public Path directory() {
        return directory;
    }

    /** @return the written file, or {@code null} when writing failed */
    public synchronized Path write(String kind, String content) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(LocalDateTime.now().format(STAMP) + "_" + kind + ".txt");
            while (Files.exists(file)) {
                // Keep names unique and in creation order; reports are rare, so waiting a millisecond is fine.
                Thread.sleep(1);
                file = directory.resolve(LocalDateTime.now().format(STAMP) + "_" + kind + ".txt");
            }
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            prune();
            return file;
        } catch (IOException e) {
            MinefedDiagnostics.LOGGER.warn("Could not write diagnostics report {}", kind, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Overwrites a report that is being updated while an incident is still running. */
    public synchronized void rewrite(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            MinefedDiagnostics.LOGGER.warn("Could not update diagnostics report {}", file, e);
        }
    }

    public synchronized void appendSummary(String content) {
        try {
            Files.createDirectories(directory);
            Path summary = directory.resolve("summary.log");
            if (Files.isRegularFile(summary) && Files.size(summary) > SUMMARY_ROTATE_BYTES) {
                Files.move(summary, directory.resolve("summary.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(summary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MinefedDiagnostics.LOGGER.warn("Could not append diagnostics summary", e);
        }
    }

    /** Deletes the oldest report files of this mod beyond the retention count. Other files are left alone. */
    void prune() throws IOException {
        List<Path> reports;
        try (Stream<Path> files = Files.list(directory)) {
            reports = files.filter(p -> REPORT_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
        for (int i = 0; i < reports.size() - retain; i++) Files.deleteIfExists(reports.get(i));
    }
}
