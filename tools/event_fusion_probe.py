#!/usr/bin/env python3
"""Local retrieval diagnostic; outputs are proposals, never admitted event support.

All corpus text stays in ignored input/output paths. Audio and captions are embedded separately;
minimum within-channel rank is an explicit nomination policy, not calibrated entailment.
"""

import argparse
import csv
import hashlib
import io
import json
import math
from pathlib import Path

SEED = "native-interval-channel-union-20261021-v1"
CHANNELS = ("audio", "visual", "human")
ARMS = {
    "audio": ("audio",),
    "visual": ("visual",),
    "human": ("human",),
    "audio_visual": ("audio", "visual"),
    "all": CHANNELS,
}


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def write_new(path, value):
    with Path(path).open("x") as handle:
        json.dump(value, handle, indent=2, allow_nan=False)
        handle.write("\n")


def pinned(ref):
    if digest(ref["path"]) != ref["sha256"]:
        raise ValueError("frozen input changed")
    return read(ref["path"])


def overlaps(start, end, segment):
    a, b = segment["start_seconds"], segment["end_seconds"]
    if not all(
        isinstance(x, (int, float)) and math.isfinite(x) for x in (start, end, a, b)
    ):
        raise ValueError("nonfinite or missing time")
    if end < start or b < a:
        raise ValueError("reversed interval")
    return end > start and b > a and max(start, a) < min(end, b)


def select_audio(start, end, transcript):
    if (
        transcript["outcome"] != "complete"
        or transcript["clock"]["origin"] != "source_media"
    ):
        raise ValueError("complete native-clock transcript required")
    segments = [s for s in transcript["segments"] if overlaps(start, end, s)]
    segments.sort(key=lambda s: (s["start_seconds"], s["index"]))
    # A complete overlapping decoder segment carries context outside the central interval.
    # Preserve word times so that a judge can refuse borrowing that context as direct support.
    words = [w for w in transcript["words"] if overlaps(start, end, w)]
    return {
        "text": " ".join(s["text"].strip() for s in segments).strip(),
        "segments": segments,
        "centralWords": words,
        "basis": "Machine transcript assertion, not independently listened evidence.",
    }


def tie(unit, row):
    return hashlib.sha256(f"{SEED}:{unit}:{row}".encode()).hexdigest()


def rank_channel(unit, scores):
    available = [(row, score) for row, score in scores.items() if score is not None]
    if any(not math.isfinite(score) for _, score in available):
        raise ValueError("nonfinite similarity")
    if not available:
        return {"status": "unavailable", "ranks": {}, "top": [], "available": 0}
    if len({score for _, score in available}) == 1:
        return {"status": "flat", "ranks": {}, "top": [], "available": len(available)}
    ordered = sorted(available, key=lambda item: (-item[1], tie(unit, item[0])))
    # Equal similarities have equal competition rank. The hash only orders their presentation.
    ranks = {}
    previous = None
    rank = 0
    for ordinal, (row, score) in enumerate(ordered, 1):
        if score != previous:
            rank = ordinal
        ranks[row] = rank
        previous = score
    return {
        "status": "ranked",
        "ranks": ranks,
        "top": [row for row, _ in ordered[:3]],
        "available": len(available),
        "bestTieCount": sum(score == ordered[0][1] for _, score in ordered),
    }


def combine(unit, ranked):
    rows = set().union(*(r["ranks"] for r in ranked))
    best = {row: min(r["ranks"].get(row, math.inf) for r in ranked) for row in rows}
    return {
        "status": "ranked" if rows else "abstained",
        "top": sorted(rows, key=lambda row: (best[row], tie(unit, row)))[:3],
        "available": len(rows),
    }


def cosine(a, b):
    if not a or len(a) != len(b) or not all(math.isfinite(x) for x in a + b):
        raise ValueError("invalid vector")
    norm = math.sqrt(math.fsum(x * x for x in a) * math.fsum(x * x for x in b))
    if not norm > 0:
        raise ValueError("zero vector")
    return math.fsum(x * y for x, y in zip(a, b)) / norm


