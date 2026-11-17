# Text mention identity without rewriting the graph

`resolve_text_identity(account, raw_annotations)` returns an account-bound identity sidecar.
It preserves local mention/entity IDs and every event, role and context. Accepted referent
classes are separate, content-derived records. Open mentions receive no referent ID.
`identity_role_table` verifies the sidecar against its inputs before exposing role, mention,
context, referent, candidates and exact evidence to a notebook or inspector.

```sh
uv run python examples/identity_workflow.py data/identity-example
uv run storymodel resolve-identity data/identity-example/account.json \
  data/identity-example/annotations.json --output data/identity-replayed.json
uv run storymodel identity-roles data/identity-example/account.json \
  data/identity-example/annotations.json data/identity-example/identity.json
```

The example uses an abstract two-token control, explicit grammatical annotations and source
construction proposals. It saves the inputs and outputs for both stages. There is no provider
call or extraction accuracy claim. Existing source and extraction recording formats are unchanged.

## Annotation boundary

An `identity-annotations/v1` envelope contains the exact `account_sha256`, an `origin`, and
`mentions`. Each annotation names an existing local entity occurrence and declares `status`:
`provided`, `not-attempted`, `failed` or `unsupported`. Provided annotations have a `form` and an
exact UTF-16 `quote` inside that occurrence's evidence. Form is `name`, `nominal`, `pronominal`
or `other`. Pronouns explicitly carry grammatical `person` and `number`; an unrecognized form
has a reason. Named/nominal forms may supply a typed entity family, including an explicitly
namespaced custom type. Unknown type is absence, not a shared invented class.

These are recorded grammatical interpretations. Quote checking proves their location, **not
their grammatical correctness**. Python labels do not contain Scala's checked chart concepts;
an unrecognized label therefore never silently becomes a nominal. The first slice consumes
annotations rather than providing an automatic form classifier.

Every account mention gets an outcome. Missing, unsupported or malformed annotations remain
open with distinct typed reason codes and optional explanation. Duplicate/foreign mention keys
or malformed/foreign envelopes refuse the whole input. Multi-span or multi-support aggregate
entities are open as `nonlocal-mention-support`: this profile expects local occurrences, not
an already collapsed canonical entity disguised as a single mention.

## Resolution rules

- Names and nominals with the same Unicode simple-lowercased label and explicit entity type
  cluster within one document under `introducing-label/v1`. Without type information they
  introduce separate singletons. No source/recall, session, edition or cross-modal union occurs.
- A third-person pronoun joins a class only when exactly one introducing class precedes it.
  Antecedent quantity is unknown in this profile and therefore compatible with any pronoun
  number. Zero or several candidates stay open; no nearest-antecedent tie-break is applied.
- Missing or unreadable earlier form metadata can conceal another antecedent. It blocks a
  supposedly unique result with `incomplete-antecedent-input`, retaining the known candidates.
  Unknown *number* broadens compatibility; missing *form* does not remove a candidate silently.
- Ordering uses original UTF-16 mention support, not annotation-array order, object-ID order,
  or a later subquote chosen by the annotator. An introducing occurrence must end before the
  pronoun starts. Overlapping introducing support blocks uniqueness conservatively. This is
  stricter than Scala's offset tie-break for co-located chart concepts; the port has no equivalent
  concept ordering evidence.
- First/second-person resolution needs accepted holder placement, which is not yet supplied.
  Existing role placement distinguishes `outside-held-context` in an explicitly narrated root,
  `holder-not-supplied` in an attributed context, `context-unknown` with no event placement, and
  `ambiguous-context` across root and attributed placements. A nearby speaker role is not a holder.

Identity is document-local, not automatically partitioned by event context: a person can be
mentioned in narration and reported speech. Identity never promotes a reported assertion into
root-world fact. Referent IDs derive from document, rule and sorted accepted members; changing
membership may change the snapshot ID. Pairing an independent recall does not rename an unchanged
source class. There are no caller-assigned global IDs or cross-account identity assertions.

Keep the original account, exact annotation bytes and sidecar. `verify_identity` recomputes the
decisions; schema validation alone does not authenticate them. The CLI checks export permission
before reading annotations/results or writing an inspection artifact.

## Remaining capability

This is the bounded introducing/third-person text path from ADR 0012. Quantity/chart evidence,
automatic form acquisition, the full two-pass holder rule, speaker groups, addressees, cross-modal
licensed identity/distinctness, speech promotion and downstream canonical graph projection remain
owed. The sidecar and role table are an executed consumer, not a claim that those capabilities
already exist. ADRs 0024–0028 continue to govern source identity and assessment boundaries.
