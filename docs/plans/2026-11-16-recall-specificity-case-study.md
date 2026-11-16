# Case study 1: recall-time specificity, and what it asks of the library

Status: proposal, 2026-11-16. Mote bead `bd-01M7JGB0JDZ5N4TB6J6YFF8GW7`. Branch
`claude/recall-precision-20261116`. Research harness: `tools/case-studies/recall-precision/`.
Progress page (private artifact): <https://claude.ai/artifact/URzr9iMwcBRmrs7Yi5htqQ>.

The owner asked for a series of case studies. Each solves one analysis problem end to end,
under critique, and then names what the library is missing. This is the first. Its question:
for the Sherlock recall scan, can we give a continuous series over recall time of how
precisely the speech points into the film?

Development participants only. Nothing in this note opens the untouched test side.

## What the case study found

| Quantity | Result | Standing |
|---|---|---|
| Where the speech points (top scene), content-local | 76% agreement with the human scene coding (95% CI 73–79), ECE 0.07 | Usable |
| Same, whole-recall analyst stream (v3 mapper) | 77%; saves 0.44 bits per word over content-local | Usable, separate regressor |
| Breadth: film extent of the referenced hypothesis | ICC 0.85 across two samples; form explains 44% of variance, scene 6%, speaker 4% | Reliable; floor at the annotation's row grain; not specificity |
| Competing hypotheses | Flag κ 0.69; magnitude ICC 0.10; agreement 58% when on vs 79% when off | Use as a flag only |
| Speech rate | \|ρ\| < 0.05 with every series; all VIF ≤ 1.96 after HRF convolution | No confound |
| Specificity proper (distinguishing detail) | Iteration 5 in progress | Open |

Sentence embeddings (MiniLM) reached 35%. The construct was sound but the encoder was too weak.
An LLM shown a recall unit with only its two preceding units, in shuffled batches with opaque
ids, recovers almost all of the whole-recall mapper's accuracy.

Four critique rounds caught real defects: tie-broken AUROC and rank transforms, an id leak
that exposed recall order, breadth leaking into uncertainty, and confidence that ignored the
fitted floor. Each fix carries a known-answer test or a recorded rerun in the harness.

## How it maps onto the measurement contract

The [measurement and projection contract][contract] anticipated most of this:

[contract]: 2026-11-16-detail-mapping-plan.md#measurement-and-projection-contract

- **Three quantities.** Breadth of reference, uncertainty between hypotheses and resolution of
  the source annotation are separate quantities. The case study measured all three and found
  them separable: breadth and the competing-hypotheses flag correlate at ρ −0.04, and the
  annotation floor shows up as breadth's correlation with row duration.
- **Reference meanings.** Single, joint, broad and alternative were named but not produced.
  The case study produced them, with word anchors.
- **Level-holding allocation.** The contract says none exists, and the regressors need one.
- **Concentration summaries.** These keep their own acceptance gate in
  [temporal queries](../temporal-queries.md). That gate summarizes allocated mass, which is a
  different quantity from the per-reference measures here, so this study does not close it.
- **Exposed ids.** ADR 0022 already requires a rendering to record its exposed item ids.
  The id leak shows why.

## Gaps and where each belongs

[ADR 0030](../adr/0030-detail-account-and-derived-analysis.md) landed on main the same day. It
already decides most of the vocabulary the case study needed:

- reference meanings: single, joint, broad, alternative, unresolved, typed external;
- content kinds: participant/action, temporal, spatial, perceptual, dialogue, mental state,
  discourse;
- Extended and RepeatedOrCategoric address classes;
- the separation of content, address, status and fidelity.

The case study's labels map onto it:

| Case-study label | ADR 0030 term |
|---|---|
| detail types: who, action, place, time, perceptual, dialogue | participant/action, spatial, temporal, perceptual, dialogue kinds |
| talk: metamemory, commentary, fragment, repair | discourse (with mental state where reported) |
| occurrence: repeated, generic | RepeatedOrCategoric |
| extent: stretch | Extended |
| competing hypotheses | alternative |
| summary (gist) | broad |

