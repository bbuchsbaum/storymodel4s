# Rich situation candidates and reference diagnostics

`align_account` accepts rich v4 accounts through the same sparse local engine as the
[legacy event baseline](alignment-workflow.md). State-linked details can now nominate source
states and receive row-reference hypotheses. Existing graph collections and prior bindings
remain intact. This extends a conservative heuristic; it does not establish semantic accuracy
or full Scala alignment parity.

```sh
uv run python examples/rich_alignment_workflow.py data/rich-alignment
uv run storymodel align data/rich-alignment/recall-b/input-account.json \
  --output data/rich-alignment/replayed.json
```

The example constructs a reusable source and two recalls from saved abstract proposals,
admits their expressed details, and runs alignment plus analysis. One recall has matching
declared polarity; the other differs. Both localize under the declared predicate/role rule,
the difference remains visible, and neither earns supported coverage because fidelity is
still pending. No provider call or new narrative text is required.

## Retrieval and reference

The rich recipe `local-situation-predicate-role-reference/v1` indexes source situations by
**kind plus predicate**, and **kind plus role/label**. A state query searches only states; an
event query searches only events. This restriction precedes the candidate budget, so unrelated
event postings cannot truncate a state query. The result declares separate event and state
source universes once, rather than copying them into every recall group.

Within that universe, the original rule applies: predicate agreement or a declared alias,
matching participant role/label, no conflicting shared role, root-context compatibility, and
one containing source row. These are sufficient-support gates for this named heuristic,
not impossibility claims. Real reference can cross representation kinds, misstate roles or
involve attributed contexts; this baseline can withhold those cases. The fuller structured
and distorted-mode capabilities remain owed.

Retrieval overflow is search-limited and withholds resolution. Multiple eligible situations
in distinct rows remain alternatives. Distinct situations sharing one row remain unresolved;
the row-reference vocabulary cannot express their occurrence-level ambiguity. No candidate
means unresolved, never external. The target grain remains rows, with situation IDs retained
in diagnostics. Existing references and assessments are preserved exactly.

## Qualifiers are separate evidence

Each rich candidate records declared source/recall polarity and modality plus a comparison:

| Comparison | Meaning |
|---|---|
| `same-declared` | Both states supply the same non-unknown value. |
| `different-declared` | Both states supply different non-unknown values. |
| `unknown` | At least one state explicitly has unknown information, including unknown/unknown. |
| `not-applicable` | Event endpoints do not have these state attributes; values are null. |

These diagnostics do not select, rank or veto reference hypotheses. They do not create an
assessment, probability or calibrated confidence. Matching and differing qualifiers can coexist
among alternatives; a polarity match cannot silently choose an otherwise ambiguous reference.

Each side's circumstances retain their owner, content-derived ID, kind, label and exact
supports, sorted by ID for stable inspection. Repeated labels with different evidence remain
separate occurrences. There is no positional pairing, inferred correspondence or measured-time
conversion. Missing circumstances mean unavailable annotation, not demonstrated absence.

## Artifacts and verification

Rich results use `storymodel.alignment-result/v2`, `situations`, `source_universes` and
per-detail `source_situations`. Candidate fields name `source_situation` and its `kind`.
The full `RichAccount` is embedded; only missing bindings and the derivation recipe change.
The existing policy schema and all legacy v1 result fields, schemas and hashes remain unchanged.

`verify_alignment(original, result)` rederives the complete receipt from the supplied account
and recorded policy. Typed objects are checked before serialization; raw JSON retains strict
duplicate-key/nonfinite handling. Editing a qualifier diagnostic is not authenticated by a
shape check: deterministic recomputation must agree. CLI export checks precede policy reads
and output creation, and existing destinations are not overwritten.

Remaining work includes richer semantic channels, event/scene/episode target grains,
joint/broad construction, explicit external decisions, hypothesis-conditional graded assessment,
account-derived sequence transitions and the full correspondence demonstration. The current
role-conflict veto is narrower than Scala's distorted-mode path, which can retain a reference
anchor despite contradiction. This local recipe does not replace that capability.
