# Cross-platform Jenkins inbound agent

This repository builds Linux and Windows variants of one Docker image for
Jenkins agents. The Linux variant uses Debian Trixie Slim, and the Windows
variants use the matching .NET Framework 4.8 Windows Server Core runtime image.
Linux installs Debian OpenJDK 21, Windows installs Microsoft OpenJDK 21, and
both install Git with Git LFS. A shared Java entrypoint launches Jenkins
Remoting directly; the image does not include or call the inbound-agent shell
or PowerShell launchers. The Windows base remains the .NET Framework runtime
because Chocolatey requires Windows PowerShell and .NET Framework; the agent
entrypoint itself does not use .NET.

At each Jenkins-agent startup, the entrypoint downloads `jnlpJars/agent.jar`
from `JENKINS_URL` next to `/jenkins/launcher.jar`. This
keeps Remoting aligned with the controller instead of the image's build date.
The download is validated and installed atomically. Startup fails if the
controller URL is missing, invalid, or does not return a JAR.

The image is intended for Jenkins jobs that need Git, Unity Version Control
(the `cm` command), Node.js, PowerShell, and access to a Docker daemon supplied
by the host. This includes Jenkinsfiles that use the Docker Pipeline plugin's
`docker.image("image").inside { ... }` syntax. The image contains only the
Docker client; it does not contain or run a Docker daemon.

## Image variants

| Variant | Dockerfile | Base image | Docker context |
| --- | --- | --- | --- |
| Linux | `linux/Dockerfile` | `debian:trixie-slim` | `linux` |
| Windows LTSC 2019 | `windows/Dockerfile` | `mcr.microsoft.com/dotnet/framework/runtime:4.8-windowsservercore-ltsc2019` | `windows` |
| Windows LTSC 2022 | `windows/Dockerfile` | `mcr.microsoft.com/dotnet/framework/runtime:4.8-windowsservercore-ltsc2022` | `windows` |

Both variants provide the same agent-level capabilities:

| Capability | Linux | Windows |
| --- | --- | --- |
| Jenkins Remoting runtime | Downloaded at startup | Downloaded at startup |
| Java 21 | Yes | Yes |
| Git and Git LFS | Yes | Yes |
| Unity Version Control CLI (`cm`) | Core client package | Client installer |
| Docker CLI | Client binary only | Client binary only |
| Node.js (`node`, `npm`, and `npx`) | Yes | Yes |
| PowerShell (`pwsh`) | Yes | Yes |
| Indexed YAML agent configuration | Yes | Yes |

Docker Compose is intentionally not installed. The Jenkins Docker Pipeline
plugin uses the Docker CLI directly and does not require Compose for
`docker.image(...).inside { ... }`.

Both Dockerfiles follow Java major version 21. Every other tool follows the
newest package available from its configured APT or Chocolatey source at build
time, including major-version upgrades. Linux and Windows versions can differ
when their package repositories publish on different schedules.

The package managers refresh their indexes whenever the installation layer is
executed. Both variants currently target x86-64 hosts.

Linux runtime packages are declared in `linux/jenkins-agent.packages` and
installed together through APT. NodeSource's moving current repository supplies
Node.js and its bundled npm. On Windows, the corresponding dependencies are
declared in `windows/jenkins-agent.nuspec` and installed together through
Chocolatey. The Windows meta-package wraps Unity Version Control's signed
vendor installer because it is not published as a Chocolatey package.

The Linux container connects to
`unix:///var/run/docker.sock`. The Windows container connects to
`npipe:////./pipe/docker_engine`. The matching socket or named pipe must be
made available when the container is started.

## Prerequisites

- A Docker client on the host.
- A Linux Docker daemon available through the `linux` Docker context.
- A Windows Docker daemon available through the `windows` Docker context.
- A Windows host version compatible with the selected
  `windowsservercore-ltsc2019` or `windowsservercore-ltsc2022` base image when
  building or running a Windows variant.

Check the daemons before building:

```text
docker --context linux info
docker --context windows info
```

The commands must report `linux` and `windows`, respectively.

## Build

The project configuration in `.env` sets the image name. With the repository's
default configuration, local builds are tagged as
`tmp/jenkins-agent:latest`.

Both Dockerfiles compile and test the Maven project in `src/` before assembling
the runtime image. Opening the repository root as a Maven project in IntelliJ
uses Java 21 and the checked-in `pom.xml` directly. The launcher uses the Java
package `net.slothsoft.jenkins.agentlauncher` and Maven coordinates
`net.slothsoft.jenkins:agent-launcher`.

Build directly from the repository root:

```text
docker --context linux build --tag tmp/jenkins-agent:latest --file linux/Dockerfile .
docker --context windows build --tag tmp/jenkins-agent:latest --file windows/Dockerfile .
```

