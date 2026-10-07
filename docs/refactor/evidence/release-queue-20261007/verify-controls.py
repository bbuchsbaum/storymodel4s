"""Corrupt rehashed receipt copies to prove the named verifier boundaries reject them."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent


def manifest(folder):
    files = sorted(p for p in folder.rglob('*') if p.is_file()
                   and p.name not in {'SHA256SUMS', 'validation.json'})
    (folder / 'SHA256SUMS').write_text(''.join(
        hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + str(p.relative_to(folder)) + '\n'
        for p in files))


def execute(folder):
    return subprocess.run([sys.executable, str(folder / 'verify.py')],
                          capture_output=True, text=True)


def load(folder, name):
    return json.loads((folder / name).read_text())


def save(folder, name, value):
    (folder / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


control = execute(HERE)
assert control.returncode == 0, control.stderr
results = []
for name, expected in [('wrong-edge', 'dependency changes do not match reviewed requests'),
                       ('missing-note-text', 'missing restored acceptance'),
                       ('truncated-batch', 'batch operation coverage'),
                       ('omitted-manifest-entry', 'artifact coverage')]:
    with tempfile.TemporaryDirectory(prefix='storymodel4s-queue-') as tmp:
        folder = Path(tmp) / 'receipt'
        shutil.copytree(HERE, folder)
        if name == 'wrong-edge':
            rows = load(folder, 'after.json')
            gate = next(r for r in rows if r['id'].startswith('bd-01M2TAMJ'))
            edge = next(e for e in gate['deps'] if e['parent'] == 'bd-01M4B896MCEE43Q9QMR4HT60R0')
            known = {e['parent'] for e in gate['deps']}
            edge['parent'] = next(r['id'] for r in load(folder, 'all-after.json')
                                  if r['status'] == 'closed' and r['id'] not in known)
            save(folder, 'after.json', rows)
        elif name == 'missing-note-text':
            rows = load(folder, 'after.json')
            privacy = next(r for r in rows if r['id'].startswith('bd-01M168TZ'))
            before = next(r for r in load(folder, 'before.json') if r['id'] == privacy['id'])
            text = before['restored_acceptance_notes'][0]['text']
            assert text in privacy['body']
            privacy['body'] = privacy['body'].replace(text, '[acceptance text omitted]', 1)
            save(folder, 'after.json', rows)
            plan = load(folder, 'classification.json')
            next(r for r in plan if r['id'] == privacy['id'])['body_after_sha256'] = (
                hashlib.sha256(privacy['body'].encode()).hexdigest())
            save(folder, 'classification.json', plan)
        elif name == 'truncated-batch':
            batch = load(folder, 'batch-result.json')
            batch['results'].pop()
            batch['accepted'] = len(batch['results'])
            save(folder, 'batch-result.json', batch)
        manifest(folder)  # Hashes remain lawful; rejection must name the semantic boundary.
        if name == 'omitted-manifest-entry':
            lines = (folder / 'SHA256SUMS').read_text().splitlines()
            assert any(line.endswith('  README.md') for line in lines)
            (folder / 'SHA256SUMS').write_text('\n'.join(
                line for line in lines if not line.endswith('  README.md')) + '\n')
        result = execute(folder)
        assert result.returncode != 0 and expected in result.stderr, (name, result.stderr)
        results.append({'mutation': name, 'exit': result.returncode,
                        'named_refusal': expected, 'hashes_recomputed': True})
assert execute(HERE).returncode == 0
print(json.dumps({'accepting_control_exit': 0, 'restored_control_exit': 0,
                  'refused_rehashed_controls': results}, indent=2))
