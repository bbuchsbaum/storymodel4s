#!/usr/bin/env python3
"""Freeze and run an offline caption comparison; outputs remain unaccepted worker proposals.

No media, captions, or reference judgments belong in this script or in Git. This harness
compares supplied frames, not automatic shot detection or downstream alignment accuracy.
"""

import argparse
import copy
import csv
import hashlib
import itertools
import json
import math
from pathlib import Path
import subprocess
import time


IDENTITY_INSTRUCTION = (
    " These images may show different shots and different people. Describe distinct people "
    "separately. Link a person across images only when visible appearance supports that match. "
    "A change of shot does not establish identity. If identity is uncertain, describe the "
    "observations separately without asserting sameness or difference. Preserve visible "
    "actions, posture, clothing, and objects; do not infer unseen actions or events."
)
SINGLE_IMAGE_PROMPT = (
    "Describe only what is visibly present in this image in one short sentence. State the "
    "visible people, their posture or action, and distinctive objects. Do not name characters, "
    "infer unseen events, or refer to any other image."
)
ARMS = ("joint", "identity-aware", "separate-images")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_new(path, value):
    with Path(path).open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True, ensure_ascii=False)
        stream.write("\n")


def request_id(request):
    body = {k: v for k, v in request.items() if k != "requestId"}
    return hashlib.sha256(
        json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def requests(source, cases, frames):
    """Keep model, images, preprocessing and per-call token limit fixed across arms."""
    ids = [c["id"] for c in cases]
    if len(set(ids)) != len(ids) or not ids:
        raise ValueError("cases must have unique nonempty identities")
    extents = {e["id"]: e for e in source["extents"]}
    result = {}
    for arm in ARMS:
        request = copy.deepcopy(source)
        request["frames"]["file"] = str(Path(frames).resolve())
        request["extents"] = []
        for case in cases:
            original = extents[case["extentId"]]
            if case["ordinals"] != original["ordinals"]:
                raise ValueError("case ordinals disagree with the recorded request")
            if arm == "separate-images":
                request["extents"].extend(
                    {"id": f"{case['id']}:image-{i + 1}", "ordinals": [ordinal]}
                    for i, ordinal in enumerate(original["ordinals"])
                )
            else:
                request["extents"].append(
                    {"id": case["id"], "ordinals": list(original["ordinals"])}
                )
        if arm == "identity-aware":
            request["recipe"]["prompt"] += IDENTITY_INSTRUCTION
        elif arm == "separate-images":
            request["recipe"]["prompt"] = SINGLE_IMAGE_PROMPT
        request["requestId"] = request_id(request)
        result[arm] = request
    return result


def stratified_selection(extents, excluded, seed, strata=16):
    """Choose within temporal strata without inspecting caption content or recall matches."""
    result = []
    for i in range(strata):
        lo, hi = i * len(extents) // strata, (i + 1) * len(extents) // strata
        eligible = [e for e in extents[lo:hi] if e["id"] not in excluded]
        if not eligible:
            raise ValueError("temporal stratum has no uninspected interval")
        chosen = min(
            eligible,
            key=lambda e: hashlib.sha256(f"{seed}:{i}:{e['id']}".encode()).hexdigest(),
        )
        result.append(chosen)
    return result


def validate_outcome(request, outcome):
    """Refuse cross-arm substitutions or incomplete outputs before any quality assessment."""
    if outcome["schema"] != "storymodel4s.media.caption-outcome":
        raise ValueError("wrong outcome schema")
    if outcome["schemaVersion"] != 1 or outcome["requestId"] != request["requestId"]:
        raise ValueError("wrong outcome request identity")
    if outcome["recipe"] != request["recipe"]:
        raise ValueError("outcome recipe differs")
    for key in ("sha256", "width", "height", "count", "pixelFormat"):
        if outcome["frames"][key] != request["frames"][key]:
            raise ValueError("outcome frame binding differs")
    for key in ("repo", "revision"):
        if outcome["model"][key] != request["model"][key]:
            raise ValueError("outcome model binding differs")
    prompt_hash = hashlib.sha256(
        request["recipe"]["prompt"].encode("utf-8")
    ).hexdigest()
    if outcome["applied"]["promptSha256"] != prompt_hash:
        raise ValueError("applied prompt differs")
    for key in ("minPixels", "maxPixels", "maxNewTokens", "greedy", "presentation"):
        if outcome["applied"][key] != request["recipe"][key]:
            raise ValueError("applied recipe differs")
    if outcome["offline"] != {"HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"}:
        raise ValueError("offline execution not reported")
    expected = [(e["id"], e["ordinals"]) for e in request["extents"]]
    actual = [(e["id"], e["ordinals"]) for e in outcome["extents"]]
    if actual != expected:
        raise ValueError("outcome extent coverage or order differs")
    if outcome["coverage"] != {"requested": len(expected), "observed": len(expected)}:
        raise ValueError("outcome coverage count differs")
    for extent in outcome["extents"]:
        if not isinstance(extent["text"], str) or not extent["text"].strip():
            raise ValueError("empty caption")


def prepare(args):
    source = read(args.request)
    cases = read(args.cases)
    if digest(args.frames) != source["frames"]["sha256"]:
        raise ValueError("recorded frames hash mismatch")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    manifest = {
        "schema": "storymodel4s.caption-fidelity-experiment/v1",
        "scope": "Raw worker proposals; not a joined or admitted caption package.",
        "selection": cases["selection"],
        "cases": cases["cases"],
        "inputs": {
            str(Path(p).resolve()): digest(p) for p in (args.request, args.cases)
        },
        "worker": {
            "path": str(Path(args.worker).resolve()),
            "sha256": digest(args.worker),
        },
        "harnessSha256": digest(__file__),
        "arms": {},
        "controls": {
            "sameModelAndFrames": True,
            "samePerCallTokenLimit": True,
            "separateImagesHasMoreCallsAndOutputBudget": True,
            "noRecallInput": True,
            "noAutomaticShotDetection": True,
            "noProductionDefaultChange": True,
        },
    }
    for arm, request in requests(source, cases["cases"], args.frames).items():
        path = output / f"{arm}.request.json"
        write_new(path, request)
        manifest["arms"][arm] = {"request": path.name, "sha256": digest(path)}
    write_new(output / "freeze.json", manifest)
    print(f"Frozen {len(cases['cases'])} cases and {len(ARMS)} arms in {output}")


def run(args):
    root = Path(args.experiment)
    manifest = read(root / "freeze.json")
    worker = manifest["worker"]
    if (
        digest(worker["path"]) != worker["sha256"]
        or digest(__file__) != manifest["harnessSha256"]
    ):
        raise ValueError("worker or harness changed after freeze")
    for arm, spec in manifest["arms"].items():
        path = root / spec["request"]
        if digest(path) != spec["sha256"]:
            raise ValueError("request changed after freeze")
        request = read(path)
        output = root / f"{arm}.outcome.json"
        log = root / f"{arm}.log"
        if output.exists() or log.exists():
            raise ValueError(f"refusing to overwrite attempted arm {arm}")
        # Preserve a virtualenv entry point: resolving its symlink selects the base environment.
        argv = [
            str(Path(args.python).absolute()),
            worker["path"],
            str(path),
            str(output),
        ]
        start = time.monotonic()
        print(f"Starting {arm}: {len(request['extents'])} calls", flush=True)
        with log.open("x") as stream:
            try:
                status = subprocess.run(
                    argv,
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    timeout=args.timeout,
                    check=False,
                ).returncode
            except subprocess.TimeoutExpired:
                status = 124
        receipt = {
            "argv": argv,
            "exitCode": status,
            "seconds": time.monotonic() - start,
            "logSha256": digest(log),
        }
        write_new(root / f"{arm}.execution.json", receipt)
        if status != 0:
            raise RuntimeError(f"{arm} failed with exit {status}; see {log}")
        outcome = read(output)
        validate_outcome(request, outcome)
        write_new(
            root / f"{arm}.verified.json",
            {
                "outcomeSha256": digest(output),
                "extents": len(outcome["extents"]),
                "generatedTokens": sum(
                    e["generatedTokens"] for e in outcome["extents"]
                ),
                "atTokenLimit": [
                    e["id"]
                    for e in outcome["extents"]
                    if e["generatedTokens"] >= request["recipe"]["maxNewTokens"]
                ],
            },
        )
        print(f"Verified {arm}: {len(outcome['extents'])} extents", flush=True)


def assessment_summary(rows, references, key):
    """Count complete reference facts separately from partial coverage and identity errors."""
    cases = {case["id"]: case for case in references["cases"]}
    if len(rows) != len(key) or {r["variant_id"] for r in rows} != set(key):
        raise ValueError("assessment must cover every variant exactly once")
    grouped = {}
    for row in rows:
        case = row["variant_id"].rsplit("-v", 1)[0]
        if case not in cases or len(row["facts"]) != len(cases[case]["facts"]):
            raise ValueError("reference fact count differs")
        if not set(row["facts"]) <= set("CPA"):
            raise ValueError("unknown fact rating")
        if row["continuing_subject_retained"] not in ("yes", "no", "na"):
            raise ValueError("unknown continuity rating")
        flags = [
            row[k]
            for k in (
                "false_merge",
                "false_split",
                "unsupported_identity",
                "other_unsupported",
            )
        ]
        if any(flag not in ("0", "1") for flag in flags):
            raise ValueError("error flags must be binary")
        grouped.setdefault(key[row["variant_id"]], []).append((case, row))

    def count(entries):
        return {
            "intervals": len(entries),
            "falseMerge": sum(int(r["false_merge"]) for _, r in entries),
            "falseSplit": sum(int(r["false_split"]) for _, r in entries),
            "identityErrorIntervals": sum(
                any(
                    r[k] == "1"
                    for k in ("false_merge", "false_split", "unsupported_identity")
                )
                for _, r in entries
            ),
            "otherUnsupportedIntervals": sum(
                int(r["other_unsupported"]) for _, r in entries
            ),
            "coveredFacts": sum(r["facts"].count("C") for _, r in entries),
            "partialFacts": sum(r["facts"].count("P") for _, r in entries),
            "absentFacts": sum(r["facts"].count("A") for _, r in entries),
            "retainedContinuity": sum(
                r["continuing_subject_retained"] == "yes" for _, r in entries
            ),
            "continuityEligible": sum(
                r["continuing_subject_retained"] != "na" for _, r in entries
            ),
        }

    result = {}
    for arm, entries in grouped.items():
        if len(entries) != len(cases) or {case for case, _ in entries} != set(cases):
            raise ValueError("each arm must cover the same reference cases")
        result[arm] = {
            "all": count(entries),
            "known": count([e for e in entries if cases[e[0]]["development"]]),
            "additional": count([e for e in entries if not cases[e[0]]["development"]]),
        }
    return result


def summarize(args):
    with Path(args.assessment).open(encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    result = assessment_summary(rows, read(args.references), read(args.key))
    write_new(
        args.output,
        {
            "scope": "Counts of supplied judgments, not automatic or general accuracy validation.",
            "inputs": {
                str(Path(p).resolve()): digest(p)
                for p in (args.assessment, args.references, args.key)
            },
            "arms": result,
        },
    )
    print(f"Summarized {len(rows)} judgments across {len(result)} arms")


def ranked_prices(rows, column):
    available = []
    for row in rows:
        if row[column] == "missing":
            continue
        value = float(row[column])
        if not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError("invalid observed retrieval price")
        available.append(
            {"row": int(row["row"]), "target": row["target"], "cost": value}
        )
    available.sort(key=lambda r: (r["cost"], r["target"]))
    if not available:
        return {
            "status": "unavailable",
            "availableTargets": 0,
            "bestTieCount": 0,
            "top": [],
        }
    flat = available[0]["cost"] == available[-1]["cost"]
    return {
        "status": "flat" if flat else "ranked",
        "availableTargets": len(available),
        "bestTieCount": sum(r["cost"] == available[0]["cost"] for r in available),
        "top": [] if flat else available[:3],
    }


def changed_top(low, high, count):
    return [r["target"] for r in low["top"][:count]] != [
        r["target"] for r in high["top"][:count]
    ]


def pool(args):
    sample = read(args.sample)
    graph = read(args.recall)["graph"]["units"]
    by_ordinal = {u["ordinal"]: u for u in graph}
    selected = {u["ordinal"]: u for u in sample["units"]}
    output = Path(args.output)
    output.mkdir(exist_ok=False)
    rankings, queries = [], []
    gallery = None
    seen = set()
    with Path(args.scores).open(encoding="utf-8") as stream:
        groups = itertools.groupby(
            csv.DictReader(stream, delimiter="\t"), key=lambda r: int(r["unit"])
        )
        for ordinal, group in groups:
            if ordinal in seen:
                raise ValueError("price matrix repeats a noncontiguous query")
            seen.add(ordinal)
            rows = list(group)
            current = {(int(r["row"]), r["target"]) for r in rows}
            if len(current) != len(rows) or len(current) != 1000:
                raise ValueError("incomplete or duplicate gallery")
            if gallery is None:
                gallery = current
            if current != gallery:
                raise ValueError("query galleries differ")
            arms = {
                k: ranked_prices(rows, k)
                for k in ("low_v", "high_v", "low_av", "high_av")
            }
            rankings.append(
                {
                    "unit": ordinal,
                    "arms": arms,
                    "changedPrices": sum(r["low_v"] != r["high_v"] for r in rows),
                }
            )
            if ordinal not in selected:
                continue
            unit = selected[ordinal]
            if (
                unit["id"] != by_ordinal[ordinal]["id"]
                or unit["text"] != by_ordinal[ordinal]["text"]
            ):
                raise ValueError("sample differs from checked recall export")
            candidates = {r["row"] for a in ("low_v", "high_v") for r in arms[a]["top"]}
            order = sorted(
                candidates,
                key=lambda row: hashlib.sha256(
                    f"caption-confirm-pool-v1:{ordinal}:{row}".encode()
                ).hexdigest(),
            )
            context = [
                {"ordinal": n, "text": by_ordinal[n]["text"]}
                for n in range(max(0, ordinal - 2), min(len(graph), ordinal + 3))
            ]
            queries.append(
                {
                    "unit": ordinal,
                    "id": unit["id"],
                    "text": unit["text"],
                    "contextForInterpretationOnly": context,
                    "candidates": [
                        {"pair": f"u{ordinal}-r{row}", "row": row} for row in order
                    ],
                }
            )
    if len(seen) != 173 or not set(selected).issubset(seen) or len(queries) != 24:
        raise ValueError("comparison does not cover the frozen query populations")
    write_new(
        output / "rankings.json",
        {
            "scope": "Full-cut prices, not normalized mapping weights.",
            "units": rankings,
        },
    )
    write_new(
        output / "pooled-queries.json",
        {
            "scope": "Candidate order hides ranks and arms. Context is not scored.",
            "queries": queries,
        },
    )
    summary = {
        "units": len(seen),
        "targets": len(gallery),
        "sampledQueries": len(queries),
        "pooledPairs": sum(len(q["candidates"]) for q in queries),
        "changedPriceQueries": sum(r["changedPrices"] > 0 for r in rankings),
        "changedTop1": sum(
            changed_top(r["arms"]["low_v"], r["arms"]["high_v"], 1) for r in rankings
        ),
        "changedTop3": sum(
            changed_top(r["arms"]["low_v"], r["arms"]["high_v"], 3) for r in rankings
        ),
    }
    write_new(output / "summary.json", summary)
    print(json.dumps(summary, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prep = commands.add_parser("prepare")
    for field in ("request", "frames", "cases", "worker", "output"):
        prep.add_argument(f"--{field}", required=True)
    prep.set_defaults(function=prepare)
    execute = commands.add_parser("run")
    execute.add_argument("--experiment", required=True)
    execute.add_argument("--python", required=True)
    execute.add_argument(
        "--timeout", type=int, default=900, help="maximum seconds per arm"
    )
    execute.set_defaults(function=run)
    report = commands.add_parser("summarize")
    for field in ("assessment", "references", "key", "output"):
        report.add_argument(f"--{field}", required=True)
    report.set_defaults(function=summarize)
    pooling = commands.add_parser("pool")
    for name in ("scores", "sample", "recall", "output"):
        pooling.add_argument(f"--{name}", required=True)
    pooling.set_defaults(function=pool)
    args = parser.parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
