#!/usr/bin/env python3
"""Isolated, compiling mutations must fail the named contract while a sibling still passes."""
import json
from pathlib import Path
import py_compile
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[3]
D='tools/recall-study/filmfest_diagnostics.py'
P='tools/recall-study/filmfest_annotation_packet.py'
C='tools/corpus/filmfest_source_court.py'
R='tools/recall-study/filmfest_replay.py'
MUTANTS=[
 ('ignore-replay-input-drift',R,"if gold.digest(local(rel))!=checksum: raise ValueError(f'changed replay input: {rel}')",
  'if False: pass','test_filmfest_replay.ReplayTests.test_changed_input_fails_before_success_receipt'),
 ('silently-rebase-stage-expectation',R,"if actual!=manifest['expectedOverall'][arm['name']]:",
  'if False:','test_filmfest_replay.ReplayTests.test_wrong_stage_expectation_is_not_silently_rebased'),
 ('zero-mass-source-prediction',D,'ordered = sorted((k for k in masses if masses[k] > 0),',
  'ordered = sorted(masses,','test_filmfest_diagnostics.DiagnosticsTests.test_zero_source_mass_is_not_a_source_prediction'),
 ('ignore-trace-binding',D,"if trace.get('reportSha256') != gold.digest(report) or trace.get('sourceInputSha256') != gold.digest(annotation):",
  'if False:','test_filmfest_diagnostics.DiagnosticsTests.test_valid_trace_joins_and_forged_binding_or_mass_is_refused'),
 ('ignore-local-normalization',D,'if not math.isclose(m,weight/norm,rel_tol=1e-10,abs_tol=1e-12):',
  'if False:','test_filmfest_diagnostics.DiagnosticsTests.test_valid_trace_joins_and_forged_binding_or_mass_is_refused'),
 ('hide-anchor-divergence',D,"elif x['posteriorIdentity'] != y['posteriorIdentity']: first = 'posterior_mass; nomination/local_cost_unavailable'",
  "elif x['filmMass'] != y['filmMass']: first = 'posterior_mass; nomination/local_cost_unavailable'",
  'test_filmfest_diagnostics.DiagnosticsTests.test_equal_film_totals_do_not_hide_anchor_mass_divergence'),
 ('ignore-packet-source',P,"if expected_inputs.get(str(annotation.resolve()))!=gold.digest(annotation):",
  'if False:','test_filmfest_protocols.AnnotationProtocolTests.test_packet_refuses_source_or_recall_changed_after_diagnostics'),
 ('invent-independent-annotation',P,"or answers.get('independenceAttested') is not True:",
  'or False:','test_filmfest_protocols.AnnotationProtocolTests.test_unanswered_and_nonindependent_records_never_count_as_labels'),
 ('duplicate-interpretations-as-ambiguity',P,"if len(set(identities))!=len(identities): raise ValueError('duplicate interpretations are not ambiguity')",
  'if False: pass','test_filmfest_protocols.AnnotationProtocolTests.test_duplicate_interpretations_cannot_manufacture_ambiguity'),
 ('admit-unreviewed-correspondence',C,"record.get('status')!='independently-reviewed'",
  'False','test_filmfest_protocols.SourceCourtTests.test_unreviewed_inventory_cannot_become_matched_inputs')]


def main():
    results=[]
    sibling='test_filmfest_diagnostics.DiagnosticsTests.test_duplicate_unit_fails_instead_of_overwriting'
    for name,path,old,new,test in MUTANTS:
        with tempfile.TemporaryDirectory(prefix='filmfest-diagnostic-mutant-') as tmp:
            root=Path(tmp)
            for rel in ('tools/corpus','tools/recall-study'):
                shutil.copytree(ROOT/rel,root/rel,ignore=shutil.ignore_patterns('__pycache__'))
            victim=root/path; original=victim.read_text()
            if original.count(old)!=1: raise ValueError(f'{name}: mutation site is not unique')
            victim.write_text(original.replace(old,new)); py_compile.compile(str(victim),doraise=True)
            command=[sys.executable,'-m','unittest',test,sibling,'-v']
            run=subprocess.run(command,cwd=root/'tools/recall-study/tests',text=True,capture_output=True)
            short=test.rsplit('.',1)[1]
            killed=run.returncode!=0 and f'FAIL: {short}' in run.stderr and 'test_duplicate_unit_fails_instead_of_overwriting (test_filmfest_diagnostics.DiagnosticsTests.test_duplicate_unit_fails_instead_of_overwriting) ... ok' in run.stderr
            results.append({'mutant':name,'compiled':True,'test':test,'exit':run.returncode,
                            'killedByNamedAssertion':killed,'sibling':sibling})
            if not killed:
                print(run.stdout+run.stderr); raise RuntimeError(f'{name}: no discriminating kill')
    print(json.dumps({'mutants':results,'killed':len(results),'total':len(MUTANTS)},indent=2))


if __name__=='__main__': main()
