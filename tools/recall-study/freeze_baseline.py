#!/usr/bin/env python3
"""Freeze one development-only engineering replay; never load recall gold.

Raw outputs stay in the ignored data root. The manifest and inventory contain
hashes, identifiers and coordinates. They are regression evidence, not accuracy
or reference-measurement evidence. No third-party Python dependencies.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
SCHEMA = "storymodel4s.bench.frozen-baseline/v1"
SETTINGS = {
    "STORYMODEL4S_SOURCE_TEXT": "bare",
    "STORYMODEL4S_CAPTION_CHANNEL": "embed",
    "STORYMODEL4S_LEXICAL_BLEND": "0.8",
    "STORYMODEL4S_LEXICAL_FIELDS": "lemmas",
    "STORYMODEL4S_CANDIDATES_PER_LEVEL": "8",
    "STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP": "false",
    "STORYMODEL4S_PRIOR_SCALE": "1.5",
    "STORYMODEL4S_MONOTONE_SCENE": "on",
    "STORYMODEL4S_MONOTONE_FILL": "on",
    "STORYMODEL4S_BACKWARD_PENALTY": "hard",
    "STORYMODEL4S_FORWARD_PENALTY": "0.0",
    "STORYMODEL4S_STAGE_TRACE": "on",
}
UNSET = ["STORYMODEL4S_SCENE_CODING", "STORYMODEL4S_SCENE_CAPTIONS",
         "STORYMODEL4S_SHUFFLE_RECALL", "STORYMODEL4S_RUNG",
         "STORYMODEL4S_WITHHOLD_KINDS"]
ARTIFACTS = {"inventory": "inventory.json", "report": "report.tsv",
             "posterior": "report.tsv.posterior.json", "stages": "report.tsv.stages.json",
             "voyage": "report.tsv.voyage.json"}
MODEL = "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"
TOKENIZER = "be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037"


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False, allow_nan=False).encode("utf-8")


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    Path(path).write_bytes(canonical(value) + b"\n")


def file_record(path, base, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    return {"base": base, "path": str(path.relative_to(root)),
            "sha256": file_hash(path), "bytes": path.stat().st_size}


def resolve(record, roots):
    require(record["base"] in roots, "unknown artifact root")
    root = Path(roots[record["base"]]).resolve()
    relative = Path(record["path"])
    require(not relative.is_absolute() and ".." not in relative.parts, "unsafe artifact path")
    path = (root / relative).resolve()
    require(path.is_relative_to(root), "artifact escapes root")
    return path


def check_file(record, roots):
    path = resolve(record, roots)
    require(path.is_file(), "missing artifact: " + record["path"])
    require(path.stat().st_size == record["bytes"] and file_hash(path) == record["sha256"],
            "changed artifact: " + record["path"])
    return path


def unique(rows, key, label):
    values = [r[key] for r in rows]
    require(values and len(values) == len(set(values)), "empty/duplicate " + label)
    return values


def row_locus_bytes(rows):
    """Deliberately simple independent canonical table; ticks never pass through floats."""
    return ("row\tpart\tstartTick\tendTick\n" + "".join(
        f"{r['row']}\t{r['part']}\t{r['startTick']}\t{r['endTick']}\n" for r in rows
    )).encode("utf-8")


def annotation_oracle(annotation, repair):
    """Independent replay of this record's supported identity formula, not a general mapper."""
    parts = {p["partId"]: p for p in repair["presentationEditionIdentity"]["parts"]}
    runs = repair["annotationToPlaybackCrosswalk"]["runs"]
    by_row = {}
    for run in runs:
        require(run["mapping"] == "identity" and
                run["formula"] == "playbackTicks = rawAnnotationSeconds * ticksPerSecond" and
                run["annotationStartSeconds"] == 0 and run["playbackStartTicks"] == 0,
                "oracle refuses unsupported formula")
        first, last = map(int, run["annotationRows"].split("-"))
        for number in range(first, last + 1):
            require(number not in by_row, "overlapping row partition")
            by_row[number] = parts[run["partId"]]
    with Path(annotation).open(encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream, delimiter="\t", quoting=csv.QUOTE_NONE))[1:]
    result = []
    for cells in rows:
        if not cells:
            continue
        number, start, end = map(int, cells[:3])
        require(number in by_row, "uncovered annotation row")
        part = by_row[number]
        rate = part["video"]["ticksPerSecond"]
        result.append({"row": number, "part": part["partId"], "startTick": str(start * rate),
                       "endTick": str(end * rate), "kind": "instant" if start == end else "extent"})
    require([r["row"] for r in result] == sorted(by_row), "annotation row coverage mismatch")
    return result


