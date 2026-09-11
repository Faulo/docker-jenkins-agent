package net.slothsoft.jenkins.agentlauncher;

import java.util.ArrayList;
import java.util.List;

final class OptionParser {
    private OptionParser() {
    }

    static List<String> split(String name, String configured) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < configured.length(); index++) {
            char character = configured.charAt(index);
            if (quote == 0 && Character.isWhitespace(character)) {
                if (started) {
                    result.add(value.toString());
                    value.setLength(0);
                    started = false;
                }
                continue;
            }
            if (character == '\'' || character == '"') {
                if (quote == 0) {
                    quote = character;
                    started = true;
                    continue;
                }
                if (quote == character) {
                    quote = 0;
                    continue;
                }
            }
            if (character == '\\' && quote != '\'' && index + 1 < configured.length()) {
                char next = configured.charAt(index + 1);
                if (next == '\\' || next == '\'' || next == '"' || Character.isWhitespace(next)) {
                    value.append(next);
                    started = true;
                    index++;
                    continue;
                }
            }
            value.append(character);
            started = true;
        }
        if (quote != 0) {
            throw new ConfigurationException(
                "environment variable " + name + " contains an unterminated quoted option"
            );
        }
        if (started) {
            result.add(value.toString());
        }
        return result;
    }
}
