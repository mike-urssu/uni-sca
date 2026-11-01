// Multibranch Pipeline. Every branch is tested; only main is deployed.
//
// Jenkins setup:
//   - Global tool (JDK) named 'jdk21'; the agent needs node/npm on the PATH (frontend build).
//   - Agent labelled 'macbook' runs the build.
//   - Credentials:
//       deploy-ssh-key   : SSH private key for the deploy server
//   - Plugins: Workspace Cleanup (cleanWs), SSH Agent (sshagent).
//   - Global property DEPLOY_HOST: user@host of the deploy server.
//   - Optional environment DEPLOY_PORT: SSH port of the deploy server (defaults to 22).
//
// Deploy server setup (once, as the DEPLOY_HOST user; the server is macOS):
//   - brew install openjdk@21 and a running Docker (Colima); the app runs the scanner containers.
//   - mkdir -p /opt/projects/uni-sca owned by that user.
// The app runs as a launchd user agent (deploy/uni-sca.plist) and listens on 127.0.0.1:8090 only (HostGuard);
// reach it with ssh -L 8090:localhost:8090 <DEPLOY_HOST>.
pipeline {
    agent { label 'macbook' }

    tools {
        jdk 'jdk21'
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        DEPLOY_DIR = '/opt/projects/uni-sca'
        // Keep in step with sca.images.scancode in application.yml.
        SCANCODE_IMAGE = 'sca-scancode:32.5.0'
        // Keep in step with the Label in deploy/uni-sca.plist.
        AGENT = 'sca.uni-sca'
    }

    stages {
        stage('Test') {
            steps {
                dir('backend') {
                    sh './gradlew clean test --no-daemon'
                }
            }
            post {
                always {
                    junit 'backend/build/test-results/test/*.xml'
                }
            }
        }

        stage('Build') {
            when { branch 'main' }
            steps {
                sh './build.sh'
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    sh '''
                        PORT="${DEPLOY_PORT:-22}"
                        OPTS="-o StrictHostKeyChecking=accept-new"
                        SSH="ssh -p $PORT $OPTS $DEPLOY_HOST"

                        $SSH "mkdir -p $DEPLOY_DIR/docker ~/Library/LaunchAgents"
                        scp -P "$PORT" $OPTS backend/build/libs/uni-sca.jar "$DEPLOY_HOST:$DEPLOY_DIR/uni-sca.jar.new"
                        scp -P "$PORT" $OPTS backend/docker/scancode.Dockerfile "$DEPLOY_HOST:$DEPLOY_DIR/docker/"
                        scp -P "$PORT" $OPTS deploy/uni-sca.plist "$DEPLOY_HOST:Library/LaunchAgents/$AGENT.plist"
                        # ScanCode has no published image; build it on the server once per pinned tag.
                        # A non-interactive ssh shell lacks Homebrew's PATH, where docker lives.
                        $SSH "export PATH=/opt/homebrew/bin:\\$PATH && cd $DEPLOY_DIR \
                            && (docker image inspect $SCANCODE_IMAGE > /dev/null 2>&1 \
                            || docker build -t $SCANCODE_IMAGE -f docker/scancode.Dockerfile docker/)"
                        # bootout stops the old process (it may report "not loaded" on the first deploy) and returns before
                        # the agent is gone; bootstrapping it again too early fails with "5: Input/output error".
                        $SSH "cd $DEPLOY_DIR && launchctl bootout user/\\$(id -u)/$AGENT 2>/dev/null; \
                            while launchctl print user/\\$(id -u)/$AGENT > /dev/null 2>&1; do sleep 1; done; \
                            mv uni-sca.jar.new uni-sca.jar \
                            && launchctl bootstrap user/\\$(id -u) ~/Library/LaunchAgents/$AGENT.plist"
                    '''
                }
            }
        }

        stage('Verify') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    sh '''
                        ssh -p "${DEPLOY_PORT:-22}" -o StrictHostKeyChecking=accept-new "$DEPLOY_HOST" '
                            for i in $(seq 1 30); do
                                code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8090/api/products)
                                [ "$code" = "200" ] && exit 0
                                sleep 2
                            done
                            echo "app did not become ready (last status: $code)"
                            exit 1
                        '
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
