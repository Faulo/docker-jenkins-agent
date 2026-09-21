FROM maven:3-eclipse-temurin-21 AS build

WORKDIR /solution

COPY pom.xml .
COPY src/ src/

RUN mvn --batch-mode --no-transfer-progress verify

FROM debian:trixie-slim

ARG DEBIAN_FRONTEND=noninteractive

# Package sources
RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    install -m 0755 -d /etc/apt/keyrings && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ADD https://packages.microsoft.com/config/debian/13/packages-microsoft-prod.deb /tmp/packages-microsoft-prod.deb
ADD https://download.docker.com/linux/debian/gpg /etc/apt/keyrings/docker.asc
ADD https://deb.nodesource.com/setup_current.x /tmp/nodesource-setup

RUN dpkg -i /tmp/packages-microsoft-prod.deb && \
    chmod a+r /etc/apt/keyrings/docker.asc && \
    echo 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian trixie stable' \
      > /etc/apt/sources.list.d/docker.list && \
    echo 'deb [trusted=yes allow-insecure=yes] https://www.plasticscm.com/plasticrepo/stable/debian ./' \
      > /etc/apt/sources.list.d/plasticscm.list && \
    bash /tmp/nodesource-setup && \
    rm -f /tmp/packages-microsoft-prod.deb /tmp/nodesource-setup

# Runtime tools
COPY linux/jenkins-agent.packages /tmp/jenkins-agent.packages

RUN apt-get update -o Acquire::AllowInsecureRepositories=true && \
    xargs apt-get install -y --no-install-recommends < /tmp/jenkins-agent.packages && \
    git lfs install --system && \
    rm -f /tmp/jenkins-agent.packages && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    java -version && \
    git --version && \
    git lfs version && \
    pwsh --version && \
    node --version && \
    npm --version && \
    npx --version && \
    docker --version && \
    cm version

# Jenkins
USER root
ENV USER=root \
    HOME=/root \
    LANG=C.UTF-8 \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    AGENT_WORKDIR=/jenkins \
    JENKINS_AGENT_WORKDIR=/jenkins \
    JAVA_OPTS="-Dorg.jenkinsci.plugins.gitclient.Git.timeOut=60" \
    DOCKER_HOST=unix:///var/run/docker.sock
WORKDIR /jenkins
RUN mkdir -p /jenkins/workspace && \
    git config --global --add safe.directory '*'
VOLUME ["/jenkins/workspace"]

COPY --from=build --chmod=644 /solution/target/launcher.jar /jenkins/launcher.jar

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["java", "-jar", "/jenkins/launcher.jar", "health"]

ENTRYPOINT ["java", "-jar", "/jenkins/launcher.jar"]
CMD ["serve"]
