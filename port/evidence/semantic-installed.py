"""Run the wheel's connected example and every semantic CLI route outside the checkout."""

import io
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

base = Path(__file__).resolve().parents[1]
python = base / "data/wheel-env/bin/python"
cli = base / "data/wheel-env/bin/storymodel"
env = dict(os.environ)
env.pop("PYTHONPATH", None)
count = 0
with tempfile.TemporaryDirectory(prefix="semantic-installed-") as temporary:
    root = Path(temporary)
    for name in ("semantic_inference", "account_inference"):
        shutil.copy2(base / f"examples/{name}.py", root / f"{name}.py")
    process = subprocess.run(
        [str(python), "semantic_inference.py", "example"],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
    )
    assert process.returncode == 0, process.stdout + process.stderr
    summary = json.loads(process.stdout)
    import_path = subprocess.check_output(
        [str(python), "-c", "import storymodel; print(storymodel.__file__)"],
        cwd=root,
        env=env,
        text=True,
    ).strip()
    assert "site-packages/storymodel" in import_path

    def run(*args, succeeds=True):
        global count
        count += 1
        result = subprocess.run(
            [str(cli), *map(str, args)], cwd=root, env=env, capture_output=True, text=True
        )
        assert (result.returncode == 0) is succeeds, result.stdout + result.stderr
        return json.loads(result.stdout) if succeeds else result.stderr

    for number in (1, 2):
        case = root / f"example/recall-{number}"
        request = case / "request.json"
        assert run(
            "prepare-semantic",
            root / "example/source.json",
            case / "recall.json",
            case / "spec.json",
            root / "example/pair.json",
            "--cache",
            case / "cache-before.json",
            "--max-semantic-candidates",
            "2",
        ) == json.loads(request.read_text())
        assert run("semantic-prompt", request) == json.loads((case / "prompt.json").read_text())
        run("import-semantic", request, case / "recording/import.json", case / "cli-recording")
        result = run("replay-semantic", request, case / "cli-recording")
        assert result == json.loads((case / "result.json").read_text())
        assert run("verify-semantic", request, case / "reply.txt", case / "result.json")["verified"]
        denied = json.loads(request.read_text())
        denied["source"]["export_permission"] = "denied"
        denied_path = case / "denied.json"
        denied_path.write_text(json.dumps(denied))
        assert "denies" in run(
            "import-semantic",
            denied_path,
            "absent-envelope.json",
            case / "no-write",
            succeeds=False,
        )
        assert "denies" in run(
            "replay-semantic", denied_path, case / "absent-recording", succeeds=False
        )
        assert not (case / "no-write").exists()
    # Compare unchanged legacy workflow artifacts against the pre-semantic implementation.
    baseline = "49ca5d414"
    archived = subprocess.check_output(["git", "archive", f"{baseline}:port/src"], cwd=base.parent)
    old = root / "old-src"
    old.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archived)) as archive:
        archive.extractall(old, filter="data")
    for kind, override in (("old", str(old)), ("new", None)):
        child_env = dict(env)
        if override is not None:
            child_env["PYTHONPATH"] = override
        subprocess.run(
            [str(python), "account_inference.py", kind],
            cwd=root,
            env=child_env,
            check=True,
            capture_output=True,
        )
    before = {
        p.relative_to(root / "old"): p.read_bytes()
        for p in (root / "old").rglob("*")
        if p.is_file()
    }
    after = {
        p.relative_to(root / "new"): p.read_bytes()
        for p in (root / "new").rglob("*")
        if p.is_file()
    }
    assert before == after
    resource_probe = """
import json, time, tracemalloc
from account_inference import control
from storymodel import *
from storymodel.serialization import canonical
from storymodel.source_transitions import SourceProjectionPolicy
pair = EmbeddingPair(**json.load(open('example/pair.json')))
records = []
for rows in (32, 128):
    source = control('source', '\\n'.join(['AA PP BB'] * rows))
    recall = control('recall', '\\n'.join(['AA PP BB'] * 8))
    local = LocalEvidenceSpec(
        units=tuple(RecallSelection(unit=u.id) for u in recall.units),
        projection=SourceProjectionPolicy(
            memberships={s.id:u.id for s,u in zip(source.states,source.units)}),
        max_candidates=4, max_postings=32)
    request = prepare_semantic(source,recall,local,pair,
                               max_comparisons=16,max_semantic_candidates=4)
    prompt = json.loads(semantic_prompt(request))
    raw = canonical(dict(schema_version='storymodel.embedding-reply/v1',
        request_sha256=semantic_request_digest(request),
        rows=[dict(key=i['key'],value=dict(status='observed',values=[1.,0.]))
              for i in prompt['items']]))
    tracemalloc.start()
    start = time.perf_counter()
    result = resolve_semantic(request, raw)
    elapsed = time.perf_counter()-start
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    assert all(r.comparisons_read == 16 and len(r.nominations) == 4 and r.budget_limited
               for r in result.semantic_retrieval)
    records.append(dict(source_rows=rows,source_anchors=2*rows,recall_units=8,dimension=2,
        vector_comparisons=sum(r.comparisons_read for r in result.semantic_retrieval),
        retained_semantic_candidates=sum(len(r.nominations) for r in result.semantic_retrieval),
        seconds_with_tracemalloc=elapsed,python_peak_bytes=peak))
print(json.dumps(records))
"""
    resources = subprocess.run(
        [str(python), "-c", resource_probe], cwd=root, env=env, capture_output=True, text=True
    )
    assert resources.returncode == 0, resources.stdout + resources.stderr
    print(
        json.dumps(
            dict(
                resources=json.loads(resources.stdout),
                import_path=import_path,
                cli_checks=count,
                workflow=summary,
                legacy_baseline=baseline,
                legacy_files=len(before),
                legacy_bytes=sum(map(len, before.values())),
                legacy_identical=True,
            )
        )
    )
