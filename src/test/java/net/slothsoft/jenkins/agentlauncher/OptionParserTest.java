package net.slothsoft.jenkins.agentlauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

final class OptionParserTest {
    @Test
    void keepsQuotedAndEscapedOptionsTogether() {
        assertEquals(
            List.of("-Xmx1g", "-Dmessage=hello world", "", "plain value", "a\\b"),
            OptionParser.split("JENKINS_JAVA_OPTS", "-Xmx1g '-Dmessage=hello world' \"\" plain\\ value a\\b")
        );
    }

    @Test
    void preservesBackslashesInsideSingleQuotes() {
        assertEquals(List.of("C:\\Program Files\\Java"), OptionParser.split("JAVA_OPTS", "'C:\\Program Files\\Java'"));
    }

    @Test
    void unterminatedQuoteDoesNotLeakValue() {
        String value = "'highly-sensitive-value";
        ConfigurationException failure = assertThrows(ConfigurationException.class,
            () -> OptionParser.split("JAVA_OPTS", value));
        assertFalse(failure.getMessage().contains(value));
    }
}
