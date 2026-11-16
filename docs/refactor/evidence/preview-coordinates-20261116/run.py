#!/usr/bin/env python3
"""Reproduce the local, text-free aggregate comparison using existing seen inputs.

Preparation reads only the pinned Sherlock development list and FilmFestival
sessions already present in the existing annotation-assisted development run.
Private text stays under the explicitly supplied ignored output directory.
Run the two emitted agentStudyPrepare commands in a standalone checkout, then
run this script with `compare`. No execution/provider command is emitted.
"""

import argparse
import collections
import csv
import json
from pathlib import Path
import shlex
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "tools/agentmapping"))
import compare_segmentations as comparison


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2)
        handle.write("\n")


def tsv(path):
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t", quoting=csv.QUOTE_NONE))


def pin(path):
    return {"path": str(path.resolve()), "sha256": comparison.sha(path.read_bytes())}


def prepare(data, output):
    if output.exists():
        raise ValueError("output already exists")
    partition_path = data / "study/recall-to-video/partition.json"
    partition = comparison.load_json(partition_path)
    dev = partition["development"]
    if not dev or len(dev) != len(set(dev)) or set(dev) & set(partition["untouchedTest"]):
        raise ValueError("invalid development partition")
    lineage_path = data / "study/recall-to-video/scorer-20260919/unit-manifest.json"
    lineage = {p["participant"].replace("_recall", ""): p
               for p in comparison.load_json(lineage_path)["participants"]}
    studies = {"sherlock": [], "filmfestival": []}
    sherlock_canonical = {}
    for name in dev:
        stem = name.removesuffix(".csv").replace("_recall", "")
        original = data / "sherlock/recall" / (stem + ".csv")
        supplied = data / "study/recall-to-video/development-20260923/A" / f"recall-map-{stem}.tsv"
        if not supplied.exists():
            supplied = data / "study/recall-to-video/all17-monofill" / f"recall-map-{stem}.tsv"
        rows = tsv(supplied)
        reference = lineage[stem]
        if pin(original)["sha256"] != reference["recallSha256"] or len(rows) != len(reference["units"]):
            raise ValueError("Sherlock input does not match the pinned unit lineage")
        if any(int(row["unit"]) != unit["ordinal"] or
               comparison.sha(row["recallText"].encode()) != unit["reportTextSha256"]
               for row, unit in zip(rows, reference["units"])):
            raise ValueError("Sherlock supplied units do not match the pinned unit lineage")
        sherlock_canonical[str(original.resolve())] = reference["transcriptSha256"]
        studies["sherlock"].append((original, supplied, "sherlock-word-csv-replay/v1",
                                    [{"key": row["unit"], "text": row["recallText"]} for row in rows]))
    gold_path = data / "filmfestival/derived/gold-scenematched.tsv"
    seen_path = data / "study/filmfestival/llm-transfer-arms-20261109/opus/manifest.json"
    seen = comparison.load_json(seen_path)
    if comparison.sha(gold_path.read_bytes()) != seen["goldSha256"]:
        raise ValueError("FilmFestival reference differs from the seen-run reference")
    allowed = {(s["participant"], s["session"]) for s in seen["sessions"]}
    grouped = collections.defaultdict(list)
    for row in tsv(gold_path):
        if (row["participant"], row["run"]) in allowed:
            grouped[(row["participant"], row["run"])].append(row)
    if set(grouped) != allowed:
        raise ValueError("seen FilmFestival session inventory mismatch")
    for (participant, session), rows in sorted(grouped.items()):
        rows.sort(key=lambda row: int(row["gold_row"]))
        original = data / "filmfestival/recall_events" / f"{participant}_task-recall_{session}_events.tsv"
        studies["filmfestival"].append((original, gold_path, "filmfestival-event-text-replay/v1",
                                       [{"key": row["gold_row"], "text": " ".join(row["text"].split())}
                                        for row in rows]))
    context = {"eligibility": [pin(partition_path), pin(lineage_path), pin(seen_path), pin(gold_path)],
               "studies": {}, "sherlock_canonical": sherlock_canonical}
    commands = []
    for study, entries in studies.items():
        folder = output / study
        folder.mkdir(parents=True)
        sessions, records = [], []
        for i, (original, supplied, recipe, units) in enumerate(entries):
            raw, _ = comparison.replay_source(original, pin(original)["sha256"], recipe)
            text_path, units_path = folder / f"transcript-{i}.txt", folder / f"supplied-{i}.json"
            text_path.write_text(raw, encoding="utf-8")
            write(units_path, {"origin": pin(supplied), "units": units})
            sessions.append({"participant": f"local{i + 1:02}", "session": "recall",
                             "transcript": text_path.name})
            records.append({"source": {**pin(original), "recipe": recipe},
                            "supplied": pin(units_path), "supplied_origin": pin(supplied),
                            "automatic_path": str(folder / f"automatic/session-{i}.json")})
        manifest = folder / "study.json"
        write(manifest, {"schema": "storymodel4s.agentmapping.transcript-study/v1",
                         "model": "offline-no-provider", "condition": "boundary-comparison",
                         "chunkSize": 50, "sessions": sessions})
        source_dir = (data / ".slice5/20261115/sherlock-export" if study == "sherlock" else
                      data / ".recovery/20261114/filmfestival-source")
        source_path = source_dir / ("source.csv" if study == "sherlock" else "annotations.csv")
        parts_path = source_dir / "parts.json"
        context.setdefault("source_artifacts", {})[study] = [pin(source_path), pin(parts_path)]
        command = ["pipeline/runMain", "storymodel4s.pipeline.agentmapping.agentStudyPrepare",
                   str(source_path), str(parts_path),
                   str(manifest), str(folder / "automatic")]
        commands.append(shlex.join(command))
        context["studies"][study] = records
    write(output / "context.json", context)
    write(output / "commands.json", commands)
    print(json.dumps({"sessions": {k: len(v) for k, v in studies.items()}, "model_calls": 0}))


