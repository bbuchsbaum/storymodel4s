"""Notebook-style public API: independent accounts, explicit pairing, analysis.

All narrative bytes/proposals reuse the admitted extraction demo. Each section can
be run as a notebook cell. No provider calls, new narrative text or output writes.
"""

# %% Independently obtain a source account (construction does not require recall).
from storymodel import (
    align_account,
    compile_source,
    pair_accounts,
    prepare_extraction,
    project,
    resolve_extraction,
)
from storymodel.demo import RECALL_TEXT
from storymodel.extraction_demo import example_proposal
from storymodel.source_demo import example_source_proposal, example_source_request

compiled = compile_source(example_source_request(), example_source_proposal())
source = compiled.account
assert {c.side for c in source.contexts} == {"source"}

# %% Extract recall independently. This fixture string is an explicitly synthetic reply.
request = prepare_extraction(
    "independent-session", RECALL_TEXT.encode(), export_permission="allowed"
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
