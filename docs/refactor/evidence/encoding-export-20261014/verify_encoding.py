#!/usr/bin/env python3
"""Independent byte and original-TSV readback; imports no export producer code."""
import argparse
from collections import Counter
import copy
import csv
import gzip
import hashlib
import json
from pathlib import Path


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def exact_slice(raw_voxels, output, bytes_per_volume, first_one_based, last_one_based):
    # The primary MATLAB prescription keeps volumes 21:end-N, inclusive.
    expected = memoryview(raw_voxels)[(first_one_based - 1) * bytes_per_volume:
                                     last_one_based * bytes_per_volume]
    require(len(output) == len(expected), "array length")
    require(output == expected, "selected voxel bytes differ")


def original_memberships(tsv):
    require(sha(tsv) == "8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296",
            "original annotation TSV pin")
    with tsv.open(newline="") as stream:
        rows = list(csv.reader(stream, delimiter="\t"))[1:]
    require([int(r[0]) for r in rows] == list(range(1, 1001)), "original segment IDs")
    intervals = {int(r[0]): (int(r[3]), int(r[4])) for r in rows if r[3] and r[4]}
    absent = set(range(1, 1001)) - intervals.keys()
    require(absent == {481, 482}, "original unavailable segments")
    expected = set()
    for part, first, last, prefix in ((1, 21, 966, 0), (2, 21, 1050, 946)):
        for local_tr, raw_volume in enumerate(range(first, last + 1), start=1):
            tr = local_tr + prefix
            for segment, (a, b) in intervals.items():
                if a <= tr <= b:
                    expected.add((segment, part, tr, local_tr - 1, raw_volume - 1))
    coordinates = {int(r[0]): tuple(int(x) if x else None for x in r[1:5]) for r in rows}
    return expected, absent, coordinates


def check_memberships(actual, expected):
    require(len(actual) == len(set(actual)), "duplicate membership")
    require(set(actual) == expected, "membership mismatch")


def check_segments(rows, absent, coordinates, memberships):
    require([r["segment"] for r in rows] == list(range(1, 1001)), "segment outcome inventory")
    require({r["segment"] for r in rows if r["status"] == "no-fmri-assignment"} == absent,
            "unavailable segment outcomes")
    for r in rows:
        segment = r["segment"]
        seconds_start, seconds_end, first_tr, last_tr = coordinates[segment]
        require(r["annotation_seconds"] == [seconds_start, seconds_end] and
                r["part"] == (1 if segment <= 482 else 2), "segment coordinates")
        if r["segment"] in absent:
            require(r["selected_half_open"] is None and r["stored_half_open"] is None and
                    r["concatenated_tr_inclusive"] is None, "invented unavailable assignment")
        else:
            links = [m for m in memberships if m[0] == segment]
            selected = [m[3] for m in links]
            stored = [m[4] for m in links]
            require(r["concatenated_tr_inclusive"] == [first_tr, last_tr] and
                    r["selected_half_open"] == [min(selected), max(selected) + 1] and
                    r["stored_half_open"] == [min(stored), max(stored) + 1], "segment range projection")


def rejected(f):
    try:
        f()
    except ValueError:
        return 1
    raise AssertionError("corruption survived")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("dataset", "tsv", "output", "receipt"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    manifest = json.loads((args.output / "manifest.json").read_text())
    for item in manifest["files"]:
        p = args.output / item["file"]
        require(p.stat().st_size == item["bytes"] and sha(p) == item["sha256"], "artifact pin")
    require(manifest["physical_scanner_media_binding"] == "unestablished", "physical authority")
    arrays = []
    for part, count, last in ((1, 972, 966), (2, 1057, 1050)):
        task = f"sherlockPart{part}"
        selection = json.loads((args.output / f"{task}-selection.json").read_text())
        require(selection["stored_indices"] == list(range(20, last)), "original indices")
        require(selection["upstream_preprocessing"] == "unknown" and selection["censoring"] == "unknown",
                "unknown upstream history")
        require(selection["additional_hrf_shift_volumes"] == 0, "double HRF adjustment")
        image = args.dataset / f"sub-03/func/sub-03_task-{task}_bold.nii.gz"
        require(sha(image) == selection["source_image"]["sha256"], "raw source pin")
        raw = gzip.decompress(image.read_bytes())
        require(raw[:348] == (args.output / selection["original_header"]).read_bytes(), "original spatial header")
        frame_bytes = 64 * 64 * 27 * 2
        require(len(raw) == 352 + count * frame_bytes, "original image byte inventory")
        selected = (args.output / selection["selected_array"]).read_bytes()
        exact_slice(memoryview(raw)[352:], selected, frame_bytes, 21, last)
        arrays.append({"part": part, "stored_range_half_open": [20, last],
                       "volumes": last - 20, "bytes": len(selected),
                       "sha256": hashlib.sha256(selected).hexdigest(), "every_voxel_byte_equal": True})
        del selected, raw
    expected, absent, coordinates = original_memberships(args.tsv)
    with (args.output / "memberships.tsv").open(newline="") as stream:
        reader = csv.reader(stream, delimiter="\t")
        require(next(reader) == ["segment", "part", "concatenated_tr", "selected_index", "stored_index"],
                "membership columns")
        actual = [tuple(map(int, r)) for r in reader]
    check_memberships(actual, expected)
    rows = json.loads((args.output / "segments.json").read_text())
    check_segments(rows, absent, coordinates, expected)
    counts = Counter(x[2] for x in actual)
    require(len(actual) == 2065 and set(counts) == set(range(1, 1977)), "TR coverage")
    require(Counter(counts.values()) == {1: 1887, 2: 89}, "TR overlap accounting")
    shifted = list(actual)
    first = list(shifted[0]); first[4] += 1; shifted[0] = tuple(first)
    fake_rows = copy.deepcopy(rows); fake_rows[480]["status"] = "published-analysis-membership"
    wrong_range = copy.deepcopy(rows); wrong_range[0]["stored_half_open"][0] += 1
    faults = [lambda: check_memberships(shifted, expected),
              lambda: check_memberships(actual + actual[:1], expected),
              lambda: check_memberships(actual[1:], expected),
              lambda: check_segments(rows[:480] + rows[482:], absent, coordinates, expected),
              lambda: check_segments(fake_rows, absent, coordinates, expected),
              lambda: check_segments(wrong_range, absent, coordinates, expected)]
    # Distinct synthetic values catch off-by-one selection, reordering and byte damage.
    raw = bytes([10, 0, 20, 0, 30, 0, 40, 0])
    exact_slice(raw, bytes([20, 0, 30, 0]), 2, 2, 3)
    for damaged in (bytes([10, 0, 20, 0]), bytes([30, 0, 20, 0]), bytes([20, 0, 31, 0]), bytes([20, 0])):
        faults.append(lambda damaged=damaged: exact_slice(raw, damaged, 2, 2, 3))
    rejected_count = sum(rejected(f) for f in faults)
    result = {"arrays": arrays, "original_segment_outcomes": len(rows), "unassigned_segments": sorted(absent),
              "membership_pairs": len(actual), "selected_trs": len(counts), "overlapping_trs": 89,
              "max_memberships_per_tr": 2, "corruptions_rejected": rejected_count,
              "physical_synchronization": "unestablished", "prior_derivative_history": "unknown",
              "predictor_averaging_or_normalization": "not performed"}
    with args.receipt.open("x") as stream:
        json.dump(result, stream, indent=2); stream.write("\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
