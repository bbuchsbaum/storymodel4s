"""Compiled scorer mutants must break a named negative and preserve a positive control."""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE / 'gold_scene.py'
POSITIVE = 'test_accepting_identical_complete_arms'
GUARDS = {
    'ARM_UNIT_SET': 'test_extra_identical_unit_refused',
    'ARM_PARTICIPANT_SET': 'test_extra_identical_participant_refused',
    'ARM_INPUT_IDENTITY': 'test_foreign_source_receipt_refused',
    'ARM_UNIT_IDENTITY': 'test_text_onset_and_ordinal_identity_mutations_refused',
    'FROZEN_SUPPORT_CHANGED': 'test_frozen_support_mutation_refused_before_scoring',
    'SUPPORT_EXCLUSION': 'test_impossible_exclusion_refused_even_under_refreshed_synthetic_pin',
    'UNEXPECTED_PARTICIPANT_FILE': 'test_unexpected_tsv_file_refused',
}


class DeleteGuard(ast.NodeTransformer):
    def __init__(self, reason):
        self.reason, self.count = reason, 0

    def visit_Expr(self, node):
        call = node.value
        if (isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and call.func.id == 'require'
                and len(call.args) == 2 and isinstance(call.args[1], ast.Constant)
                and call.args[1].value == self.reason):
            self.count += 1
            return ast.copy_location(ast.Pass(), node)
        return self.generic_visit(node)


def main():
    source = SOURCE.read_text()
    mutants = []
    for reason, negative in GUARDS.items():
        transform = DeleteGuard(reason)
        tree = transform.visit(ast.parse(source))
        assert transform.count == 1, (reason, transform.count)
        ast.fix_missing_locations(tree)
        mutants.append((reason, negative, ast.unparse(tree)))
    for name, negative, old, new in [
        ('drop nonlabels from denominator', 'test_two_unit_wrong_blank_invalid_and_failure_keep_denominator',
         'def rate(pairs, tol=0):', 'def rate(pairs, tol=0):\n    pairs = [(g, p) for g, p in pairs if p is not None]'),
        ('pooled result masquerades as primary', 'test_primary_pooled_ci_and_leave_one_out_independent_answers',
         'return primary, rate(b, tol) - rate(a, tol)',
         'return rate(b, tol) - rate(a, tol), rate(b, tol) - rate(a, tol)'),
    ]:
        assert source.count(old) == 1
        mutants.append((name, negative, source.replace(old, new)))
    results = []
    with tempfile.TemporaryDirectory(prefix='scorer-mutants-') as scratch:
        for index, (name, negative, content) in enumerate(mutants):
            compile(content, str(SOURCE), 'exec')
            path = Path(scratch) / f'mutant-{index}.py'
            path.write_text(content)
            command = [sys.executable, str(HERE / 'tests/test_gold_scene.py'), '-v',
                       'ScorerSuite.' + negative, 'ScorerSuite.' + POSITIVE]
            run = subprocess.run(command, env={**os.environ, 'SCORER_MUTANT': str(path)},
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            assert run.returncode == 1 and 'Ran 2 tests' in run.stdout, run.stdout
            failed = re.search(r'FAILED \(failures=(\d+)\)', run.stdout)
            assert failed and int(failed[1]) >= 1, run.stdout
            assert any(POSITIVE in line and line.endswith(' ... ok') for line in run.stdout.splitlines()), run.stdout
            results.append({'mutation': name, 'rejectingTest': negative, 'acceptingTest': POSITIVE,
                            'compiled': True, 'tests': 2, 'failedAssertions': int(failed[1]), 'passes': 1,
                            'logSha256': hashlib.sha256(run.stdout.encode()).hexdigest()})
    print(json.dumps({'sourceSha256': hashlib.sha256(source.encode()).hexdigest(), 'mutations': results}, indent=2))


if __name__ == '__main__':
    main()
