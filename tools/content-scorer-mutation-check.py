#!/usr/bin/env python3
"""Run S2a-4 and repaired S2a-1 mutants in an isolated clone; retain named failures and restore sources.

The output directory must not exist. A failed compile, absent totals, or failure outside the
named witness is inconclusive. Test/clean forces the inline compile court to be recompiled.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def analyze(output, exit_code, witness, allowed_failures):
    """A kill needs the named assertion, passing siblings, and no unrelated failure."""
    plain = re.sub(r'\x1b\[[0-9;]*m', '', output)
    totals = re.findall(r'Total (\d+), Failed (\d+), Errors (\d+), Passed (\d+)', plain)
    failures = re.findall(r'^==> X \S+Suite\.(.*?) \d+(?:\.\d+)?s\b', plain, re.MULTILINE)
    compile_error = 'Compilation failed' in plain or bool(re.search(r'\[error\] --', plain))
    named_failure = witness in failures
    unrelated = sorted(set(failures) - set(allowed_failures))
    consistent = (len(totals) == 1 and int(totals[0][1]) == len(failures))
    killed = (exit_code != 0 and not compile_error and named_failure and not unrelated and
              consistent and any(int(f) > 0 and int(e) == 0 and int(p) > 0
                                 for _, f, e, p in totals))
    return dict(exit=exit_code, totals=totals, killed=killed, named_failure=named_failure,
                compile_error=compile_error, failures=failures, unrelated_failures=unrelated)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--grakern')
    parser.add_argument('--out')
    parser.add_argument('--self-test', action='store_true')
    parser.add_argument('--only', help='Run one named mutant after an inconclusive attempt')
    args = parser.parse_args()
    if args.self_test:
        good = ('==> X example.ProbeSuite.expected 0.01s failure\n'
                'Failed: Total 3, Failed 1, Errors 0, Passed 2\n')
        unrelated = (good.replace('Failed 1', 'Failed 2').replace('Passed 2', 'Passed 1') +
                     '==> X example.ProbeSuite.unrelated 0.01s failure\n')
        assert analyze(good, 1, 'expected', {'expected'})['killed']
        assert not analyze(unrelated, 1, 'expected', {'expected'})['killed']
        assert not analyze('[error] -- compile error\n' + good, 1, 'expected', {'expected'})['killed']
        assert not analyze(good, 0, 'expected', {'expected'})['killed']
        assert not analyze(good.replace('Failed 1', 'Failed 2'), 1, 'expected', {'expected'})['killed']
        print('mutation receipt analyzer: 5 checks passed')
        return
    if not args.grakern or not args.out:
        parser.error('--grakern and --out are required unless --self-test is used')
    root = Path(__file__).resolve().parents[1]
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    content = 'align/src/main/scala/storymodel4s/align/content.scala'
    scoring = 'align/src/main/scala/storymodel4s/align/contentscore.scala'
    # Each entry gives an exact source site, replacement and expected failing test name.
    mutants = [
        ('M1-unit-ordinal', content,
         '  def byRole(role: SketchRole): Option[ParticipantContent] = participants.find(_.role == role)',
         '  def ordinal: Int = 0\n\n  def byRole(role: SketchRole): Option[ParticipantContent] = participants.find(_.role == role)',
         '*ContentSurfaceSuite', 'UnitContent: public method names and overload counts are pinned', 2),
        ('M2-nested-chart', content,
         '  /** Built on first read: several scorers never look at participants. */',
         '  def hiddenChart: Option[storymodel4s.proposition.PropositionChart[storymodel4s.proposition.CheckState.Checked]] = None\n\n  /** Built on first read: several scorers never look at participants. */',
         '*ContentSurfaceSuite', 'UnitContent: no forbidden type in JVM signatures', 2),
        ('M3-members-iteration', content,
         '  /** How many leaves there are. */',
         '  def iterator: Iterator[TargetContent[O]] = structural.iterator\n\n  /** How many leaves there are. */',
         '*ContentSurfaceSuite', 'Members: public method names and overload counts are pinned', 1),
        ('M4-grain-constructor', content,
         'final class ContentGrain private[align]', 'final class ContentGrain',
         '*ContentUnforgeableSuite', 'ContentGrain: new is refused beside a same-shape control', 1),
        ('M5-participant-constructor', content,
         'final class ParticipantContent private[align]', 'final class ParticipantContent',
         '*ContentUnforgeableSuite', 'ParticipantContent: new is refused beside a same-shape control', 1),
        ('M6-drop-empty-chart', content,
         'SemanticProjection.canonical(ev.chart).map(Some(_))',
         'SemanticProjection.canonical(ev.chart).map(g => Option.unless(g.isEmpty)(g))',
         '*ContentBehaviorLawsSuite', 'a missing chart remains distinct from an observed empty chart', 1),
        ('M7-participant-order', content,
         'if sorted then ps.sorted else ps', 'if sorted then ps else ps',
         '*ContentProjectionSuite', 'canonical content does not move with recall participant order', 1),
        ('M8-member-order', scoring,
         'modeGateWith(u, t, _.sortBy(_.ordinal))', 'modeGateWith(u, t, identity)',
         '*ContentScoringSuite', 'the strict gate orders the same contradictions by content, whatever the storage order', 1),
        ('M9-source-unit', content,
         'private[align] def sourceUnit', 'def sourceUnit',
         '*ContentUnforgeableSuite', 'the sourceUnit historical shortcut is not public', 1),
        ('M10-source-node', content,
         'private[align] def sourceNode', 'def sourceNode',
         '*ContentUnforgeableSuite', 'the sourceNode historical shortcut is not public', 1),
        ('M11-graph-constructor', 'proposition/src/main/scala/storymodel4s/proposition/semantic.scala',
         'final class SemanticGraph[O <: GraphOrder] private (',
         'final class SemanticGraph[O <: GraphOrder] (',
         '*SemanticGraphCourtSuite', 'construction doors are closed on SemanticGraph and ContentCompatibilityReport', 1),
        ('M12-report-constructor', 'proposition/src/main/scala/storymodel4s/proposition/semantic.scala',
         'final class ContentCompatibilityReport private[proposition] (',
         'final class ContentCompatibilityReport (',
         '*SemanticGraphCourtSuite', 'construction doors are closed on SemanticGraph and ContentCompatibilityReport', 1),
    ]
    if args.only:
        mutants = [m for m in mutants if m[0] == args.only]
        if not mutants:
            parser.error("unknown mutant")
    receipts = []
    for name, filename, original, replacement, suite, witness, occurrences in mutants:
        path = root / filename
        before = path.read_bytes()
        source = before.decode()
        if source.count(original) != occurrences:
            raise RuntimeError(f'{name}: source site drift')
        # The first occurrence belongs to UnitContent, the second to TargetContent.
        mutated = source.replace(original, replacement, 1).encode()
        project = 'propositionJVM' if suite == '*SemanticGraphCourtSuite' else 'alignJVM'
        command = ['sbt', '-batch', f'-Dstorymodel4s.grakern.build={args.grakern}',
                   'set ThisBuild / tlFatalWarnings := true', f'{project}/Test/clean',
                   f'{project}/testOnly {suite}']
        receipt = dict(name=name, head=head, file=filename, command=command, witness=witness,
                       original_sha256=hashlib.sha256(before).hexdigest(),
                       mutant_sha256=hashlib.sha256(mutated).hexdigest())
        try:
            path.write_bytes(mutated)
            with (out / f'{name}.log').open('w') as log:
                result = subprocess.run(command, cwd=root, text=True, stdout=log,
                                        stderr=subprocess.STDOUT, timeout=900)
                log.write(f'\nMUTATION_EXIT={result.returncode}\n')
            output = (out / f'{name}.log').read_text()
            allowed = {witness}
            if name == 'M2-nested-chart':
                allowed.add('UnitContent: public method names and overload counts are pinned')
            if name == 'M4-grain-constructor':
                allowed.add('ContentGrain: apply is refused beside a same-shape control')
            if name == 'M5-participant-constructor':
                allowed.add('ParticipantContent: apply is refused beside a same-shape control')
            if name == 'M8-member-order':
                allowed.update({'the strict mode gate does not move with node storage order',
                                'strict inherited contradictions are in content order'})
            receipt.update(analyze(output, result.returncode, witness, allowed))
            killed = receipt['killed']
        finally:
            path.write_bytes(before)
            assert path.read_bytes() == before, f'{name}: restoration failed'
        receipts.append(receipt)
        (out / 'receipts.json').write_text(json.dumps(receipts, indent=2) + '\n')
        print(json.dumps(receipt), flush=True)
        if not killed:
            raise SystemExit(f'{name}: survived or inconclusive; inspect full log')


if __name__ == '__main__':
    main()
