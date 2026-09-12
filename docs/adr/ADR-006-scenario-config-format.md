# ADR-006: Scenario configuration is YAML, parsed with SnakeYAML

- Status: Accepted
- Date: 2026-09-12

## Decision

Benchmark scenarios are committed YAML files under `research/configs/`, and `benchmark-runner` reads them with `org.yaml:snakeyaml`. The version is managed by the Spring Boot BOM already imported in the root POM, so no new version is pinned here.

The blueprint fixes this format, not this ADR: §13.3 puts `config.yaml` inside every immutable run folder, and P10-01 makes a scenario YAML schema a Phase 10 deliverable. Choosing YAML now means the Phase 2 B0 config is the same artifact Phase 10 will validate against a schema, rather than something that has to be migrated.

## Why not the JDK alone

`java.util.Properties` cannot express the nested and list-valued fields the scenario matrix needs (repetitions, seeds, network profiles, peer classes). Hand-rolling a parser for a subset of YAML would produce a format that looks like YAML but silently disagrees with it, which is worse than a dependency.

## Consequences

- `benchmark-runner` is the only module that parses scenario files. Runtime modules take plain values, never a config file, so an experiment cannot change transfer behaviour by editing YAML in an unexpected place.
- Use `new Yaml(new SafeConstructor(...))`. Scenario files are repository content, but a loader that can instantiate arbitrary classes has no business in a build.
- A config field is part of the experiment record. Renaming one changes what a preserved run means, so it needs an `experiment-method.md` note (blueprint §21.3).
