#!/usr/bin/env python3
"""Controls for exact member/nominal supported-closure dispositions."""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

inventory_path, validator = map(Path, sys.argv[1:3])
inventory = json.loads(inventory_path.read_text())
members = inventory["members"]
nominals = set(inventory["nominals"])


def exact_nominal(prefix: str) -> str:
    return next(value for value in nominals if value.startswith(prefix + " ::"))


def member_ids(prefix: str) -> list[str]:
    return sorted({member["id"] for member in members if member["id"].startswith(prefix + " ::")})


def members_exposed_by(nominal: str) -> list[str]:
    return sorted({member["id"] for member in members if nominal in member["exposedBy"]})


def run(manifest: dict, expected: bool, message: str | None = None) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
        json.dump(manifest, handle)
        path = Path(handle.name)
    try:
        result = subprocess.run(
            [sys.executable, str(validator), str(inventory_path), str(path)],
            capture_output=True,
            text=True,
        )
        if (result.returncode == 0) != expected:
            raise SystemExit(result.stdout + result.stderr)
        if message and message not in result.stdout + result.stderr:
            raise SystemExit(result.stdout + result.stderr)
    finally:
        path.unlink(missing_ok=True)


def manifest(nominal_dispositions: dict[str, str], member_dispositions: dict[str, str]) -> dict:
    return {
        "schema": "storymodel4s-stable-api-disposition/v1",
        "nominals": nominal_dispositions,
        "members": member_dispositions,
    }


factory_exposure = exact_nominal("storymodel4s.stabilityfixture.FactoryExposure")
stable_accessor_type = exact_nominal("storymodel4s.stabilityfixture.StableAccessorType")
factory_member = member_ids("storymodel4s.stabilityfixture.FactoryExposure.experimentalFactory")[0]
accessor_member = member_ids("storymodel4s.stabilityfixture.FactoryExposure.stableAccessor")[0]
factory_members = {member: "experimental" for member in members_exposed_by(factory_exposure)}
factory_members.update({member: "experimental" for member in members_exposed_by(stable_accessor_type)})
factory_members[accessor_member] = "stable"

# A factory-only parameter can be experimental while the same nominal has a stable accessor.
accepted = manifest(
    {factory_exposure: "stable", stable_accessor_type: "stable"},
    factory_members,
)
run(accepted, expected=True)
if len(sys.argv) == 4:
    Path(sys.argv[3]).write_text(json.dumps(accepted, indent=2) + "\n")

# Marking that factory stable requires its parameter's nominal disposition.
factory_as_stable = manifest(
    {factory_exposure: "stable", stable_accessor_type: "stable"},
    {**factory_members, factory_member: "stable"},
)
run(factory_as_stable, expected=False)

# Removing the neighboring stable accessor is an unknown public member of a partially stable type.
missing_accessor = manifest(
    {factory_exposure: "stable", stable_accessor_type: "stable"},
    {member: disposition for member, disposition in factory_members.items() if member != accessor_member},
)
run(missing_accessor, expected=False)

# The two overloads have distinct exact identities. One may be stable while its neighbour remains
# experimental, provided the chosen overload's result nominal is stable.
bound_only = exact_nominal("storymodel4s.stabilityfixture.BoundOnly")
upper = exact_nominal("storymodel4s.stabilityfixture.Upper")
overloads = member_ids("storymodel4s.stabilityfixture.FixtureFacade.overload")
if len(overloads) != 2 or overloads[0] == overloads[1]:
    raise SystemExit("fixture did not retain two exact overload identities")
stable_overload = next(
    member for member in overloads if any(edge["from"] == member and edge["to"] == bound_only for edge in inventory["edges"])
)
run(
    manifest(
        {bound_only: "stable", upper: "stable"},
        {
            **{member: "experimental" for owner in [bound_only, upper] for member in members_exposed_by(owner)},
            **{overload: "stable" if overload == stable_overload else "experimental" for overload in overloads},
        },
    ),
    expected=True,
)

# A stable nominal cannot extend an experimental parent.
parent_root = exact_nominal("storymodel4s.stabilityfixture.ParentRoot")
parent_only = exact_nominal("storymodel4s.stabilityfixture.ParentOnly")
run(
    manifest(
        {parent_root: "stable", parent_only: "experimental"},
        {member: "experimental" for member in members_exposed_by(parent_root)},
    ),
    expected=False,
    message="stable nominal dependency is not a stable nominal",
)

# Close the actual compiled enum graph, including its singleton term alternative. This positive
# control must pass before selectively withholding the payload or singleton can count as a kill.
public_enum = exact_nominal("storymodel4s.stabilityfixture.PublicEnum")
enum_payload = exact_nominal("storymodel4s.stabilityfixture.EnumPayloadOnly")
selected = {public_enum}
while True:
    expanded = selected | {
        edge["to"] for edge in inventory["edges"] if edge["from"] in selected
    }
    if expanded == selected:
        break
    selected = expanded
enum_nominals = {nominal: "stable" for nominal in nominals.intersection(selected)}
enum_members = {member["id"]: "stable" for member in members if member["id"] in selected}
run(manifest(enum_nominals, enum_members), expected=True)
run(
    manifest({**enum_nominals, enum_payload: "experimental"}, enum_members),
    expected=False,
    message="stable member dependency is not a stable nominal",
)
singleton = next(
    edge["to"] for edge in inventory["edges"]
    if edge["from"] == public_enum and edge["kind"] == "public-alternative"
    and edge["to"] in enum_members
)
run(
    manifest(enum_nominals, {**enum_members, singleton: "experimental"}),
    expected=False,
    message="stable nominal alternative is not a stable member",
)
print("supported-closure controls: 8 passed")
