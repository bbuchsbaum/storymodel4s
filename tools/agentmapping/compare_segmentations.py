#!/usr/bin/env python3
"""Compare supplied units with a saved automatic segmentation on pinned source text.

No model calls. Exact ordered substring correspondence is established globally;
ambiguous, absent and order-conflicting units are excluded, never assigned their
first text match. The input source is independent of the supplied unit list.
Receipts contain coordinates and counts, never transcript excerpts.
"""

import argparse
import bisect
import csv
import hashlib
import json
from pathlib import Path
import re
import sys


SCHEMA = "storymodel4s.segmentation-comparison/v1"
SOURCE_RECIPES = {
    "sherlock-word-csv-replay/v1": (",", "Words", csv.QUOTE_MINIMAL),
    "filmfestival-event-text-replay/v1": ("\t", "recall_transcript", csv.QUOTE_NONE),
}


class Refusal(ValueError):
    """An input cannot establish the declared common coordinate system."""


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonicalize(raw):
    """The pinned story-source/v1 recipe; no Unicode or internal-space folding."""
    def trim_end(text, removable):
        end = len(text) - 1 if text.endswith(("\u0085", "\u2028", "\u2029")) else len(text)
        start = end
        while start > 0 and text[start - 1] in removable:
            start -= 1
        return text[:start] + text[end:]
    text = raw.replace("\r\n", "\n").replace("\r", "\n")
    text = "\n".join(trim_end(line, " \t") for line in text.split("\n"))
    return trim_end(re.sub(r"\n{3,}", "\n\n", text).lstrip("\n"), "\n")


def replay_source(path, expected_sha, recipe):
    data = Path(path).read_bytes()
    if sha(data) != expected_sha:
        raise Refusal("source file checksum mismatch")
    if recipe not in SOURCE_RECIPES:
        raise Refusal("unknown source replay recipe")
    delimiter, field, quoting = SOURCE_RECIPES[recipe]
    # csv requires newline preservation inside quoted records.
    import io
    reader = csv.DictReader(io.StringIO(data.decode("utf-8"), newline=""),
                            delimiter=delimiter, quoting=quoting)
    if not reader.fieldnames or field not in reader.fieldnames:
        raise Refusal("source field is absent")
    records = list(reader)
    if any(None in row or row.get(field) is None for row in records):
        raise Refusal("malformed source records")
    values = [row[field].strip("".join(map(chr, range(33)))) for row in records]
    raw = " ".join(value for value in values if value)
    if not raw:
        raise Refusal("source replay is empty")
    return raw, {"recipe": recipe, "sha256": expected_sha,
                 "records": len(values), "blank_records": values.count("")}


def occurrences(text, value):
    out, at = [], 0
    while True:
        at = text.find(value, at)
        if at < 0:
            return out
        end = at + len(value)
        # Do not recover a whole supplied token from the middle of a source word.
        left_fragment = at > 0 and text[at - 1].isalnum() and value[0].isalnum()
        right_fragment = end < len(text) and text[end].isalnum() and value[-1].isalnum()
        if not left_fragment and not right_fragment:
            out.append((at, end))
        at += 1


def correspond(text, units):
    """Keep only coordinates shared by every monotone exact-match solution.

    Missing units are explicit exclusions. If the remaining ordered sequence has
    no solution, refuse its correspondence wholesale: choosing a convenient
    subsequence would silently change the reference. Gaps are retained and counted.
    """
    if not units or any(not isinstance(u, str) or not u for u in units):
        raise Refusal("supplied units must be nonempty strings")
    candidates = [occurrences(text, unit) for unit in units]
    present = [i for i, cs in enumerate(candidates) if cs]
    result = [{"status": "absent"} for _ in units]
    if not present:
        return result
    forward = []
    for i in present:
        cs = candidates[i]
        if forward:
            earliest_end = min((b for _, b in forward[-1]), default=None)
            cs = [] if earliest_end is None else [(a, b) for a, b in cs if a >= earliest_end]
        forward.append(cs)
    if not forward[-1]:
        for i in present:
            result[i] = {"status": "order-conflict"}
        return result
    following_start = None
    for i, cs in reversed(list(zip(present, forward))):
        viable = cs if following_start is None else [(a, b) for a, b in cs if b <= following_start]
        following_start = max(a for a, _ in viable)
        result[i] = ({"status": "unique", "span": list(viable[0])}
                     if len(viable) == 1 else {"status": "ambiguous", "candidates": len(viable)})
    return result


def utf16_offsets(text):
    offsets = [0]
    for char in text:
        offsets.append(offsets[-1] + (2 if ord(char) > 0xFFFF else 1))
    return offsets


