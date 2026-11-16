#!/usr/bin/env python3
"""Run two isolated semantic mutations, each with passing sibling controls."""

import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / "tools/agentmapping/compare_segmentations.py"
TEST = ROOT / "tools/agentmapping/test_compare_segmentations.py"
CASES = [
    ("accept-first-ambiguous-occurrence", "if len(viable) == 1 else", "if viable else",
     "test_repeated_phrase_remains_ambiguous"),
    ("ignore-raw-lineage", 'or descriptor.get("rawSha256") != sha(raw.encode())', 'or False',
     "test_raw_identity_not_only_canonical_identity"),
]
results = []
for name, old, new, target in CASES:
    with tempfile.TemporaryDirectory(prefix="segmentation-mutant-") as tmp:
        path = Path(tmp)
        source = SOURCE.read_text()
        assert source.count(old) == 1
        source = source.replace(old, new)
        if name == "ignore-raw-lineage":
            source = source.replace('or descriptor.get("rawText") != raw', 'or False')
        (path / SOURCE.name).write_text(source)
        shutil.copyfile(TEST, path / TEST.name)
        subprocess.run([sys.executable, "-m", "py_compile", SOURCE.name], cwd=path, check=True)
        prefix = "test_compare_segmentations.ComparisonTests."
        tests = [target, "test_boundaries_have_independently_known_split_and_merge_counts",
                 "test_utf16_non_bmp_and_split_surrogate_refusal"]
        run = subprocess.run([sys.executable, "-m", "unittest", "-v", *[prefix + t for t in tests]],
                             cwd=path, capture_output=True, text=True)
        assert run.returncode == 1, (name, run.stderr)
        assert "Ran 3 tests" in run.stderr and "FAILED (failures=1)" in run.stderr, run.stderr
        assert ("FAIL: " + target) in run.stderr, run.stderr
        results.append({"mutation": name, "compiled": True, "exit": run.returncode,
                        "failed_test": target, "passing_siblings": 2})
print(json.dumps(results, indent=2))
