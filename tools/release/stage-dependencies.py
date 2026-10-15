#!/usr/bin/env python3
"""Prepare exact upstream source clones and optionally test/stage them in a local Maven repo.

This command never publishes remotely. The checked source revisions are unchanged; a recorded
build overlay assigns proposed coordinates and local output/routing for the release rehearsal.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from preflight import internal_closure, load_matrix, parse_pom, pom_paths, require_matrix


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("dependency-candidates.json"))
    parser.add_argument("--output", type=Path, required=True, help="new directory; existing paths are refused")
    parser.add_argument("--source", action="append", default=[], help="optional NAME=/local/git/repo with the pinned object")
    parser.add_argument("--run", action="store_true", help="run the serial JVM tests and publish to the local file repository")
    args = parser.parse_args()
    spec = json.loads(args.manifest.read_text())
    if spec.get("schema") != "storymodel4s-dependency-candidates/v1":
        raise SystemExit("unknown candidate manifest schema")
    local_sources = dict(value.split("=", 1) for value in args.source)
    work = args.output.resolve()
    work.mkdir(parents=True, exist_ok=False)
    repository = work / "maven"
    repository.mkdir()
    receipt = {"schema": "storymodel4s-dependency-staging/v1", "status": "preparing", "manifestSha256": digest(args.manifest), "commands": [], "sources": [], "artifacts": [], "publication": "not-published"}
    receipt_path = work / "receipt.json"

    def save() -> None:
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")

    def run(command: list[str], cwd: Path, timeout: int = 600) -> None:
        log = work / f"command-{len(receipt['commands']):03d}.log"
        record = {"command": command, "cwd": str(cwd), "log": str(log)}
        receipt["commands"].append(record)
        save()
        with log.open("w") as output:
            try:
                result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
                record["exit"] = result.returncode
            except subprocess.TimeoutExpired:
                record["exit"] = None
                record["timeoutSeconds"] = timeout
            except OSError as error:
                record["exit"] = None
                record["spawnError"] = str(error)
        record["logSha256"] = digest(log)
        save()
        if record["exit"] != 0:
            raise RuntimeError(f"command failed; see {log}")

    try:
        for entry in spec["repositories"]:
            name, commit = entry["name"], entry["commit"]
            dest = work / name
            source = local_sources.get(name, entry["url"])
            run(["git", "clone", "--no-checkout", source, str(dest)], work)
            run(["git", "checkout", "--detach", commit], dest)
            actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=dest, text=True).strip()
            if actual != commit:
                raise RuntimeError(f"wrong checkout for {name}: {actual}")
            run(["git", "remote", "set-url", "origin", entry["url"]], dest)
            overlay = dest / "zz-release-preparation.sbt"
            # JSON string quoting also produces valid Scala literals for these fixed paths/URLs.
            overlay.write_text(
                f'ThisBuild / version := {json.dumps(entry["version"])}\n'
                f'ThisBuild / publishTo := Some(Resolver.file("local-release-rehearsal", file({json.dumps(str(repository))}))(Resolver.mavenStylePatterns))\n'
                'ThisBuild / publishMavenStyle := true\n'
            )
            record = {"name": name, "commit": commit, "version": entry["version"], "url": entry["url"], "path": str(dest), "overlaySha256": digest(overlay)}
            receipt["sources"].append(record)
            save()
        grakern = work / "grakern"
        build = grakern / "build.sbt"
        source = build.read_text()
        for name, variable in [("graph4s", "Graph4sRevision"), ("gale", "GaleRevision")]:
            original = f's"https://github.com/canardlapin/{name}.git#${variable}"'
            if source.count(original) != 1:
                raise RuntimeError(f"expected one pinned {name} source reference")
            source = source.replace(original, json.dumps((work / name).as_uri()))
        build.write_text(source)
        receipt["sourceRoutingPatchSha256"] = digest(build)
        run(["git", "diff", "--check"], grakern)
        coordinates = [
            {"groupId": "io.github.canardlapin", "artifactId": artifact, "version": entry["version"], "packaging": "jar"}
            for entry in spec["repositories"] for artifact in entry["projects"].values()
        ]
        (work / "matrix.json").write_text(json.dumps({"schema": "storymodel4s-release-candidate-matrix/v1", "coordinates": coordinates}, indent=2) + "\n")
        receipt["status"] = "prepared"
        save()
        if args.run:
            for entry in spec["repositories"]:
                # Override each target at project scope as well: a project-specific setting must
                # never turn this local rehearsal into a remote publication.
                target = f'Some(Resolver.file("local-release-rehearsal", file({json.dumps(str(repository))}))(Resolver.mavenStylePatterns))'
                settings = [f'set LocalProject("{project}") / publishTo := {target}' for project in entry["projects"]]
                commands = ["sbt", "-batch"] + settings + entry["tests"] + [f"{project}/publish" for project in entry["projects"]]
                run(commands, work / entry["name"], timeout=3600)
            for path in sorted(repository.rglob("*")):
                if path.is_file():
                    receipt["artifacts"].append({"path": str(path.relative_to(repository)), "size": path.stat().st_size, "sha256": digest(path)})
            poms = [parse_pom(path) for path in pom_paths([repository])]
            require_matrix(poms, load_matrix(work / "matrix.json"), "upstream staging")
            missing = internal_closure(poms)
            if missing:
                raise RuntimeError("upstream dependency closure failed: " + "; ".join(missing))
            receipt["pomValidation"] = {"matrix": "passed", "internalClosure": "passed", "count": len(poms)}
            receipt["status"] = "locally-staged-pending-preflight"
    except Exception as error:
        receipt["status"] = "failed"
        receipt["error"] = str(error)
    finally:
        save()
    print(f"{receipt['status']}: {receipt_path}")
    return 1 if receipt["status"] == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
