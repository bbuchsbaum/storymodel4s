"""Watch exact published-head workflows with bounded transport retries."""
import datetime
import json
import subprocess
import sys
import time
from pathlib import Path

SHA, directory = sys.argv[1:3]
REPO = "bbuchsbaum/storymodel4s"
GH = "/Users/bbuchsbaum/.local/bin/gh-bbuchsbaum"
OUT = Path(directory)
OUT.mkdir(parents=True, exist_ok=True)

def event(record):
    record.update(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(), sha=SHA)
    with (OUT / "events.jsonl").open("a") as f:
        f.write(json.dumps(record) + "\n")

def api(path):
    for attempt in range(5):
        r = subprocess.run([GH, "api", path], capture_output=True, text=True, timeout=90)
        if r.returncode == 0:
            return json.loads(r.stdout)
        event({"transport_error": r.stderr, "path": path, "attempt": attempt + 1})
        if attempt == 4:
            raise RuntimeError(r.stderr)
        time.sleep(10 * (attempt + 1))

last = None
deadline = time.monotonic() + 7200
while time.monotonic() < deadline:
    data = api(f"repos/{REPO}/actions/runs?head_sha={SHA}&per_page=100")
    (OUT / "runs.json").write_text(json.dumps(data, indent=2) + "\n")
    selected = {}
    for run in data["workflow_runs"]:
        if run["name"] in ["Continuous Integration", "Documentation"]:
            selected.setdefault(run["name"], run)
    states = []
    for name, run in selected.items():
        assert run["head_sha"] == SHA
        slug = "source" if name == "Continuous Integration" else "docs"
        jobs = api(f"repos/{REPO}/actions/runs/{run['id']}/jobs?per_page=100")
        (OUT / f"{slug}-run.json").write_text(json.dumps(run, indent=2) + "\n")
        (OUT / f"{slug}-jobs.json").write_text(json.dumps(jobs, indent=2) + "\n")
        states.append({"name": name, "id": run["id"], "status": run["status"],
                       "conclusion": run["conclusion"], "jobs": [
                           {k: j[k] for k in ["id", "name", "status", "conclusion"]}
                           for j in jobs["jobs"]]})
    state = json.dumps(states, sort_keys=True)
    if state != last:
        print(state, flush=True)
        event({"states": states})
        last = state
    if len(selected) == 2 and all(r["status"] == "completed" for r in selected.values()):
        sys.exit(0 if all(r["conclusion"] == "success" for r in selected.values()) else 1)
    time.sleep(60)
raise TimeoutError("Hosted qualification exceeded 7200s; state retained")
