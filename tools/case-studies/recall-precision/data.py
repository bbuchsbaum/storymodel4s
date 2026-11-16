"""Dataset facade for the case-study harness.

Case study 1 (ADAPTER_API): the adapter is chosen by the environment variable
CASE_STUDY_DATASET (default "sherlock") and must provide every name in ADAPTER_API. Scripts
import this module as `D` and never reach into a dataset's own helpers, so a new dataset is a new
adapter, not a fork of the scripts.

Case study 2 (CS2_API): code that reads several corpora in one process asks for each by name with
`adapter(name)`, which checks CS2_API. Both corpora provide it; only Sherlock provides the case
study 1 names.
"""

from __future__ import annotations

import importlib
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

ADAPTER_API = (
    "NAME",
    "ROOT",
    "DATA",
    "OUT",
    "TR",
    "FilmRow",
    "Word",
    "development",
    "guard",
    "film_rows",
    "film_speech_rows",
    "film_outline",
    "cast_line",
    "recall_words",
    "units",
    "gold_intervals",
    "gold_scene_at",
    "analyst_dir",
)

CS2_API = (
    "NAME",
    "WORD_CLOCK",
    "development",
    "guard",
    "composition",
    "unit_texts",
    "sessions",
    "session_words",
    "gold_rows",
)


def adapter(name: str, api: tuple = CS2_API):
    """The adapter module for `name`, checked against `api`."""
    mod = importlib.import_module(f"adapters.{name}")
    missing = [n for n in api if not hasattr(mod, n)]
    if missing:
        raise SystemExit(f"adapter {name!r} lacks {missing}")
    return mod


DATASET = os.environ.get("CASE_STUDY_DATASET", "sherlock")
_adapter = adapter(DATASET, ADAPTER_API)
globals().update({n: getattr(_adapter, n) for n in ADAPTER_API})
