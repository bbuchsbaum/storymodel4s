"""Run the pinned sibling gate only after successful restored source qualification."""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--source-meta', type=Path, required=True)
p.add_argument('--source-clone', type=Path, required=True)
p.add_argument('--source-sha', required=True)
p.add_argument('--clone', type=Path, required=True)
p.add_argument('--log', type=Path, required=True)
a = p.parse_args()
deadline = time.monotonic() + 7500
while time.monotonic() < deadline:
    if a.source_meta.exists():
        try:
            meta = json.loads(a.source_meta.read_text())
        except json.JSONDecodeError:
            meta = {}
        if 'exit_code' in meta:
            assert meta['exit_code'] == 0, 'Source gate failed; consumer is not submitted.'
            assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=a.source_clone,
                text=True).strip() == a.source_sha
            assert not subprocess.check_output(['git', 'status', '--porcelain'],
                cwd=a.source_clone, text=True).strip()
            assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=a.clone,
                text=True).strip() == '90f682d1acddd759867098e90f6e3ef163244b0e'
            assert not subprocess.check_output(['git', 'status', '--porcelain'],
                cwd=a.clone, text=True).strip()
            command = ['sbt', '-batch',
                '-Dstoryatlas4s.storymodel4s.build=' + str(a.source_clone),
                '-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern',
                'set ThisBuild / tlFatalWarnings := true', 'compileAll', 'testAll',
                'scalafmtCheckAll', 'scalafmtSbtCheck']
            print('Restored source green; pinned clean sibling compile/test gate starting.', flush=True)
            result = subprocess.run([sys.executable,
                '/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py',
                '--log', str(a.log), '--cwd', str(a.clone), '--timeout', '3600', '--', *command])
            sys.exit(result.returncode)
    time.sleep(15)
raise TimeoutError('Source gate did not complete within7500s; no consumer submitted.')
