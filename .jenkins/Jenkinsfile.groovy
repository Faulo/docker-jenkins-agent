def assertValue(actual, expected, description) {
    if (actual != expected) {
        error "${description}: expected '${expected}', got '${actual}'"
    }
}

def assertContains(actual, expected, description) {
    if (!actual.contains(expected)) {
        error "${description}: expected output to contain '${expected}', got '${actual}'"
    }
}

def assertNotContains(actual, unexpected, description) {
    if (actual.contains(unexpected)) {
        error "${description}: output contained forbidden value '${unexpected}'"
    }
}

def assertOccurrences(actual, expected, count, description) {
    def occurrences = actual.count(expected)
    if (occurrences != count) {
        error "${description}: expected '${expected}' ${count} time(s), got ${occurrences} in '${actual}'"
    }
}

def exec(command) {
    if (isUnix()) {
        sh command
    } else {
        bat command
    }
}

def execStdout(command) {
    return isUnix()
        ? sh(script: command, returnStdout: true)
        : bat(script: "@${command}", returnStdout: true)
}

def execStatus(command) {
    return isUnix()
        ? sh(script: command, returnStatus: true)
        : bat(script: command, returnStatus: true)
}

def candidateImage() {
    return "$DOCKER_NAMESPACE/$DOCKER_IMAGE"
}

def configPath() {
    return isUnix() ? '/tmp/jenkins-agent-test.yml' : 'C:/jenkins-agent-test.yml'
}

def environmentProbe(variable) {
    return isUnix()
        ? 'env'
        : "-Cmd \"[Environment]::GetEnvironmentVariable('${variable}')\""
}

def javaProbe() {
    return isUnix()
        ? '/bin/echo'
        : 'C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
}

def withProjectEnvironment(Closure body) {
    def values = readProperties file: '.env'
    withEnv(values.collect { name, value -> "${name}=${value}" }, body)
}

def runContainer(arguments, environment = [], config = null, inheritEntrypoint = true) {
    def environmentArguments = environment.collect { "--env \"${it}\"" }.join(' ')
    def entrypointArgument = inheritEntrypoint ? '' : '--entrypoint=""'
    def containerId = execStdout("docker create ${entrypointArgument} ${environmentArguments} ${candidateImage()} ${arguments}").trim()
    try {
        if (!isUnix() && environment.any { it.startsWith('JENKINS_JAVA_BIN=') }) {
            exec "docker cp jenkins-java-probe.ps1 ${containerId}:C:/jenkins-java-probe.ps1"
        }
        if (config != null) {
            writeFile file: 'jenkins-agent-test.yml', text: config
            exec "docker cp jenkins-agent-test.yml ${containerId}:${configPath()}"
        }
        exec "docker start ${containerId}"
        def exitCode = execStdout("docker wait ${containerId}").trim()
        def logs = execStdout("docker logs ${containerId} 2>&1")
        return [exitCode: exitCode, logs: logs]
    } finally {
        exec "docker rm --force --volumes ${containerId}"
    }
}

def testWebSocket() {
    if (!isUnix()) {
        writeFile file: 'jenkins-java-probe.ps1', text: 'Write-Output ($args -join \' \')'
    }
    def baseEnvironment = [
        "JENKINS_URL=${env.JENKINS_URL}",
        'JENKINS_SECRET=not-a-secret',
        'JENKINS_AGENT_NAME=argument-probe',
        "JENKINS_JAVA_BIN=${javaProbe()}"
    ]
    if (!isUnix()) {
        baseEnvironment.add('JENKINS_JAVA_OPTS=-NoProfile -File C:/jenkins-java-probe.ps1')
    }
    def cases = [
        [description: 'default', value: null, enabled: true],
        [description: 'empty', value: '', enabled: true],
        [description: 'whitespace', value: '   ', enabled: true],
        [description: 'one', value: '1', enabled: true],
        [description: 'true', value: 'true', enabled: true],
        [description: 'trimmed mixed-case true', value: ' TrUe ', enabled: true],
        [description: 'zero', value: '0', enabled: false],
        [description: 'false', value: 'false', enabled: false],
        [description: 'trimmed mixed-case false', value: ' FaLsE ', enabled: false]
    ]

    cases.each { testCase ->
        def environment = new ArrayList(baseEnvironment)
        if (testCase.value != null) {
            environment.add("JENKINS_WEB_SOCKET=${testCase.value}")
        }
        def result = runContainer('', environment)

        assertValue(result.exitCode, '0', "WebSocket ${testCase.description} exit code")
        assertOccurrences(
            result.logs,
            '-webSocket',
            testCase.enabled ? 1 : 0,
            "WebSocket ${testCase.description} launch arguments"
        )
        assertContains(
            result.logs,
            isUnix() ? '-jar /jenkins/agent.jar' : '-jar C:/jenkins/agent.jar',
            "WebSocket ${testCase.description} adjacent agent JAR"
        )
        assertNotContains(
            result.logs,
            isUnix() ? '/usr/share/jenkins/agent.jar' : 'C:/ProgramData/Jenkins/agent.jar',
            "WebSocket ${testCase.description} inherited agent JAR"
        )
    }

    ['yes', 'enabled', 'ture'].each { value ->
        def result = runContainer('', baseEnvironment + ["JENKINS_WEB_SOCKET=${value}"])

        assertValue(result.exitCode, '1', "invalid WebSocket value '${value}' exit code")
        assertContains(result.logs, 'JENKINS_WEB_SOCKET', "invalid WebSocket value '${value}' error")
        assertContains(result.logs, 'true, false, 1, or 0', "invalid WebSocket value '${value}' error")
        assertNotContains(result.logs, value, "invalid WebSocket value '${value}' error")
    }

    def explicit = runContainer('-webSocket', baseEnvironment)
    assertValue(explicit.exitCode, '0', 'explicit WebSocket exit code')
    assertOccurrences(explicit.logs, '-webSocket', 1, 'explicit WebSocket launch arguments')
}