def controls():
    """Invented contrasts characterize the encoder; no film or participant excerpts."""
    return {
        "query": "A cyclist is lying unconscious beside a bicycle.",
        "documents": {
            "paraphrase": "The bike rider lies unresponsive on the pavement next to the bike.",
            "wrong-person": "The cyclist stands by the bicycle while a gardener lies unconscious nearby.",
            "sleeping": "The cyclist is sleeping peacefully beside the bicycle.",
            "negated": "The cyclist beside the bicycle is not unconscious and is talking.",
            "earlier-reference": "A person says the cyclist had lain unconscious by a bicycle yesterday.",
            "hypothetical": "Tomorrow a cyclist might fall unconscious beside a bicycle.",
            "unrelated": "A shopkeeper counts coins behind the counter.",
        },
    }


def prepare(args):
    config = read(args.config)
    source = pinned(config["source"])
    annotation = json.loads(source["annotations"])
    media = json.loads(source["media"])
    correspondence = json.loads(source["correspondence"])
    if (
        hashlib.sha256(source["correspondence"].encode()).hexdigest()
        != source["correspondenceSha256"]
    ):
        raise ValueError("correspondence hash changed")
    parts = {p["occurrence"]: p for p in media["parts"]}
    by_annotation = {
        p["annotationPart"]: parts[p["filmOccurrence"]] for p in correspondence["parts"]
    }
    transcripts = {name: pinned(ref) for name, ref in config["transcripts"].items()}
    for name, transcript in transcripts.items():
        if transcript["source"]["sha256"] != by_annotation[name]["input"]:
            raise ValueError("audio and visual media identities differ")
        if digest(transcript["source"]["path"]) != transcript["source"]["sha256"]:
            raise ValueError("source media changed")
    rows = list(csv.DictReader(io.StringIO(annotation["csv"])))
    targets = []
    records = []
    for row in rows:
        ordinal = int(row["ordinal"])
        part = row["part"]
        start = float(row["onset"])
        end = float(row["end"]) if row["end"].strip() else start
        view = by_annotation[part]
        hits = [
            p
            for p in view["proposals"]
            if p["id"] == f"annotation-segment-{ordinal:04d}"
        ]
        if len(hits) > 1:
            raise ValueError("ambiguous visual ownership")
        if hits and (
            int(hits[0]["start"]) / 2500 != start or int(hits[0]["end"]) / 2500 != end
        ):
            raise ValueError("declared annotation/visual clocks differ")
        audio = select_audio(start, end, transcripts[part])
        captions = hits[0]["text"] if hits else ""
        text = {"audio": audio["text"], "visual": captions, "human": row["text"]}
        target = {
            "row": ordinal,
            "part": part,
            "start": start,
            "end": end,
            "audio": audio,
            "visual": captions,
            "human": row["text"],
            "sampleOrdinals": hits[0]["samples"] if hits else [],
        }
        targets.append(target)
        for channel in CHANNELS:
            if text[channel].strip():
                records.append(
                    {
                        "id": f"{channel}:{ordinal}",
                        "role": "document",
                        "text": text[channel],
                    }
                )
    recall = pinned(config["recall"])["graph"]["units"]
    sample = pinned(config["sample"])
    if sample["sourceSha256"] != config["recall"]["sha256"]:
        raise ValueError("sample recall ownership changed")
    units = sorted(recall, key=lambda u: u["ordinal"])
    if len(targets) != 1000 or len(units) != 173:
        raise ValueError("unexpected frozen population")
    for u in units:
        records.append(
            {"id": f"query:{u['ordinal']}", "role": "query", "text": u["text"]}
        )
    contrast = controls()
    records.append({"id": "control:query", "role": "query", "text": contrast["query"]})
    records.extend(
        {"id": f"control:{key}", "role": "document", "text": value}
        for key, value in contrast["documents"].items()
    )
    output = Path(args.output)
    output.mkdir(exist_ok=False)
    write_new(output / "embedding-input.json", {"records": records})
    write_new(
        output / "source-evidence.json",
        {
            "targets": targets,
            "recall": units,
            "sample": [u["ordinal"] for u in sample["units"]],
        },
    )
    write_new(
        output / "freeze.json",
        {
            "protocol": "Native-interval channel-union retrieval v1. Positive decoder-segment overlap; no padding; channels separate. Cosine ranks; minimum channel competition rank; frozen hash ties; no agreement bonus. Flat/missing channels abstain. No entailment, normalized mass or source membership claim.",
            "supportJudging": "Freeze audio/visual support before revealing human annotations. Context outside interval cannot establish direct support. Machine ASR assertion is separate from visible depiction and from listened accuracy. Human text arms use all support; no-human arms use only audio/visual support. All24 development queries retained;92/93 diagnostic only. Same participant/film and human-derived segmentation retained.",
            "config": config,
            "configSha256": digest(args.config),
            "toolSha256": digest(__file__),
            "inputs": {
                n: digest(output / n)
                for n in ("embedding-input.json", "source-evidence.json")
            },
            "syntheticContrasts": contrast,
        },
    )
    print(
        json.dumps(
            {"targets": len(targets), "queries": len(units), "requests": len(records)}
        )
    )


