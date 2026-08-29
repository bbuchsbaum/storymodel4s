# Araby — narrative acceptance fixture (stress-case)

**Bead:** `bd-01M15RA8FVQ0XERMC9VMZD8D35`
**Publication class:** Diagnostic-only. Never Calibrated.
**Text:** none in this file. No Dubliners sentences, paraphrases-as-quotes, or
student summaries enter the repo until the owner signs
`docs/design/story-text-admission-checklist.md` against **the edition actually
transcribed** (1914 Grant Richards or a scan of it — not a modern annotated
edition). Design and this table do not need the text.

This is a researcher-reviewed **narrative acceptance fixture** in the *War of
the Ghosts* role (AGENTS.md fixture policy 14c): plain-language expectations
backed by mechanical checks. It is not an AMR gold, not a recall corpus, and
not a calibration story. Fame is contamination of a calibrated measurement.

---

## What Araby stresses that WOG cannot

WOG already tests: speech-embedded intention, hypothesized causality, group
vs member agency, `ReaderAt` as a reader-time visibility filter, Distorted /
External alignment foils, and a narrated-world root that is not "objective
truth".

Araby must earn its keep on two properties the chief confirmed, with the
mechanism corrected:

1. **Dual self.** Two epistemic frames in one discourse: the boy's lived
   experience (as *content*) and the adult narrator's judgment (as *holder*).
   Same person. The boy is the subject, never the `ContextKind` holder.
2. **Interiority.** Desire, intention, imagination, sensory affect, and an
   epiphany that must not become a narrated-world event — and must not be
   attributed to the boy as a belief-holder. The adult's appraisal is not
   the boy's phenomenology (design-contract rule 7).

Withdrawn justifications (do not reuse): leave-story-out, published student
summaries as recall, "scale", and "anachrony closer". Chesnutt's *The
Goophered Grapevine* may already carry a retrospective narrator in the frozen
set; **retrospective narration alone is not Araby's claim**. The claim is
interiority plus a dual self that `ReaderAt` cannot express.

---

## `ReaderAt` is not the mechanism

`ReaderAt(t)` is a visibility filter over the surface axis (ADR 0002). WOG
already tests it. Araby's diction is adult at offset 0: `ReaderAt(early)`
still shows narrator judgment. Treating `ReaderAt` as the dual-self answer
annotates adult diction as early reader-visible fact and **loses the dual
self**.

The test is representational / context: nested `ContextKind` frames, not a
horizon cut.

---

## Existing `ContextKind` constructors suffice

`story.ContextKind` already has `NarratedWorld`, `Speech`, `Belief`,
`Desire`, `Intention`, `Hypothetical`, `Counterfactual`, `Memory`, and
`Imagination`, each holder-bearing where a self is involved.

**No `Narrator` kind.** Ruled 2026-08-29 (`post-01M16Q0AJS8XQR0FX2N2FKY39X`):
existing vocabulary suffices. `Belief(holder)` and `Memory(holder)` plus
`ContextFrame.parent` already nest.

**Holder ruling (neither of the options I asked).** `Belief(boy)` is not
shallower — it is wrong. It misattributes the holder. The boy in the bazaar
is humiliated; he is not the one appraising himself across years. The holder
of both frames is the **adult narrator**. The boy is the **subject**, never
the holder.

| Frame | Kind | What it must not collapse into |
|---|---|---|
| Public boyhood events (he goes; the hall is packing; he buys nothing; uncle is late) | `NarratedWorld` | objective biography; adult judgment |
| Recalled boyhood interior (wanting to go; the bazaar as fantasized; humiliation as lived) | content inside `Memory(adult)` — situations, not `Desire(boy)` / `Imagination(boy)` as holders | `NarratedWorld` satisfaction; boy as holder |
| Adult evaluative stance (the vanity-judgment) | `Belief(adult)`, parent of `Memory(adult)` | `Belief(boy)`; a `NarratedWorld` event; `ReaderAt` |

One person, one `EntityId` `E.adult` used as holder. The boy appears as
participant / subject of situations inside `Memory(adult)`, never as
`holderEntity` on a `ContextKind`. Flattening to one frame removes the only
feature that earned this text the fixture slot.

---

## Expectations table

Ids are planned. They become fixture constants only when a hand-model is
authored after PD sign-off. Checks use the WOG `Check` vocabulary
(`InContext`, `EntityDistinct` — here its negation: one person — plus
modality / polarity / no-reference). Sentence numbers are omitted on
purpose: they would require admitting the text.

