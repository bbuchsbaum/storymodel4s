#!/usr/bin/env python3
"""Reproduce source03 raw-volume selection and published segment/TR membership.

This bounded evidence producer accepts only the already pinned release/workbook.
It exports original int16 voxel bytes, not a preprocessed predictor matrix.
"""
import argparse
import csv
from fractions import Fraction
import gzip
import hashlib
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile


WORKBOOK = "5271b7704ccebfc7cae2228c78faf472c0ba5530c1a45e4c784757057bf476df"
REVISION = "fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def save(path, value):
    with path.open("xb") as stream:
        stream.write(canonical(value))


def rational(value):
    value = Fraction(value)
    return {"numerator": str(value.numerator), "denominator": str(value.denominator)}


def annotation_rows(workbook):
    require(digest(workbook) == WORKBOOK, "workbook identity")
    ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    with zipfile.ZipFile(workbook) as archive:
        sheet = ET.fromstring(archive.read("xl/worksheets/sheet1.xml"))
    rows = []
    for row in sheet.findall("s:sheetData/s:row", ns):
        cells = {}
        for cell in row.findall("s:c", ns):
            col = cell.attrib["r"].rstrip("0123456789")
            if col in ("A", "B", "C", "D", "E") and cell.attrib.get("t", "n") == "n":
                text = cell.findtext("s:v", namespaces=ns)
                if text:
                    number = Fraction(text)
                    require(number.denominator == 1, "nonintegral worksheet coordinate")
                    cells[col] = int(number)
        segment = cells.get("A")
        if segment is None or not 1 <= segment <= 1000:
            continue
        part = 1 if segment <= 482 else 2
        prefix, length = (0, 946) if part == 1 else (946, 1030)
        record = {"segment": segment, "part": part,
                  "annotation_seconds": [cells["B"], cells["C"]]}
        if "D" not in cells or "E" not in cells:
            require(segment in (481, 482) and "D" not in cells and "E" not in cells,
                    "unexpected missing TR coordinates")
            record.update(status="no-fmri-assignment", concatenated_tr_inclusive=None,
                          selected_half_open=None, stored_half_open=None)
        else:
            first, last = cells["D"], cells["E"]
            require(prefix < first <= last <= prefix + length, "TR interval crosses run")
            start, end = first - prefix - 1, last - prefix
            record.update(status="published-analysis-membership",
                          concatenated_tr_inclusive=[first, last],
                          selected_half_open=[start, end], stored_half_open=[start + 20, end + 20])
        rows.append(record)
    require([r["segment"] for r in rows] == list(range(1, 1001)), "segment inventory")
    return rows


