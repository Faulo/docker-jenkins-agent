package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

final class AgentHealth {
    static final String HEALTH_FILE = "JENKINS_HEALTH_FILE";
    static final String HEALTH_STALE_SECONDS = "JENKINS_HEALTH_STALE_SECONDS";
    static final String HEALTH_TIMEOUT_SECONDS = "JENKINS_HEALTH_TIMEOUT_SECONDS";
    static final int DEFAULT_STALE_SECONDS = 30;
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private AgentHealth() {
    }

    static int run(Path baseDirectory, Map<String, String> environment) {
        String configured = environment.get(HEALTH_FILE);
        Path statusFile = configured == null || configured.isBlank()
            ? baseDirectory.resolve("agent-health.status")
            : Path.of(configured);
        int timeoutSeconds = readPositiveSeconds(
            HEALTH_TIMEOUT_SECONDS,
            environment.get(HEALTH_TIMEOUT_SECONDS),
            DEFAULT_TIMEOUT_SECONDS
        );
        int staleSeconds = readPositiveSeconds(
            HEALTH_STALE_SECONDS,
            environment.get(HEALTH_STALE_SECONDS),
            DEFAULT_STALE_SECONDS
        );
        return run(
            statusFile,
            Instant.now(),
            Duration.ofSeconds(timeoutSeconds),
            Duration.ofSeconds(staleSeconds),
            AgentHealth::processMatches,
            System.out,
            System.err
        );
    }

    static int run(
        Path statusFile,
        Instant now,
        Duration timeout,
        Duration stale,
        BiPredicate<Long, Long> processMatches,
        PrintStream output,
        PrintStream error
    ) {
        Map<String, String> values = read(statusFile);
        if (values == null) {
            error.println("docker-jenkins-agent: unhealthy; Remoting health state is unavailable");
            return 1;
        }
        Long pid = positiveLong(values.get("pid"));
        Long processStart = nonnegativeLong(values.get("processStart"));
        Long stateSince = positiveLong(values.get("stateSince"));
        Long lastConnected = nonnegativeLong(values.get("lastConnected"));
        Long updated = positiveLong(values.get("updated"));
        String state = values.get("state");
        if (pid == null || processStart == null || stateSince == null || lastConnected == null || updated == null
            || !"2".equals(values.get("version")) || "invalid".equals(safeState(state))
            || !processMatches.test(pid, processStart)) {
            error.println("docker-jenkins-agent: unhealthy; Remoting health state is invalid");
            return 1;
        }
        long nowMilliseconds = now.toEpochMilli();
        if (isInvalidTimestamp(updated, nowMilliseconds) || age(nowMilliseconds, updated).compareTo(stale) > 0) {
            error.println("docker-jenkins-agent: unhealthy; Remoting health state is stale");
            return 1;
        }
        if (isInvalidTimestamp(stateSince, nowMilliseconds)) {
            error.println("docker-jenkins-agent: unhealthy; Remoting health state is invalid");
            return 1;
        }
        if ("connected".equals(state)) {
            output.println("docker-jenkins-agent: healthy; Remoting is connected");
            return 0;
        }
        if (("starting".equals(state) || "reconnecting".equals(state))
            && age(nowMilliseconds, stateSince).compareTo(timeout) <= 0) {
            output.println("docker-jenkins-agent: healthy; Remoting is " + state + " within the connection grace period");
            return 0;
        }
        error.println("docker-jenkins-agent: unhealthy; Remoting state is " + safeState(state));
        return 1;
    }

    static int readPositiveSeconds(String name, String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            // Report only the variable name so configuration values cannot leak.
        }
        throw new ConfigurationException("environment variable " + name + " must be a positive integer");
    }

    private static boolean processMatches(long pid, long expectedStart) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(handle -> handle.info().startInstant())
            .map(start -> Math.abs(start.toEpochMilli() - expectedStart) <= 2000)
            .orElse(false);
    }

    private static Map<String, String> read(Path path) {
        Map<String, String> values = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                int separator = line.indexOf('=');
                if (separator <= 0 || values.putIfAbsent(
                    line.substring(0, separator), line.substring(separator + 1)) != null) {
                    return null;
                }
            }
            return values;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static Long positiveLong(String value) {
        Long parsed = nonnegativeLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static Long nonnegativeLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Duration age(long now, long timestamp) {
        return Duration.ofMillis(now - timestamp);
    }

    private static boolean isInvalidTimestamp(long timestamp, long now) {
        return timestamp <= 0 || timestamp > now + 5000;
    }

    private static String safeState(String state) {
        if (state == null) {
            return "invalid";
        }
        return switch (state) {
            case "starting", "connected", "reconnecting", "terminal" -> state;
            default -> "invalid";
        };
    }
}
