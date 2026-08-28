# M1 W4(9) — Frozen-fixture adjudication protocol (design checkpoint, rev 0)

**Bead:** bd-01M14YJ8FG15S0R1ART7DTM1XZ (claude-storymodel4s-m1). **Status:** design
checkpoint for critique; nothing here is executed yet. Companion: the frozen corpus
manifest (`2026-08-28-m1-frozen-corpus-manifest.md`) and the spike spec rev 2.
**Independence audit** of the frozen set is a separate Codex-owned bead (W4: Claude
builds, Codex audits); this document is what that audit checks against.

## 0. Why a protocol, not a script

ADR 0001 §D7 and L6: defaults are chosen and probabilities are *calibrated* only
against fixtures that are **adjudicated** and **built independently of every tested
channel**. The design record (§14.4, §32.2, §88, §103) adds: multiple acceptable
targets and levels per recall unit; inter-annotator agreement is the ceiling no gate
may exceed; synthetic material is diagnostic, never selection evidence; WOG never
selects. The vision's own success criterion — an interpretation that is examinable and
falsifiable — applies to the fixtures too: every frozen label must be traceable to
exact spans, an annotator identity (pseudonymized), and an adjudication decision.

## 1. Roles and the channel-independence law

| Role | Count per story | May see |
|---|---|---|
| Annotator A, B | 2 | the story text, the surface atlas, the layer guidelines, their own prior layers |
| Adjudicator | 1 | A's and B's annotations for the layer being adjudicated, the guidelines |
| Recall panel participant | ≥ 3 per story per partition | the story once (read or heard), then nothing |
| Transcriber | 1 per recall | the audio; the story text is withheld |
| Independence auditor (Codex) | 1 per set | everything, read-only, after the freeze |

**Law I1 (channel independence).** No embedding model, chart provider, WL kernel,
reranker, or LLM judge that is *or could later be* under test may touch the
construction of a frozen set. The only machine assistance permitted is (a) the
deterministic `core.SurfaceAnalyzer` atlas and (b) a *different* provider than any
under test, used only to produce **candidate proposals** that both annotators must
independently confirm or reject; every such proposal is logged with its provider
fingerprint in the set's receipt so the auditor can prove it is not a tested channel.
A set built with any tested channel's help is **diagnostic**, never a selection set.

**Law I2 (blindness).** Annotators and adjudicators never see a model's alignment,
chart, candidate list, or score for a story they annotate. Recall participants never
see annotations. Transcribers never see the story text.

**Law I3 (no self-adjudication).** The adjudicator of a layer is not one of its two
annotators.

## 2. Annotation layers (in order; each has an agreement measure and a stop rule)

The layers follow the design record §32.2. A layer is *frozen* only when its stop
rule holds on every story in the set; later layers reference frozen earlier layers by
content-addressed ID.

| # | Layer | Unit of annotation | Agreement measure (A vs B, before adjudication) | Stop rule |
|---|---|---|---|---|
| L0 | Surface units and mentions | atlas sentence/token IDs (deterministic; annotators mark mention spans only) | mention span exact-match F1 | F1 ≥ 0.90 or adjudicated to 100% coverage |
| L1 | Entities and coreference | mention → entity cluster; `MentionForm` | MUC, B³, CEAF-e (report all three) | B³ ≥ 0.85; every disagreement adjudicated |
| L2 | Situations, predicates, roles | clause-anchored `Event`/`State` with `ParticipantRole`s, polarity, modality, context kind | situation-mention F1; role macro-F1; polarity/modality/context accuracy | situation F1 ≥ 0.90; role macro-F1 ≥ 0.85; context accuracy ≥ 0.90 |
| L3 | Story-world time | `TemporalEdge`s (canonical forward relations) per context; `Unclear` allowed | relation macro-F1 on shared pairs; strict cycles after adjudication = 0 | macro-F1 ≥ 0.80; zero cycles |
| L4 | Causal and goal | `CausalEdge`/`GoalEdge` with `EpistemicStatus` | accepted-edge precision (prioritized over recall); status accuracy | precision ≥ 0.85; every accepted edge cites a span or an upstream claim |
| L5 | Event identity and reference | `ExactCorefCluster[SituationK]`; `NarrativeReference` (Prospective/Retrospective/Summary/Partial/Bridging/Thematic) | cluster B³; reference-mode accuracy | B³ ≥ 0.85; adjudicated |
| L6 | Hierarchy | primary segmentation (scenes/episodes/root) + boundary beliefs; auxiliary arcs optional | ±1-clause boundary F1; WindowDiff; parent agreement | boundary F1 ≥ 0.80; both alternative segmentations retained as `BoundaryBelief`s |
| L7 | Descriptors | `DescriptorClaim` (Summary/Theme/Motif) on segments | human agreement on summary faithfulness (Likert ≥ 4/5 by the other annotator) | adjudicated |

