#!/usr/bin/env python3
"""Validate the explicitly supported part of a stability-audit inventory."""

import json
import sys
from pathlib import Path

SCHEMA = "storymodel4s-stable-api-disposition/v1"
DISPOSITIONS = {"stable", "experimental"}


def fail(message: str) -> None:
    raise SystemExit(f"supported-closure: {message}")


def object_of(value: object, name: str) -> dict[str, str]:
    if not isinstance(value, dict):
        fail(f"{name} must be an object mapping exact inventory identities to dispositions")
    if any(not isinstance(key, str) or not isinstance(disposition, str) for key, disposition in value.items()):
        fail(f"{name} must map strings to strings")
    unknown = sorted(set(value.values()) - DISPOSITIONS)
    if unknown:
        fail(f"{name} has unsupported dispositions: {', '.join(unknown)}")
    return value


def main(inventory_path: str, manifest_path: str) -> None:
    inventory = json.loads(Path(inventory_path).read_text())
    manifest = json.loads(Path(manifest_path).read_text())
    if manifest.get("schema") != SCHEMA:
        fail(f"expected schema {SCHEMA!r}")

    nominals = object_of(manifest.get("nominals"), "nominals")
    member_dispositions = object_of(manifest.get("members"), "members")
    inventory_nominals = set(inventory.get("nominals", []))
    unresolved = inventory.get("unresolved")
    if not isinstance(unresolved, list) or unresolved:
        fail("inventory has unresolved symbols")
    inventory_members = inventory.get("members", [])
    if not all(isinstance(member, dict) for member in inventory_members):
        fail("inventory members must be objects")
    member_ids = {member.get("id") for member in inventory_members}
    if None in member_ids or not all(isinstance(member_id, str) for member_id in member_ids):
        fail("inventory members need exact string ids")

    unknown_nominals = sorted(set(nominals) - inventory_nominals)
    if unknown_nominals:
        fail("manifest nominal is absent from inventory: " + unknown_nominals[0])
    unknown_members = sorted(set(member_dispositions) - member_ids)
    if unknown_members:
        fail("manifest member is absent from inventory: " + unknown_members[0])

    stable_nominals = {identity for identity, disposition in nominals.items() if disposition == "stable"}
    stable_members = {identity for identity, disposition in member_dispositions.items() if disposition == "stable"}

    # A stable nominal with selective member support has no safe default: every public/protected
    # exposure reported by TASTy must be classified explicitly.
    exposed_by_stable = set()
    for member in inventory_members:
        owners = member.get("exposedBy")
        if not isinstance(owners, list) or not all(isinstance(owner, str) for owner in owners):
            fail("inventory members need exposedBy string arrays")
        if stable_nominals.intersection(owners):
            exposed_by_stable.add(member["id"])
    unclassified = sorted(exposed_by_stable - set(member_dispositions))
    if unclassified:
        fail("stable nominal exposes member without disposition: " + unclassified[0])

    # Edges are already restricted by the TASTy walker to internal `storymodel4s` type symbols.
    # External Scala/JDK/Cats/Circe symbols remain terminal `external` records and are deliberately
    # outside this contract. A member-exposure or companion-factory link is a classification
    # boundary, not a nominal type dependency. All other edges from a stable nominal, including
    # parents, must remain inside the stable nominal closure. A singleton enum alternative is a
    # term member rather than a nominal; its own typed edges still require nominal closure.
    classification_kinds = {"member-exposure", "companion-factory"}
    dependencies: dict[str, set[str]] = {}
    for edge in inventory.get("edges", []):
        if not isinstance(edge, dict):
            fail("inventory edges must be objects")
        source, target = edge.get("from"), edge.get("to")
        kind = edge.get("kind")
        if not isinstance(source, str) or not isinstance(target, str) or not isinstance(kind, str):
            fail("inventory edges need string from, to, and kind")
        if kind == "public-alternative" and source in stable_nominals and target in member_ids:
            if target not in stable_members:
                fail(f"stable nominal alternative is not a stable member: {source} -> {target}")
            continue
        if kind not in classification_kinds:
            if source in stable_nominals:
                dependencies.setdefault(source, set()).add(target)
            if source in stable_members:
                dependencies.setdefault(source, set()).add(target)
    for nominal in sorted(stable_nominals):
        missing = sorted(dependencies.get(nominal, set()) - stable_nominals)
        if missing:
            fail(f"stable nominal dependency is not a stable nominal: {nominal} -> {missing[0]}")
    for member in sorted(stable_members):
        missing = sorted(dependencies.get(member, set()) - stable_nominals)
        if missing:
            fail(f"stable member dependency is not a stable nominal: {member} -> {missing[0]}")

    print(
        "supported-closure: ok "
        f"stableNominals={len(stable_nominals)} stableMembers={len(stable_members)} "
        f"classifiedMembers={len(member_dispositions)}"
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: validate-supported-closure.py INVENTORY.json DISPOSITIONS.json")
    main(*sys.argv[1:])
