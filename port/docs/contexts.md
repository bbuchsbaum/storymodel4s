# Full context vocabulary

Rich accounts v5 add five context kinds to the existing narrated, reported, believed and
intended vocabulary. Source and recall use the same checked construction path. Contexts
describe where a claim belongs; representing it never promotes it to narrated-world truth.

| Context kind | Holder semantics |
|---|---|
| `narrated` | Root world; no holder |
| `reported`, `believed`, `intended` | Holder-bearing, available in legacy accounts |
| `desire`, `memory`, `imagination` | Holder-bearing, added in v5 |
| `hypothetical`, `counterfactual` | Holderless, added in v5 |

Holderless is different from an unknown, failed or unattempted holder. A holder proposal
for a holderless frame is invalid. [Attribution](attribution.md) derives a holder from recorded
mention candidates and verified first-pass identity; it never guesses the nearest speaker.

## Construct and consume

```python
from storymodel import prepare_rich_construction, compile_rich_construction

request = prepare_rich_construction(
    key, "source", text, rows, full_contexts=True, export_permission="allowed",
)
result = compile_rich_construction(request, recorded_proposal_json)
account = result.account  # storymodel.account/v5
```

`full_contexts=True` selects request/proposal/result v2 and `rich-extractive-admission/v2`.
Without it, the existing v1 constructor still produces v4 accounts. The same `import_rich`
and `replay_rich` functions record and reproduce either version. The CLI adds `--full-contexts`
to `prepare-rich`; no separate execution engine is introduced.

Every row uses exact UTF-16 quotes, local keys and nested/disjoint scopes. A situation must
occupy the deepest applicable scope. The resolver refuses a bad row atomically, preserving
other rows and explicit processing outcomes. Construction does not infer grammatical forms,
holders or recall details; their existing checked producers run separately.

`situation_table`, `attribution_table` and `attributed_identity_roles` consume the expanded
vocabulary. The latter preserves the actual situation context while looking for the nearest
holder-bearing ancestor: it skips hypothetical/counterfactual frames, but stops at an
unresolved holder-bearing frame. A holderless frame directly under narrated has no holder.
Multiple actual placements remain ambiguous, even if an ancestor holder happens to agree.
First-person attachment still requires explicit singular evidence and a document-local holder.

The existing local alignment consumer preserves non-root context diagnostics and refuses to
treat these contexts as root-compatible. It does not yet establish cross-account holder
equivalence or attribution-aware fidelity.

## Compatibility

There is one `RichAccount` model for v4/v5, with one graph validator. `Context` retains its
legacy vocabulary/schema; `ExtendedContext` supplies disjoint new kinds under the same
content-addressed context namespace. V4 refuses extended kinds, in JSON and typed-object
consumption. Legacy v1–v3 `Account` schemas and the v1 proposal schema remain unchanged.

`upgrade_context_account(v4_account)` explicitly produces v5, preserving all graph collections
and IDs while recording the input digest in its new recipe. Pairing v4/v5 rich accounts
produces v5 if either input is v5; it preserves both independent graphs and the conjunctive
export grant. Upgrading does not reinterpret existing contexts.

The combined generated rich JSON Schemas describe both versions. Runtime validation also
checks correlations between account vocabulary, construction version and recipe; JSON Schema
alone does not establish those relationships. Old v1 prompts are byte-identical, and the
pre-change recording under `tests/fixtures/context-v1` replays to its original result. That
fixture was captured at `43a05b65b` before the context implementation, using abstract tokens.

## Executable example

```sh
python examples/context_workflow.py data/context-example
cd data/context-example/source
storymodel replay-rich request.json recording --output replayed.json
storymodel resolve-attributed-identity account.json identity-annotations.json holder-proposals.json --output final.json
storymodel verify-attributed-identity account.json identity-annotations.json holder-proposals.json final.json
storymodel attributed-identity-roles account.json identity-annotations.json holder-proposals.json final.json --output roles.json
```

The example constructs one source and two independent recalls through actual recording/replay,
then admits recall details and pairs each detailed recall with the unchanged source. Each has
the five new kinds, a holderless nested frame and an unresolved held nested frame. It saves
construction, holders, both identity passes, role/situation inspection, detail admission and
alignment artifacts. Identity results bind the original account; the separately saved
`detailed-account.json` is a new snapshot and must not be substituted into their verification.

Abstract token controls establish construction and conditional computation, not linguistic
accuracy. Playback evidence, automatic holder/form acquisition, richer number/addressee/group
inference and attribution-aware assessment remain owed. This slice does not claim full Scala
runtime parity or full P2 completion.
