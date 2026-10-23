# ADR 0024 — Cross-modal referent mentions and licensed identity links

**Status:** Proposed 2026-10-23. Not accepted. It needs an owner decision (AGENTS.md SD5).

**Decider:** the owner

**Tracker:** epic `bd-01M5MR1KTXEP898CTN2KE3J219`. This is slice S2, `bd-01M5MR3BR0C7DCF9TRB3GEVDKV`.
S3 (source identity graph), S4 (speech promotion) and S5 (recall binding) are blocked on this decision.

## Context

Scoring a recall such as "she was dead on the floor" means establishing that facts observed
separately concern one person. Here, the facts are a depicted person lying on a floor and a line
of dialogue saying someone is dead. The connected-assessment experiment of 2026-10-23
(`.agent-work/connected-assessment-20261023/report.md`, kept local) measured the problem.

- On the body diagnostic, the link between the depicted and the described person stayed
  unresolved even with full context. So did the depicted person's death. The frozen development
  reference judged both resolvable.
- A natural recall claim was wrongly contradicted. The proof gave a source person to the recall
  role without establishing that the two were the same person.
- Proofs could cite records from another moment. The fix was a deterministic target-binding guard
  (S0, `claude/evidence-binding-s0`). After it, the only path from an off-target record to the
  target is a `same_situation` edge proposed per question, and the validator cannot check that
  edge (follow-up `bd-01M5MRVE0ZN7F5B6BE7440GFJ8`).

The root cause is architectural. An LLM reconstructs identity from separate text records for every
question, and its identity edges are labels it asserts. The paired contrast suite (S1,
`tools/test_binding_contrast_suite.py`) records four cases the current design cannot decide. For
example, an edge whose stated basis is a matching hat passes as identity.

The relevant vocabulary already exists in part:

| Existing | Where | Limit |
|---|---|---|
| `MentionIdentity.Open(reason, candidates)` | `document/coreference.scala` | Text mentions only |
| `EntityRelation.SameAs` | `story/relations.scala` | Forgeable case-class edge with no typed basis |
| `ContextKind.Speech(ContextHolder)` | `story/nodes.scala` | No route from speech to `NarratedWorld` |
| `TypedSupport.Anchored(Playback(axis, …))` | `core/support.scala` | Can already anchor support in media time |

## Decision (proposed)

### 1. A referent mention can come from any modality

`ReferentMention` extends the mention layer of ADR 0012 beyond text. A mention has one origin:

- a **visual** detection or track over a playback interval;
- a **dialogue** mention inside an utterance (ASR or annotation), which carries its speech context;
- a **narration or annotation** mention;
- a **recall** mention in a recall unit.

Its support is a `TypedSupport` in the origin's own coordinate family. A mention is not an entity,
and two mentions are never one referent because their labels match.

### 2. Identity is a licensed link

`IdentityLink(left, right, basis, evidence)` is a validating type (rule 8). Whether a link is
valid depends on the relation between its endpoints, their origins and the support cited, so
the Cartesian-product test makes it unforgeable from the start. It is a `final` non-case class
with a private constructor and a checked factory that receives enough context to prove the
relation. Its tests must show from outside the package that `apply`, `copy`, `fromProduct` and
`Mirror.ProductOf` are refused.

Each basis has a fixed **licence class**:

| Basis | Licence | Factory must check |
|---|---|---|
| `ContinuousTrack(trackId)` | Sufficient alone | Both mentions lie inside one track with no gap in observed coverage |
| `DeicticReference(utterance)` | Sufficient alone | The spoken referent is present in the same interval, cited by speech and visual support |
| `NamedReference(name)` | Sufficient alone | The same name is attached to both mentions, in contexts that permit naming |
| `TextCoreference(rule)` | Sufficient alone | The ADR 0012 rule, unchanged |
| `AppearanceMatch` | Contributory only | Nothing: it can never resolve identity by itself |
| `TemporalProximity` | Contributory only | Nothing: it can never resolve identity by itself |
| `CompatibleDescription` | Contributory only | Nothing: it can never resolve identity by itself |

Contributory bases can rank candidates or prompt further acquisition, but no combination of them
yields `Resolved`. An explicit `DistinctLink` counter-link makes the whole equivalence class
`Open`, as the S0 graph already does. Ambiguity stays `Open` with its candidates, as in ADR 0012.

### 3. Build source identity before the recall, once per film

The source identity graph is computed from video, ASR, captions and optional annotations before
any recall text is read and without it (S3). An LLM may **propose** links (rule 13). A
deterministic resolver **accepts** a proposed link only when the factory licenses its basis. Every
accepted and every refused proposal keeps its receipt. Captions derived from the same frames are
not independent corroboration.

### 4. Promotion from speech to the narrated world is its own status

A speech assertion stays inside `Speech(holder)`. A named defeasible rule may add a separate
narrated-world claim with status **`Inferred(rule, premises)`**, distinct from observed and
asserted (S4). The rule fires only when all of the following hold:

- the speaker's context is not belief, hypothetical, memory, imagination or marked deception;
- the referent link is licensed under §2;
- no evidence in the source graph contests the claim.

The source of contestation (text read per question, or a mark in the source graph) is decided in
S4. Analyses can include or exclude `Inferred`.

### 5. Recall assessment consumes the graph and never extends it

The recall assessor (S5) binds each recall argument to a source referent, or leaves it `Open`.
It then judges each attribute separately. A link proposed per question cannot make two source
mentions identical, and it cannot extend continuity. If attribution fails, the local source facts
remain, but the recall gets no credit and the participant is not penalized.

## Rejected alternatives

1. **An LLM assigns global entity ids per question.** This is today's design. Identity becomes an
   unverifiable label, and the S1 suite shows appearance passing as identity.
2. **Keep `SameAs` and add a free-text basis.** A string basis cannot carry a licence class, so it
   repeats the stringly-typed defect that rule 2 forbids.
3. **Blanket temporal overlap as identity or continuity.** This rejects persistence across cuts
   and dialogue about earlier events, and it accepts two different people in one shot.
4. **Embedding similarity as identity.** Rule 1 forbids it. Embeddings may only retrieve
   candidates.

## Consequences

- New public vocabulary in a portable module. The proposal is the `document` module, next to ADR
  0012, with no JVM-only types. Visual tracks arrive as data, never as detector code.
- The track-level visual evidence is a measured dependency. The sampled-pair Qwen associations
  flip orientation and call one body two people (facet report, 2026-10-22). Until a track source
  is admitted, `ContinuousTrack` will be rare, and most visual identity will stay `Open`.
  Measured recall accuracy may fall before it rises.
- The S1 expected failures for S2, S3 and S5 become the acceptance witnesses. Per-question
  `same_entity` and `same_situation` edges in `tools/connected_assessment_probe.py` lose the
  power to establish identity or continuity.

## Questions for the owner

1. Should a sufficient-alone basis be enough by itself, or should a disputed identity also require
   the absence of any contributory evidence against it?
2. Placement: `document`, or a new portable module? A new module needs its own decision line.
3. Can `Inferred` claims count toward recall credit in the primary analysis, or only in a
   declared secondary analysis?
