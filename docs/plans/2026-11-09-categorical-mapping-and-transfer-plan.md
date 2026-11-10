# Categorical mapping artifact, then transfer evaluation (2026-11-09)

This plan replaces slice 4 of the [port design](2026-11-08-llm-mapper-port-design.md) and fixes
the order of work after it. It was settled with the owner on 2026-11-09 after an external review.
Slices 1-3 of the port have landed (main fe1b73929). Mote holds status.

## Where this leaves the direction

Direct LLM mapping is a primary mapping method. The next milestone is a reproducible categorical
mapping artifact with independent inspection and source-support lookup. Transfer evaluation
follows on verified reference data. Rich source and recall event representations remain a core
objective, developed through bounded, evaluated capabilities. Structured decoding is introduced
where experiments show a benefit.

What the current numbers do and don't measure:

- 75-79% on Sherlock development measures **narrative scene coding only**. Referent scenes, cited
  rows, who-did-what, within-scene detail and timing are unmeasured.
- The model reads the surrounding recall, so its labels are not an independent measure of recall
  order. Any analysis of recall order that uses them must say so.
- The order-prior experiment slightly lowered accuracy, so a structured decoder is not mandatory.
  "The LLM proposes and a structured mapper checks" remains a hypothesis.

## Order

| Step | What | Depends on |
|---|---|---|
| 4a | Categorical decision support in `align`, with codec and consumer changes | - |
| 4b | Recorded responses to a typed, saved, independently readable artifact | 4a |
| T | FilmFestival preparation, then a frozen transfer evaluation | prep: none; run: 4b |
| E | Evidence improvements (speaker labels first), each a new packet version | 4b |
| R | First rich-event capability: who-did-what in recurring scenes | 4b |

FilmFestival preparation runs in parallel and neither delays 4a nor widens step 4.

## Step 4 objective

> From existing recorded model exchanges, produce a checked mapping artifact that preserves the
> model's full localization answer, accounts for every requested recall unit, and can be
> independently loaded to inspect assignments, citations and source support.

Scope: offline replay and export of the existing v2 localization output. Fidelity assessment
(slice 5), live-provider operation and StoryAtlas presentation are separate deliveries. Stop
when the acceptance below passes. No producer framework, ensemble abstraction or general viewer.

## What the output means

These meanings are fixed before any class is named.

| Information | Meaning |
|---|---|
| Narrative assignment | The scene the retelling is currently narrating (prompt item 1) |
| Referent assignment | The scene containing the specifically mentioned content (prompt item 2) |
| Alternatives | Model-reported narrative alternatives, numbers kept verbatim; not a distribution |
| Confidence | A separate self-reported observation; absent stays absent, never 0 |
| Row citations | Rows the model cited, bound to the source they index |
| Processing outcome | Whether a valid answer was obtained for that unit, and if not, why |
| Provenance | Accepted exchange's recording key, outline and system SHA, model, parser version |

- Narrative and referent are two named interpretations (D2). Neither becomes the scientific
  `Reference` member because of its name.
- The v2 prompt asks for narrative alternatives only. They are never attached to the referent.
- The v2 reply does not say which assignment its rows support. That ambiguity is kept: rows are
  cited by the unit, not by an interpretation. A cited row outside the narrative scene can be
  legitimate when the referent differs.
- A citation that resolves to a real row is a valid reference. It is not evidence that the row
  supports the claim.

## 4a: categorical decision support

`UnitOutcome.Decision` today carries a numeric `DecisionBasis`, a raw argmax and a decoded mass,
all resolved from `UnitMeasures`. A categorical producer has none of these.

Design:

- `Decision` gains a sealed support with two cases:
  - **numerical**: today's `basis`, `rawArgmax` and `decodedMass`, unchanged in meaning and
    built only by `UnitOutcome.computed`;
  - **categorical**: the producer's policy and nothing numeric. No scores, argmax, target mass
    or calibrated probability. Optional raw observations never become its basis.
