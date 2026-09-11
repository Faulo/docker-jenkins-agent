package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AgentHealthTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsConnectedMatchingProcessIndefinitely() throws IOException {
        long now = 1_800_000_000_000L;
        Path status = writeStatus(now, "connected", now - 1_000_000, now - 1_000_000);
        assertEquals(0, run(status, now, true));
    }

    @Test
    void acceptsStartingAndReconnectingWithinGracePeriod() throws IOException {
        long now = 1_800_000_000_000L;
        assertEquals(0, run(writeStatus(now, "starting", now - 299_000, 0), now, true));
        assertEquals(0, run(writeStatus(now, "reconnecting", now - 299_000, now - 500_000), now, true));
    }

    @Test
    void rejectsExpiredGraceTerminalStaleOrMismatchedStatus() throws IOException {
        long now = 1_800_000_000_000L;
        assertEquals(1, run(writeStatus(now, "starting", now - 301_000, 0), now, true));
        assertEquals(1, run(writeStatus(now, "reconnecting", now - 301_000, now - 500_000), now, true));
        assertEquals(1, run(writeStatus(now, "terminal", now, now), now, true));
        assertEquals(1, run(writeStatus(now - 31_000, "connected", now - 31_000, now), now, true));
        assertEquals(1, run(writeStatus(now, "connected", now, now), now, false));
    }

    private Path writeStatus(long updated, String state, long stateSince, long lastConnected) throws IOException {
        Path status = temporaryDirectory.resolve("agent-health.status");
        Files.writeString(status, "version=2\npid=12\nprocessStart=34\nstate=" + state
            + "\nstateSince=" + stateSince + "\nlastConnected=" + lastConnected
            + "\nupdated=" + updated + "\ndiagnostic=none\n");
        return status;
    }

    private static int run(Path status, long now, boolean matches) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            return AgentHealth.run(
                status,
                Instant.ofEpochMilli(now),
                Duration.ofSeconds(300),
                Duration.ofSeconds(30),
                (pid, start) -> matches,
                output,
                output
            );
        }
    }
}