What ADR 0030 leaves open (type placement, a response-frequency measure kind, held-level
allocation, producer wire compatibility) is exactly what the case study ran into. The draft
[ADR 0031](../adr/0031-reference-measures-and-held-level-projection.md) proposes those
decisions from the case-study evidence. The owner decides.

| # | Gap | Module | Builds on | Decided by |
|---|---|---|---|---|
| G1 | Anchored references with word-span anchors, film support, meaning, occurrence | ADR 0030 account (placement open) | `TextSpan`/`SpanSet`, `SourceNodeRef` | ADR 0030 |
| G2 | Discourse spans (metamemory, commentary, fragment, repair) inside episodic units | ADR 0030 account | content kind `discourse` | ADR 0030 |
| G3 | Reference measures: alternatives present, U, extent per occurrence, occurrence count, support grain | `align` (portable) | `TemporalQuery`, `TemporalSupport` | draft ADR 0031 §1 |
| G4 | Recorded calibration of self-reported weights | `align` | reserved `CalibratedProbability` and `CalibratedEvent`, ADR 0022 | draft ADR 0031 §2 |
| G5 | Held-level recall-time projection; missing distinct from not applicable | `align`, then a `features` time-bin target | `RecallTiming`, `TemporalExposure`, scanner crosswalk | draft ADR 0031 §3 |
| G6 | Content-local producer condition and numeric weights in the agent bundle | `pipeline` agentmapping | `AgentAnswers`, bundle v2, ADR 0022 rendering identity | draft ADR 0031 §4 |
| G7 | Scoring kernels with known-answer tests | research tooling, later `bench` | `tools/recall-study` scorer | none |

Placement follows the rules in AGENTS.md:

- Portable measures stay in `align`.
- LLM calls, prompts, the leave-one-participant-out search, bootstraps and controls stay in
  research tooling.
- The library stores fitted calibration parameters with a receipt and applies them
  deterministically (rule 13).
- `features` keeps depending only on `core`.

## Proposed slices, in landing order

The owner and the case study agreed a narrowed scope (ADR 0031 revision 4):

- no published alternative entropy;
- no calibration artifact;
- the onset-point projection only.

Each slice is about a day's work with one acceptance criterion. Beads are children of the case
study bead.

1. **The owner decides draft ADR 0031.**
2. **G3 reference measures in `align`** (`bd-01M7JRBR9XAK79B448ZMYPEKBN`).
   - It covers: alternatives present, member count, extent per constituent on a declared axis,
     the annotation-floor flag, occurrence count and typed unavailable values.
   - Acceptance: laws on JVM, JS and Native, plus a mutation that makes extent read other
     members fails a named law.
3. **G5 onset-point projection** (`bd-01M7JRCJ82DJGRAEWBM0H2X78T`).
   - It covers: word membership by exact UTF-16 support, a distinct-reference reducer and a
     per-bin ledger.
   - Acceptance: the contract's discriminating and refusing controls.
4. **G6 view rule and recorded producer condition** (`bd-01M7JRDAT1101VTF6YSC12RSFR`).
5. **G1/G2 account types.** These follow ADR 0030's own implementation beads.

Research-tooling work runs in parallel:

- the shared scoring kernels (G7);
- the harness defects: token alignment, tempered ICC, the per-bin ledger and the dataset
  adapter;
- the validation beads.

## What stays open scientifically

- **Breadth is film extent, not specificity.** Iteration 5 adds a distinguishing-detail channel
  by type: who, action, place, time, object, dialogue, perceptual. That channel is closest to
  the owner's original "what, where, when" precision.
- **No outside validation of breadth or the flag exists yet.** It belongs in ADR 0030's joint
  coding rubric (`docs/design/detail-coding/`), whose human pilot is pending. The linguistics
  reviewer's design would provide it:
  - two human coders on about 300 stratified references;
  - repeated-motif scoring;
  - text manipulations.

  The human coding needs the owner's time; the manipulations do not.
- **Between-participant agreement at fixed film moments.** This is untested for every series.
- **One frozen read.** Every number here is development data. A claim needs one frozen read on
  the untouched participants, under the existing protocol.
