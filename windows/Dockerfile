ARG OS_BASE=ltsc2019
ARG BUILD_OS_BASE=1809

FROM eclipse-temurin:21-jdk-windowsservercore-${BUILD_OS_BASE} AS build

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell", "-NonInteractive", "-NoProfile", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]

WORKDIR C:/solution

COPY .mvn/ .mvn/
COPY mvnw.cmd .
COPY pom.xml .
COPY src/ src/

RUN ./mvnw.cmd --batch-mode --no-transfer-progress verify; \
    if ($LASTEXITCODE -ne 0) { throw ('Maven build failed with exit code {0}' -f $LASTEXITCODE) }

FROM mcr.microsoft.com/dotnet/framework/runtime:4.8-windowsservercore-${OS_BASE}

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell", "-NonInteractive", "-NoProfile", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]

# Runtime tools
RUN Invoke-Expression ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1')); \
    if (-not (Get-Command choco -ErrorAction SilentlyContinue)) { throw 'Chocolatey installation failed' }

COPY windows/jenkins-agent.nuspec C:/build/jenkins-agent.nuspec
COPY windows/tools/ C:/build/tools/

RUN choco pack C:/build/jenkins-agent.nuspec --output-directory C:/build --limit-output; \
    if ($LASTEXITCODE -ne 0) { throw ('choco pack jenkins-agent failed with exit code {0}' -f $LASTEXITCODE) }; \
    choco install jenkins-agent --yes --no-progress --limit-output --source 'C:/build;https://community.chocolatey.org/api/v2/'; \
    if ($LASTEXITCODE -notin 0, 1641, 3010) { throw ('choco install jenkins-agent failed with exit code {0}' -f $LASTEXITCODE) }; \
    Remove-Item -Recurse -Force C:/build

RUN java.exe -version; \
    if ($LASTEXITCODE -ne 0 -or -not $env:JAVA_HOME) { throw 'Java smoke check failed' }; \
    git.exe --version; \
    if ($LASTEXITCODE -ne 0) { throw 'Git smoke check failed' }; \
    git.exe lfs version; \
    if ($LASTEXITCODE -ne 0) { throw 'Git LFS smoke check failed' }; \
    pwsh.exe --version; \
    if ($LASTEXITCODE -ne 0) { throw 'PowerShell smoke check failed' }; \
    node.exe --version; \
    if ($LASTEXITCODE -ne 0) { throw 'Node.js smoke check failed' }; \
    npm.cmd --version; \
    if ($LASTEXITCODE -ne 0) { throw 'npm smoke check failed' }; \
    npx.cmd --version; \
    if ($LASTEXITCODE -ne 0) { throw 'npx smoke check failed' }; \
    docker.exe --version; \
    if ($LASTEXITCODE -ne 0) { throw 'Docker CLI smoke check failed' }; \
    cm.exe version; \
    if ($LASTEXITCODE -ne 0) { throw 'Unity Version Control smoke check failed' }

# Jenkins
USER ContainerAdministrator
ENV USER=ContainerAdministrator \
    HOME=C:/Users/ContainerAdministrator \
    AGENT_WORKDIR=C:/jenkins \
    JENKINS_AGENT_WORKDIR=C:/jenkins \
    JAVA_OPTS="-Dorg.jenkinsci.plugins.gitclient.Git.timeOut=60" \
    DOCKER_HOST=npipe:////./pipe/docker_engine
WORKDIR C:/jenkins
RUN New-Item -ItemType Directory -Path C:/jenkins/workspace -Force | Out-Null; \
    git config --global --add safe.directory *
VOLUME ["C:/jenkins/workspace"]

COPY --from=build C:/solution/target/launcher.jar C:/jenkins/launcher.jar

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["java.exe", "-jar", "C:/jenkins/launcher.jar", "--health"]

ENTRYPOINT ["java.exe", "-jar", "C:/jenkins/launcher.jar"]
