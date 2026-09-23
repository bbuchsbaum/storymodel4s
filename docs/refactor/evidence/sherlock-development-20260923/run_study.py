#!/usr/bin/env python3
"""Bounded development reproduction; raw reports stay under the ignored data root."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
PROTOCOL = HERE / "protocol.json"


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    with Path(path).open("x") as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write("\n")


def require(ok, why):
    if not ok:
        raise ValueError(why)


def checked_inputs(data):
    protocol = load(PROTOCOL)
    for name, pin in protocol["inputs"].items():
        path = (data if pin["base"] == "data" else ROOT) / pin["path"]
        require(path.stat().st_size == pin["bytes"] and sha(path) == pin["sha256"],
                "input changed: " + name)
    require(not subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=no"], cwd=ROOT).strip(),
        "commit the protocol, runner and support registration before execution")
    return protocol


def run(command, out, name, environment=None):
    log = out / (name + ".log")
    started = time.time()
    with log.open("xb") as stream:
        result = subprocess.run(command, cwd=ROOT, env=environment,
                                stdout=stream, stderr=subprocess.STDOUT, check=False)
    receipt = {"command": command, "cwd": str(ROOT), "exitCode": result.returncode,
               "startedUnixSeconds": started, "elapsedSeconds": time.time() - started,
               "logSha256": sha(log), "logBytes": log.stat().st_size,
               "producerCommit": subprocess.check_output(
                   ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
               "protocolSha256": sha(PROTOCOL)}
    write(out / (name + ".receipt.json"), receipt)
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["freeze", "map", "score"])
    parser.add_argument("--data-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--classpath", type=Path)
    args = parser.parse_args()
    data, out = args.data_root.resolve(), args.out.resolve()
    require(out.is_relative_to(data / "study/recall-to-video"), "raw output must stay in study data")
    protocol = checked_inputs(data)
    manifest = data / protocol["inputs"]["unitManifest"]["path"]
    scorer = str(ROOT / "tools/recall-study/gold_scene.py")
    if args.phase == "freeze":
        out.mkdir(parents=True, exist_ok=False)
        command = [sys.executable, scorer, "freeze-support", str(manifest),
                   str(data / protocol["gold"]["path"]), str(out / "support.json"),
                   "--partition", "development"]
        require(run(command, out, "freeze-support") == 0, "support freeze failed; retained log")
        # This output must be committed independently before opening predictions.
        print((out / "freeze-support.log").read_text().strip())
        return

    registration = load(HERE / "support-registration.json")
    require(registration["protocolSha256"] == sha(PROTOCOL), "registered protocol changed")
    require(registration["supportFileSha256"] == sha(out / "support.json"), "support changed")
    if args.phase == "map":
        require(args.classpath is not None, "--classpath required for map")
        classpath = args.classpath.read_text().strip()
        require(classpath and "\n" not in classpath, "one runtime classpath required")
        environment = {k: v for k, v in os.environ.items() if not k.startswith("STORYMODEL4S_")}
        environment.update(protocol["settings"]["environment"])
        environment.update(ORT_DISABLE_TELEMETRY="1", HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1")
        environment["STORYMODEL4S_ONNX_MODEL"] = str(data / protocol["inputs"]["model"]["path"])
        environment["STORYMODEL4S_ONNX_TOKENIZER"] = str(data / protocol["inputs"]["tokenizer"]["path"])
        outcomes = []
        for arm in protocol["arms"]:
            target = out / arm
            target.mkdir(exist_ok=False)
            env = environment.copy()
            if protocol["arms"][arm]["captions"]:
                env["STORYMODEL4S_SCENE_CAPTIONS"] = str(data / protocol["arms"][arm]["captions"])
            for participant in protocol["participants"]:
                name = participant["participant"]
                report = target / ("recall-map-" + name + ".tsv")
                command = ["java", "-Xmx2G", "-cp", classpath,
                           "storymodel4s.bench.sherlock.sherlockRecallMap",
                           str(data / protocol["inputs"]["annotation"]["path"]),
                           str(data / protocol["inputs"]["recall:" + name]["path"]), str(report)]
                code = run(command, target, name, env)
                artifacts = {p.name: {"sha256": sha(p), "bytes": p.stat().st_size}
                             for p in sorted(target.glob(report.name + "*")) if p.is_file()}
                outcome = {"arm": arm, "participant": name, "exitCode": code,
                           "artifacts": artifacts,
                           "status": "produced" if code == 0 and report.is_file() else "failed"}
                outcomes.append(outcome)
                # Preserve every attempt immediately; a later failure never erases it.
                write(target / (name + ".outcome.json"), outcome)
                print(arm, name, outcome["status"], flush=True)
        write(out / "mapper-outcomes.json", outcomes)
        require(all(o["status"] == "produced" for o in outcomes),
                "at least one participant failed; no partial aggregate permitted")
        return

    outcomes = load(out / "mapper-outcomes.json")
    expected = {(arm, p["participant"]) for arm in protocol["arms"] for p in protocol["participants"]}
    require(len(outcomes) == len(expected) and
            {(o["arm"], o["participant"]) for o in outcomes} == expected and
            all(o["status"] == "produced" for o in outcomes), "incomplete mapper outcomes")
    for outcome in outcomes:
        for name, pin in outcome["artifacts"].items():
            path = out / outcome["arm"] / name
            require(path.stat().st_size == pin["bytes"] and sha(path) == pin["sha256"],
                    "mapper output changed")
    for arm in protocol["arms"]:
        command = [sys.executable, scorer, "import-tsv", str(manifest), str(out / arm),
                   str(out / (arm + ".json")), "--partition", "development"]
        require(run(command, out, "import-" + arm) == 0, "arm import failed; no partial aggregate")
    for name, other in [("A-self", "A"), ("A-AV", "AV")]:
        if other not in protocol["arms"]:
            continue
        command = [sys.executable, scorer, "compare", str(manifest), str(out / "support.json"),
                   str(out / "A.json"), str(out / (other + ".json")), str(out / (name + ".json")),
                   "--config", str(ROOT / "tools/recall-study/scoring-config.json"),
                   "--support-sha256", registration["supportSha256"]]
        require(run(command, out, "compare-" + name) == 0, "fixed-support comparison failed")
    comparison = load(out / ("A-AV.json" if "AV" in protocol["arms"] else "A-self.json"))
    summary = {"qualification": "development-only scene localization; no held-out or calibration claim",
               "inputParticipants": comparison["inputParticipants"],
               "eligibleParticipants": comparison["eligibleParticipants"], "arms": {}}
    for index, arm in enumerate(protocol["arms"]):
        rows = comparison["participants"]
        eligible = [p for p in rows if p["goldEligibleUnits"] > 0]
        summary["arms"][arm] = {
            "participantAverageSceneExactPercent": statistics.mean(
                p["arms"][index]["sceneExact"] for p in eligible) if eligible else None,
            "participantAverageWithinOnePercent": statistics.mean(
                p["arms"][index]["sceneWithinOne"] for p in eligible) if eligible else None,
            "pooledSceneExactPercent": sum(p["arms"][index]["sceneExact"] * p["goldEligibleUnits"]
                                           for p in eligible) / sum(p["goldEligibleUnits"] for p in eligible)
            if eligible else None,
            "inputUnits": sum(p["inputUnits"] for p in rows),
            "goldEligibleUnits": sum(p["goldEligibleUnits"] for p in rows)}
    summary["pairedSeries"] = comparison["series"]
    write(out / "summary.json", summary)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
