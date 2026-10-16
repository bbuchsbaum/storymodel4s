"""Retirement courts: the historical entry point must not spend a gold read."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import gold_scene
import risk_coverage as risk

if os.environ.get('RISK_COVERAGE_MUTANT'):
    source = Path(os.environ['RISK_COVERAGE_MUTANT']).read_text()
    exec(compile(source, str(HERE / 'risk_coverage.py'), 'exec'), risk.__dict__)


class RiskRetirementSuite(unittest.TestCase):
    def test_legacy_entrypoint_refuses_before_gold_or_prediction_reads(self):
        stderr = io.StringIO()
        with mock.patch.object(risk, 'load_gold', create=True,
                               side_effect=AssertionError('unexpected gold read')) as gold:
            with mock.patch.object(risk, 'load_scored',
                                   side_effect=AssertionError('unexpected prediction read')) as reports:
                with contextlib.redirect_stderr(stderr):
                    status = risk.main(['risk_coverage.py', 'unread-gold.csv',
                                        'historical-arm', 'unread-reports'])
        self.assertEqual(status, 2)
        gold.assert_not_called()
        reports.assert_not_called()
        self.assertIn('LEGACY_RISK_COVERAGE_RETIRED', stderr.getvalue())
        self.assertIn('No gold has been opened', stderr.getvalue())

    def test_import_is_safe_before_an_explicit_read(self):
        spec = importlib.util.spec_from_file_location('risk_import_control', HERE / 'risk_coverage.py')
        module = importlib.util.module_from_spec(spec)
        with mock.patch.object(gold_scene, 'load_gold',
                               side_effect=AssertionError('unexpected import-time gold read')) as gold:
            spec.loader.exec_module(module)
        gold.assert_not_called()


if __name__ == '__main__':
    unittest.main()