def testEntrypoint() {
    testWebSocket()
    def selectedEnvironment = runContainer(
        environmentProbe('JENKINS_AGENT_NAME'),
        [
            "JENKINS_CONFIG_FILE=${configPath()}",
            'JENKINS_CONFIG_INDEX=selected',
            'JENKINS_AGENT_NAME=from-environment'
        ],
        '''other:
  JENKINS_AGENT_NAME: from-other-index
selected:
  JENKINS_AGENT_NAME: from-selected-index
'''
    )
    assertValue(selectedEnvironment.exitCode, '0', 'indexed environment exit code')
    assertContains(selectedEnvironment.logs, isUnix() ? 'JENKINS_AGENT_NAME=from-selected-index' : 'from-selected-index', 'indexed environment')
    assertNotContains(selectedEnvironment.logs, 'from-other-index', 'unselected environment')

    def directEnvironment = runContainer(
        environmentProbe('JENKINS_AGENT_NAME'),
        ['JENKINS_AGENT_NAME=from-direct-environment']
    )
    assertValue(directEnvironment.exitCode, '0', 'direct environment exit code')
    assertContains(directEnvironment.logs, isUnix() ? 'JENKINS_AGENT_NAME=from-direct-environment' : 'from-direct-environment', 'direct environment')

    def missingIndex = runContainer(
        '--health',
        [
            "JENKINS_CONFIG_FILE=${configPath()}",
            'JENKINS_CONFIG_INDEX=missing'
        ],
        '''selected:
  DO_NOT_LOG: highly-sensitive-value
'''
    )
    assertValue(missingIndex.exitCode, '1', 'missing index exit code')
    assertContains(missingIndex.logs, 'missing', 'missing index error')
    assertNotContains(missingIndex.logs, 'highly-sensitive-value', 'missing index error')

    def malformed = runContainer(
        '--health',
        [
            "JENKINS_CONFIG_FILE=${configPath()}",
            'JENKINS_CONFIG_INDEX=selected'
        ],
        '''selected: [
  DO_NOT_LOG: highly-sensitive-value
'''
    )
    assertValue(malformed.exitCode, '1', 'malformed YAML exit code')
    assertContains(malformed.logs, 'selected', 'malformed YAML error')
    assertNotContains(malformed.logs, 'highly-sensitive-value', 'malformed YAML error')

    def nonMapping = runContainer(
        '--health',
        [
            "JENKINS_CONFIG_FILE=${configPath()}",
            'JENKINS_CONFIG_INDEX=selected'
        ],
        '''selected: highly-sensitive-value
'''
    )
    assertValue(nonMapping.exitCode, '1', 'non-mapping exit code')
    assertContains(nonMapping.logs, 'selected', 'non-mapping error')
    assertNotContains(nonMapping.logs, 'highly-sensitive-value', 'non-mapping error')

    def missingPair = runContainer(
        '--health',
        ["JENKINS_CONFIG_FILE=${configPath()}"],
        '''selected:
  DO_NOT_LOG: highly-sensitive-value
'''
    )
    assertValue(missingPair.exitCode, '1', 'missing configuration pair exit code')
    assertContains(missingPair.logs, 'JENKINS_CONFIG_INDEX', 'missing configuration pair error')
    assertNotContains(missingPair.logs, 'highly-sensitive-value', 'missing configuration pair error')

    def healthWithoutAgent = runContainer('--health')
    assertValue(healthWithoutAgent.exitCode, '1', 'health without managed agent exit code')
}

