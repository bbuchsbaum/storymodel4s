# ADR 0024 — Cross-modal referent mentions and licensed identity links

**Status:** Accepted 2026-10-23 (AGENTS.md SD5). The owner chose the three answers recorded
under "Owner decisions".

**Decider:** the owner

**Tracker:** epic `bd-01M5MR1KTXEP898CTN2KE3J219`. This is slice S2,
`bd-01M5MR3BR0C7DCF9TRB3GEVDKV`. Acceptance unblocks S3 (source identity graph) and S4 (speech
promotion). S5 (recall binding) also waits for S3, S4 and continuity follow-up
`bd-01M5MRVE0ZN7F5B6BE7440GFJ8`.

## Context

Scoring a recall such as "she was dead on the floor" means establishing that facts observed
separately concern one person. Here, the facts are a depicted person lying on a floor and a line
of dialogue saying someone is dead. The connected-assessment experiment of 2026-10-23
measured the problem. Its report is kept locally in the ignored `.agent-work/` tree; the counts
used here are reproduced on the epic's Mote beads.

- On the body diagnostic, the link between the depicted and the described person stayed
  unresolved even with full context. So did the depicted person's death. The frozen development
  reference judged both resolvable.
- A natural recall claim was wrongly contradicted. The proof gave a source person to the recall
  role without establishing that the two were the same person.
- Proofs could cite records from another moment. The fix was a deterministic target-binding guard
  (S0, branch `claude/evidence-binding-s0`, not yet on main). After it, the only path from an
  off-target record to the target is a `same_situation` edge proposed per question, and the
  validator cannot check that edge (follow-up `bd-01M5MRVE0ZN7F5B6BE7440GFJ8`).

The root cause is architectural. An LLM reconstructs identity from separate text records for every
question, and its identity edges are labels it asserts. The paired contrast suite (S1,
`tools/test_binding_contrast_suite.py` on branch `claude/evidence-binding-s1`) records four cases
the current design cannot decide. For example, an edge whose stated basis is a matching hat passes
as identity.

The relevant vocabulary already exists in part:

| Existing | Where | Limit |
|---|---|---|
| `MentionIdentity.Open(reason, candidates)` | `document/coreference.scala` | Text mentions only |
| `EntityRelation.SameAs` | `story/relations.scala` | Forgeable case-class edge with no typed basis |
| `ContextKind.Speech(ContextHolder)` | `story/nodes.scala` | No route from speech to `NarratedWorld` |
| `TypedSupport.Anchored(EvidenceSupport)` with `EvidenceAnchor.MediaTime`, `Shot`, `Track` | `core/support.scala`, `core/source.scala` | Already anchors support in media time, including a `TrackId` |

## Decision

### 1. A referent mention can come from any modality

`ReferentMention` extends the mention layer of ADR 0012 beyond text. A mention has one origin:

- a **visual** detection or track over a playback interval;
- a **dialogue** mention inside an utterance (ASR or annotation), which carries its speech context;
- a **narration or annotation** mention;
- a **recall** mention in a recall unit.

Its support is a `TypedSupport` in the origin's own coordinate family. A mention is not an entity.
Across modalities, a matching descriptive label ("a woman", "a person in a dark jacket", a
detector class) never makes two mentions one referent. Proper names differ: a shared name is the
`NamedReference` basis below, and within text ADR 0012's `introducing-label/v1` still clusters
names and nominals by exact label, unchanged.

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
| `ContinuousTrack(trackId)` | Sufficient alone | Both mentions lie inside one `EvidenceAnchor.Track` with no gap in observed coverage |
| `DeicticReference(utterance)` | Sufficient alone | Exactly one candidate referent is present in the interval, cited by speech and visual support |
| `NamedReference(name)` | Sufficient alone | The name is attached to each mention directly, not through another identity link |
| `TextCoreference(rule)` | Sufficient alone | The ADR 0012 rule, unchanged |
| `AppearanceMatch` | Contributory only | Nothing: it can never resolve identity by itself |
| `TemporalProximity` | Contributory only | Nothing: it can never resolve identity by itself |
| `CompatibleDescription` | Contributory only | Nothing: it can never resolve identity by itself |

