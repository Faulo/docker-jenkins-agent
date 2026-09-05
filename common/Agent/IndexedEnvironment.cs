using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security;
using YamlDotNet.Core;
using YamlDotNet.RepresentationModel;

namespace Agent;

static class IndexedEnvironment {
    public const string CONFIG_FILE = "JENKINS_CONFIG_FILE";
    public const string CONFIG_INDEX = "JENKINS_CONFIG_INDEX";

    public static IReadOnlyDictionary<string, string> Load() => Load(
        Environment.GetEnvironmentVariable(CONFIG_FILE),
        Environment.GetEnvironmentVariable(CONFIG_INDEX),
        File.OpenText
    );

    internal static IReadOnlyDictionary<string, string> Load(
        string? file,
        string? index,
        Func<string, TextReader> openReader
    ) {
        if (file is null && index is null) {
            return new Dictionary<string, string>();
        }
        if (file is null) {
            throw new ConfigurationException($"{CONFIG_FILE} is required when {CONFIG_INDEX} is set");
        }
        if (index is null) {
            throw new ConfigurationException($"{CONFIG_INDEX} is required when {CONFIG_FILE} is set");
        }
        if (string.IsNullOrWhiteSpace(file)) {
            throw Error(file, index, "the file path is empty");
        }

        var yaml = new YamlStream();
        try {
            using var reader = openReader(file);
            yaml.Load(reader);
        } catch (Exception exception) when (exception is IOException
                                            or UnauthorizedAccessException
                                            or SecurityException
                                            or ArgumentException
                                            or NotSupportedException
                                            or YamlException) {
            throw Error(file, index, "the file could not be read or parsed");
        }

        if (yaml.Documents.Count != 1 || yaml.Documents[0].RootNode is not YamlMappingNode root) {
            throw Error(file, index, "the document root must be one mapping");
        }

        var matches = root.Children
            .Where(pair => pair.Key is YamlScalarNode key && string.Equals(key.Value, index, StringComparison.Ordinal))
            .Select(pair => pair.Value)
            .ToArray();
        if (matches.Length == 0) {
            throw Error(file, index, "the index does not exist");
        }
        if (matches.Length != 1) {
            throw Error(file, index, "the index is ambiguous");
        }
        if (matches[0] is not YamlMappingNode selected) {
            throw Error(file, index, "the selected value is not a mapping");
        }

        var comparer = OperatingSystem.IsWindows()
            ? StringComparer.OrdinalIgnoreCase
            : StringComparer.Ordinal;
        var values = new Dictionary<string, string>(comparer);
        foreach (var (keyNode, valueNode) in selected.Children) {
            if (keyNode is not YamlScalarNode key
                || valueNode is not YamlScalarNode value
                || !IsValidName(key.Value)
                || value.Value is null
                || (value.Style == ScalarStyle.Plain && value.Value.Length == 0)
                || value.Value.Contains('\0')) {
                throw Error(file, index, "an environment name or value cannot be represented safely");
            }
            if (!values.TryAdd(key.Value!, value.Value)) {
                throw Error(file, index, "an environment name occurs more than once");
            }
        }
        return values;
    }

    public static void Apply(IReadOnlyDictionary<string, string> values) {
        foreach ((string name, string value) in values) {
            Environment.SetEnvironmentVariable(name, value);
        }
    }

    static bool IsValidName(string? name) => !string.IsNullOrEmpty(name)
                                             && !name.Contains('=')
                                             && !name.Contains('\0');

    static ConfigurationException Error(string file, string index, string message) => new(
        $"configuration file '{Safe(file)}' at index '{Safe(index)}': {message}"
    );

    static string Safe(string value) => new(value.Select(character => char.IsControl(character) || character == '\'' ? '?' : character).ToArray());
}
