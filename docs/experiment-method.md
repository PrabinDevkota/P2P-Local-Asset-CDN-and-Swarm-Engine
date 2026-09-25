# Experiment method

Paper runs are committed YAML under `research/configs/`. A config is an input. It does not contain a target Mbps or an offload percentage.

## Factors every scenario names

`PaperScenario` requires each file to name:

| Factor | Where |
| --- | --- |
| baseline | `baseline` (`B0`–`B6`, `B9`) |
| scale | `scale.peers`, `scale.clients` |
| network | `network.profile`, delay, jitter, loss |
| churn | `churn.killFractions` or `churn.mode` |
| cache | `cacheState`: `cold` or `warm` |
| seed | `run.seed` |
| repetitions | `run.repetitions` |

B1, B2, and B3 still differ only in `scheduler`. Adding `scale`, `network`, and `cacheState` records the loopback, single-client setup those files already ran. It does not change seeder count, seed, pipeline depth, or LAPS weights.

`scale-clients-10.yaml`, `scale-clients-25.yaml`, and `scale-clients-50.yaml` name the flash-crowd counts from the scenario matrix. `./mvnw verify` does not download a full asset at those counts. `ClientFanout` is what starts N client tasks; the unit test runs 10, 25, and 50 trivial tasks so the counts execute.

## Network impairment

`TcNetem` applies `tc qdisc replace ... netem` and deletes it in a `finally`, including when the run throws. A loopback profile does not call `tc`. If `tc` is missing, the session stays unapplied and the run record says so. That is not a shaped WAN.

## Raw runs

`BenchmarkHarness` writes `research/raw/<runId>/` with `config.yaml`, `environment.json`, `git_commit.txt`, `seed.txt`, `events.csv.gz`, `peer_metrics.csv.gz`, `summary.json`, `validation.json`, and `stdout/runner.log`. A folder whose `validation.json` says `passed: true` is not overwritten.

`scripts/reproduce_paper.ps1` (and `reproduce_paper.sh`) runs a 320-byte B0 three times and writes `research/processed/b0-smoke-table.md`. The table lists origin bytes, cache bytes, and elapsed time. It does not compute an offload ratio.

`research/configs/b5-fastcdc.yaml` names a FastCDC cross-version study. `CrossVersionStudy` records shared hash bytes, canonical manifest size, chunking time, elapsed time, and a 95% bootstrap interval for B4 and B5. It does not rank them and does not state a dedup ratio. `./mvnw verify` runs that study on a small file, not the 64 MiB size named in the YAML.

## What a number is allowed to mean

Report only values taken from a raw run folder. Docker-host loopback is not a production WAN. Emulated client counts must be labeled as such.
