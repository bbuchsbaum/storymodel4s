"""Read-only reconciliation of the final S4b owned-browser smoke receipt."""
from pathlib import Path
import hashlib
import json
import re


root = Path(__file__).resolve().parents[3]
receipt_path = root / "data/study/d1a-s4b-20260919/consumer-final/consumer-smoke.json"
output = root / "data/study/film-foundation-goal-20260919/root-s4b-final-smoke-audit.json"
assert not output.exists(), output


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


receipt = json.loads(receipt_path.read_text())
assert receipt["schema"] == "d1a-consumer-smoke/v1"
cache = Path(receipt["ownedProcessesAfter"]["cachePath"])
clones = cache.parent
consumer = clones / "storyatlas4s"
expected = {
    "storyatlas4s": "b156029d95a662107c819dd2aefc80abec7d748e",
    "storymodel4s": "9dcaf8e786207d523787d670810c28d104efe117",
    "intaglio": "4eb566d9208f474d64d61e778e084dee2ddbaa76",
    "grakern": "0329c43c88a0b71e9aa4456723bb16bac2fa3841",
}
expected_state = {name: {"revision": revision, "status": ""} for name, revision in expected.items()}
assert receipt["before"] == receipt["after"] == expected_state
assert sha(consumer / "app/smoke/smoke.cjs") == receipt["scriptSha256"]

smoke = receipt["smoke"]
smoke_log = Path(smoke["logPath"])
assert sha(smoke_log) == smoke["logSha256"]
assert smoke["exitCode"] == 0 and smoke["terminalPassed"]
assert smoke["passedChecks"] == 260 and smoke["failedChecks"] == 0
smoke_text = smoke_log.read_text()
assert "\nsmoke passed\n" in smoke_text

for name in ["guardBefore", "guardAfter"]:
    guard = receipt[name]
    path = Path(guard["logPath"])
    assert guard["exitCode"] == 0
    assert sha(path) == guard["logSha256"]
    assert "No automated top-level browser processes found." in path.read_text()

pid = receipt["browser"]["actualLaunchedPid"]
for name, wanted_pids in [("ownedProcessesBefore", []), ("ownedProcessesAfter", [pid])]:
    observed = receipt[name]
    path = Path(observed["logPath"])
    assert observed["exactLaunchedPids"] == wanted_pids
    assert observed["rawScopedRows"] == []
    assert path.read_bytes() == b""
    assert sha(path) == observed["logSha256"]

browser = receipt["browser"]
assert browser["version"] == "1.55.1"
for name in ["executable", "actualLaunchedExecutable"]:
    assert Path(browser[name]).is_relative_to(cache)
assert sha(Path(browser["actualLaunchedExecutable"])) == browser["actualLaunchedExecutableSha256"]
match = re.search(r"<launching> (.*?) --.*?<launched> pid=(\d+)", smoke_text, re.S)
assert match and Path(match.group(1)) == Path(browser["actualLaunchedExecutable"])
assert int(match.group(2)) == pid

diagnostic = Path(receipt["environmentOverrides"]["STORYATLAS4S_SMOKE_DIAGNOSTICS_DIR"]) / "ancestor-proxy.json"
payload = json.loads(diagnostic.read_text())
assert "selection-proxy" in payload["proxy"]
assert 'points="0,0 1600,0 1600,1080 0,1080"' in payload["centerHit"]

result = {
    "scope": "Independent S4b browser-smoke receipt reconciliation; no browser launch or process action by auditor.",
    "revisions": expected,
    "smokeReceiptSha256": sha(receipt_path),
    "smokeLogSha256": smoke["logSha256"],
    "scriptSha256": receipt["scriptSha256"],
    "passedChecks": smoke["passedChecks"],
    "browser": {
        "playwrightVersion": browser["version"],
        "actualExecutable": browser["actualLaunchedExecutable"],
        "actualExecutableSha256": browser["actualLaunchedExecutableSha256"],
        "launchedPid": pid,
        "node": receipt["nodeRuntime"],
    },
    "lifecycle": {
        "preGuard": receipt["guardBefore"]["logSha256"],
        "postGuard": receipt["guardAfter"]["logSha256"],
        "ownedBefore": receipt["ownedProcessesBefore"]["logSha256"],
        "ownedAfter": receipt["ownedProcessesAfter"]["logSha256"],
        "allOwnedRowsAbsentAfter": True,
    },
    "proxyCenterDiagnosticSha256": sha(diagnostic),
    "conclusion": "Passed: 260 live checks, exact launched owned browser identified, global and cache/PID-scoped cleanup receipts clean.",
    "limits": "This validates the recorded local smoke only; it does not establish CI execution, remote publication, or scientific validity.",
}
output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