The Windows Dockerfile uses an Eclipse Temurin Java 21 build stage and the
checked-in Maven Wrapper, without installing Chocolatey in the builder. It
defaults to an `1809` builder for the LTSC 2019 runtime. Build either Windows
variant explicitly with its matching image tag and build arguments:

```text
docker --context windows build --build-arg OS_BASE=ltsc2019 --build-arg BUILD_OS_BASE=1809 --tag tmp/jenkins-agent:ltsc2019 --file windows/Dockerfile .
docker --context windows build --build-arg OS_BASE=ltsc2022 --build-arg BUILD_OS_BASE=ltsc2022 --tag tmp/jenkins-agent:ltsc2022 --file windows/Dockerfile .
```

On Windows, the following interactive entry points provide the same builds and
pause before closing so their output remains visible:

- `docker-build-linux.bat`
- `docker-build-windows.bat`

The shared `docker-build.bat` script accepts `linux` or `windows` as its first
argument. When called without an argument, it builds for the active Docker
daemon.

## Test

The root `.env` defines the local image name, test command, shared run options,
and platform-specific run options. The checked-in test currently verifies that
Java starts successfully and reports its version. The shared test runner
executes the command through the platform's shell so the same command works
with both upstream agent entrypoints.

Run the configured test through:

- `docker-test-linux.bat`
- `docker-test-windows.bat`

The shared `docker-test.bat` script also accepts `linux` or `windows` as its
first argument. The platform-specific run options are where the Docker socket
or named pipe and any required environment are supplied.

Do not commit credentials to `.env`. Treat access to a host Docker socket or
named pipe as privileged access to that Docker daemon.

## Use with Jenkins

The command can be omitted when the controller URL, agent secret, and agent
name are supplied as environment variables:

```yaml
services:
  agent:
    image: faulo/jenkins-agent:latest
    environment:
      JENKINS_URL: http://jenkins:8080/
      JENKINS_SECRET: xxx
      JENKINS_AGENT_NAME: yyy
```

The Java entrypoint also accepts Remoting connection arguments through `command`.
`JENKINS_URL` must still be present in the environment because it identifies
the controller that supplies `jnlpJars/agent.jar`:

```yaml
services:
  agent:
    image: faulo/jenkins-agent:latest
    environment:
      JENKINS_URL: http://jenkins:8080/
    command: ["-url", "http://jenkins:8080", "-secret", "xxx", "-name", "yyy", "-webSocket"]
```

`JENKINS_WEB_SOCKET` defaults to `true`. It accepts `1` or `true` to enable
WebSocket and `0` or `false` to disable it. Matching is case-insensitive and
ignores surrounding whitespace. An unset, empty, or whitespace-only value uses
the default; every other value terminates startup with a configuration error.
An explicit `-webSocket` command argument remains authoritative.

The entrypoint maps the launcher environment used by the official Jenkins
images to Remoting arguments: `JENKINS_SECRET`, `JENKINS_AGENT_NAME` (and the
legacy `JENKINS_NAME` alias), `JENKINS_TUNNEL`, `JENKINS_URL`,
`JENKINS_AGENT_WORKDIR`, `JENKINS_WEB_SOCKET`, `JENKINS_DIRECT_CONNECTION`,
`JENKINS_INSTANCE_IDENTITY`, and `JENKINS_PROTOCOLS`. Explicit command
arguments take precedence and are not duplicated. `JENKINS_JAVA_BIN` selects
Java; otherwise `JAVA_HOME` and then the platform `PATH` are used.
`JENKINS_JAVA_OPTS` falls back to `JAVA_OPTS`, and `REMOTING_OPTS` supplies
additional Remoting arguments. Quoted option values are kept as single
arguments on both platforms.

### Indexed agent configuration

For a global Docker Swarm service, mount a YAML document containing one agent
environment per index and set both of these variables:

- `JENKINS_CONFIG_FILE`: the explicit path to the mounted YAML document.
- `JENKINS_CONFIG_INDEX`: the exact, non-empty, case-sensitive top-level key to
  select.

For example:

```yaml
Dende:
  JENKINS_AGENT_NAME: Dende
  JENKINS_SECRET: example-secret
groke:
  JENKINS_AGENT_NAME: Mörkö
  JENKINS_SECRET: example-secret
```

The selected value must be a mapping whose keys and values are YAML scalars
that can be represented as environment variables. The selected mapping is
applied after the container environment, so it is authoritative when a name
is present in both places. Values are never printed by the entrypoint.

A Linux Swarm service can select the record for the node hosting each task:

