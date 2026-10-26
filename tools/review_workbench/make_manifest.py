#!/usr/bin/env python3
"""Write a workbench manifest for a real packet: absolute paths and SHA-256 pins only. It reads
no recall content (the recall file is hashed and checked against its expected pin), so an agent
may run it; only the owner opens the resulting packet in the workbench.

Usage (Sherlock, NN03):
  python3 -I make_manifest.py OUT_DIR --repo REPO --census-dir CENSUS_DIR \
      --expect-recall-sha256 5bfe5e4afa516e86d302912db8f6978f16f80ab00a84cb2a878eb2ed0c15e48e \
      --reviewer NAME --gate-op MOTE_OP
OUT_DIR is created with mode 0700 and must not exist.
"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess

SHERLOCK = {
    "annotation": "data/sherlock/Sherlock_Segments_1000_NN_2017.tsv",
    "watchOnsets": "data/sherlock/Sherlock_Watch_Scene_N50_Onsets.csv",
    "recall": "data/sherlock/recall/NN03_ANT_202231_final.csv",
    "transcript1": "data/transcription/unified-v1/full/whisper_mlx/sherlock/Sherlock_part1_imovie/transcript.json",
    "transcript2": "data/transcription/unified-v1/full/whisper_mlx/sherlock/Sherlock_part2_imovie/transcript.json",
}
MEDIA = {
    "1": "data/sherlock/media/Sherlock_part1_imovie.m4v",
    "2": "data/sherlock/media/Sherlock_part2_imovie.m4v",
}
# The 1000-row table: rows 1-482 are part one; part-two TRs are numbered continuing from 946.
PARTS = {
    "1": {"rows": [1, 482], "trShift": 0},
    "2": {"rows": [483, 1000], "trShift": 946},
}


def sha(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def duration(p: Path) -> float:
    out = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "csv=p=0",
            str(p),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return round(float(out.stdout.strip()), 3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--repo", required=True)
    ap.add_argument("--census-dir", required=True)
    ap.add_argument("--expect-recall-sha256", required=True)
    ap.add_argument("--reviewer", required=True)
    ap.add_argument(
        "--gate-op", required=True, help="Mote op recording the owner's gate decision"
    )
    ap.add_argument("--subject", default="NN03")
    ap.add_argument(
        "--gate-mode",
        default="reference-first",
        choices=["reference-first", "complete-all"],
        help="reference-first opens the recall pass directly (owner's direct-first plan); "
        "complete-all requires the whole source pass first",
    )
    a = ap.parse_args()
    repo, census = Path(a.repo).resolve(), Path(a.census_dir).resolve()
    out = Path(a.out_dir)
    if out.exists():
        raise SystemExit("OUT_DIR must not exist")
    inputs = {k: repo / v for k, v in SHERLOCK.items()}
    inputs.update(
        {
            "census": census / "word-census.json",
            "windows": census / "review-windows.csv",
            "decisions": census / "decisions.csv",
        }
    )
    pins = {k: {"path": str(p), "sha256": sha(p)} for k, p in inputs.items()}
    if pins["recall"]["sha256"] != a.expect_recall_sha256:
        raise SystemExit("recall does not match its expected pin")
    # Part two's local scene numbers continue after part one's last scene.
    with open(inputs["watchOnsets"], newline="", encoding="utf-8-sig") as f:
        part1_scenes = sum(1 for r in csv.DictReader(f) if r["Scan"] == "Part1")
    parts = {}
    for p, spec in PARTS.items():
        media = repo / MEDIA[p]
        parts[p] = {
            **spec,
            "seconds": duration(media),
            "firstScene": 1 if p == "1" else part1_scenes + 1,
        }
    manifest = {
        "schema": "review-workbench-manifest/v0",
        "subject": a.subject,
        "synthetic": False,
        "tr": 1.5,
        "parts": parts,
        "inputs": pins,
        "media": {
            p: {"path": str(repo / m), "sha256": sha(repo / m)}
            for p, m in MEDIA.items()
        },
        "gate": {"mode": a.gate_mode, "decisionOp": a.gate_op},
        "reviewer": a.reviewer,
    }
    out.mkdir(mode=0o700, parents=True)
    os.chmod(out, 0o700)
    path = out / "manifest.json"
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(json.dumps(manifest, indent=1) + "\n")
    print(
        json.dumps(
            {
                "manifest": str(path),
                "inputs": len(pins),
                "parts": {p: v["firstScene"] for p, v in parts.items()},
            }
        )
    )


if __name__ == "__main__":
    main()
