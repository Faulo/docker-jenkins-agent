package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class IndexedEnvironmentTest {
    private static final String FILE = "/run/secrets/jenkins-agents";
    private static final String INDEX = "Dende";

    @Test
    void selectsExactMappingAndKeepsScalarText() {
        String yaml = """
            dende:
              JENKINS_AGENT_NAME: wrong-case
            Dende:
              JENKINS_AGENT_NAME: Mörkö
              JENKINS_SECRET: 00123
              EMPTY: ""
            """;
        assertEquals(
            Map.of("JENKINS_AGENT_NAME", "Mörkö", "JENKINS_SECRET", "00123", "EMPTY", ""),
            IndexedEnvironment.load(FILE, INDEX, ignored -> new StringReader(yaml), false)
        );
    }

    @Test
    void requiresConfigurationPair() {
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> IndexedEnvironment.load(FILE, null, ignored -> new StringReader(""), false));
        assertTrue(failure.getMessage().contains(IndexedEnvironment.CONFIG_INDEX));
    }

    @Test
    void rejectsMalformedYamlWithoutLeakingValues() {
        String sensitive = "highly-sensitive-value";
        String yaml = "Dende: [\n  JENKINS_SECRET: " + sensitive;
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> IndexedEnvironment.load(FILE, INDEX, ignored -> new StringReader(yaml), false));
        assertFalse(failure.getMessage().contains(sensitive));
    }

    @Test
    void windowsRejectsNamesThatDifferOnlyByCase() {
        String yaml = "Dende:\n  NAME: first\n  name: second\n";
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> IndexedEnvironment.load(FILE, INDEX, ignored -> new StringReader(yaml), true));
        assertTrue(failure.getMessage().contains("more than once"));
    }
}
