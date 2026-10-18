# Jenkins inbound agent for Linux and Windows containers

[![Docker](https://github.com/Faulo/docker-jenkins-agent/actions/workflows/docker.yml/badge.svg?branch=main)](https://github.com/Faulo/docker-jenkins-agent/actions/workflows/docker.yml) [![Docker image updated](https://img.shields.io/docker/last-updated/faulo/jenkins-agent/latest)](https://hub.docker.com/r/faulo/jenkins-agent/tags?name=latest) [![License: MIT](https://img.shields.io/github/license/Faulo/docker-jenkins-agent)](LICENSE) [![Platforms: Linux and Windows amd64](https://img.shields.io/badge/platforms-Linux%20%7C%20Windows%20amd64-0078D4)](#available-images)

[`faulo/jenkins-agent`](https://hub.docker.com/r/faulo/jenkins-agent) connects
Linux and Windows containers to a Jenkins controller as inbound agents. It
includes Java 25, Git and Git LFS, Unity Version Control, Node.js, PowerShell,
and the Docker CLI for running jobs and launching separate build containers.

The launcher downloads Jenkins Remoting from your controller at startup,
supports environment-based or indexed YAML configuration, and reports the
agent's connection state through Docker health checks.

## Available images

| Tag | Platform | Runtime base |
| --- | --- | --- |
| `latest` | Linux amd64 and Windows amd64 | Debian Trixie Slim; Windows Server Core LTSC 2019 and LTSC 2022 |
| `latest-linux`, `latest-linux-amd64` | Linux amd64 | Debian Trixie Slim |
| `latest-windowsservercore` | Windows amd64 | Windows Server Core LTSC 2019 and LTSC 2022 through `faulo/choco` |
| `latest-windowsservercore-ltsc2019` | Windows amd64 | Windows Server Core LTSC 2019 through `faulo/choco` |
| `latest-windowsservercore-ltsc2022` | Windows amd64 | Windows Server Core LTSC 2022 through `faulo/choco` |

Use `latest` for automatic platform selection or an LTSC-specific tag to
select a Windows release. Windows containers require a compatible Windows
host. Tags receive updates in place; pin an image digest when you need an
exact image.

## Quick start

Create an agent in Jenkins and select **Launch agents by connecting it to the
controller**. Set its remote root directory to `/jenkins` for Linux or
`C:/jenkins` for Windows.

Set `JENKINS_URL`, `JENKINS_AGENT_NAME`, and `JENKINS_SECRET` in your shell
or a private Compose environment file using the connection details Jenkins
provides. The controller URL must be reachable from the container.

For a Linux container, save this as `compose.yaml`:

```yaml
services:
  agent:
    image: faulo/jenkins-agent:latest
    restart: unless-stopped
    environment:
      JENKINS_URL: ${JENKINS_URL:?Set JENKINS_URL}
      JENKINS_AGENT_NAME: ${JENKINS_AGENT_NAME:?Set JENKINS_AGENT_NAME}
      JENKINS_SECRET: ${JENKINS_SECRET:?Set JENKINS_SECRET}
    volumes:
      - jenkins-workspace:/jenkins/workspace

volumes:
  jenkins-workspace:
```

For a Windows-container engine, use the same configuration with this volume
target instead:

```yaml
    volumes:
      - jenkins-workspace:c:/jenkins/workspace
```

Start the agent and inspect its state:

```text
docker compose up --detach
docker compose ps
docker compose logs agent
```

WebSocket transport is enabled by default. The container makes the outbound
connection to Jenkins; this setup requires no published agent port.

At every agent startup, the launcher downloads
`<JENKINS_URL>/jnlpJars/agent.jar`, validates the response, and installs it
atomically beside the launcher. The controller must serve that endpoint as a
JAR. A missing or invalid URL, failed download, or non-JAR response stops
startup, including when an earlier download exists.

## Included tools

| Tool | Linux | Windows |
| --- | --- | --- |
| Java 25 runtime | Debian OpenJDK | Eclipse Temurin |
| Jenkins Remoting | Downloaded from the controller at startup | Downloaded from the controller at startup |
| Git and Git LFS | Included | Included |
| Unity Version Control (`cm`) | Included | Included |
| Node.js, npm, and npx | Included | Included |
| PowerShell (`pwsh`) | Included | Included |
| Docker CLI | Included | Included |

The runtime image contains the Java runtime; use a separate build environment
when a job needs JDK tools such as `javac`. Docker Compose and a Docker daemon
are not included. The Windows variants receive their prerequisites and
installer compatibility fixes from
[`faulo/choco`](https://github.com/Faulo/docker-choco).

## Agent configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `JENKINS_URL` | required | Absolute HTTP or HTTPS controller URL, including any context path |
| `JENKINS_AGENT_NAME` | none | Inbound agent name configured in Jenkins |
| `JENKINS_SECRET` | none | Inbound agent secret supplied by Jenkins |
| `JENKINS_WEB_SOCKET` | `true` | Enable WebSocket transport; accepts `true`, `false`, `1`, or `0` |
| `JENKINS_AGENT_WORKDIR` | platform remote root | Remoting working directory |
| `JENKINS_TUNNEL` | none | Optional tunnel address for TCP transport |
| `JENKINS_CONFIG_FILE` | none | Mounted YAML file for indexed configuration |
| `JENKINS_CONFIG_INDEX` | none | Case-sensitive key selecting one record from that file |

WebSocket values are case-insensitive and ignore surrounding whitespace.
An unset or blank value uses the default; other values fail startup.

The launcher also supports `JENKINS_NAME` as a legacy agent-name alias and the
Remoting settings `JENKINS_DIRECT_CONNECTION`, `JENKINS_INSTANCE_IDENTITY`,
and `JENKINS_PROTOCOLS`. Explicit Remoting arguments take precedence over
environment-derived arguments.

`JENKINS_JAVA_BIN` selects the Java executable; otherwise the launcher uses
`JAVA_HOME` and then `PATH`. `JENKINS_JAVA_OPTS` falls back to `JAVA_OPTS`,
and `REMOTING_OPTS` supplies additional Remoting arguments. Quoted option
values remain single arguments on both platforms.

The default command is `serve`. Docker command arguments and Compose
`command:` are passed to the fixed Java launcher. For example,
`command: ["serve", "-webSocket"]` supplies an explicit Remoting option.
The `health` command runs the connection health probe.

This image hosts the Jenkins agent itself. For Docker Pipeline
`docker.image(...).inside { ... }` build environments, select a separate tool
image whose command the plugin can replace.

### Indexed YAML configuration

Mount a YAML document containing one environment mapping per index:

```yaml
linux-builder:
  JENKINS_AGENT_NAME: linux-builder
  JENKINS_SECRET: replace-with-agent-secret
windows-builder:
  JENKINS_AGENT_NAME: windows-builder
  JENKINS_SECRET: replace-with-agent-secret
```

Set both `JENKINS_CONFIG_FILE` and `JENKINS_CONFIG_INDEX` to select a record.
The file can be mounted as a Docker secret; standard secret paths are
`/run/secrets/<name>` on Linux and
`C:/ProgramData/Docker/secrets/<name>` for Windows Swarm services.

Each selected environment name and value must be a scalar that can be
represented safely as an environment variable. The selected mapping overrides
the container environment. The launcher rejects missing files, malformed
documents, missing or ambiguous indices, and invalid mappings before connecting
to Jenkins. Diagnostics identify the file and index without printing selected
values.

For a global Swarm service, `JENKINS_CONFIG_INDEX: '{{.Node.Hostname}}'` can
select the record matching each task's host. Keep the YAML document private
because it contains agent credentials.

## Workspaces and Docker access

Configure the Jenkins node's remote root to match the image:

| Platform | Remote root | Persistent workspace |
| --- | --- | --- |
| Linux | `/jenkins` | `/jenkins/workspace` |
| Windows | `C:/jenkins` | `C:/jenkins/workspace` |

The workspace is a child of the remote root and is declared as a Docker volume.
Persist it with a named volume or a host mount when jobs need reusable workspace
state. Both `AGENT_WORKDIR` and `JENKINS_AGENT_WORKDIR` default to the remote root.

Jobs that use the included Docker CLI need access to a Docker daemon. Add the
appropriate mount to the agent service when that capability is required.

Linux:

```yaml
    volumes:
      - jenkins-workspace:/jenkins/workspace
      - /var/run/docker.sock:/var/run/docker.sock
```

Windows:

```yaml
    volumes:
      - type: volume
        source: jenkins-workspace
        target: c:/jenkins/workspace
      - type: npipe
        source: '\\.\pipe\docker_engine'
        target: '\\.\pipe\docker_engine'
```

The default Docker endpoint is `unix:///var/run/docker.sock` on Linux and
`npipe:////./pipe/docker_engine` on Windows. Access to that endpoint grants
jobs control over the attached engine; use it with trusted workloads.

When jobs launch nested build containers through Docker Pipeline, the agent
and daemon must see the same workspace filesystem. Jenkins can use
`--volumes-from` to share the agent container's workspace with those containers.

## Health checks

Docker health checks observe the Remoting connection through a local status
record. The probe validates the record's process identity and freshness without
opening another controller connection or submitting work over the Remoting
channel.

A confirmed connection is healthy. Startup and reconnecting states receive a
five-minute grace period; after that, they are unhealthy while Remoting continues
its own reconnect attempts. Repeated reconnect callbacks do not reset the
grace period. Fatal errors and a completed Remoting engine fail health
immediately.

| Variable | Default | Purpose |
| --- | --- | --- |
| `JENKINS_HEALTH_INTERVAL_SECONDS` | `10` | Connection observation interval |
| `JENKINS_HEALTH_TIMEOUT_SECONDS` | `300` | Startup and reconnect grace period |
| `JENKINS_HEALTH_STALE_SECONDS` | `30` | Maximum status-record age |
| `JENKINS_HEALTH_FILE` | platform status path | Override the status file for diagnostics |

Timing overrides must be positive integer seconds.

## Runtime defaults

Linux runs as `root`; Windows runs as `ContainerAdministrator`. Git trusts
all repository directories to support mounted workspaces, and its client
operation timeout defaults to 60 minutes. Review these permissions before
allowing untrusted jobs.

Linux Unity Version Control packages come from a legacy APT source whose
repository signature is not verified. Other configured APT sources use signed
repositories. The Windows Unity Version Control installer requires a valid
Unity Authenticode signature.

## Maintenance and updates

Linux amd64 and Windows Server Core LTSC 2019 and LTSC 2022 images are rebuilt
monthly to pick up base-image and tool updates. Java follows major version 25;
other tools follow their configured package sources, including major-version
updates. Linux and Windows tool versions can differ as those sources change.

Recreate agents with a newly pulled image to adopt image updates. Remoting is
refreshed from the controller on every agent startup. Pin an image digest
when you need an exact image; controller-provided Remoting remains dynamic.

## Tests and development

Launcher unit tests cover argument parsing, environment normalization, indexed
configuration, controller JAR acquisition, process supervision, and connection
health. Run them from the repository root with a Java 25 JDK and the Maven
Wrapper.

Linux or macOS:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

Windows:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

The repository also includes Pester image integration tests for platform tools,
entrypoint behavior, controller-supplied Remoting, and health monitoring. They
require a compatible container engine and a reachable Jenkins controller.

Both Dockerfiles use the repository root as their build context. Build on an
engine configured for the selected container OS:

```text
docker build --pull --file linux/Dockerfile --tag tmp/jenkins-agent:latest .
docker build --pull --file windows/Dockerfile --tag tmp/jenkins-agent:latest .
```

The Windows build defaults to LTSC 2019. For LTSC 2022, select matching runtime
and builder releases:

```text
docker build --pull --file windows/Dockerfile --build-arg OS_BASE=ltsc2022 --build-arg BUILD_OS_BASE=ltsc2022 --tag tmp/jenkins-agent:ltsc2022 .
```

## Support and contributions

[Open an issue](https://github.com/Faulo/docker-jenkins-agent/issues) with the
image tag or digest, container OS, transport mode, and a minimal reproduction.
For Windows, include the host version and isolation mode. Remove credentials
from logs and configuration before sharing them.

Pull requests are welcome. Include tests for changes to launcher behavior and
update the documentation when public settings change.

## License

The original launcher code, Dockerfiles, scripts, and documentation are licensed
under the [MIT License](LICENSE). Jenkins Remoting, Java, bundled tools, and base
images retain their respective licenses.
