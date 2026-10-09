"""Run one restored source gate after the bounded guard/trap controller has finished."""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--clone', type=Path, required=True)
p.add_argument('--sha', required=True)
p.add_argument('--mutations', type=Path, required=True)
p.add_argument('--log', type=Path, required=True)
p.add_argument('--scope', type=Path, required=True)
a = p.parse_args()
deadline = time.monotonic() + 2400
while time.monotonic() < deadline:
    receipt = a.mutations / 'mutations.json'
    if receipt.exists():
        try:
            witnesses = json.loads(receipt.read_text())
        except json.JSONDecodeError:
            witnesses = []
        if len(witnesses) == 17:
            assert all(w['candidate_sha'] == a.sha and w['qualified'] for w in witnesses)
            assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=a.clone,
                                           text=True).strip() == a.sha
            if not subprocess.check_output(['git', 'status', '--porcelain'], cwd=a.clone,
                                            text=True).strip():
                print('Seventeen named assertion/trap witnesses complete; exact source restored.',
                      flush=True)
                result = subprocess.run([sys.executable,
                    str(Path(__file__).with_name('source-gate-driver.py')),
                    '--clone', str(a.clone), '--sha', a.sha, '--log', str(a.log),
                    '--scope', str(a.scope)])
                sys.exit(result.returncode)
    time.sleep(15)
raise TimeoutError('Mutation controller did not supply seventeen restored witnesses within2400s')