def inspect_run(paths, expected_inventory=None):
    inv = load(paths["inventory"])
    require(inv["schema"] == "storymodel4s.bench.baseline-inventory/v1", "unknown inventory schema")
    if expected_inventory is not None:
        require(inv == expected_inventory, "changed inventory, identities, context or runtime")
    ordinals = unique(inv["units"], "ordinal", "unit ordinals")
    unit_ids = unique(inv["units"], "id", "unit IDs")
    word_ids = unique(inv["words"], "id", "word IDs")
    target_ids = unique(inv["targets"], "id", "target IDs")
    require([w["index"] for w in inv["words"]] == list(range(len(word_ids))), "word inventory gap")
    require(ordinals == list(range(ordinals[0], ordinals[0] + len(ordinals))), "unit ordinal gap")
    require([r["row"] for r in inv["rowLoci"]] == list(range(1, len(inv["rowLoci"]) + 1)),
            "row locus gap/duplicate")
    for u in inv["units"]:
        expected_words = [w["index"] for w in inv["words"]
                          if w["startUtf16"] < u["endUtf16"] and w["endUtf16"] > u["startUtf16"]]
        require(expected_words and u["wordIndices"] == expected_words,
                "unresolved word membership")
    with Path(paths["report"]).open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t", quoting=csv.QUOTE_NONE)
        require(reader.fieldnames is not None and len(reader.fieldnames) == len(set(reader.fieldnames)),
                "missing/duplicate report header")
        report = list(reader)
    require(all(None not in row and all(v is not None for v in row.values()) for row in report),
            "malformed report row")
    require([int(r["unit"]) for r in report] == ordinals, "report unit loss/duplication/reorder")
    for expected, actual in zip(inv["units"], report):
        require(digest(actual["recallText"].encode()) == expected["reportTextSha256"],
                "report unit text identity mismatch")
    posterior, stages = load(paths["posterior"]), load(paths["stages"])
    require(posterior["schema"] == "storymodel4s.bench.recall-to-video.posterior" and
            posterior["schemaVersion"] == 1 and
            stages["schema"] == "storymodel4s.bench.stage-trace/v1", "unknown sidecar schema")
    require([u["unit"] for u in posterior["units"]] == ordinals, "posterior unit accounting mismatch")
    require([u["unit"] for u in stages["units"]] == ordinals, "stage unit accounting mismatch")
    require([u["unitId"] for u in stages["units"]] == unit_ids, "stage unit identity mismatch")
    require(stages["reportSha256"] == file_hash(paths["report"]), "foreign stage/report receipt")
    require(stages["recallChecksum"] == inv["transcriptSha256"], "foreign recall receipt")
    require(stages["sourceFingerprint"] == inv["sourceFingerprint"], "foreign source receipt")
    anchors = []
    for row, post, stage in zip(report, posterior["units"], stages["units"]):
        chosen = post["decoded"]["ref"] if post["decoded"] else None
        require(chosen == stage["finalAnchor"], "stage/decoded disagreement")
        if chosen is not None:
            require(chosen in target_ids and row["mapAnchor"] == chosen, "foreign/changing chosen target")
        anchors.append({"unit": int(row["unit"]), "id": stage["unitId"],
                        "reportAnchor": row["mapAnchor"], "chosen": chosen})
    voyage = load(paths["voyage"])
    require(isinstance(voyage, dict) and voyage.get("schema") == "storymodel4s.view.recall-voyage" and
            voyage.get("schemaVersion") == 1, "unknown voyage schema")
    require(voyage.get("coding") is None, "unexpected independent coding in baseline")
    require([u["id"] for u in voyage["units"]] == unit_ids and
            [u["ordinal"] for u in voyage["units"]] == ordinals and
            [u["unit"] for u in voyage["rows"]] == unit_ids and
            [u["unit"] for u in voyage["decisions"]] == unit_ids, "voyage unit accounting mismatch")
    require([digest(u["text"].encode()) for u in voyage["units"]] ==
            [u["textSha256"] for u in inv["units"]], "voyage text identity mismatch")
    require(voyage["provenance"]["sourceChecksum"] == inv["sourceFingerprint"], "foreign voyage source")
    require([reference_key(d["anchor"]) for d in voyage["decisions"]] ==
            [a["chosen"] for a in anchors], "voyage decision mismatch")
    voyage_targets = [reference_key(n["ref"]) for n in voyage["timeline"]["nodes"]]
    require(len(voyage_targets) == len(set(voyage_targets)) and
            set(voyage_targets) == set(target_ids), "voyage target accounting mismatch")
    return {"units": len(ordinals), "words": len(word_ids), "targets": len(target_ids),
            "annotationRows": len(inv["rowLoci"]), "anchorsSha256": digest(canonical(anchors)),
            "rowLociSha256": digest(row_locus_bytes(inv["rowLoci"])),
            "outcomes": {"reportRows": len(report), "sourceChosen": sum(a["chosen"] is not None for a in anchors),
                         "withoutSourceChoice": sum(a["chosen"] is None for a in anchors)}}


