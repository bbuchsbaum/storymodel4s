# The viewer: a recovery plan

- **Date:** 2026-09-03
- **Standard:** `vision.md`, and the owner's statement that storyatlas4s is the user application
  for this library and must be "utterly beautiful and useful".
- **Governing contract:** ADR 0002. It is not the problem. Most of what follows was specified there
  in 2026-08 and never built.

## 1. Where we actually are

| | state |
|---|---|
| `view` module | Discourse Atlas and Codex compilers only; 71 tests, sound |
| Projections named in ADR 0002 | seven; **one** exists |
| Uncertainty marks (D9) | specified in five typed states; **none implemented** |
| Feature values on marks (D11) | specified; marks carry `SidecarRequired` and no values |
| Context bands (D4 row 6) | specified; not emitted |
| Recall vocabulary (D10) | specified, court frozen; **nothing lowered from `align`** |
| storyatlas4s pin | 135 commits behind `main`, from before text could become a model |
| Model the viewer renders | the hand-authored fixture; it has **never** shown a machine-built model |

The diagnosis is not neglect of craft. It is that the viewer was built once against a hand fixture
and then never followed the model. Everything the model learned this week is invisible to it.

## 2. The organizing principle

**The viewer may never out-claim the model.**

We have just spent a slice removing four falsehoods from the model. A viewer that draws reported
speech like narration, or an unresolved pronoun like a resolved referent, or a partial model like a
complete one, puts every one of those falsehoods back at the last step, where they are most
convincing because they are now pictures. So:

1. Nothing is drawn that the model does not evidence. ADR 0002's V-E3 already says a mark may not
   sit on text that does not support it; the same rule must extend to epistemic weight.
2. Uncertainty has its own visual vocabulary and never degrades to "faint". D9's five states, each
   distinguishable by a non-colour channel, with raw scores never sharing a scale with probabilities.
3. Absence is drawn. A sentence the provider abstained on, a referent we could not resolve, a
   trajectory step whose turnover we could not measure: each is a visible mark, not a gap in the ink.
4. Every visual has a textual twin, so a picture can be diffed, audited, and cited.

This is what makes the thing beautiful rather than decorative. A research instrument earns trust by
showing its own limits precisely, and that is a design problem, not a disclaimer.

## 3. What "excellent" means here, stated so it can fail

The viewer is excellent when a researcher who has never seen the story can answer these from the
screen, each in under a minute, and can click from every answer to the words that support it:

1. What happened, in what order, at what grain?
2. What is narrated, and what is only said, believed, or wished by someone in the story?
3. Who is in this story, where does each appear, and where do two mentions become one referent?
4. What did the system fail to understand, and exactly where?
5. How confident is any given claim, and on what basis?
6. What changed when I moved the reader's horizon, the zoom, or the model version?

Question 4 is the one that separates an instrument from a demo, and it is the one nothing in the
repository can answer today.

## 3a. The two bars

The work is judged blind against two references, and must win both.

- **Bar A, exactness:** `storyatlas4s/mockups/v2/review/*.png`, the frozen v2 design package. Same
  subject, same information model, so the comparison is exact and cannot be hallucinated. Its
  brief's anti-pattern list (§15) is part of the bar: no synthetic material presented as genuine, no
  inferred arc stated as fact, no unlabelled numeric track, no faded-as-ambiguous, no layout called
  chronological without a defined temporal axis.
- **Bar B, craft:** a named piece of narrative data journalism, *The Structure of Stand-Up Comedy*
  (pudding.cool/2018/02/stand-up/), screenshotted live at matched viewports. It is an essay and ours
  is an instrument, so only the transferable dimensions count: does the visual make a text's shape
  legible to someone who has not read the text, is every mark and axis meaningful, and is the result
  alive rather than a dashboard.

Bar A alone would cap the work at our own design. Bar B is what stops that.

## 4. Phases

Each phase is gated by what the model can support. Building a projection the model cannot feed
produces an empty picture, which is worse than no picture.

### V0. The viewer can show a real model at all

Nothing else is possible until this lands.

