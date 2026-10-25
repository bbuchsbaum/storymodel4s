# ADR 0028 — Exporting the source identity graph to the connected assessor

**Status:** Proposed 2026-10-25. Every question under "Owner decisions" is open.

**Decider:** the owner

**Tracker:** S3d `bd-01M5MT3DRFC5PY4BEBBVGVD168`, under S3 `bd-01M5MR41A9HTQH1MHC57JK28DK`. Its
assessor half waits for `bd-01M5RAN674YW0QGQFM7RY66WXM`, which integrates the Codex line that holds
the assessor onto `main`.

## Context

ADR 0027 builds a recall-blind identity graph for Sherlock part b, rows 500-585, and leaves the
codec to S3d. The run is reported in `docs/reviews/2026-10-24-s3c-source-identity-run.md`. Its
output, `source-identity-graph/v1`, lists mention ids and their outcomes, identity classes, and
each dialogue mention's relation to every visual class with hints. It does not record where a
mention is anchored, its name or its context.

The connected assessor (`tools/connected_assessment_probe.py`, not yet on `main`) takes identity and
continuity from edges the model proposes for each question:
- **Edges.** `same_*` and `different_*` edges over five kinds (entity, event, assertion,
  situation, context). Each edge has a basis, and only `unresolved` is ignored.
- **Equality.** The validator's `Graph` is the transitive closure of `same_` edges within one kind,
  and a `different_` pair spoils its class.
- **Handles.** They are local to one proposal: a citation is a UTF-16 span inside an evidence
  record, and a record has `id`, `part`, `start` and `end`.
- **Target binding.** It reaches a target interval through `same_situation` classes, or through
  `same_entity` classes in identity mode.

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

`sourceIdentity build` writes `v2` in place of `v1`. It adds, for each admitted mention:
- its origin;
- its stream;
- its intervals, in edition ticks, with the edition's timebase;
- its name, where there is one;
- its context;
- its identity class id, which is the least member id, as `v1` already orders classes.

The relations, hints, proposal outcomes, manifest and input hashes are kept as they are in `v1`.
The graph's own SHA-256 is printed by the command, as now. The exchange is defined by a JSON Schema
under `docs/data/`, and the producer-side courts check every field.

### 2. A checked Python reader

`tools/source_identity/graph_reader.py` reads `v2` for the assessor. It refuses, with a typed
reason:
- any other `schemaVersion`;
- a graph whose bytes do not match the SHA-256 it was given;
- an unknown relation status;
- a mention whose interval does not convert exactly to the part clock;
- an edition or part different from the one the assessor's records use.

The reader exposes `relation(mention_a, mention_b)` (`Same`, `Unknown`, `Open`) and
`mentions_in(part, start, end)`. It never infers a relation the graph does not state.

### 3. Records are joined to graph mentions before the model runs

The assessor's input preparation attaches to each evidence record the graph mentions anchored inside
the record's interval, on the same part clock, half-open. This is a deterministic time join. An
entity handle must name one mention from its record's list. A handle that names none, or names a
mention outside its record, is unbound, and a proof resting on it is `Unresolved`.

### 4. Identity comes from the graph, not from the model

When a graph is supplied, the validator takes entity equality from `relation`:
- **`Same` licenses.** `Unknown` and `Open` do not, and a hint never does (ADR 0024).
- **Model identity edges are refused.** A model `same_entity` or `different_entity` edge is moved
  to `proposed_*`, with the reason `identity is graph-owned`, and it never licenses.
- **Other kinds are unchanged.** Event, assertion, situation and context edges keep today's rules
  until a source for them exists (§5).

When a graph is required but absent, stale or refused by the reader, identity-mode licensing is
`Unresolved`. It never falls back to the model's edges.

### 5. Situation continuity is not in this export

The S3 case needs situation or state segments that a state change ends, and binding through an
unbroken segment. This ADR does not provide that. The case stays an expected failure, owned by
`bd-01M5MRVE0ZN7F5B6BE7440GFJ8` (continuity edges bind without a timing or cut check). That bead
gets its own ADR (owner decision C).

### 6. Scope

The graph covers part b, rows 500-585. Records outside it have no graph mentions, so their entity
handles are unbound and identity-mode proofs there are `Unresolved`. That fails closed. Assessor
coverage beyond the graph's scope waits for more graphs.

## Owner decisions

**E. The export (§1-2).**
- *Recommended:* a new graph version written by `sourceIdentity build`, with a JSON Schema and a
  checked Python reader.
- *Alternative:* a circe codec in `codec` for the document module's resolution types. That would be
  portable, but the assessor is Python and reads a file, so it adds a Scala surface nobody consumes
  yet.

**J. The join (§3).**
- *Recommended:* attach graph mentions to records by time, and require entity handles to name one.
- *Alternative:* map a handle's UTF-16 citation span to a mention by its words. That needs the
  record text to be aligned to recognizer word indices, which today's records do not carry.

**M. Model identity edges (§4).**
- *Recommended:* refuse them once a graph is supplied, and record them as proposed.
- *Alternative:* accept a model edge only where the graph says `Same`. That gives the same licences
  but leaves the model's answer looking like evidence.

**A. S3d's acceptance.** The S3d bead asks to turn the S2 and S3 expected failures into passing
witnesses.
- *Recommended:* amend it to S2 and S5, which this graph decides, and move S3 to the continuity bead
  (§5).
- *Alternative:* keep S3 in S3d and let S3d wait for a continuity source.

**C. Continuity.** Whether it gets its own ADR now, from `bd-01M5MRVE0ZN7F5B6BE7440GFJ8`, or waits
for track input (`bd-01M5PYJ7237CQAGMY0GC7ESZM1`).
- *Recommended:* its own ADR. Annotation rows and shot boundaries can bound segments without
  tracks.

## Consequences

- **What the graph settles.** Identity in the assessor is licensed by the recall-blind graph and
  audited by it. The S2 and S5 cases become decidable from evidence the model never sees.
- **Narrower coverage.** Identity-mode coverage is limited to graphed scope until more graphs exist.
- **Two halves.** The Scala export (§1) and the Python reader (§2) can land on `main` now. The
  assessor changes (§3-4) land after `bd-01M5RAN674YW0QGQFM7RY66WXM`.

## Rejected alternatives

- **Keeping per-question identity edges, with an appearance basis that the validator skips.** That
  fixes S2 alone, and it leaves identity to the model, which ADR 0024 and rule 13 forbid.
- **Feeding the graph to the model as context.** The model would restate it, and the validator would
  still trust the model's edges.
- **Exporting `v1` unchanged.** It has no anchors, so no record can be joined to a mention.