def testControllerAgent() {
    def downloadedJar = isUnix() ? '/tmp/controller-agent.jar' : 'controller-agent.jar'
    try {
        exec "curl -fsSL ${env.JENKINS_URL}jnlpJars/agent.jar -o ${downloadedJar}"
        def controllerVersion = execStdout("java -jar ${downloadedJar} -version 2>&1").trim()
        def result = runContainer('-version', ["JENKINS_URL=${env.JENKINS_URL}"])
        assertValue(result.exitCode, '0', 'controller agent version exit code')
        assertContains(result.logs, controllerVersion, 'controller agent version')
    } finally {
        if (isUnix()) {
            execStatus "rm -f ${downloadedJar}"
        } else {
            execStatus "del /f /q ${downloadedJar}"
        }
    }

    def runtimeCommand = isUnix()
        ? "sh -c 'test -s /jenkins/launcher.jar && test ! -e /jenkins/agent && test ! -e /jenkins/agent-health.jar && test ! -e /usr/local/bin/jenkins-agent'"
        : "powershell.exe -NoProfile -Command \"if (-not (Test-Path -LiteralPath C:/jenkins/launcher.jar) -or (Test-Path -LiteralPath C:/jenkins/agent.exe) -or (Test-Path -LiteralPath C:/jenkins/agent-health.jar) -or (Test-Path -LiteralPath C:/ProgramData/Jenkins/jenkins-agent.ps1)) { exit 1 }\""
    def runtime = runContainer(runtimeCommand, [], null, false)
    assertValue(runtime.exitCode, '0', 'runtime image contains only the Java launcher')
}

def testLiveHealth() {
    def environmentArguments = [
        "JENKINS_URL=${env.JENKINS_URL}",
        'JENKINS_SECRET=not-a-secret',
        'JENKINS_AGENT_NAME=Mörkö',
        'JENKINS_WEB_SOCKET=true',
        'JENKINS_HEALTH_INTERVAL_SECONDS=1',
        'JENKINS_HEALTH_TIMEOUT_SECONDS=10',
        'JENKINS_HEALTH_STALE_SECONDS=10'
    ].collect { "--env \"${it}\"" }.join(' ')
    def containerId = execStdout("docker create ${environmentArguments} ${candidateImage()}").trim()
    try {
        exec "docker start ${containerId}"
        def healthCommand = isUnix()
            ? "java -jar /jenkins/launcher.jar --health"
            : "java.exe -jar C:/jenkins/launcher.jar --health"
        def jarCommand = isUnix()
            ? "test -f /jenkins/agent.jar"
            : "powershell.exe -NoProfile -Command \"if (-not (Test-Path -LiteralPath C:/jenkins/agent.jar)) { exit 1 }\""
        def jarExitCode = '1'
        for (int attempt = 0; attempt < 20 && jarExitCode != '0'; attempt++) {
            sleep time: 1, unit: 'SECONDS'
            jarExitCode = execStatus("docker exec ${containerId} ${jarCommand}").toString()
        }
        assertValue(jarExitCode, '0', 'controller agent JAR is adjacent to the entrypoint')
        if (isUnix()) {
            assertContains(
                execStdout("docker logs ${containerId} 2>&1"),
                'Setting up agent: Mörkö',
                'Unicode agent name'
            )
        }
        def inspectFormat = isUnix() ? "'{{.State.Running}}'" : '"{{.State.Running}}"'
        assertValue(
            execStdout("docker inspect --format=${inspectFormat} ${containerId}").trim(),
            'true',
            'reconnecting agent process remains running'
        )
        def graceExitCode = '1'
        for (int attempt = 0; attempt < 20 && graceExitCode != '0'; attempt++) {
            graceExitCode = execStatus("docker exec ${containerId} ${healthCommand}").toString()
            if (graceExitCode != '0') {
                sleep time: 1, unit: 'SECONDS'
            }
        }
        assertValue(graceExitCode, '0', 'starting agent health exit code during grace')
        sleep time: 11, unit: 'SECONDS'
        assertValue(
            execStatus("docker exec ${containerId} ${healthCommand}").toString(),
            '1',
            'starting agent health exit code after grace'
        )
    } finally {
        exec "docker rm --force --volumes ${containerId}"
    }
}

