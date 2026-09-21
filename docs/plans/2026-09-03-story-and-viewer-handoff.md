# Story pipeline and viewer: handoff

What a fresh agent needs to pick up the text→model→picture line. The design records are
`2026-09-01-story-pipeline-solo-plan.md`, `2026-09-02-model-truthfulness-slice.md` and
`2026-09-03-visualization-recovery-plan.md`; ADRs 0005 (compiler), 0008 (provider-agent) and 0002
(visualization) carry the decisions. This file is the operational brief: what works, what is
measured, what the traps are, and what is actually left.

## 1. Where it stands

A model built unattended from English, and a viewer that renders it honestly.

| | state |
|---|---|
| storymodel4s `main` | `e6c5bee6`, pushed |
| storyatlas4s `main` | `0518d31`, pushed |
| War of the Ghosts, 50 sentences | 50 charts, 49 sentences yielding situations, 65 situations, 35→29 entities, 64 trajectory steps, 6 contexts |
| Validated? | **No**, and honestly so: 70 gaps, because removing the filename-derived title left the summary family unresolved |
| Cost of a full rebuild | **zero** — replay from 50 committed recordings, no key, no network |

### The free replay, which you will use constantly

```
sbt -Dstorymodel4s.grakern.build=$G \
  "pipeline/runMain storymodel4s.pipeline.storyBuild replay <TEXT> \
   pipeline/src/test/resources/recordings/wog-captured <OUT>"
```

`<TEXT>` is the triple-quoted literal extracted from
`fixtures/src/main/scala/storymodel4s/fixtures/wog/WarOfTheGhostsText.scala`. **Not**
`docs/design/war-of-the-ghosts-boas1901.txt`: that file carries a provenance header, cuts into 58
sentences, and matches none of the recordings. The recordings are keyed to the literal. This trap
has caught two agents.

## 2. What is true of the model, and what is not

Fixed this session, each with mutation proofs and courts on the real model:

- **Reported speech leaves the root world.** The climax is a retelling quoted across four sentences
  of which only the first has a speech verb; the rest were parsed alone and became root-world
  events, so the model asserted **a second battle that never happened**. Quotation spans are now a
  typed observation in `core`, and the chart's own `EmbeddedProposition` makes child contexts.
  Six situations moved into one speech frame at characters 1724–1900.
- **Referentiality** is decided by role and concept together: adverbs left the entity layer, and
  time and manner fillers became typed circumstances with their own spans.
- **A title is a checked type** that cannot be built without naming its basis. No claim can be a
  filename.
- **A landmark carries its own claim status**, read from the node, no default.

Still false or absent, in priority order, all measured:

1. **Credence is 1.0 on all claims**, and 135 carry a raw score with no calibration model at all,
   which contract 3 forbids outright. `InteropTables` already computes graded 0.9 and 0.5 scores
   that never reach the model. This makes ADR 0002's uncertainty vocabulary undeliverable: the
   viewer has the marks and nothing to draw with them.
2. **Provenance identifies nothing and costs 90 MB.** `ClaimMeta.provenance` repeats the run's full
   604-call log inside every one of 325 claim provenances: 196,300 records for 604 distinct calls.
   A claim should carry the calls that produced it.
3. **No feature measures the story.** `featureSpaces`, `sidecars`, `descriptors`, `hypotheses` are
   all empty. This is the entire remaining distance to the data-journalism bar; a critic put it
   exactly: *"Pudding did not infer an arc, it measured one."*
4. **Causal, goals, state-changes, references, entity-relations are all empty.** `:purpose` and
   `:cause` survive only inside description strings.
5. **The hierarchy is one segment and zero boundary beliefs**, and calls itself a hierarchy.
6. **The temporal layer is discourse order relabelled**: 64 edges, all `Unclear`, at credence 1.0.
7. **51 of 119 argument fillers are unlicensed** — 37 numbered arguments no lexicon licensed, 14
   named roles outside the table. Recorded in the coverage ledger, not resolved. Frame lexicon work.
8. **Coreference is still lemma-grouped** where pronouns are concerned; `document/mentionform.scala`
   has the correct rule written out and unused. The cast table therefore shows "he" as one entity
   with 11 mentions spanning a story that has several men.