- A new checked factory `UnitOutcome.categorical` takes the unit, the chosen destination or an
  explicit no-assignment reason, the producer policy, links and stages. It validates unit,
  source and target membership and the link and stage bindings, as `computed` does. It grants no
  execution authority.
- A new `DecisionOrigin` case names a producer's categorical choice, so it can't be read as an
  argmax, a structured decode or a gap fill.
- Calibration stays `Unavailable` for categorical decisions.
- Consumers that need a number (`TemporalQuery`, digest rendering, the surface workspace reader)
  handle the categorical case or return a typed missing-measure refusal. Nothing substitutes 0.
- Wire: the decision encodes its support kind. The mapping record and exchange schema versions
  move to v0.2, and v0.2 readers still read v0.1 (numerical only). A v0.1 reader refuses v0.2
  by version, so it cannot silently reinterpret a categorical record.
- ADR 0022 gets an implementation note recording the decision and the rejected alternative
  (fabricating a one-hot numeric basis).

Acceptance:

- A consumer outside `storymodel4s.align` constructs, saves and reloads a score-free categorical
  choice.
- Invalid bindings are refused: foreign unit, destination outside the source, link or stage
  mismatch, empty no-assignment reason.
- Courts compile-refuse `apply`, `copy`, `fromProduct` and `Mirror` for the new types from
  outside the package, each with a positive control.
- Mutation: removing the membership check fails a named test.
- Existing numerical behaviour is unchanged: the existing align, codec, laws and fixtures suites
  pass unmodified.
- Gate: the `reference-scope.sh` modules on JVM, JS and Native; storyatlas4s compiles and
  passes its tests against the candidate.

## 4b: recorded responses to an independent reader

Parse the recorded response text again, before the narrative-only projection. The recorded
exchange stays the audit source.

Outcome rules:

| Recorded situation | Interpretation |
|---|---|
| Unit omitted from every accepted reply | Failed processing: provider output omitted the unit |
| Field absent, wrong type or out of range | Invalid output for that field (never a no-assignment) |
| Explicit `"narrative": null` | The model reported no narrative assignment |
| Explicit `"referent": null` | No specific referent reported, as the prompt allows |
| `conf` absent | Confidence unavailable |
| Three unparsable attempts for a chunk | Failed processing for its units, with that cause |
| A recorded exchange is missing or failed | The run refuses; no bundle is written |

An explicit null is recorded as what the response says, without inferring intention.

- The arm projection (`AgentReplay.participant`, narrative only) stays for slices 1 and 3. It no
  longer writes null for an omitted unit or a missing field, so the arm import records those as
  a provider failure and an invalid output. Development has neither, so its arm is unchanged.
  The typed path does not read the projection.
- The artifact is a bundle (`storymodel4s.agentmapping.bundle/v1`) holding:
  - a narrative mapping record and a referent mapping record (codec v0.2, categorical);
  - per-unit observations: alternatives, confidence, rows, response key, attempt;
  - provenance: model, provider, outline and system SHA, chunk size, parser version;
  - the source dictionary: each row's id, scene, part and clock span, and its annotation text.
- The bundle is written under `data/` (rule 14d: it holds film and recall text).
- A short Python reader (`tools/agentmapping/read_bundle.py`, standard library only) loads a
  bundle, selects a unit, prints both interpretations and citations, and resolves cited rows.

Acceptance:

- Every development unit's narrative label equals the frozen slice-1 arm, unit by unit.
- Referent, alternatives, confidence, citations, provenance and processing distinctions survive
  save and reload.
- A synthetic fixture (no film or recall text) where narrative and referent differ, a citation
  falls outside the narrative scene, and omitted, invalid and explicit-null answers all occur.
  The development replay is all-successful, so only this fixture tests those distinctions.
- The Python reader runs on the fixture in a test and on the development bundle by hand.

## T: FilmFestival transfer evaluation

The references exist: 19 workbooks match their recorded hashes, and the derived file has 3,226
utterances from 15 participants, 2,809 scene-specific. These participants were already used for
development (see [the diagnostic results](2026-09-05-filmfestival-diagnostic-results.md)), so
this is a transfer evaluation on previously used data, not a confirmation.

