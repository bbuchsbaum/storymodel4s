# Explicit reconstruction

Reconstruction makes declared post-inference choices. It preserves the original local evidence,
state posteriors and categorical reference/fidelity judgments. An ordered reconstruction is not
an independent measurement of recall organization.

The complete installed path is `reconstruct_accounts(source, recall, evidence_spec, spec)`.
It runs the existing account-derived evidence and inference path, then decodes the posterior.
Passing `inference=...` reuses a saved artifact after full recomputation against the inputs.
`verify_account_reconstruction` checks both stages. The saved result includes the complete
original inference artifact and separate reconstruction decisions.

```python
from storymodel import ReconstructionPolicy, ReconstructionSpec, reconstruct_accounts

# source, recall and evidence_spec come from independent account construction.
spec = ReconstructionSpec(
    roots={first_scene.id: 0, second_scene.id: 1},
    policy=ReconstructionPolicy(order="nondecreasing"),
)
result = reconstruct_accounts(source, recall, evidence_spec, spec)
for decision in result.reconstruction.decisions:
    print(decision.unit, decision.anchor, decision.chosen_mass, decision.origin)
```

The runnable [account example](../examples/account_inference.py) uses abstract tokens and writes
all inputs, original inference, reconstruction specification and result. CLI equivalents:

```sh
storymodel reconstruct-accounts source.json recall.json spec.json reconstruction-spec.json \
  --inference inference.json --output reconstruction.json
storymodel verify-account-reconstruction source.json recall.json spec.json \
  reconstruction-spec.json reconstruction.json
```

Both source and recall export grants are checked before the producer reads the specifications
or optional inference file. Verification does not confer export permission. API result grants
are the conjunction of the account grants.

## Grouping and decisions

`roots` maps unique source anchors to unique integer ranks. Actual parentage in the checked
source projection supplies membership. Roots cannot overlap through ancestry. A generic
situation `part_of` edge never establishes primary grouping. Omitted roots leave anchors
ungrouped; zero and negative ranks are valid. Empty grouping is valid.

For every original row, the result records:

- `raw_argmax` and its own state mass, including external destinations;
- `raw_source_maximum`, combining faithful and distorted modes per anchor;
- the chosen `anchor`, its own combined mass, assigned group and decision origin.

Only positive mass participates in source maxima and group populations. The raw state maximum
can still exist in an all-zero supplied row; the source maximum then remains absent. Empty
rows have neither. Ties use Scala-compatible UTF-16 state/anchor key order; ambiguous state
renderings additionally use the existing content-identity tie break.

Policies maximize **additive group mass**, not a product of posteriors or another likelihood:

| Policy | Decision rule |
|---|---|
| `unconstrained` | Choose each row's maximum positive source anchor; no assigned group. |
| `nondecreasing` | Maximize summed group mass with nondecreasing group ranks. Optional forward penalty charges `max(0, next_rank - previous_rank - 1)`. |
| `penalized-backward` | Allow backward movement, charging backward penalty times distance in the sorted **active group index**, not numeric rank difference. Forward movement is free. |

Active groups are those with positive anchored mass anywhere in the input rows. A group with
no mass anywhere is not introduced solely by a fill request. Equal-scoring paths prefer the
lowest final group index, then lowest predecessor indices working backward. If an assigned
group has no positive anchor in a row, the decoder falls back to its raw source maximum;
this may lie outside the assigned group. The origin exposes that fallback.

No implicit historical preset scales the input model. To reproduce the historical Sherlock
transition scaling, construct `LocalEvidenceSpec.weights` from the default `TransitionWeights`,
multiplying `discourse-successor`, `world-time-successor`, `backward` and `long-jump` by 1.5,
and choose `nondecreasing` reconstruction. Keep that evidence specification in the artifact.
This expresses the historical configuration through the existing explicit parameters without
introducing a second inference profile hierarchy. Complete historical workflow parity still
requires retained refinement and all evidence channels.

## Explicit requested filling

Filling is disabled by default. Enable `ReconstructionPolicy(order="nondecreasing",
fill="requested")` and prepare a `ReconstructionFill` using `prepare_reconstruction_fill`.
Each `FillTarget(unit=..., group=..., anchor=...)` must name a recall unit and a level-zero
source leaf in that group. Duplicate unit/group requests refuse.

The request binds complete source and recall digests, the projected source and root ranks.
It cannot be reused after content, projection or grouping changes even if unit IDs match.
Every requested unit must occur in the decoding rows. A supplied request under a disabled
policy refuses, including an empty request. Requested mode with no request performs no fill.

Fill is considered only when the assigned group has no positive anchor in that row. It can
select a noncandidate, which retains zero original mass and `requested-fill` origin. It never
inserts a state, changes any original row or fabricates a probability. Existing positive
in-group evidence takes precedence over requested fill.

## Supplied-weight path and numerical contract

`reconstruct_rows(source, recall, rows, spec, projection=...)` uses the same decoder with
`ReconstructionRow`/`ReconstructionMass`. These inputs are nonnegative finite weights, which
may be empty or subnormalized; they are not certified evidence or calibrated probabilities.
Declared row order is preserved. Units must belong to the independent recall account, and
anchors and their kinds must match the projected source. `verify_reconstruction` recomputes
this path. Use the account wrapper when the claim is specifically model-conditional inference.

The default nondecreasing decoder uses a running maximum: O(rows × active groups) time and
backpointer space, with two score vectors. Penalty variants stream candidate transitions in
O(rows × active groups²) time; no dense all-pairs transition table is allocated. Group ancestry
is memoized. Sparse state rows remain sparse.

Python deliberately uses accurately summed masses and 1200-digit decimal score intermediates
from binary64 inputs, preserving small increments after large accumulated scores. Finite raw
weights, aggregate masses, transition penalties, scores and final accumulations are required;
overflow is a controlled refusal. This improves arithmetic rather than reproducing incidental
Scala rounding. Source identities and input probabilities remain unchanged.

Reference contracts: `align/reconstruction.scala`, `matrix.scala`, `source.scala`, the
`MappingPipeline` consumer and `ReconstructionPolicySuite`. Runtime Scala equivalence is not
claimed merely from reading those implementations. Refinement, richer evidence channels,
organization readouts and scientific qualification remain separate obligations.
