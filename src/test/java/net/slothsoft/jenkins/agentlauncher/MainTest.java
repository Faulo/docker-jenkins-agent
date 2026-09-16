package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class MainTest {
    @Test
    void parsesServeAndDirectRemotingArguments() {
        Main.LaunchRequest defaults = Main.parseArguments(List.of("serve"));
        assertFalse(defaults.health());
        assertEquals(List.of(), defaults.remotingArguments());

        Main.LaunchRequest serve = Main.parseArguments(List.of("serve", "-url", "https://jenkins.example/"));
        assertFalse(serve.health());
        assertEquals(List.of("-url", "https://jenkins.example/"), serve.remotingArguments());

        Main.LaunchRequest direct = Main.parseArguments(List.of("-version"));
        assertFalse(direct.health());
        assertEquals(List.of("-version"), direct.remotingArguments());
    }

    @Test
    void parsesStandaloneHealthCommand() {
        Main.LaunchRequest request = Main.parseArguments(List.of("health"));
        assertTrue(request.health());
        assertEquals(List.of(), request.remotingArguments());
    }

    @Test
    void rejectsUnknownCommandsAndHealthArguments() {
        assertThrows(ConfigurationException.class, () -> Main.parseArguments(List.of("env")));
        assertThrows(ConfigurationException.class, () -> Main.parseArguments(List.of("health", "extra")));
    }
}
