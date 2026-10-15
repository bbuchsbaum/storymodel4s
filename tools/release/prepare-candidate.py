#!/usr/bin/env python3
"""Generate and validate the exact storymodel4s POM matrix using fixed upstream source overlays.

This prepares metadata only. It neither publishes artifacts nor qualifies public availability.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from preflight import internal_closure, load_matrix, parse_pom, require_matrix


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', type=Path, required=True)
    parser.add_argument('--upstream-staging', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--matrix', type=Path, default=Path(__file__).with_name('candidate-matrix.json'))
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout, text=True):
        raise SystemExit('candidate checkout must be clean')
    staging = args.upstream_staging.resolve()
    upstream = json.loads((staging / 'receipt.json').read_text())
    if upstream.get('status') != 'locally-staged-pending-preflight':
        raise SystemExit('upstream staging must have completed its source tests and POM checks')
    for source in upstream['sources']:
        tree = staging / source['name']
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=tree, text=True).strip()
        if actual != source['commit'] or sha(tree / 'zz-release-preparation.sbt') != source['overlaySha256']:
            raise SystemExit('upstream source or version overlay changed after staging')
        tracked = subprocess.check_output(['git', 'diff', 'HEAD', '--name-only'], cwd=tree, text=True).splitlines()
        untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=tree, text=True).splitlines()
        if tracked != (['build.sbt'] if source['name'] == 'grakern' else []) or untracked != ['zz-release-preparation.sbt']:
            raise SystemExit('unexpected upstream source changes after staging')
    if sha(staging / 'grakern/build.sbt') != upstream['sourceRoutingPatchSha256']:
        raise SystemExit('upstream source routing changed after staging')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    poms = output / 'poms'
    poms.mkdir()
    matrix = json.loads(args.matrix.read_text())
    entries = matrix['coordinates']
    versions = {entry['version'] for entry in entries}
    if len(versions) != 1:
        raise SystemExit('candidate matrix must have one version')
    receipt = {'status': 'running', 'publication': 'not-published',
               'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip(),
               'matrixSha256': sha(args.matrix), 'upstreamReceiptSha256': sha(staging / 'receipt.json'), 'poms': []}
    command = ['sbt', '-batch', f'-Dstorymodel4s.grakern.build={staging / "grakern"}',
               f'set ThisBuild / version := {json.dumps(next(iter(versions)))}',
               *[entry['project'] + '/makePom' for entry in entries]]
    receipt['command'] = command
    log = output / 'make-poms.log'
    try:
        with log.open('w') as stream:
            result = subprocess.run(command, cwd=checkout, stdout=stream, stderr=subprocess.STDOUT, timeout=1800)
        receipt['exit'] = result.returncode
        receipt['logSha256'] = sha(log)
        if result.returncode:
            raise RuntimeError('POM generation failed; see make-poms.log')
        for entry in entries:
            filename = f"{entry['artifactId']}-{entry['version']}.pom"
            paths = list((checkout / entry['moduleDirectory'] / 'target').rglob(filename))
            if len(paths) != 1:
                raise RuntimeError(f'expected one generated {filename}, found {len(paths)}')
            target = poms / filename
            shutil.copy2(paths[0], target)
            receipt['poms'].append({'path': str(target.relative_to(output)), 'sha256': sha(target)})
        candidates = [parse_pom(path) for path in sorted(poms.glob('*.pom'))]
        require_matrix(candidates, load_matrix(args.matrix), 'storymodel4s candidate')
        failures = internal_closure(candidates)
        if failures:
            raise RuntimeError('; '.join(failures))
        receipt['status'] = 'metadata-qualified-pending-public-resolution'
    except Exception as error:
        receipt['status'] = 'failed'
        receipt['error'] = str(error)
    finally:
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"{receipt['status']}: {output / 'receipt.json'}")
    return 1 if receipt['status'] == 'failed' else 0


if __name__ == '__main__':
    raise SystemExit(main())