Rules that apply to every layer:

- **Evidence.** Every explicit claim carries `SpanSet` evidence; inferred claims cite the
  motivating spans and get `WorldKnowledgeInferred`/`Hypothesized`. The codec refuses a
  `SurfaceExplicit` claim without spans, so the annotation tool cannot save one.
- **Ambiguity survives.** Where A and B disagree and the adjudicator judges *both*
  defensible, the frozen file records the adjudicated primary **and** the alternative as
  `Resolved.alternatives` (design record §14.4, §32.2). Gates are then scored against
  the acceptable set, not one answer.
- **Ceiling.** The pre-adjudication agreement of every layer is stored in the set
  receipt; a gate threshold for that layer may not exceed it (§32.2, `gates.md`).

## 3. Recall-unit targets (the alignment gold)

For each adjudicated recall (§4), every recall unit receives, by the same A/B +
adjudicator process:

```
RecallUnitTarget(
  unit:            RecallUnitId,
  targets:         NonEmptyVector[(NarrativeNodeId, level)]   // ALL acceptable anchors, any level
  primary:         (NarrativeNodeId, level)                    // adjudicated best
  fidelity:        Set[Facet] expected to be Distorted        // RoleReversal, Polarity, Context, Modality, Outcome, Actor, Object, Location, Cause — empty = Faithful
  groundedness:    Source | Association | Inference | Intrusion | Uninterpretable
  externalSubtype: Option[Association | Commentary | Evaluation | SourceMonitoring | TaskCommentary]
  discourse:       DiscourseFunction (oracle label; predicted labels are scored separately)
  confidence:      annotator confidence 1–5
  notes:           free text (never used by any metric)
)
```

- A **summary** unit lists the segment as primary and may list its children as
  acceptable at level 0 only if the adjudicator judges the unit precise enough.
- A **blend** lists both anchors as acceptable and the adjudicator marks `blend = true`.
- A **role-reversed / negated / context-shifted** unit anchors to the correct event with
  the expected facets — never to "external" (ADR 0001 §D5: distortion, not omission).
- Acceptable-target agreement is measured as Jaccard over target sets and exact match
  on primary; the stop rule is Jaccard ≥ 0.80 before adjudication.

## 4. Natural human-recall panel

- **Collection.** Free recall immediately after one exposure (read silently, or listened
  to a neutral recording — recorded per story), instruction: "tell the story in your own
  words, in as much detail as you can"; no probes for the free-recall set. A second,
  delayed recall (≥ 24 h) is optional and stored as a separate panel.
- **Panel size.** Development and calibration partitions: ≥ 3 recalls per story;
  untouched test partition: ≥ 5 recalls per story (macro CIs need them). Participants
  are never reused across partitions.
- **Transcription.** Verbatim; disfluencies, false starts, hedges, and self-repairs
  **retained** (they are evidence for `ExpressedUncertainty` and repair handling);
  interviewer speech, if any, on its own turn; timestamps per turn when audio exists;
  UTF-8, one `TranscriptAtlas` per recall.
- **Segmentation is adjudicated before targets.** The baseline `RecallSegmenter` may
  *propose* idea-unit boundaries (it is deterministic and not a tested channel), but A
  and B independently confirm/repair every boundary; only adjudicated unit boundaries
  receive targets. The segmenter's proposals are logged so its boundary accuracy can be
  reported as a diagnostic.
- **Consent and privacy.** Recalls of public-domain stories are not sensitive, but
  participant identities are pseudonymized in the manifest (`p-<hmac>`), and any
  incidental personal remark in a recall is redacted at transcription with a marker,
  never silently dropped.

## 5. Partitions and the leakage checklist

Stories are grouped by **family** (folktale/oral; fairy tale, literary; short fiction,
literary). Each family contributes to every partition so that *leave-family-out*
evaluation is possible for provider selection:

| Partition | Purpose | Who may look |
|---|---|---|
| development | error analysis, feature engineering, choosing views/reducers | engineers |
| calibration | fitting retrieval and rejection calibration models, cost weights, floors | the calibration job only |
| untouched test | the number that selects a default; opened once per candidate set | the bench, never a person before the report |

Leakage checklist (the auditor signs each item):

1. No story appears in more than one partition; WOG appears in none (regression only).
2. Provider selection uses leave-family-out; calibration uses leave-story-out within
   the calibration partition.
3. No annotator or adjudicator worked on both a development and a test story.
4. No recall participant appears in more than one partition.
5. No tested channel's output was visible to any annotator (I1/I2), per the receipt.
6. The test partition's recall transcripts were not read by any engineer before the
   report (access is logged by the bench harness).
7. Any metamorphic/synthetic material is stored under `diagnostic/`, never under a
   partition.

