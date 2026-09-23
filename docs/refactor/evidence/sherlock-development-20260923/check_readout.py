#!/usr/bin/env python3
"""Independent Decimal interval and integer-count check; imports no production scorer."""
import argparse
from collections import Counter
import csv
from decimal import Decimal
from fractions import Fraction
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def load(path):
    return json.loads(Path(path).read_text())


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(ok, why):
    if not ok:
        raise ValueError(why)


def close(actual, expected):
    # Exact integer ratios versus Double percentages: 1e-10 percentage points is
    # far below one observation's contribution and accommodates rounding only.
    require(actual is not None and abs(actual - float(expected)) < 1e-10, "rate mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    data, out = args.data_root.resolve(), args.out.resolve()
    protocol = load(HERE / "protocol.json")
    registration = load(HERE / "support-registration.json")
    manifest = load(data / protocol["inputs"]["unitManifest"]["path"])
    support = load(out / "support.json")
    rule = load(ROOT / "embed-bench/src/main/resources/storymodel4s/bench/sherlock/scene-coding-rule.json")
    gold_path = data / protocol["gold"]["path"]
    require(digest(gold_path) == registration["goldSha256"], "gold changed")
    require(digest(out / "support.json") == registration["supportFileSha256"], "support changed")
    intervals = {}
    with gold_path.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            intervals.setdefault(int(row["Subject"]), []).append((
                Decimal(row["Onset"]) * Decimal(str(rule["trSeconds"])),
                Decimal(row["Offset"]) * Decimal(str(rule["trSeconds"])), int(row["Scene"])))
    for rows in intervals.values():
        rows.sort()
    aliases = {r["participant"]: r for r in rule["participants"]}
    people = {p["participant"]: p for p in manifest["participants"] if p["partition"] == "development"}
    expected_names = [p["participant"] for p in protocol["participants"]]
    require(set(people) == set(expected_names), "development population changed")
    expected_gold = {}
    for name in expected_names:
        alias = aliases[int(name[2:4])]
        for unit in people[name]["units"]:
            scene = None
            reason = alias["eligibility"]
            if reason == "eligible":
                if unit["onsetSeconds"] is None:
                    reason = "missing-timing"
                elif alias["goldSubject"] not in intervals:
                    reason = "uncoded-subject"
                else:
                    moment = Decimal(unit["onsetSeconds"])
                    for start, end, label in intervals[alias["goldSubject"]]:
                        if start <= moment <= end:
                            scene = label
                            break
                    reason = None if scene is not None else "outside-coded-intervals"
            expected_gold[name, unit["id"]] = (scene, reason)
    require(len(support["rows"]) == len(expected_gold), "support row count mismatch")
    for row in support["rows"]:
        require((row["goldScene"], row["exclusion"]) == expected_gold[row["participant"], row["id"]],
                "Decimal interval support mismatch")

    comparison = load(out / "A-AV.json")
    self_comparison = load(out / "A-self.json")
    summary = load(out / "summary.json")
    actual_people = {p["participant"]: p for p in comparison["participants"]}
    require(set(actual_people) == set(expected_names), "missing reported participant")
    require(all(v["primaryParticipantAverageDifferencePoints"] == 0.0 and
                v["primaryCI95"] == [0.0, 0.0] for v in self_comparison["series"].values()),
            "A-self control is not zero")
    reference, historical = {}, []
    for index, arm in enumerate(["A", "AV"]):
        content = load(out / (arm + ".json"))
        parts = {p["participant"]: p for p in content["participants"]}
        require(set(parts) == set(expected_names), "arm participant set mismatch")
        reference[arm] = {}
        for name in expected_names:
            part = parts[name]
            expected_ids = {u["id"] for u in people[name]["units"]}
            require(len(part["units"]) == len(expected_ids) and
                    {u["id"] for u in part["units"]} == expected_ids, "arm unit set mismatch")
            eligible = exact = within = 0
            outcomes = Counter()
            for unit in part["units"]:
                outcome = unit["outcome"]
                outcomes[outcome["kind"]] += 1
                gold, _ = expected_gold[name, unit["id"]]
                if gold is not None:
                    eligible += 1
                    if outcome["kind"] == "label":
                        exact += outcome["scene"] == gold
                        within += abs(outcome["scene"] - gold) <= 1
            result = actual_people[name]
            require(result["inputUnits"] == len(expected_ids) and result["goldEligibleUnits"] == eligible,
                    "reported denominator mismatch")
            require({k: v for k, v in result["arms"][index]["outcomes"].items() if v} == dict(outcomes),
                    "outcome accounting mismatch")
            if eligible:
                close(result["arms"][index]["sceneExact"], Fraction(100 * exact, eligible))
                close(result["arms"][index]["sceneWithinOne"], Fraction(100 * within, eligible))
            else:
                require(result["arms"][index]["sceneExact"] is None, "missing gold became a score")
            reference[arm][name] = {"units": len(expected_ids), "eligible": eligible,
                                    "correct": exact, "withinOne": within, "outcomes": dict(outcomes)}
        selected = [v for v in reference[arm].values() if v["eligible"]]
        macro = sum(Fraction(100 * v["correct"], v["eligible"]) for v in selected) / len(selected)
        pooled = Fraction(100 * sum(v["correct"] for v in selected), sum(v["eligible"] for v in selected))
        close(summary["arms"][arm]["participantAverageSceneExactPercent"], macro)
        close(summary["arms"][arm]["pooledSceneExactPercent"], pooled)
    selected_names = [n for n in expected_names if reference["A"][n]["eligible"]]
    delta = sum(Fraction(100 * (reference["AV"][n]["correct"] - reference["A"][n]["correct"]),
                         reference["A"][n]["eligible"]) for n in selected_names) / len(selected_names)
    close(comparison["series"]["sceneExact"]["primaryParticipantAverageDifferencePoints"], delta)
    for name in expected_names:
        filename = "recall-map-" + name + ".tsv"
        current, old = out / "A" / filename, data / "study/recall-to-video/all17-monofill" / filename
        historical.append({"participant": name, "newSha256": digest(current),
                           "historicalSha256": digest(old) if old.is_file() else None,
                           "byteIdentical": digest(current) == digest(old) if old.is_file() else None})
    result = {"schema": "sherlock-development-independent-check/v1", "supportRowsChecked": len(expected_gold),
              "method": "raw gold Decimal intervals; integer outcome counts; Fraction percentages; no production scorer import",
              "percentageTolerance": 1e-10, "allChecksPassed": True, "integerCounts": reference,
              "historicalDevelopmentReportParity": historical,
              "sameRegisteredAnalysisGoldReread": True,
              "scriptSha256": digest(Path(__file__)),
              "checkedArtifacts": {n: digest(out / n) for n in
                                   ["support.json", "A.json", "AV.json", "A-self.json", "A-AV.json", "summary.json"]}}
    with (out / "independent-check.json").open("x") as stream:
        json.dump(result, stream, indent=2, allow_nan=False)
        stream.write("\n")
    print(json.dumps({"supportRowsChecked": len(expected_gold), "allChecksPassed": True,
                      "historicalByteIdentical": sum(r["byteIdentical"] is True for r in historical),
                      "historicalCompared": len(historical)}))


if __name__ == "__main__":
    main()