def compare(output):
    context = comparison.load_json(output / "context.json")
    for item in context["eligibility"]:
        if pin(Path(item["path"])) != item:
            raise ValueError("eligibility artifact changed")
    for artifacts in context["source_artifacts"].values():
        for item in artifacts:
            if pin(Path(item["path"])) != item:
                raise ValueError("source dictionary artifact changed")
    aggregate = {"schema": comparison.SCHEMA, "studies": {}, "model_calls": 0,
                 "mapping_error": "not-assessed; automatic segmentation has no model outputs"}
    for study, entries in context["studies"].items():
        totals = collections.Counter()
        for i, entry in enumerate(entries):
            if pin(Path(entry["supplied_origin"]["path"])) != entry["supplied_origin"]:
                raise ValueError("supplied source artifact changed")
            manifest = output / study / f"comparison-{i}.json"
            write(manifest, {"schema": comparison.SCHEMA, "source": entry["source"],
                             "supplied": entry["supplied"],
                             "automatic": pin(Path(entry["automatic_path"]))})
            receipt = output / study / f"receipt-{i}.json"
            if comparison.main([str(manifest), "--receipt", str(receipt)]) != 0:
                raise ValueError("comparison refused")
            report = comparison.load_json(receipt)
            if study == "sherlock" and report["canonical_sha256"] != context["sherlock_canonical"][entry["source"]["path"]]:
                raise ValueError("Sherlock canonical transcript differs from pinned lineage")
            totals["sessions"] += 1
            for key in ["supplied_units", "automatic_units", "uncovered_source_alphanumeric_codepoints"]:
                totals[key] += report[key]
            totals.update({"alignment_" + k: v for k, v in report["alignment"].items()})
            totals.update({"boundary_" + k: v for k, v in report["boundaries"].items()})
        aggregate["studies"][study] = dict(totals)
    write(output / "aggregate.json", aggregate)
    print(json.dumps(aggregate, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["prepare", "compare"])
    parser.add_argument("--data-root", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.phase == "prepare":
        if args.data_root is None:
            parser.error("prepare requires --data-root")
        prepare(args.data_root.resolve(), args.output.resolve())
    else:
        compare(args.output.resolve())