Contributory bases can rank candidates or prompt further acquisition, but no combination of them
yields `Resolved`. Ambiguity stays `Open` with its candidates, as in ADR 0012.

Distinctness is licensed the same way. A `DistinctLink` opens the whole equivalence class only
with a sufficient basis: two simultaneous detections in one frame, different names attached
directly, or dialogue that explicitly contrasts the referent with someone present. A distinctness
proposal with only a contributory basis (different clothing, a long gap) is recorded beside the
class and does not open it. This differs from the S0 validator, where any `different_*` edge
opens the class. S5 brings that validator into line with this rule.

### 3. Build source identity before the recall, once per film

The source identity graph is computed from video, ASR, captions and optional annotations before
any recall text is read and without it (S3). An LLM may **propose** links (rule 13). A
deterministic resolver **accepts** a proposed link only when the factory licenses its basis. Every
accepted and every refused proposal keeps its receipt. Captions derived from the same frames are
not independent corroboration.

### 4. Promotion from speech to the narrated world is its own status

A speech assertion stays inside `Speech(holder)`. A named defeasible rule may add a separate
narrated-world claim, called **`Inferred(rule, premises)`** here, that is distinguishable from
depicted and surface-explicit claims (S4). S4 decides whether that is a new `EpistemicStatus`
case or `WorldKnowledgeInferred` with a named rule and premises. Either way, the claim must cite
its speech-scoped twin as a premise. `story/consistency.scala` already flags a narrated-world
situation that duplicates a speech-scoped one as reported content promoted to fact, and an
uncited promotion must keep tripping that check. The rule fires only when all of the following
hold:

- the speech is in the narrated world, not nested in belief, desire, intention, hypothetical,
  counterfactual, memory or imagination. Deception has no vocabulary yet; until S4 adds it,
  contesting evidence is the only guard;
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

- New public vocabulary in the portable `document` module, next to ADR 0012, with no JVM-only
  types. Visual tracks arrive as data, never as detector code.
- The track-level visual evidence is a measured dependency. The sampled-pair Qwen associations
  flip orientation and call one body two people (facet report, 2026-10-22). Until a track source
  is admitted, `ContinuousTrack` will be rare, and most visual identity will stay `Open`.
  Measured recall accuracy may fall before it rises.
- The four S1 expected failures, one each for S2, S3, S4 and S5, become acceptance witnesses.
  Per-question `same_entity` and `same_situation` edges in `tools/connected_assessment_probe.py`
  lose the power to establish identity or continuity.

## Owner decisions (2026-10-23)

1. **One sufficient basis stands alone.** A single licensed sufficient basis resolves identity.
   Contributory evidence pointing the other way, such as a changed coat or a gap in time, is
   recorded beside the link and may prompt further acquisition. It does not reopen the link. Only
   a `DistinctLink` with a sufficient basis makes the class `Open`. Rejected: requiring that no
   contributory evidence points the other way. Appearance legitimately changes within a story, so
   ordinary costume and lighting changes would become lost identities.
2. **Placement is the `document` module, next to ADR 0012.** The new types extend its mention and
   identity layer, stay portable and add no module. `document` depends on `story`, not on
   `recall`. So a recall-origin mention refers to its recall unit by identifier, and S5 records
   any new module edge it needs in its own decision line. Rejected: a new portable module,
   which would split one identity vocabulary across two modules.
3. **`Inferred` counts in the primary analysis, with a mandatory sensitivity analysis.** The
   scoring target is what the narrative establishes, so a recall detail supported only through
   `Inferred(rule, premises)` earns primary credit. Every report also gives the results with
   `Inferred` support excluded, and counts `Inferred`-only credits separately. Rejected: credit in
   a secondary analysis only, which would score conclusions the viewer is plainly meant to draw as
   unsupported recall.
