"""Run compiled guard-deletion mutants against one rejecting and one accepting test."""
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "freeze_baseline.py"
CASES = {
    "complete row-locus oracle mismatch": "test_unused_source_row_change_refused_even_with_refreshed_hashes",
    "repeat run artifact bytes differ": "test_two_run_byte_difference_refused",
    "distinct run artifact and log paths required": "test_duplicate_run_receipt_refused",
}
POSITIVE = "test_complete_synthetic_replay_preserves_distinct_outcomes"


class DeleteGuard(ast.NodeTransformer):
    def __init__(self, reason):
        self.reason, self.count = reason, 0

    def visit_Expr(self, node):
        call = node.value
        if (isinstance(call, ast.Call) and isinstance(call.func, ast.Name) and
                call.func.id == "require" and len(call.args) == 2 and
                isinstance(call.args[1], ast.Constant) and call.args[1].value == self.reason):
            self.count += 1
            return ast.copy_location(ast.Pass(), node)
        return self.generic_visit(node)


def main():
    results = []
    source = SOURCE.read_text()
    with tempfile.TemporaryDirectory(prefix="baseline-mutants-") as scratch:
        for number, (reason, negative) in enumerate(CASES.items()):
            transform = DeleteGuard(reason)
            tree = transform.visit(ast.parse(source))
            assert transform.count == 1, (reason, transform.count)
            ast.fix_missing_locations(tree)
            compile(tree, str(SOURCE), "exec")
            mutant = Path(scratch) / f"mutant_{number}.py"
            mutant.write_text(ast.unparse(tree))
            command = [sys.executable, str(HERE / "test_freeze_baseline.py"), "-v",
                       "BaselineSuite." + negative, "BaselineSuite." + POSITIVE]
            result = subprocess.run(command, env={**os.environ, "BASELINE_MODULE": str(mutant)},
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            assert result.returncode == 1 and "Ran 2 tests" in result.stdout, result.stdout
            assert "FAILED (failures=1)" in result.stdout, result.stdout
            assert any(POSITIVE in line and line.endswith(" ... ok")
                       for line in result.stdout.splitlines()), result.stdout
            results.append({"deletedGuard": reason, "rejectingTest": negative,
                            "acceptingTest": POSITIVE, "compiled": True,
                            "tests": 2, "failures": 1, "passes": 1,
                            "logSha256": hashlib.sha256(result.stdout.encode()).hexdigest()})
    print(json.dumps({"sourceSha256": hashlib.sha256(source.encode()).hexdigest(),
                      "mutations": results}, indent=2))


if __name__ == "__main__":
    main()
