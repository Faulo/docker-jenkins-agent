package net.slothsoft.jenkins.agentlauncher;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AgentEnvironment {
    static final String WEB_SOCKET = "JENKINS_WEB_SOCKET";

    private AgentEnvironment() {
    }

    static void normalize(List<String> arguments, Map<String, String> environment) {
        boolean webSocket = readWebSocket(environment.get(WEB_SOCKET));
        boolean explicitWebSocket = arguments.contains("-webSocket");
        if (webSocket && !explicitWebSocket) {
            environment.put(WEB_SOCKET, "true");
        } else {
            environment.remove(WEB_SOCKET);
        }
    }

    static boolean readWebSocket(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new ConfigurationException(
                "environment variable " + WEB_SOCKET + " must be true, false, 1, or 0"
            );
        };
    }
}
