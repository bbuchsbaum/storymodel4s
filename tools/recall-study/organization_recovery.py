#!/usr/bin/env python3
"""Compare frozen compiled-engine artifacts with independent enumeration; retain adverse results."""
import argparse
import hashlib
import itertools
import json
from pathlib import Path
import subprocess

from read_organization import CATEGORIES, check_result, load, number, verified_files


def admission_resource(frozen_bytes):
    """Derive the exact reviewed recall whitelist without copying narrative clauses into Scala."""
    frozen = load(frozen_bytes.decode())
    language = frozen["language"]
    texts = {language["sentences"][0], " ".join(language["sentences"][i] for i in [1, 0])}
    for path, quality in itertools.product(language["paths"], language["quality"]):
        sentences = language["paraphrases"] if quality == "paraphrase" else language["sentences"]
        text = " ".join(sentences[i] for i in path)
        if quality == "transcription":
            for old, new in language["transcription"].items():
                text = text.replace(old, new)
        texts.add(text)
    payload = {"schemaVersion": "organization-language-admission/v1",
               "frozenManifestSha256": hashlib.sha256(frozen_bytes).hexdigest(),
               "textSha256": sorted(hashlib.sha256(t.encode()).hexdigest() for t in texts)}
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def integer_counts(path):
    pairs = list(zip(path, path[1:]))
    return [sum(b < a for a, b in pairs), sum(b > a for a, b in pairs), sum(b == a for a, b in pairs)]


def check_frozen_rows(result, expected, ranks, tolerance=1e-10):
    """Bind the arithmetic oracle to independently reconstructed frozen inputs."""
    key = lambda rank: f"sit:synthetic-rank-{rank}"
    order = {r["target"]: number(r["position"]) for r in result["order"]}
    if (result["orderKind"] != "DeclaredPositions" or result["axis"] != "Discourse"
            or len(order) != len(result["order"]) or order != {key(n): n for n in ranks}):
        raise ValueError("frozen target order substituted")
    rows = result["rows"]
    if len(rows) != len(expected):
        raise ValueError("frozen unit inventory substituted")
    for index, (row, mass) in enumerate(zip(rows, expected)):
        status = "Unresolved" if mass is None else "Available"
        expected_mass = {} if mass is None else {key(n): p for n, p in mass.items()}
        actual = {v["destination"]: number(v["mass"]) for v in row["mass"]}
        if row["unit"] != f"u{index}" or row["status"] != status:
            raise ValueError("frozen unit status substituted")
        if (len(actual) != len(row["mass"]) or actual.keys() != expected_mass.keys()
                or any(abs(actual[k] - p) > tolerance for k, p in expected_mass.items())):
            raise ValueError("frozen row mass substituted")
        if (set(row["admissible"]) != expected_mass.keys()
                or len(row["admissible"]) != len(expected_mass)):
            raise ValueError("frozen admissible population substituted")


def check_packet_inputs(result, path, quality, omitted, storage, tolerance=1e-10):
    expected = [None if i == omitted else {
        n: quality if n == target else (1 - quality) / (len(storage) - 1)
        for n in storage} for i, target in enumerate(path)]
    check_frozen_rows(result, expected, storage, tolerance)


def language_true_mass(result, path, source_size, reported, tolerance=1e-10):
    """Read the true-target mass from rows, rather than trusting an emitted summary."""
    ranks = {number(r["position"]): r["target"] for r in result["order"]}
    if (result["orderKind"] != "TextDiscourseRanks" or result["axis"] != "Discourse"
            or len(ranks) != len(result["order"]) or set(ranks) != set(range(source_size))
            or len(set(ranks.values())) != source_size or len(result["rows"]) != len(path)):
        raise ValueError("language truth inventory substituted")
    actual = [next((number(v["mass"]) for v in row["mass"]
                    if v["destination"] == ranks[target]), 0.0)
              for row, target in zip(result["rows"], path)]
    if len(reported) != len(actual) or any(abs(number(a) - b) > tolerance for a, b in zip(reported, actual)):
        raise ValueError("incorrect reported true-target mass")
    return sum(actual) / len(path)


