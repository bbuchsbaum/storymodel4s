# Fresh recapture of the pre-migration baseline

This procedure produces a separate observation of the old implementation. It does not
replace the frozen expectations or qualify a migration. The original `run.py` and its
recorded hash remain unchanged. Do not run it directly beside the historical manifest.

Use a local storymodel4s repository containing capture commit `f3c6edb4` and this recipe,
and a local grakern repository containing `0329c43c`. Set the paths below to those source
repositories. The destination must not exist. Setup uses fresh standalone clones and
does not start sbt. Its new manifest deliberately copies only immutable input fields;
old backend results, runtime, difference receipts, and completion fields are excluded.

```sh
python3 - /path/to/storymodel4s /path/to/grakern /private/tmp/wog-recapture-new <<'PY'
from pathlib import Path
import hashlib, json, platform, shutil, subprocess, sys, time

source, grakern_source, dest = map(lambda p: Path(p).resolve(), sys.argv[1:])
evidence = source / 'docs/refactor/evidence/wog-hsmm-baseline-20260919'
frozen = json.loads((evidence / 'manifest.json').read_text())
runner = (evidence / 'run.py').read_bytes()
assert hashlib.sha256(runner).hexdigest() == frozen['runnerSha256']
dest.mkdir()  # Refuse an existing attempt; never reuse its results.
repo, grakern, out = dest / 'storymodel4s', dest / 'grakern', dest / 'results'
for origin, target, revision in (
    (source, repo, frozen['instrumentedRevision']),
    (grakern_source, grakern, frozen['grakernRevision']),
):
    subprocess.run(['git', 'clone', '--no-hardlinks', str(origin), str(target)], check=True)
    subprocess.run(['git', 'checkout', '--detach', revision], cwd=target, check=True)
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=target, text=True).strip() == revision
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=target)
out.mkdir()
(out / 'run.py').write_bytes(runner)
fresh = {key: frozen[key] for key in (
    'schema', 'baselineProductionRevision', 'instrumentedRevision', 'instrumentationPath',
    'instrumentationSha256', 'grakernRevision', 'runnerSha256',
)}
fresh.update(repository=str(repo), grakernRepository=str(grakern), platforms={},
             qualification='Fresh pre-migration recapture; inspect this attempt independently.')
(out / 'manifest.json').write_text(json.dumps(fresh, indent=2) + '\n')
probes = {'startedEpochSeconds': time.time(), 'platform': platform.platform(),
          'machine': platform.machine(), 'commands': []}
for command in (['/usr/bin/sw_vers'], ['java', '-version'], ['node', '--version'], ['clang', '--version']):
    try:
        p = subprocess.run(command, capture_output=True, text=True)
        probes['commands'].append(dict(command=command, exitCode=p.returncode, stdout=p.stdout, stderr=p.stderr))
    except OSError as error:
        probes['commands'].append(dict(command=command, error=str(error)))
(out / 'runtime-probes.json').write_text(json.dumps(probes, indent=2) + '\n')
print(out)
PY
```

With the build slot available, run the unchanged historical runner using the results path
printed by setup. It refuses existing logs. Preserve any failed attempt and use a new
destination for a retry. Do not infer completion from files left by a partial attempt.

```sh
python3 /private/tmp/wog-recapture-new/results/run.py
```

Only after that command succeeds, derive new receipts from this attempt. This records the
actual sbt welcome lines separately from shell Java/default compiler probes. It compares
newly captured backend artifacts, preserving every difference without choosing a tolerance.

```sh
python3 - /private/tmp/wog-recapture-new/results <<'PY'
from pathlib import Path
import hashlib, json, struct, sys

out = Path(sys.argv[1]).resolve()
manifest = json.loads((out / 'manifest.json').read_text())
assert set(manifest['platforms']) == {'JVM', 'JS', 'Native'}
welcome = {}
for name, result in manifest['platforms'].items():
    assert result['exitCode'] == 0 and result['cleanBefore'] and result['cleanAfter']
    assert len(result['passedTests']) == 3
    log = out / (name.lower() + '.log')
    assert hashlib.sha256(log.read_bytes()).hexdigest() == result['logSha256']
    artifact = out / result['artifact']
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == result['sha256']
    welcome[name] = [line for line in log.read_text().splitlines() if 'welcome to sbt' in line]
    assert welcome[name], name

runtime = {'probes': json.loads((out / 'runtime-probes.json').read_text()),
           'sbtWelcomeLines': welcome,
           'scope': 'Shell Java/default clang probes do not identify sbt Java or the selected Native compiler.'}
def changes(a, b, path=''):
    if type(a) is not type(b):
        return [dict(path=path, left=a, right=b, kind='structure')]
    if isinstance(a, dict):
        if a.keys() != b.keys():
            return [dict(path=path, leftKeys=sorted(a), rightKeys=sorted(b), kind='structure')]
        return [d for k in a for d in changes(a[k], b[k], path + '/' + k)]
    if isinstance(a, list):
        if len(a) != len(b):
            return [dict(path=path, leftLength=len(a), rightLength=len(b), kind='structure')]
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in changes(x, y, path + '/' + str(i))]
    if a == b:
        return []
    result = dict(path=path, left=a, right=b, kind='value')
    if isinstance(a, float):
        result.update(leftBits=struct.pack('>d', a).hex(), rightBits=struct.pack('>d', b).hex())
    return [result]

jvm = json.loads((out / 'jvm.json').read_text())
differences = {name: changes(jvm, json.loads((out / (name + '.json')).read_text()))
               for name in ('js', 'native')}
for filename, value, field in (
    ('runtime.json', runtime, 'runtimeReceipt'),
    ('backend-differences.json', differences, 'backendDifferenceReceipt'),
):
    target = out / filename
    with target.open('x') as stream:
        stream.write(json.dumps(value, indent=2) + '\n')
    manifest[field] = filename
    manifest[field + 'Sha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
PY
```

Compare the new hashes with the frozen per-backend hashes and investigate mismatches.
Retain runtime/compiler differences as limitations; this procedure sets no new Native
cross-OS contract. Fresh runtime and difference receipts live only under the new results
directory. No historical evidence files are copied into it except the input-only manifest
fields and the unchanged runner. The original execution is still bound to its original logs.
