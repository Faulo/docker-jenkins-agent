ARG OS_BASE=ltsc2019

FROM mcr.microsoft.com/dotnet/sdk:9.0-windowsservercore-${OS_BASE} AS agent-build

WORKDIR C:/source

COPY Directory.Build.props .
COPY common/Agent/ .

RUN dotnet publish Agent.csproj \
        --configuration Release \
        --runtime win-x64 \
        --self-contained true \
        --property:PublishSingleFile=true \
        --output C:/out

FROM jenkins/inbound-agent:jdk21-windowsservercore-${OS_BASE}

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell", "-NonInteractive", "-NoProfile", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]

ARG DOCKER_MAJOR=29
ARG NODE_MAJOR=24
ARG PLASTIC_MAJOR=11
ARG POWERSHELL_MAJOR=7

# Jenkins
USER ContainerAdministrator
ENV USER=ContainerAdministrator \
    HOME=C:/Users/ContainerAdministrator \
    AGENT_WORKDIR=C:/jenkins \
    JENKINS_AGENT_WORKDIR=C:/jenkins \
    JENKINS_WEB_SOCKET=true \
    JAVA_OPTS="-Dorg.jenkinsci.plugins.gitclient.Git.timeOut=60"
WORKDIR C:/jenkins
RUN New-Item -ItemType Directory -Path C:\\jenkins\\workspace -Force | Out-Null
VOLUME ["C:/jenkins/workspace"]

# Git
RUN git config --global --add safe.directory *

# PowerShell
ADD https://api.github.com/repos/PowerShell/PowerShell/releases?per_page=100 C:/powershell-releases.json
RUN $releases = Get-Content C:/powershell-releases.json -Raw | ConvertFrom-Json; \
    $release = $releases | \
      Where-Object { -not $_.draft -and -not $_.prerelease -and $_.tag_name -match ('^v' + $env:POWERSHELL_MAJOR + '\.\d+\.\d+$') } | \
      Sort-Object { [version]$_.tag_name.Substring(1) } | \
      Select-Object -Last 1; \
    if (-not $release) { throw ('No stable PowerShell ' + $env:POWERSHELL_MAJOR + ' release found') }; \
    $powershellVersion = $release.tag_name.Substring(1); \
    $archiveName = 'PowerShell-' + $powershellVersion + '-win-x64.zip'; \
    $archive = 'C:/' + $archiveName; \
    $asset = $release.assets | Where-Object name -eq $archiveName; \
    $hashesAsset = $release.assets | Where-Object name -eq 'hashes.sha256'; \
    if (-not $asset -or -not $hashesAsset) { throw ('PowerShell ' + $powershellVersion + ' release assets are incomplete') }; \
    curl.exe -fsSL $asset.browser_download_url -o $archive; \
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download PowerShell' }; \
    curl.exe -fsSL $hashesAsset.browser_download_url -o C:/powershell-hashes; \
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download PowerShell hashes' }; \
    $hashLine = Get-Content C:/powershell-hashes | Where-Object { $_ -match ('\*' + [regex]::Escape($archiveName) + '$') }; \
    if (-not $hashLine) { throw ('No checksum found for ' + $archiveName) }; \
    $expectedHash = ($hashLine -split '\s+')[0]; \
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash; \
    if ($actualHash -ne $expectedHash) { throw ('Checksum verification failed for ' + $archiveName) }; \
    $destination = 'C:/Program Files/PowerShell/7'; \
    New-Item -ItemType Directory -Force $destination | Out-Null; \
    Expand-Archive -LiteralPath $archive -DestinationPath $destination; \
    Remove-Item -LiteralPath $archive,C:/powershell-hashes,C:/powershell-releases.json; \
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine'); \
    [Environment]::SetEnvironmentVariable('Path', ($destination + ';' + $machinePath), 'Machine'); \
    $pwsh = Join-Path $destination pwsh.exe; \
    $installedVersion = & $pwsh -NoLogo -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'; \
    if ($LASTEXITCODE -ne 0 -or $installedVersion -ne $powershellVersion) { throw ('Unexpected PowerShell version: ' + $installedVersion) }; \
    $installedVersion
RUN pwsh.exe -NoLogo -NoProfile -Command \
        'if ($PSVersionTable.PSVersion.Major -ne [int]$env:POWERSHELL_MAJOR) { exit 1 }'; \
    if ($LASTEXITCODE -ne 0) { throw 'PowerShell smoke check failed' }

# Node.js
ADD https://nodejs.org/dist/latest-v${NODE_MAJOR}.x/SHASUMS256.txt C:/node-shasums
RUN $nodePattern = '(?m)^(?<hash>[0-9a-f]{64})\s+\*?(?<archive>node-v(?<version>' + $env:NODE_MAJOR + '\.\d+\.\d+)-win-x64\.zip)\r?$'; \
    $nodeRelease = [regex]::Match((Get-Content C:/node-shasums -Raw), $nodePattern); \
    if (-not $nodeRelease.Success) { throw ('No Node.js ' + $env:NODE_MAJOR + ' release found') }; \
    $archiveName = $nodeRelease.Groups['archive'].Value; \
    $archive = 'C:/' + $archiveName; \
    $nodeVersion = $nodeRelease.Groups['version'].Value; \
    $nodeUrl = 'https://nodejs.org/dist/latest-v' + $env:NODE_MAJOR + '.x/' + $archiveName; \
    curl.exe -fsSL $nodeUrl -o $archive; \
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download Node.js' }; \
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash; \
    if ($actualHash -ne $nodeRelease.Groups['hash'].Value) { throw ('Checksum verification failed for ' + $archiveName) }; \
    Expand-Archive -LiteralPath $archive -DestinationPath C:/node; \
    $destination = 'C:/Program Files/nodejs'; \
    Move-Item -LiteralPath ('C:/node/node-v' + $nodeVersion + '-win-x64') -Destination $destination; \
    Remove-Item -LiteralPath $archive,C:/node-shasums; \
    Remove-Item -LiteralPath C:/node -Force; \
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine'); \
    [Environment]::SetEnvironmentVariable('Path', ($destination + ';' + $machinePath), 'Machine')
