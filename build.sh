#!/usr/bin/env bash
# Builds the React UI into the backend's static resources, then the single runnable jar.
set -euo pipefail
cd "$(dirname "$0")"
(cd frontend && npm ci --silent && npm run build -- --logLevel error)
(cd backend && ./gradlew bootJar -q)
echo "backend/build/libs/uni-sca.jar"
