#!/usr/bin/env python3
"""Read a corpus descriptor, so the scorers stop carrying corpus constants.

Schema `storymodel4s.corpus.descriptor/v1`, emitted by `corpusIntake`. This module is the Python
side of ADR 0018's descriptor decision.

Why this exists. Measured 2026-09-17, four part-offset maps live in this tree under two names and
two incompatible meanings, with nothing declaring which is which:

  score.py:37     PART_OFFSET media-part-b = 100000.0   an order-preserving SENTINEL
  matched.py:14   PART        media-part-b = 100000.0   the same sentinel
  agreement.py:28 PART        media-part-b = 1426.0     a REAL time, because it measures gaps
  filmfest_gold_film.py:23    run-02       = 1490.0     a fourth map in a fourth key space

The sentinels are correct for what they do -- both feed only Kendall tau-b, a rank statistic where
any monotone offset works -- and wrong for anything that measures a duration. One name, two
semantics. Here they are two DECLARED kinds, and the ambiguity stops being representable.

The in-tree precedent for reading this from disk is `agreement.py:31-45`, which already loads
`parts.json` and falls back to a literal only when it is absent. That is the only data-driven seam
in this tree and it belongs to the agreement scorer; this generalizes it rather than inventing a
mechanism.
"""
import json
import os

SCHEMA = "storymodel4s.corpus.descriptor"
SCHEMA_VERSION = 1

# Two kinds, because "part offset" meant two things.
ORDERING_ONLY = "ordering-only"   # a monotone sentinel; valid for rank statistics ONLY
ELAPSED_TIME = "elapsed-time"     # a real offset in seconds; valid for durations and gaps


class DescriptorError(Exception):
    """A descriptor that cannot be used. Never a silent default."""


def load(path):
    """Loads and checks a descriptor. Refuses rather than falling back."""
    if not os.path.exists(path):
        raise DescriptorError(f"no descriptor at {path}")
    with open(path, encoding="utf-8") as handle:
        doc = json.load(handle)
    schema = doc.get("schema")
    if schema != SCHEMA:
        raise DescriptorError(f"schema is {schema!r}, expected {SCHEMA!r}")
    version = doc.get("schemaVersion")
    if version != SCHEMA_VERSION:
        raise DescriptorError(f"schema version is {version!r}, expected {SCHEMA_VERSION}")
    return doc


def part_offsets(descriptor, kind):
    """The part offsets of a declared KIND.

    Asking for `elapsed-time` when only ordering sentinels are declared raises, rather than handing
    back numbers that will silently produce a meaningless duration.
    """
    if kind not in (ORDERING_ONLY, ELAPSED_TIME):
        raise DescriptorError(f"unknown offset kind {kind!r}")
    offsets = descriptor.get("partOffsets", {})
    if kind not in offsets:
        raise DescriptorError(
            f"this corpus declares no {kind} part offsets; "
            f"declared: {sorted(offsets) or 'none'}"
        )
    return {k: float(v) for k, v in offsets[kind].items()}


def gold_rule(descriptor):
    """The gold-eligibility parameters: TR, excluded participants, the id pattern."""
    rule = descriptor.get("goldRule")
    if rule is None:
        raise DescriptorError("this corpus declares no gold rule")
    missing = [k for k in ("trSeconds", "excludedParticipants", "participantPattern") if k not in rule]
    if missing:
        raise DescriptorError(f"gold rule is missing {', '.join(missing)}")
    return rule