## 6. The freeze

A set is frozen by committing a **content-addressed manifest** *before* any channel
runs against it:

```
fixtures/src/main/resources/frozen/<set-id>/            # set-id = f-<yyyymmdd>-<8hex of manifest checksum>
  manifest.json           # canonical JSON (codec.Canonical): protocol version, story list with
                          # text checksums (canonicalText SHA-256), annotation file checksums per
                          # layer, recall panel checksums, pseudonymized annotator/adjudicator/
                          # participant ids, agreement ceilings per layer, partition map,
                          # provider fingerprints of any candidate-proposal assistance (I1)
  stories/<story-id>/text.txt              # plain UTF-8; the atlas is recomputed, never stored
  stories/<story-id>/L0..L7.json           # one canonical-JSON file per frozen layer
  stories/<story-id>/model.json            # the assembled StoryModel[Validated] (codec), contentChecksum in manifest
  recalls/<partition>/<story-id>/<recall-id>.transcript.json   # TranscriptAtlas (codec)
  recalls/<partition>/<story-id>/<recall-id>.units.jsonl       # adjudicated RecallUnit boundaries
  recalls/<partition>/<story-id>/<recall-id>.targets.jsonl     # RecallUnitTarget per unit
  receipts/agreement.json                   # pre-adjudication agreement per layer per story
  receipts/adjudication.jsonl               # every disagreement and its decision (append-only)
  receipts/audit.json                       # written by the independence auditor after the freeze
```

- **Immutability.** After the manifest commit, no file under `<set-id>/` changes. A
  correction creates `<set-id'>` with a `diff-receipt.json` naming the parent set, the
  changed files, and why; the bench reports which set-id every number came from.
- **Verification.** `embed-bench` refuses to run against a set whose file checksums do
  not match its manifest, or whose manifest commit is not an ancestor of the candidate
  under test.

## 7. What a label may legally be called (ADR 0001 L6)

| Label | Condition |
|---|---|
| **raw** | any score not fitted against adjudicated targets (including cosines, kernel values, rule masses) |
| **benchmark-tuned** | fitted or selected on development/calibration material that is *not* fully adjudicated, or on any diagnostic (synthetic) material |
| **calibrated** | fitted on the calibration partition of a frozen, adjudicated, audited set (leave-story-out), with a named calibration model, ECE (equal-mass bins) and Brier reported, and evaluated on the untouched test partition |

`Credence.calibrationModel` names the set-id and the fitting method; a `Credence` with a
model name that does not resolve to a frozen set is invalid.

## 8. Ceilings and gates

`gates.md` thresholds are provisional until five stories are frozen. From then on the
gate for layer *k* is `min(provisional_k, ceiling_k − margin)` where `ceiling_k` is the
pre-adjudication agreement recorded in `receipts/agreement.json` and `margin` is
declared in the manifest (default 0.02). A gate that would exceed attainable human
agreement is a specification error, not a model failure.

## 9. Operational checklist (per story)

1. Fetch the text as a data file from the manifest's provenance; verify the recorded
   checksum; run `SurfaceAnalyzer`; commit `text.txt`.
2. L0–L7 in order; after each layer: compute agreement, adjudicate, record ceilings,
   freeze the layer file.
3. Assemble `model.json`; it must pass `StoryValidator` under `strict`; record its
   `contentChecksum`.
4. Collect recalls; transcribe; propose boundaries with the segmenter; adjudicate
   boundaries; assign `RecallUnitTarget`s; adjudicate targets.
5. Assign the story's partition per the manifest; run the leakage checklist.
6. When all stories are done: write the manifest, commit, request the independence
   audit, and only then let a channel run.

## Decisions requested from chief

1. **Panel sizes** (§4): ≥ 3 recalls/story for dev+cal and ≥ 5 for test — enough for
   story-macro CIs, or raise test to ≥ 8?
2. **Candidate-proposal assistance** (I1): allow a non-tested LLM provider to propose
   L2 situations/roles for annotators to confirm, or forbid all generative assistance
   for the first frozen set and accept the slower hand pass?
3. **Delayed recall** (§4): in or out of the first set?
4. **Acceptable-target Jaccard stop rule** (§3): 0.80 before adjudication — too strict
   for summary-heavy recalls? Alternative: measure at the level of `(anchor, level)`
   *after* collapsing hierarchy ancestors.
5. **Diagnostic material placement** (§5.7): keep the metamorphic corpus inside the
   set directory under `diagnostic/` (auditable together) or in a separate resource
   root?
6. **Ceiling margin** (§8): 0.02 default acceptable?
7. **Who adjudicates the first set**: the owner + one lab member, with Codex as auditor
   only (the protocol forbids agents as annotators or adjudicators — confirm that is
   intended for the *selection* set; agents may annotate diagnostic sets).
