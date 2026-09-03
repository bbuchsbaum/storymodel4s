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
- **`scalafmtAll` before claiming green.** `checkAll` runs the format check first and aborts the
  whole gate on one unformatted file, so zero tests run.
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
