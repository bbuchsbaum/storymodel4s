"""Notebook-style public API: independent accounts, explicit pairing, analysis.

All narrative bytes/proposals reuse the admitted extraction demo. Each section can
be run as a notebook cell. No provider calls, new narrative text or output writes.
"""

# %% Independently obtain a source account (construction does not require recall).
from storymodel import (
    align_account,
    make_account,
    pair_accounts,
    prepare_extraction,
    project,
    resolve_extraction,
)
from storymodel.extraction_demo import example_proposal, example_request

fixture = example_request()
assert fixture.source is not None
source = make_account(
    documents=fixture.source.documents,
    units=fixture.source.units,
    contexts=tuple(c for c in fixture.source.contexts if c.side == "source"),
    entities=fixture.source.entities,
    events=fixture.source.events,
    export_permission="allowed",
)
assert {c.side for c in source.contexts} == {"source"}

# %% Extract recall independently. This fixture string is an explicitly synthetic reply.
request = prepare_extraction(
    "independent-session", fixture.recall.raw_text.encode(), export_permission="allowed"
)
extracted = resolve_extraction(request, example_proposal())
recall = extracted.account
assert {c.side for c in recall.contexts} == {"recall"}
assert all(entity.origin_document == request.recall.document.id for entity in recall.entities)

# %% Pair without rewriting identities; reference inference is an explicit next operation.
paired = pair_accounts(source, recall)
assert paired.details == recall.details
aligned = align_account(paired)
analysis = project(aligned.account)
assert len(analysis.recall) == 3
# The default lexical rule has no implicit paraphrase aliases or fidelity grades.
assert all(row.reference_outcome == "unresolved" for row in analysis.recall)
print(f"Independent accounts paired: {len(paired.details)} details, no invented source coverage.")
