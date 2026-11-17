# Sparse candidates and partial reference alignment

The local baseline connects extracted event/detail accounts to source rows through their
source events. Candidate evidence, reference outcome and fidelity remain separate.

From `port/`, run the entire admitted synthetic path into a new directory:

```sh
uv run python examples/alignment_workflow.py data/alignment-demo
uv run storymodel align data/alignment-demo/input-account.json \
  --policy-file data/alignment-demo/policy.json --output data/alignment-check.json
uv run storymodel analyze data/alignment-demo/account.json
```

The example starts with raw recall, records synthetic extraction proposals, resolves the
account, generates candidates and writes partial reference bindings and existing projections.
Its explicit, example-specific predicate alias equates `left` with the source graph's
`placing`. That is an illustrative input assumption, not a general linguistic equivalence
or a discovered paraphrase. With the default empty alias table those details remain unresolved.

Two placement details localize to the first source row. The watching detail remains
unresolved: the retained source graph contains only a placement event, even though its
second text row mentions watching. This exposes incomplete source construction rather than
silently treating source text as an extracted event. No source graph is fabricated to improve
the result. Addresses and inventories stay unchanged. Fidelity remains pending, so neither
matched detail earns definite supported source coverage.

## Candidate generation

`align_account(account, policy=None)` validates the account, then indexes source events by
normalized predicate and `(role, entity label)`. It retrieves the union of matching postings
for each unbound recall event. Case and whitespace are normalized; no stemming, negation
removal, implicit synonyms, embeddings or order priors are used. Alias groups are explicit,
non-overlapping, caller-supplied terms with a stated basis. The Unicode version is recorded.

Only retrieved pairs are evaluated. Each candidate retains:

- Exact predicate match, declared alias match, or no predicate support.
- Matching role/label pairs and their independent source/recall evidence.
- Conflicting labels where a role occurs on both sides with no shared label.
- Root-context compatibility or an explicit context limitation.
- Source rows containing the **whole** event support, and reasons admission failed.

Matching labels never merge entity identities. Scores, probabilities and confidence thresholds
are absent. Diagnostic ordering is deterministic by ID and has no inferential meaning.
Source event IDs define the declared candidate universe; no result implies that the source
graph or lexical retrieval covers all real events or paraphrases.

`max_candidates` defaults to 64. The generator stops after finding budget + 1 distinct hits;
the count is a lower bound when truncated. It retains at most the budget, marks search-limited
and withholds all resolution for that recall event. A seemingly unique retained candidate
cannot become a match after unseen competitors were discarded. Index storage is proportional
to source inputs; there is no dense source-by-recall matrix. Row containment is evaluated
only for retrieved events and cached across recall queries.

## Declared resolution rule

A candidate qualifies only if its predicate matches exactly or through an explicit alias,
at least one participant role/label matches, no shared role has conflicting labels, both
events are in narrated root contexts, and exactly one source row contains its entire support.
Attributed contexts are withheld even when both have the same kind: matching “believed”
labels does not establish who believes what. This is a conservative, versioned **heuristic
reference rule**, not proof of semantic reference or accuracy. Role conflicts can cause false
negatives when recall misstates a participant; that limitation is visible, not called fidelity.

- One qualifying event produces a resolved single-row reference under this rule.
- Multiple qualifying events in different rows remain alternative single-row hypotheses.
- Distinct qualifying events sharing a row remain unresolved: the current row hypothesis
  cannot represent their event-level ambiguity. They are not collapsed into false certainty.
- No qualifying candidate is attempted-unresolved, never external.
- A detail without an attached recall event is not-attempted.
- Retrieval overflow is search-limited, with no outcome or hypotheses.

Reordering and revisiting source events are allowed. There is no one-to-one or monotonic
constraint, and no sequence prior that can conceal weak content evidence. Multiple details
may inherit the reference of their shared recall event without inheriting a fidelity grade.
This profile does not infer joint/broad references, event-level reference targets, source
relations, explicit external destinations, or cross-context correspondence.

Existing referent bindings—including explicit external, unresolved, joint, broad and
alternative outcomes—and all existing assessments are preserved exactly. Narrative bindings
are also preserved. Only missing referent slots are filled. A failed search is never evidence
that content is external. To compare policies, always rerun from the original unaligned
account; this function deliberately does not overwrite a previous binding.

## Outputs and provenance

`AlignmentResult` contains the input account digest, complete policy, source event universe,
event-level candidate evidence, per-detail decisions and the derived ordinary `Account`.
The derived recipe binds the input and policy digests; graph objects, details, entity IDs,
inventories and export permission remain unchanged. It works with `analysis.project` and
the existing `analyze` CLI. Pending fidelity remains pending, not supported or neutral.

`verify_alignment(original, result)` rederives every candidate and binding against the
independently supplied original account. It refuses edited diagnostics, replaced accounts
and incompatible policy/runtime versions. Receipt hashes establish consistency, not author
authentication. Reading an account alone validates its structural relationships; it does
not substitute for verifying the derivation receipt.

```python
from storymodel.alignment import align_account, verify_alignment
from storymodel.analysis import project
from storymodel.artifacts import read_account
from pathlib import Path

original = read_account(Path("input-account.json"))
result = align_account(original)
assert verify_alignment(original, result) == result
analysis = project(result.account)
```

CLI `align` exports the complete result by default; `--account-only` exports the account for
existing consumers. `--policy-file` reads the explicit JSON policy; `--expected-sha256` pins
the original account bytes. Denied export refuses before reading the policy or creating an
output. Existing destinations are not overwritten. `schemas/alignment.json` is generated
from the Python contract. There are no live model calls or new dependencies.

The next useful evidence comes from stronger source construction and independent reference
examples. This baseline does not establish measured mapping accuracy, Scala graph-HSMM
parity, calibration, or faster development than Scala.
