#!/usr/bin/env python3
"""Wait for the recorded source gate, then run docs and the exact consumer pair serially."""
import json
from pathlib import Path
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
producer = root / 'producer-check'
docs = root / 'docs-check'
atlas = root / 'atlas-check'
producer_sha = '2c4b74f9ef069e1bc1f6678f8dababe2421ca929'
docs_sha = sys.argv[2]
atlas_sha = 'ac2ca6cb85fdb8a959390e0e3d16e7af87c3e16d'
runner = '/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
guard = str(Path.home() / '.local/share/agent-policy/browser-automation-guard.mjs')
state = {'producer': producer_sha, 'docs': docs_sha, 'consumer': atlas_sha,
         'status': 'waiting-for-source', 'completed': []}


def save():
    (root / 'serial-checks.json').write_text(json.dumps(state, indent=2) + '\n')


def exact(path, sha):
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=path, text=True).strip() == sha
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=path, text=True).strip()


def command(label, cwd, arguments, timeout=7200):
    state['status'] = label
    save()
    code = subprocess.call([sys.executable, runner, '--log', str(root / (label + '.log')),
                            '--timeout', str(timeout), '--', *arguments], cwd=cwd)
    state['completed'].append({'check': label, 'exit': code})
    save()
    return code


save()
deadline = time.monotonic() + 14400
meta = root / 'planned-cli-full-gate.log.meta.json'
while True:
    # The log runner reserves an empty sidecar at startup and writes it on completion.
    # Existence alone is not completion; a read may also overlap the final write.
    try:
        source = json.loads(meta.read_text())
        if isinstance(source.get('exit_code'), int):
            break
    except (OSError, json.JSONDecodeError):
        pass
    if time.monotonic() >= deadline:
        state['status'] = 'source-wait-timeout'; save(); raise SystemExit(1)
    time.sleep(30)
if source['exit_code'] != 0:
    state['status'] = 'source-failed'; save(); raise SystemExit(1)
exact(producer, producer_sha)
exact(docs, docs_sha)
code = command('docs-browser-audit-before', docs, ['node', guard, '--audit'], 60)
if code:
    raise SystemExit(code)
try:
    code = command('docs-full-verify', docs / 'docs-site', ['npm', 'run', 'verify'])
finally:
    audit = command('docs-browser-audit-after', docs, ['node', guard, '--audit'], 60)
if code or audit:
    state['status'] = 'docs-failed'; save(); raise SystemExit(code or audit)
exact(atlas, atlas_sha)
exact(producer, producer_sha)
code = command('planned-cli-atlas-exact-pair', atlas, [
    'sbt', '-batch', f'-Dstoryatlas4s.storymodel4s.build={producer}',
    'set ThisBuild / tlFatalWarnings := true', 'compileAll', 'testAll',
    'app/fastLinkJS', 'scalafmtCheckAll', 'scalafmtSbtCheck'])
state['status'] = 'complete' if code == 0 else 'consumer-failed'; save()
raise SystemExit(code)
