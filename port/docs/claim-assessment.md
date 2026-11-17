# Attributed claim assessment with a fixed population

Claim assessment records answers to a declared set of recall/source/facet questions without
changing reference hypotheses or existing fidelity grades. It distinguishes evidence against a
claim from unavailable evidence, and a reviewed nonexpression assertion from failed extraction.

The implementation follows the working accounting contract in Scala `ClaimAssessmentReceipt`
and ADR 0022. A checked receipt establishes physical ownership, complete question accounting
and reproducible status derivation. It does **not** establish semantic truth, assessor identity,
successful procedure execution, complete review or extraction accuracy.

```sh
uv run python examples/claim_assessment_workflow.py data/claim-assessment
uv run storymodel assess-claims data/claim-assessment/plan.json \
  data/claim-assessment/reply.json --output data/claim-assessment/replayed.json
uv run storymodel verify-claims data/claim-assessment/plan.json \
  data/claim-assessment/reply.json data/claim-assessment/replayed.json
```

The example consumes the existing rich source/two-recall workflow, declares seven abstract
diagnostic questions, and produces one instance of each status. The same source evidence can
be cited for several questions without multiplying credit within a question. The mapping file
stays unchanged. This is an engineering control, not evidence that any real recall is accurate.

## Declare the population first

`prepare_claim_assessment(account, questions)` checks a complete legacy or rich account snapshot.
Each `ClaimQuestion` names an existing recall unit, a source target and one of the ten existing
facets: actor, action, object, location, outcome, cause, context, role reversal, polarity or
modality. Source targets may be represented source units (row/scene) or event/state situations.
The target is the declared condition of the question; it is not silently promoted to an accepted
reference. A question need not have a successfully extracted detail.

Questions have content-derived identities. Duplicate semantic questions refuse, rather than
acquiring caller-assigned IDs that inflate the denominator. Their declared order is retained.
`claim_population_digest(plan)` binds that population, order, version and the **full account**,
including its existing mappings and provenance. Assessment success does not change this digest.
The plan is a separate artifact from the reply.

For CLI preparation, supply a JSON array of question objects:

```sh
uv run storymodel prepare-claim-assessment account.json questions.json --output plan.json
```

An empty population is valid and reports zero counts; no fraction is manufactured.

## Submit attributed evidence

The `storymodel.claim-assessment-reply/v1` envelope contains `population_sha256`, `citations`
and `submissions`. It must include exactly one submission per question, including unanswered
and unexpressed cases. Missing, duplicate and foreign submissions refuse the entire reply.

Each `ClaimCitation` names an existing source `origin` and a `Support` with a document ID and
nonempty, sorted, disjoint UTF-16 spans. Every span must lie in the origin's exact support union;
gaps cannot be filled by a hull. The citation origin may differ from the question's assessed
target, as in a cross-scene explanation. The assessor owns that semantic connection; admission
does not prove it. Unknown documents, foreign origins and split surrogate boundaries refuse.

Citation IDs derive from origin and canonical support coordinates. Adjacent splits share an
identity; real gaps remain distinct. Original spans are retained and checked individually, so
merging coordinates for identity cannot hide an invalid UTF-16 split. Duplicate citation IDs
refuse. Reused citations and multiple citations never multiply question counts.

Each `ClaimSubmission` records:

- `recall_state`: expressed, reviewed-not-expressed, extraction-unresolved or reference-unresolved.
- `reviewed`: a support inside the question's recall unit; this is an attributed review scope.
- `claim`: support inside that review scope, required exactly for expressed/reference-unresolved.
- Separate `supporting` and `counterevidence` citation ID sets, and typed `unresolved` reasons.
- `assessor` kind and a versioned `procedure` name. These are attribution, not authentication.

Unavailable or unexpressed claims cannot carry decided evidence. An unanswered expressed claim
needs an unresolved reason. Extraction and reference failures require their corresponding reasons.
Reviewed nonexpression cannot simultaneously mean failed assessment. Extra chart/playback fields
are rejected by this text profile; they are not ignored.

## Derived statuses and counts

Call `assess_claims(plan, raw_reply)` to validate ownership and derive the receipt. Callers do
not choose the final status.

| Inputs | Status |
|---|---|
| Reviewed-not-expressed | `not-expressed` |
| Extraction-unresolved | `extraction-unresolved` |
| Reference-unresolved, even with source evidence | `reference-unresolved` |
| Expressed, with support and counterevidence | `conflicted` |
| Expressed, support only, no unresolved reason | `supported` |
| Expressed, counterevidence only, no unresolved reason | `contradicted` |
| Other lawful expressed submissions | `unresolved` |

Support/counterevidence coexist; neither cancels the other. A context-unresolved assessment
remains unresolved even if support exists. Conflicted remains conflicted even with additional
unresolved reasons. Reference-unresolved takes precedence because the question's reference is
still unsettled.

Every status stays in `counts` and `total_questions`. `expressed_questions` includes expressed
and reference-unresolved submissions; extraction failure cannot establish expression.
`resolved_questions` counts only supported and contradicted. These are equal-question accounting
counts, not participant accuracy, probabilities, source coverage or existing fidelity grades.

Keep the plan, exact raw reply and receipt. `verify_claim_assessment` rederives every decision
and count; shape validation alone is insufficient. Response hashes bind exact bytes, so whitespace
changes provenance even when decisions agree. CLI export grants are checked before reading replies
or writing outputs, and existing destinations are not overwritten.

## Compatibility and remaining work

The Python plan binds the complete account snapshot, including current bindings, instead of
duplicating Scala's method-specific `MappingResult` hierarchy. Existing mapping artifacts,
prompts, IDs, assessments and analysis policies stay unchanged. Semantic question/citation IDs
are derived rather than caller-assigned; this deliberately refuses duplicate descriptions.

This first profile supports exact text evidence. Scala's checked chart ownership/provenance and
anchored media-bundle citations remain owed with the proposition and playback representations.
They cannot be represented as text coordinates without losing their ownership guarantees.
Automatic assessor execution, semantic entailment, the full graded assessor, and the broader
recurring-scene correspondence demonstration are separate remaining work. Unit-only localization
remains a valid route; no graph or neighboring recall is made a prerequisite for it.