def reference_key(ref):
    if ref is None:
        return None
    require(ref["type"] in ("Segment", "Situation"), "unknown voyage reference")
    return {"Segment": "seg:", "Situation": "sit:"}[ref["type"]] + ref["id"]


def runner_commands(annotation, recall, paths):
    args = [str(annotation), str(recall)]
    require(all(not any(c.isspace() for c in p) for p in [*args, *map(str, paths.values())]),
            "sbt runner requires paths without whitespace")
    return ["sbt", "-batch",
            "embedBench/Test/runMain storymodel4s.bench.sherlock.SherlockBaselineCapture " +
            " ".join([*args, str(paths["inventory"])]),
            "embedBench/runMain storymodel4s.bench.sherlock.sherlockRecallMap " +
            " ".join([*args, str(paths["report"])])]


def require_clean_code():
    scope = ["*.scala", "*.sbt", "project", "tools/recall-study/freeze_baseline.py"]
    require(not subprocess.check_output(["git", "diff", "HEAD", "--", *scope],
                                        cwd=REPO, text=True), "commit code before capturing")
    require(not subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *scope],
                                        cwd=REPO, text=True), "commit untracked capture code first")


def verify(manifest, data_root, repo=REPO):
    require(manifest["schema"] == SCHEMA, "unknown baseline schema")
    roots = {"data": Path(data_root), "repo": Path(repo)}
    inputs = {name: check_file(record, roots) for name, record in manifest["inputs"].items()}
    partition = load(inputs["partition"])
    require(manifest["participant"] in partition["development"] and
            manifest["participant"] not in partition["untouchedTest"], "non-development participant")
    require(manifest["configuration"] == {"environment": SETTINGS, "unset": UNSET,
            "provider": "pinned-local-onnx", "responseCache": "none; local model recomputed"},
            "changed baseline configuration")
    require(manifest["inputs"]["model"]["sha256"] == MODEL and
            manifest["inputs"]["tokenizer"]["sha256"] == TOKENIZER, "model admission pin mismatch")
    require(any(r["localCsvSha256"] == manifest["inputs"]["recall"]["sha256"]
                for r in load(inputs["recallLineage"])["sources"]), "recall bytes not in admitted lineage")
    inv = manifest["inventory"]
    require(inv["annotationSha256"] == manifest["inputs"]["annotation"]["sha256"] and
            inv["recallSha256"] == manifest["inputs"]["recall"]["sha256"], "foreign inventory input")
    require(inv["rowLoci"] == annotation_oracle(inputs["annotation"], load(inputs["repairRecord"])),
            "complete row-locus oracle mismatch")
    require(len(manifest["runs"]) == 2, "two successful unchanged runs required")
    run_paths = [[resolve(r, roots) for r in [run["log"], *run["artifacts"].values()]]
                 for run in manifest["runs"]]
    require(len(set(run_paths[0] + run_paths[1])) == len(run_paths[0] + run_paths[1]),
            "distinct run artifact and log paths required")
    hashes = []
    for run in manifest["runs"]:
        require(run["exitCode"] == 0 and set(run["artifacts"]) == set(ARTIFACTS),
                "incomplete/failed run")
        paths = {name: check_file(record, roots) for name, record in run["artifacts"].items()}
        log = check_file(run["log"], roots)
        require(log.read_text().endswith("\nCAPTURE_EXIT=0\n"), "missing successful command receipt")
        require(run["command"] == runner_commands(inputs["annotation"], inputs["recall"], paths),
                "foreign command receipt")
        require(inspect_run(paths, inv) == manifest["summary"], "changed run summary")
        hashes.append({name: r["sha256"] for name, r in run["artifacts"].items()})
    require(hashes[0] == hashes[1], "repeat run artifact bytes differ")
    for record in manifest.get("historicalArtifacts", []):
        check_file(record, roots)
    return manifest["summary"]


