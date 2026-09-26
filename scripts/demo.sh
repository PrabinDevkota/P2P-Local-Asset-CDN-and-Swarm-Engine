#!/usr/bin/env sh
# One-command demo (blueprint P12-01).
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -pl tracker-service,benchmark-runner -am package -DskipTests
if command -v docker >/dev/null 2>&1; then
  docker compose -f infra/docker-compose.demo.yml up -d || echo "Docker Compose did not start. The in-process demo still runs."
  echo "Dashboard: http://localhost:3000  Prometheus: http://localhost:9090  Tracker: http://localhost:8080"
else
  echo "Docker is not on PATH. Redis, tracker, and Grafana are not started."
fi
DEMO_METRICS_PORT=9109 ./mvnw -q -pl benchmark-runner -am org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.prabin.swarmedge.benchmark.DemoRun \
  -Dexec.classpathScope=compile \
  -Dexec.args=--serve