- **Draft rendering.** `AtlasCompiler.compile` takes `StoryModel[Validated]`. Our machine-built model
  does not validate, and while the title and summary rules stand, it will not. Add an explicit draft
  path that renders a `StoryModel[Draft]` **with its gaps as marks**, so a partial model is legible
  as partial. This is more truthful than making the model validate to satisfy the renderer, and it is
  what a researcher needs when the pipeline is imperfect, which is always.
- **The app reads `storymodel.json`.** storyatlas4s gains a `codec` dependency and an edition input,
  so the CLI and the app render a model the pipeline produced, not only the linked fixture.
- **Pin bump** to current `main`, and a standing rule that the pin is bumped in the same slice as any
  `view` change, so it can never again drift 135 commits.

*Acceptance:* the War of the Ghosts model built by `storyBuild` renders, its 70 gaps are visible as
marks, and its coverage ledger is on screen.

### V1. Draw what the model already knows and the viewer hides

This is the truthfulness phase, and it is where the current model's new distinctions land.

- **Context bands.** Speech, belief and desire frames drawn as bands over the discourse axis, so the
  survivor's retelling and the ghosts' recruiting speech are visibly not narration. This is the
  single most important addition: the model gained it this week and the atlas would otherwise draw
  the fabricated battle back into the picture.
- **The five uncertainty states (D9)**, each with its own mark and a non-colour channel: missing with
  a reason, raw credence, calibrated probability, alternatives, unresolved. An unresolved referent
  must not look like a resolved one.
- **Circumstances distinct from participants**, since the model now separates them.
- **The coverage ledger as a surface**, not a footnote: every sentence, what the provider did with
  it, and why, clickable to the words.

*Acceptance:* questions 2, 4 and 5 of §3 answerable from the screen; a mutation that draws an
unresolved referent as resolved turns a named visual court red.

### V2. The instrument

Only now does the visual design work pay off, because it has something true to present.

- The v2 mockup's typography, three-pane composition, breadcrumb and control bar, already designed
  and sitting in `storyatlas4s/mockups/v2`.
- Region hulls with labels placed inside, landmark glyphs by situation kind, a discourse-offset axis,
  priority-budgeted label collision avoidance, minimum row heights.
- Whole-work minimap, viewBox pan and zoom, the epistemic playhead wired to the reader horizon.
- Codex and Atlas coordinated by one selection, with the V-L2 placement states already implemented.

*Acceptance:* questions 1, 3 and 6; the static edition and the browser shell both pass their laws;
a researcher can work in it for an hour without reaching for the JSON.

### V3. Projections that need model work first

Named with their blocker, so nobody builds an empty picture:

| projection | blocked on |
|---|---|
| Chronology Loom | real temporal relations; today all 64 edges are `Unclear` (audit D8) |
| Causal / goal | the causal layer, which is empty; `:purpose` and `:cause` are discarded |
| Feature tracks (D11) | any feature at all; `featureSpaces` and `sidecars` are empty |
| Recall Voyage (D10) | the `align` lowering; the court is frozen and nothing consumes `HsmmResult` |
| Story-World, Local Semantic Field | entity typing and geometry |

## 5. Standing rules

- **The pin moves with the change.** Any `view` change bumps the storyatlas4s pin in the same slice.
- **Every mark cites evidence.** Extend V-E3 from support to epistemic weight, with a law.
- **Every visual has a textual twin.** The twin is the audit surface and the regression surface.
- **Visual courts, not screenshots.** Assertions over mark identity, counts, placement and the twin;
  screenshots stay diagnostic, per the existing visual-QA decision.
- **The viewer renders the pipeline's own output**, never a hand fixture, from V0 onward. A fixture
  may remain as a fast case; it may not be the only case.

## 6. First slice

V0, in one branch: draft rendering with gap marks, the codec path, the pin bump, and the War of the
Ghosts machine-built model rendering end to end with its gaps visible. That is the smallest thing
that turns the viewer from a picture of a fixture into an instrument pointed at real output, and
every later phase depends on it.