```yaml
services:
  agent:
    image: faulo/jenkins-agent:latest
    environment:
      JENKINS_CONFIG_FILE: /run/secrets/jenkins_agents_v1
      JENKINS_CONFIG_INDEX: '{{.Node.Hostname}}'
    secrets:
      - jenkins_agents_v1
    deploy:
      mode: global
      placement:
        constraints:
          - node.labels.slothsoft.jenkins-agent == true
```

Use the platform's actual secret mount path on Windows, for example
`C:/ProgramData/Docker/secrets/jenkins_agents_v1`. If either configuration
variable is set without the other, the file cannot be parsed, the index is
missing or is not a mapping, or an entry cannot be represented safely, the
container exits before starting Jenkins. Errors identify the file and index
but do not include selected values.

In addition to the Jenkins connection settings, mount the platform's Docker
endpoint if jobs need to invoke Docker. Any job using this image can then use
the host daemon through the included Docker CLI.

For `docker.image(...).inside { ... }`, the Jenkins agent and Docker daemon
must also see the same workspace filesystem. Jenkins detects that the agent is
running in a container and uses `--volumes-from` to share its workspace with
the nested build container.

Configure the Jenkins node's **Remote root directory** to match the image:

| Variant | Remote root | Job workspace root |
| --- | --- | --- |
| Linux | `/jenkins` | `/jenkins/workspace` |
| Windows | `C:\jenkins` | `C:\jenkins\workspace` |

Both `AGENT_WORKDIR` and `JENKINS_AGENT_WORKDIR` are set to the corresponding
remote root. The `workspace` directory is deliberately a child of that root
and is declared as a Docker volume in each image. It is the path to mount when
workspace persistence or host access is required.

## Health check

Both variants use `java -jar /jenkins/launcher.jar --health` (with the
corresponding `C:/jenkins` path on Windows) as their Docker health check. The
same JAR is loaded as a Java agent inside the Remoting process, where it
attaches a listener to Remoting's engine. Typed disconnect, reconnect, error,
and completion callbacks drive the state machine; the deliberately narrow
`Connected` status signal confirms a successful connection. A non-blocking
active-channel observation remains as a compatibility fallback. Unknown status
texts and callbacks are ignored.

The observer writes an atomic local status record. The short-lived Java health
probe only validates that cached record against the Remoting process and a
30-second freshness limit; it neither opens a controller connection nor queues
work on the Remoting channel. A confirmed connection remains healthy without
periodic round trips. Startup and reconnecting states remain healthy for a
five-minute grace period, then become unhealthy while Remoting continues its
own reconnect attempts. A fatal Remoting error or completed engine is an
immediate hard failure. Repeated reconnect callbacks do not restart the grace
period.

`JENKINS_HEALTH_INTERVAL_SECONDS`, `JENKINS_HEALTH_TIMEOUT_SECONDS`, and
`JENKINS_HEALTH_STALE_SECONDS` override the 10-second observation interval,
300-second startup/reconnect grace period, and 30-second status freshness
limit. All must be positive integer seconds. `JENKINS_HEALTH_FILE` overrides
the platform-specific status path, primarily for diagnostics.

## Runtime defaults and security

- Linux processes run as `root`; Windows processes run as
  `ContainerAdministrator`.
- Linux uses the built-in `C.UTF-8` locale so Java preserves non-ASCII agent
  names in native protocol data.
- `JAVA_OPTS` sets the Jenkins Git client operation timeout to 60 minutes.
- Git treats every repository path as a safe directory. This avoids ownership
  checks for host-mounted workspaces but removes that Git security boundary.
- Linux consumes signed Debian, Microsoft, Docker, and NodeSource APT
  repositories. Windows consumes the Chocolatey Community Repository for its
  shared tool manifest, while the Unity Version Control installer must have a
  valid Unity Authenticode signature.
- The Linux Unity Version Control repository currently requires an
  unauthenticated APT install because its legacy repository signature is
  rejected by current Debian policy.

These defaults are suitable only for trusted Jenkins workloads and should be
reviewed before exposing agents to untrusted jobs.

## Automation

The GitHub Actions workflow publishes the configured image through the shared
`Faulo/workflows-docker` workflow. The Windows variants are named `ltsc2019`
and `ltsc2022`, producing the platform tags `latest-ltsc2019` and
`latest-ltsc2022`. The combined `latest` manifest lists LTSC 2022 first so
newer compatible hosts prefer it while LTSC 2019 hosts retain a matching
fallback. The workflow runs when either Dockerfile changes, can be started
manually, and runs monthly to pick up refreshed base images.

The shared workflow persists Linux BuildKit layers in the GitHub Actions cache.
For Windows, it pulls the previously published platform image and passes it to
Docker as the build cache source. Local Docker builds use each context's normal
layer cache automatically.
