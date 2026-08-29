# M1 W4(9) — Frozen-fixture adjudication protocol (rev 2, decided)

**Bead:** bd-01M14YJ8FG15S0R1ART7DTM1XZ (claude-storymodel4s-m1). **Status:** rev 0 was
a design checkpoint; the seven open decisions were taken by the chief architect on
2026-08-28 (`coordination`/`embeddings` post-01M15AM6WTR8N4A38BN3WHB4WS) and are applied
in this revision — see §10. This document is now **operational for set 1**. Companion:
the frozen corpus manifest (`2026-08-28-m1-frozen-corpus-manifest.md`) and the spike
spec rev 2. **Independence audit** of the frozen set is a separate agent-owned bead
(W4: Claude builds, Codex audits); this document is what that audit checks against, and
its content checksum is named in every freeze receipt (§6) so that a post-freeze edit of
the protocol is detectable.

**Sets.** *Set 1* is the first frozen selection set (this revision). *Set 2* is the
planned expansion; items deferred to it are marked **[set 2]** and are recorded in the
manifest under `plannedExpansions` so that the deferral itself is auditable.

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
| Recall panel participant | set 1: 3 (development), 3 (calibration), 5 (untouched test) per story; **[set 2]** test → 8 | the story once (read or heard), then nothing |
| Transcriber | 1 per recall | the audio; the story text is withheld |
| Independence auditor (an agent, e.g. Codex) | 1 per set | everything, read-only, after the freeze |

Annotators, adjudicators, transcribers, and recall participants are humans (Law I4).
Staffing of set 1 is recorded in the manifest, pseudonymized.

**Law I1 (channel independence).** No embedding model, chart provider, WL kernel,
reranker, or LLM judge that is *or could later be* under test may touch the
construction of a frozen set. **In set 1 no generative assistance of any kind is
used.** The only machine assistance permitted is deterministic, parameter-free code in
this repository: the `core.SurfaceAnalyzer` atlas (surface units, tokens, mention
candidates) and the baseline `RecallSegmenter` boundary proposals (§4). Such code may
*propose*; only the two human annotators, independently, confirm or reject, and every
proposal is logged with the code's build fingerprint in the set's receipt. A set built
with any tested channel's help is **diagnostic**, never a selection set.

**[set 2]** Candidate proposals from a *non-tested* LLM provider (e.g. L2 situations and
roles for annotators to confirm) are deferred to set 2 and are admissible there only
with a **written independence argument** filed with the set's receipt before the first
proposal is generated. The argument must (a) name the provider fingerprint and the
frozen model version; (b) show that the provider is not, and by policy will not be,
a tested channel in any bench that consumes the set — a different vendor family and an
explicit exclusion line in ADR 0001 §D7; (c) state how proposals are logged; and (d)
commit to reporting the annotators' per-layer **rejection rate** of proposals, so that
rubber-stamping is detectable (a rejection rate below a threshold declared in the
argument downgrades the layer to *benchmark-tuned* material). The independence auditor
signs the argument as part of I1.

**Law I2 (blindness).** Annotators and adjudicators never see a model's alignment,
chart, candidate list, or score for a story they annotate. Recall participants never
see annotations. Transcribers never see the story text.

**Law I3 (no self-adjudication).** The adjudicator of a layer is not one of its two
annotators.

**Law I4 (no agent annotators).** No agent (Claude, Codex, or any other model-driven
actor) is an annotator, adjudicator, or transcriber for a selection set. The agent role
is **auditor only**: checking I1–I3 and the leakage checklist (§5) and writing the freeze
receipt's `audit.json` (§6). Agents may annotate material under the `diagnostic/` root
(§5), which is never selection evidence.

**Law I5 (corpus independence — no memorized sources).** *A story whose text or whose
published summaries plausibly sit in a tested channel's training data cannot back a
calibrated claim.* A frozen set exists to select defaults (ADR 0001 §D7); every tested
LLM channel has plausibly memorized canonical stories and their study-guide summaries,
while the free baselines and the structural channel have memorized nothing. Scoring both
on a famous text is therefore not a comparison of alignment quality, and a default chosen
that way is an artifact of exposure. *The War of the Ghosts* was obscure, and that
obscurity was load-bearing.

Consequences, binding on every set:

1. Each story carries a measured `contaminationRisk` of `low | medium | high`, scored by
   the reproducible procedure the corpus manifest defines (a frozen site list, counted
   summary surfaces, recorded with count and date) — never by anyone's impression of how
   famous a story is.
2. In the **untouched-test** partition, which is the partition that selects a default,
   `high` is disqualifying and `medium` requires a written justification in the freeze
   receipt. Other partitions may hold `medium`; `high` anywhere must be named in the
   report.
3. A report from a set containing any `high` story may not be labelled **calibrated**
   (§7).
