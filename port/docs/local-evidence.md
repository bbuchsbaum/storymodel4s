# Account-derived local evidence and inference

The Python library can now run **independent source and recall accounts → candidate retrieval →
mode gates → local costs → graph transitions → sequence posterior**. Researchers no longer need
to supply a state-cost table or transition matrix for this profile. This implements the
sketch/lexical path; chart-aware scoring and other retained channels remain owed.

```python
from storymodel import LocalEvidenceSpec, RecallSelection, infer_accounts

spec = LocalEvidenceSpec(
    units=tuple(RecallSelection(unit=unit.id) for unit in recall.units)
)
result = infer_accounts(source, recall, spec)
# result.evidence.retrieval, .cells and .sequence explain the stages separately.
# result.inference contains model-conditional posteriors, flows and the best path.
```

`derive_local_evidence` stops before inference; `verify_local_evidence` and
`verify_account_inference` recompute the complete result from the independent input snapshots
and specification. These operations do not change account reference, address or fidelity.
An admitted faithful mode means no applicable contradiction was detected, not that a claim was
proved true. Distorted states remain valid source anchors.

```sh
uv run python examples/account_inference.py data/account-inference-demo
storymodel infer-accounts data/account-inference-demo/source.json \
  data/account-inference-demo/recall.json data/account-inference-demo/spec.json \
  --output data/inference.json
storymodel verify-account-inference data/account-inference-demo/source.json \
  data/account-inference-demo/recall.json data/account-inference-demo/spec.json data/inference.json
storymodel derive-local-evidence data/account-inference-demo/source.json \
  data/account-inference-demo/recall.json data/account-inference-demo/spec.json \
  --request-only --output data/numerical-input.json
```

The example uses abstract tokens and declared graph controls for an ordinary match, a role
swap and a polarity conflict. It makes no provider call and supplies no costs or transitions.
It is engineering evidence, not extraction or alignment accuracy. Source and recall export
grants are checked before the CLI reads the specification; API outputs inherit both grants.

## Content and retrieval

Each `RecallSelection` selects an actual recall unit. With `content="auto"`, one fully contained
situation supplies its sketch; no contained situation uses text-only content. Multiple fully
contained situations require an explicit `situation` or `content="text-only"`, avoiding a
silent first-event choice. Explicit selection must contain **all** situation evidence in that
unit. Automatic lookup uses a document/onset index and checks exact support after lookup.
Unit-local text remains part of lexical evidence when a selected sketch covers only part of it.

Source events/states are situation targets; rows/scenes are segment targets. Declared
`projection.memberships` connects source situations to rows; existing segment parents retain
their hierarchy. Segment participants are aggregated from actual situation descendants, and
segment lemmas include their own support and participant names. A segment has no invented
predicate, polarity, modality or chart. Text position is used only by the separately declared
[transition projection](source-transitions.md), never the content cost.

The shared lexical implementation carries Scala's simple code-point lowercasing, Unicode
letter/digit tokenization, possessive removal, 90 stopwords, 126 irregular forms, Porter
stemming and final-y rule. Python avoids recursive consonant evaluation on long y runs.
This is a generic English fallback, not a synonym model. Unicode-version-wide equality with
every Scala runtime is not claimed.

The named `local-evidence/v1` recipe builds inverted predicate, participant-role/name and lemma
indexes. A recall query visits these in that order. `max_postings` bounds posting visits,
including repeated hits; `max_candidates` bounds the retained population. Retrieval ranks by
lexical Jaccard similarity plus exact predicate agreement plus same-role name-hit fraction.
Ties use anchor IDs. The score only nominates candidates: it is not a local energy or confidence.
The receipt reports posting visits, distinct candidates seen and search truncation. A truncated
search never reports complete absence. Supplying `targets` bypasses retrieval with an explicit,
checked nomination population; this does not assert a reference judgment.

No usable query content produces sole `unranked`. A usable query with no retrieved candidates
produces the five ordinary external states, while retaining `empty` retrieval status. Neither
outcome is copied into categorical reference/fidelity. Explicit empty nominations are also
distinct from abstention. Unknown and duplicate targets are refused rather than silently dropped.

## Gates before prices

