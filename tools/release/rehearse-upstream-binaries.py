#!/usr/bin/env python3
"""Resolve staged upstream binaries in empty caches and execute an external Scala consumer."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading

from preflight import (CENTRAL, command_receipt, external_closure, internal_closure,
                       load_matrix, parse_pom, pom_paths, report_jars, require_matrix, sha256)


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staging', type=Path, required=True)
    parser.add_argument('--coursier', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    staging, coursier, output = args.staging.resolve(), args.coursier.resolve(), args.output.resolve()
    upstream = json.loads((staging / 'receipt.json').read_text())
    if upstream['status'] != 'locally-staged-pending-preflight':
        raise SystemExit('require completed source tests and local staging')
    repository = staging / 'maven'
    for artifact in upstream['artifacts']:
        if sha256(repository / artifact['path']) != artifact['sha256']:
            raise SystemExit('staged artifact changed after qualification')
    poms = [parse_pom(p) for p in pom_paths([repository])]
    require_matrix(poms, load_matrix(staging / 'matrix.json'), 'upstream binaries')
    if internal_closure(poms):
        raise SystemExit('incomplete staged dependency closure')
    output.mkdir(parents=True, exist_ok=False)
    fixture = Path(__file__).with_name('UpstreamBinaryConsumer.scala').resolve()
    receipt = {'status': 'running', 'publication': 'not-published', 'commands': [],
               'upstreamReceiptSha256': sha256(staging / 'receipt.json'),
               'coursierSha256': sha256(coursier), 'fixtureSha256': sha256(fixture)}
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(repository)))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        url = f'http://127.0.0.1:{server.server_port}'
        commands, failures = external_closure(coursier, poms, output, url, 180)
        receipt['commands'] = commands
        if failures:
            raise RuntimeError('; '.join(failures))
        # Resolve one combined graph for compilation; a union of separate fetches could include
        # conflicting versions of the same transitive artifact.
        runtime_cache = output / 'consumer-cache'
        runtime_report = output / 'consumer.json'
        coordinates = sorted(pom.coordinate.gav for pom in poms)
        command = [str(coursier), 'fetch', '--no-default', '--repository', url,
                   '--repository', CENTRAL, '--json-output-file', str(runtime_report),
                   '--json-report-add-urls', *coordinates]
        combined = command_receipt(command, runtime_cache, output / 'consumer-config', output / 'consumer-fetch.log', 180)
        receipt['commands'].append(combined)
        if combined['exit'] != 0:
            raise RuntimeError('combined binary dependency resolution failed')
        combined['jars'] = report_jars(runtime_report, runtime_cache, [url, CENTRAL], False)
        combined['reportSha256'] = sha256(runtime_report)
        for coordinate in coordinates:
            roots = [jar for jar in combined['jars'] if jar['coordinate'] == coordinate]
            if not roots or any(not jar['url'].startswith(url + '/') for jar in roots):
                raise RuntimeError(f'combined graph omits staged root {coordinate}')
        runtime = [jar['path'] for jar in combined['jars']]
        cache, config = output / 'compiler-cache', output / 'compiler-config'
        report = output / 'compiler.json'
        command = [str(coursier), 'fetch', '--no-default', '--repository', CENTRAL,
                   '--json-output-file', str(report), '--json-report-add-urls',
                   'org.scala-lang:scala3-compiler_3:3.7.4']
        compiler = command_receipt(command, cache, config, output / 'compiler-fetch.log', 180)
        receipt['commands'].append(compiler)
        if compiler['exit'] != 0:
            raise RuntimeError('compiler resolution failed')
        compiler['jars'] = report_jars(report, cache, [CENTRAL], True)
        compiler['reportSha256'] = sha256(report)
        compiler_cp = ':'.join(jar['path'] for jar in compiler['jars'])
        runtime_cp = ':'.join(runtime)
        classes = output / 'classes'
        classes.mkdir()
        for label, command in [
            ('compile', ['java', '-cp', compiler_cp, 'dotty.tools.dotc.Main', '-classpath', runtime_cp,
                         '-d', str(classes), str(fixture)]),
            ('run', ['java', '-cp', str(classes) + ':' + runtime_cp, 'UpstreamBinaryConsumer'])]:
            record = command_receipt(command, cache, config, output / f'{label}.log', 180)
            receipt['commands'].append(record)
            if record['exit'] != 0:
                raise RuntimeError(f'binary consumer {label} failed')
        receipt['status'] = 'local-binary-consumer-qualified'
        receipt['assertions'] = 6
    except Exception as error:
        receipt['status'] = 'failed'
        receipt['error'] = str(error)
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
        receipt['serverClosed'] = True
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"{receipt['status']}: {output / 'receipt.json'}")
    return 0 if receipt['status'] == 'local-binary-consumer-qualified' else 1


if __name__ == '__main__':
    raise SystemExit(main())