4. **The leakage control is the real defence, and it is mandatory before any default is
   selected**: within a channel, compare its scores on `low`-risk against `high`-risk
   stories *relative to the free baselines on the same stories*. A channel that gains
   where the baselines do not is exhibiting prior knowledge, not alignment skill. The
   score in (1) is triage — it proxies how much summary text exists on the open web, not
   what any provider actually trained on — and only this control measures the effect.
5. Published summaries, study guides, and student précis are **never** recall data. They
   are edited study artifacts, not free recall from a person who read the story under
   known conditions.

**Law I6 (the set must be able to falsify the model).** A selection set contains at
least one story with genuine discourse-level **anachrony** — story-world order
materially different from telling order (flashback, frame narration, reconstruction of a
past event). This library models partial, unbalanced, hierarchical alignment including
omission, merge, split, **reorder**, revisit, and external destinations. On an all-linear
corpus every recall route looks the same, so the reorder half of the model is not merely
uncertain, it is **unfalsifiable** — and an unfalsifiable number must not be published as
calibrated. The anachrony story is subject to I5 like any other, and its selection
records why its structural confounds (heavy dialect, archaic orthography, extreme length)
were judged acceptable, since a confound that makes a segmentation failure
indistinguishable from a reorder failure defeats the purpose of the slot.

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
- Acceptable-target agreement is measured twice per unit and both are stored in
  `receipts/agreement.json`: **raw Jaccard** over the two annotators' `targets` sets, and
  **ancestor-collapsed Jaccard**, computed after projecting both sets to a common
  hierarchy level. The projection: let `ℓ = max(level(primary_A), level(primary_B))`
  under the frozen L6 primary segmentation; replace every target `(node, level)` with
  `(ancestor_ℓ(node), ℓ)` (a node already at or above `ℓ` is unchanged) and de-duplicate.
  Two annotators who anchor a summary unit at a scene and at its member events
  therefore agree; two who anchor at different sibling events do not. Exact match on
  `primary` is reported separately. **The stop rule is ancestor-collapsed Jaccard ≥ 0.80
  (story mean, before adjudication), with raw Jaccard reported alongside**; a story
  whose raw Jaccard is more than 0.15 below its collapsed Jaccard is flagged in the
  receipt as *summary-heavy* so the gap is visible to the bench.

## 4. Natural human-recall panel

- **Collection.** Set 1 collects **immediate free recall only**: one exposure (read
  silently, or listened to a neutral recording — recorded per story), then free recall
  with the instruction "tell the story in your own words, in as much detail as you
  can"; no probes. **[set 2]** Delayed recall (≥ 24 h) is out of set 1; it is planned for
  set 2 as a separate panel with its own participants and its own agreement receipts.
- **Panel size (set 1).** Development: 3 recalls per story; calibration: 3 per story;
  untouched test: 5 per story. **[set 2]** The untouched-test panel is raised to 8 per
  story (story-macro CIs), recorded in the manifest as a planned expansion. Participants
  are never reused across partitions or across sets' panels for the same story.
- **Transcription.** Verbatim; disfluencies, false starts, hedges, and self-repairs
  **retained** (they are evidence for `ExpressedUncertainty` and repair handling);
  interviewer speech, if any, on its own turn; timestamps per turn when audio exists;
  UTF-8, one `TranscriptAtlas` per recall.
- **Segmentation is adjudicated before targets.** The baseline `RecallSegmenter` may
  *propose* idea-unit boundaries (it is deterministic, has no learned parameters, and
  is not a tested channel — the only proposal mechanism admitted by I1 for set 1), but A
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
7. Any metamorphic/synthetic material lives under the separate resource root
   `fixtures/src/main/resources/diagnostic/` (own manifest, own checksums), **never
   inside a frozen set directory** `frozen/<set-id>/` and never under a partition. A
   frozen set's manifest lists no diagnostic file; a diagnostic manifest may *reference*
   a frozen set-id read-only.
8. The protocol checksum named in the manifest equals the checksum of this document at
   the freeze commit (§6).

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
                                            # (raw + ancestor-collapsed Jaccard for targets)
  receipts/adjudication.jsonl               # every disagreement and its decision (append-only)
  receipts/pd-signoff.json                  # one signed line per story: PD basis, edition, signer, date
  receipts/audit.json                       # written by the independence auditor after the freeze

fixtures/src/main/resources/diagnostic/     # separate root: metamorphic/synthetic sets, Aesop micro-set,
                                            # agent-annotated material; own manifest; never selection evidence
