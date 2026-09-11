package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class AgentProcessTest {
    @Test
    void mapsLauncherEnvironmentAndPreservesArguments() {
        Map<String, String> environment = new HashMap<>(Map.ofEntries(
            Map.entry("JENKINS_JAVA_BIN", "/custom/java"),
            Map.entry("JENKINS_JAVA_OPTS", "-Xmx1g '-Dmessage=hello world'"),
            Map.entry("JENKINS_URL", "https://jenkins.example/"),
            Map.entry("JENKINS_SECRET", "secret"),
            Map.entry("JENKINS_AGENT_NAME", "agent name"),
            Map.entry("JENKINS_TUNNEL", "tunnel.example:50000"),
            Map.entry("JENKINS_AGENT_WORKDIR", "/jenkins"),
            Map.entry("JENKINS_WEB_SOCKET", "true"),
            Map.entry("JENKINS_DIRECT_CONNECTION", "direct.example:50000"),
            Map.entry("JENKINS_INSTANCE_IDENTITY", "identity"),
            Map.entry("JENKINS_PROTOCOLS", "JNLP4-connect"),
            Map.entry("REMOTING_OPTS", "-noReconnectAfter 1h")
        ));
        ProcessBuilder process = AgentProcess.javaProcess(
            List.of("-disableHttpsCertValidation"), environment, false,
            Path.of("/jenkins/agent.jar"), Path.of("/jenkins/launcher.jar")
        );
        assertEquals(List.of(
            "/custom/java", "-Xmx1g", "-Dmessage=hello world", "-javaagent:/jenkins/launcher.jar",
            "-jar", "/jenkins/agent.jar", "-secret", "secret", "-name", "agent name",
            "-tunnel", "tunnel.example:50000", "-url", "https://jenkins.example/", "-workDir", "/jenkins",
            "-webSocket", "-direct", "direct.example:50000", "-protocols", "JNLP4-connect",
            "-instanceIdentity", "identity", "-noReconnectAfter", "1h", "-disableHttpsCertValidation"
        ), process.command());
    }

    @Test
    void explicitArgumentsAreNotDuplicated() {
        Map<String, String> environment = new HashMap<>(Map.of(
            "JENKINS_URL", "https://environment.example/",
            "JENKINS_SECRET", "environment-secret",
            "JENKINS_AGENT_NAME", "environment-name",
            "JENKINS_WEB_SOCKET", "true"
        ));
        List<String> arguments = List.of("-url", "https://argument.example/", "-secret", "argument-secret",
            "-name", "argument-name", "-webSocket");
        List<String> command = AgentProcess.javaProcess(arguments, environment, false,
            Path.of("/jenkins/agent.jar"), Path.of("/jenkins/launcher.jar")).command();
        assertFalse(command.contains("environment-secret"));
        assertFalse(command.contains("environment-name"));
        assertEquals(1, command.stream().filter("-webSocket"::equals).count());
        assertEquals(arguments, command.subList(command.size() - arguments.size(), command.size()));
    }

    @Test
    void javaHomeAndPlatformSelectExecutable() {
        Map<String, String> environment = new HashMap<>(Map.of("JAVA_HOME", "C:/openjdk-21/"));
        List<String> command = AgentProcess.javaProcess(new ArrayList<>(), environment, true,
            Path.of("C:/jenkins/agent.jar"), Path.of("C:/jenkins/launcher.jar")).command();
        assertEquals("C:/openjdk-21/bin/java.exe", command.getFirst());
    }
}
