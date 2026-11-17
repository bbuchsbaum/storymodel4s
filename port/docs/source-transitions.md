# Source-derived sequence transitions

The P4 bridge turns a checked independent source account into a sparse graph view and
transition probabilities for the existing [sequence kernel](sequence-inference.md).
It accepts independent recall units and **explicit local state populations and costs**.
It does not yet derive their admissibility, semantic costs or fidelity.

```python
from storymodel import SourceSequenceSpec, build_source_sequence, infer_sequence

# rows contain SequenceRow values with declared states/costs for actual recall unit IDs.
spec = SourceSequenceSpec(rows=rows)
derived = build_source_sequence(source, recall, spec)
posterior = infer_sequence(derived.request)
```

The runnable abstract-symbol control constructs all inputs and saves each artifact:

```sh
uv run python examples/source_sequence.py data/source-sequence-demo
storymodel build-source-sequence data/source-sequence-demo/source.json \
  data/source-sequence-demo/recall.json data/source-sequence-demo/spec.json \
  --request-only --output data/derived-request.json
storymodel infer-sequence data/derived-request.json --output data/derived-posterior.json
storymodel verify-source-sequence data/source-sequence-demo/source.json \
  data/source-sequence-demo/recall.json data/source-sequence-demo/spec.json \
  data/source-sequence-demo/derived.json
```

Omit `--request-only` to export the full derivation receipt. Source and recall producers must
both permit export; denial is checked before reading the specification. API results propagate
their joint permission. Verification recomputes all fields against independently supplied
source, recall and specification, including complete snapshot digests.

## Projection contract: account-source-layers/v1

- Events and states are situation anchors; source rows and scenes are segment anchors.
  Existing row/scene primary parents remain intact, including nested scenes. Situations have
  level zero, rows at least one and scenes at least two; parents have greater height than
  every child. `memberships` can explicitly attach a situation to one row, provided **all**
  its evidence lies in that row. Nearby spans and generic situation `part_of` do not establish
  narrative containment. These are declared projection memberships, not new accepted claims.
- Discourse succession connects adjacent first occurrences within each document and hierarchy
  level, with content IDs breaking ties. This is an explicit Python text projection recipe;
  it is not a claim to recover every Scala story-side discourse ordering.
- World-time succession is the transitive reduction of accepted `before` relations in the
  narrated context. Attributed-world precedence does not become narrated-world succession.
  Causal and reference layers retain explicit directions. Reference has no transition weight.
- Entity continuity joins shared **entity IDs**, never matching labels. Each entity selects
  at most `entity_fanout` neighbors per situation before symmetrization, with participant-set
  Jaccard weights. Within one document it favors nearest discourse ranks. Other-coordinate
  neighbors use stable document/ID ordering, with same-document neighbors preferred; these
  edges assert shared identity, not distance. Symmetrization and multiple entities can raise
  final degree above the fanout setting. Zero disables this layer.
- Semantic edges are supplied similarities on known anchors, bound in the policy receipt.
  They are sidecars; no embedding backend, calibration or semantic evidence producer is
  supplied by this slice. Duplicate/foreign pairs are refused.
- Positions are absent unless their document is listed in `scoring_documents`. Opting in
  uses the support hull midpoint divided by canonical UTF-16 document length, matching the
  Scala scoring feature. This does not fill gaps in evidence or assert physical/world time.
  Multi-document situations keep their explicit relations and identity continuity, but have
  no single onset, position or discourse edge. Position differences are absent across documents.

The implementation stores sparse edge maps and uses bounded-memory reachability for the
world-time reduction, avoiding Scala's all-pairs closure allocation. Its worst-case repeated
search time is O(E(V+E)); this slice does not claim an asymptotic speed improvement. Transition
construction visits the Cartesian products of **adjacent candidate populations**, as required
by this model, not every pair of account nodes. Diagnostics are cached once per visited anchor
pair. Large unbounded candidate populations remain expensive; the later evidence producer
must expose its candidate policy and limits.

## Transition model

Feature values and default weights follow `align/hsmm.scala`: stay, discourse/world successors,
causal adjacency, hierarchy up/down, shared entity, maximum bidirectional semantic weight,
backward movement and jump length. Directed cause-to-effect/effect-to-cause features are exposed
but carry zero default weight. Backward is suppressed for moves to an ancestor. Missing
position-dependent features are omitted, while a measured zero remains present.

For a source origin, source destinations receive a softmax of their feature scores, multiplied
by one minus the external-entry probability. External destinations share that entry probability.
For an external origin, source destinations are uniform, and external destinations share the
external-stay probability. Both probabilities are sigmoids of explicit logits. Missing logits
mean zero and therefore probability 0.5, not an absent prior. A destination population containing
only externals receives total mass one. Faithful and distorted modes use the same anchor
features, but remain separate destinations in the softmax.

Each destination population must include an external state. The Scala mixture assumes this
population; silently omitting it would lose probability mass. Python refuses that input instead
of renormalizing a different model. A single-row request needs no transition population.
The existing sole-unranked rule remains enforced. Extreme logits compute both probabilities
separately to retain small representable exit mass; binary64 underflow can still make edges zero.
Nonfinite parameters and overflowed scores are refused.

## Evidence and remaining work

Tests independently check feature values, analytical mixtures, tiny enumerated path weights,
coordinate/context boundaries, input tampering and export behavior. The installed example is
a computational control with declared energies, not extraction, localization accuracy, or a
calibrated confidence study. Scala semantics were read from source; no new executed Scala
comparison is claimed. See [evidence](../evidence/source-transitions.json).

Next: derive candidate nominations, mode gates, cost components and measured-support diagnostics
from account evidence, then feed this same producer/kernel. Chart-aware contradictions, semantic
and structural channels, full narrative hierarchy, refinement/reconstruction, other retained
alignment methods and scientific feature/readout consumers remain owed.
