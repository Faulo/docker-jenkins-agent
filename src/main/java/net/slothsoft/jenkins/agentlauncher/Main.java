package net.slothsoft.jenkins.agentlauncher;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Main {
    record LaunchRequest(boolean health, List<String> remotingArguments) {
    }

    private Main() {
    }

    public static void main(String[] arguments) {
        System.exit(run(List.of(arguments)));
    }

    static int run(List<String> arguments) {
        try {
            Map<String, String> environment = isWindows()
                ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER)
                : new HashMap<>();
            environment.putAll(System.getenv());
            environment.putAll(IndexedEnvironment.load(environment));
            Path baseDirectory = baseDirectory();
            LaunchRequest request = parseArguments(arguments);
            if (request.health()) {
                AgentEnvironment.normalize(arguments, environment);
                return AgentHealth.run(baseDirectory, environment);
            }
            return AgentProcess.run(new ArrayList<>(request.remotingArguments()), environment, baseDirectory);
        } catch (ConfigurationException exception) {
            System.err.println("docker-jenkins-agent: " + exception.getMessage());
            return 1;
        } catch (Exception exception) {
            System.err.println("docker-jenkins-agent: the agent entrypoint failed");
            return 1;
        }
    }

    static LaunchRequest parseArguments(List<String> arguments) {
        if (arguments.isEmpty()) {
            return new LaunchRequest(false, List.of());
        }
        String command = arguments.getFirst();
        if ("health".equals(command)) {
            if (arguments.size() != 1) {
                throw new ConfigurationException("the health command does not accept arguments");
            }
            return new LaunchRequest(true, List.of());
        }
        if ("serve".equals(command)) {
            return new LaunchRequest(false, List.copyOf(arguments.subList(1, arguments.size())));
        }
        if (!command.startsWith("-")) {
            throw new ConfigurationException("the launcher command must be serve or health");
        }
        return new LaunchRequest(false, List.copyOf(arguments));
    }

    private static Path baseDirectory() throws URISyntaxException {
        Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        return location.toFile().isDirectory() ? location : location.getParent();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