def capture(data_root, output, participant):
    data_root, output = Path(data_root).resolve(), Path(output).resolve()
    require(output.is_relative_to(data_root), "raw outputs must remain under the data root")
    require(not output.exists(), "refusing to overwrite an existing capture")
    partition_path = data_root / "study/recall-to-video/partition.json"
    partition = load(partition_path)
    require(participant in partition["development"] and participant not in partition["untouchedTest"],
            "only a named development participant may be captured")
    # This is a capture, not a tuning interface. Remove ambient study switches, including gold.
    ambient_options = {"SBT_OPTS", "JVM_OPTS", "JAVA_OPTS", "JAVA_TOOL_OPTIONS",
                       "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}
    env = {k: v for k, v in os.environ.items()
           if not k.startswith("STORYMODEL4S_") and k not in ambient_options}
    env.update(SETTINGS)
    env["ORT_DISABLE_TELEMETRY"] = "1"
    env["STORYMODEL4S_ONNX_MODEL"] = str(data_root / "models/onnx/model.onnx")
    env["STORYMODEL4S_ONNX_TOKENIZER"] = str(data_root / "models/onnx/tokenizer.json")
    inputs = {
        "annotation": (data_root / "sherlock/Sherlock_Segments_1000_NN_2017.tsv", "data", data_root),
        "recall": (data_root / "sherlock/recall" / (participant + ".csv"), "data", data_root),
        "partition": (partition_path, "data", data_root),
        "model": (Path(env["STORYMODEL4S_ONNX_MODEL"]), "data", data_root),
        "tokenizer": (Path(env["STORYMODEL4S_ONNX_TOKENIZER"]), "data", data_root),
        "repairRecord": (REPO / "docs/data/sherlock/timebase-repair.json", "repo", REPO),
        "recallLineage": (REPO / "docs/data/sherlock/recall-lineage.json", "repo", REPO),
    }
    for path in [REPO / "build.sbt", REPO / ".jvmopts", REPO / "project/build.properties",
                 REPO / "project/plugins.sbt", *sorted(REPO.glob("zz*.sbt"))]:
        if path.is_file():
            inputs["build:" + str(path.relative_to(REPO))] = (path, "repo", REPO)
    records = {name: file_record(*args) for name, args in inputs.items()}
    lineage = load(inputs["recallLineage"][0])
    require(any(r["localCsvSha256"] == records["recall"]["sha256"] for r in lineage["sources"]),
            "recall bytes not in admitted lineage")
    require(records["model"]["sha256"] == MODEL and records["tokenizer"]["sha256"] == TOKENIZER,
            "model admission pin mismatch")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    require_clean_code()
    output.mkdir(parents=True)
    manifest = {"schema": SCHEMA, "codeRevision": revision, "participant": participant,
                "configuration": {"environment": SETTINGS, "unset": UNSET, "provider": "pinned-local-onnx",
                                  "responseCache": "none; local model recomputed"},
                "inputs": records, "runs": [], "historicalArtifacts": [],
                "qualification": "development-only engineering replay; no gold read; not reference measurement",
                "excludedFromByteParity": ["run logs: sbt timings, elapsed milliseconds, output paths"],
                "mediaVerification": "declared pins retained in repairRecord; media bytes not opened"}
    # Preserve only this development participant's existing report, never enumerate test outcomes.
    historical = data_root / "study/recall-to-video/all17-monofill" / ("recall-map-" + participant + ".tsv")
    if historical.is_file():
        saved = output / "historical-report.tsv"
        shutil.copyfile(historical, saved)
        manifest["historicalArtifacts"].append(file_record(saved, "data", data_root))
    for number in (1, 2):
        run_dir = output / f"run-{number}"
        run_dir.mkdir()
        paths = {k: run_dir / v for k, v in ARTIFACTS.items()}
        command = runner_commands(inputs["annotation"][0], inputs["recall"][0], paths)
        with (run_dir / "run.log").open("w") as log:
            result = subprocess.run(command, cwd=REPO, env=env,
                                    stdout=log, stderr=subprocess.STDOUT)
            log.write(f"\nCAPTURE_EXIT={result.returncode}\n")
        require(result.returncode == 0, f"run {number} failed; inspect local run.log")
        for record in records.values():
            check_file(record, {"data": data_root, "repo": REPO})
        require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip() == revision,
                "code revision changed during capture")
        require_clean_code()
        summary = inspect_run(paths)
        if number == 1:
            manifest["inventory"] = load(paths["inventory"])
            manifest["summary"] = summary
        manifest["runs"].append({"exitCode": result.returncode, "command": command,
                                 "log": file_record(run_dir / "run.log", "data", data_root),
                                 "artifacts": {k: file_record(p, "data", data_root) for k, p in paths.items()}})
        write_json(output / "capture-progress.json", manifest)
        print(f"Captured run {number}: {summary['units']} units, {summary['annotationRows']} annotation rows", flush=True)
    verify(manifest, data_root)
    write_json(output / "manifest.json", manifest)
    print("Verified identical output bytes and full row-locus table in two runs.", flush=True)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    cap = sub.add_parser("capture")
    cap.add_argument("--data-root", type=Path, required=True)
    cap.add_argument("--out", type=Path, required=True)
    cap.add_argument("--participant", required=True)
    check = sub.add_parser("verify")
    check.add_argument("manifest", type=Path)
    check.add_argument("--data-root", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "capture":
            capture(args.data_root, args.out, args.participant)
        else:
            print(json.dumps(verify(load(args.manifest), args.data_root), sort_keys=True))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print("REFUSED: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
