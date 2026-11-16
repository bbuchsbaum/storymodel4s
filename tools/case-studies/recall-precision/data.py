"""Dataset facade for the case-study harness.

The adapter is chosen by the environment variable CASE_STUDY_DATASET (default "sherlock") and
must provide every name in ADAPTER_API. Scripts import this module as `D` and never reach into a
dataset's own helpers, so a new dataset is a new adapter, not a fork of the scripts.
"""

from __future__ import annotations

import importlib
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ADAPTER_API = (
    "NAME", "ROOT", "DATA", "OUT", "TR",
    "FilmRow", "Word",
    "development", "guard", "film_rows", "film_speech_rows", "film_outline", "cast_line",
    "recall_words", "units", "gold_intervals", "gold_scene_at", "analyst_dir",
)

DATASET = os.environ.get("CASE_STUDY_DATASET", "sherlock")
_adapter = importlib.import_module(f"adapters.{DATASET}")
_missing = [n for n in ADAPTER_API if not hasattr(_adapter, n)]
if _missing:
    raise SystemExit(f"adapter {DATASET!r} lacks {_missing}")
globals().update({n: getattr(_adapter, n) for n in ADAPTER_API})
