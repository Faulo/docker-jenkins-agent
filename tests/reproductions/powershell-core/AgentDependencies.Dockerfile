FROM faulo/choco:windowsservercore-ltsc2022@sha256:a418802f2342dc0e23971b83c3279c0fb590061e0b0e1a98600cbc680dea8eba

SHELL ["C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell", "-NonInteractive", "-NoProfile", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]

COPY windows/jenkins-agent.nuspec C:/build/jenkins-agent.nuspec
COPY windows/tools/ C:/build/tools/

RUN choco-install C:/build/jenkins-agent.nuspec; \
    if ($LASTEXITCODE -ne 0) { throw ('Agent dependency reproduction failed with exit code {0}' -f $LASTEXITCODE) }
