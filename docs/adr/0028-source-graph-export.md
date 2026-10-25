# ADR 0028 — Exporting the source identity graph to the connected assessor

**Status:** Proposed 2026-10-25. Every question under "Owner decisions" is open. The cold review of
`6f1235532` found four blocking defects: the mention choice, overlap against containment, inexact
conversion, and identity licensed without a graph. Its re-review of `b3dedab4f` found two more: the
one-class rule reintroduced the deterministic deixis that ADR 0027 rejected, and it treated a
contested class as `Same`. The re-review of `3d7c5225b` found that a picture record still bound
an absent person to whoever was shown whenever the model emitted a single handle. This revision
answers all seven and their follow-ups. Its re-review of `ca9a14089` found that name containment
bound a relational description of an absent person to the person named in it; binding is now on
name equality.

**Decider:** the owner

**Tracker:** S3d `bd-01M5MT3DRFC5PY4BEBBVGVD168`, under S3 `bd-01M5MR41A9HTQH1MHC57JK28DK`. Its
assessor half waits for `bd-01M5RAN674YW0QGQFM7RY66WXM`, which integrates the Codex line that holds
the assessor onto `main`.

## Context

ADR 0027 builds a recall-blind identity graph for Sherlock part b, rows 500-585, and leaves the
codec to S3d. The run is reported in `docs/reviews/2026-10-24-s3c-source-identity-run.md`. Its
output, `source-identity-graph/v1`, lists mention ids and their outcomes, identity classes, and each
dialogue mention's relation to every visual class with hints. A relation row names its class by
the class's least *visual* id. It does not record where a mention is anchored, its name or its
context. `ReferentRelation` has four values (`Same`, `Distinct`, `Unknown`, `Open`); the S3c run
passes no distinctions, so it produces no `Distinct`.

The connected assessor (`tools/connected_assessment_probe.py`, not yet on `main`) takes identity and
continuity from edges the model proposes for each question:
- **Edges.** `same_*` and `different_*` edges over five kinds (entity, event, assertion,
  situation, context). Each edge has a basis, and only `unresolved` is ignored.
- **Equality.** The validator's `Graph` is the transitive closure of `same_` edges within one kind,
  and a `different_` pair spoils its class.
- **Handles and records.** A handle is local to one proposal, and its citation is a UTF-16 span of
  a record's text. Records have `id`, `part`, `start`, `end` and `text`. The validator's
  `overlaps` is a half-open test on one part with strict inequalities.
- **Where entity equality is used.**
  - role merges in every mode;
  - `cross_scope_identity` affirmations and denials;
  - identity-mode target binding through `same_entity` reachability;
  - the cross-component checks in `enforce_parent_assignments`.

Three of the S1 contrast cases fail today because of this:
- **Appearance-only identity** (S2). A `same_entity` edge whose basis is `direct_correspondence`,
  argued only from matching clothing, merges two people.
- **An identity claim about someone elsewhere** (S5). The model's `same_entity` edge between a
  depicted and a described person is accepted.
- **Continuity across a state change** (S3). Any `same_situation` edge carries binding across an
  intervening state change, because no record between the two ends is checked.

The identity graph can settle the first two. It cannot settle the third, because it has no
situations: ADR 0027 builds referent identity only.

## Decision

### 1. The export is `source-identity-graph/v2`

`sourceIdentity build` writes `v2` in place of `v1`.
- **Each admitted mention gains:**
  - its origin;
  - its stream;
  - its intervals, in edition ticks, with the edition's timebase;
  - its name, where there is one;
  - its context;
  - its recognizer word indices, for a dialogue mention;
  - its class id.
- **Class ids.** A class id is the least member id of the class, over all its members. Relation rows
  name classes by that id, which replaces `v1`'s least visual id.
- **Relations** keep all four `ReferentRelation` values, with hints. Each class carries a contested
  flag, and every class pair whose relation is `Distinct` is written explicitly.
- **Unchanged:** proposal outcomes, the manifest and the input hashes are kept as in `v1`.
- **Definition.** The exchange is defined by a JSON Schema under `docs/data/`, and courts on the
  writer check every field.

### 2. A checked Python reader

`tools/source_identity/graph_reader.py` reads `v2` for the assessor. It refuses, with a typed
reason:
- any other `schemaVersion`;
- a graph whose bytes do not match the SHA-256 the run is frozen with;
- an unknown relation status;
- an edition or part other than the one the assessor's input declares for its records.

