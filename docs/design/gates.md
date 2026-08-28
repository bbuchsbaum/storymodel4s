# Quality gates (v0 — provisional)

All thresholds are provisional engineering targets, revisited after five gold
stories and compared with inter-annotator ceilings (design record §32.5).
Blocking gates: G0, G1, G2, temporal acyclicity.

| Gate | Pass condition | Status |
|---|---|---|
| G0 Domain laws | Zero unit/property failures for IDs, spans, endpoints, dimensions, constructors | enforced (M0) |
| G1 Evidence | 100% of accepted explicit claims have valid spans; no dangling evidence | enforced (M0 validators) |
| G2 Hallucination | Severe hallucinations ≤ 1% of accepted situations | M3 |
| G3 Atomic semantics | Situation F1 ≥ .90; role macro-F1 ≥ .85; polarity/modality ≥ .93 | M3 |
| G4 Reference/context | Entity/coreference ≥ .88; context accuracy ≥ .90 | M3 |
| G5 Temporal | Accepted-edge precision ≥ .90; macro-F1 ≥ .80; zero strict cycles | M4 |
| G6 Causal | Accepted-edge precision ≥ .85; macro-F1 ≥ .70; unsupported accepted ≤ 2% | M4 |
| G7 Hierarchy | ±1-clause boundary F1 ≥ .80; ancestor agreement ≥ .80 | M5 |
| G8 Expert usability | ≥ 90% nodes and ≥ 85% accepted edges need no major correction | M6 |
| G9 Recall readiness | Correct target in top 5 for ≥ 95% curated units | M6 |
| G10 Reproducibility | Exact cache replay; uncached accepted-graph stability ≥ .90 | M1/M6 |
| G11 Scaling | No dense event-pair allocation; 10k words < 1 GB excluding weights | M5 |

## Alignment-specific (M0 fixtures, M6 gold)

- Role-swapped and negated foils: external mass > source mass (gated).
- Blends: bimodal P, not a confident single pick.
- Summaries: mass on segment level, not an arbitrary leaf.
- Association ("Stephen King"): Association state, < 0.2 source mass.
- Ablation ladder must be reported: content → +hierarchy → +order → +causality → +external → +sensory.
- Calibration (ECE, Brier) reported per claim family, leave-story-out.