RUN $nodeVersion = node.exe --version; \
    if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch ('^v' + $env:NODE_MAJOR + '\.')) { throw ('Unexpected Node.js version: ' + $nodeVersion) }; \
    npm.cmd --version; \
    if ($LASTEXITCODE -ne 0) { throw 'npm smoke check failed' }; \
    npx.cmd --version; \
    if ($LASTEXITCODE -ne 0) { throw 'npx smoke check failed' }; \
    $nodeVersion

# Docker CLI
ENV DOCKER_HOST=npipe:////./pipe/docker_engine

ADD https://download.docker.com/win/static/stable/x86_64/ docker-versions.html

RUN $dockerPattern = 'docker-(?<version>' + $env:DOCKER_MAJOR + '\.\d+\.\d+)(?:-(?<revision>\d+))?\.zip'; \
    $dockerRelease = [regex]::Matches((Get-Content docker-versions.html -Raw), $dockerPattern) | \
      Sort-Object @{ Expression = { [version]$_.Groups['version'].Value } }, @{ Expression = { [int]$_.Groups['revision'].Value } } | \
      Select-Object -Last 1; \
    if (-not $dockerRelease) { throw ('No Docker ' + $env:DOCKER_MAJOR + ' release found') }; \
    $dockerVersion = $dockerRelease.Groups['version'].Value; \
    $dockerUrl = 'https://download.docker.com/win/static/stable/x86_64/' + $dockerRelease.Value; \
    curl.exe -fsSL $dockerUrl -o docker.zip; \
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download Docker' }; \
    Expand-Archive -Path docker.zip -DestinationPath . -Force; \
    Remove-Item docker.zip,docker-versions.html -Force; \
    New-Item -ItemType Directory -Path 'C:\\Program Files\\Docker' -Force | Out-Null; \
    Move-Item -Path docker\\docker.exe -Destination 'C:\\Program Files\\Docker\\docker.exe'; \
    Remove-Item docker -Recurse -Force; \
    [Environment]::SetEnvironmentVariable('PATH', 'C:\\Program Files\\Docker;'+$env:PATH, [EnvironmentVariableTarget]::Machine); \
    $installedVersion = & 'C:\\Program Files\\Docker\\docker.exe' --version; \
    if ($LASTEXITCODE -ne 0 -or $installedVersion -notmatch ('^Docker version ' + [regex]::Escape($dockerVersion) + ',')) { throw ('Unexpected Docker version: ' + $installedVersion) }; \
    $installedVersion

# Unity Version Control
ADD https://www.plasticscm.com/plasticrepo/stable/debian/Packages plastic-packages

RUN $plasticPattern = '(?m)^Version:\s*(' + $env:PLASTIC_MAJOR + '\.\S+)\s*$'; \
    $plasticVersion = [regex]::Matches((Get-Content plastic-packages -Raw), $plasticPattern) | \
      ForEach-Object { $_.Groups[1].Value } | Sort-Object { [version]$_ } | Select-Object -Last 1; \
    if (-not $plasticVersion) { throw ('No Unity Version Control ' + $env:PLASTIC_MAJOR + ' release found') }; \
    $plasticUrl = 'https://www.plasticscm.com/download/downloadinstaller/' + $plasticVersion + '/plasticscm/windows/client?flags=None'; \
    curl.exe -fsSL $plasticUrl -o plastic-installer.exe; \
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download Unity Version Control' }; \
    $signature = Get-AuthenticodeSignature plastic-installer.exe; \
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Unity Technologies') { throw 'Unity Version Control signature verification failed' }; \
    $process = Start-Process \
      -FilePath plastic-installer.exe \
      -ArgumentList '--mode unattended --unattendedmodeui none' \
      -Wait -NoNewWindow -PassThru; \
    if ($process.ExitCode -ne 0) { throw "Unity Version Control installer failed with exit code $($process.ExitCode)" }; \
    Remove-Item plastic-installer.exe,plastic-packages -Force; \
    $installedVersion = & 'C:\\Program Files\\PlasticSCM5\\client\\cm.exe' version; \
    if ($LASTEXITCODE -ne 0) { throw 'Unity Version Control smoke check failed' }; \
    if ($installedVersion -notmatch ('^' + [regex]::Escape($plasticVersion) + '(?:\s|$)')) { throw ('Unexpected Unity Version Control version: ' + $installedVersion) }; \
    $installedVersion

COPY --from=agent-build C:/out/agent.exe C:/jenkins/agent.exe
RUN C:/jenkins/agent.exe --health; \
    if ($LASTEXITCODE -ne 0) { throw 'Jenkins agent health check failed' }

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD ["C:/jenkins/agent.exe", "--health"]

ENTRYPOINT ["C:/jenkins/agent.exe"]
