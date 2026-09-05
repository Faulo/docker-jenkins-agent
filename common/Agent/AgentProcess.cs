using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;

namespace Agent;

static partial class AgentProcess {
    const string LINUX_ENTRYPOINT = "/usr/local/bin/jenkins-agent";
    const string WINDOWS_ENTRYPOINT = @"C:\ProgramData\Jenkins\jenkins-agent.ps1";

    public static int Run(IReadOnlyList<string> arguments, IReadOnlyDictionary<string, string> indexedEnvironment) {
        if (OperatingSystem.IsLinux()) {
            return Exec(LINUX_ENTRYPOINT, arguments, LinuxEnvironment(indexedEnvironment));
        }
        if (OperatingSystem.IsWindows()) {
            var start = WindowsStartInfo(arguments);
            using var process = Process.Start(start)
                                ?? throw new InvalidOperationException("failed to start the native Jenkins agent entrypoint");
            process.WaitForExit();
            return process.ExitCode;
        }
        throw new PlatformNotSupportedException("docker-jenkins-agent supports Linux and Windows only");
    }

    internal static ProcessStartInfo WindowsStartInfo(IReadOnlyList<string> arguments) {
        var start = new ProcessStartInfo {
            FileName = "powershell.exe",
            UseShellExecute = false
        };
        start.ArgumentList.Add("-File");
        start.ArgumentList.Add(WINDOWS_ENTRYPOINT);
        foreach (string argument in arguments) {
            start.ArgumentList.Add(argument);
        }
        return start;
    }

    internal static IReadOnlyList<string> LinuxEnvironment(IReadOnlyDictionary<string, string> indexedEnvironment) {
        var environment = Environment.GetEnvironmentVariables()
            .Cast<DictionaryEntry>()
            .ToDictionary(entry => (string)entry.Key, entry => (string)entry.Value!, StringComparer.Ordinal);
        foreach ((string name, string value) in indexedEnvironment) {
            environment[name] = value;
        }
        return environment.Select(entry => $"{entry.Key}={entry.Value}").ToArray();
    }

    static int Exec(string executable, IReadOnlyList<string> arguments, IReadOnlyList<string> environment) {
        IntPtr[] strings = new IntPtr[arguments.Count + 1];
        IntPtr[] environmentStrings = new IntPtr[environment.Count];
        IntPtr argumentVector = IntPtr.Zero;
        IntPtr environmentVector = IntPtr.Zero;
        IntPtr executablePointer = IntPtr.Zero;
        try {
            executablePointer = Marshal.StringToCoTaskMemUTF8(executable);
            strings[0] = Marshal.StringToCoTaskMemUTF8(executable);
            for (int index = 0; index < arguments.Count; index++) {
                strings[index + 1] = Marshal.StringToCoTaskMemUTF8(arguments[index]);
            }
            argumentVector = Marshal.AllocHGlobal((strings.Length + 1) * IntPtr.Size);
            Marshal.Copy(strings, 0, argumentVector, strings.Length);
            Marshal.WriteIntPtr(argumentVector, strings.Length * IntPtr.Size, IntPtr.Zero);
            for (int index = 0; index < environment.Count; index++) {
                environmentStrings[index] = Marshal.StringToCoTaskMemUTF8(environment[index]);
            }
            environmentVector = Marshal.AllocHGlobal((environmentStrings.Length + 1) * IntPtr.Size);
            Marshal.Copy(environmentStrings, 0, environmentVector, environmentStrings.Length);
            Marshal.WriteIntPtr(environmentVector, environmentStrings.Length * IntPtr.Size, IntPtr.Zero);
            execve(executablePointer, argumentVector, environmentVector);
            throw new Win32Exception(Marshal.GetLastPInvokeError(), "failed to execute the native Jenkins agent entrypoint");
        } finally {
            foreach (IntPtr value in strings) {
                Marshal.FreeCoTaskMem(value);
            }
            foreach (IntPtr value in environmentStrings) {
                Marshal.FreeCoTaskMem(value);
            }
            Marshal.FreeHGlobal(argumentVector);
            Marshal.FreeHGlobal(environmentVector);
            Marshal.FreeCoTaskMem(executablePointer);
        }
    }

    [LibraryImport("libc", SetLastError = true)]
    private static partial int execve(IntPtr path, IntPtr arguments, IntPtr environment);
}
