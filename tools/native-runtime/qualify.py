#!/usr/bin/env python3
"""Compare unmodified Scala Native with the retained zero-hash patch in a new clone."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

BASE = "65dd513df188300d0180514ad828308facc5ca61"
UPSTREAM = "https://github.com/scala-native/scala-native.git"
HERE = Path(__file__).resolve().parent
PROBE_CASES = ["empty", "dynamic-nul", "dynamic-zero-ascii", "ordinary", "negative",
               "literal-nul", "literal-two-nuls", "literal-zero-ascii"]


def plain_log(text: str) -> str:
    """Remove terminal CSI styling for parsing; the saved log remains byte-for-byte raw."""
    return re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", text)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="new output directory")
    parser.add_argument("--source", default=UPSTREAM, help="optional local upstream Git clone")
    parser.add_argument("--mode", choices=["debug", "release-fast"], default="debug")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    checkout = output / "scala-native"
    receipt = {"schema": "storymodel4s-native-runtime-qualification/v1", "base": BASE,
               "mode": args.mode, "status": "running", "commands": [],
               "inputs": {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                          for name in ["ZeroHashProbe.scala", "zero-hash.patch"]}}

    def save():
        (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")

    def run(label, command, cwd, must_pass=True):
        log = output / (label + ".log")
        start = time.monotonic()
        environment = dict(os.environ, SCALANATIVE_MODE=args.mode)
        record = {"label": label, "command": command, "exit": None, "log": log.name}
        receipt["commands"].append(record)
        save()
        try:
            with log.open("w") as stream:
                result = subprocess.run(command, cwd=cwd, env=environment, stdout=stream,
                                        stderr=subprocess.STDOUT, timeout=1800)
            record["exit"] = result.returncode
        except subprocess.TimeoutExpired:
            record["timeoutSeconds"] = 1800
            raise RuntimeError(f"{label} timed out; inspect {log}")
        finally:
            record["seconds"] = round(time.monotonic() - start, 3)
            if log.exists():
                record["sha256"] = hashlib.sha256(log.read_bytes()).hexdigest()
            save()
        if must_pass and record["exit"]:
            raise RuntimeError(f"{label} exited {record['exit']}; inspect {log}")
        return record["exit"], plain_log(log.read_text(errors="replace"))

    try:
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        clang = str(Path(os.environ["LLVM_BIN"]) / "clang") if os.environ.get("LLVM_BIN") else shutil.which("clang")
        if not clang:
            raise RuntimeError("clang is unavailable")
        run("java-version", [java, "-version"], output)
        run("clang-version", [clang, "--version"], output)
        run("clone", ["git", "clone", "--no-checkout", args.source, str(checkout)], output)
        run("checkout", ["git", "checkout", "--detach", BASE], checkout)
        actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
        if actual != BASE:
            raise RuntimeError("wrong upstream revision")
        (checkout / "sandbox/src/main/scala/ZeroHashProbe.scala").write_bytes(
            (HERE / "ZeroHashProbe.scala").read_bytes())
        settings = ["sbt", "-batch", "++3.7.4",
                    'set LocalProject("sandbox3") / Compile / mainClass := Some("ZeroHashProbe")',
                    "show sandbox3/nativeConfig", "show tests3/nativeConfig"]
        code, original = run("original", settings + ["sandbox3/run"], checkout, False)
        original_passes = re.findall(r"^PASS (.+)$", original, re.M)
        if (code == 0 or original_passes != PROBE_CASES[:5]
                or "START literal-nul" not in original
                or not re.search(r"Unhandled signal (10|11)\b", original)):
            raise RuntimeError("original did not reproduce the named literal-NUL crash after controls")
        receipt["original"] = {"controlPasses": original_passes, "crashCase": "literal-nul"}
        run("apply", ["git", "apply", str(HERE / "zero-hash.patch")], checkout)
        source_suffixes = [
            "/sbt-buildinfo/ScalaNativeBuildInfo.scala",
            "/org/scalanative/testsuite/javalib/lang/StringTest.scala",
            "/org/scalanative/testsuite/javalib/lang/StringHashCodeTest.scala",
            "/utils/AssertThrows.scala", "/org/scalanative/testsuite/utils/Platform.scala"]
        select = ('set LocalProject("tests3") / Test / sources ~= (_.filter(p => Seq('
                  + ", ".join(json.dumps(s) for s in source_suffixes)
                  + ').exists(p.getPath.endsWith)))')
        code, fixed = run("fixed", settings + ["sandbox3/run", select,
            "tests3/testOnly org.scalanative.testsuite.javalib.lang.StringHashCodeTest "
            "org.scalanative.testsuite.javalib.lang.StringTest"], checkout)
        if re.findall(r"^PASS (.+)$", fixed, re.M) != PROBE_CASES:
            raise RuntimeError("fixed probe did not pass all eight named cases")
        suites = re.findall(r"Test run ([\w.]+) finished: (\d+) failed, (\d+) ignored, (\d+) total", fixed)
        # Scala Native's JUnitTask increments `total` only for non-ignored methods.
        # Retain that executed count and normalize the inventory total separately.
        by_name = {name.rsplit(".", 1)[-1]: {"failed": int(failed), "ignored": int(ignored),
                   "executed": int(total), "total": int(total) + int(ignored),
                   "passed": int(total) - int(failed)}
                   for name, failed, ignored, total in suites}
        expected_string_tests = len(re.findall(r"@Test\b", (checkout /
            "unit-tests/shared/src/test/scala/org/scalanative/testsuite/javalib/lang/StringTest.scala").read_text()))
        if (set(by_name) != {"StringTest", "StringHashCodeTest"}
                or any(s["failed"] for s in by_name.values())
                or by_name["StringHashCodeTest"]["passed"] != 9
                or by_name["StringTest"]["total"] != expected_string_tests):
            raise RuntimeError(f"missing or failing String test totals: {by_name}")
        receipt["fixed"] = {"probePassed": 8, "suites": by_name}
        receipt["status"] = "passed"
    except Exception as error:
        receipt["status"] = "failed"
        receipt["error"] = str(error)
    finally:
        save()
    print(f"{receipt['status']}: {output / 'receipt.json'}")
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