def healthAcceptanceDockerfile() {
    if (isUnix()) {
        return '''FROM IMAGE_TO_TEST
ARG JENKINS_URL
COPY common/AgentHealthMonitorAcceptance.java /tmp/agent-health-test/AgentHealthMonitorAcceptance.java
RUN curl -fsSL "${JENKINS_URL%/}/jnlpJars/agent.jar" -o /tmp/agent-health-test/agent.jar && \
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
'''.replace('IMAGE_TO_TEST', candidateImage())
    }
    return '''# escape=`
FROM IMAGE_TO_TEST
SHELL ["C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell", "-NonInteractive", "-NoProfile", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]
ARG JENKINS_URL
COPY common/AgentHealthMonitorAcceptance.java C:/agent-health-test/AgentHealthMonitorAcceptance.java
RUN curl.exe -fsSL ($env:JENKINS_URL.TrimEnd('/') + '/jnlpJars/agent.jar') -o C:/agent-health-test/agent.jar; `
    if ($LASTEXITCODE -ne 0) { throw 'Failed to download controller agent JAR' }; `
    New-Item -ItemType Directory -Path C:/agent-health-test/classes -Force | Out-Null; `
    javac.exe -cp C:/agent-health-test/agent.jar -d C:/agent-health-test/classes C:/agent-health-test/AgentHealthMonitorAcceptance.java; `
    if ($LASTEXITCODE -ne 0) { throw 'Failed to compile health acceptance test' }; `
    $env:JENKINS_HEALTH_FILE = 'C:/agent-health-test.status'; `
    $env:JENKINS_HEALTH_INTERVAL_SECONDS = '1'; `
    $env:JENKINS_HEALTH_TIMEOUT_SECONDS = '2'; `
    $env:JENKINS_HEALTH_STALE_SECONDS = '10'; `
    java.exe -javaagent:C:/jenkins/launcher.jar -cp 'C:/agent-health-test/agent.jar;C:/agent-health-test/classes' agent.health.AgentHealthMonitorAcceptance; `
    if ($LASTEXITCODE -ne 0) { throw 'Health acceptance test failed' }
'''.replace('IMAGE_TO_TEST', candidateImage())
}

def testHealthHook() {
    def platform = isUnix() ? 'linux' : 'windows'
    def testImage = "tmp/jenkins-agent-health-test:${env.BUILD_NUMBER}-${platform}"
    writeFile file: 'Dockerfile.health-test', text: healthAcceptanceDockerfile()
    try {
        exec "docker build --build-arg JENKINS_URL=\"${env.JENKINS_URL}\" --tag ${testImage} --file Dockerfile.health-test ."
    } finally {
        execStatus "docker image rm --force ${testImage}"
    }
}

def testImage() {
    testEntrypoint()
    testControllerAgent()
    testLiveHealth()
    testHealthHook()
    def platformContract = isUnix()
        ? runContainer("sh -c '. /etc/os-release && test \"\$VERSION_CODENAME\" = trixie'", [], null, false)
        : runContainer('choco list --local-only --exact jenkins-agent --limit-output', [], null, false)
    assertValue(platformContract.exitCode, '0', 'platform package contract exit code')
    if (!isUnix()) {
        assertContains(platformContract.logs, 'jenkins-agent|1.0.0', 'Chocolatey package manifest')
    }
    def user = runContainer(isUnix() ? 'id -u' : 'whoami', [], null, false)
    assertValue(user.exitCode, '0', 'runtime user probe exit code')
    if (isUnix()) {
        assertValue(user.logs.trim(), '0', 'runtime user')
    } else {
        assertContains(user.logs.toLowerCase(), 'containeradministrator', 'runtime user')
    }
    def scriptSuffix = isUnix() ? '' : '.cmd'
    def probes = [
        [command: 'docker --version', expected: 'Docker version '],
        [command: 'git --version', expected: 'git version'],
        [command: 'git lfs version', expected: 'git-lfs/'],
        [command: 'cm version', expected: '.'],
        [command: 'pwsh --version', expected: 'PowerShell '],
        [command: 'node --version', expected: 'v'],
        [command: "npm${scriptSuffix} --version", expected: '.'],
        [command: "npx${scriptSuffix} --version", expected: '.']
    ]
    probes.each { probe ->
        def result = runContainer(probe.command, [], null, false)
        assertValue(result.exitCode, '0', "${probe.command} exit code")
        assertContains(result.logs, probe.expected, probe.command)
    }
}

properties([
    parameters([
        choice(
            name: 'DOCKER_NAMESPACE',
            choices: ['faulo', 'tmp'],
            description: 'Docker image namespace to test'
        )
    ]),
    disableConcurrentBuilds(),
    disableResume()
])

def hosts = ['Dende', 'Garl']
def dockerNamespace = params.DOCKER_NAMESPACE ?: 'faulo'

stage('Integration Tests') {
    for (def host in hosts) {
        stage("Host: ${host}") {
            node(host) {
                deleteDir()
                checkout scm
                
                catchError(
                    message: "Integration test failed on ${host}",
                    stageResult: 'FAILURE',
                    buildResult: 'FAILURE',
                    catchInterruptions: false
                ) {
                    withEnv([
                        "DOCKER_NAMESPACE=${dockerNamespace}"
                    ]) {
                        withProjectEnvironment {
                            echo "Testing ${candidateImage()} on ${host}"
                            testImage()
                        }
                    }
                }
            }
        }
    }
}
