using System;

namespace Agent;

sealed class ConfigurationException : Exception {
    public ConfigurationException(string message) : base(message) {
    }
}
