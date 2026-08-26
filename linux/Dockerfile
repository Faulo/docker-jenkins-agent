FROM jenkins/inbound-agent:trixie-jdk21

ARG DEBIAN_FRONTEND=noninteractive
ARG DOCKER_MAJOR=29
ARG NODE_MAJOR=24
ARG PLASTIC_MAJOR=11
ARG POWERSHELL_MAJOR=7

# Jenkins
USER root
ENV USER=root \
    HOME=/root \
    AGENT_WORKDIR=/jenkins \
    JENKINS_AGENT_WORKDIR=/jenkins \
    JENKINS_WEB_SOCKET=true \
    JAVA_OPTS="-Dorg.jenkinsci.plugins.gitclient.Git.timeOut=60"
WORKDIR /jenkins
RUN mkdir -p /jenkins/workspace
VOLUME ["/jenkins/workspace"]

# Git
RUN git config --global --add safe.directory '*'

# Node.js
ADD https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/SHASUMS256.txt /tmp/node-shasums

RUN node_archive="$(sed -nE "s/^[0-9a-f]+  (node-v${NODE_MAJOR}\\.[0-9]+\\.[0-9]+-linux-x64\\.tar\\.gz)$/\\1/p" /tmp/node-shasums)" && \
    test -n "$node_archive" && \
    curl -fsSL "https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/${node_archive}" -o "/tmp/${node_archive}" && \
    (cd /tmp && grep "  ${node_archive}$" node-shasums | sha256sum -c -) && \
    tar -xzf "/tmp/${node_archive}" --strip-components=1 -C /usr/local && \
    rm -f "/tmp/${node_archive}" /tmp/node-shasums && \
    node --version | grep -E "^v${NODE_MAJOR}\\." && \
    npm --version && \
    npx --version

# PowerShell
ADD https://api.github.com/repos/PowerShell/PowerShell/releases?per_page=100 /tmp/powershell-releases.json

RUN apt-get update && \
    apt-get install -y --no-install-recommends libicu76 && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    powershell_version="$(grep -oE "\"tag_name\"[[:space:]]*:[[:space:]]*\"v${POWERSHELL_MAJOR}\\.[0-9]+\\.[0-9]+\"" /tmp/powershell-releases.json | sed -n '1p' | cut -d '"' -f 4 | tr -d v)" && \
    test -n "$powershell_version" && \
    powershell_archive="powershell-${powershell_version}-linux-x64.tar.gz" && \
    powershell_url="https://github.com/PowerShell/PowerShell/releases/download/v${powershell_version}" && \
    curl -fsSL "${powershell_url}/${powershell_archive}" -o "/tmp/${powershell_archive}" && \
    curl -fsSL "${powershell_url}/hashes.sha256" -o /tmp/powershell-hashes && \
    iconv -f UTF-16 -t UTF-8 /tmp/powershell-hashes | tr -d '\r' > /tmp/powershell-hashes.utf8 && \
    (cd /tmp && grep " \\*${powershell_archive}$" powershell-hashes.utf8 | sha256sum -c -) && \
    install -d "/opt/microsoft/powershell/${POWERSHELL_MAJOR}" && \
    tar -xzf "/tmp/${powershell_archive}" -C "/opt/microsoft/powershell/${POWERSHELL_MAJOR}" && \
    chmod +x "/opt/microsoft/powershell/${POWERSHELL_MAJOR}/pwsh" && \
    ln -s "/opt/microsoft/powershell/${POWERSHELL_MAJOR}/pwsh" /usr/local/bin/pwsh && \
    rm -f "/tmp/${powershell_archive}" /tmp/powershell-hashes /tmp/powershell-hashes.utf8 /tmp/powershell-releases.json && \
    pwsh -NoLogo -NoProfile -Command \
      "if (\$PSVersionTable.PSVersion.Major -ne ${POWERSHELL_MAJOR}) { exit 1 }"

# Docker CLI
ENV DOCKER_HOST=unix:///var/run/docker.sock

# The remote index invalidates this layer when Docker publishes a release.
ADD https://download.docker.com/linux/static/stable/x86_64/ /tmp/docker-versions

RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
      > /etc/apt/sources.list.d/docker.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends "docker-ce-cli=5:${DOCKER_MAJOR}.*" && \
    rm -f /tmp/docker-versions /etc/apt/sources.list.d/docker.list /etc/apt/keyrings/docker.asc && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    docker --version | grep -E "^Docker version ${DOCKER_MAJOR}\."

# Unity Version Control
ADD https://www.plasticscm.com/plasticrepo/stable/debian/Packages /tmp/plastic-packages

RUN echo 'deb [trusted=yes allow-insecure=yes] https://www.plasticscm.com/plasticrepo/stable/debian ./' \
      > /etc/apt/sources.list.d/plasticscm.list && \
    apt-get update -o Acquire::AllowInsecureRepositories=true && \
    apt-get install -y --no-install-recommends --allow-unauthenticated \
      "plasticscm-client-core=${PLASTIC_MAJOR}.*" && \
    rm -f /tmp/plastic-packages /etc/apt/sources.list.d/plasticscm.list && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    cm version | grep -E "^${PLASTIC_MAJOR}\."