The shallow mode gate preserves Scala's role-reversal, polarity, attributed-context and
unrealized-modality rules. Polarity/context/modality comparisons require matching specified
predicates; a complete agent/patient inversion also engages across differing predicates.
Unknown attributes do not conflict. Necessary maps to asserted and probable to possible in
the reduced modality vocabulary, as in the Scala source bridge. Source `Event` has no explicit
polarity/modality fields in this account version; those remain unknown. `State` retains them.

A segment becomes distorted only if **every engaged actual leaf** contradicts the recall.
Engagement means predicate agreement or a detected contradiction. One compatible engaged leaf
keeps the segment faithful; unrelated leaves do not vote. The result retains each member's
engagement and facets, and unions facets only for the all-contradicted case. Empty membership
does not fabricate a leaf. Semantic measurements, weights and temperature cannot admit a
faithful mode after a gate has refused it.

Python deliberately aggregates all participants in a role for comparison. Scala's historical
`byRole.find` can privilege a serialization-dependent first participant. The new recipe is
invariant to same-role participant order. Name overlap is content evidence, not cross-account
entity identity; it never changes entity IDs or coreference.

## Costs, support and explicit defaults

Default weights follow Scala: semantic 1, predicate .5, entity .4, sensory .15, granularity .3,
distortion 1; chart and structural .5 each but ineligible without chart representations.
The predicate term is 0/1 for equal/unequal specified predicates, or a declared .5 default.
Entity credit is 1 for a same-role name match, .5 in another role, 0 otherwise; unspecified
recall participants use a declared .5 cost. `slot_defaults` exposes those constants and the
unassessed-function zero preference. They cannot be mistaken for observed agreements in a
diagnostic table. Their participation in method support follows Scala's existing cost recipe;
that support is not raw observation completeness or a truth probability.

Discourse function is an explicit selection input, default `not-assessed`; it is not guessed
from address, reference or fidelity. Function determines the source prior, preferred level
and natural external destination. An unassessed function adds no prior or level penalty and
prefers no external destination. Distortion costs .3 per detected facet before its weight.
Perceptual detail content supplies a set of normalized sensory terms for its unit; absent
sensory terms remain eligible but missing, never perfect matches. Duplicated terms do not
multiply sensory evidence in this Python profile.

Semantic mode `lexical` measures Jaccard distance, with two empty lemma sets explicitly costing
1 rather than perfect agreement. Mode `table` accepts snapshot-bound per-unit/target estimates
with `observed`, eligible `missing`, or `ineligible` states. Unlisted table cells abstain.
Missing semantic distance is priced with the declared `missing_semantic` constant, but its
weight leaves the measured-support numerator. Ineligible comparisons leave both the priced and
eligible populations. No chart or structural term is fabricated from sketch/lexical scores.

Cost is the weighted sum of present terms scaled by eligible/present weight, plus the function
prior. Only eligible dimensions affect scaling. With only sensory absent at default weights,
the factor is 3.35/3.2. Measured support is measured/eligible weight, excluding semantic
imputation. Zero assessed support excludes the state instead of granting a deceptively low
cost. Historical all-zero configured weights retain their prior-priced state with support
`None`; a provider removing the last positive eligible semantic weight is excluded. Excluded
cells retain diagnostic terms but have no total and cannot enter the numerical state space.

Wide Decimal intermediates avoid overflow/underflow in scaling; published costs and support
must remain representable in binary64. Aggregate weight overflow, nonfinite inputs, and a
positive support fraction that rounds to zero are refused. Posterior inference still uses
the existing [kernel's numerical contract](sequence-inference.md).

## Scope of the evidence

Independent controls pin literal costs/support, reversal/polarity/context gates, segment
membership reductions, candidate budgets, input ownership, zero-support exclusions and
unchanged account artifacts. The installed example/CLI exercise the full computational path.
See [recorded evidence](../evidence/local-evidence.json).

Still owed: chart-aware contradictions and structural reductions, outcome/cause and remaining
content representations, semantic/embedding providers and their execution receipts, nominal
mention parsing, multi-proposition recall composition, richer candidate/refinement methods,
reconstruction, scientific feature/readout consumers and executed Scala comparison artifacts.
The table input is an explicit supplied measurement contract, not proof of a provider run.
This profile does not claim full LocalEvidence or full P4 parity.
