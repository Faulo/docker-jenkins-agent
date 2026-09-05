using System;
using System.Collections.Generic;
using System.IO;
using NUnit.Framework;

namespace Agent.Tests;

sealed class IndexedEnvironmentTests {
    const string FILE = "/run/secrets/jenkins-agents";
    const string INDEX = "Dende";

    [Test]
    public void LoadWithoutConfigurationReturnsEmptyEnvironment() {
        var values = IndexedEnvironment.Load(null, null, _ => throw new AssertionException("file should not be opened"));

        Assert.That(values, Is.Empty);
    }

    [TestCase(null, INDEX, IndexedEnvironment.CONFIG_FILE)]
    [TestCase(FILE, null, IndexedEnvironment.CONFIG_INDEX)]
    public void LoadRequiresConfigurationPair(string? file, string? index, string expectedName) {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(file, index, Open("")));

        Assert.That(exception!.Message, Does.Contain(expectedName));
    }

    [Test]
    public void LoadSelectsExactMappingAndKeepsScalarText() {
        var values = IndexedEnvironment.Load(FILE, INDEX, Open("""
            dende:
              JENKINS_AGENT_NAME: wrong-case
            Dende:
              JENKINS_AGENT_NAME: Mörkö
              JENKINS_SECRET: 00123
              EMPTY: ""
            """));

        Assert.That(values, Is.EquivalentTo(new Dictionary<string, string> {
            ["JENKINS_AGENT_NAME"] = "Mörkö",
            ["JENKINS_SECRET"] = "00123",
            ["EMPTY"] = ""
        }));
    }

    [Test]
    public void LoadRejectsMissingIndexWithoutExposingValues() {
        AssertSafeFailure("missing", """
            Dende:
              JENKINS_SECRET: highly-sensitive-value
            """, "index does not exist");
    }

    [Test]
    public void LoadRejectsMalformedYamlWithoutExposingValues() {
        AssertSafeFailure(INDEX, """
            Dende: [
              JENKINS_SECRET: highly-sensitive-value
            """, "could not be read or parsed");
    }

    [TestCase("Dende: scalar", "selected value is not a mapping")]
    [TestCase("- Dende", "document root must be one mapping")]
    [TestCase("", "document root must be one mapping")]
    [TestCase("Dende: {}\n---\nOther: {}", "document root must be one mapping")]
    public void LoadRequiresOneDocumentWithSelectedMapping(string yaml, string expectedError) {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(FILE, INDEX, Open(yaml)));

        Assert.That(exception!.Message, Does.Contain(expectedError));
    }

    [TestCase("=INVALID")]
    [TestCase("")]
    public void LoadRejectsUnsafeEnvironmentNames(string name) {
        const string value = "highly-sensitive-data";
        string yaml = $"Dende:\n  '{name}': '{value}'";
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(FILE, INDEX, Open(yaml)));

        Assert.That(exception!.Message, Does.Contain("cannot be represented safely"));
        Assert.That(exception.Message, Does.Not.Contain(value));
    }

    [Test]
    public void LoadRejectsNullEnvironmentValue() {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(FILE, INDEX, Open("""
            Dende:
              JENKINS_SECRET:
            """)));

        Assert.That(exception!.Message, Does.Contain("cannot be represented safely"));
    }

    [Test]
    public void LoadReportsUnreadableFileAndIndex() {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(
            FILE,
            INDEX,
            _ => throw new FileNotFoundException()
        ));

        Assert.That(exception!.Message, Does.Contain(FILE).And.Contain(INDEX).And.Contain("could not be read or parsed"));
    }

    [Test]
    public void LoadSanitizesFileAndIndexInErrors() {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(
            "bad\nfile",
            "bad\rindex",
            _ => throw new FileNotFoundException()
        ));

        Assert.That(exception!.Message, Does.Not.Contain("\n").And.Not.Contain("\r"));
    }

    static Func<string, TextReader> Open(string content) => _ => new StringReader(content);

    static void AssertSafeFailure(string index, string yaml, string expectedError) {
        var exception = Assert.Throws<ConfigurationException>(() => IndexedEnvironment.Load(FILE, index, Open(yaml)));

        Assert.That(exception!.Message, Does.Contain(FILE).And.Contain(index).And.Contain(expectedError));
        Assert.That(exception.Message, Does.Not.Contain("highly-sensitive-value"));
    }
}