## 3. What is true of the viewer

Judged blind by four independent critics against two bars: our own frozen `mockups/v2` artboards,
and pudding.cool/2018/02/stand-up. Margins moved from a two-class gap to **54–46** against the
mockup and from 85–15 to **76–24** against The Pudding. Four of six plan questions are answerable
from the screen, up from none.

Won outright: typography (the mockup sets its own prose in monospace against its own brief), colour
discipline (selection hue is 0.03% of the canvas), greyscale (every distinction is a shape),
declared meaning (we state what the axes *refuse* to mean), disclosure of failure (called
best-in-class), and genuine material (the mockup must stamp SYNTHETIC PLACEHOLDER).

Lost: composition, label placement, and uncertainty — the last of which is blocked on item 1 above.

**Rule that must survive: the viewer may never out-claim the model.** Absence is drawn, not omitted;
an unresolved referent may not look resolved; a partial model renders as visibly partial.

## 4. Traps, each of which cost real time

- **`compilation-report.json` cannot be read back.** No codecs for `DerivationGap` or
  `SentenceCoverage`, no parsers inverting the rendered strings, and `upstreamClaims`/`evidence` are
  written as **counts, not contents**. A viewer reading `storymodel.json` alone can compile neither
  the gap nor the abstention channel and must say so. Fixing this needs codecs in `codec` or a
  `derivation.json` beside the model.
- **`qlmanage -t` fits the longest side**, so a landscape plate comes back square with the right
  third cut off. Use `scratchpad/craft1/raster.py`, or pad to square then crop.
- **One worktree per agent.** Two agents in one tree produced arbitrary red states and a lost
  afternoon. Gate in an isolated clone when a branch has two authors.
- **Never `git checkout -- <file>` while holding uncommitted work.** It cost two agents real work in
  one session. Mutate and revert by exact string replacement.
- **Run `bash tools/reference-scope.sh "$(git merge-base main HEAD)" HEAD` before naming a SHA**, and
  gate every module it names. A rule that decides whether any model validates has a blast radius the
  size of every suite that builds a model, not the size of the files you edited.
- **`scalafmtAll` before claiming green.** Until 2026-09-21 `checkAll` ran the format check first
  and aborted the whole gate on one unformatted file, so zero tests ran. It now runs
  `compileAll;testAll` first and formatting last, but a format failure still fails the gate.
- **Gate exports are ~2.8 GB.** Keep one live, delete it the moment its candidate lands, and delete
  only paths you created — a glob over `gate-*` once destroyed another agent's evidence.
- **Watch a gate by its PID** (`until ! ps -p <pid>`), never by `pgrep -f <export-dir>`: the export
  directory is not in the java command line, so such a watcher fires immediately on a half-finished
  run. And phrase wait conditions against a branch being green, not against `git status` output.

## 5. What I would do next, in order

1. **Codecs for the derivation record**, or a `derivation.json`. It is the cheapest unblock: it turns
   the viewer's 66 law marks into the full 206 absences and closes the honest-but-thin picture.
2. **Credence and provenance** (items 1 and 2 above). They are the same slice in spirit: stop
   asserting certainty nobody derived, and make a receipt identify its own claim. This also fixes the
   90 MB file and makes the uncertainty vocabulary drawable.
3. **One measured feature over the text.** The vision promises word-level measurements forming
   aligned tracks that contribute evidence to boundaries. One real feature is what lets the picture
   say a moment matters, and it is the whole remaining distance to bar B.
4. **Pronoun coreference**, wiring `MentionForms.resolvableAt` in. The rule is written; nothing uses it.

Do not start a new projection (Chronology Loom, Recall Voyage, causal) before the layer it reads
exists. Building a projection the model cannot feed produces an empty picture, which is worse than
no picture, and the recovery plan §V3 names each one's blocker.

## 6. Update, 2026-09-03: all four slices landed

Everything above §5 was true at `328021b2`. Four landings later the picture is:

