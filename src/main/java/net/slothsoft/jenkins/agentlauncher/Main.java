package net.slothsoft.jenkins.agentlauncher;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Main {
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
            if (arguments.size() == 1 && "--health".equals(arguments.getFirst())) {
                AgentEnvironment.normalize(arguments, environment);
                return AgentHealth.run(baseDirectory, environment);
            }
            return AgentProcess.run(new ArrayList<>(arguments), environment, baseDirectory);
        } catch (ConfigurationException exception) {
            System.err.println("docker-jenkins-agent: " + exception.getMessage());
            return 1;
        } catch (Exception exception) {
            System.err.println("docker-jenkins-agent: the agent entrypoint failed");
            return 1;
        }
    }

    private static Path baseDirectory() throws URISyntaxException {
        Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        return location.toFile().isDirectory() ? location : location.getParent();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
