using System;
using System.Linq;
using NUnit.Framework;

namespace Agent.Tests;

sealed class AgentHealthTests {
    [Test]
    public void StartInfoChecksBundledRemotingJar() {
        var start = AgentHealth.StartInfo();

        Assert.Multiple(() => {
            Assert.That(start.FileName, Does.Contain("java"));
            Assert.That(start.UseShellExecute, Is.False);
            Assert.That(start.ArgumentList.ToArray(), Is.EqualTo(new[] {
                "-jar",
                OperatingSystem.IsWindows() ? @"C:\ProgramData\Jenkins\agent.jar" : "/usr/share/jenkins/agent.jar",
                "-version"
            }));
        });
    }
}