| | state |
|---|---|
| storymodel4s `main` | derivation record `46d4a6a5`, credence and provenance `9d71e87c`, feature tracks `8307316d`, entity identity by referring form: the merge containing this section |
| model wire schema | **0.6.0**: a claim's credence is `{"score", "basis"}` (0.4.0, ADR 0010), a flow step's turnover is an estimate (0.5.0, ADR 0012), and a segment carries its own `meta` and a tagged `summary`, stated or `unsummarized` with a reason (0.6.0, ADR 0005 §10); older files are refused, no migration |
| `storymodel.json`, fifty-sentence replay | **0.8 MB**, was 89.8 MB |
| bundle files | `storymodel.json`, `compilation-report.json`, `receipts.json`, **`derivation.json`** (`derivation-record/v1`), **`features.json`** (`features-record/v1`), **`features/<manifest checksum>.sidecar`** per measured space |
| still not validated | 84 gaps, 3 violations (was 149 / 135 before ADR 0005 §10 gave the root segment its own claim): the summary family is still unresolved without a caller's title and is now a typed absence on the root segment; the 3 violations are the one abstained sentence's situation, context and membership, the same 3 the titled build reports; the open pronouns of ADR 0012 and the participant edges and coverages behind them are the other 79 gaps |

### What is now true of the model

1. **Credence says what entitles it.** `Credence(score, basis)`: `Score.Unmeasured | Raw(value,
   scorer)`, `CredenceBasis.Uncalibrated | Calibrated(p, model) | Determined(rule)`. On the replay:
   0 calibrated probabilities (was 197 fabricated `1.0`s), 0 claims without a basis (was 128),
   269 `Unmeasured + Determined`, 56 `Raw + Determined` carrying the interop role table's grades
   under the table's own scorer. Nothing reads `1.0` anywhere. A parser that reports confidence
   passes it into `AmrCandidates.fromPenman`; the current one reports none, and the model says so.
2. **A claim's provenance is its own calls.** An accepted claim carries its rule call and the parse
   receipts of the sentences its evidence lies in (3, or 5 across two sentences); a derived claim
   carries none and cites upstream claims. The run log stays in `receipts.json`.
3. **The derivation record is readable.** `derivation.json` carries every attempt, gap (with its
   upstream claim ids and evidence refs), coverage row and the summary coverage, bound to the
   model by story id, source checksum and the SHA-256 of the exact `storymodel.json` bytes.
   `DerivationRecordCodec.decode(model, text)` refuses a record for another build.
4. **Features measure the story.** `--feature token-length`, `--feature type-frequency`,
   `--feature lexicon=<path>`: raw token tracks with typed missingness, and mean reductions per
   sentence and per situation with coverage and exact support, materialized as SM4SFT02 sidecars
   plus `features.json`. Any word→value table is a measure (ADR 0011); no norms ship here.
5. **No pronoun is an entity.** Identity is decided by referring form (ADR 0012): names and
   nominals cluster by exact label; a third-person pronoun resolves only to a unique
   number-compatible antecedent that precedes it, else it is an open reference with its
   candidates recorded (22 with several candidates, 3 first-person under an open speaker, 1
   addressee, 0 resolved on this story); a holder offered an open pronoun is unresolved. A
   first-person singular under a named speaker is that speaker (`speech-holder/v1`, ADR 0012 §3);
   "we" and "you" stay open as `SpeakerGroup` / `NeedsAddressee` with the speaker as candidate.
   Entities 29 → 23, participant edges 57 → 30. A flow step's turnover is `Missing(InputUnresolved)`
   where a cast holds an open pronoun (43 of 64 steps) instead of a number nobody measured.

### What is still false or absent

- Items 4–7 of §2 stand: causal/goal/state-change/reference/entity-relation layers empty; the
  hierarchy is one segment with zero boundary beliefs; the temporal layer is discourse order;
  51 fillers unlicensed. Item 8 is closed as far as the evidence allows: pronouns are open, not
  merged, and nothing chooses among candidates until a calibrated policy exists.
- No claim is calibrated: `align`'s calibrators are siblings of `document` and unreachable; a fit
  needs adjudicated data. `story.StatusWeight.of(meta)` still falls back to a constant per status
  off the story-build path (ADR 0010 records it).
