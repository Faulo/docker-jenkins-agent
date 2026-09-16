param(
    [Parameter(Mandatory)]
    [string] $Namespace,

    [Parameter(Mandatory)]
    [string] $Name,

    [Parameter(Mandatory)]
    [string] $Variant,

    [Parameter(Mandatory)]
    [string] $Context,

    [Parameter(Mandatory)]
    [string] $Image,

    [Parameter(Mandatory)]
    [string] $Os,

    [Parameter(Mandatory)]
    [AllowEmptyCollection()]
    [string[]] $DockerRunArguments
)

BeforeDiscovery {
    $webSocketCases = @(
        @{ Description = 'default'; Value = $null; Enabled = $true }
        @{ Description = 'empty'; Value = ''; Enabled = $true }
        @{ Description = 'whitespace'; Value = '   '; Enabled = $true }
        @{ Description = 'one'; Value = '1'; Enabled = $true }
        @{ Description = 'true'; Value = 'true'; Enabled = $true }
        @{ Description = 'trimmed mixed-case true'; Value = ' TrUe '; Enabled = $true }
        @{ Description = 'zero'; Value = '0'; Enabled = $false }
        @{ Description = 'false'; Value = 'false'; Enabled = $false }
        @{ Description = 'trimmed mixed-case false'; Value = ' FaLsE '; Enabled = $false }
    )
    $invalidWebSocketValues = @('yes', 'enabled', 'ture')
    $toolProbes = @(
        @{ Command = @('docker', '--version'); Expected = 'Docker version ' }
        @{ Command = @('git', '--version'); Expected = 'git version' }
        @{ Command = @('git', 'lfs', 'version'); Expected = 'git-lfs/' }
        @{ Command = @('cm', 'version'); Expected = '.' }
        @{ Command = @('pwsh', '--version'); Expected = 'PowerShell ' }
        @{ Command = @('node', '--version'); Expected = 'v' }
        @{ Command = @('npm', '--version'); Expected = '.' }
        @{ Command = @('npx', '--version'); Expected = '.' }
    )
}

