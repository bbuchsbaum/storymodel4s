"""Compiled single-defect mutants and restored controls; no corpus access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "recall_clock.py"
TEST = HERE / "test_recall_clock.py"
CASES = [
    ("wrong-release-column", '"openneuro": 4', '"openneuro": 1',
     "test_selected_clock_uses_released_column_not_added_offset"),
    ("skip-byte-identity", 'matches = [s for s in sources if s["localCsvSha256"] == checksum]',
     'matches = [sources[-1]]', "test_unknown_bytes_refuse_before_csv_parse"),
    ("discard-empty-record", '    for record in records:\n',
     '    records = [r for r in records if r["textPresence"] != "empty"]\n    for record in records:\n',
     "test_every_record_survives_quoted_csv_and_empty_word"),
    ("missing-selected-onset-as-zero", 'record["selectedOnset"] = record["values"][selected]',
     'record["selectedOnset"] = record["values"][selected] if record["values"][selected]["status"] == "observed" else {"status": "observed", "decimal": "0"}',
     "test_princeton_selection_keeps_missing_distinct_from_zero"),
    ("float-rounding", 'return {"status": "observed", "decimal": text}',
     'return {"status": "observed", "decimal": str(float(text))}',
     "test_large_exact_and_negative_onsets_are_preserved"),
    ("skip-record-count", 'require(len(records) == source["recordCount"], "record-count-mismatch")',
     'require(True, "record-count-mismatch")', "test_declared_counts_are_checked"),
    ("skip-alias-source-binding", 'require(selected[0]["sourceId"] == source["id"], "alias-source-mismatch")',
     'require(True, "alias-source-mismatch")', "test_omitted_source_does_not_acquire_an_alias"),
    ("alias-sha-not-checked", 'require(row.get("sha256") == known[target], "alias-digest-mismatch")',
     'require(True, "alias-digest-mismatch")', "test_foreign_or_inconsistent_alias_map_refuses"),
    ("invent-scanner-authority", '"status": "unavailable", "reason": "no-verified-run-crosswalk"',
     '"status": "verified", "reason": "no-verified-run-crosswalk"',
     "test_no_duration_scanner_join_or_text_is_fabricated"),
    ("leak-source-text", 'records.append({"record": index,',
     'records.append({"text": cells[0], "record": index,',
     "test_no_duration_scanner_join_or_text_is_fabricated"),
    ("overwrite-existing-output", 'os.link(temporary, path)', 'os.replace(temporary, path)',
     "test_publication_is_complete_and_never_overwrites"),
]


def run(module, test=None):
    args = [sys.executable, str(TEST), "-v"]
    if test:
        args.append("RecallClockSuite." + test)
    result = subprocess.run(args, env={**os.environ, "RECALL_CLOCK_MODULE": str(module)},
                            capture_output=True, text=True)
    return args, result, result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=False)
    source = SOURCE.read_text()
    rows = []
    for label in ("before", "after"):
        command, control, log = run(SOURCE)
        (args.out / (label + ".log")).write_text(log)
        if control.returncode != 0 or "\nOK\n" not in log:
            raise RuntimeError("restored control failed: " + label)
        if label == "after":
            break
        with tempfile.TemporaryDirectory(prefix="recall-clock-mutants-") as directory:
            for name, old, new, test in CASES:
                if source.count(old) != 1:
                    raise RuntimeError("mutation anchor is not unique: " + name)
                mutant_source = source.replace(old, new)
                compile(mutant_source, name, "exec")
                mutant = Path(directory) / (name + ".py")
                mutant.write_text(mutant_source)
                command, result, output = run(mutant, test)
                (args.out / (name + ".log")).write_text(output)
                if result.returncode != 1 or "Ran 1 test" not in output or "FAILED (failures=1)" not in output:
                    raise RuntimeError("mutant did not fail the named assertion: " + name)
                rows.append({"name": name, "test": test, "compiled": True,
                             "mutantSha256": hashlib.sha256(mutant_source.encode()).hexdigest(),
                             "exit": result.returncode, "failures": 1,
                             "logSha256": hashlib.sha256(output.encode()).hexdigest()})
    receipt = {"sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
               "controlBefore": "pass", "controlAfter": "pass", "mutations": rows}
    (args.out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"mutantsKilled": len(rows), "restoredControls": "pass", "receipt": str(args.out / "receipt.json")}))


if __name__ == "__main__":
    main()
