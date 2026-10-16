#!/usr/bin/env python3
"""Qualify an immutable Git-source build and a network-denied installed CLI.

Uses fresh sbt/Coursier directories, records actual source fetches and exits, and
retains failures. It never publishes artifacts or supplies a local source override.
The runtime witness reuses the admitted public control in method/map-files.mdx.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET
import urllib.error
import urllib.parse
import urllib.request


OVERRIDE_KEYS = ("STORYMODEL4S_GRAKERN_BUILD", "SBT_OPTS", "JAVA_OPTS",
                 "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS")
TOTALS = re.compile(r"(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, Skipped (\d+))?")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_environment(environment):
    present = [key for key in OVERRIDE_KEYS if environment.get(key)]
    require(not present, "ambient build overrides: " + ", ".join(present))


def load_pins(path):
    value = json.loads(Path(path).read_text())
    require(value.get("schema") == "storymodel4s-source-pins/v1", "unknown source-pin schema")
    sources = value["sources"]
    require({row["name"] for row in sources} == {"grakern", "graph4s", "gale"} and len(sources) == 3,
            "source-pin inventory mismatch")
    for row in sources:
        require(re.fullmatch(r"[0-9a-f]{40}", row["revision"]), "non-immutable source revision")
        require(row["url"] == "https://github.com/canardlapin/" + row["name"] + ".git",
                "unexpected source URL")
    return sources


def validate_fetches(pins, fetched, declarations):
    require(len(fetched) == len(pins), "missing or extra source checkout")
    for pin in pins:
        matches = [row for row in fetched if row["url"] == pin["url"]]
        require(len(matches) == 1, "missing or duplicate source: " + pin["name"])
        row = matches[0]
        require(row["revision"] == pin["revision"] and row["clean"],
                "wrong revision or dirty source: " + pin["name"])
        pattern = r'\bval\s+' + re.escape(pin["variable"]) + r'\s*=\s*"([0-9a-f]+)"'
        declared = re.findall(pattern, declarations[pin["declaredBy"]])
        require(declared == [pin["revision"]], "source declaration differs: " + pin["name"])


def test_totals(text):
    rows = []
    for total, failed, errors, passed, skipped in TOTALS.findall(text):
        row = dict(zip(("total", "failed", "errors", "passed", "skipped"),
                       map(int, (total, failed, errors, passed, skipped or "0"))))
        require(row["total"] == sum(row[key] for key in ("failed", "errors", "passed", "skipped")),
                "inconsistent test total")
        rows.append(row)
    require(rows and sum(row["passed"] for row in rows) > 0, "no passing test totals")
    require(not any(row["failed"] or row["errors"] for row in rows), "failing test totals")
    return {"tasks": len(rows), **{key: sum(row[key] for row in rows)
                                 for key in ("passed", "failed", "errors", "skipped")}}


def extract(archive, destination):
    require(not destination.exists(), "extraction destination exists")
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP entry")
        for entry in bundle.infolist():
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in entry.filename,
                    "unsafe ZIP entry")
            require((entry.external_attr >> 16) & 0o170000 != 0o120000, "ZIP symlink")
        roots = {PurePosixPath(name).parts[0] for name in names}
        require(len(roots) == 1, "distribution must have one root")
        destination.mkdir()
        bundle.extractall(destination)
    return destination / roots.pop()


def inventory(directory):
    return {p.relative_to(directory).as_posix(): sha(p)
            for p in sorted(directory.rglob("*")) if p.is_file()}


def pom_inventory(paths):
    """Trace the actual candidate POM graph; retain scope/optional declarations."""
    def child(node, name, default=None):
        if node is None:
            return default
        found = next((n for n in node if n.tag.rsplit("}", 1)[-1] == name), None)
        return found

    def text(node, name, default=None):
        found = child(node, name)
        return found.text.strip() if found is not None and found.text else default

    result = {}
    for path in paths:
        root = ET.parse(path).getroot()
        key = ":".join(text(root, name) for name in ("groupId", "artifactId", "version"))
        require(key not in result, "duplicate candidate POM")
        dependencies = []
        for dependency in child(root, "dependencies", []):
            dependencies.append({"coordinate": ":".join(text(dependency, name) for name in
                                                         ("groupId", "artifactId", "version")),
                                 "scope": text(dependency, "scope", "compile"),
                                 "optional": text(dependency, "optional", "false") == "true"})
        result[key] = {"sha256": sha(path), "dependencies": dependencies}
    return result


def affected_by_missing(poms, missing):
    """Compile/runtime paths from a candidate to unresolved public dependency roots."""
    result = {}
    for coordinate in poms:
        found, visited, pending = set(), set(), [coordinate]
        while pending:
            current = pending.pop()
            if current in visited:
                continue
            visited.add(current)
            if current in missing:
                found.add(current)
            for dependency in poms.get(current, {}).get("dependencies", []):
                if dependency["scope"] in {"compile", "runtime"} and not dependency["optional"]:
                    pending.append(dependency["coordinate"])
        if found:
            result[coordinate] = sorted(found)
    return result


def maven_witness(witness, checkout, base, env):
    matrix = json.loads((checkout / "tools/release/candidate-matrix.json").read_text())["coordinates"]
    require(len({row["project"] for row in matrix}) == len(matrix), "duplicate POM project")
    # Only the StoryModel version changes in this metadata invocation. The actual pinned
    # source dependencies retain their own versions; no publication overlays are supplied.
    witness.run(base + ['set ThisBuild / version := "1.0.0"',
                        *[row["project"] + "/makePom" for row in matrix]], checkout, env)
    output = witness.output / "poms"
    output.mkdir()
    for row in matrix:
        filename = row["artifactId"] + "-1.0.0.pom"
        paths = list((checkout / row["moduleDirectory"] / "target").rglob(filename))
        require(len(paths) == 1, "missing or duplicate generated candidate POM: " + filename)
        shutil.copyfile(paths[0], output / filename)
    poms = pom_inventory(sorted(output.glob("*.pom")))
    expected = {row["groupId"] + ":" + row["artifactId"] + ":1.0.0" for row in matrix}
    require(set(poms) == expected, "actual POM matrix differs")
    external = sorted({dep["coordinate"] for row in poms.values() for dep in row["dependencies"]
                       if dep["coordinate"] not in poms})
    public = {}
    for coordinate in external:
        group, artifact, version = coordinate.split(":")
        require(all(re.fullmatch(r"[A-Za-z0-9_.+\-]+", part) for part in (group, artifact, version)),
                "unsafe POM coordinate")
        url = "https://repo.maven.apache.org/maven2/" + "/".join((group.replace(".", "/"), artifact,
                 urllib.parse.quote(version, safe=""), urllib.parse.quote(artifact + "-" + version + ".pom", safe="")))
        row = {"url": url}
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
                row.update(httpStatus=response.status, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        except urllib.error.HTTPError as error:
            row["httpStatus"] = error.code
        except OSError as error:
            row["transportError"] = type(error).__name__
        public[coordinate] = row
    unresolved = {coordinate for coordinate, row in public.items() if row.get("httpStatus") != 200}
    closure = {"source": witness.receipt["source"], "candidateVersion": "1.0.0", "poms": poms,
               "publicRootPomChecks": public, "unresolvedRoots": sorted(unresolved),
               "affectedCandidateArtifacts": affected_by_missing(poms, unresolved),
               "scope": "Actual candidate POM graph and public direct-root availability; full public transitive resolution and publication retain their separate preflight.",
               "publication": "deferred-not-published"}
    path = witness.output / "maven-closure.json"
    path.write_text(json.dumps(closure, indent=2) + "\n")
    witness.receipt["mavenClosure"] = {"path": path.name, "sha256": sha(path), "candidatePoms": len(poms),
                                        "unresolvedRoots": sorted(unresolved),
                                        "affectedCandidateArtifacts": sorted(closure["affectedCandidateArtifacts"])}


class Witness:
    def __init__(self, output):
        self.output = output
        self.receipt = {"schema": "storymodel4s-source-cli-qualification/v1",
                        "status": "running", "qualification": "LocallyObserved",
                        "publication": "not-published", "startedUnix": time.time(), "commands": []}

    def save(self):
        (self.output / "receipt.json").write_text(json.dumps(self.receipt, indent=2) + "\n")

    def run(self, argv, cwd, env=None, expected=0, timeout=7200, stdout_path=None):
        log = self.output / ("command-%03d.log" % len(self.receipt["commands"]))
        row = {"argv": list(map(str, argv)), "cwd": str(cwd), "log": log.name,
               "expectedExit": expected}
        self.receipt["commands"].append(row)
        self.save()
        try:
            with log.open("wb") as stream:
                if stdout_path is None:
                    result = subprocess.run(row["argv"], cwd=cwd, env=env, stdout=stream,
                                            stderr=subprocess.STDOUT, timeout=timeout)
                else:
                    with stdout_path.open("wb") as payload:
                        result = subprocess.run(row["argv"], cwd=cwd, env=env, stdout=payload,
                                                stderr=stream, timeout=timeout)
                    row["stdout"] = str(stdout_path.relative_to(self.output))
                    row["stdoutSha256"] = sha(stdout_path)
            row["exit"] = result.returncode
        except subprocess.TimeoutExpired:
            row["exit"] = None
            row["timeoutSeconds"] = timeout
        except OSError as error:
            row["exit"] = None
            row["spawnError"] = str(error)
        row["logSha256"] = sha(log)
        self.save()
        require(row["exit"] == expected, "command exit differs; inspect " + str(log))
        return log


def offline_prefix(witness, cwd, env):
    require(sys.platform == "darwin" and shutil.which("sandbox-exec"),
            "this witness requires macOS sandbox-exec; an offline flag alone is insufficient")
    prefix = ["sandbox-exec", "-p", "(version 1) (allow default) (deny network*)"]
    # A live owned listener distinguishes actual denial from absent connectivity.
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        server.listen(4)
        port = server.getsockname()[1]
        code = ("import socket,sys\ntry:\n s=socket.create_connection(('127.0.0.1',"
                + str(port) + "),2);s.close()\nexcept PermissionError:\n sys.exit(23)\n"
                "except OSError:\n sys.exit(24)\nelse:\n sys.exit(0)\n")
        probe = [sys.executable, "-c", code]
        witness.run(probe, cwd, env, timeout=10)
        witness.run(prefix + probe, cwd, env, expected=23, timeout=10)
    witness.receipt["networkDenial"] = {"mechanism": "macOS sandbox deny network*",
                                         "onlineControlExit": 0, "deniedControlExit": 23}
    return prefix


def runtime_witness(witness, checkout, installed, env):
    env = dict(env, PYTHONDONTWRITEBYTECODE="1")
    root = witness.output / "runtime"
    root.mkdir()
    readers = root / "readers"
    readers.mkdir()
    for name in ("read_mapping.py", "read_weighted_mapping.py"):
        shutil.copyfile(checkout / "examples/mapping" / name, readers / name)
    # Exact, previously admitted project control; no participant material.
    (root / "source.txt").write_text("Anna arrived. Bob left.")
    (root / "recall.txt").write_text("Anna arrived.")
    study = {"schemaVersion": "storymodel-study/v1",
             "source": {"format": "text", "path": "source.txt", "kind": "Sentence"},
             "profile": "recall-baseline-lexical-words/v1",
             "recalls": [{"participant": "synthetic", "session": "one", "path": "recall.txt",
                           "timing": None, "admission": {"kind": "project-synthetic-control"}}]}
    (root / "study.json").write_text(json.dumps(study) + "\n")
    cli = ["sh", str(installed / "bin/storymodel4s")]
    witness.run(cli + ["mappingRun", "template"], root, env, stdout_path=root / "mapping.json")
    reader_results = {}
    prefix = offline_prefix(witness, root, env)
    for label, runner in (("online", []), ("offline", prefix)):
        exchange, weighted = label + "-exchange", label + "-weighted"
        witness.run(runner + cli + ["mappingRun", "exchange", "study.json", "mapping.json", exchange], root, env)
        witness.run(runner + cli + ["mappingWeight", "study.json", "mapping.json", "0/1,1/2,1/1", weighted], root, env)
        pair_log = witness.run(runner + [sys.executable, str(readers / "read_mapping.py"), "--directory", exchange], root, env)
        pair = json.loads(pair_log.read_text())
        require(pair["status"] == "pass" and pair["pairs"] == 1 and pair["attempts"] == 1,
                "paired reader did not check the expected observation")
        weight_log = witness.run(runner + [sys.executable, str(readers / "read_weighted_mapping.py"), weighted], root, env)
        require(weight_log.read_text().startswith("pass: 1 attempts, 3 weighted members;"),
                "weighted reader did not check three members")
        reader_results[label] = {"pairs": 1, "attempts": 1, "weightedMembers": 3}
    for suffix in ("exchange", "weighted"):
        require(inventory(root / ("online-" + suffix)) == inventory(root / ("offline-" + suffix)),
                "offline replay differs: " + suffix)
    witness.run(prefix + cli + ["mappingRun", "exchange", "study.json", "mapping.json", "offline-exchange", "--resume"], root, env)
    witness.run(prefix + cli + ["mappingRun", "exchange", "study.json", "mapping.json", "offline-exchange"], root, env, expected=2)
    witness.receipt["runtime"] = {"onlineOfflineBytesEqual": True, "pairedAndThreeWeights": True,
                                   "independentReaders": {name: sha(readers / name) for name in
                                                          ("read_mapping.py", "read_weighted_mapping.py")},
                                   "readback": reader_results,
                                   "inputs": {name: sha(root / name) for name in
                                              ("source.txt", "recall.txt", "study.json", "mapping.json")}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Git repository containing the exact revision")
    parser.add_argument("--revision", required=True, help="full immutable StoryModel commit")
    parser.add_argument("--output", type=Path, required=True, help="new directory on a volume with >=15 GiB free")
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--sbt", default="sbt")
    parser.add_argument("--full-gate", action="store_true", help="run workflow generation and checkAll instead of testJVM")
    args = parser.parse_args()
    validate_environment(os.environ)
    require(re.fullmatch(r"[0-9a-f]{40}", args.revision), "revision must be a full SHA")
    require((args.java_home / "bin/java").is_file(), "missing explicit JDK")
    output = args.output.resolve()
    require(not output.exists(), "output exists; preserve it and use a new directory")
    parent = output.parent
    parent.mkdir(parents=True, exist_ok=True)
    require(shutil.disk_usage(parent).free >= 15 * 1024**3, "fresh qualification requires >=15 GiB free")
    output.mkdir()
    witness = Witness(output)
    witness.receipt["source"] = args.revision
    witness.save()
    # sbt 1.12's JNI boot socket can overflow sockaddr_un with a long TMPDIR.
    # Keep sockets short and private while heavy temporary files use the build volume.
    ipc = tempfile.TemporaryDirectory(prefix="sm4s-ipc-", dir="/private/tmp" if sys.platform == "darwin" else "/tmp")
    witness.receipt["ipcDirectory"] = ipc.name
    try:
        checkout = output / "checkout"
        witness.run(["git", "clone", "--no-hardlinks", "--no-checkout", str(args.source.resolve()), str(checkout)], output)
        witness.run(["git", "checkout", "--detach", args.revision], checkout)
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=checkout), "dirty source checkout")
        pins = load_pins(checkout / "tools/release/source-pins.json")
        directories = {key: output / key for key in ("boot", "global", "staging", "ivy", "coursier", "config", "tmp")}
        for directory in directories.values():
            directory.mkdir()
        repositories = output / "repositories"
        repositories.write_text("[repositories]\n  maven-central: https://repo.maven.apache.org/maven2\n")
        env = dict(os.environ, JAVA_HOME=str(args.java_home.resolve()), TMPDIR=str(directories["tmp"]),
                   XDG_RUNTIME_DIR=ipc.name,
                   COURSIER_CACHE=str(directories["coursier"]), COURSIER_CONFIG_DIR=str(directories["config"]),
                   COURSIER_CREDENTIALS="", GIT_TERMINAL_PROMPT="0", ORT_DISABLE_TELEMETRY="1")
        witness.receipt["isolation"] = {key: str(value) for key, value in directories.items()}
        witness.run([str(args.java_home / "bin/java"), "-version"], output, env)
        base = [args.sbt, "-batch", "-J-Xmx4G", "-J-XX:ActiveProcessorCount=4",
                "-J-Djava.io.tmpdir=" + str(directories["tmp"]),
                "-Dsbt.boot.directory=" + str(directories["boot"]),
                "-Dsbt.global.base=" + str(directories["global"]),
                "-Dsbt.global.staging=" + str(directories["staging"]),
                "-Dsbt.ivy.home=" + str(directories["ivy"]),
                "-Dsbt.repository.config=" + str(repositories), "-Dsbt.override.build.repos=true"]
        tasks = ["githubWorkflowGenerate", "checkAll"] if args.full_gate else ["testJVM"]
        log = witness.run(base + ["set ThisBuild / tlFatalWarnings := true", *tasks, "pipeline/cliDistribution"], checkout, env)
        witness.receipt["tests"] = test_totals(log.read_text(errors="replace"))
        fetched, declarations = [], {"storymodel4s": (checkout / "build.sbt").read_text()}
        for gitdir in directories["staging"].rglob(".git"):
            tree = gitdir.parent
            url = subprocess.check_output(["git", "config", "--get", "remote.origin.url"], cwd=tree, text=True).strip()
            row = {"url": url, "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=tree, text=True).strip(),
                   "clean": not subprocess.check_output(["git", "status", "--porcelain"], cwd=tree)}
            # Record the access route without serializing credentials from a URL rewrite.
            effective = subprocess.check_output(["git", "remote", "get-url", "origin"], cwd=tree, text=True).strip()
            row["transport"] = "configured-ssh" if effective.startswith("git@") else "https" if effective == url else "configured-git-route"
            fetched.append(row)
            if url == "https://github.com/canardlapin/grakern.git":
                declarations["grakern"] = (tree / "build.sbt").read_text()
        validate_fetches(pins, fetched, declarations)
        witness.receipt["fetchedSources"] = fetched
        archives = list((checkout / "pipeline/target/cli-distribution").glob("*.zip"))
        require(len(archives) == 1, "expected one built distribution")
        installed = extract(archives[0], output / "installed")
        manifest = json.loads((installed / "manifest.json").read_text())
        require(manifest["source"] == args.revision, "packaged source differs")
        runtime = output / "verification"
        runtime.mkdir()
        witness.run([sys.executable, str(checkout / "tools/test_cli_distribution.py"), str(installed)], runtime, env)
        witness.receipt["artifact"] = {"archiveSha256": sha(archives[0]), "archiveBytes": archives[0].stat().st_size,
                                        "manifestSha256": sha(installed / "manifest.json"), "files": len(manifest["files"])}
        runtime_witness(witness, checkout, installed, env)
        maven_witness(witness, checkout, base, env)
        witness.receipt["status"] = "source-cli-qualified"
    except Exception as error:
        witness.receipt["status"] = "failed"
        witness.receipt["error"] = str(error)
    finally:
        witness.receipt["finishedUnix"] = time.time()
        witness.save()
        ipc.cleanup()
    print(witness.receipt["status"] + ": " + str(output / "receipt.json"))
    return 0 if witness.receipt["status"] == "source-cli-qualified" else 1


if __name__ == "__main__":
    raise SystemExit(main())
