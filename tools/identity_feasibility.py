#!/usr/bin/env python3
"""Offline recall census. Screens nominate review work; they never select or label cases.

The public summary contains counts and hashes only. Text-bearing review artifacts require
an explicitly supplied private directory that is outside Git or ignored by Git.
"""
from __future__ import annotations

import argparse
import collections
import csv
from decimal import Decimal, InvalidOperation
import hashlib
import html
import json
import os
from pathlib import Path
import struct
import subprocess


WORD = "Words"
SECONDS = "Princeton Dataspace Word Onset Time (sec)"
TR_INDEX = "Princeton Dataspace TR Number"
TR = Decimal("1.5")
SEED = "identity-feasibility-NN03-20261025-v2"
PRONOUNS = frozenset("he him his himself she her hers herself they them their theirs themselves".split())
NOUNS = frozenset("person people man men woman women girl girls boy boys lady ladies body victim victims mother father sister brother daughter son wife husband friend stranger detective officer doctor driver nurse killer murderer patient witness".split())
PRIVATE_FILES = ("review.html", "word-census.json", "decisions.csv", "review-windows.csv")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def number(value):
    try:
        n = Decimal(value)
        return n if n.is_finite() else None
    except (InvalidOperation, TypeError, ValueError):
        return None


def read_csv(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or len(set(reader.fieldnames)) != len(reader.fieldnames):
            raise ValueError("CSV header is missing or repeats a field")
        rows = list(reader)
    if any(None in row or any(v is None for v in row.values()) for row in rows):
        raise ValueError("CSV row width differs from its header")
    return rows


def normalize_word(value):
    return value.casefold().replace("’", "'").strip(" .,;:!?\"'()[]{}")


def screen(value):
    token = normalize_word(value)
    # Contractions nominate a pronoun; no inference about its referent is made.
    base = token.split("'", 1)[0]
    return [kind for kind, words in (("pronoun", PRONOUNS), ("person_noun", NOUNS))
            if base in words]


def scene_intervals(rows, subject):
    intervals = []
    for row in rows:
        if row["Subject"] != str(subject):
            continue
        values = [number(row[k]) for k in ("Scene", "Onset", "Offset")]
        if any(x is None or x != int(x) for x in values):
            raise ValueError("Scene row needs finite integer coordinates")
        scene, start, end = map(int, values)
        if not 1 <= scene <= 50 or not 0 <= start <= end:
            raise ValueError("Scene interval is outside its declared domain")
        intervals.append((scene, start, end))
    if not intervals:
        raise ValueError("No intervals for the declared subject")
    return intervals


def block_order():
    remaining = [s for s in range(1, 51, 5) if s != 26]
    ordered = sorted(remaining, key=lambda s: hashlib.sha256(f"{SEED}:{s}".encode()).hexdigest())
    return {"confirmation": ordered[:5], "discovery": [26] + ordered[5:]}


def scene_split():
    # Fixed adjacent blocks reduce split boundaries without using reference judgments.
    confirmation = set(block_order()["confirmation"])
    return {s: "confirmation" if (s - 1) // 5 * 5 + 1 in confirmation else "discovery"
            for s in range(1, 51)}


def inventory(rows, intervals, window_size=50):
    if window_size <= 0:
        raise ValueError("Window size must be positive")
    ledger = []
    by_scene = collections.defaultdict(lambda: collections.Counter())
    for i, row in enumerate(rows, 1):
        if any(k not in row for k in (WORD, SECONDS, TR_INDEX)):
            raise ValueError("Recall CSV lacks a required field")
        seconds, tr_index = number(row[SECONDS]), number(row[TR_INDEX])
        if seconds is not None and seconds < 0:
            seconds = None
        if tr_index is not None and (tr_index < 1 or tr_index != int(tr_index)):
            tr_index = None
        # Reproduce gold_scene.py's *closed* seconds membership, not a new timing claim.
        legacy = sorted({s for s, a, z in intervals
                         if seconds is not None and a * TR <= seconds <= z * TR})
        # Diagnostic alternative: inclusive membership in the CSV's one-based TR index.
        indexed = sorted({s for s, a, z in intervals
                          if tr_index is not None and a <= tr_index <= z})
        zero_indexed = sorted({s for s, a, z in intervals
                               if tr_index is not None and a <= tr_index - 1 <= z})
        kinds = screen(row[WORD])
        item = {"word_row": i, "window": (i - 1) // window_size + 1,
                "nomination_scenes": indexed, "legacy_seconds_scenes": legacy,
                "zero_based_tr_scenes": zero_indexed,
                "boundary_disagreement": not (legacy == indexed == zero_indexed),
                "timing_missing": seconds is None or tr_index is None,
                "screen": kinds}
        ledger.append(item)
        for s in indexed:
            by_scene[s]["word_rows"] += 1
            by_scene[s]["screened_word_rows"] += bool(kinds)
            for kind in kinds:
                by_scene[s][kind] += 1
    windows = [{"window": i // window_size + 1, "first_word": i + 1,
                "last_word": min(len(rows), i + window_size)}
               for i in range(0, len(rows), window_size)]
    counts = {"word_rows": len(rows), "review_windows": len(windows),
              "screened_word_rows": sum(bool(x["screen"]) for x in ledger),
              "pronoun_screen_rows": sum("pronoun" in x["screen"] for x in ledger),
              "person_noun_screen_rows": sum("person_noun" in x["screen"] for x in ledger),
              "no_scene_nomination_rows": sum(not x["nomination_scenes"] for x in ledger),
              "multiple_scene_nomination_rows": sum(len(x["nomination_scenes"]) > 1 for x in ledger),
              "timing_missing_rows": sum(x["timing_missing"] for x in ledger),
              "boundary_disagreement_rows": sum(x["boundary_disagreement"] for x in ledger),
              "one_vs_zero_tr_disagreement_rows": sum(x["nomination_scenes"] != x["zero_based_tr_scenes"] for x in ledger),
              "one_tr_vs_legacy_seconds_disagreement_rows": sum(x["nomination_scenes"] != x["legacy_seconds_scenes"] for x in ledger)}
    summary = {"schema": "identity-feasibility-census/v1", "counts": counts,
               "scene_nomination_policy": "Onset <= CSV one-based Princeton TR <= Offset; nomination only. Preserve both zero-based inclusive TR and gold_scene.py closed-seconds alternatives; no membership or split decision may depend on a discrepant word's nomination.",
               "scene_counts": [{"scene": s, **dict(v)} for s, v in sorted(by_scene.items())],
               "screen_limit": "Lexical workload screen only; names, ellipsis, other descriptions and unflagged words require human review. Rows and tokens are not attribution decisions.",
               "human_resolvable_decisions": None, "go_status": "not_evaluated",
               "split": {"seed": SEED, "unit": "fixed block of five adjacent source scene IDs",
                         "block_selection_order": block_order(), "assignment": scene_split(),
                         "supersedes": "v1 scene split; see census-v1.json and the plan's seed history",
                         "qualification": "Prospectively untouched for this pilot only; NN03 and this film were previously exposed. Cross-partition decisions are quarantined, never silently reassigned."},
               "source_scope": {"status": "not_sealed_pending_blind_reference_census", "mandatory_discovery_scenes": list(range(26, 31))},
               "review_burden": {"assumptions_not_measurements": True,
                                 "complete_transcript_minutes_at_100_to_200_words_per_minute": [round(len(rows)/200, 1), round(len(rows)/100, 1)],
                                 "decision_adjudication_minutes_per_case": [2, 5],
                                 "decision_count_unknown": True,
                                 "additional_source_viewing_and_anchor_review": "must be added from Claude's source-scope inventory"}}
    return summary, ledger, windows


def audit_legacy_units(rows, units):
    covered = collections.Counter()
    exact = 0
    for u in units:
        endpoints = [struct.unpack(">d", bytes.fromhex(u[k][2:]))[0] for k in ("onset", "lastWordOnset")]
        a, z = endpoints
        if not all(number(str(x)) is not None for x in endpoints) or a > z:
            raise ValueError("Legacy unit has invalid endpoint")
        indices = [i for i, row in enumerate(rows) if number(row[SECONDS]) is not None
                   and a <= float(row[SECONDS]) <= z]
        covered.update(indices)
        exact += " ".join(" ".join(rows[i][WORD] for i in indices).split()) == " ".join(u["text"].split())
    return {"units": len(units), "exact_normalized_text_matches": exact,
            "unique_word_rows_selected_by_saved_endpoints": len(covered),
            "word_rows_not_selected": len(rows) - len(covered),
            "word_rows_selected_more_than_once": sum(v > 1 for v in covered.values()),
            "qualification": "Audit of this saved export's selection, not a claim that every omitted token is a person reference or that the original segmenter is defective."}


def private_directory(path):
    path = Path(path).resolve()
    ancestor = path
    while not ancestor.exists():
        ancestor = ancestor.parent
    root = subprocess.run(["git", "-C", str(ancestor), "rev-parse", "--show-toplevel"],
                          capture_output=True, text=True)
    if root.returncode == 0:
        for name in PRIVATE_FILES:
            check = subprocess.run(["git", "-C", root.stdout.strip(), "check-ignore", "-q", str(path / name)])
            if check.returncode != 0:
                raise ValueError("Every private output inside Git must be ignored")
    if path.exists() and any(path.iterdir()):
        raise ValueError("Private output directory must be new or empty")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path


def write_private(path, rows, ledger, windows):
    def save(name, text):
        target = path / name
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(text)
    save("word-census.json", json.dumps(ledger, indent=2) + "\n")
    page = ["<!doctype html><meta charset='utf-8'><title>Local recall review</title>",
            "<style>body{max-width:900px;margin:40px auto;font:18px/1.8 sans-serif}sup{font-size:9px;color:#666}section{border-top:1px solid #ccc;padding:12px 0}</style>",
            "<h1>Local recall census</h1><p>Review every word. Windows are navigation aids, not independent cases. Record full referring-expression extents and the claim role/occurrence in decisions.csv. Group co-referring repetitions of the same role/occurrence once. Keep ambiguity, non-person references and out-of-scope cases. No arm outputs or scene nominations are shown here.</p>"]
    for window in windows:
        page.append(f"<section><h2>Window {window['window']} — rows {window['first_word']}–{window['last_word']}</h2>")
        for i in range(window["first_word"] - 1, window["last_word"]):
            page.append(f"<span id='w{i+1}'><sup>{i+1}</sup>{html.escape(rows[i][WORD])}</span> ")
        page.append("</section>")
    save("review.html", "\n".join(page))
    save("decisions.csv", "decision_id,first_word,last_word,other_coref_spans,reference_form,claim_role,occurrence_group,source_scene_ids,source_anchor_ids,person_reference,source_resolvable,reference_outcome,ambiguity_reason,reviewer,reviewed_at\n")
    save("review-windows.csv", "window,first_word,last_word,reviewed,reviewer\n" + "".join(
        f"{w['window']},{w['first_word']},{w['last_word']},,\n" for w in windows))
    return {p.name: digest(p) for p in sorted(path.iterdir()) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recall", type=Path, required=True)
    parser.add_argument("--expected-recall-sha256", required=True)
    parser.add_argument("--scenes", type=Path, required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--legacy-voyage", type=Path)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--private-dir", type=Path, required=True)
    args = parser.parse_args()
    if args.summary.exists():
        raise ValueError("Summary output already exists; retain previous runs")
    if digest(args.recall) != args.expected_recall_sha256:
        raise ValueError("Recall bytes do not match the declared source identity")
    rows = read_csv(args.recall)
    intervals = scene_intervals(read_csv(args.scenes), args.subject)
    summary, ledger, windows = inventory(rows, intervals)
    summary["inputs"] = {"recall_sha256": digest(args.recall), "scenes_sha256": digest(args.scenes),
                         "subject": args.subject, "script_sha256": digest(Path(__file__))}
    if args.legacy_voyage:
        summary["inputs"]["legacy_voyage_sha256"] = digest(args.legacy_voyage)
        summary["legacy_unit_audit"] = audit_legacy_units(rows, json.loads(args.legacy_voyage.read_text())["units"])
    private = private_directory(args.private_dir)
    summary["private_artifact_sha256"] = write_private(private, rows, ledger, windows)
    args.summary.parent.mkdir(parents=True, exist_ok=True)
    args.summary.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary["counts"], sort_keys=True))


if __name__ == "__main__":
    main()
