# Pre-registration: within-scene precision for recall-to-video

Written and committed **before any unit is adjudicated and before any anchor is scored at segment
granularity.** It extends `2026-09-02-gold-scene-preregistration.md`, whose rules stay in force. The
scene gold resolves 50 scenes; the pipeline emits one of 1000 segments. This file fixes how the
missing granularity is measured, by whom, and what may be concluded from it.

## 1. What is being measured, and why it needs a human

For a recall unit the pipeline places in the right scene, the question is whether the segment it
anchors on is the segment the person was describing. No released labelling resolves that, and no
gold-free proxy reaches it: cross-participant agreement and posterior concentration both operate at
or above scene granularity, and the pipeline's own confidence is what is under test. The only source
of a segment-level target is a person reading the recall unit against the scene's annotated segments.

The measurement is therefore an adjudication, and the adjudicator labels the **unit**, never the
prediction. The packet shows the recall unit with its neighbours for context and the gold scene's
segments in film order. It shows no anchor, no posterior, no runner-up, and no arm identity. The
prediction is sealed separately and joined only at scoring. Because the label attaches to the unit,
the same adjudication scores every present and future arm.

## 2. Sampling frame, fixed here

- **Run**: the default configuration, whose report set is `data/study/recall-to-video/all17-monofill/`. A bare
  environment reproduced it byte-for-byte for `NN03` on 2026-09-03 before this file was written, so
  "default" and that directory are the same object.
- **Participants**: the ten development participants with gold. The untouched five stay untouched;
  they have already been read four times at scene level, and this adjudication will become a
  selection instrument for emission work, which rule 2 of the scene pre-registration confines to
  development.
- **Units**: units with a gold scene under the scene pre-registration's §4 labelling rule.
- **Two strata**, by the default run's scene decision:
  - **A, scene-correct**: predicted scene equals gold scene. This stratum answers the within-scene
    question. Leaf-anchored and scene-anchored units are both in frame; the anchor grain is a fact
    about the prediction, not the unit, and is used only at scoring.
  - **B, scene-wrong**: predicted scene differs from gold scene. Sampled at a lower rate so that
    temporal error in film seconds can be reported over all units, not only the easy ones.
- **Sample**: 15 units per participant from A and 5 from B, drawn without replacement by
  `random.Random(20260903)` after sorting units by participant and unit index. 200 units. A
  participant with fewer eligible units than its quota contributes all of them.
- **Packet order**: units grouped by gold scene so the adjudicator reads a scene's segments once;
  scene groups and units within a group are shuffled by the same seed. Because the order is random,
  an incompletely adjudicated packet is a random subsample: the analysis uses every adjudicated unit
  and reports the count. A prefix of the packet is a cluster sample by scene group, so each unit's
  inclusion probability is the same but the A:B and per-participant balance hold only in
  expectation; the participant-clustered bootstrap remains valid. Nothing is chosen after seeing
  which units are done.

## 3. The packet and the sealed key

`tools/recall-study/within_scene.py packet` writes, under `data/study/recall-to-video/within-scene/`, outside Git
like every artefact carrying recall prose:

- `packet.md` — the blind packet: per scene group, the segment list (number, film-part clock,
  annotated description, location, cast), then each unit with the previous and next unit greyed
  as context and an answer line to fill in place.
- `key.tsv` — per sampled unit: participant, unit index, gold scene, stratum. The stratum is one
  bit of the default run's output (its scene decision), and the adjudicator never sees the key. The
  prediction itself is not in the key; it is read from the arm's report at scoring, so the same key
  scores any arm.
- `manifest.json` — seed, frame counts per stratum and participant, the SHA-256 of `packet.md`
  and `key.tsv`, and the SHA-256 of every input file.

The manifest's digests are recorded in the study log at generation time, before adjudication. The
generator is deterministic, so any reader can regenerate the packet and check the digests.

## 4. The adjudication task

For each unit the adjudicator marks, on its answer line:

- `first` and `last` — the inclusive range of segment numbers, within the listed scene, that the
  unit describes. A unit that describes the scene as a whole, or a gist not attributable to a
  sub-span, takes the scene's first and last segment.
- `none` in place of the range when the unit describes nothing in the listed scene.
- `sure` — `y` or `n`. Free-text `note` optional.

Grain is **derived**, never marked: `point` (range of one or two segments), `span` (three or more,
narrower than the scene), `whole` (the full scene), `none`. `whole` takes precedence: in a scene of
one or two segments the full range is `whole`, not `point`, which is the conservative reading since
such a unit leaves the primary set rather than scoring as a trivial hit.

