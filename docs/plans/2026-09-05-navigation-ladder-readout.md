# Navigation ladder: fixed development readout

Recorded before new gold scoring on 2026-09-05, in response to the owner asking to assess and unify
the handoff `bd-01M1RZB6Z6ZMRMCPXSEFFEE35N`. Inference is already complete at `f4ee65b4`.

- Population: exactly the 11 development participants in the existing `partition.json`; 1,744
  ordered recall units in every arm. No untouched participant is opened for this readout.
- Arms: ladder-content, ladder-hierarchy, ladder-order, ladder-causality, ladder-similarity,
  ladder-full, chosen-shuffled-dev, prior0-chosen-dev. Freeze seven contrasts, each non-full arm
  against ladder-full. Gold-free outcomes first, then the existing scene-exact/within-one rules.
- Gold eligibility and participant mapping stay in `gold_scene.py`; paired changes are participant
  macro differences with its 2,000-draw bootstrap and seed 20260902. Pooled scene accuracy and its
  participant-cluster interval remain separate from those paired macro changes.
- These are seven additional exploratory development contrasts, including identical-result arms;
  none selects a configuration. Previously recorded confirmation remains historical evidence.
- All ladder arms retain the shipped external prior and the existing scene decoder/fill. Content
  therefore means no weighted transition feature, not independent unit selection or no prior.
- Inspection before scoring established that the supposed shuffle retains every recall unit in its
  original order and is byte-identical to the unshuffled blend080-lemmas-dev reports. It is an
  ineffective shuffle, retained only as a historical arm. The prior-zero arm predates scene decode
  and fill; its contrast with ladder-full cannot isolate the prior. Neither is a valid new control.
- Gold-free agreement uses fixed cross-participant text pairs. Its legacy pair-bootstrap intervals
  are descriptive; a constant anchor achieves zero gap, so agreement alone cannot establish accuracy.
- +external and +sensory remain unimplemented rungs. Identical causality/similarity outcomes on this
  source do not establish that these relations are unhelpful on a source that supplies them.

The resulting numbers, immutable input hashes, commands, and the integration gate will be recorded
in the study log and a bound receipt. No inference rerun or parameter sweep is part of this readout.
