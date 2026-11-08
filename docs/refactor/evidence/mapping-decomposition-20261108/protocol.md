# Scene-error decomposition of the frozen 2026-09-23 development run

Mote: `bd-01M6Y6YK1VD58FE7XPKTY0BSSY` (child of `bd-01M5EA1JDF877X4RZPJY6EFJDJ`).
Registered 2026-11-08, before the read. Diagnostic only.

## Question

When the mapper assigns the wrong scene, where does the error arise? Possible stages are
candidate retrieval, local ranking among retrieved candidates, and sequence decoding. Codex's
2026-10-21 diagnostic found that the fixed-basis profile leaves exported source ranks unchanged
on NN03. A scene-gold contrast of that profile against historical scoring is therefore
uninformative, so this readout is run instead.

## Inputs (no new mapper run)

- Stage traces: `data/study/recall-to-video/development-20260923/A/*.stages.json`, the
  registered annotations-only arm, 11 development participants.
- Eligibility and gold scenes: the frozen `development-20260923/support.json` (gold SHA-256
  `68cc307c…`), which was already registered for the 2026-09-23 readout. No new gold file is read.
- Row-to-scene map: the scene-start markers in `data/sherlock/Sherlock_Segments_1000_NN_2017.tsv`
  (column "Scene Segments"). Before the gold is joined, the map must reproduce the trace's
  `assignedScene` for every unit whose `finalAnchor` is a row. Otherwise the script stops.

## Definitions (per gold-eligible unit)

- *Source states* are states whose anchor is a `seg:` or `sit:` ref; `ext:` states are non-source.
- *Local top* is the source state with the largest `localMass`; its scene is the local scene.
- *Nominated scenes* are the scenes of all nominated refs, at any level.
- Classes, mutually exclusive, in this order:
  1. `no-trace`: the unit is abstained or has no source state.
  2. `retrieval-miss`: the gold scene is not among the nominated scenes.
  3. `ranking-miss`: the gold scene is nominated, but the local scene is not the gold scene and
     the final `assignedScene` is not the gold scene.
  4. `decode-rescue`: the local scene is not the gold scene, but the final scene is.
  5. `decode-loss`: the local scene is the gold scene, but the final scene is not.
  6. `hit`: both the local and the final scene equal the gold scene.
- Also reported:
  - local top-1 scene-exact against final scene-exact;
  - *non-source dominance*, meaning the best `ext:` local mass exceeds the best source local mass;
  - the level (row or scene) at which the gold scene was first nominated;
  - within-one-scene versions of local and final accuracy.

## Aggregation

The class shares are reported participant-averaged (the primary weighting, as on 2026-09-23) and
pooled. NN05 is retained with no gold. No intervals, no arm comparison, no parameter selection.

## Ledger and disclosure

This adds one development diagnostic to the scene-gold ledger, taking it from 17 to 18. No
untouched participant output is opened.

Disclosure: during exploration on 2026-11-08, before this registration, the author did two
things:

- opened the raw gold rows for Subject 3 (NN03) to check units u92 and u93;
- printed summary statistics from the raw gold file for all 16 gold subjects, including the
  untouched ones: the row count, the distinct-scene count, monotonicity and the maximum offset.
  No untouched per-unit scene labels were printed.

These summary statistics of untouched gold are a contamination of the untouched partition,
limited to coarse coverage counts, and must be cited wherever an untouched readout is reported.

## Use

The result chooses which stage the next engineering work targets: retrieval, local scoring, or
decoding. It cannot establish an improvement and does not change any default.
