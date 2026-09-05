# Cross-platform Jenkins inbound agent

This repository builds Linux and Windows variants of one Docker image for
Jenkins agents. Both variants extend the official
[`jenkins/inbound-agent`](https://hub.docker.com/r/jenkins/inbound-agent)
image and use Java 21.

The image is intended for Jenkins jobs that need Git, Unity Version Control
(the `cm` command), Node.js, PowerShell, and access to a Docker daemon supplied
by the host. This includes Jenkinsfiles that use the Docker Pipeline plugin's
`docker.image("image").inside { ... }` syntax. The image contains only the
Docker client; it does not contain or run a Docker daemon.

## Image variants

| Variant | Dockerfile | Base image | Docker context |
| --- | --- | --- | --- |
| Linux | `linux/Dockerfile` | `jenkins/inbound-agent:trixie-jdk21` | `linux` |
| Windows LTSC 2019 | `windows/Dockerfile` | `jenkins/inbound-agent:jdk21-windowsservercore-ltsc2019` | `windows` |
| Windows LTSC 2022 | `windows/Dockerfile` | `jenkins/inbound-agent:jdk21-windowsservercore-ltsc2022` | `windows` |

Both variants provide the same agent-level capabilities:

| Capability | Linux | Windows |
| --- | --- | --- |
| Jenkins inbound-agent runtime | Yes | Yes |
| Java 21 | Yes | Yes |
| Git and Git LFS | Yes | Yes |
| Unity Version Control 11 CLI (`cm`) | Core client package | Client installer |
| Docker CLI 29 | Client binary only | Client binary only |
| Node.js 24 (`node`, `npm`, and `npx`) | Yes | Yes |
| PowerShell 7 (`pwsh`) | Yes | Yes |
| Indexed YAML agent configuration | Yes | Yes |

Docker Compose is intentionally not installed. The Jenkins Docker Pipeline
plugin uses the Docker CLI directly and does not require Compose for
`docker.image(...).inside { ... }`.

Both Dockerfiles follow Docker major version 29, Node.js major version 24,
PowerShell major version 7, and Unity Version Control major version 11. Each
platform resolves and installs its newest available stable release in that
major line during the build. Linux and Windows versions can differ when a
release is not yet available for both platforms, but neither image silently
upgrades to a new major version.

Remote package indexes are explicit Dockerfile inputs, so publishing a new
compatible release invalidates the installation layer even when a previous
build cache is available. Both variants currently target x86-64 hosts.

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

Build directly from the repository root:

```text
docker --context linux build --tag tmp/jenkins-agent:latest --file linux/Dockerfile .
docker --context windows build --tag tmp/jenkins-agent:latest --file windows/Dockerfile .
```

The Windows Dockerfile defaults to LTSC 2019. Build either Windows variant
explicitly with its matching image tag and `OS_BASE` build argument:

```text
docker --context windows build --build-arg OS_BASE=ltsc2019 --tag tmp/jenkins-agent:ltsc2019 --file windows/Dockerfile .
docker --context windows build --build-arg OS_BASE=ltsc2022 --tag tmp/jenkins-agent:ltsc2022 --file windows/Dockerfile .
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

The inherited `jenkins/inbound-agent` entrypoint also continues to accept
connection arguments through `command`:

```yaml
services:
  agent:
    image: faulo/jenkins-agent:latest
    command: ["-url", "http://jenkins:8080", "-secret", "xxx", "-name", "yyy", "-webSocket"]
```

Set `JENKINS_WEB_SOCKET: "true"` in the environment-based form when the agent
should connect over WebSocket.

### Indexed agent configuration

For a global Docker Swarm service, mount a YAML document containing one agent
environment per index and set both of these variables:

- `JENKINS_CONFIG_FILE`: the explicit path to the mounted YAML document.
- `JENKINS_CONFIG_INDEX`: the exact, case-sensitive top-level key to select.

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

Both `AGENT_WORKDIR` image metadata and the inbound launcher's
`JENKINS_AGENT_WORKDIR` are set to the corresponding remote root. The
`workspace` directory is deliberately a child of that root and is declared as
a Docker volume in each image. It is the path to mount when workspace
persistence or host access is required.

Refer to the
[`jenkins/inbound-agent` documentation](https://github.com/jenkinsci/docker-agent)
for the supported Jenkins connection modes and launch examples.

## Health check

Both variants use `/jenkins/agent --health` or
`C:/jenkins/agent.exe --health` as their Docker health check. The command first
validates and loads any indexed configuration, then asks the bundled Jenkins
Remoting JAR to report its version. A successful probe confirms that the
entrypoint, Java runtime, Remoting JAR, and mounted configuration are usable;
it does not test controller connectivity.

## Runtime defaults and security

- Linux processes run as `root`; Windows processes run as
  `ContainerAdministrator`.
- `JAVA_OPTS` sets the Jenkins Git client operation timeout to 60 minutes.
- Git treats every repository path as a safe directory. This avoids ownership
  checks for host-mounted workspaces but removes that Git security boundary.
- Linux installs Docker from Docker's signed APT repository. The Windows Unity
  Version Control installer must have a valid Unity Authenticode signature.
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
