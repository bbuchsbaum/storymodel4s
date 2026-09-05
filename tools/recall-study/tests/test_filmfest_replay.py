"""A saved replay must fail on changed inputs and on a changed observed stage result."""
import json
from pathlib import Path
import subprocess
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import filmfest_replay as replay
import test_filmfest_diagnostics as fixtures


class ReplayTests(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.DiagnosticsTests(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root=self.fixture.root
        self.labels=self.root/'gold.tsv'
        self.labels.write_text('participant\trun\tmovie\tstart_s\tend_s\nsub-01\trun-01\t1\t0\t2\n')
        self.diagnostics=self.root/'diagnostics'
        command=[sys.executable,str(Path(replay.__file__).with_name('filmfest_diagnostics.py')),
            str(self.fixture.annotation),str(self.labels),str(self.diagnostics),
            '--arm','baseline',str(self.root),str(self.fixture.annotation)]
        subprocess.run(command,check=True,capture_output=True)
        self.manifest=self.root/'manifest.json'
        replay.create(self.diagnostics,self.root,self.fixture.annotation,self.labels,
                      [('baseline',self.root,self.fixture.annotation)],self.manifest)

    def test_saved_output_replay_checks_the_full_join(self):
        result=replay.replay(self.manifest,self.root,self.root/'replayed',run_tests=False)
        self.assertEqual(result['eligibleUnitsByArm'],{'baseline':1})
        self.assertGreaterEqual(result['inputsVerified'],4)

    def test_changed_input_fails_before_success_receipt(self):
        self.fixture.report.write_text(self.fixture.report.read_text()+'\n')
        with self.assertRaisesRegex(ValueError,'changed replay input'):
            replay.replay(self.manifest,self.root,self.root/'replayed',run_tests=False)
        self.assertFalse((self.root/'replayed').exists())

    def test_wrong_stage_expectation_is_not_silently_rebased(self):
        manifest=json.loads(self.manifest.read_text())
        manifest['expectedOverall']['baseline']['finalCorrect']=1
        self.manifest.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError,'saved stage counts changed'):
            replay.replay(self.manifest,self.root,self.root/'replayed',run_tests=False)


if __name__=='__main__': unittest.main()
