package team.minefed.diagnostics;

/** Monotonic count of prepared chunks and received batch acknowledgements. Server-thread only. */
public interface ChunkSendProgress {
    long minefedDiagnostics$progress();
}
