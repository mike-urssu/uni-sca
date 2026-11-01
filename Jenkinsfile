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
// Deploy server setup (once, as the DEPLOY_HOST user):
//   - JDK 21 and Docker; the user is in the docker group (the app runs the scanner containers).
//   - mkdir -p /opt/projects/uni-sca owned by that user.
//   - loginctl enable-linger <user>, so the user service runs without a login session.
// The app listens on 127.0.0.1:8080 only (HostGuard); reach it with ssh -L 8080:localhost:8080 <DEPLOY_HOST>.
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

                        $SSH "mkdir -p $DEPLOY_DIR/docker ~/.config/systemd/user"
                        scp -P "$PORT" $OPTS backend/build/libs/uni-sca.jar "$DEPLOY_HOST:$DEPLOY_DIR/uni-sca.jar.new"
                        scp -P "$PORT" $OPTS backend/docker/scancode.Dockerfile "$DEPLOY_HOST:$DEPLOY_DIR/docker/"
                        scp -P "$PORT" $OPTS deploy/uni-sca.service "$DEPLOY_HOST:.config/systemd/user/uni-sca.service"
                        # ScanCode has no published image; build it on the server once per pinned tag.
                        $SSH "cd $DEPLOY_DIR && (docker image inspect $SCANCODE_IMAGE > /dev/null 2>&1 \
                            || docker build -t $SCANCODE_IMAGE -f docker/scancode.Dockerfile docker/)"
                        $SSH "cd $DEPLOY_DIR && mv uni-sca.jar.new uni-sca.jar \
                            && systemctl --user daemon-reload && systemctl --user enable uni-sca \
                            && systemctl --user restart uni-sca"
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
                                code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/products)
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
