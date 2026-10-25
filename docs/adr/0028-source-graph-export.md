# ADR 0028 — Exporting the source identity graph to the connected assessor

**Status:** Proposed 2026-10-25. Every question under "Owner decisions" is open. The cold review of
`6f1235532` found four blocking defects: the mention choice, overlap against containment, inexact
conversion, and identity licensed without a graph. Its re-review of `b3dedab4f` found two more: the
one-class rule reintroduced the deterministic deixis that ADR 0027 rejected, and it treated a
contested class as `Same`. This revision answers all six and their follow-ups.

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
  flag, and every class pair whose relation is `Distinct` or `Open` is written explicitly.
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
- `visual_classes(part, start, end)`: the classes of the picture-stream mentions that overlap the
  record, half-open and strict, as `overlaps` is;
- `mention_for_words(word_indices)`: the dialogue mention whose recognizer words are exactly these,
  if one exists;
- `relation(class_a, class_b)`, derived from the graph's mention-level relations:
  - **one class, uncontested:** `Same`;
  - **one class, contested:** `Open`;
  - **two classes** take the relation the graph records between them. `v2` writes every class
    pair that is `Distinct` or `Open`, so a pair with no row is `Unknown`.

It never infers a relation the graph does not state.

### 3. A handle binds only through what the record is

Each record declares its modality, picture or speech. A speech record also carries the recognizer
word indices of its text. Binding is decided before validation, and the model names no mentions.
- **A picture record** describes what is shown. Its entity handle binds to a class only when the
  record overlaps exactly one visual class *and* the record has exactly one entity handle. A
  picture record with two or more entity handles binds none of them, because the extra handle may
  be someone not shown.
- **A speech record never binds by time.** A speech record's handle binds only when its cited span
  covers exactly the words of one graph dialogue mention. Then it takes that mention's class. A
  pronoun so bound stays in its own class, which the graph leaves `Unknown` to every person on
  screen, so no deixis is made here (ADR 0027 §4). A described person with no graph mention, such
  as a kinship noun outside ADR 0027 §2's list, stays unbound.
- **Refusals leave handles unbound.** A record refused by the reader (§2), or with no qualifying
  mention, leaves its handles unbound.
- **Equality.** Two handles are the same entity only if both are bound and `relation` is `Same`.
  They are different only if it is `Distinct`. Every other case, `Open` included, is unresolved.

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
- **A synthetic `v2` graph fixture.** Records get declared modalities, distinct intervals, ticks
  that convert exactly, and word indices on speech records.
  - **S2:** two picture records, each overlapping one visual mention, in different classes. The
    positive control has one class.
  - **S5, real-shaped:** a picture record overlapping one visual class, and a speech record whose
    described-person span covers no graph mention. It must come out unresolved. The positive
    control is a speech record whose span covers a name mention in the depicted person's class.
  - **No deixis:** a speech record in a one-person row whose pronoun span covers a pronoun mention.
    It must not bind to the person shown.
  - **Two handles:** a picture record with two entity handles binds neither.
  - **Contested class:** a class marked contested gives `Open`, which is unresolved.
- **Graph-mode variants of these cases.** They run against today's validator and are marked as
  expected failures.

The existing pairs and their pinned outcomes stay as the no-graph record.

### 7. Scope

The graph covers part b, rows 500-585. Records outside it overlap no mention, so their entity
handles are unbound. Assessor coverage beyond that scope waits for more graphs. The assessor half
has a named gate before it lands: a check that the real records (`rendered-input-pack.json`) use
part-b seconds and the graph's edition, that each declares its modality, and that speech records
carry word indices that match the recognizer transcript the graph was built from.

## Owner decisions

**E. The export (§1-2).**
- *Recommended:* a new graph version written by `sourceIdentity build`, with a JSON Schema and a
  checked Python reader.
- *Alternative:* a circe codec in `codec` for the document module's resolution types. That would be
  portable, but the assessor is Python and reads a file, so it adds a Scala surface nobody consumes
  yet.

**J. Binding a handle (§3).**
- *Recommended:* bind by modality. A picture record binds its single entity handle to its single
  visual class. A speech record binds only by an exact word-index match to a graph dialogue
  mention. This requires speech records to carry word indices, which is a change to the assessor's
  input.
- *Alternative:* speech handles never bind. No input change is needed, but every described or
  spoken referent is unresolved, so the S5 positive control cannot pass.
- *Rejected:* binding by time to the only class present. That is deterministic deixis (ADR 0027
  §4), and it binds absent people to whoever is shown.

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
  - a picture record that overlaps several people;
  - a picture record with several entity handles;
  - a speech span that is not an exact graph mention.

  So identity coverage falls. Speech records must carry word indices. Cross-scope denials are
  unlicensable until a source of distinctness exists.
- **Two halves.** The Scala export (§1) and the Python reader (§2) can land on `main` now. The
  consumer tests (§6) and the validator changes (§3-4) land after
  `bd-01M5RAN674YW0QGQFM7RY66WXM`.

## Rejected alternatives

- **Keeping per-question identity edges, with an appearance basis that the validator skips.** That
  fixes S2 alone, and it leaves identity to the model, which ADR 0024 and rule 13 forbid.
- **Feeding the graph to the model as context.** The model would restate it, and the identity
  decision would still pass through the model. Under §3 the model sees nothing of the graph.
- **Exporting `v1` unchanged.** It has no anchors, so no record can be joined to a mention.