Preparation, independent of any model prediction:

1. Verify global scene ids against the annotation target dictionary, including the run-2 offset.
2. Fix observation units, gold joins, eligibility and exclusions.
3. Keep multi-scene reference sets and film-only labels.
4. Confirm which media editions exist for the automatic-input condition.
5. Record prior exposure, and freeze the adapted prompt, inputs, model, decoding and retry
   policy before scoring.

Reporting: film identity and scene agreement separately. For multi-scene gold, one prediction
matching a listed scene is target agreement, not recovery of the whole reference. Report
participant averages, per-film results, denominators, failures and abstentions. Keep the
annotation-assisted and automatic-input conditions separate, with a common-film comparison
where their films differ. If scene correspondence stays unresolved, evaluate film identity
alone under that narrower claim.

## E and R

- **Evidence improvements.** Speaker-labelled dialogue first, then gist-detail severity. Each is
  a new packet version scored against the frozen v2 baseline.
- **Fidelity.** The graded verifier needs fresh items before any scientific use, with supported,
  incorrect and unresolved cases, not only model-flagged errors.
- **First rich-event capability: who-did-what in recurring scenes.** Take a bounded set of
  difficult scenes and recalls. Represent each source event once, keep the participant's
  recalled assertions separate, and test whether the result distinguishes actor, action and
  recipient combinations that scene labels cannot. Include correct, role-swapped and partly
  wrong recalls. Grow the event representation when it shows useful grounding, analysis,
  inspectability or saved computation; scene accuracy is one possible benefit, not the only one.

## Revision 2026-11-10: the scientific preview

