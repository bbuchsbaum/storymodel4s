"""Compiled, single-defect word-clock reader mutations with restored controls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "recall_word_clock.py"
TEST = HERE / "test_recall_word_clock.py"
CASES = [
    ("empty-span-as-owner", 's["start"] < s["end_exclusive"] and s["start"] < word["end_exclusive"]',
     's["start"] < word["end_exclusive"]', "test_interior_empty_span_cannot_own_a_word"),
    ("weaken-id-rules", '0 < units(value) <= 256 and not any(', '0 < units(value) <= 256 and not any(' + '\n        False and ',
     "test_identifier_rules_match_g1_utf16_and_java_whitespace"),
    ("skip-mapping-byte-pin", 'require(clock.digest(mapping_bytes) == expected_mapping_sha256, "mapping-file-digest-mismatch")',
     'require(True, "mapping-file-digest-mismatch")', "test_explicit_recipe_and_external_mapping_pin_are_required"),
    ("skip-inventory-digest", 'require(inventory["digest"] == calculated, "inventory-digest-mismatch")',
     'require(True, "inventory-digest-mismatch")', "test_inventory_and_segmentation_digests_are_independently_checked"),
    ("skip-segmentation-digest", 'require(inventory["segmentation_id"] == segmentation, "segmentation-digest-mismatch")',
     'require(True, "segmentation-digest-mismatch")', "test_inventory_and_segmentation_digests_are_independently_checked"),
    ("codepoint-length", 'return len(utf16(text)) // 2', 'return len(text)',
     "test_utf16_nonbmp_ids_repeated_words_and_quoted_newline"),
    ("word-index-as-csv-record", 'intake["records"][span["record"] - 1]["selectedOnset"]',
     'intake["records"][word["index"]]["selectedOnset"]',
     "test_empty_record_is_retained_without_shifting_word_indices"),
    ("drop-excluded-records", 'for r in intake["records"]]', 'for r in intake["records"] if r["record"] not in omitted]',
     "test_empty_record_is_retained_without_shifting_word_indices"),
    ("invent-duration", '"duration": {"status": "unavailable",', '"duration": {"status": "observed",',
     "test_no_mapping_context_scanner_duration_or_interpolation_authority"),
    ("invent-context-validation", '"fullMappingContextValidation": "not-performed"',
     '"fullMappingContextValidation": "verified"',
     "test_no_mapping_context_scanner_duration_or_interpolation_authority"),
    ("invent-recording-binding", '"recordingIdentityBinding": "not-verified-by-this-reader"',
     '"recordingIdentityBinding": "verified"',
     "test_identical_text_different_recording_clocks_keep_distinct_provenance"),
    ("sort-measured-onsets", 'observed = [w for w in members if w["onset"]["status"] == "observed"]',
     'observed = sorted([w for w in members if w["onset"]["status"] == "observed"], key=lambda w: Decimal(w["onset"]["decimal"]))',
     "test_backward_and_equal_onsets_are_diagnostics_not_repaired"),
    ("partial-as-complete", 'else "partial",', 'else "complete",',
     "test_missing_boundary_is_not_a_measured_unit_start"),
    ("hide-unassigned-words", 'sum(w["membership"]["status"] == "unassigned" for w in words)', '0',
     "test_membership_uses_components_and_overlap_not_hull_or_containment"),
    ("hull-as-membership", 'for s in entry["spans"])]',
     'for s in [{"start": min(s["start"] for s in entry["spans"]), "end_exclusive": max(s["end_exclusive"] for s in entry["spans"])}])]',
     "test_membership_uses_components_and_overlap_not_hull_or_containment"),
    ("hide-missing-adjacencies", '"unobservedPairs": len(adjacent) - len(comparable)', '"unobservedPairs": 0',
     "test_missing_boundary_is_not_a_measured_unit_start"),
]


def run(module, test=None):
    args = [sys.executable, str(TEST), "-v"]
    if test:
        args.append("WordClockSuite." + test)
    result = subprocess.run(args, env={**os.environ, "RECALL_WORD_CLOCK_MODULE": str(module),
                                       "PYTHONPATH": str(HERE.parent)}, capture_output=True, text=True)
    return args, result, result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    source = SOURCE.read_text()
    mutations, controls = [], []
    for phase in ("before", "after"):
        command, result, log = run(SOURCE)
        (args.out / (phase + ".log")).write_text(log)
        if result.returncode != 0 or "\nOK\n" not in log:
            raise RuntimeError("restored control failed: " + phase)
        controls.append({"phase": phase, "command": command, "exit": result.returncode,
                         "logSha256": hashlib.sha256(log.encode()).hexdigest()})
        if phase == "after":
            break
        with tempfile.TemporaryDirectory(prefix="word-clock-mutants-") as directory:
            for name, old, new, test in CASES:
                if source.count(old) != 1:
                    raise RuntimeError("mutation anchor not unique: " + name)
                mutated = source.replace(old, new)
                compile(mutated, name, "exec")
                module = Path(directory) / (name + ".py")
                module.write_text(mutated)
                command, result, log = run(module, test)
                (args.out / (name + ".log")).write_text(log)
                if result.returncode != 1 or "Ran 1 test" not in log or "FAILED (failures=1)" not in log:
                    raise RuntimeError("mutant failed to hit one named assertion: " + name)
                mutations.append({"name": name, "test": test, "command": command,
                                  "compiled": True, "exit": result.returncode, "failures": 1,
                                  "mutantSha256": hashlib.sha256(mutated.encode()).hexdigest(),
                                  "logSha256": hashlib.sha256(log.encode()).hexdigest()})
    receipt = {"sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
               "controls": controls, "mutations": mutations}
    (args.out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"mutantsKilled": len(mutations), "restoredControls": "pass"}))


if __name__ == "__main__":
    main()
