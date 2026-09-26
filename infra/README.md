# Infra

`docker-compose.demo.yml` starts Redis, the tracker, Prometheus, and Grafana. Origin, the EDGE, and the eight peers run in the demo JVM via `scripts/demo.ps1`. Grafana panels read the metrics that demo writes. They do not contain a target offload.
