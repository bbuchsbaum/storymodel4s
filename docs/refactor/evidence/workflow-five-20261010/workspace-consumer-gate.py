"""Qualify the exact local producer/consumer pair and close every owned browser."""
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[4]
work = root / '.agent-work/workflow-five'
source = Path('/private/tmp/storymodel4s-s2b3-build')
consumer = Path('/private/tmp/storyatlas4s-study-workflow')
producer_sha, consumer_sha = sys.argv[1:]
wrapper = '/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
witness = work / 'workspace-final-witness'
fixtures = json.loads((witness / 'workspace-fixtures.json').read_text())
assert fixtures['producerRevision'] == producer_sha

def exact():
    for checkout, sha in [(source, producer_sha), (consumer, consumer_sha)]:
        assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip() == sha
        subprocess.run(['git', 'diff', '--exit-code', 'HEAD'], cwd=checkout, check=True)

def logged(name, cwd, command):
    result = subprocess.run([sys.executable, wrapper, '--log', str(work / (name + '.log')),
                             '--cwd', str(cwd), '--timeout', '7200', '--', *command],
                            stdout=subprocess.DEVNULL)
    print(name, 'exit', result.returncode, flush=True)
    assert result.returncode == 0, name

def sbt(packet):
    return ['sbt', '-batch', '-Dstoryatlas4s.storymodel4s.build=' + str(source),
            '-Dstorymodel4s.grakern.build=/private/tmp/storymodel4s-workflow-grakern-pin',
            '-Dstoryatlas.surfaceWorkspace.fixture=' + packet,
            'set ThisBuild / tlFatalWarnings := true']

exact()
assert producer_sha in (consumer / 'build.sbt').read_text()
logged('workspace-final-consumer', consumer, sbt(fixtures['text']) + [
    'compileAll', 'testAll', 'app/fastLinkJS',
    'cli/run edition --out target/edition', 'app/editionBundle',
    'scalafmtCheckAll', 'scalafmtSbtCheck'])
logged('workspace-final-consumer-timed', consumer, sbt(fixtures['timed']) + [
    'shellJVM/testOnly storyatlas4s.shell.SurfaceWorkspaceControllerSuite'])
actual = work / 'sherlock-workspace-input/output'
actual_manifest = json.loads((actual / 'manifest.json').read_text())
assert len(actual_manifest['files']) == 2
for index, entry in enumerate(actual_manifest['files'], 1):
    logged('workspace-final-consumer-sherlock-' + str(index), consumer,
           sbt(str(actual / entry['path'])) + [
               'shellJVM/testOnly storyatlas4s.shell.SurfaceWorkspaceControllerSuite'])
exact()
audit = ['node', '/Users/bbuchsbaum/.local/share/agent-policy/browser-automation-guard.mjs', '--audit']
browser_node = ['npm', 'exec', '--offline', '--yes', '--package=node@22.20.0', '--', 'node']
logged('workspace-browser-before', consumer, audit)
try:
    logged('workspace-browser-existing', consumer, browser_node + ['app/smoke/smoke.cjs', 'target/edition/index.html'])
    logged('workspace-browser-ordinary', consumer, browser_node + [str(Path(__file__).with_name('workspace-browser-smoke.cjs')),
                                                 str(consumer), str(witness), str(work / 'workspace-browser')])
finally:
    logged('workspace-browser-after', consumer, audit)
exact()
completion = json.loads((work / 'workspace-source-complete.json').read_text())
assert completion['sha'] == producer_sha
completion.update(consumer=consumer_sha, consumerSourcePin=producer_sha,
                  actualCorpusConsumerPackets=2,
                  browserControlNode='22.20.0',
                  browser='existing smoke and four ordinary producer packets; visual review separate')
(work / 'workspace-gate-complete.json').write_text(json.dumps(completion) + '\n')
