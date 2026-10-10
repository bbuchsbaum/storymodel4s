"""Qualify the structural adapter and benchmark against the declared engine pin."""
import json
import re
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[4]
work = root / '.agent-work/workflow-five'
source = Path('/private/tmp/storymodel4s-s2b3-build')
engine = Path('/private/tmp/storymodel4s-workflow-grakern-pin')
sha = '34c0bc9a367c7c4e3cdad6743feb33cc4371f5ca'
pin = '0329c43c88a0b71e9aa4456723bb16bac2fa3841'
assert json.loads((work / 'workspace-source-complete.json').read_text())['sha'] == sha
for checkout, revision in [(source, sha), (engine, pin)]:
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip() == revision
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout, text=True).strip()
command = ['sbt', '-batch', '-Dstorymodel4s.grakern.build=' + str(engine),
           'set ThisBuild / tlFatalWarnings := true',
           'embedGrakern/clean', 'embedBench/clean', 'embedGrakern/test', 'embedBench/test']
log = work / 'workspace-declared-dependency.log'
result = subprocess.run([sys.executable,
                        '/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py',
                        '--log', str(log), '--cwd', str(source), '--timeout', '3600',
                        '--', *command], stdout=subprocess.DEVNULL)
print('declared dependency gate exit', result.returncode, flush=True)
assert result.returncode == 0
rows = [tuple(int(v or 0) for v in m) for m in re.findall(
    r'(?:Passed|Failed): Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)(?:, (?:Ignored|Skipped) (\d+))?',
    log.read_text())]
assert len(rows) == 2 and all(r[1] == r[2] == 0 for r in rows), rows
receipt = {'producer': sha, 'grakern': pin,
           'graph4s': 'ea5d2d762f85f5a0f97ee188deb5fac0ef2bcbaf',
           'gale': 'd55fe2f97196a76ab7879e1a12f1e92403aeba06',
           'command': command, 'exit': result.returncode, 'moduleCells': rows,
           'scope': 'Clean adapter and benchmark recompile; supplements full checkAll using explicit 8efc5efa engine override.'}
(work / 'workspace-dependency-complete.json').write_text(json.dumps(receipt, indent=2) + '\n')
