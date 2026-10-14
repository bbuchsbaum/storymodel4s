#!/usr/bin/env python3
"""Independent exact-time/index readback of the two actual scannerSamples outputs."""
import copy
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import sys


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def seconds(value):
    require(type(value["numerator"]) is str and type(value["denominator"]) is str,
            "exact rational strings")
    return Fraction(int(value["numerator"]), int(value["denominator"]))


def verify(value, job, job_sha, raw_count, stop):
    require(value["schemaVersion"] == "scanner-samples/v0.1", "schema")
    require(value["job_sha256"] == job_sha, "job binding")
    require(value["scanner_binding"] == {"status": "unestablished"}, "physical authority")
    run, layout = value["run"], value["layout"]
    require(run["authority"] == layout["authority"] == "declared-not-independently-verified",
            "serialized declaration authority")
    require(run["declaration"] == job["run"], "run declaration")
    require(layout["declaration"] == dict(job["layout"], run_digest=run["digest"]),
            "layout declaration")
    require(run["declaration"]["sample_count"] == raw_count, "raw sample count")
    require([seconds(x) for x in run["declaration"]["sample_seconds"]] ==
            [Fraction(3 * i, 2) for i in range(raw_count)], "raw scanner times")
    require(run["declaration"]["origin"] == {"status": "first-stored-sample"}, "origin")
    require(run["declaration"]["applied_history"] == {"status": "unknown"}, "upstream history")
    require(layout["dropped_stored_indices"] == list(range(20)) + list(range(stop, raw_count)),
            "dropped raw indices")
    samples = layout["samples"]
    require(len(samples) == stop - 20, "selected length")
    for analysis_index, sample in enumerate(samples):
        raw_index = analysis_index + 20
        require(sample["analysis_index"] == analysis_index and sample["slot"] == {
            "status": "acquired", "stored_index": raw_index, "censoring": {"status": "unknown"}
        }, "sample/index/censoring")
        require(sample["time"]["status"] == "acquired" and
                seconds(sample["time"]["seconds"]) == Fraction(3 * raw_index, 2), "sample time")


def main(root, receipt):
    rows = []
    rejected = 0
    for part, raw_count, stop in [(1, 972, 966), (2, 1057, 1050)]:
        job_path = root / f"sherlockPart{part}-scanner-job.json"
        output = root / f"part{part}-scanner.json"
        job = json.loads(job_path.read_text())
        value = json.loads(output.read_text())
        verify(value, job, sha(job_path), raw_count, stop)
        changes = []
        for kind in ["time", "drop", "array", "binding", "censor"]:
            changed = copy.deepcopy(value)
            if kind == "time":
                changed["layout"]["samples"][0]["time"]["seconds"]["numerator"] = "0"
            elif kind == "drop":
                changed["layout"]["samples"].pop()
            elif kind == "array":
                changed["layout"]["declaration"]["analysis_array_sha256"] = "0" * 64
            elif kind == "binding":
                changed["scanner_binding"]["status"] = "established"
            else:
                changed["layout"]["samples"][0]["slot"]["censoring"]["status"] = "included"
            try:
                verify(changed, job, sha(job_path), raw_count, stop)
            except ValueError:
                rejected += 1
                changes.append(kind)
            else:
                raise AssertionError("corruption survived: " + kind)
        rows.append({"part": part, "output_sha256": sha(output), "job_sha256": sha(job_path),
                     "raw_samples": raw_count, "selected_samples": stop - 20,
                     "run_digest": value["run"]["digest"], "layout_digest": value["layout"]["digest"],
                     "array_sha256": job["layout"]["analysis_array_sha256"],
                     "corruptions_rejected": changes})
    result = {"source": "0a3a97777c861f41c911ae1232a911590576fd2a", "parts": rows,
              "corruptions_rejected": rejected, "first_selected_raw_time_seconds": "30",
              "physical_synchronization": "unestablished",
              "scope": "emitted index/time declarations; voxel-byte evidence is in actual-readback.json"}
    receipt.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"parts": len(rows), "selected_samples": 1976, "corruptions_rejected": rejected}))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_scanner_outputs.py OUTPUT_DIRECTORY RECEIPT.json")
    main(Path(sys.argv[1]), Path(sys.argv[2]))