def checked_automatic(raw, descriptor):
    text = canonicalize(raw)
    if (descriptor.get("recipe") != "recall-segmenter/v1"
            or descriptor.get("canonicalization") != "story-source/v1"
            or descriptor.get("offsetUnit") != "utf-16-code-units"
            or descriptor.get("rawSha256") != sha(raw.encode())
            or descriptor.get("canonicalSha256") != sha(text.encode())
            or descriptor.get("rawText") != raw
            or descriptor.get("canonicalText") != text):
        raise Refusal("automatic descriptor does not match the pinned source replay")
    offsets = utf16_offsets(text)
    reverse = {offset: i for i, offset in enumerate(offsets)}
    spans, previous = [], 0
    rows = descriptor.get("units")
    if not isinstance(rows, list) or not rows:
        raise Refusal("automatic segmentation is empty")
    for i, unit in enumerate(rows, 1):
        ss = unit.get("spans")
        if unit.get("u") != i or not isinstance(ss, list) or len(ss) != 1:
            raise Refusal("automatic units require ordered singleton spans")
        start, end = ss[0].get("start"), ss[0].get("endExclusive")
        if (type(start) is not int or type(end) is not int or start not in reverse
                or end not in reverse or not previous <= start < end):
            raise Refusal("automatic span is unordered, overlapping or splits a surrogate")
        a, b = reverse[start], reverse[end]
        if text[a:b] != unit.get("text"):
            raise Refusal("automatic span text mismatch")
        spans.append((a, b))
        previous = end
    return text, spans


def compare(raw, units, descriptor):
    text, automatic = checked_automatic(raw, descriptor)
    aligned = correspond(text, units)
    offsets = utf16_offsets(text)
    # Compare internal cuts only in runs of consecutive, uniquely placed supplied
    # units. No score crosses an excluded unit or either unobserved outer boundary.
    runs, run = [], []
    for i, row in enumerate(aligned):
        if row["status"] == "unique":
            run.append(i)
        else:
            if len(run) >= 2:
                runs.append(run)
            run = []
    if len(run) >= 2:
        runs.append(run)
    supplied_cuts, auto_cuts = set(), set()
    domains = []
    auto_starts = [a for a, _ in automatic]
    for run in runs:
        lo, hi = aligned[run[0]]["span"][0], aligned[run[-1]]["span"][1]
        domains.append([offsets[lo], offsets[hi]])
        supplied_cuts.update(aligned[i]["span"][0] for i in run[1:])
        auto_cuts.update(auto_starts[bisect.bisect_right(auto_starts, lo):
                                    bisect.bisect_left(auto_starts, hi)])
    covered = set()
    for row in aligned:
        if row["status"] == "unique":
            a, b = row["span"]
            covered.update(range(a, b))
            row["span"] = [offsets[a], offsets[b]]
    statuses = {s: sum(row["status"] == s for row in aligned)
                for s in ("unique", "absent", "ambiguous", "order-conflict")}
    return {
        "schema": SCHEMA,
        "canonical_sha256": sha(text.encode()),
        "raw_sha256": sha(raw.encode()),
        "offset_unit": "utf-16-code-units",
        "correspondence": "all-monotone-exact-occurrences/v1",
        "supplied_units": len(units), "automatic_units": len(automatic),
        "alignment": statuses, "unit_correspondence": aligned,
        "eligible_domains": domains,
        "boundaries": {"matched": len(supplied_cuts & auto_cuts),
                       "supplied_only": len(supplied_cuts - auto_cuts),
                       "automatic_only": len(auto_cuts - supplied_cuts),
                       "eligible_supplied": len(supplied_cuts),
                       "eligible_automatic": len(auto_cuts),
                       "excluded_supplied": max(0, len(units) - 1) - len(supplied_cuts)},
        "uncovered_source_codepoints": len(text) - len(covered),
        "uncovered_source_alphanumeric_codepoints": sum(
            c.isalnum() and i not in covered for i, c in enumerate(text)),
        "mapping_error": {"status": "not-assessed",
                          "reason": "no mapping outputs for the automatic segmentation"},
        "model_calls": 0,
    }


def load_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Refusal("duplicate JSON field")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding="utf-8"), object_pairs_hook=pairs)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.receipt.exists():
            raise Refusal("receipt already exists")
        spec = load_json(args.manifest)
        if spec.get("schema") != SCHEMA:
            raise Refusal("unknown comparison manifest")
        base = args.manifest.resolve().parent
        source = spec["source"]
        raw, lineage = replay_source(base / source["path"], source["sha256"], source["recipe"])
        supplied_path, automatic_path = base / spec["supplied"]["path"], base / spec["automatic"]["path"]
        for path, entry in [(supplied_path, spec["supplied"]), (automatic_path, spec["automatic"])]:
            if sha(path.read_bytes()) != entry["sha256"]:
                raise Refusal("comparison artifact checksum mismatch")
        supplied, automatic = load_json(supplied_path), load_json(automatic_path)
        units = supplied["units"]
        keys = [u["key"] for u in units]
        if any(not isinstance(k, str) or not k for k in keys) or len(set(keys)) != len(keys):
            raise Refusal("supplied row keys must be unique strings")
        report = compare(raw, [u["text"] for u in units], automatic["transcript"])
        report["lineage"] = {"source": lineage,
                             "manifest_sha256": sha(args.manifest.read_bytes()),
                             "supplied_sha256": spec["supplied"]["sha256"],
                             "automatic_sha256": spec["automatic"]["sha256"]}
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        with args.receipt.open("x", encoding="utf-8") as out:
            json.dump(report, out, indent=2)
            out.write("\n")
        print(json.dumps({k: report[k] for k in ["alignment", "boundaries", "model_calls"]}))
        return 0
    except (Refusal, OSError, UnicodeError, ValueError, KeyError, TypeError) as exc:
        # A malformed input may contain participant text; never echo its value.
        print(f"comparison refused ({type(exc).__name__})", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
