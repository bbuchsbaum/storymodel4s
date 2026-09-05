#!/usr/bin/env python3
"""Create or replay a bound saved-output diagnostic set without rerunning ONNX.

Replay verifies source/gold/report/sidecar hashes, reruns the diagnostic joins, checks declared
stage counts and case directions, and runs the fast synthetic contracts. This is saved-output
regression evidence, not a rerun of a changed Scala engine; Scala courts remain the landing gate.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

import filmfest_diagnostics as diag
import filmfest_gold_film as gold


def relative(path, data):
    return str(Path(path).resolve().relative_to(data.resolve()))


def create(diagnostics, data, reference, labels, arms, path):
    receipt = json.loads((diagnostics/'receipt.json').read_text())
    if receipt['schema']!='storymodel4s.filmfestival.diagnostics/v1': raise ValueError('unknown diagnostic receipt')
    for name,checksum in receipt['artifacts'].items():
        if gold.digest(diagnostics/name)!=checksum: raise ValueError('diagnostics changed after receipt')
    if (gold.digest(reference),gold.digest(labels))!=(receipt['referenceSha256'],receipt['goldSha256']):
        raise ValueError('gold/reference differs from diagnostics')
    inputs = {relative(reference,data):gold.digest(reference),relative(labels,data):gold.digest(labels)}
    arm_specs = []
    for name,reports,index in arms:
        if name not in receipt['arms']: raise ValueError('unknown diagnostic arm')
        bound = receipt['arms'][name]['inputs']
        if str(Path(index).resolve()) not in bound: raise ValueError('source index not bound to arm')
        report_paths = [p for p in bound if p.endswith('.tsv') and Path(p).name.startswith('recall-map-')]
        if not report_paths or any(Path(p).parent!=Path(reports).resolve() for p in report_paths):
            raise ValueError('report directory not bound to arm')
        for file,checksum in bound.items():
            if gold.digest(file)!=checksum: raise ValueError('input changed after diagnostics')
            inputs[relative(file,data)] = checksum
        arm_specs.append({'name':name,'reports':relative(reports,data),'source':relative(index,data),
                          'population':receipt['arms'][name]['population']})
    if len({a['name'] for a in arm_specs})!=len(arm_specs) or {a['name'] for a in arm_specs}!=set(receipt['arms']):
        raise ValueError('manifest must account for each arm exactly once')
    summaries = json.loads((diagnostics/'summary.json').read_text())
    comparisons = json.loads((diagnostics/'comparisons.json').read_text())
    manifest = {'schema':'storymodel4s.filmfestival.saved-replay/v1','inputs':inputs,
        'reference':relative(reference,data),'gold':relative(labels,data),'arms':arm_specs,
        'expectedOverall':{name:s['overall'] for name,s in summaries.items()},
        'comparisons':{name:{'counts':c['counts'],'sample':c['sample']} for name,c in comparisons.items()},
        'diagnosticReceiptSha256':gold.digest(diagnostics/'receipt.json'),
        'scope':'saved-output regression and synthetic contracts; no engine rerun or scientific confirmation'}
    with path.open('x') as f: json.dump(manifest,f,indent=2,sort_keys=True)
    return manifest


def replay(manifest_path, data, output, run_tests=True):
    started=time.monotonic()
    manifest=json.loads(manifest_path.read_text())
    if manifest['schema']!='storymodel4s.filmfestival.saved-replay/v1': raise ValueError('unknown replay schema')
    def local(rel):
        path=(data/rel).resolve()
        if not path.is_relative_to(data.resolve()): raise ValueError('replay input escapes data root')
        return path
    for rel,checksum in manifest['inputs'].items():
        if gold.digest(local(rel))!=checksum: raise ValueError(f'changed replay input: {rel}')
    labels=gold.load_gold(local(manifest['gold']))
    ranges=gold.film_ranges(local(manifest['reference']))
    results={}
    for arm in manifest['arms']:
        rows,receipt=diag.load_arm(local(arm['reports']),local(arm['source']),labels,ranges)
        if receipt['population']!=arm['population']: raise ValueError('replay recall population changed')
        actual=diag.summarize(rows)['overall']
        if actual!=manifest['expectedOverall'][arm['name']]:
            raise ValueError(f"saved stage counts changed: {arm['name']}")
        results[arm['name']]=rows
    for name,expected in manifest['comparisons'].items():
        before,after=name.split('__')
        actual=diag.compare(results[before],results[after])
        if actual['counts']!=expected['counts'] or actual['sample']!=expected['sample']:
            raise ValueError('saved paired directions or diagnostic sample changed')
    output.mkdir(parents=True,exist_ok=False)
    tests=None
    if run_tests:
        command=[sys.executable,'-m','unittest','discover','-s',str(Path(__file__).parent/'tests'),'-v']
        with (output/'tests.log').open('w') as log:
            completed=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
        tests={'command':command,'exit':completed.returncode}
        if completed.returncode: raise RuntimeError('replay synthetic contracts failed; see tests.log')
    receipt={'schema':'storymodel4s.filmfestival.replay-receipt/v1','manifestSha256':gold.digest(manifest_path),
        'inputsVerified':len(manifest['inputs']),'eligibleUnitsByArm':{k:len(v) for k,v in results.items()},
        'tests':tests,'elapsedSeconds':time.monotonic()-started,
        'implementation':{p.name:gold.digest(p) for p in (Path(__file__),Path(diag.__file__),Path(gold.__file__))}}
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n')
    return receipt


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='command',required=True)
    build=sub.add_parser('create')
    for name in ('diagnostics','data','reference','gold','output'): build.add_argument(name,type=Path)
    build.add_argument('--arm',nargs=3,action='append',required=True)
    run=sub.add_parser('run')
    for name in ('manifest','data','output'): run.add_argument(name,type=Path)
    a=p.parse_args()
    if a.command=='create': create(a.diagnostics,a.data,a.reference,a.gold,a.arm,a.output)
    else: print(json.dumps(replay(a.manifest,a.data,a.output),indent=2))
