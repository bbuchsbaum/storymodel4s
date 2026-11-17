# Context holders from recorded candidates

`resolve_context_holders(account, identity_annotations, holder_proposals)` derives holders
without changing the account, its contexts or occurrence IDs. Both recordings are exact JSON
strings. Identity annotations use the existing [text identity](identity.md) contract.
The result retains that complete identity result, the account digest, the exact proposal-byte
digest, attribution origin, export grant and a decision for every context.

```python
from storymodel import resolve_context_holders, verify_attribution, attribution_table

result = resolve_context_holders(account, forms_json, holders_json)
verify_attribution(account, forms_json, holders_json, result)
rows = attribution_table(account, forms_json, holders_json, result)
```

The holder-proposals envelope contains `schema_version: storymodel.holder-proposals/v1`,
`account_sha256`, `origin` and `contexts`. Each completed proposal names a context, a possibly
empty list of candidate **mention occurrence IDs**, and evidence contained in that context's
recorded attribution evidence. This text profile requires the context evidence and candidates
to belong to one document. Candidate mentions can precede the context or occur in another unit;
no nearest-speaker heuristic is applied. The record is an attributed interpretation, not proof
that an exact quotation entails a particular speaker.

The deterministic rule uses every candidate's verified identity:

| Input | Holder result |
|---|---|
| No candidates in a completed proposal | `unattributed / no-candidate` |
| At least one candidate has unresolved identity | `unattributed / unresolved-candidate` |
| All candidates resolve, but to several identity classes | `unattributed / several-candidates` |
| All candidates resolve to one identity class | `named`, with that referent ID |
| Proposal missing | `not-attempted` |
| Explicit processing outcome | `not-attempted`, `failed` or `unsupported`, with its reason |
| Narrated-world context | `not-applicable`; a holder proposal for it is invalid |

Several mentions of the same referent are one holder. A readable candidate beside an unreadable
candidate does not justify picking the readable one. Candidate order does not affect the result.
Invalid or foreign proposals refuse the whole batch; they are not converted into legitimate
abstentions. Result verification recomputes both identity and attribution from the original
recordings, checking typed Python objects before serialization.

`attribution_table` is a checked consumer for events and states. It preserves the full original
context record, role occurrences, evidence and holder decision. The holder addresses a
`TextReferent` in `result.identity.referents`; it does not replace any account-local entity.
Different recall sessions keep independent identities. A change to any account content,
including its export grant, invalidates the input binding. The API can compute privately on
denied accounts; CLI producers check the grant before opening the recordings. The verifier
can check denied artifacts but emits only a verification receipt.

Run the complete abstract construction example and its installed commands:

```sh
python examples/attribution_workflow.py data/attribution-example
cd data/attribution-example
storymodel resolve-holders account.json identity-annotations.json holder-proposals.json --output holders.json
storymodel verify-attribution account.json identity-annotations.json holder-proposals.json holders.json
storymodel attribution-table account.json identity-annotations.json holder-proposals.json holders.json --output rows.json
```

This profile supports the existing `reported`, `believed` and `intended` text contexts in
accounts v1–v4, using the existing identity resolver's capabilities and limits. It does not
silently widen old construction prompts or wire schemas. Additional Scala context families
(desire, memory, imagination, hypothetical and counterfactual), playback attribution,
automatic chart/quotation candidate discovery, first-person/addressee identity consumption,
and attribution-aware assessment remain required work. In particular, the example's declared
first-person mention remains open in the base identity result; this slice provides its holder
input but does not yet apply a new identity rule. No claims are promoted to narrated-world
truth, and no extraction accuracy or executed Scala runtime parity is claimed.