Steps 4a, 4b and T are done (see [Where things stand](#where-things-stand)). This revision sets
the next milestone and replaces the E and R ordering above. It follows an external review of
2026-11-10 and the owner's requirement that outputs be uniform across datasets and conditions.

### The milestone

A **scientific preview**: a researcher-usable workflow with a stated claim boundary.

> A researcher can supply timed film descriptions and recall transcripts, obtain auditable
> scene-localization results through a documented command, inspect every assignment and
> failure, and reproduce the saved analysis without another model call.

- Video-derived film input uses the same workflow as a separately qualified input mode.
- Recall audio is a later adapter. It adds transcription error and its own validation problem.
- The preview is a localization and annotation tool. Other uses of its labels are experimental
  until they have their own evidence (see [Claim boundary](#claim-boundary)).

### Where things stand

| Result | Figure |
|---|---|
| Sherlock development, narrative scene | 79.2% |
| Sherlock registered read, narrative scene | 75.4%, against 55.2% for the old mapper |
| FilmFestival annotation-assisted, referent scene | 86.4% and 84.8% by session |
| FilmFestival automatic input, referent scene | 78.5% and 78.9% |

- **Sherlock development:** 10 participants; the prompt was tuned here.
- **Sherlock registered read:** 5 participants, 635 units. It is new participants on the same
  film, and coarse gold statistics had been seen beforehand.
- **FilmFestival annotation-assisted:** a transfer test; another method had used these
  participants.
- **FilmFestival automatic input:** scored; the independent check is pending.

These are not one progression. Sherlock's reference is narrative coding with one scene per unit.
FilmFestival's is referent coding, and a multi-scene reference counts a hit on any listed scene.
The units, the grain and the reference differ. Haiku 5.5 is 1 to 2 points below Opus 5.5 with
annotations and 4 to 9 points below with automatic input.

What exists and is not yet connected:

- an ordinary-file command surface (`studyPrepare`, `mappingRun`; see
  [the command guide](../guide/mapping-facade.md)) and release qualification work;
- the categorical bundle and its Python reader, fed only by a Sherlock-specific loader;
- FilmFestival runs in Python tools that write their own reply and score files;
- record and replay of model exchanges in the library, with no live call path.

### Deliveries

1. **Release contract.**
   - Accepted when inputs, output meanings, supported analyses, governance and exclusions are
     written and agreed, and every retained ticket has an observable acceptance criterion.
2. **One recorded pipeline.**
   - Sherlock and FilmFestival pass through the same source, recall and outcome contracts and
     the same reader.
   - Recorded predictions survive unit by unit.
   - A third, synthetic study needs no corpus-specific branch.
3. **Live execution.**
   - A fresh authorized study runs from the documented command, with recording, bounded
     retries, resume, model identity and usage receipts.
   - Offline replay makes no model call and reproduces the output.
4. **Ordinary transcripts.**
   - The existing segmenter is connected, with offsets and provenance kept.
   - Supplied and automatic units are compared on both corpora, with segmentation error
     reported apart from mapping error.
5. **Measurement qualification.**
   - A report says which uses are supported, unsupported or inconclusive, with uncertainty and
     full denominators.
   - Its evidence is blinded double coding, a frozen prospective evaluation, baselines and
     error analysis.
6. **Independent usability.**
   - Someone outside the implementation completes a new study and a documented analysis
     without help.
   - That covers installation, a tutorial on shareable data, Python and R readback, cost and
     run time, troubleshooting and methods guidance.

Order: 1 to 3 are the engineering priority. Preparation for the human coding in 5 starts
alongside them and does not wait for integration. Documentation grows with the workflow.

**First slice of delivery 2:** FilmFestival recorded responses, into the shared categorical
bundle, read by the existing independent reader, with every outcome preserved and time answers
handled explicitly. It tests the common contract before live calls make failures harder to
diagnose.

Rules that carry over to every delivery:

- Narrative and referent stay separate. Scene answers and time answers stay distinguishable.
  Missing support and processing failures stay distinct from a reported non-assignment.
- A new dataset or input condition is an input adapter into the same prediction format, scorer
  and report. Where that is impossible, the document says so.
- Evaluation code is frozen and independently reviewed before a corpus is sent to a model, and
  results are independently recomputed before they are reported. Both caught real defects in
  each FilmFestival run.

### Measurement qualification (delivery 5)

- **Double coding gives reliability under a rubric, not a universal ceiling.** Narrative and
  referent coding are defined separately. Coders do not see model answers. Disagreements are
  kept before adjudication. Ambiguous and unassignable recalls are included. The budget is set
  after a small timing pilot.
- **"Unseen data" names its target.** New participants, new films, new annotation styles and
  automatic inputs are different tests. For the preview's promise the decisive one is a frozen
  pipeline on unfamiliar films and participants. Whether a model saw a film in training may
  stay unknown and is reported separately.
- **Baselines travel with every score.** On FilmFestival a context-free word match scores about
  39% and knowing the previous reference scene 75% to 83%. A model given only film titles and
  lengths scored 37% strict, far above the 7% of a uniform guess. Scene agreement on ordered
  recall has a large floor that comes from structure.

### Claim boundary

Scene agreement does not validate every downstream measure.

| Use | Status for the preview |
|---|---|
| Which scene a recall unit refers to or narrates | supported, with stated agreement and limits |
| Inspecting and correcting assignments | supported |
| Recall order and chronology | not supported |
| Fidelity of recalled detail; who did what | experimental |
| Omissions; comparisons between participants or groups | not yet evaluated |

- Order is unsupported because the mapper reads the surrounding recall, so its labels are not
  an independent measure of order.
- The fidelity verifier has not been tested on fresh items.

### Backlog

Mote holds 163 unfinished tickets (130 open, 24 in review, 7 blocked, 2 in progress). Each gets
one of three dispositions: required for the preview; retained research programme, with its
rationale kept; superseded or duplicate. A ticket is closed only when its requirement is
obsolete or met. Work waiting on integration is part of the triage.

### After the preview

The event-representation programme continues as bounded, evaluated capabilities. The first is
who-did-what in recurring scenes, evaluated on fresh examples once the common workflow is
usable. Speaker-labelled evidence and fidelity follow as new packet versions against a frozen
baseline.