All time comparison is in integer edition ticks. A record's `start` and `end` are converted with
exact rational arithmetic. A record whose times are not a whole number of ticks is refused. That
refuses the record, not the graph.

The reader exposes:
- `visual_mentions(part, start, end)`: the picture-stream mentions that overlap the record,
  half-open and strict as `overlaps` is, with their annotation names;
- `mention_for_span(record, utf16_span)`: the dialogue mention whose recognizer words the cited
  span covers exactly, by the record's per-word UTF-16 offsets, if one exists;
- `relation(class_a, class_b)`, derived from the graph:
  - **one class, uncontested:** `Same`;
  - **any pair involving a contested class:** unresolved;
  - **two uncontested classes:** `Distinct` when the graph records it, and otherwise `Unknown`.
    `v2` writes every `Distinct` class pair explicitly.

It never infers a relation the graph does not state.

### 3. A handle binds only through a name or an exact word match

Each record declares one modality, picture or speech. A record that is both, such as an annotation
row with a speaker and a description, is refused, and its handles are unbound. A speech record
carries the recognizer word indices of its text and each word's UTF-16 offsets. Binding is decided
before validation, and the model names no mentions.
- **A picture record never binds by presence alone.** An entity handle in a picture record binds
  only when its cited span, after the core's name folding, *equals* the annotation name of one
  visual mention that overlaps the record, matched on whole tokens. It then takes that mention's
  class.
  - A longer span stays unbound, even if it contains a listed name. A relational description that
    names someone else is one such span.
  - An unnamed description stays unbound.
  - So does the name of someone not listed in the overlapping row.
  - The handle count does not matter, so a model that emits one handle for an absent person gains
    nothing.
- **A speech record never binds by time.** A speech record's handle binds only when its cited span
  covers exactly the words of one graph dialogue mention. Then it takes that mention's class.
  - A pronoun so bound is linked to a person on screen only if the ADR 0027 resolver licensed that
    link (a deictic or named link). The assessor never adds one.
  - A described person with no graph mention, such as a kinship noun outside ADR 0027 §2's list,
    stays unbound.
- **Refusals leave handles unbound.** A record refused by the reader (§2), or with no qualifying
  mention, leaves its handles unbound.
- **Equality.** Two handles are the same entity only if both are bound and `relation` is `Same`.
  They are different only if it is `Distinct`. Every other case is unresolved.

### 4. Identity is graph-owned everywhere

In a graph run, every use of entity equality in the validator reads §3's bindings and the graph's
relations, never the model's edges. That covers:
- **role merges:** a role's handles must be bound to one class;
- **`cross_scope_identity`:** an affirmation needs `Same`. A denial needs `Distinct`. The S3c graph
  has no `Distinct`, so a denial is unlicensable until a source of distinctness exists, and that
  fails closed;
- **identity-mode target binding:** an argument reaches the target through its class's mentions
  that overlap the target, not through model reachability;
- **`enforce_parent_assignments`:** shared roles need one class.

The model's `same_entity` and `different_entity` edges are kept as `proposed_*`, with the reason
`identity is graph-owned`. They never license. Event, assertion, situation and context edges keep
today's rules until a source for them exists (§5).

A run declares in its freeze whether it is a graph run, and gives the graph's SHA-256. The SHA-256
also enters each `claim_id`. A graph run whose graph is absent, mismatched or refused by the reader
resolves every entity-equality question as unresolved, and never falls back. Whether runs without a
graph exist at all is owner decision G.

### 5. Situation continuity is not in this export

The S3 case needs situation or state segments that a state change ends, and binding through an
unbroken segment. This ADR does not provide that. The case stays an expected failure, owned by
`bd-01M5MRVE0ZN7F5B6BE7440GFJ8`, which covers continuity edges that bind without a timing or cut
check (owner decision C).

### 6. Consumer tests first (T6)

Before the validator changes, S3d writes the following from outside the assessor module:
- **A synthetic `v2` graph fixture.** Records get one declared modality each, distinct intervals,
  ticks that convert exactly, and, on speech records, word indices with UTF-16 offsets.
  - **S2:** two picture records whose spans name two visual mentions in different classes. The
    positive control has one class.
  - **S5, real-shaped:** a picture record naming the depicted person, and a speech record whose
    described-person span covers no graph mention. It must come out unresolved. The positive
    control is a speech record whose span covers a name mention in the depicted person's class.
  - **The absent person in a picture record:** a one-person picture record whose single handle's
    span is an unnamed description of someone else. It must stay unbound.
  - **A relational description:** a picture record whose span contains a listed name inside a longer
    description of someone else. It must stay unbound. The control is a span equal to the name.
  - **No added deixis:** a speech pronoun the graph left unlinked must not bind to the person shown.
    The control is a pronoun the graph linked by a licensed deictic link, which binds to that
    person.
  - **A mixed record** is refused.
  - **A contested class** gives unresolved.
