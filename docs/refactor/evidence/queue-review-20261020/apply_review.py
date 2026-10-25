#!/usr/bin/env python3
"""Apply the reviewed planning packet through the public Mote CLI, sequentially."""
import hashlib
import json
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ACTOR = 'codex-queue-review'


def main():
    operations = json.loads((HERE / 'planned-sets.json').read_text())
    original = json.loads((HERE / 'before.json').read_text())
    result_path = HERE / 'applied.json'
    if result_path.exists():
        raise RuntimeError('Prior mutation receipt exists; inspect/resume explicitly rather than duplicate operations')
    p = subprocess.run(['mote', '--actor', ACTOR, '--json', 'ls'], cwd=ROOT,
                       capture_output=True, text=True, check=True)
    current = {i['id']: i for i in json.loads(p.stdout)}
    for expected in original:
        if current.get(expected['id']) != expected:
            raise RuntimeError('Baseline changed; review before applying: ' + expected['id'])
    receipt = dict(sets_sha256=hashlib.sha256((HERE / 'planned-sets.json').read_bytes()).hexdigest(),
                   batch_sha256=hashlib.sha256((HERE / 'planned-batch.jsonl').read_bytes()).hexdigest(),
                   commands=[])

    def run(args, identity):
        p = subprocess.run(['mote', '--actor', ACTOR, *args], cwd=ROOT, capture_output=True, text=True)
        receipt['commands'].append(dict(command=['mote', '--actor', ACTOR, *identity],
                                        exit=p.returncode, stdout=p.stdout, stderr=p.stderr))
        result_path.write_text(json.dumps(receipt, indent=2) + '\n')
        if p.returncode:
            raise RuntimeError('Mote refused operation; inspect receipt before continuing')
        print('APPLIED', *identity[:2], flush=True)
    for operation in operations:
        fields = [name + '=' + str(value) for name, value in operation['fields'].items()]
        run(['set', operation['id'], *fields], ['set', operation['id'], 'fields bound by planned-sets.json'])
    run(['batch', str(HERE / 'planned-batch.jsonl')], ['batch', 'planned-batch.jsonl'])
    p = subprocess.run(['mote', '--actor', ACTOR, '--json', 'ls'], cwd=ROOT,
                       capture_output=True, text=True, check=True)
    (HERE / 'after.json').write_text(p.stdout)
    print('FINISHED', len(receipt['commands']), 'CLI commands', flush=True)


if __name__ == '__main__':
    main()
