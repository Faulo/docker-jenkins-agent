def assertValue(actual, expected, description) {
    if (actual != expected) {
        error "${description}: expected '${expected}', got '${actual}'"
    }
}

def candidateImage() {
    return "$DOCKER_NAMESPACE/$DOCKER_IMAGE"
}

def testImage() {
    docker.image(candidateImage()).inside("--entrypoint=''") {
        exec 'docker --version'
        exec 'git --version'
        exec 'cm version'
        exec 'pwsh --version'
        exec 'node --version'
        exec 'npm --version'
        exec 'npx -y hello Faulo'
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
                        withEnvFile {
                            echo "Testing ${candidateImage()} on ${host}"
                            testImage()
                        }
                    }
                }
            }
        }
    }
}