The adjudicator of record is a human (M1 adjudication protocol, Law I4). A second, **machine**
adjudication of the same packet by a language model that never sees the key is permitted as a
diagnostic lane: it checks that the packet is answerable, it gives a provisional estimate before the
human read, and its agreement with the human lane is itself a finding. Under M1 Law I1 a set built
with a language model's help is diagnostic and never a selection set, so the machine lane may not
select an arm and its numbers are never called gold. Its answers are stored separately and the
human adjudicator does not open them until their own answers are complete. Its rubric is the packet's
own instruction text, verbatim; a lane run on any other instruction is a different lane.

## 5. Outcomes, fixed here

Notation: for a unit, the adjudicated range is `[a, b]`; the predicted leaf segment is `p`; the
segment holding the scene's temporal midpoint is `m`.

**Primary.** On stratum A, leaf-anchored, grain `point` or `span`:

1. **`hit`** — `a ≤ p ≤ b`. Reported with its participant-clustered bootstrap CI, and **paired
   against the scene-midpoint null** (`a ≤ m ≤ b`). The leaf anchor carries within-scene
   information if the paired difference's CI excludes zero in the model's favour. This is the one
   comparison that answers the handoff's question.
2. **`uniform-null hit`** — the exact expectation `(b − a + 1) / scene length` per unit, averaged.
   Reported alongside, not decisive.

**Secondary.**

3. `hit ±1`, `hit ±2` — `p` within one or two segments of the range.
4. **`time gap`** — seconds between the predicted segment's interval and the adjudicated range's
   interval, zero when they overlap; median and 75th percentile, model against the midpoint null and
   the uniform expectation.
5. **Grain distribution** — shares of `point`, `span`, `whole`, `none` across all adjudicated
   units. A fact about recall, reported for both strata.
6. **Abstention concordance** — share of `whole` among scene-anchored units against leaf-anchored
   units in stratum A. Tests whether the pipeline abstains to the scene level on the units that are
   in fact gist.
7. **Runner-up rescue** — among primary-set misses, share whose runner-up leaf hits.
8. **Temporal error over all units** — time gap pooled over strata A and B, each unit weighted by
   its stratum's frame size over its sample size, reported as a weighted median and 75th percentile,
   model against the midpoint of the *predicted* scene. A scene-anchored unit has no leaf, so its
   position is that same midpoint and it contributes equally to both sides. *(Both sentences after
   the first were added on 2026-09-03 after the machine lane was scored, when a review found the
   scorer had used the adjudicated count instead of the sample size and the abstention case unwritten;
   the scorer was corrected to the pre-registered weight and the log's row re-derived.)*
9. **Confidence** — `hit` by quartile of `mapAnchorMass`. Diagnostic.

**Where the uncertainty goes.** A unit the adjudicator cannot narrow is labelled `whole` and leaves
the primary set rather than being scored as a miss, so vagueness in the recall does not read as
imprecision in the anchor; the grain distribution reports how much left. A unit the adjudicator
marks `sure: n` stays in, and every primary and secondary outcome is repeated on `sure: y` units
alone as a robustness read. `none` is coverage. Nothing is imputed for a skipped unit.

**Label status.** Every admitted answer file names its lane in its filename and header
(`human` or `machine`); a machine label is never merged into a human file, and a file with no lane
named is invalid. The human lane's labels are *adjudicated*; the machine lane's are *diagnostic*.

**Reliability**, when both lanes exist: range Jaccard per unit (median), grain agreement, and
agreement on the `hit` decision under the sealed prediction. A machine lane whose median Jaccard
with the human lane is below 0.5 is reported as failed and its estimates are not quoted.

Inference throughout is a paired bootstrap over units clustered by participant, 2000 resamples,
seed 20260903, plus per-participant counts of improvement. Both are reported; neither is chosen
after seeing which is kinder.

## 6. Binding rules

1. The human lane is the measurement of record. Only it may select an arm, and only on development
   participants, as the scene pre-registration's rule 2 already requires.
2. Every arm scored against the human lane is **counted** in the study log's gold ledger, continuing
   the scene ledger's numbering. Every arm scored against the machine lane is counted in a separate
   diagnostic ledger. This pass pre-specifies one scoring per lane: the default configuration.
3. A later arm is compared to the default on the **intersection** of units both place in the gold
   scene, and the size of that intersection is reported next to the comparison, because a sharper
   emission changes which units are in stratum A.
4. `none` units are reported as coverage and excluded from every hit and gap outcome. They are not
   errors of the aligner and not errors of the adjudicator; they are the scene gold's own boundary
   noise, and their share is worth knowing.
5. The word *calibrated* stays barred; one film, ten participants, and 200 units cannot license it.
6. The packet, the key, the manifest and the answer files stay outside Git. A content-free copy of
   each lane's answers (participant, unit index, first, last, sure — no notes, no prose) may be
   admitted under `docs/data/sherlock/` with its digest once the lane is complete.
