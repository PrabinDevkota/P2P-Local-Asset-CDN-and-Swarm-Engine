# One-command demo (blueprint P12-01).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

& .\mvnw.cmd -B -pl tracker-service,benchmark-runner -am package -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$compose = Join-Path 'infra' 'docker-compose.demo.yml'
if (Get-Command docker -ErrorAction SilentlyContinue) {
    docker compose -f $compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'Docker Compose did not start. The in-process demo still runs.'
    } else {
        Write-Host 'Dashboard: http://localhost:3000  Prometheus: http://localhost:9090  Tracker: http://localhost:8080'
    }
} else {
    Write-Host 'Docker is not on PATH. Redis, tracker, and Grafana are not started.'
}

$env:DEMO_METRICS_PORT = '9109'
& .\mvnw.cmd -q -pl benchmark-runner -am org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.prabin.swarmedge.benchmark.DemoRun" "-Dexec.classpathScope=compile" "-Dexec.args=--serve"
exit $LASTEXITCODE
