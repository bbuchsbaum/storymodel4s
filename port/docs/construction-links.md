# Connect row-local construction references

`connect_rich_construction(request, construction_raw, links_raw)` connects independently
constructed text situations across observation rows. Link proposals name `(row, key)` pairs;
callers do not manufacture content-addressed event/state IDs. The function rebuilds the
construction from its original request and exact reply, resolves admitted keys, and delegates
the complete batch to the existing [relation admission](relations.md) engine.

This is an adapter into the existing graph and numerical consumers. It does not introduce a
second graph model or change historical construction/relation protocols.

```python
from storymodel import (
    construction_link_prompt, connect_rich_construction,
    relation_table, project_source, verify_connected_construction,
)

# request and construction_raw are the original independent source construction inputs.
prompt = construction_link_prompt(request, construction_raw)
# A supplied/recorded proposer returns links_raw using the prompt's construction_sha256.
connected = connect_rich_construction(request, construction_raw, links_raw)
verify_connected_construction(request, construction_raw, links_raw, connected)
table = relation_table(connected.account)
projection = project_source(connected.account)
```

The `storymodel.construction-links/v1` envelope contains `construction_sha256`, processing
`status`, optional `reason` and `relations`. Each relation has `key`, `kind`, `source`, `target`
and nonempty `evidence`. Endpoints are objects such as `{"row":"row-0","key":"s"}`.
Each evidence item names an endpoint row and a `quote` with a UTF-16 `span` and exact `text`.
Quotes must lie inside that row; multiple fragments preserve discontinuous support. The
original request supplies the actual document identity.

Keys are qualified by row. Repeating `s` in another row never selects the first match, and
repeated predicate text at different offsets retains distinct occurrence identity. Entity,
context and circumstance keys cannot substitute for event/state endpoints. Rejected, missing
or unattempted constructions supply no endpoint. An accepted situation from a partial row
remains usable; the row's partial outcome remains in the saved construction receipt.

The batch binds the complete construction result, including input/reply hashes and processing
outcomes. A changed document, proposal, request policy or even original reply bytes requires a
new binding. The updated account's recipe binds the construction digest, exact link-reply hash
and underlying admission receipt. `verify_connected_construction` rechecks typed objects and
recomputes everything against the original inputs; a valid-looking saved key map is not proof.

## Processing and existing semantics

Malformed or foreign-binding envelopes raise an error. Unknown endpoints, invalid quotes,
context violations, duplicate relation identities and cycles reject the batch atomically,
leaving the original account unchanged. No row-order-dependent winning edge is selected.
`search-limited`, `not-attempted` and `failed` remain distinct, with reasons. A completed empty
batch is a processing result, not evidence that no relation exists.

The result's `status` describes the link pass. Its `construction.resolutions` separately
describes every observation row. An admitted link pass does not complete missing construction
or recall-detail extraction. The account-only CLI option refuses if either link admission or
any construction row is incomplete. Computation may run privately with export denied; prompt
and CLI export operations check permission before reading supplementary input files or writing
outputs.

The retained bounded relation rules are unchanged: `before`, `causes` and generic `part_of`
are same-context directed acyclic relations; `references` permits explicit context crossing
within one account side. No before/causes edge is inferred from row order. Exact quotes prove
location, not entailment. Source projection consumes declared before/causal/reference edges;
generic `part_of` is not primary narrative containment and receives no hierarchy promotion.

Scala's full canonical temporal relations, deeper shared temporal scope, causal epistemic
status/cross-context behavior, entity relations and primary narrative hierarchy remain owed.
Those require versioned semantic extensions rather than weakening these existing contracts.
Native-media link construction and grouping are also subsequent work. This profile accepts
the existing text rich-construction requests v1/v2, not a saved arbitrary or media account.

## Executable path

From the port directory with the package installed:

```sh
python examples/connected_construction.py data/connected-example
storymodel construction-link-prompt data/connected-example/source/request.json data/connected-example/source/graph-proposals.json
storymodel connect-rich data/connected-example/source/request.json data/connected-example/source/graph-proposals.json data/connected-example/source/links.json --output data/connected-result.json
storymodel verify-connected-rich data/connected-example/source/request.json data/connected-example/source/graph-proposals.json data/connected-example/source/links.json data/connected-result.json
```

The example records and replays one source and two recalls, admits their explicit cross-row
links, saves relation tables, pairs the unchanged source with each recall, and runs the existing
source projection and account-inference pipeline. All labels are abstract AA/BB/PP/QQ controls.
The two PP source occurrences remain distinct even though their predicates and row-local keys
match. The example proves connected construction and computation, not provider accuracy or full
Scala parity.

[Verification evidence](../evidence/construction-links.json) includes the installed workflow
and mutation recipes in `port/evidence/construction-links-{installed,mutations}.py`.