def rankings(args):
    root = Path(args.root)
    freeze = read(root / "freeze.json")
    for name, sha in freeze["inputs"].items():
        if digest(root / name) != sha:
            raise ValueError("frozen material changed")
    submitted = read(root / "embedding-input.json")["records"]
    actual = read(args.embeddings)
    if actual["inputSha256"] != digest(root / "embedding-input.json"):
        raise ValueError("embedding input differs")
    if [r["id"] for r in actual["records"]] != [r["id"] for r in submitted]:
        raise ValueError("embedding request ownership differs")
    values = {
        r["id"]: r["vector"] if r["status"] == "observed" else None
        for r in actual["records"]
    }
    evidence = read(root / "source-evidence.json")
    result = []
    scores = []
    for unit in evidence["recall"]:
        ordinal = unit["ordinal"]
        query = values[f"query:{ordinal}"]
        channels = {}
        for channel in CHANNELS:
            prices = {}
            for target in evidence["targets"]:
                vector = values.get(f"{channel}:{target['row']}")
                prices[target["row"]] = (
                    cosine(query, vector) if query and vector else None
                )
            channels[channel] = rank_channel(ordinal, prices)
            scores.append({"unit": ordinal, "channel": channel, "scores": prices})
        arms = {
            arm: combine(ordinal, [channels[c] for c in selected])
            for arm, selected in ARMS.items()
        }
        result.append({"unit": ordinal, "channels": channels, "arms": arms})
    contrast = {
        key: cosine(values["control:query"], values[f"control:{key}"])
        for key in controls()["documents"]
    }
    write_new(
        root / "rankings.json",
        {"units": result, "embeddingSha256": digest(args.embeddings)},
    )
    write_new(root / "scores.json", scores)
    write_new(
        root / "synthetic-results.json",
        {
            "cosine": contrast,
            "negativeOutranksParaphrase": [
                key
                for key, score in contrast.items()
                if key != "paraphrase" and score >= contrast["paraphrase"]
            ],
            "qualification": "Characterization only: cosine does not establish event support or entailment.",
        },
    )
    baseline = pinned(freeze["config"]["lexicalRanks"])
    baseline = {r["unit"]: r for r in baseline["units"]}
    units = {u["ordinal"]: u for u in evidence["recall"]}
    pool = []
    for ordinal in evidence["sample"]:
        rank = result[ordinal]
        if rank["unit"] != ordinal:
            raise ValueError("recall ordinal/index differs")
        selected = set().union(*(set(c["top"]) for c in rank["channels"].values()))
        selected.update(r["row"] for r in baseline[ordinal]["arms"]["low_v"]["top"])
        # With3channels andtop3, min-rank fusedtop3must be contained in thispool.
        if any(not set(arm["top"]).issubset(selected) for arm in rank["arms"].values()):
            raise ValueError("pool misses a combined-arm candidate")
        pool.append(
            {
                "unit": ordinal,
                "text": units[ordinal]["text"],
                "context": [
                    units[n]["text"]
                    for n in range(max(0, ordinal - 2), min(173, ordinal + 3))
                ],
                "candidates": sorted(selected, key=lambda row: tie(ordinal, row)),
            }
        )
    write_new(
        root / "pooled-queries.json",
        {
            "scope": "No ranks or arm labels. Context only resolves reference; not added to scoring.",
            "queries": pool,
        },
    )
    print(
        json.dumps(
            {
                "pooledPairs": sum(len(q["candidates"]) for q in pool),
                "distinctTargets": len(
                    set().union(*(set(q["candidates"]) for q in pool))
                ),
                "sample": len(pool),
                "outcomes": {
                    status: sum(r["status"] == status for r in actual["records"])
                    for status in sorted({r["status"] for r in actual["records"]})
                },
            }
        )
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--config", required=True)
    p.add_argument("--output", required=True)
    p.set_defaults(function=prepare)
    p = sub.add_parser("rank")
    p.add_argument("--root", required=True)
    p.add_argument("--embeddings", required=True)
    p.set_defaults(function=rankings)
    args = parser.parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