BeforeAll {
    . (Join-Path $PSScriptRoot '../.jenkins/Docker.ps1')

    $jenkinsUrl = $env:JENKINS_URL
    if ([string]::IsNullOrWhiteSpace($jenkinsUrl)) {
        throw 'JENKINS_URL is required for Jenkins agent integration tests'
    }

    $launcherPath = $Os -eq 'windows' ? 'C:/jenkins/launcher.jar' : '/jenkins/launcher.jar'
    $expectedEntrypoint = @('java', '-jar', $launcherPath)
    $expectedCommand = @('serve')
    $expectedHealthcheck = @('CMD', 'java', '-jar', $launcherPath, 'health')

    function New-TestResourceName {
        param(
            [Parameter(Mandatory)]
            [string] $Prefix
        )

        return "docker-jenkins-agent-$Prefix-$([guid]::NewGuid().ToString('N'))"
    }

    function Remove-TestContainer {
        param(
            [string] $Container
        )

        if ([string]::IsNullOrWhiteSpace($Container)) {
            return
        }

        $result = Get-DockerCommandResult -Context $Context -Arguments @('container', 'inspect', $Container)
        if ($result.ExitCode -eq 0) {
            Invoke-Docker -Context $Context -Arguments @('container', 'rm', '--force', '--volumes', $Container)
        }
    }

    function Remove-TestImage {
        param(
            [string] $TestImage
        )

        if ([string]::IsNullOrWhiteSpace($TestImage)) {
            return
        }

        $result = Get-DockerCommandResult -Context $Context -Arguments @('image', 'inspect', $TestImage)
        if ($result.ExitCode -eq 0) {
            Invoke-Docker -Context $Context -Arguments @('image', 'rm', '--force', $TestImage)
        }
    }

    function Wait-TestContainer {
        param(
            [Parameter(Mandatory)]
            [string] $Container,

            [Parameter(Mandatory)]
            [ValidateRange(1, [int]::MaxValue)]
            [int] $TimeoutSeconds
        )

        $stopwatch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $state = Invoke-DockerOutput -Context $Context -Arguments @(
                'container', 'inspect', '--format', '{{.State.Running}} {{.State.ExitCode}}', $Container
            )
            $parts = $state.Split(' ', 2, [StringSplitOptions]::RemoveEmptyEntries)
            if ($parts[0] -eq 'false') {
                return [int] $parts[1]
            }

            Start-Sleep -Milliseconds 250
        } while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds)

        throw "Container $Container did not exit within $TimeoutSeconds seconds"
    }

    function Invoke-JenkinsContainer {
        param(
            [string[]] $Command = @(),

            [string[]] $Environment = @(),

            [string] $Config,

            [switch] $WithoutEntrypoint,

            [string] $Entrypoint,

            [switch] $JavaProbe,

            [int] $TimeoutSeconds = 120
        )

        $container = New-TestResourceName -Prefix 'container'
        $configFile = $null
        $probeFile = $null
        $arguments = @('create', '--name', $container)
        $arguments += $DockerRunArguments
        if ($WithoutEntrypoint) {
            $arguments += '--entrypoint='
        } elseif ($PSBoundParameters.ContainsKey('Entrypoint')) {
            $arguments += @('--entrypoint', $Entrypoint)
        }
        foreach ($entry in $Environment) {
            $arguments += @('--env', $entry)
        }
        $arguments += $Image
        $arguments += $Command

        try {
            Invoke-Docker -Context $Context -Arguments $arguments

            if ($JavaProbe) {
                $probeFile = Join-Path ([IO.Path]::GetTempPath()) "$(New-TestResourceName -Prefix 'java-probe').ps1"
                Set-Content -LiteralPath $probeFile -Value "Write-Output (`$args -join ' ')" -Encoding utf8NoBOM
                Invoke-Docker -Context $Context -Arguments @(
                    'container', 'cp', $probeFile, "${container}:C:/jenkins-java-probe.ps1"
                )
            }
            if ($PSBoundParameters.ContainsKey('Config')) {
                $configFile = Join-Path ([IO.Path]::GetTempPath()) "$(New-TestResourceName -Prefix 'config').yml"
                Set-Content -LiteralPath $configFile -Value $Config -Encoding utf8NoBOM
                $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
                Invoke-Docker -Context $Context -Arguments @('container', 'cp', $configFile, "${container}:$configPath")
            }

            Invoke-Docker -Context $Context -Arguments @('container', 'start', $container)
            $exitCode = Wait-TestContainer -Container $container -TimeoutSeconds $TimeoutSeconds
            $logs = Get-DockerCommandResult -Context $Context -Arguments @('container', 'logs', $container)
            return [pscustomobject] @{
                ExitCode = $exitCode
                Output = ($logs.Output | Out-String)
            }
        } finally {
            Remove-TestContainer -Container $container
            if ($null -ne $configFile -and (Test-Path -LiteralPath $configFile)) {
                Remove-Item -LiteralPath $configFile -Force
            }
            if ($null -ne $probeFile -and (Test-Path -LiteralPath $probeFile)) {
                Remove-Item -LiteralPath $probeFile -Force
            }
        }
    }

    function Get-WebSocketEnvironment {
        param(
            [string] $AgentName = 'argument-probe'
        )

        $java = if ($Os -eq 'windows') {
            'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
        } else {
            '/bin/echo'
        }
        $environment = @(
            "JENKINS_URL=$jenkinsUrl"
            'JENKINS_SECRET=not-a-secret'
            "JENKINS_AGENT_NAME=$AgentName"
            "JENKINS_JAVA_BIN=$java"
        )
        if ($Os -eq 'windows') {
            $environment += 'JENKINS_JAVA_OPTS=-NoProfile -File C:/jenkins-java-probe.ps1'
        }
        return $environment
    }

    function Get-HealthAcceptanceDockerfile {
        if ($Os -eq 'linux') {
            return @"
FROM $Image
ARG JENKINS_URL
COPY common/AgentHealthMonitorAcceptance.java /tmp/agent-health-test/AgentHealthMonitorAcceptance.java
RUN curl -fsSL "`${JENKINS_URL%/}/jnlpJars/agent.jar" -o /tmp/agent-health-test/agent.jar && \
    mkdir -p /tmp/agent-health-test/classes && \
    javac -cp /tmp/agent-health-test/agent.jar \
      -d /tmp/agent-health-test/classes /tmp/agent-health-test/AgentHealthMonitorAcceptance.java && \
    JENKINS_HEALTH_FILE=/tmp/agent-health-test.status \
    JENKINS_HEALTH_INTERVAL_SECONDS=1 \
    JENKINS_HEALTH_TIMEOUT_SECONDS=2 \
    JENKINS_HEALTH_STALE_SECONDS=10 \
    java -javaagent:/jenkins/launcher.jar \
      -cp /tmp/agent-health-test/agent.jar:/tmp/agent-health-test/classes \
      agent.health.AgentHealthMonitorAcceptance
"@
        }

        return @"
# escape=``
FROM $Image
SHELL ["C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell", "-NonInteractive", "-NoProfile", "-Command", "`$ErrorActionPreference = 'Stop'; `$ProgressPreference = 'SilentlyContinue';"]
ARG JENKINS_URL
COPY common/AgentHealthMonitorAcceptance.java C:/agent-health-test/AgentHealthMonitorAcceptance.java
RUN curl.exe -fsSL (`$env:JENKINS_URL.TrimEnd('/') + '/jnlpJars/agent.jar') -o C:/agent-health-test/agent.jar; ``
    if (`$LASTEXITCODE -ne 0) { throw 'Failed to download controller agent JAR' }; ``
    New-Item -ItemType Directory -Path C:/agent-health-test/classes -Force | Out-Null; ``
    javac.exe -cp C:/agent-health-test/agent.jar -d C:/agent-health-test/classes C:/agent-health-test/AgentHealthMonitorAcceptance.java; ``
    if (`$LASTEXITCODE -ne 0) { throw 'Failed to compile health acceptance test' }; ``
    `$env:JENKINS_HEALTH_FILE = 'C:/agent-health-test.status'; ``
    `$env:JENKINS_HEALTH_INTERVAL_SECONDS = '1'; ``
    `$env:JENKINS_HEALTH_TIMEOUT_SECONDS = '2'; ``
    `$env:JENKINS_HEALTH_STALE_SECONDS = '10'; ``
    java.exe -javaagent:C:/jenkins/launcher.jar -cp 'C:/agent-health-test/agent.jar;C:/agent-health-test/classes' agent.health.AgentHealthMonitorAcceptance; ``
    if (`$LASTEXITCODE -ne 0) { throw 'Health acceptance test failed' }
"@
    }
}

