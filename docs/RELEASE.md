# Release checklist

Tag `v1.0.0` only after every line below has been run on this commit.

- `./mvnw verify` is green.
- `scripts/demo.ps1` or `scripts/demo.sh` starts the in-process demo: origin, one EDGE, eight seeders, a cold pass, then a warm pass. `DemoRunTest` is that check inside verify.
- Optional Docker: Redis, tracker, Prometheus, and Grafana from `infra/docker-compose.demo.yml`. Grafana dashboard `SwarmEdge demo` reads `swarmedge_demo_origin_bytes`, `swarmedge_demo_peer_bytes`, `swarmedge_demo_completion_millis`, `swarmedge_demo_active_peers`, and `swarmedge_demo_cache_hits`.
- `scripts/reproduce_paper.ps1` writes `research/processed/b0-smoke-table.md` from a measured B0 smoke. The table does not state an offload ratio.
- `python research/analysis/index_runs.py` writes `research/processed/experiment-index.md`. A config with no raw summary stays "no raw summary".

`research/raw/` and `research/processed/` are gitignored. Do not commit measured numbers as if they were source, and do not fill a resume percentage from a demo smoke.
