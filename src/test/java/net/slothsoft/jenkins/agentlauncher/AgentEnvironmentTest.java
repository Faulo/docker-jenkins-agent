package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class AgentEnvironmentTest {
    @ParameterizedTest
    @MethodSource("booleanValues")
    void acceptsDocumentedBooleanValues(String value, boolean expected) {
        assertEquals(expected, AgentEnvironment.readWebSocket(value));
    }

    static Stream<Arguments> booleanValues() {
        return Stream.of(
            Arguments.of(null, true), Arguments.of("", true), Arguments.of("   ", true),
            Arguments.of("1", true), Arguments.of("true", true), Arguments.of(" TrUe ", true),
            Arguments.of("0", false), Arguments.of("false", false), Arguments.of(" FaLsE ", false)
        );
    }

    @Test
    void rejectsUnknownValueWithoutRepeatingIt() {
        String value = "highly-sensitive-value";
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> AgentEnvironment.readWebSocket(value));
        assertFalse(failure.getMessage().contains(value));
    }

    @Test
    void normalizesDefaultAndExplicitWebSocket() {
        Map<String, String> environment = new HashMap<>();
        AgentEnvironment.normalize(List.of(), environment);
        assertEquals("true", environment.get(AgentEnvironment.WEB_SOCKET));
        AgentEnvironment.normalize(List.of("-webSocket"), environment);
        assertFalse(environment.containsKey(AgentEnvironment.WEB_SOCKET));
    }
}
