using System;

namespace Agent;

static class Program {
    static int Main(string[] arguments) {
        try {
            var indexedEnvironment = IndexedEnvironment.Load();
            IndexedEnvironment.Apply(indexedEnvironment);
            return arguments.Length == 1 && string.Equals(arguments[0], "--health", StringComparison.Ordinal)
                ? AgentHealth.Run()
                : AgentProcess.Run(arguments, indexedEnvironment);
        } catch (ConfigurationException exception) {
            Console.Error.WriteLine("docker-jenkins-agent: " + exception.Message);
            return 1;
        } catch (Exception) {
            Console.Error.WriteLine("docker-jenkins-agent: the agent entrypoint failed");
            return 1;
        }
    }
}
