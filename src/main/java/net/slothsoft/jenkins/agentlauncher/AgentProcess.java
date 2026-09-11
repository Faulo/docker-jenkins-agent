package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class AgentProcess {
    private static final String JENKINS_AGENT_NAME = "JENKINS_AGENT_NAME";
    private static final String JENKINS_AGENT_WORKDIR = "JENKINS_AGENT_WORKDIR";
    private static final String JENKINS_DIRECT_CONNECTION = "JENKINS_DIRECT_CONNECTION";
    private static final String JENKINS_INSTANCE_IDENTITY = "JENKINS_INSTANCE_IDENTITY";
    private static final String JENKINS_JAVA_BIN = "JENKINS_JAVA_BIN";
    private static final String JENKINS_JAVA_OPTS = "JENKINS_JAVA_OPTS";
    private static final String JENKINS_NAME = "JENKINS_NAME";
    private static final String JENKINS_PROTOCOLS = "JENKINS_PROTOCOLS";
    private static final String JENKINS_SECRET = "JENKINS_SECRET";
    private static final String JENKINS_TUNNEL = "JENKINS_TUNNEL";
    private static final String JAVA_HOME = "JAVA_HOME";
    private static final String JAVA_OPTS = "JAVA_OPTS";
    private static final String REMOTING_OPTS = "REMOTING_OPTS";

    private AgentProcess() {
    }

    static int run(List<String> arguments, Map<String, String> environment, Path baseDirectory)
        throws IOException, InterruptedException {
        ProcessBuilder alternate = alternateProcess(arguments, isWindows());
        if (alternate != null) {
            return runProcess(alternate, environment);
        }

        AgentEnvironment.normalize(arguments, environment);
        Path agentJar = AgentJar.destination(baseDirectory);
        AgentJar.install(environment.get(AgentJar.JENKINS_URL), agentJar);
        ProcessBuilder process = javaProcess(arguments, environment, isWindows(), agentJar, baseDirectory.resolve("launcher.jar"));
        try (AgentSupervisor supervisor = AgentSupervisor.acquire(baseDirectory)) {
            supervisor.verifyHeld();
            return runProcess(process, environment);
        }
    }

    static ProcessBuilder javaProcess(
        List<String> arguments,
        Map<String, String> environment,
        boolean windows,
        Path agentJar,
        Path launcherJar
    ) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable(environment, windows));
        String javaOptions = environment.get(JENKINS_JAVA_OPTS);
        String javaOptionsName = JENKINS_JAVA_OPTS;
        if (javaOptions == null || javaOptions.isBlank()) {
            javaOptions = environment.get(JAVA_OPTS);
            javaOptionsName = JAVA_OPTS;
        }
        addOptions(command, javaOptionsName, javaOptions);
        command.add("-javaagent:" + argumentPath(launcherJar));
        command.add("-jar");
        command.add(argumentPath(agentJar));

        String name = environment.get(JENKINS_NAME);
        if (name == null || name.isBlank()) {
            name = environment.get(JENKINS_AGENT_NAME);
        }
        addEnvironmentArgument(command, arguments, "-secret", environment.get(JENKINS_SECRET));
        addEnvironmentArgument(command, arguments, "-name", name);
        addEnvironmentArgument(command, arguments, "-tunnel", environment.get(JENKINS_TUNNEL));
        addEnvironmentArgument(command, arguments, "-url", environment.get(AgentJar.JENKINS_URL));
        addEnvironmentArgument(command, arguments, "-workDir", environment.get(JENKINS_AGENT_WORKDIR));
        if (!arguments.contains("-webSocket") && "true".equals(environment.get(AgentEnvironment.WEB_SOCKET))) {
            command.add("-webSocket");
        }
        addEnvironmentArgument(command, arguments, "-direct", environment.get(JENKINS_DIRECT_CONNECTION));
        addEnvironmentArgument(command, arguments, "-protocols", environment.get(JENKINS_PROTOCOLS));
        addEnvironmentArgument(command, arguments, "-instanceIdentity", environment.get(JENKINS_INSTANCE_IDENTITY));
        addOptions(command, REMOTING_OPTS, environment.get(REMOTING_OPTS));
        command.addAll(arguments);
        return new ProcessBuilder(command);
    }

    static ProcessBuilder alternateProcess(List<String> arguments, boolean windows) {
        if (!windows && arguments.size() == 1 && !arguments.getFirst().startsWith("-")) {
            return new ProcessBuilder(arguments.getFirst());
        }
        if (windows && arguments.size() == 2 && "-Cmd".equalsIgnoreCase(arguments.getFirst())) {
            return new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", arguments.get(1));
        }
        return null;
    }

    private static int runProcess(ProcessBuilder builder, Map<String, String> environment)
        throws IOException, InterruptedException {
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.inheritIO();
        Process process = builder.start();
        Thread shutdown = new Thread(() -> {
            if (process.isAlive()) {
                process.destroy();
            }
        }, "jenkins-agent-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return process.waitFor();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException exception) {
                // The JVM is already shutting down and the hook owns child termination.
            }
        }
    }

    private static String javaExecutable(Map<String, String> environment, boolean windows) {
        String configured = environment.get(JENKINS_JAVA_BIN);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String javaHome = environment.get(JAVA_HOME);
        if (javaHome != null && !javaHome.isBlank()) {
            return javaHome.replaceAll("[/\\\\]+$", "") + (windows ? "/bin/java.exe" : "/bin/java");
        }
        return windows ? "java.exe" : "java";
    }

    private static String argumentPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void addEnvironmentArgument(
        List<String> command,
        List<String> arguments,
        String option,
        String value
    ) {
        if (value == null || value.isBlank() || arguments.contains(option)) {
            return;
        }
        command.add(option);
        command.add(value);
    }

    private static void addOptions(List<String> command, String name, String configured) {
        if (configured != null && !configured.isBlank()) {
            command.addAll(OptionParser.split(name, configured));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
