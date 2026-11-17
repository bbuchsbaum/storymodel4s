# Recorded event and state relation admission

`admit_relations(account, raw_json)` constructs typed relations over independently built
source or recall situations. Legacy accounts use event endpoints; rich v4 accounts admit both
events and states. Proposals bind the exact input account digest and cite exact UTF-16
quotes in its documents. The result retains the processing outcome, accepted proposal-to-ID
mapping and updated account. No provider is invoked.

```python
from storymodel import admit_relations, relation_table, verify_relations

# account is an independently admitted graph; raw_reply is saved provider/proposer JSON.
# result = admit_relations(account, raw_reply)
# verify_relations(account, raw_reply, result)
# rows = relation_table(result.account)
```

Run an abstract, nonnarrative diagnostic from the installed package:

```sh
uv run python examples/relation_workflow.py data/relations-example
uv run storymodel admit-relations data/relations-example/input.json \
  data/relations-example/proposals.json --output data/relations-replayed.json
uv run storymodel relation-table data/relations-example/account.json

# Construct source/recall events and states, then admit and inspect their relations:
uv run python examples/rich_relation_workflow.py data/rich-relations-example
uv run storymodel admit-relations data/rich-relations-example/input.json \
  data/rich-relations-example/proposals.json --output data/rich-relations-replayed.json
uv run storymodel relation-table data/rich-relations-example/account.json
```

The proposal envelope is `storymodel.relation-proposals/v1`, with `account_sha256`, `status`,
optional `reason`, and `relations`. Each relation has a local `key`, typed `kind`, existing event
IDs `source`/`target`, and `evidence` quotes containing `document`, `span` and exact `text`.
For rich accounts, use `storymodel.relation-proposals/v2`: the same fields address event or
state IDs and produce `storymodel.relation-result/v2` with recipe
`situation-relation-admission/v1`. A mismatched proposal/account version refuses explicitly.
The v1 schemas, recipe and outputs remain unchanged. Rich results preserve every original
graph collection and ID; only relations and the derivation recipe change.

Context is derived from the source situation. Multiple quotes preserve discontinuous support;
gaps are never filled by a text hull. No existing graph objects or IDs are rewritten.

The batch is atomic. Bad endpoints, false quotes, duplicate relation identities, incompatible contexts or
cycles reject the entire proposed batch and retain the original account. A cycle spanning
new and existing edges also refuses. Response order cannot select an arbitrary winning edge. Identity includes evidence: two claims
with the same kind/endpoints but different cited supports remain distinct, preserving their
provenance. An identical repeated claim refuses rather than adding duplicate credit.
Malformed or foreign-input envelopes raise `ValueError` because their identity is uncertain.

`completed`, `search-limited`, `not-attempted` and `failed` processing remain distinct. Limited
processing can admit a partial batch with a reason; a failed or unattempted batch cannot carry
claims. A completed empty batch records processing, not evidence that no real relationships
exist. Keep the original account, raw reply and result. `verify_relations` recomputes the entire
receipt; checking its shape alone is insufficient. Account-only export drops processing receipts.

`relation_table` exposes IDs and predicate labels for both endpoints, both contexts and exact
evidence. Rich rows name `source_situation`/`target_situation` with explicit
`source_kind`/`target_kind` values (`event` or `state`); legacy rows retain their event field names.
It is a computation-owned inspection table, not another inferential method. CLI export
checks the producer grant before reading proposals or creating output. All four kinds have
positive and refusal tests on source and recall; tests use abstract graph controls.

## Bounded compatibility contract

The four bounded relation kinds now apply to events and states in rich accounts. This does not
add the full Scala relation vocabulary or change existing extraction/source recording schemas.

| Kind | Current Python rule | Scala work still owed |
|---|---|---|
| `part_of` | Same-context situation relation, acyclic. | Separate entity `PartOf`/`MemberOf`, narrative containment, support/single-parent hierarchy rules. |
| `before` | Same-context directed situation relation, acyclic. | Full canonical Allen relations, interval composition, pair consistency and scoped context semantics. |
| `causes` | Same-context directed situation relation, acyclic. | Additional causal kinds, epistemic status, causal cue checks and explicit cross-context semantics. Scala does not impose this blanket causal DAG. |
| `references` | Same-side situation endpoints; explicit context crossing is allowed. | Typed reference subfamilies and their narrative consistency rules. |

These are deliberate narrow contracts, not Scala parity. Exact quoted evidence establishes
location and account identity, **not semantic entailment**. In particular, this profile does not
infer story-world order from row order or causality from temporal order. Full temporal, causal,
entity and hierarchy capabilities remain P2/P4 obligations. Their richer semantics will need an
explicit version boundary rather than silently weakening older account validators.
