package net.slothsoft.jenkins.agentlauncher;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class AgentHealthMonitor {
    private static final int DEFAULT_INTERVAL_SECONDS = 10;
    private static Monitor monitor;

    private AgentHealthMonitor() {
    }

    @SuppressWarnings("unused")
    public static void premain(String arguments) throws IOException {
        start();
    }

    static synchronized void start() throws IOException {
        if (monitor != null) {
            return;
        }
        Monitor created = new Monitor();
        try {
            created.writeStatus();
        } catch (RuntimeException exception) {
            throw new IOException("failed to initialize Jenkins Remoting health observer", exception);
        } catch (IOException exception) {
            throw new IOException("failed to initialize Jenkins Remoting health status", exception);
        }
        monitor = created;
        Thread thread = new Thread(created, "jenkins-remoting-health-observer");
        thread.setDaemon(true);
        thread.start();
    }

    private static final class Monitor implements Runnable {
        private final Path statusFile;
        private final int intervalSeconds;
        private final long pid;
        private final long processStart;
        private final Set<Object> observedEngines = Collections.newSetFromMap(new IdentityHashMap<>());
        private String state = "starting";
        private String diagnostic = "waiting-for-remoting";
        private long stateSince = System.currentTimeMillis();
        private long lastConnected;
        private Object currentChannel;
        private boolean sawChannel;
        private boolean listenerAttached;

        private Monitor() {
            String configuredFile = System.getenv(AgentHealth.HEALTH_FILE);
            statusFile = Path.of(configuredFile == null || configuredFile.isBlank()
                ? isWindows() ? "C:/jenkins/agent-health.status" : "/jenkins/agent-health.status"
                : configuredFile);
            intervalSeconds = AgentHealth.readPositiveSeconds(
                "JENKINS_HEALTH_INTERVAL_SECONDS",
                System.getenv("JENKINS_HEALTH_INTERVAL_SECONDS"),
                DEFAULT_INTERVAL_SECONDS
            );
            AgentHealth.readPositiveSeconds(
                AgentHealth.HEALTH_TIMEOUT_SECONDS,
                System.getenv(AgentHealth.HEALTH_TIMEOUT_SECONDS),
                AgentHealth.DEFAULT_TIMEOUT_SECONDS
            );
            AgentHealth.readPositiveSeconds(
                AgentHealth.HEALTH_STALE_SECONDS,
                System.getenv(AgentHealth.HEALTH_STALE_SECONDS),
                AgentHealth.DEFAULT_STALE_SECONDS
            );
            pid = ProcessHandle.current().pid();
            processStart = ProcessHandle.current().info().startInstant()
                .orElse(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()))
                .toEpochMilli();
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    observeEngine();
                    observeChannelFallback();
                    writeStatusIgnoringFailure();
                    TimeUnit.SECONDS.sleep(listenerAttached ? intervalSeconds : 1);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                transition("terminal", "observer-interrupted");
                writeStatusIgnoringFailure();
            }
        }

        private void observeEngine() {
            synchronized (this) {
                if (listenerAttached || state.equals("terminal")) {
                    return;
                }
            }
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getClass().getName().equals("hudson.remoting.Engine")) {
                    attach(thread);
                    return;
                }
            }
        }

        private void attach(Object engine) {
            if (engine == null) {
                return;
            }
            synchronized (this) {
                if (observedEngines.contains(engine)) {
                    return;
                }
            }
            try {
                ClassLoader classLoader = engine.getClass().getClassLoader();
                Class<?> listenerClass = Class.forName("hudson.remoting.EngineListener", false, classLoader);
                Object listener = Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{listenerClass},
                    this::onEngineEvent
                );
                Method addListener = engine.getClass().getMethod("addListener", listenerClass);
                addListener.invoke(engine, listener);
                synchronized (this) {
                    observedEngines.add(engine);
                    listenerAttached = true;
                    diagnostic = "observing-remoting-events";
                }
                writeStatusIgnoringFailure();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                synchronized (this) {
                    diagnostic = "remoting-listener-unavailable";
                }
                writeStatusIgnoringFailure();
            }
        }

        private Object onEngineEvent(Object proxy, Method method, Object[] arguments) {
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (methodName) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "docker-jenkins-agent health listener";
                    default -> null;
                };
            }
            synchronized (this) {
                switch (methodName) {
                    case "status" -> {
                        if (arguments != null && arguments.length > 0 && arguments[0] instanceof String status
                            && status.strip().equalsIgnoreCase("Connected")) {
                            markConnected("remoting-connected-status");
                        }
                    }
                    case "onDisconnect", "onReconnect" -> transition("reconnecting", "waiting-for-reconnect");
                    case "error" -> transition("terminal", "remoting-error");
                    case "completed" -> transition("terminal", "remoting-completed");
                    default -> {
                        // Unknown callbacks are intentionally ignored for forward compatibility.
                    }
                }
            }
            writeStatusIgnoringFailure();
            return null;
        }

        private void observeChannelFallback() {
            synchronized (this) {
                if (state.equals("terminal")) {
                    return;
                }
            }
            try {
                Object channel = findActiveChannel();
                synchronized (this) {
                    if (channel == null) {
                        if (!listenerAttached && (currentChannel != null || sawChannel)) {
                            currentChannel = null;
                            transition("reconnecting", "waiting-for-channel");
                        }
                    } else {
                        currentChannel = channel;
                        sawChannel = true;
                        if (!state.equals("connected")) {
                            markConnected("active-channel");
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                synchronized (this) {
                    diagnostic = "channel-observer-unavailable";
                }
            }
        }

        private void markConnected(String newDiagnostic) {
            if (state.equals("terminal")) {
                return;
            }
            lastConnected = System.currentTimeMillis();
            transition("connected", newDiagnostic);
        }

        private void transition(String newState, String newDiagnostic) {
            if (state.equals("terminal") && !newState.equals("terminal")) {
                return;
            }
            if (!state.equals(newState)) {
                state = newState;
                stateSince = System.currentTimeMillis();
            }
            diagnostic = newDiagnostic;
        }

        private synchronized void writeStatus() throws IOException {
            String content = "version=2\n"
                + "pid=" + pid + "\n"
                + "processStart=" + processStart + "\n"
                + "state=" + state + "\n"
                + "stateSince=" + stateSince + "\n"
                + "lastConnected=" + lastConnected + "\n"
                + "updated=" + System.currentTimeMillis() + "\n"
                + "diagnostic=" + diagnostic + "\n";
            Path absoluteFile = statusFile.toAbsolutePath();
            Files.createDirectories(absoluteFile.getParent());
            Path temporaryFile = absoluteFile.resolveSibling(absoluteFile.getFileName() + ".tmp-" + pid);
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporaryFile, absoluteFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void writeStatusIgnoringFailure() {
            try {
                writeStatus();
            } catch (IOException exception) {
                // A missing heartbeat makes the external health check fail closed.
            }
        }
    }

    private static Object findActiveChannel() throws ReflectiveOperationException {
        Class<?> channelClass = Class.forName("hudson.remoting.Channel");
        Field registry = findChannelRegistry(channelClass);
        registry.setAccessible(true);
        Object registryValue = registry.get(null);
        if (!(registryValue instanceof Map<?, ?> activeChannels)) {
            throw new IllegalStateException("unexpected Remoting channel registry");
        }
        Method isClosingOrClosed = channelClass.getMethod("isClosingOrClosed");
        // Remoting protects its weak registry with the registry object's monitor.
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (activeChannels) {
            for (Object candidate : activeChannels.keySet()) {
                if (channelClass.isInstance(candidate) && !((Boolean) isClosingOrClosed.invoke(candidate))) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Field findChannelRegistry(Class<?> channelClass) throws NoSuchFieldException {
        try {
            return channelClass.getDeclaredField("ACTIVE_CHANNELS");
        } catch (NoSuchFieldException exception) {
            for (Field field : channelClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                    return field;
                }
            }
            throw exception;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }
}