- Features infer no boundaries (ADR 0004's `level` question is open). The viewer reads
  `features.json` (storyatlas4s `f7e825d`) but draws nothing from it yet: the value-bearing mark of
  ADR 0002 D11 is not minted.

### The viewer

storyatlas4s reads `derivation.json` beside the model through the model-bound decoder (branch
`solo/derivation-record` in its `.worktrees/derivation-record`; see the storyatlas4s commit for
its pin and gate). A record for another build is refused, never paired; no file means
`NotSupplied`, still distinct from zero gaps.

Since 2026-09-04 (storyatlas4s `80de6d7` pins storymodel4s `a0b2bf4f`; `f7e825d` on top) it also
reads `features.json` the same way: `FeaturesRecordCodec.decode(draft, text)` binds the record,
every sidecar the record names is read from `features/` and verified block by block against the
manifest the model carries (`SidecarCodec.materializeScalarTrack`), and a track under a manifest
the model does not carry is refused. `FeatureRecord` on `ReadModel` and `Edition` is `NotSupplied`
or `Supplied(artifact, tracks)`; a supplied record with no tracks is the pipeline saying nothing
was measured and never shares a receipt line with "not supplied". The values reach the receipt
and nothing else. The drawing half needs, in order: a value-bearing `VisualPrimitive` in `view`
(D11: word underlay, sentence step-wash, situation field over its `SpanSet`; coverage and
missingness as separate masks, V-U1/V-U7; circularity status on every aggregate, V-U8), an ADR
0002 amendment for it, `CommonViewState.feature` set in storyatlas4s `Edition` so `FeaturePlanner`
moves from `NotRequested` to `SidecarRequired`, and a sixth `AtlasLowering.Layer` with geometry in
`AtlasPlate`. The lowering cannot see `codec`, so the resolver stays in `cli`/`app` and hands
values to the lowering through a `view` type (ADR 0002 §9 checkpoint 7). Two things to verify
first: `FeatureSelection.Derived(derivation, Some(basisId))` fails closed as `Unresolved`, so a
derived track must be selected with `None`, and `resolveSpace` then mints `derived:<hash32>`,
which must equal the record's space id; and two measures on one plate need separate scales
(V-U2: `codepoints` vs `occurrences`).

### New traps

- **sbt-git in a linked worktree cannot load a detached HEAD** (`MissingObjectException`, then the
  retry prompt hangs a `-batch` gate forever with zero totals). Gate a merge result on a branch.
- **The Coursier cache was wiped mid-session** (something freed ~80 GB); a gate then dies at
  `<module>JS / update` with `NoSuchFileException`. Infrastructure: rerun with network up.
- **`rm -rf` on the scratchpad and heredoc-written shell scripts are denied** to the agent; use
  fresh output directories and the Write tool.
- **A fresh storyatlas4s linked worktree needs `zz-worktree-local.sbt` copied in** from a sibling
  worktree (sbt-git otherwise fails with "Bare Repository has neither a working tree, nor an
  index"); the file is not gitignored there, so add files by name, never `git add -A`.
- **Read a sibling's pin from the branch you are gating**, not from whichever checkout is handy:
  the intaglio pin differed between storyatlas4s `0518d31` and `48d462a`, and a gate against the
  wrong clone fails compiling `VoyageLowering` with `CssClass`/`PointShape.Diamond` not found.

### What I would do next, in order

1. ~~Speech-holder resolution for first- and second-person pronouns~~ Done (ADR 0012 §3). What
   remains open on this story is the chain: the speaker is "they", and "they" is open. Closing
   that is the calibrated antecedent policy of item 4, not another rule.
2. **The viewer draws Token/Sentence/Situation tracks.** Reading `features.json` is done
   (storyatlas4s `f7e825d`, see "The viewer"); the picture waits on the D11 primitive listed
   there (`FeatureChannelState.SidecarRequired` is still the seam).
3. ~~A root segment that validates without a story summary~~ Done (ADR 0005 §10, schema 0.6.0):
   the segment carries its own claim and a typed summary absence; the untitled fifty-sentence
   build goes 149 → 84 gaps and 135 → 3 violations, the same 3 as the titled build (one abstained
   sentence). A summary rule that reads the story is still owed for the summary itself.
4. **Calibration**: a `CalibrationModelId`-typed fit over adjudicated roles, leave-story-out, the
   first `Calibrated` basis in the story path.


## 7. Feature drawing continuation — 2026-09-04

The drawing half is implemented on `solo/feature-values` in both repositories.
The portable contract is ADR 0002 §16: `VisualPrimitive.Feature` carries the
actual scalar outcome, exact support, coverage, recipe, basis, provenance and
sidecar checksum. `FeatureChannelState.Materialized` distinguishes these values
from a manifest that still needs a resolver. Derived selection now resolves the
producer's recorded basis, including aggregate tracks; the earlier advice to
select those with `None` is obsolete. No wire schema changed.

storyatlas4s adds the sixth lowering layer and `features.html`, with a reading
page, SVG and textual twin for each Token/Sentence/Situation track. Each grain
uses its own measured display range. Situation supports remain separate pieces,
missing values stay visible, and coverage has a separate channel. Whole-story
measurements require an omniscient horizon. Aggregate circularity is explicitly
unassessed: `features-record/v1` does not carry a feature-use ledger.

The replay check exposed a separate input-boundary trap. Its compilation report
records three `compiler.required-derivation` errors, but a fresh structural
`StoryValidator` run promotes the remaining graph. That does not establish that
the derivation was complete. The viewer retains its draft route whenever the
bound derivation record reports gaps or abstentions; local validation remains
separately reported and the original compiler policy is not reconstructed.

Verification is executable: `tools/feature-mutation-check.py` in this repository,
and `e2e/static/features.cjs` plus `feature-mutations.py` in storyatlas4s. The
captured War of the Ghosts replay supplies 9 tracks with 1,896 outcomes, including
195 discontinuous situation supports. The tiny partial lexicon used for this
check contains synthetic test values, not scientific norms. Full landing gate
receipts belong to the continuation's git history and the viewer's feature
rendering evidence note.

Next after this slice: the calibration work already listed above, beginning
with adjudicated roles and a leave-story-out evaluation design. Feature shading
does not establish a calibrated confidence, a boundary, or causal structure.

## 8. Calibration instrument — 2026-09-04

`document.ParticipantCalibration` implements the role-study mechanics under
ADR 0015. It extracts exact candidates from checked compiler inputs, binds final
judgments to source/acquisition identities, estimates separate role/scorer/score
cells with a declared Beta(1,1) prior, and refits leave-story-out folds. Source
aliases, story-group reuse, incompatible producers, unseen cells and cells with
only one training story produce explicit refusals. Unresolved judgments survive
in the record and do not become negative labels. Losses are absent when no
labeled prediction exists.

A successful application returns a participant attempt carrying
`AcceptanceBasis.Calibrated(p, CalibrationModelId)` through the existing resolver
and compiler. Its raw score, source evidence and generating rule receipts
survive. The compiler seam is exercised with synthetic labels; no production
fit or acceptance default is claimed. Neither these probabilities nor this
adjudication target answers cross-sentence identity or recall quality.

The distinction that matters next is **a working fitting instrument versus an
empirically supported calibration**. The 2026-08-28 frozen-fixture protocol
requires independent human annotations blind to candidates, a frozen calibration
partition, and untouched-test evaluation. Its manifest remains a candidate list;
no frozen resource directory was present in this checkout. An external corpus
may exist, but its location has not been supplied. The data-preparation and
application guide is `docs/calibration/participant-roles.md`.

Do next: locate or construct the independently adjudicated corpus under that
protocol; map its frozen local role judgments onto exact extracted candidates;
run the held-out study and report coverage, refusals and per-story losses; assess
selection effects from unresolved annotations; then decide and receipt a
production acceptance policy and CLI integration. Keep WOG diagnostic. The fit
conditions on resolved adjudications and must not be advertised as reliability
across unevaluable candidates without evidence for that transfer.

Mechanical evidence is reproducible with
`tools/role-calibration-mutation-check.py`; full gate receipts are under
`target/role-calibration-evidence/` in the isolated calibration checkout. Those
courts establish software behavior, not that human data has been collected or
that a probability generalizes to new narratives.

The focused JVM court passed 27 tests (20 study/compiler tests and seven
external-package construction tests). All ten compiling mutations were killed;
the ledger is `docs/calibration/2026-09-04-mutations.json`. These are locally
observed mechanical results on synthetic material.
