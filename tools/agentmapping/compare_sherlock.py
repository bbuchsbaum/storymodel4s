#!/usr/bin/env python3
"""Compare legacy Sherlock v1 bundles with generic v2 recall bundles.

Usage:
    compare_sherlock.py LEGACY_DIR V2_DIR --receipt RECEIPT.json

The comparison is offline.  It validates every input with the repository's
bundle readers, pairs participants in the ``recall`` session, and joins rows
only when legacy ``index == v2 u == v2 row``.  It intentionally does not join
content-derived unit, source, or record identities: those legitimately changed
between formats.  A receipt is always written after a completed comparison;
differences make the command exit 1.  Refusals exit 2.
"""

import argparse
import collections
import hashlib
import json
import os
import sys

import read_bundle
import study_bundle


FIELDS = ("narrative", "referent")
EXTRAS = (("alternatives", "alts"), ("rows", "rows"), ("confidence", "conf"))
MAX_DIAGNOSTICS = 20


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_legacy_directory(directory):
    """Checked v1 bundles by participant, with their file hashes."""
    names = sorted(name for name in os.listdir(directory) if name.endswith(".bundle.json"))
    if not names:
        raise read_bundle.BundleError("no legacy JSON bundles in the directory")
    bundles, hashes = {}, {}
    for name in names:
        path = os.path.join(directory, name)
        bundle = read_bundle.load(path)
        if bundle["schema"] != read_bundle.SCHEMA:
            raise read_bundle.BundleError(f"{name} is not a legacy v1 bundle")
        indexes = [unit["index"] for unit in bundle["units"]]
        if not all(isinstance(index, int) and not isinstance(index, bool) for index in indexes):
            raise read_bundle.BundleError(f"{name} has a non-integer legacy index")
        if len(set(indexes)) != len(indexes):
            raise read_bundle.BundleError(f"{name} has a repeated legacy index")
        for unit in bundle["units"]:
            read_bundle.check_unit(bundle, unit)
        participant = bundle["provenance"]["participant"]
        if participant in bundles:
            raise read_bundle.BundleError(f"two legacy bundles for participant {participant!r}")
        bundles[participant] = bundle
        hashes[name] = sha256_file(path)
    return bundles, hashes


def load_v2_directory(directory):
    """Checked v2 recall bundles by participant, with their file hashes."""
    named = study_bundle.load_directory(directory)
    bundles, hashes = {}, {}
    for name, bundle in named.items():
        provenance = bundle["provenance"]
        if provenance["session"] != "recall":
            raise study_bundle.BundleError(f"{name} is not a v2 recall-session bundle")
        participant = provenance["participant"]
        if participant in bundles:
            raise study_bundle.BundleError(f"two v2 recall bundles for participant {participant!r}")
        bundles[participant] = bundle
        hashes[name] = sha256_file(os.path.join(directory, name))
    if not bundles:
        raise study_bundle.BundleError("no v2 bundles for session 'recall'")
    return bundles, hashes


