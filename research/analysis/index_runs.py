"""Map committed scenario files to raw summaries (blueprint P12-03).

Reads YAML names and, when a raw summary exists, the measured fields in that file.
Does not invent bytes, ratios, or figures. Does not modify research/raw.
"""

from pathlib import Path
import json
import sys


def scenario_fields(path: Path) -> dict:
    fields = {"file": path.name, "scenarioId": "", "baseline": ""}
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("scenarioId:"):
            fields["scenarioId"] = stripped.split(":", 1)[1].strip()
        elif stripped.startswith("baseline:"):
            fields["baseline"] = stripped.split(":", 1)[1].strip()
    return fields


def summaries(raw: Path) -> list[dict]:
    found = []
    if not raw.is_dir():
        return found
    for summary in sorted(raw.glob("*/summary.json")):
        try:
            body = json.loads(summary.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            body = {"unreadable": str(summary)}
        body["_path"] = str(summary)
        found.append(body)
    return found


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    configs = sorted((root / "configs").glob("*.yaml"))
    raw_summaries = summaries(root / "raw")
    out = root / "processed" / "experiment-index.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "# Experiment index",
        "",
        "Scenario files are inputs. A row has measured fields only when `research/raw/<run>/summary.json` exists.",
        "This index does not compute an offload ratio.",
        "",
        "| config | scenario | baseline | raw summary |",
        "| --- | --- | --- | --- |",
    ]
    for config in configs:
        fields = scenario_fields(config)
        match = [item["_path"] for item in raw_summaries if fields["scenarioId"] and fields["scenarioId"] in item.get("_path", "")]
        linked = match[0] if match else "no raw summary"
        lines.append(f"| {fields['file']} | {fields['scenarioId']} | {fields['baseline']} | {linked} |")
    lines.append("")
    lines.append(f"Raw summaries found: {len(raw_summaries)}.")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