def select_volumes(dataset, verification, run, output):
    task = run["task"]
    relative = f"sub-03/func/sub-03_task-{task}_bold.nii.gz"
    pin = next(x for x in verification["files"] if x["file"] == relative)
    source = dataset / relative
    require(source.stat().st_size == pin["bytes"] and digest(source) == pin["sha256"],
            "raw image identity")
    count = run["storedVolumes"]
    expected_count, retained = (972, 946) if task == "sherlockPart1" else (1057, 1030)
    require(count == expected_count and run["trSeconds"] == "3/2", "run inventory")
    crop = run["releaseCropPrescription"]
    require(crop["beginningVolumes"] == 20 and crop["resultLength"] == retained and
            crop["endVolumes"] == count - 20 - retained, "published crop")
    name = f"{task}-selected.i16le"
    frame_bytes = 64 * 64 * 27 * 2
    with gzip.open(source, "rb") as stream:
        header = stream.read(348)
        require(hashlib.sha256(header).hexdigest() == run["header348Sha256"], "header identity")
        require(struct.unpack_from("<i", header)[0] == 348 and header[344:348] == b"n+1\0",
                "NIfTI format or byte order")
        require(struct.unpack_from("<8h", header, 40) == (4, 64, 64, 27, count, 1, 1, 1),
                "NIfTI dimensions")
        require(struct.unpack_from("<2h", header, 70) == (4, 16), "NIfTI datatype")
        require(struct.unpack_from("<f", header, 108)[0] == 352.0, "NIfTI offset")
        require(struct.unpack_from("<2f", header, 112) == (1.0, 0.0), "NIfTI scaling")
        require(struct.unpack_from("<f", header, 92)[0] == 1.5 and header[123] & 0x38 == 8,
                "NIfTI temporal units")
        require(len(stream.read(4)) == 4, "NIfTI extension flags")
        with (output / name).open("xb") as target:
            for index in range(count):
                frame = stream.read(frame_bytes)
                require(len(frame) == frame_bytes, "truncated voxel payload")
                if 20 <= index < 20 + retained:
                    target.write(frame)
        require(stream.read(1) == b"", "unexpected voxel payload tail")
    header_name = f"{task}-original-header.bin"
    with (output / header_name).open("xb") as stream:
        stream.write(header)
    array_hash = digest(output / name)
    receipt = {
        "source_image": pin, "source_header_sha256": run["header348Sha256"],
        "original_header": header_name, "selected_array": name,
        "array_sha256": array_hash, "array_bytes": retained * frame_bytes,
        "shape_time_voxel": [retained, 64 * 64 * 27], "dtype": "little-endian-int16",
        "spatial_dimensions": [64, 64, 27], "voxel_index": "x + 64*(y + 64*z)",
        "spatial_reference": "original NIfTI header; no resampling or affine modification",
        "scaling": {"slope": 1, "intercept": 0},
        "stored_indices": list(range(20, 20 + retained)),
        "new_processing": ["copy selected stored-volume bytes in original order"],
        "upstream_preprocessing": "unknown", "censoring": "unknown",
        "additional_hrf_shift_volumes": 0, "physical_scanner_media_binding": "unestablished",
    }
    save(output / f"{task}-selection.json", receipt)
    receipt_hash = hashlib.sha256(canonical(receipt)).hexdigest()
    job = {
        "schemaVersion": "scanner-samples-job/v0.1",
        "run": {
            "key": {"dataset": "ds001132", "revision": REVISION, "participant": "sub-03",
                    "session": {"status": "absent"}, "task": task, "run": "1"},
            "image_sha256": pin["sha256"], "header_sha256": run["header348Sha256"],
            "sample_count": count, "sample_seconds": [rational(Fraction(3 * i, 2)) for i in range(count)],
            "origin": {"status": "first-stored-sample"}, "applied_history": {"status": "unknown"}},
        "layout": {"analysis_array_sha256": array_hash, "receipt": receipt_hash, "length": retained,
                   "slots": [{"status": "acquired", "stored_index": i, "censoring": {"status": "unknown"}}
                             for i in receipt["stored_indices"]]}}
    save(output / f"{task}-scanner-job.json", job)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("dataset", "workbook", "verification", "crosswalk", "output"):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    verification = json.loads(args.verification.read_text())
    crosswalk = json.loads(args.crosswalk.read_text())
    require(verification["dataset"] == "ds001132" and verification["revision"] == REVISION and
            verification["participant"] == "sub-03", "admitted recording")
    require(digest(args.verification) == crosswalk["verification_sha256"], "crosswalk input pin")
    rows = annotation_rows(args.workbook)
    args.output.mkdir(parents=True, exist_ok=False)
    receipts = [select_volumes(args.dataset, verification, run, args.output)
                for run in verification["runs"] if run["task"] in ("sherlockPart1", "sherlockPart2")]
    require(len(receipts) == 2, "movie run inventory")
    save(args.output / "segments.json", rows)
    with (args.output / "memberships.tsv").open("x", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(["segment", "part", "concatenated_tr", "selected_index", "stored_index"])
        for row in rows:
            if row["selected_half_open"] is not None:
                prefix = 0 if row["part"] == 1 else 946
                for index in range(*row["selected_half_open"]):
                    writer.writerow([row["segment"], row["part"], index + prefix + 1, index, index + 20])
    manifest = {
        "qualification": "new raw-array selection and published annotation-index membership only",
        "workbook_sha256": WORKBOOK, "verification_sha256": digest(args.verification),
        "crosswalk_sha256": digest(args.crosswalk), "producer_sha256": digest(Path(__file__)),
        "physical_scanner_media_binding": "unestablished", "existing_derivative_history": "unknown",
        "predictor_averaging": "not applied", "feature_normalization": "not applied",
        "segment_count": len(rows), "unassigned_segments": [481, 482],
        "files": [{"file": p.name, "bytes": p.stat().st_size, "sha256": digest(p)}
                  for p in sorted(args.output.iterdir())]}
    save(args.output / "manifest.json", manifest)
    print(json.dumps({"segments": len(rows), "selected_volumes": [r["shape_time_voxel"][0] for r in receipts],
                      "unassigned_segments": [481, 482], "physical_synchronization": "unestablished"}))


if __name__ == "__main__":
    main()
