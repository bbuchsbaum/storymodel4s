"""Synthetic frame-bank metadata; no media or narrative fixtures."""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import atomic_caption_probe as probe


class AtomicCaptionOwnership(unittest.TestCase):
    def test_part_mapping_is_checked_before_model_load(self):
        freeze = dict(harnessSha256=probe.sha(probe.__file__), prompt=probe.PROMPT,
                      inputs={"synthetic-bank": "digest"},
                      frames={"source-part": dict(file="synthetic-bank", sha256="digest", count=3)},
                      targets=[dict(row=0, part="source-part", sampleOrdinals=[0, 1, 2])])
        with tempfile.TemporaryDirectory() as td:
            root = Path(td); (root / "outputs.jsonl").touch()
            # The valid control reaches the subsequent overwrite guard without loading a model.
            with patch.object(probe, "read", return_value=freeze), patch.object(probe, "sha", side_effect=lambda p: freeze['harnessSha256'] if str(p)==probe.__file__ else 'digest'):
                with self.assertRaisesRegex(ValueError, "execution already attempted"):
                    probe.run(root)
            for changes in [dict(part="other-part"), dict(sampleOrdinals=[0, 1]),
                            dict(sampleOrdinals=[0, 1, 3])]:
                bad = copy.deepcopy(freeze); bad['targets'][0].update(changes)
                with self.subTest(changes=changes), patch.object(probe, "read", return_value=bad), patch.object(probe, "sha", side_effect=lambda p: freeze['harnessSha256'] if str(p)==probe.__file__ else 'digest'):
                    with self.assertRaisesRegex(ValueError, "unknown source frame bank|unbound frame bank or invalid sampling"):
                        probe.run(root)


if __name__ == "__main__":
    unittest.main()
