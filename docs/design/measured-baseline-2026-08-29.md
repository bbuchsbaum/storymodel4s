# What storymodel4s does and does not do, measured — 2026-08-29

**Author:** `claude-storymodel4s` (chief architect)
**Measurements by:** `claude-storymodel4s-m1`, `codex-storymodel-research-spike`
**Status:** Honest baseline. Not a roadmap, not a defect list.

This document exists because the project could state what it *guarantees* and could not state what
it *achieves*. Everything below was measured on 2026-08-29 against `main`, with library defaults,
and each claim names the measurement behind it. It should be read before any number from this
repository is quoted anywhere.

## 1. There is no real human data in this repository

Not one transcript, not one participant recall. Every fixture is authored, on both sides:

- `WarOfTheGhostsModel.scala` — "Hand-authored narrative acceptance fixture… before any automatic
  acquisition runs."
- The recall side — ten "manual recall paraphrases", embedded as Scala values, not transcripts.
- `BirthdayInterview.scala` — "short synthetic Autobiographical-Interview transcript with invented,
  neutral content."

**Consequence.** Every figure this project has published is a correct statement about code
behaviour on prose we wrote. None is a finding about recall. This does not diminish the correctness
work — a ratio-of-sums is right and a mean-of-ratios wrong on any data — but the distinction must
travel with every number.

## 2. Out of the box, the library does not anchor recall to source events

With `DefaultLocalCostModel`, default weights and `externalFloor`, `lexicalJaccard`, **no** synonym
table, **no** canonicalisation, public API only:

> **Of the seven WOG paraphrases declaring a source target, ONE is hit at top-5. None at top-1 or
> top-3.**

*(Corrected from "eight" within the hour. Three paraphrases declare empty targets, not one —
`RoleSwappedFoil`, `NegatedFoil` and `ExternalAssociation`. The first two are **foils**, meant to
anchor as `Distorted` rather than to a plain target, so counting them as ordinary misses was wrong
twice over. The original denominator came from splitting a source file on a string instead of
counting at runtime.)*

**And more than half of that failure is miscalibration, not missing semantics.** The decomposition:

| configuration | top-5 | mean source mass |
|---|---|---|
| defaults | 1/7 | 0.267 |
| `externalFloor` 1.2 only | 2/7 | 0.466 |
| suite weights only | 4/7 | 0.605 |
| suite weights + floor 1.2, **no synonyms** | 4/7 | 0.818 |
| floor 2.0, default weights | 4/7 | 0.988 |

**Calibration alone takes it from 1/7 to 4/7 with no synonym table at all**, and simply raising
`externalFloor` to 2.0 with otherwise-default weights does as well as the suite's tuned vector. The
defaults are wrong in a way that is cheap to fix.

**4/7 is the ceiling across every calibration variant tried.** The remaining three need something
calibration cannot provide. That is the honest boundary between "tune the numbers" and "we need
graded semantics" — and it is where the hand-authored synonym table earns its place.

**It does not pick the wrong node — it declines to anchor.** `ext:Intrusion` is the top state for
seven of ten, at 0.37–0.85 of the row. Summary: 0.812 with 0.073 source mass. Blended: 0.853 with
0.025.

That abstention is the scaffolding working. A model that confidently anchored the wrong event would
be the estimand defect this project exists to prevent. Every rule about `Missing`, `Ineligible`,
refusing to impute, and not scoring absence as agreement is what produces the abstention.

**This is not a defect report.** `WarOfTheGhostsAlignmentSuite:199-201` says so in the code's own
words: *"with no graded semantics the propositional and entity terms are mostly uninformative noise
from the baseline segmenter… TODO(M5): replace with calibrated defaults once embeddings and provider
charts exist; these numbers are provisional, not scientific."* The defaults were never claimed to
work. What was never stated is how far off they are. Now it is.

## 3. What *does* work unaided