def language_diagnostics(result, path, truth):
    """Conditional localization and support diagnostics on the retained original units."""
    ranks = {number(r["position"]): r["target"] for r in result["order"]}
    rows = result["rows"]
    true_keys = [ranks[target] for target in path]
    masses = [{v["destination"]: number(v["mass"]) for v in row["mass"]} for row in rows]
    bounds = result.get("bounds")
    return {
        "true_target_admitted_fraction": sum(k in row["admissible"] for k, row in zip(true_keys, rows)) / len(path),
        "source_candidate_fraction": sum(sum(k in row["admissible"] for k in ranks.values()) / len(ranks) for row in rows) / len(path),
        "unique_true_argmax_fraction": sum(m.get(k, 0.0) > max((v for other, v in m.items() if other != k), default=0.0)
                                          for k, m in zip(true_keys, masses)) / len(path),
        "mean_true_vs_best_other_margin": sum(m.get(k, 0.0) - max((v for other, v in m.items() if other != k), default=0.0)
                                               for k, m in zip(true_keys, masses)) / len(path),
        "truth_in_conditional_bounds": None if bounds is None else {
            c: bounds[c]["minimum"] <= n <= bounds[c]["maximum"] for c, n in zip(CATEGORIES[:3], truth)},
    }


def evaluate(manifest_path, results_path):
    frozen_bytes = Path(manifest_path).read_bytes()
    frozen = load(frozen_bytes.decode())
    receipt, files = verified_files(results_path)
    if receipt["schemaVersion"] != "organization-recovery-results/v1":
        raise ValueError("wrong recovery schema")
    metadata = receipt["metadata"]
    if metadata["frozenManifestSha256"] != hashlib.sha256(frozen_bytes).hexdigest():
        raise ValueError("frozen conditions changed")
    # This is a real Git ancestry check; failed substitutions cannot compare equal.
    subprocess.run(["git", "merge-base", "--is-ancestor", metadata["freezeCommit"], metadata["producerCommit"]], check=True, capture_output=True)
    committed = subprocess.run(["git", "show", metadata["freezeCommit"] + ":tools/recall-study/fixtures/organization-recovery/manifest.json"], check=True, capture_output=True).stdout
    if metadata["freezeCommit"] == metadata["producerCommit"] or committed != frozen_bytes:
        raise ValueError("freeze does not precede producer or committed conditions differ")
    tolerance = frozen["tolerance"]
    witnesses = []
    for case in frozen["cases"]:
        artifact = load(files[f"case-{case['id']}.json"])
        if artifact["fixture"] != case:
            raise ValueError("fixture substituted")
        expected = [None if row is None else {n: 1 / len(row) for n in row} for row in case["rows"]]
        ranks = {n for row in case["rows"] if row is not None for n in row}
        check_frozen_rows(artifact["result"], expected, ranks, tolerance)
        summary = check_result(artifact["result"], tolerance)
        actual = [summary["counts"][c] for c in CATEGORIES[:3]]
        if any(abs(a - e) > tolerance for a, e in zip(actual, case["counts"])):
            raise ValueError(f"known-answer failure: {case['id']}")
        witnesses.append({"id": case["id"], **summary})
    sweep = frozen["experiments"]
    expected_conditions = list(itertools.product(sweep["paths"], sweep["correct_mass"], sweep["omitted_indices"], sweep["target_storage_orders"], sweep["displays_per_unit"]))
    experiments = []
    for index, (path, quality, omitted, storage, copies) in enumerate(expected_conditions):
        artifact = load(files[f"experiment-{index}.json"])
        if (artifact["truth"], artifact["correctMass"], artifact.get("omitted"), artifact["storage"], artifact["displayCopies"]) != (path, quality, omitted, storage, copies):
            raise ValueError("experimental condition substituted")
        check_packet_inputs(artifact["result"], path, quality, omitted, storage, tolerance)
        summary = check_result(artifact["result"], tolerance)
        truth = integer_counts(path)
        if artifact["truthCounts"] != truth:
            raise ValueError("wrong frozen truth")
        counts = [summary["counts"][c] for c in CATEGORIES[:3]]
        experiments.append({"path": path, "correct_mass": quality, "omitted": omitted, "storage": storage, "display_copies": copies,
                            "truth_counts": truth, "counts": counts, "bias": [a - t for a, t in zip(counts, truth)],
                            "coverage": artifact["result"]["measuredTransitions"] / max(1, len(path) - 1), "bounds_check": summary["bounds_check"]})
    language_conditions = list(itertools.product(frozen["language"]["paths"], frozen["language"]["quality"], frozen["language"]["temperatures"]))
    language = []
    for index, (path, quality, temperature) in enumerate(language_conditions):
        artifact = load(files[f"language-{index}.json"])
        if (artifact["truth"], artifact["quality"], artifact["temperature"]) != (path, quality, temperature):
            raise ValueError("language condition substituted")
        if artifact.get("status") == "failed":
            language.append({"path": path, "quality": quality, "temperature": temperature, "status": "failed", "reason": artifact["reason"]})
            continue
        summary = check_result(artifact["result"], tolerance)
        mean_true_mass = language_true_mass(artifact["result"], path, len(frozen["language"]["sentences"]), artifact["trueTargetMass"], tolerance)
        truth = integer_counts(path)
        if artifact["truthCounts"] != truth:
            raise ValueError("wrong language truth")
        bias = [summary["counts"][c] - t for c, t in zip(CATEGORIES[:3], truth)]
        if any(abs(a - e) > tolerance for a, e in zip(bias, artifact["countBias"])):
            raise ValueError("incorrect reported bias")
        language.append({"path": path, "quality": quality, "temperature": temperature, "status": "complete", "counts": summary["counts"],
                         "bias": bias, "mean_true_target_mass": mean_true_mass, "bounds_check": summary["bounds_check"],
                         **language_diagnostics(artifact["result"], path, truth)})
    expected_names = {f"case-{case['id']}.json" for case in frozen["cases"]} | {f"experiment-{i}.json" for i in range(len(experiments))} | {f"language-{i}.json" for i in range(len(language))}
    if set(files) != expected_names or metadata["packetExperiments"] != len(experiments) or metadata["languageExperiments"] != len(language):
        raise ValueError("missing, extra or dropped recovery cases")
    attenuation = []
    forward, reverse = sweep["paths"][:2]
    for quality, omitted, storage, copies in itertools.product(sweep["correct_mass"], sweep["omitted_indices"], sweep["target_storage_orders"], sweep["displays_per_unit"]):
        group = [r for r in experiments if (r["correct_mass"], r["omitted"], r["storage"], r["display_copies"]) == (quality, omitted, storage, copies)]
        f = next(r for r in group if r["path"] == forward)
        r = next(r for r in group if r["path"] == reverse)
        effect = r["counts"][0] - f["counts"][0]
        attenuation.append({"correct_mass": quality, "omitted": omitted, "storage": storage, "display_copies": copies,
                            "true_backward_contrast": 3, "observed_backward_contrast": effect, "retained_effect_fraction": effect / 3})
    # Storage and display changes must leave the statistical observations unchanged.
    for path, quality, omitted in itertools.product(sweep["paths"], sweep["correct_mass"], sweep["omitted_indices"]):
        values = [r["counts"] for r in experiments if (r["path"], r["correct_mass"], r["omitted"]) == (path, quality, omitted)]
        if any(any(abs(x - y) > tolerance for x, y in zip(v, values[0])) for v in values[1:]):
            raise ValueError("storage or word-copy inflation")
    language_attenuation = []
    for quality, temperature in itertools.product(frozen["language"]["quality"], frozen["language"]["temperatures"]):
        pair = [next(r for r in language if (r["quality"], r["temperature"], r["path"]) == (quality, temperature, path))
                for path in frozen["language"]["paths"][:2]]
        available = all(r["status"] == "complete" for r in pair)
        effect = pair[1]["counts"]["Backward"] - pair[0]["counts"]["Backward"] if available else None
        language_attenuation.append({"quality": quality, "temperature": temperature,
                                     "true_backward_contrast": 3, "observed_backward_contrast": effect,
                                     "retained_effect_fraction": effect / 3 if available else None})
    return {"schema": "organization-recovery-report/v1", "freeze": metadata,
            "arithmetic_gate": "pass", "scientific_claim": "human recovery and calibration not established",
            "recovery_claim_gate": "adverse bias retained; no general recovery claim",
            "known_answers": witnesses, "experiments": experiments, "effect_attenuation": attenuation, "language": language,
            "language_effect_attenuation": language_attenuation,
            "totals": {"known_answers": len(witnesses), "packet_experiments": len(experiments), "language_experiments": len(language),
                       "language_failures": sum(r["status"] == "failed" for r in language),
                       "biased_packet_conditions": sum(any(abs(b) > tolerance for b in r["bias"]) for r in experiments)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frozen-manifest", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = evaluate(args.frozen_manifest, args.results)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        print(json.dumps(report["totals"], sort_keys=True))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"recovery refused: {error}\n")


if __name__ == "__main__":
    main()