def legacy_prediction(unit, field):
    """A v1 answer without pretending absent, null, and invalid are equal."""
    processing = unit["processing"]
    if processing["status"] != "answered":
        # The v1 processing label ``unparsable`` became v2's more explicit
        # reply failure ``unparsable-chunk``; other processing labels are
        # already the v2 failure reasons.
        reason = (
            "unparsable-chunk" if processing["status"] == "unparsable" else processing["status"]
        )
        return {"status": "failed", "reason": reason}
    value = unit[field]
    if value["status"] == "value":
        return {"status": "choice", "scene": value["value"]}
    if value["status"] == "null":
        return {"status": "null"}
    if value["status"] == "absent":
        # v1 represented an answered reply with no field as ``absent``.  v2
        # preserves the same event as the explicit parse failure missing-field.
        return {"status": "failed", "reason": "missing-field"}
    # Raw invalid replies can contain private text.  A digest distinguishes them
    # without placing it in diagnostics or receipts.
    return {
        "status": "invalid",
        "raw_sha256": hashlib.sha256(
            json.dumps(value.get("raw"), sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
    }


def v2_prediction(unit, field):
    stated = unit[field]["stated"]
    if stated["status"] == "failed":
        return {"status": "failed", "reason": stated["reason"]}
    if stated["kind"] == "scene":
        return {"status": "choice", "scene": stated["scene"]}
    if stated["kind"] == "none":
        return {"status": "null"}
    # v2's validator only admits scene, film, or none as answered non-time
    # replies.  Film answers are intentionally not equated with a scene choice.
    return {"status": "invalid", "kind": stated["kind"]}


def legacy_extra(unit, key):
    value = unit[key]
    if value["status"] == "value":
        return {"status": "value", "value": value["value"]}
    if value["status"] == "null":
        return {"status": "null"}
    if value["status"] == "absent":
        return {"status": "absent"}
    return {"status": "invalid"}


def v2_extra(unit, key):
    if key not in unit["extras"]:
        return {"status": "absent"}
    value = unit["extras"][key]
    return {"status": "null"} if value is None else {"status": "value", "value": value}


def record_digests(bundle):
    return {field: bundle["interpretations"][field]["recordDigest"] for field in FIELDS}


def add_difference(differences, participant, unit, field):
    differences.append({"participant": participant, "u": unit, "field": field})


def compare(legacy, v2, legacy_hashes=None, v2_hashes=None):
    """Return a machine-readable receipt; callers decide the exit status.

    Inputs are expected to have been checked by ``read_bundle.load`` and
    ``study_bundle.load``.  Keeping this pure makes the semantic join reusable
    in tests and in other offline tooling.
    """
    legacy_hashes = legacy_hashes or {}
    v2_hashes = v2_hashes or {}
    differences = []
    old_people, new_people = set(legacy), set(v2)
    if old_people != new_people:
        for participant in sorted(old_people ^ new_people):
            add_difference(differences, participant, None, "participant-set")
    counts = collections.Counter(participants=len(old_people), units=0)
    digest_receipts = {}
    for participant in sorted(old_people & new_people):
        old, new = legacy[participant], v2[participant]
        digest_receipts[participant] = {"old": record_digests(old), "new": record_digests(new)}
        old_units = {unit["index"]: unit for unit in old["units"]}
        new_units = {unit["u"]: unit for unit in new["units"]}
        if any(unit["row"] != unit["u"] for unit in new["units"]):
            add_difference(differences, participant, None, "v2-u-row-join")
        if set(old_units) != set(new_units):
            for number in sorted(set(old_units) ^ set(new_units)):
                add_difference(differences, participant, number, "unit-set")
        for number in sorted(set(old_units) & set(new_units)):
            old_unit, new_unit = old_units[number], new_units[number]
            if new_unit["row"] != number:
                add_difference(differences, participant, number, "v2-u-row-join")
                continue
            counts["units"] += 1
            for field in FIELDS:
                before, after = legacy_prediction(old_unit, field), v2_prediction(new_unit, field)
                counts[f"old:{field}:{before['status']}"] += 1
                counts[f"new:{field}:{after['status']}"] += 1
                if before != after:
                    add_difference(differences, participant, number, field)
            for old_key, new_key in EXTRAS:
                if legacy_extra(old_unit, old_key) != v2_extra(new_unit, new_key):
                    add_difference(differences, participant, number, f"extra:{new_key}")
    return {
        "schema": "storymodel4s.agentmapping.sherlock-comparison/v1",
        "participants": sorted(old_people),
        "counts": dict(sorted(counts.items())),
        "file_sha256": {"legacy": dict(sorted(legacy_hashes.items())), "v2": dict(sorted(v2_hashes.items()))},
        "record_digests": digest_receipts,
        "differences": {
            "count": len(differences),
            "diagnostics": differences[:MAX_DIAGNOSTICS],
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("legacy_dir")
    parser.add_argument("v2_dir")
    parser.add_argument("--receipt", required=True)
    args = parser.parse_args(argv)
    try:
        legacy, old_hashes = load_legacy_directory(args.legacy_dir)
        v2, new_hashes = load_v2_directory(args.v2_dir)
        receipt = compare(legacy, v2, old_hashes, new_hashes)
        with open(args.receipt, "x", encoding="utf-8") as handle:
            json.dump(receipt, handle, indent=2, sort_keys=True)
            handle.write("\n")
    except (read_bundle.BundleError, study_bundle.BundleError, OSError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    print(json.dumps({"units": receipt["counts"]["units"], "differences": receipt["differences"]["count"]}))
    return 1 if receipt["differences"]["count"] else 0


if __name__ == "__main__":
    sys.exit(main())