| # | Phenomenon | Statement a reviewer reads | Mechanical checks (planned) |
|---|---|---|---|
| A1 | One person; boy is never holder | Dual self is `Belief(adult)` over `Memory(adult)`, not two people and not `Desire(boy)`. | One `EntityId` `E.adult`. Every `Belief` / `Memory` `holderEntity` is `E.adult`. No `ContextKind` in the model has `holderEntity` equal to a distinct boy id. |
| A2 | Lived wanting is memory content | Wanting to go is a situation inside `Memory(adult)`, not a `Desire(boy)` frame and not a root-world fact that the bazaar will satisfy. | `InContext(S.desireToGo, Memory(E.adult))`. Not `InContext(..., Desire(_))`. Not `InContext(..., NarratedWorld)` asserting satisfaction. |
| A3 | Imagined bazaar ≠ visited bazaar | The bazaar as fantasized is remembered content; the visit is a narrated-world situation. | `InContext(S.imaginedBazaar, Memory(E.adult))`. `InContext(S.visitBazaar, NarratedWorld)`. Distinct situation ids. `NoReference` as identity; `NarrativeReference.Prospective` from desire-content to visit is allowed. |
| A4 | Visit is narrated-world | He reaches the hall; the stallholders are packing; he buys nothing. Those are root-world events / states. | `InContext(S.visitBazaar, NarratedWorld)` (and any packing / non-purchase companions). Polarity on purchase is negative if modeled as an event. |
| A5 | Judgment is `Belief(adult)` | The closing vanity-appraisal is the adult narrator's belief, parent of the memory frame — not a hall-event and not the boy's belief. | `InContext(S.epiphany, Belief(E.adult))`. `Belief` frame is parent of the `Memory` frame. **Forbidden:** `IsEvent(S.epiphany)` in `NarratedWorld`. **Forbidden:** `Belief` whose holder is the boy. **Forbidden:** causal dependence as if the judgment happened in the hall. |
| A6 | Frames stay nested | Flattening experience and judgment into one `NarratedWorld` (or one `Belief`) fails the fixture. That flattening is what would make Araby not worth the slot. | `S.desireToGo` is in `Memory(E.adult)`. `S.epiphany` is in `Belief(E.adult)`. Memory's parent is the Belief frame. Neither is `ReaderAt`. |
| A7 | Girl-attention is remembered content | Attention to Mangan's sister lives inside `Memory(adult)`, not a `Desire(boy)` holder and not a root-world romance the visit fulfills. | Relevant situations `InContext(..., Memory(E.adult))`. No `NarratedWorld` causal `SurfaceExplicit` from her gesture to "Araby will satisfy". |
| A8 | Uncle's lateness is root | The delay that makes him late is a narrated-world event/state (clock time in the story-world), not a memory of lateness invented by the adult voice. | `InContext(S.uncleLate, NarratedWorld)`. Distinguishes story-world time from discourse-time retrospect. |
| A9 | No `ReaderAt` dual-self | A horizon cut must not be the carrier of adult vs boy. | Any `ReaderAt` test on this fixture asserts visibility of surface, not which self is speaking. A test that treats early offsets as "boy-only" **fails**. |
| A10 | Diagnostic, not Calibrated | Nothing derived from this fixture is published as a calibrated score, a population parameter, or a model-comparison number. | Fixture metadata / origin tag is Diagnostic. No path from this model into a `Calibrated` credence or a leave-story-out claim. |

Adversarial foils the hand-model must refuse (design record §27.2 / §52.3
shape):

- `Belief(boy)` or `Desire(boy)` / `Imagination(boy)` as holders.
- Two strangers (`EntityDistinct`) for one person; also flattening to one frame.
- `fromProduct` / apply minting of a "checked" dual-self witness (when the
  model exists: same unforgeable bar as the rest of the sweep).
- Epiphany as `IsEvent` in `NarratedWorld`.
- Imagined bazaar and visit sharing one situation id.
- `ReaderAt(0)` implied to hide narrator judgment.
- Any student-summary or web-plot-recap used as a recall unit.

---

## What this fixture will not do

- Close anachrony. Discourse time ≠ story-world time ≠ recall time is already
  a library law; Araby is a poor closer because the telling is uniformly late.
- Supply published school summaries as recall. Those are fame-contaminated
  and ethically the wrong kind of "participant" text.
- Enter story text, even in comments, before the checklist header is signed
  by someone other than the commit proposer, against the 1914 edition in hand.

---

## Ruled / still open

1. **Ruled** (`post-01M16Q0AJS8XQR0FX2N2FKY39X`): `Belief(adult)` over
   `Memory(adult)`; boy is subject, never holder. Neither `Belief(boy)` nor
   `Memory(boy)` as I had posed them.
2. PD sign-off remains an owner-and-second-checker act. This document is not
   that sign-off. No story text here.
3. Hand-model waits on that sign-off. The table is the acceptance contract.
