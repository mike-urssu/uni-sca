# App image, built by the Jenkinsfile from backend/build/libs (the jar build.sh produces).
# The app clones repositories with git and runs the scanners through the host's docker socket (compose.yaml).
FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
COPY --from=docker:29.2.1-cli /usr/local/bin/docker /usr/local/bin/docker
COPY uni-sca.jar /app/uni-sca.jar
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/uni-sca.jar", "serve"]
