"""Compiled single-defect mutants of the scene-frame planner and restored controls; no media."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent / "extract_scene_frames.py"
TEST = HERE / "test_extract_scene_frames.py"
CASES = [
    (
        "majority-part-reinstated",
        "    chosen = ordered_parts(parts, order)\n",
        "    chosen = ordered_parts(parts, order)\n"
        '    chosen = [max(chosen, key=lambda kd: len(kd[1]["rows"]))]\n',
        "test_straddling_scene_samples_every_part_inside_its_own_extent",
    ),
    (
        "union-span-offsets",
        '        lo, hi = d["start"], d["end"]\n',
        '        lo, hi = min(x["start"] for _, x in chosen), max(x["end"] for _, x in chosen)\n',
        "test_straddling_scene_samples_every_part_inside_its_own_extent",
    ),
    (
        "no-frame-floor-per-part",
        "    spare = per - n\n",
        "    spare = per - 0\n",
        "test_every_part_gets_a_frame_and_counts_sum_exactly",
    ),
    (
        "lexical-part-order",
        "    return [(k, parts[k]) for k in order if k in parts]\n",
        "    return sorted(parts.items())\n",
        "test_timeline_order_is_declared_not_lexical",
    ),
    (
        "tie-to-later-part",
        "key=lambda i: (-(quotas[i] - math.floor(quotas[i])), i)\n",
        "key=lambda i: (-(quotas[i] - math.floor(quotas[i])), -i)\n",
        "test_remainder_tie_goes_to_the_earlier_part",
    ),
    (
        "skip-extent-check",
        "    if math.isfinite(start) and math.isfinite(end) and start >= 0 and end - start > 0:\n",
        "    if True:\n",
        "test_unsamplable_extents_and_budgets_refuse",
    ),
    (
        "legacy-half-second-span",
        "        offsets = [lo + (hi - lo) * (k + 0.5) / m for k in range(m)]\n",
        "        offsets = [lo + max(hi - lo, 0.5) * (k + 0.5) / m for k in range(m)]\n",
        "test_short_extent_control_v1_sampled_outside_v2_stays_inside",
    ),
    (
        "extract-before-refusal",
        '            raise SystemExit(f"refused before extraction: scene {label!r}: {e}")\n',
        "            continue\n",
        "test_refusal_happens_before_any_frame_is_extracted",
    ),
]


def run(module, test=None):
    args = [sys.executable, str(TEST), "-v"]
    if test:
        args.append("SceneFramesSuite." + test)
    result = subprocess.run(
        args,
        env={**os.environ, "SCENE_FRAMES_MODULE": str(module)},
        capture_output=True,
        text=True,
    )
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
        with tempfile.TemporaryDirectory(prefix="scene-frames-mutants-") as directory:
            for name, old, new, test in CASES:
                if source.count(old) != 1:
                    raise RuntimeError("mutation anchor is not unique: " + name)
                mutant_source = source.replace(old, new)
                compile(mutant_source, name, "exec")
                mutant = Path(directory) / (name + ".py")
                mutant.write_text(mutant_source)
                command, result, output = run(mutant, test)
                (args.out / (name + ".log")).write_text(output)
                if (
                    result.returncode != 1
                    or "Ran 1 test" not in output
                    or "FAILED (failures=1)" not in output
                ):
                    raise RuntimeError(
                        "mutant did not fail the named assertion: " + name
                    )
                rows.append(
                    {
                        "name": name,
                        "test": test,
                        "compiled": True,
                        "mutantSha256": hashlib.sha256(
                            mutant_source.encode()
                        ).hexdigest(),
                        "exit": result.returncode,
                        "failures": 1,
                        "logSha256": hashlib.sha256(output.encode()).hexdigest(),
                    }
                )
    receipt = {
        "sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
        "testSha256": hashlib.sha256(TEST.read_bytes()).hexdigest(),
        "controlBefore": "pass",
        "controlAfter": "pass",
        "mutations": rows,
    }
    (args.out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(
        json.dumps(
            {
                "mutantsKilled": len(rows),
                "restoredControls": "pass",
                "receipt": str(args.out / "receipt.json"),
            }
        )
    )


if __name__ == "__main__":
    main()
