// Multibranch Pipeline. Every branch is tested; only main is pushed to GHCR and deployed.
//
// Jenkins setup:
//   - Global tool (JDK) named 'jdk21'; the agent needs node/npm on the PATH (frontend build) and runs Docker
//     (image build).
//   - Agent labelled 'macbook' runs the build.
//   - Credentials:
//       ghcr-credentials : Username/Password = GitHub user + classic PAT with write:packages
//       deploy-ssh-key   : SSH private key for the deploy server
//   - Plugins: Workspace Cleanup (cleanWs), SSH Agent (sshagent).
//   - Global property DEPLOY_HOST: user@host of the deploy server.
//   - Optional environment GHCR_OWNER: GitHub user/org that owns the image (defaults to the GHCR username).
//   - Optional environment DEPLOY_PORT: SSH port of the deploy server (defaults to 22).
//
// Deploy server setup (once): $DEPLOY_DIR/.env from deploy/.env.example, a read-only `docker login ghcr.io`,
// and Traefik on the external 'proxy' network.
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
        IMAGE_NAME = 'uni-sca'
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

        stage('Publish image') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    script {
                        // GHCR repository names must be lowercase.
                        def owner = (env.GHCR_OWNER ?: env.GHCR_USER).toLowerCase()
                        env.IMAGE = "ghcr.io/${owner}/${env.IMAGE_NAME}"
                        env.IMAGE_TAG = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    }
                    sh '''
                        ./build.sh
                        # The agent runs as a system daemon without the user's keychain, so Docker's keychain credential
                        # helpers fail (Keychain Error -61). The macOS CLI picks such a helper whenever one is on the PATH,
                        # so run docker with the helpers hidden and a throwaway config; it is removed when the step ends.
                        DOCKER_BIN="$(command -v docker)"
                        docker() { PATH=/usr/bin:/bin:/usr/sbin:/sbin "$DOCKER_BIN" "$@"; }
                        export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
                        export DOCKER_CONFIG="$(mktemp -d)"
                        trap 'rm -rf "$DOCKER_CONFIG"' EXIT
                        ln -s "$HOME/.docker/cli-plugins" "$DOCKER_CONFIG/cli-plugins" # buildx
                        echo "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
                        docker build --pull --platform linux/arm64 -f deploy/Dockerfile \
                            -t "$IMAGE:$IMAGE_TAG" -t "$IMAGE:latest" backend/build/libs
                        docker push "$IMAGE:$IMAGE_TAG"
                        docker push "$IMAGE:latest"
                    '''
                }
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                sshagent(credentials: ['deploy-ssh-key']) {
                    // The server pulls with its own read-only GHCR login, so the write token never leaves Jenkins.
                    sh '''
                        PORT="${DEPLOY_PORT:-22}"
                        OPTS="-o StrictHostKeyChecking=accept-new"
                        # A non-interactive ssh shell lacks Homebrew's PATH, where docker lives.
                        SSH="ssh -p $PORT $OPTS $DEPLOY_HOST PATH=/opt/homebrew/bin:\\$PATH"
                        COMPOSE="docker compose --env-file .env --env-file release.env"

                        $SSH "mkdir -p $DEPLOY_DIR/docker"
                        scp -P "$PORT" $OPTS deploy/compose.yaml "$DEPLOY_HOST:$DEPLOY_DIR/compose.yaml"
                        scp -P "$PORT" $OPTS backend/docker/scancode.Dockerfile "$DEPLOY_HOST:$DEPLOY_DIR/docker/"
                        printf 'IMAGE=%s\\nIMAGE_TAG=%s\\n' "$IMAGE" "$IMAGE_TAG" | $SSH "cat > $DEPLOY_DIR/release.env"
                        # ScanCode has no published image; build it on the server once per pinned tag.
                        $SSH "cd $DEPLOY_DIR && (docker image inspect $SCANCODE_IMAGE > /dev/null 2>&1 \
                            || docker build -t $SCANCODE_IMAGE -f docker/scancode.Dockerfile docker/)"
                        $SSH "cd $DEPLOY_DIR && $COMPOSE pull app && $COMPOSE up -d"
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
