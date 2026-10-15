#!/usr/bin/env python3
"""Prove prepared upstream source checks include the Git index before sbt starts."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

SCRIPT = Path(os.environ.get('PREPARE_CANDIDATE', Path(__file__).with_name('prepare-candidate.py'))).resolve()


def git(tree, *args):
    return subprocess.check_output(['git', *args], cwd=tree, text=True, stderr=subprocess.STDOUT).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


with tempfile.TemporaryDirectory() as temporary:
    work = Path(temporary)
    staging = work / 'upstream'
    staging.mkdir()
    sources = []
    for name in ['candidate', 'graph4s', 'gale', 'grakern']:
        tree = (work if name == 'candidate' else staging) / name
        tree.mkdir()
        git(tree, 'init', '-q')
        (tree / 'build.sbt').write_text('// pinned build\n')
        (tree / 'source.scala').write_text('// pinned source\n')
        git(tree, 'add', 'build.sbt', 'source.scala')
        git(tree, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        if name != 'candidate':
            overlay = tree / 'zz-release-preparation.sbt'
            overlay.write_text('// recorded local version overlay\n')
            sources.append({'name': name, 'commit': git(tree, 'rev-parse', 'HEAD'), 'overlaySha256': sha(overlay)})
    (staging / 'grakern/build.sbt').write_text('// allowed source routing\n')
    (staging / 'receipt.json').write_text(json.dumps({
        'status': 'locally-staged-pending-preflight', 'sources': sources,
        'sourceRoutingPatchSha256': sha(staging / 'grakern/build.sbt')}))
    matrix = work / 'matrix.json'
    matrix.write_text(json.dumps({'coordinates': [{'project': 'coreJVM', 'version': '1.0.0'}]}))
    binary = work / 'bin'
    binary.mkdir()
    marker = work / 'sbt-called'
    (binary / 'sbt').write_text('#!/bin/sh\ntouch "$PREPARE_TEST_MARKER"\nexit 73\n')
    (binary / 'sbt').chmod(0o755)
    env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'], PREPARE_TEST_MARKER=str(marker))

    def run(label):
        output = work / label
        result = subprocess.run([sys.executable, str(SCRIPT), '--checkout', str(work / 'candidate'),
            '--upstream-staging', str(staging), '--output', str(output), '--matrix', str(matrix)],
            env=env, text=True, capture_output=True)
        return result, output

    result, output = run('unchanged')
    assert marker.exists(), result.stderr
    assert json.loads((output / 'receipt.json').read_text())['exit'] == 73
    marker.unlink()
    print('PASS: unchanged prepared sources reach the sbt sentinel', flush=True)
    tree = staging / 'graph4s'
    for label in ['unstaged', 'staged']:
        (tree / 'source.scala').write_text('// untested source edit\n')
        if label == 'staged':
            git(tree, 'add', 'source.scala')
        result, output = run(label)
        assert result.returncode != 0 and 'unexpected upstream source changes' in result.stderr, result.stderr
        assert not output.exists() and not marker.exists(), 'sbt must not start for untested source'
        git(tree, 'restore', '--staged', '--worktree', 'source.scala')
        print(f'PASS: {label} upstream source edit refuses before sbt starts', flush=True)
