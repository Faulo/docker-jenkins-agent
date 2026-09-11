package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

final class IndexedEnvironment {
    static final String CONFIG_FILE = "JENKINS_CONFIG_FILE";
    static final String CONFIG_INDEX = "JENKINS_CONFIG_INDEX";

    private IndexedEnvironment() {
    }

    static Map<String, String> load(Map<String, String> environment) {
        return load(environment.get(CONFIG_FILE), environment.get(CONFIG_INDEX), path -> {
            try {
                return Files.newBufferedReader(Path.of(path));
            } catch (IOException | RuntimeException exception) {
                throw new ReaderException(exception);
            }
        }, isWindows());
    }

    static Map<String, String> load(
        String file,
        String index,
        Function<String, Reader> openReader,
        boolean windows
    ) {
        if (file == null && index == null) {
            return Map.of();
        }
        if (file == null) {
            throw new ConfigurationException(CONFIG_FILE + " is required when " + CONFIG_INDEX + " is set");
        }
        if (index == null) {
            throw new ConfigurationException(CONFIG_INDEX + " is required when " + CONFIG_FILE + " is set");
        }
        if (file.isBlank()) {
            throw error(file, index, "the file path is empty");
        }
        if (index.isBlank()) {
            throw error(file, index, "the index is empty");
        }

        List<Node> documents = new ArrayList<>();
        try (Reader reader = openReader.apply(file)) {
            new Yaml().composeAll(reader).forEach(documents::add);
        } catch (IOException | RuntimeException exception) {
            throw error(file, index, "the file could not be read or parsed");
        }
        if (documents.size() != 1 || !(documents.getFirst() instanceof MappingNode root)) {
            throw error(file, index, "the document root must be one mapping");
        }

        List<Node> matches = new ArrayList<>();
        for (NodeTuple tuple : root.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key && index.equals(key.getValue())) {
                matches.add(tuple.getValueNode());
            }
        }
        if (matches.isEmpty()) {
            throw error(file, index, "the index does not exist");
        }
        if (matches.size() != 1) {
            throw error(file, index, "the index is ambiguous");
        }
        if (!(matches.getFirst() instanceof MappingNode selected)) {
            throw error(file, index, "the selected value is not a mapping");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (NodeTuple tuple : selected.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key)
                || !(tuple.getValueNode() instanceof ScalarNode value)
                || !isValidName(key.getValue())
                || Tag.NULL.equals(value.getTag())
                || (value.getScalarStyle() == DumperOptions.ScalarStyle.PLAIN && value.getValue().isEmpty())
                || value.getValue().indexOf('\0') >= 0) {
                throw error(file, index, "an environment name or value cannot be represented safely");
            }
            boolean duplicate = values.keySet().stream().anyMatch(existing -> windows
                ? existing.equalsIgnoreCase(key.getValue())
                : existing.equals(key.getValue()));
            if (duplicate) {
                throw error(file, index, "an environment name occurs more than once");
            }
            values.put(key.getValue(), value.getValue());
        }
        return values;
    }

    private static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && name.indexOf('=') < 0 && name.indexOf('\0') < 0;
    }

    private static ConfigurationException error(String file, String index, String message) {
        return new ConfigurationException(
            "configuration file '" + safe(file) + "' at index '" + safe(index) + "': " + message
        );
    }

    private static String safe(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(character -> {
            if (Character.isISOControl(character) || character == '\'') {
                result.append('?');
            } else {
                result.appendCodePoint(character);
            }
        });
        return result.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    private static final class ReaderException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private ReaderException(Throwable cause) {
            super(cause);
        }
    }
}
