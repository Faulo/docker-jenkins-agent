package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.UUID;

final class AgentJar {
    static final String JENKINS_URL = "JENKINS_URL";
    private static final long MAXIMUM_SIZE = 100L * 1024L * 1024L;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMinutes(2))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private AgentJar() {
    }

    static Path destination(Path baseDirectory) {
        return baseDirectory.resolve("agent.jar");
    }

    static URI controllerJarUri(String configured) {
        if (configured == null || configured.isBlank()) {
            throw invalidUrl();
        }
        try {
            URI root = URI.create(configured.trim());
            if (!root.isAbsolute() || root.getHost() == null
                || !("http".equalsIgnoreCase(root.getScheme()) || "https".equalsIgnoreCase(root.getScheme()))) {
                throw invalidUrl();
            }
            String normalized = root.toString().replaceAll("/+$", "") + "/";
            return URI.create(normalized).resolve("jnlpJars/agent.jar");
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
    }

    static void install(String configuredUrl, Path destination) {
        URI source = controllerJarUri(configuredUrl);
        Path directory = destination.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IllegalStateException("the Jenkins agent JAR destination has no parent directory");
        }
        Path temporary = directory.resolve("agent.jar." + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        try {
            HttpRequest request = HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("unexpected HTTP status " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (contentLength > MAXIMUM_SIZE) {
                    throw tooLarge();
                }
                try (var output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )) {
                    byte[] buffer = new byte[81920];
                    long total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAXIMUM_SIZE) {
                            throw tooLarge();
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
            validateJar(temporary);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw installFailure(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw installFailure(exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException exception) {
                // Preserve the original download or installation failure.
            }
        }
    }

    private static void validateJar(Path path) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(signature) != signature.length
                || signature[0] != 'P' || signature[1] != 'K'
                || signature[2] != 3 || signature[3] != 4) {
                throw new ConfigurationException("the Jenkins controller response is not an agent JAR");
            }
        }
    }

    private static ConfigurationException invalidUrl() {
        return new ConfigurationException(
            "environment variable " + JENKINS_URL + " must be an absolute HTTP or HTTPS URL"
        );
    }

    private static ConfigurationException tooLarge() {
        return new ConfigurationException("the Jenkins controller agent JAR exceeds the maximum supported size");
    }

    private static ConfigurationException installFailure(Exception cause) {
        return new ConfigurationException(
            "failed to install the Jenkins controller agent JAR from " + JENKINS_URL,
            cause
        );
    }
}
