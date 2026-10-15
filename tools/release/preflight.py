#!/usr/bin/env python3
"""Nonpublishing Maven publication preflight for an explicit candidate POM matrix."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any


MATRIX_SCHEMA = "storymodel4s-release-candidate-matrix/v1"
RECEIPT_SCHEMA = "storymodel4s-release-preflight/v1"
CENTRAL = "https://repo.maven.apache.org/maven2"
DYNAMIC = re.compile(r"(?i)(snapshot|latest|release|[\[\](){}*+])")


class PreflightError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(node: ET.Element, name: str) -> ET.Element | None:
    return next((element for element in node if local(element.tag) == name), None)


def text(node: ET.Element | None, name: str, props: dict[str, str] | None = None) -> str | None:
    value = child(node, name) if node is not None else None
    if value is None or value.text is None:
        return None
    result = value.text.strip()
    if props:
        for key, replacement in props.items():
            result = result.replace("${" + key + "}", replacement)
    return result


def required(node: ET.Element | None, name: str, props: dict[str, str] | None = None) -> str:
    value = text(node, name, props)
    if not value:
        raise PreflightError(f"missing required POM field {name}")
    return value


@dataclass(frozen=True, order=True)
class Coordinate:
    group_id: str
    artifact_id: str
    version: str
    packaging: str

    @property
    def gav(self) -> str:
        return f"{self.group_id}:{self.artifact_id}:{self.version}"

    @property
    def key(self) -> tuple[str, str, str, str]:
        return (self.group_id, self.artifact_id, self.version, self.packaging)


@dataclass
class Pom:
    path: Path
    coordinate: Coordinate
    dependencies: list[Coordinate]
    sha256: str


def reject_dynamic(value: str, context: str) -> None:
    if not value or DYNAMIC.search(value):
        raise PreflightError(f"non-fixed version in {context}: {value!r}")


def parse_pom(path: Path) -> Pom:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise PreflightError(f"cannot parse {path}: {error}") from error
    if local(root.tag) != "project":
        raise PreflightError(f"{path}: root is not a Maven project")
    props: dict[str, str] = {}
    properties = child(root, "properties")
    if properties is not None:
        props = {local(element.tag): (element.text or "").strip() for element in properties}
    parent = child(root, "parent")
    group_id = text(root, "groupId", props) or text(parent, "groupId", props)
    version = text(root, "version", props) or text(parent, "version", props)
    if not group_id or not version:
        raise PreflightError(f"{path}: groupId and version must be explicit or inherited from parent")
    coordinate = Coordinate(group_id, required(root, "artifactId", props), version, text(root, "packaging", props) or "jar")
    reject_dynamic(coordinate.version, f"{path} project version")

    for field in ("modelVersion", "name", "description", "url"):
        required(root, field, props)
    licenses = child(root, "licenses")
    license_nodes = [] if licenses is None else [entry for entry in licenses if local(entry.tag) == "license"]
    if not license_nodes:
        raise PreflightError(f"{path}: missing license metadata")
    for license_node in license_nodes:
        required(license_node, "name", props)
        required(license_node, "url", props)
    developers = child(root, "developers")
    developer_nodes = [] if developers is None else [entry for entry in developers if local(entry.tag) == "developer"]
    if not developer_nodes:
        raise PreflightError(f"{path}: missing developer metadata")
    for developer in developer_nodes:
        required(developer, "id", props)
        required(developer, "name", props)
    scm = child(root, "scm")
    # Generated project POMs carry the Central-relevant SCM URL and connection, but deliberately
    # omit developerConnection and tag; do not invent stricter metadata than the candidate emits.
    for field in ("url", "connection"):
        required(scm, field, props)

    dependencies: list[Coordinate] = []
    dependency_root = child(root, "dependencies")
    if dependency_root is not None:
        for dependency in dependency_root:
            if local(dependency.tag) != "dependency":
                continue
            dep = Coordinate(
                required(dependency, "groupId", props),
                required(dependency, "artifactId", props),
                required(dependency, "version", props),
                text(dependency, "type", props) or "jar",
            )
            reject_dynamic(dep.version, f"{path} dependency {dep.group_id}:{dep.artifact_id}")
            dependencies.append(dep)
    return Pom(path, coordinate, dependencies, sha256(path))


def pom_paths(inputs: list[Path]) -> list[Path]:
    result: set[Path] = set()
    for source in inputs:
        if source.is_file():
            result.add(source.resolve())
        elif source.is_dir():
            result.update(path.resolve() for path in source.rglob("*.pom"))
            result.update(path.resolve() for path in source.rglob("pom.xml"))
        else:
            raise PreflightError(f"candidate POM input does not exist: {source}")
    if not result:
        raise PreflightError("no candidate POMs found")
    return sorted(result)


def load_matrix(path: Path) -> list[Coordinate]:
    try:
        document = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise PreflightError(f"cannot read expected matrix {path}: {error}") from error
    if document.get("schema") != MATRIX_SCHEMA or not isinstance(document.get("coordinates"), list):
        raise PreflightError(f"{path}: expected {MATRIX_SCHEMA} with coordinates array")
    coordinates = []
    for entry in document["coordinates"]:
        if not isinstance(entry, dict):
            raise PreflightError(f"{path}: matrix coordinate is not an object")
        try:
            coordinate = Coordinate(entry["groupId"], entry["artifactId"], entry["version"], entry["packaging"])
        except KeyError as error:
            raise PreflightError(f"{path}: missing matrix field {error.args[0]}") from error
        if not all(isinstance(value, str) and value for value in coordinate.key):
            raise PreflightError(f"{path}: matrix coordinates must be nonempty strings")
        reject_dynamic(coordinate.version, f"{path} matrix {coordinate.group_id}:{coordinate.artifact_id}")
        coordinates.append(coordinate)
    if len(set(coordinates)) != len(coordinates):
        raise PreflightError(f"{path}: duplicate expected coordinate")
    return sorted(coordinates)


def require_matrix(actual: list[Pom], expected: list[Coordinate], label: str) -> None:
    if len({pom.coordinate for pom in actual}) != len(actual):
        raise PreflightError(f"{label} has duplicate candidate POM coordinate")
    actual_set = {pom.coordinate for pom in actual}
    expected_set = set(expected)
    missing = sorted(expected_set - actual_set)
    extra = sorted(actual_set - expected_set)
    if missing or extra:
        detail = []
        if missing:
            detail.append("missing=" + ",".join(coordinate.gav for coordinate in missing))
        if extra:
            detail.append("unexpected=" + ",".join(coordinate.gav for coordinate in extra))
        raise PreflightError(f"{label} coordinate matrix mismatch: {'; '.join(detail)}")


def baseline_required(version: str) -> bool:
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not match:
        raise PreflightError(f"release version must be fixed semver for baseline policy: {version}")
    major, minor, patch = (int(value) for value in match.groups())
    if major != 1:
        raise PreflightError(f"baseline policy only admits 1.x releases, got {version}")
    return (minor, patch) != (0, 0)


def check_baseline(candidates: list[Pom], baseline_inputs: list[Path]) -> dict[str, Any]:
    versions = {pom.coordinate.version for pom in candidates}
    if len(versions) != 1:
        raise PreflightError("candidate coordinates must share one release version")
    version = next(iter(versions))
    need_baseline = baseline_required(version)
    if not baseline_inputs:
        if need_baseline:
            raise PreflightError(f"{version} requires an exact 1.0.0 baseline POM matrix")
        return {"candidateVersion": version, "required": False, "baseline": []}
    baseline = [parse_pom(path) for path in pom_paths(baseline_inputs)]
    if any(pom.coordinate.version != "1.0.0" for pom in baseline):
        raise PreflightError("baseline POMs must all be exactly version 1.0.0")
    expected = [Coordinate(pom.coordinate.group_id, pom.coordinate.artifact_id, "1.0.0", pom.coordinate.packaging) for pom in candidates]
    require_matrix(baseline, expected, "baseline")
    return {
        "candidateVersion": version,
        "required": need_baseline,
        "baseline": [
            {"path": str(pom.path), "sha256": pom.sha256, "coordinate": pom.coordinate.gav}
            for pom in baseline
        ],
    }


def internal_closure(candidates: list[Pom]) -> list[str]:
    candidate_by_ga = {(pom.coordinate.group_id, pom.coordinate.artifact_id): pom.coordinate for pom in candidates}
    internal_groups = {pom.coordinate.group_id for pom in candidates}
    failures: list[str] = []
    for pom in candidates:
        for dependency in pom.dependencies:
            key = (dependency.group_id, dependency.artifact_id)
            if dependency.group_id in internal_groups:
                target = candidate_by_ga.get(key)
                if target is None or target.version != dependency.version or target.packaging != dependency.packaging:
                    failures.append(f"{pom.coordinate.gav} -> missing internal {dependency.gav}:{dependency.packaging}")
    return sorted(failures)


def command_receipt(command: list[str], cache: Path, config: Path, log: Path, timeout: int) -> dict[str, Any]:
    home = config / "home"
    home.mkdir(parents=True, exist_ok=True)
    environment = {
        "PATH": os.environ.get("PATH", ""), "HOME": str(home), "XDG_CONFIG_HOME": str(config),
        "COURSIER_CACHE": str(cache), "COURSIER_CONFIG_DIR": str(config / "coursier"), "COURSIER_CREDENTIALS": "",
    }
    receipt: dict[str, Any] = {"command": command, "log": str(log)}
    try:
        completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=environment, timeout=timeout)
        receipt["exit"] = completed.returncode
        log.write_text(completed.stdout)
    except subprocess.TimeoutExpired as error:
        receipt["exit"] = None
        receipt["timeoutSeconds"] = timeout
        output = error.stdout or ""
        log.write_text(output.decode() if isinstance(output, bytes) else output)
    except OSError as error:
        receipt["exit"] = None
        receipt["spawnError"] = str(error)
        log.write_text(str(error) + "\n")
    receipt["logSha256"] = sha256(log)
    return receipt


def report_jars(report: Path, cache: Path, repositories: list[str], public: bool) -> list[dict[str, str]]:
    try:
        document = json.loads(report.read_text())
    except (OSError, json.JSONDecodeError) as error:
        raise PreflightError(f"missing or malformed Coursier JSON report {report}: {error}") from error
    if not isinstance(document, dict):
        raise PreflightError(f"Coursier JSON report must be an object: {report}")
    dependencies = document.get("dependencies")
    if not isinstance(dependencies, list) or not dependencies:
        raise PreflightError(f"empty Coursier dependency report {report}")
    jars = []
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            raise PreflightError(f"malformed dependency entry in {report}")
        coord, file_name, url = dependency.get("coord"), dependency.get("file"), dependency.get("url")
        if not all(isinstance(value, str) and value for value in (coord, file_name, url)):
            raise PreflightError(f"dependency entry lacks coord, file, or url in {report}")
        reject_dynamic(coord.rsplit(":", 1)[-1], f"Coursier resolved {coord}")
        path = Path(file_name).resolve()
        if not path.is_file() or not path.is_relative_to(cache.resolve()):
            raise PreflightError(f"Coursier dependency JAR is absent or outside isolated cache: {path}")
        if public and not url.startswith(CENTRAL + "/"):
            raise PreflightError(f"public Coursier dependency did not originate at Maven Central: {url}")
        if not public and not any(url.startswith(repository.rstrip("/") + "/") for repository in repositories):
            raise PreflightError(f"staging Coursier dependency came from an unrequested repository: {url}")
        jars.append({"coordinate": coord, "path": str(path), "url": url, "sha256": sha256(path)})
    return sorted(jars, key=lambda jar: (jar["coordinate"], jar["path"]))


def external_closure(coursier: Path, candidates: list[Pom], work: Path, staging: str | None, timeout: int) -> tuple[list[dict[str, Any]], list[str]]:
    external = sorted({dependency.gav for pom in candidates for dependency in pom.dependencies if dependency.group_id != pom.coordinate.group_id})
    receipts: list[dict[str, Any]] = []
    failures: list[str] = []
    modes = [("maven-central", [CENTRAL], external)]
    if staging:
        modes.append(("staging-rehearsal", [staging, CENTRAL], sorted(pom.coordinate.gav for pom in candidates)))
    for mode, repositories, coordinates in modes:
        cache = work / f"coursier-cache-{mode}"
        cache.mkdir()
        for index, coordinate in enumerate(coordinates):
            log = work / f"coursier-{mode}-{index:04d}.log"
            report = work / f"coursier-{mode}-{index:04d}.json"
            command = [str(coursier), "fetch", "--no-default", "--json-output-file", str(report), "--json-report-add-urls"]
            for repository in repositories:
                command += ["--repository", repository]
            command.append(coordinate)
            receipt = command_receipt(command, cache, work / f"coursier-config-{mode}", log, timeout)
            receipt["mode"] = mode
            receipt["coordinate"] = coordinate
            receipt["report"] = str(report)
            if receipt["exit"] != 0:
                failures.append(f"coursier {mode} failed for {coordinate}")
                receipt["status"] = "failed"
            else:
                try:
                    receipt["jars"] = report_jars(report, cache, repositories, mode == "maven-central")
                    receipt["reportSha256"] = sha256(report)
                    roots = [jar for jar in receipt["jars"] if jar["coordinate"] == coordinate]
                    if not roots:
                        raise PreflightError(f"Coursier report omits requested coordinate {coordinate}")
                    if mode == "staging-rehearsal" and any(not jar["url"].startswith(staging.rstrip("/") + "/") for jar in roots):
                        raise PreflightError(f"candidate coordinate did not originate at requested staging repository: {coordinate}")
                    receipt["status"] = "passed"
                except PreflightError as error:
                    failures.append(str(error))
                    receipt["status"] = "failed"
            receipts.append(receipt)
    return receipts, failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", type=Path, required=True, help="exact candidate coordinate matrix JSON")
    parser.add_argument("--coursier", type=Path, required=True, help="supplied coursier executable")
    parser.add_argument("--output", type=Path, required=True, help="receipt JSON path")
    parser.add_argument("--baseline", type=Path, action="append", default=[], help="published 1.0.0 baseline POM path or directory")
    parser.add_argument("--staging-repository", help="optional explicit staging repository rehearsal")
    parser.add_argument("--timeout-seconds", type=int, default=120, help="per-Coursier command timeout")
    parser.add_argument("candidate", type=Path, nargs="+", help="candidate POM paths or directories")
    args = parser.parse_args(argv)

    work = Path(tempfile.mkdtemp(prefix="storymodel4s-release-preflight-"))
    receipt: dict[str, Any] = {"schema": RECEIPT_SCHEMA, "qualified": "not-qualified", "work": str(work), "commands": []}
    try:
        if not args.coursier.is_file() or not os.access(args.coursier, os.X_OK):
            raise PreflightError(f"coursier executable is not runnable: {args.coursier}")
        if args.timeout_seconds <= 0:
            raise PreflightError("timeout must be positive")
        expected = load_matrix(args.expected)
        candidates = [parse_pom(path) for path in pom_paths(args.candidate)]
        require_matrix(candidates, expected, "candidate")
        receipt["candidatePoms"] = [{"path": str(pom.path), "sha256": pom.sha256, "coordinate": pom.coordinate.gav} for pom in candidates]
        receipt["baseline"] = check_baseline(candidates, args.baseline)
        internal_failures = internal_closure(candidates)
        if internal_failures:
            raise PreflightError(internal_failures[0])
        receipt["coursier"] = {"path": str(args.coursier.resolve()), "sha256": sha256(args.coursier)}
        commands, external_failures = external_closure(args.coursier, candidates, work, args.staging_repository, args.timeout_seconds)
        receipt["commands"] = commands
        receipt["publicDependencyQualification"] = "passed" if all(command["status"] == "passed" for command in commands if command["mode"] == "maven-central") else "failed"
        receipt["stagingRepositoryRehearsal"] = (
            "not-requested" if not args.staging_repository else
            "passed" if all(command["status"] == "passed" for command in commands if command["mode"] == "staging-rehearsal") else "failed"
        )
        if external_failures:
            raise PreflightError(external_failures[0])
        receipt["qualified"] = "public-dependency-qualified"
    except PreflightError as error:
        receipt["error"] = str(error)
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    if receipt["qualified"] != "public-dependency-qualified":
        print(receipt["error"], file=sys.stderr)
        return 1
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
