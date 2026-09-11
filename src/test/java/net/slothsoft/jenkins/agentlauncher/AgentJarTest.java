package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import org.junit.jupiter.api.Test;

final class AgentJarTest {
    @Test
    void resolvesControllerJarBelowConfiguredRoot() {
        assertEquals(URI.create("https://jenkins.example/jenkins/jnlpJars/agent.jar"),
            AgentJar.controllerJarUri(" https://jenkins.example/jenkins "));
    }

    @Test
    void rejectsUnsafeSchemeWithoutLeakingValue() {
        String value = "file:///highly-sensitive-value";
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> AgentJar.controllerJarUri(value));
        assertFalse(failure.getMessage().contains(value));
    }
}