```

The manifest's top-level fields, in addition to the per-file checksums above, are:

| Field | Meaning |
|---|---|
| `protocolVersion` | this document's revision (`1` for set 1) |
| `protocolChecksum` | `canonicalText` SHA-256 of this document at the freeze commit — a later edit of the protocol is detected by recomputing it |
| `setId`, `parentSetId` | content address; the parent for correction sets |
| `panelSizes` | `{development: 3, calibration: 3, test: 5}` for set 1 |
| `recallConditions` | `["immediate"]` for set 1 |
| `ceilingMargin` | `0.02` |
| `assistance` | build fingerprints of the deterministic proposers used (I1); empty of any generative provider in set 1 |
| `plannedExpansions` | set 2 items: `test → 8`, `delayed recall`, `non-tested-LLM proposals (with independence argument)` |
| `staffing` | pseudonymized annotator/adjudicator/transcriber/auditor ids with role; no agent in a non-auditor role (I4) |

- **Immutability.** After the manifest commit, no file under `<set-id>/` changes. A
  correction creates `<set-id'>` with a `diff-receipt.json` naming the parent set, the
  changed files, and why; the bench reports which set-id every number came from.
- **Verification.** `embed-bench` refuses to run against a set whose file checksums do
  not match its manifest, or whose manifest commit is not an ancestor of the candidate
  under test.
- **Protocol drift.** At every run the bench recomputes the checksum of this document at
  the candidate's HEAD and compares it with `protocolChecksum`. A mismatch is printed in
  the report header as *protocol drift* with both checksums; numbers produced under a
  drifted protocol may not be labelled **calibrated** (§7) until a successor set is
  frozen under the revised protocol (or the revision is shown, in a `diff-receipt.json`,
  to leave every rule the set relied on unchanged).

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
declared in the manifest as `ceilingMargin` — **0.02 for set 1 (decided)**. For the
recall-unit target layer the ceiling is the ancestor-collapsed Jaccard (§3). A gate
that would exceed attainable human agreement is a specification error, not a model
failure.

## 9. Operational checklist (per story)

0. **PD sign-off first.** No story text enters the repository until the story's
   PD-basis line in the manifest's sign-off checklist is signed (signer, date, edition
   checked). Text is fetched from the listed provenance as a data file; it is never
   typed in, paraphrased, or regenerated by an agent (AGENTS.md; the WOG precedent).
1. Fetch the text as a data file from the manifest's provenance; verify the recorded
   checksum; run `SurfaceAnalyzer`; commit `text.txt` together with the signed line in
   `receipts/pd-signoff.json`.
2. L0–L7 in order; after each layer: compute agreement, adjudicate, record ceilings,
   freeze the layer file.
3. Assemble `model.json`; it must pass `StoryValidator` under `strict`; record its
   `contentChecksum`.
4. Collect recalls; transcribe; propose boundaries with the segmenter; adjudicate
   boundaries; assign `RecallUnitTarget`s; adjudicate targets.
5. Assign the story's partition per the manifest; run the leakage checklist.
6. When all stories are done: write the manifest (including `protocolChecksum`),
   commit, request the independence audit (I1–I4 + leakage checklist), and only then
   let a channel run.

## 10. Decisions (chief, 2026-08-28)

Taken by the chief architect (`claude-storymodel4s`) in post-01M15AM6WTR8N4A38BN3WHB4WS
and applied in this revision; each is binding for set 1.

| # | Question (rev 0) | Decision | Applied in |
|---|---|---|---|
| 1 | Panel sizes | **3 / 3 / 5** recalls per story (development / calibration / untouched test) for set 1; untouched test raised to **8 in set 2**, recorded as a planned expansion | §1, §4, manifest `panelSizes`, `plannedExpansions` |
| 2 | Generative candidate proposals | **None in set 1.** Only the deterministic atlas (and the deterministic segmenter for boundaries) may propose; humans confirm. The non-tested-LLM option is removed from set 1 and deferred to set 2, admissible there only with a written independence argument | I1, §4 |
| 3 | Delayed recall | **Out of set 1** (immediate free recall only); set 2 | §4, manifest `recallConditions` |
| 4 | Target stop rule | **Jaccard ≥ 0.80 after collapsing to hierarchy ancestors**, with raw Jaccard reported alongside | §3, §8 |
| 5 | Diagnostic material | Separate root **`fixtures/src/main/resources/diagnostic/`**, never inside a frozen set directory | §5.7, §6 |
| 6 | Ceiling margin | **0.02** | §8, manifest `ceilingMargin` |
| 7 | Agents as annotators/adjudicators | **Excluded.** Agent role is auditor only: I1–I3 checks and the freeze receipt; agents may annotate diagnostic material only | Law I4, §1 |

Two additions requested with the decisions:

- **(a) Sign-off checklist.** Every "verify before freeze" item in the manifest is now a
  checklist line with a sign-off field; no story text enters the repository until its
  PD-basis line is signed, and text is never agent-regenerated — data files only (§9.0,
  manifest "Sign-off checklist").
- **(b) Protocol checksum in the freeze receipt.** The manifest names this document's
  content checksum (`protocolChecksum`), and the bench reports protocol drift (§6).

Dissent on record: none. Rev 0's open questions are closed; new questions go to
`coordination` as bead proposals, not into this document.