Describe "Jenkins agent entrypoint [$Os, $Image]" {
    It 'declares the fixed appliance launcher' {
        $actual = Invoke-DockerOutput -Context $Context -Arguments @(
            'image', 'inspect', '--format', '{{json .Config.Entrypoint}}', $Image
        ) | ConvertFrom-Json

        ($actual -join "`n") | Should -Be ($expectedEntrypoint -join "`n")
    }

    It 'declares only the default serve argument in Cmd' {
        $actual = Invoke-DockerOutput -Context $Context -Arguments @(
            'image', 'inspect', '--format', '{{json .Config.Cmd}}', $Image
        ) | ConvertFrom-Json

        ($actual -join "`n") | Should -Be ($expectedCommand -join "`n")
        $actual | Should -Not -Contain 'java'
        $actual | Should -Not -Contain 'java.exe'
        $actual | Should -Not -Contain $launcherPath
    }

    It 'declares the standalone launcher health probe' {
        $actual = Invoke-DockerOutput -Context $Context -Arguments @(
            'image', 'inspect', '--format', '{{json .Config.Healthcheck.Test}}', $Image
        ) | ConvertFrom-Json

        ($actual -join "`n") | Should -Be ($expectedHealthcheck -join "`n")
    }

    It 'handles the <Description> WebSocket value' -ForEach $webSocketCases {
        $environment = @(Get-WebSocketEnvironment)
        if ($null -ne $Value) {
            $environment += "JENKINS_WEB_SOCKET=$Value"
        }
        $result = Invoke-JenkinsContainer `
            -Environment $environment `
            -JavaProbe:($Os -eq 'windows')

        $result.ExitCode | Should -Be 0
        ([regex]::Matches($result.Output, [regex]::Escape('-webSocket'))).Count |
            Should -Be ($Enabled ? 1 : 0)
        $agentJar = $Os -eq 'windows' ? 'C:/jenkins/agent.jar' : '/jenkins/agent.jar'
        $inheritedAgentJar = if ($Os -eq 'windows') {
            'C:/ProgramData/Jenkins/agent.jar'
        } else {
            '/usr/share/jenkins/agent.jar'
        }
        $result.Output | Should -Match ([regex]::Escape("-jar $agentJar"))
        $result.Output | Should -Not -Match ([regex]::Escape($inheritedAgentJar))
    }

    It 'rejects the invalid WebSocket value <_> without leaking it' -ForEach $invalidWebSocketValues {
        $environment = @(Get-WebSocketEnvironment) + "JENKINS_WEB_SOCKET=$_"
        $result = Invoke-JenkinsContainer `
            -Environment $environment `
            -JavaProbe:($Os -eq 'windows')

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'JENKINS_WEB_SOCKET'
        $result.Output | Should -Match 'true, false, 1, or 0'
        $result.Output | Should -Not -Match ([regex]::Escape($_))
    }

    It 'does not duplicate an explicit WebSocket argument' {
        $result = Invoke-JenkinsContainer `
            -Command @('-webSocket') `
            -Environment @(Get-WebSocketEnvironment) `
            -JavaProbe:($Os -eq 'windows')

        $result.ExitCode | Should -Be 0
        ([regex]::Matches($result.Output, [regex]::Escape('-webSocket'))).Count | Should -Be 1
    }

    It 'does not execute command arguments as an alternate process' {
        $command = if ($Os -eq 'windows') {
            @('-Cmd', "Write-Output 'implicit-entrypoint-bypass'")
        } else {
            @('env')
        }
        $result = Invoke-JenkinsContainer `
            -Command $command `
            -Environment @('IMPLICIT_ENTRYPOINT_BYPASS=implicit-entrypoint-bypass')

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Not -Match 'IMPLICIT_ENTRYPOINT_BYPASS='
    }

    It 'allows an explicit entrypoint override for diagnostics' {
        $entrypoint = $Os -eq 'windows' ? 'powershell.exe' : '/bin/sh'
        $command = if ($Os -eq 'windows') {
            @('-NoProfile', '-Command', "Write-Output 'explicit-entrypoint-bypass'")
        } else {
            @('-c', 'echo explicit-entrypoint-bypass')
        }
        $result = Invoke-JenkinsContainer -Entrypoint $entrypoint -Command $command

        $result.ExitCode | Should -Be 0
        $result.Output.Trim() | Should -Be 'explicit-entrypoint-bypass'
    }

    It 'applies the selected indexed environment over the direct environment' {
        $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
        $environment = @(Get-WebSocketEnvironment -AgentName 'from-environment') + @(
            "JENKINS_CONFIG_FILE=$configPath"
            'JENKINS_CONFIG_INDEX=selected'
        )
        $result = Invoke-JenkinsContainer `
            -Environment $environment `
            -JavaProbe:($Os -eq 'windows') `
            -Config @'
other:
  JENKINS_AGENT_NAME: from-other-index
selected:
  JENKINS_AGENT_NAME: from-selected-index
'@

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'from-selected-index'
        $result.Output | Should -Not -Match 'from-other-index'
    }

    It 'preserves the direct environment without indexed configuration' {
        $result = Invoke-JenkinsContainer `
            -Environment @(Get-WebSocketEnvironment -AgentName 'from-direct-environment') `
            -JavaProbe:($Os -eq 'windows')

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'from-direct-environment'
    }

    It 'rejects a missing configuration index without leaking values' {
        $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
        $result = Invoke-JenkinsContainer -Command @('health') -Environment @(
            "JENKINS_CONFIG_FILE=$configPath"
            'JENKINS_CONFIG_INDEX=missing'
        ) -Config @'
selected:
  DO_NOT_LOG: highly-sensitive-value
'@

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'missing'
        $result.Output | Should -Not -Match 'highly-sensitive-value'
    }

    It 'rejects malformed YAML without leaking values' {
        $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
        $result = Invoke-JenkinsContainer -Command @('health') -Environment @(
            "JENKINS_CONFIG_FILE=$configPath"
            'JENKINS_CONFIG_INDEX=selected'
        ) -Config @'
selected: [
  DO_NOT_LOG: highly-sensitive-value
'@

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'selected'
        $result.Output | Should -Not -Match 'highly-sensitive-value'
    }

    It 'rejects a non-mapping configuration value without leaking it' {
        $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
        $result = Invoke-JenkinsContainer -Command @('health') -Environment @(
            "JENKINS_CONFIG_FILE=$configPath"
            'JENKINS_CONFIG_INDEX=selected'
        ) -Config 'selected: highly-sensitive-value'

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'selected'
        $result.Output | Should -Not -Match 'highly-sensitive-value'
    }

    It 'requires the configuration file and index as a pair' {
        $configPath = $Os -eq 'windows' ? 'C:/jenkins-agent-test.yml' : '/tmp/jenkins-agent-test.yml'
        $result = Invoke-JenkinsContainer `
            -Command @('health') `
            -Environment @("JENKINS_CONFIG_FILE=$configPath") `
            -Config @'
selected:
  DO_NOT_LOG: highly-sensitive-value
'@

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'JENKINS_CONFIG_INDEX'
        $result.Output | Should -Not -Match 'highly-sensitive-value'
    }

    It 'reports unhealthy without a managed agent' {
        $result = Invoke-JenkinsContainer -Command @('health')
        $result.ExitCode | Should -Be 1
    }
}

Describe "Jenkins agent runtime [$Os, $Image]" {
    It 'downloads the controller agent version' {
        $agentJar = Join-Path ([IO.Path]::GetTempPath()) "$(New-TestResourceName -Prefix 'controller-agent').jar"
        try {
            Invoke-WebRequest -Uri "$($jenkinsUrl.TrimEnd('/'))/jnlpJars/agent.jar" -OutFile $agentJar
            $controllerVersion = @(& java -jar $agentJar -version 2>&1)
            $LASTEXITCODE | Should -Be 0

            $result = Invoke-JenkinsContainer -Command @('-version') -Environment @("JENKINS_URL=$jenkinsUrl")
            $result.ExitCode | Should -Be 0
            $result.Output | Should -Match ([regex]::Escape(($controllerVersion | Out-String).Trim()))
        } finally {
            if (Test-Path -LiteralPath $agentJar) {
                Remove-Item -LiteralPath $agentJar -Force
            }
        }
    }

    It 'contains only the Java launcher implementation' {
        $command = if ($Os -eq 'windows') {
            @(
                'powershell.exe', '-NoProfile', '-Command',
                'if (-not (Test-Path -LiteralPath C:/jenkins/launcher.jar) -or (Test-Path -LiteralPath C:/jenkins/agent.exe) -or (Test-Path -LiteralPath C:/jenkins/agent-health.jar) -or (Test-Path -LiteralPath C:/ProgramData/Jenkins/jenkins-agent.ps1)) { exit 1 }'
            )
        } else {
            @(
                'sh', '-c',
                'test -s /jenkins/launcher.jar && test ! -e /jenkins/agent && test ! -e /jenkins/agent-health.jar && test ! -e /usr/local/bin/jenkins-agent'
            )
        }
        $result = Invoke-JenkinsContainer -Command $command -WithoutEntrypoint
        $result.ExitCode | Should -Be 0
    }

    It 'satisfies the platform package contract' {
        $command = if ($Os -eq 'windows') {
            @('choco', 'list', '--local-only', '--exact', 'jenkins-agent', '--limit-output')
        } else {
            @('sh', '-c', '. /etc/os-release && test "$VERSION_CODENAME" = trixie')
        }
        $result = Invoke-JenkinsContainer -Command $command -WithoutEntrypoint

        $result.ExitCode | Should -Be 0
        if ($Os -eq 'windows') {
            $result.Output | Should -Match ([regex]::Escape('jenkins-agent|1.0.0'))
        }
    }

    It 'runs as the expected administrative user' {
        $command = $Os -eq 'windows' ? @('whoami') : @('id', '-u')
        $result = Invoke-JenkinsContainer -Command $command -WithoutEntrypoint

        $result.ExitCode | Should -Be 0
        if ($Os -eq 'windows') {
            $result.Output | Should -Match 'containeradministrator'
        } else {
            $result.Output.Trim() | Should -Be '0'
        }
    }

    It 'provides <Command>' -ForEach $toolProbes {
        $command = @($Command)
        if ($Os -eq 'windows' -and $command[0] -in @('npm', 'npx')) {
            $command[0] += '.cmd'
        }
        $result = Invoke-JenkinsContainer -Command $command -WithoutEntrypoint

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match ([regex]::Escape($Expected))
    }
}

Describe "Jenkins agent health [$Os, $Image]" {
    It 'stays healthy during startup and keeps reconnecting' {
        $container = New-TestResourceName -Prefix 'live-health'
        $arguments = @('create', '--name', $container)
        $arguments += $DockerRunArguments
        foreach ($entry in @(
            "JENKINS_URL=$jenkinsUrl"
            'JENKINS_SECRET=not-a-secret'
            'JENKINS_AGENT_NAME=Mörkö'
            'JENKINS_WEB_SOCKET=true'
            'JENKINS_HEALTH_INTERVAL_SECONDS=1'
            'JENKINS_HEALTH_TIMEOUT_SECONDS=300'
            'JENKINS_HEALTH_STALE_SECONDS=10'
        )) {
            $arguments += @('--env', $entry)
        }
        $arguments += $Image

        try {
            Invoke-Docker -Context $Context -Arguments $arguments
            Invoke-Docker -Context $Context -Arguments @('container', 'start', $container)

            $healthCommand = if ($Os -eq 'windows') {
                @('java', '-jar', 'C:/jenkins/launcher.jar', 'health')
            } else {
                @('java', '-jar', '/jenkins/launcher.jar', 'health')
            }
            $healthExitCode = 1
            for ($attempt = 0; $attempt -lt 20 -and $healthExitCode -ne 0; $attempt++) {
                $execArguments = @('container', 'exec', '--env', 'JENKINS_URL=', $container) + $healthCommand
                $healthExitCode = (Get-DockerCommandResult -Context $Context -Arguments $execArguments).ExitCode
                if ($healthExitCode -ne 0) {
                    Start-Sleep -Seconds 1
                }
            }
            $healthExitCode | Should -Be 0

            $jarCommand = if ($Os -eq 'windows') {
                @(
                    'powershell.exe', '-NoProfile', '-Command',
                    'if (-not (Test-Path -LiteralPath C:/jenkins/agent.jar)) { exit 1 }'
                )
            } else {
                @('test', '-f', '/jenkins/agent.jar')
            }
            $jarExitCode = 1
            for ($attempt = 0; $attempt -lt 20 -and $jarExitCode -ne 0; $attempt++) {
                Start-Sleep -Seconds 1
                $execArguments = @('container', 'exec', $container) + $jarCommand
                $jarExitCode = (Get-DockerCommandResult -Context $Context -Arguments $execArguments).ExitCode
            }
            $jarExitCode | Should -Be 0

            if ($Os -eq 'linux') {
                $logs = Invoke-DockerOutput -Context $Context -Arguments @('container', 'logs', $container)
                $logs | Should -Match 'Setting up agent: Mörkö'
            }
            $running = Invoke-DockerOutput -Context $Context -Arguments @(
                'container', 'inspect', '--format', '{{.State.Running}}', $container
            )
            $running | Should -Be 'true'
        } finally {
            Remove-TestContainer -Container $container
        }
    }

    It 'passes the in-process health monitor acceptance contract' {
        $testImage = "tmp/jenkins-agent-health-test:$($Os)-$([guid]::NewGuid().ToString('N'))"
        $dockerfile = Join-Path ([IO.Path]::GetTempPath()) "$(New-TestResourceName -Prefix 'health').Dockerfile"
        try {
            Set-Content -LiteralPath $dockerfile -Value (Get-HealthAcceptanceDockerfile) -Encoding utf8NoBOM
            Invoke-Docker -Context $Context -Arguments @(
                'build',
                '--build-arg', "JENKINS_URL=$jenkinsUrl",
                '--tag', $testImage,
                '--file', $dockerfile,
                [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
            )
        } finally {
            Remove-TestImage -TestImage $testImage
            if (Test-Path -LiteralPath $dockerfile) {
                Remove-Item -LiteralPath $dockerfile -Force
            }
        }
    }
}