- **Graph-mode variants of these cases.** They run against today's validator and are marked as
  expected failures.

The existing pairs and their pinned outcomes stay as the no-graph record.

### 7. Scope

The graph covers part b, rows 500-585. Records outside it overlap no mention, so their entity
handles are unbound. Assessor coverage beyond that scope waits for more graphs. The assessor half
has a named gate before it lands: a check that the real records (`rendered-input-pack.json`) use
part-b seconds and the graph's edition, that each declares one modality, and that speech records
carry word indices and per-word UTF-16 offsets that match the recognizer transcript the graph was
built from.

## Owner decisions

**E. The export (§1-2).**
- *Recommended:* a new graph version written by `sourceIdentity build`, with a JSON Schema and a
  checked Python reader.
- *Alternative:* a circe codec in `codec` for the document module's resolution types. That would be
  portable, but the assessor is Python and reads a file, so it adds a Scala surface nobody consumes
  yet.

**J. Binding a handle (§3).**
- *Recommended:* bind by name or exact word match. A picture handle binds only when its span equals
  the annotation name of an overlapping visual mention. A speech handle binds only when its span is exactly a graph
  dialogue mention. Speech records must carry word indices and UTF-16 offsets, which is an input
  change.
- *Alternative (a):* bind a picture record's single handle to its single visual class, and disclose
  the error class as an accepted risk, measured by a court and a labelled sample. This covers
  unnamed descriptions, at the cost of binding some absent people.
- *Alternative (c):* bind only from records derived from the annotation's presence column, never
  from captions.
- *Rejected:* binding by time to the only class present. That is deterministic deixis (ADR 0027
  §4), and the model's handle count would decide it.

**M. Model identity edges (§4).**
- *Recommended:* keep them as `proposed_*`, with a reason, and never let them license.
- *Alternative:* drop them from the output. That is simpler, but the record of what the model
  claimed is lost.

**G. Runs without a graph.**
- *Recommended:* allow them only as a declared comparison arm. Their identity results are labelled
  `model-proposed` and are never reported as licensed. Rule 13 already bars model-licensed
  identity, and this makes that visible without discarding the existing measured arms.
- *Alternative:* remove the no-graph mode. Every film then needs a graph before any identity result
  exists.

**A. S3d's acceptance.** The S3d bead asks to turn the S2 and S3 expected failures into passing
witnesses.
- *Recommended:* amend it to the graph-mode S2 and S5 witnesses (§6), and move S3 to the continuity
  bead (§5).
- *Alternative:* keep S3 in S3d and let S3d wait for a continuity source.

**C. Continuity.**
- *Recommended:* its own ADR now, from `bd-01M5MRVE0ZN7F5B6BE7440GFJ8`. Annotation rows and
  scene-cut boundaries can bound segments without tracks.
- *Alternative:* wait for track input (`bd-01M5PYJ7237CQAGMY0GC7ESZM1`).

## Consequences

- **What the graph settles.** Identity in the assessor is licensed by the recall-blind graph. The S2
  and S5 cases become decidable from evidence the model never sees.
- **What it costs.** Nothing binds in these cases:
  - an unnamed or relational description on a picture record;
  - a speech span that is not an exact graph mention;
  - a mixed record.

  So identity coverage falls. Speech records must carry word indices and UTF-16 offsets.
  Cross-scope denials are unlicensable until a source of distinctness exists.
- **Not a role check.** Graph binding decides which person a quoted name or mention is. It does not
  decide which role the model assigns that handle to. Role attribution is unchecked, as it is
  today.
- **Two halves.** The Scala export (§1) and the Python reader (§2) can land on `main` now. The
  consumer tests (§6) and the validator changes (§3-4) land after
  `bd-01M5RAN674YW0QGQFM7RY66WXM`.

## Rejected alternatives

- **Keeping per-question identity edges, with an appearance basis that the validator skips.** That
  fixes S2 alone, and it leaves identity to the model, which ADR 0024 and rule 13 forbid.
- **Feeding the graph to the model as context.** The model would restate it, and the identity
  decision would still pass through the model. Under §3 the model sees nothing of the graph.
- **Exporting `v1` unchanged.** It has no anchors, so no record can be joined to a mention.
