"""Serial exact-candidate disclosure, workspace, corpus and source qualification."""
import json
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[4]
work = root / '.agent-work/workflow-five'
clone = Path('/private/tmp/storymodel4s-s2b3-build')
sha = sys.argv[1]
wrapper = '/Users/bbuchsbaum/.agents/skills/lean-logs/scripts/run_logged.py'
sbt = ['sbt', '-batch', '-Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern',
       'set ThisBuild / tlFatalWarnings := true']

def clean():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=clone, text=True).strip() == sha
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=clone, text=True).strip()

def logged(name, command):
    result = subprocess.run([sys.executable, wrapper, '--log', str(work / (name + '.log')),
                             '--cwd', str(clone), '--timeout', '7200', '--', *command],
                            stdout=subprocess.DEVNULL)
    print(name, 'exit', result.returncode, flush=True)
    assert result.returncode == 0, name

clean()
with (work / 'workspace-reference-scope.txt').open('w') as output:
    subprocess.run(['bash', 'tools/reference-scope.sh', '3f25f999', sha], cwd=clone,
                   stdout=output, check=True)
for prefix in ['privacy', 'workspace']:
    subprocess.run([sys.executable, str(Path(__file__).with_name('mutation-driver.py')),
                    '--clone', str(clone), '--out', str(work / (prefix + '-mutations')),
                    '--sha', sha, '--spec', str(Path(__file__).with_name(prefix + '-mutation-spec.json')),
                    *(['--resume'] if (work / (prefix + '-mutations/mutations.json')).exists() else [])],
                   check=True)
clean()
witness = work / 'workspace-final-witness'
logged('workspace-final-witness', sbt + [
    'codecJVM/Test/clean',
    'codecJVM/testOnly storymodel4s.probes.SurfaceWorkspaceBoundarySuite',
    'pipeline/Test/clean',
    'pipeline/testOnly storymodel4s.probes.RecallDisclosureBoundarySuite',
    'pipeline/Test/runMain storymodel4s.pipeline.StudyWorkspaceWitness ' + str(witness) + ' ' + sha,
    'pipeline/Test/runMain storymodel4s.pipeline.SherlockDisclosureWitness ' +
    str(work / 'sherlock-local-paths.json') + ' ' + str(root / 'docs/data/sherlock/recall-lineage.json')])
logged('workspace-final-reader', [sys.executable, 'examples/mapping/test_workspace.py', str(witness)])
logged('workspace-final-reader-details', [sys.executable, 'examples/mapping/read_workspace.py', str(witness)])
actual = work / 'sherlock-workspace-input'
command = 'pipeline/runMain storymodel4s.pipeline.studyWorkspace run ' + ' '.join(
    str(actual / name) for name in ['study.json', 'mapping.json', 'cache', 'output']) + ' ' + sha
logged('workspace-final-sherlock', sbt + [command])
logged('workspace-final-sherlock-reader', [sys.executable, 'examples/mapping/read_sherlock_workspace.py', str(actual)])
clean()
logged('workspace-final-checkAll', sbt + ['checkAll'])
logged('workspace-final-paired-reader', [sys.executable, 'examples/mapping/test_paired_mapping.py', str(work / 'workspace-final-checkAll.log')])
logged('workspace-final-weighted-reader', [sys.executable, 'examples/mapping/test_weighted_mapping.py', str(work / 'workspace-final-checkAll.log')])
clean()
(work / 'workspace-source-complete.json').write_text(json.dumps({
    'sha': sha, 'source': 'checkAll', 'reader': True, 'publicCorpusDisclosureSources': 17,
    'publicAnnotationRows': 1000, 'publicAnnotationRecalls': 2}) + '\n')