**External classification.** `ExternalAssociation` → `ext:Association` at 0.874. The Inference
paraphrase → `SourceConsistentInference` at 0.744. These routes come from discourse function via the
segmenter, not from semantic matching, and need none of the hand-authored resources.

So: the part of the model that says *"this came from outside the story"* functions. The part that
says *"this is that event"* does not.

## 4. The end-to-end path is severed at exactly one link

Both ends run from raw text today. Transcript → `RecallGraph` is two function calls. `SourceView` →
`RecallSignature` is four lines with library defaults.

*(Re-verified after slice E landed at `2d39f35`, which made `RecallGraph` a phantom-typed
private-constructor class. **The call did not change.** `RecallSegmenter.segment(transcript):
RecallGraph[Checked]` still returns in one call and now returns a graph carrying a proof its
invariants were checked. Closing the construction boundary cost the raw-text path nothing — worth
recording, because the usual price of a construction boundary is added ceremony at every call
site.)*

**The missing transformation is `SurfaceAtlas` → `NarrativeGraph` + `NarrativeHierarchy`.** Nothing
produces them — not an implementation, not a stub, not a provider trait. And `acquire`, the module
named for it, declares `.dependsOn(core, proposition)` and **not** `story`, so it cannot name the
type it would have to return.

Independently traced twice: from the build graph, and blind from the raw text by an agent that had
not read the first trace and stopped at the same joint.

## 5. Our one end-to-end demonstration has four layers of hand-authorship

1. The story model is hand-authored.
2. The recall side is hand-authored.
3. The segmentation is **hand-overridden** — `segmentOne` merges the segmenter's two idea units into
   one because expectations are stated per statement, so the number is computed on a unit the
   pipeline does not produce.
4. The synonym bridging is hand-authored. It moves the **lexical** argmax on 5 of 10 paraphrases —
   but its net contribution to the **full aligner** is **one paraphrase of seven**. The other four
   are rescued by predicate, entity, granularity, structural and the HSMM transitions. *(Corrected
   the same day: the "half the cases" figure is true of the lexical channel and false of the
   aligner. Layer 4 is much thinner than it first appeared. The `Sensory` case, made most of in the
   original report, hits **without** the table once the full model runs.)* Caveat carried from the
   measurement: it uses a uniform top-5 source-hit metric, whereas the suite asserts differently
   per case — `subtreeMass` for Vague, segment-level for Summary, `Distorted`-state checks for the
   foils.

Each is individually defensible and each is documented at the point it happens. The **stack** was
invisible because nobody had counted it. If a second acceptance fixture is built, record not another
honest comment but a single statement of *how many human decisions stand between the raw text and
the published number*.

## 6. What a researcher hits, walking in cold

From a spike run by an agent with no prior time in these modules:

- `git status` shows hundreds of untracked coordination files before any research work appears; a
  cold reader cannot tell whether the checkout is safe to use.
- The WOG raw text exists **twice** — as an admitted `.txt` and as Scala source — with no statement
  of which is authoritative.
- There is **no admitted-story loader**: provenance headers have no machine-readable boundary, so
  every consumer writes its own parser.
- `sbt coreJVM/console` with redirected input **exits 0 and executes nothing**. A researcher
  following the README would read process success as program execution.
- **A fixture load order produces a fabricated error.** Touching `WarOfTheGhostsExpectations`
  before `WarOfTheGhostsModel` makes the model fail validation with four *specific and entirely
  fictitious* defects — `claims.unique-ids … 2 claims share id`, `entity-relation.membership-acyclic
  @ entities/null`. Touch the model first and it loads cleanly, 71 situations, suite 114/114 green.
  A consumer that happens to import in the wrong order is told its model is corrupt. The `null`s
  are the signature of a nested-object initialisation cycle; the order dependence is demonstrated,
  the mechanism is not yet proven.

## What would change this document

One honest end-to-end run on real recall data, with estimands reported alongside their support,
against a benchmark someone outside this project would accept. Until then the library can be proven
correct and cannot be shown to measure anything, and no amount of further correctness work closes
that gap.
