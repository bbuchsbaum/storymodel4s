#!/usr/bin/env python3
"""Exercise the real coreJVM MiMa task and release guard with a synthetic binary baseline.

Use a clean standalone candidate clone. Only sbt session settings and generated build outputs
change. The baseline is a local fixture, never a released storymodel4s artifact.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
from pathlib import Path


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkout', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout, text=True):
        raise SystemExit('the candidate checkout must be clean')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    repository = output / 'maven'
    repository.mkdir()
    for name, method in [('old', 'def keep(n: Int): String = n.toString'),
                         ('same', 'def keep(n: Int): String = n.toString'),
                         ('removed', 'def changed(n: Long): String = n.toString')]:
        (output / (name + '.scala')).write_text('package releasebaselineprobe\nclass StableApi { ' + method + ' }\n')
    receipt = {'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip(),
               'scope': 'synthetic binary contract through actual coreJVM release tasks; not a published baseline',
               'status': 'running', 'commands': []}
    receipt_path = output / 'receipt.json'

    def save() -> None:
        receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')

    def run(label: str, version: str, source: str, commands: list[str], failure: str | None = None) -> None:
        settings = [
            f'set ThisBuild / version := {json.dumps(version)}',
            f'set LocalProject("coreJVM") / Compile / unmanagedSources := Seq(file({json.dumps(str(output / (source + ".scala")))}))',
            'set LocalProject("coreJVM") / Compile / managedSources := Nil',
            f'set LocalProject("coreJVM") / publishTo := Some(Resolver.file("baseline-fixture", file({json.dumps(str(repository))}))(Resolver.mavenStylePatterns))',
            f'set LocalProject("coreJVM") / resolvers += "baseline-fixture" at {json.dumps(repository.as_uri())}',
        ]
        command = ['sbt', '-batch', *settings, *commands]
        log = output / (label + '.log')
        record = {'label': label, 'command': command, 'expectedFailure': failure}
        receipt['commands'].append(record)
        save()
        with log.open('w') as stream:
            try:
                result = subprocess.run(command, cwd=checkout, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
                record['exit'] = result.returncode
            except (subprocess.TimeoutExpired, OSError) as error:
                record['exit'] = None
                record['error'] = str(error)
        record['logSha256'] = sha(log)
        save()
        text = log.read_text()
        if failure is None:
            if record['exit'] != 0:
                raise RuntimeError(f'{label} failed; see {log}')
        elif not isinstance(record['exit'], int) or record['exit'] == 0 or failure not in text:
            raise RuntimeError(f'{label} did not fail for the named property; see {log}')
        if 'Compilation failed' in text or 'ResolveException' in text:
            raise RuntimeError(f'{label} is inconclusive: compile or resolution failure')

    try:
        run('first-baseline', '1.0.0', 'old', ['coreJVM/releaseBaselineCheck', 'coreJVM/publish'])
        run('unchanged', '1.0.1', 'same', ['coreJVM/mimaReportBinaryIssues'])
        run('removed-stable-method', '1.0.1', 'removed', ['coreJVM/mimaReportBinaryIssues'], 'releasebaselineprobe.StableApi.keep')
        message = "must compare with this module's exact 1.0.0 binary baseline"
        for version in ['1.0.1', '1.1.0']:
            run('empty-' + version, version, 'same', ['set LocalProject("coreJVM") / mimaPreviousArtifacts := Set.empty', 'coreJVM/mimaReportBinaryIssues'], message)
        run('wrong-module', '1.0.1', 'same', ['set LocalProject("coreJVM") / mimaPreviousArtifacts := Set("example" % "wrong" % "1.0.0")', 'coreJVM/mimaReportBinaryIssues'], message)
        if subprocess.check_output(['git', 'status', '--porcelain'], cwd=checkout, text=True):
            raise RuntimeError('rehearsal changed the candidate source checkout')
        receipt['status'] = 'passed'
    except Exception as error:
        receipt['status'] = 'failed'
        receipt['error'] = str(error)
    finally:
        save()
    print(f"{receipt['status']}: {receipt_path}")
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
