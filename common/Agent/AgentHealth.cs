using System;
using System.Diagnostics;
using System.IO;

namespace Agent;

static class AgentHealth {
    public static int Run() {
        var start = StartInfo();
        using var process = Process.Start(start)
                            ?? throw new InvalidOperationException("failed to start the Jenkins agent health probe");
        process.WaitForExit();
        return process.ExitCode;
    }

    internal static ProcessStartInfo StartInfo() {
        string? configuredJava = Environment.GetEnvironmentVariable("JENKINS_JAVA_BIN");
        string? javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
        string java = !string.IsNullOrWhiteSpace(configuredJava)
            ? configuredJava
            : !string.IsNullOrWhiteSpace(javaHome)
                ? Path.Combine(javaHome, "bin", OperatingSystem.IsWindows() ? "java.exe" : "java")
                : OperatingSystem.IsWindows() ? "java.exe" : "java";
        string? configuredAgent = Environment.GetEnvironmentVariable("JENKINS_AGENT_FILE");
        string agent = !string.IsNullOrWhiteSpace(configuredAgent)
            ? configuredAgent
            : OperatingSystem.IsWindows()
                ? @"C:\ProgramData\Jenkins\agent.jar"
                : "/usr/share/jenkins/agent.jar";
        var start = new ProcessStartInfo {
            FileName = java,
            UseShellExecute = false
        };
        start.ArgumentList.Add("-jar");
        start.ArgumentList.Add(agent);
        start.ArgumentList.Add("-version");
        return start;
    }
}
