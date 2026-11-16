"""Case study 2: the probabilistic recall-to-movie mapping object (harness, Python only).

Design: docs/plans/2026-11-16-cs2-mapping-object.md (revision 2 and owner decisions).

Modules:
- source:      the (work, occurrence, part, seconds) address and the corpus composition;
- account:     references at native grain, word cells and the account that holds them;
- projections: typed LocationDistribution and CoverageMeasure, marginals, correspondence;
- baselines:   frozen per-corpus reference forecasts;
- scorer:      hierarchical log loss, skill, strata and metric refusal.

Nothing here is corpus-specific; corpora enter through adapters (adapters/<name>.py, CS2_API).
"""
