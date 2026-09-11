package net.slothsoft.jenkins.agentlauncher;

import java.io.Serial;

final class ConfigurationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
        super(message);
    }

    ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
