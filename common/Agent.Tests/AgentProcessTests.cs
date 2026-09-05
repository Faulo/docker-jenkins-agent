using System;
using System.Collections.Generic;
using System.Linq;
using NUnit.Framework;

namespace Agent.Tests;

sealed class AgentProcessTests {
    [Test]
    public void LinuxEnvironmentAppliesIndexedValuesAfterProcessEnvironment() {
        string name = $"AGENT_TEST_{Guid.NewGuid():N}";
        Environment.SetEnvironmentVariable(name, "from-process-environment");
        try {
            var environment = AgentProcess.LinuxEnvironment(new Dictionary<string, string> {
                [name] = "from-indexed-environment"
            });

            Assert.That(environment, Does.Contain($"{name}=from-indexed-environment"));
            Assert.That(environment, Does.Not.Contain($"{name}=from-process-environment"));
        } finally {
            Environment.SetEnvironmentVariable(name, null);
        }
    }

    [Test]
    public void WindowsStartInfoPreservesArguments() {
        var start = AgentProcess.WindowsStartInfo(["-url", "https://jenkins.example/with space", "Mörkö"]);

        Assert.Multiple(() => {
            Assert.That(start.FileName, Is.EqualTo("powershell.exe"));
            Assert.That(start.UseShellExecute, Is.False);
            Assert.That(start.ArgumentList.ToArray(), Is.EqualTo(new[] {
                "-File",
                @"C:\ProgramData\Jenkins\jenkins-agent.ps1",
                "-url",
                "https://jenkins.example/with space",
                "Mörkö"
            }));
        });
    }
}
