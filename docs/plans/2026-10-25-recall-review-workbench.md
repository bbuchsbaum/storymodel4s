# Recall review workbench: a local tool for recall-to-video mapping and attribution review

**Date:** 2026-10-25.
**Commissioned by the owner** on 2026-10-25: "go ahead and do the whole thing… it should be
exceptionally good and efficient at facilitating recall to video mapping."
**Epic:** see Mote (the `review-workbench` tag), under the evidence-binding epic
`bd-01M5MR1KTXEP898CTN2KE3J219`.
**First use:** human reference review for the identity-attribution experiment (owner decisions op
`20261025T174031.593362Z-p20738-c0000-r7f9b-h1e09a1`).
**Later:** a port to storyatlas4s, which is a Laminar (Scala.js) app.

## Purpose

A reviewer working through a participant's recall has to do three things, one recall line at a
time:
1. find where in the film that line points;
2. decide which person each reference means;
3. record why.

Today that means a CSV, a static HTML page and a video player in three windows. The workbench puts
them in one keyboard-driven screen, where every recall line comes with ranked candidate film moments
and the clip already cued. The goal is the fastest *correct* judgment, not the fastest click.

## Non-negotiable constraints

1. **Local only.** Participant recall and the media never leave the machine (rule 14d, custody).
   - The server binds to `127.0.0.1`, and every request needs a random per-session token.
   - Nothing is published as a hosted page.
   - Caches, such as frames and indexes, live in the mode-0700 private directory.
2. **Blind to arm outputs.** The workbench never loads any arm's output: not the automatic graph
   (B),
   the annotation-name baseline (D), or the multimodal judge (C). It loads raw evidence only: the
   recall, the annotation table, the recognizer transcript and the media. Navigation aids are
   computed from that raw evidence and recorded as shown (see "Suggestions").
3. **Two passes, kept apart.**
   - The **source pass** builds arm A's reviewed source graph. It never displays recall.
   - The **recall pass** builds the reference census and attributions.
   - The same human doing both is allowed, but **the source pass is done first**. The workbench
     records the order, and it refuses to start the source pass for a block once the reviewer has
     opened recall windows nominated to that block. This is the separation the joint proposal
     requires ("source graph A … without recall questions or recall answer labels").
4. **Writes the agreed interchange.** Output is Codex's private `decisions.csv` and
   `review-windows.csv`, column for column, with the exact semantics in
   `2026-10-25-identity-feasibility-codex.md` (steps 1-6). An append-only JSONL edit log sits beside
   them, so no edit is ever lost.
5. **Bound to the frozen census.** It loads only if the recall CSV hash matches the pinned
   `5bfe5e4a…e48e` and the census matches its receipt. It never changes the v2 split.

## The two contracts, which are what get ported

The UI is a prototype, but these contracts are not. They are versioned JSON Schemas under
`docs/data/`, with synthetic examples, so the storyatlas4s port reads and writes the same files.

- **`review-packet/v1`** is built locally by the server and never committed. It holds:
  - the subject;
  - the frozen manifest hashes;
  - the windows, with each word's row id, text, timing and nominations from `word-census.json`;
  - the scene table (global id, part, local TR range, seconds);
  - the annotation rows (row, seconds, `Name - All`, `Name - Speaking` and scene details);
  - the recognizer words per part;
  - the media parts;
  - the candidate index.
- **`review-decisions/v1`** is the JSONL edit log. Each event carries:
  - an event id;
  - the pass;
  - the decision id;
  - the fields, which are exactly the `decisions.csv` columns;
  - the reviewer;
  - the start and commit times;
  - the suggestions that were shown;
  - whether a draft was revealed.

  `decisions.csv` is a deterministic export of the latest event per decision.
- **`source-review/v1`** is arm A's artifact, produced by the source pass. It holds:
  - per row, the people confirmed present, with exact anchors;
  - unnamed people with stable ids;
  - dialogue mentions resolved to a source person;
  - identity across rows;
  - each item's event, state and context.

  It is shaped so that it can be lowered into the graph's v2 classes for the A arm.

## Screens

### Recall pass

```
┌ NN03 · window 17/50 · decision 12 ─ block 26-30 (discovery) ── ⏱ 0:41 ── [?] keys ┐
│ RECALL                                      │ FILM  part b  ▶ 1:07-1:44  ⟲ R       │
│ … ‹prev› and then [[she]] was lying … ‹next›│ [ video, cued, ±10 s pad, rate 1×  ] │
│ TR 331-334 · nominated: scene 29 (boundary) │ timeline: scene 29 ▮▮▮▮ rows 500-504 │
├─────────────────────────────────────────────┤ rows: Name-All · Speaking · details │
│ CANDIDATES (ranked, click or 1-9 to jump)   │ frames: ▢ ▢ ▢ ▢ (row midpoints)     │
│ 1 scene 29 row 502  nominated, name hit     │                                      │
│ 2 scene 29 row 503  dialogue overlap        │ SEARCH  /  "phrase or name"  → hits  │
│ 3 scene 30 row 507  lexical 0.41            │                                      │
├─────────────────────────────────────────────┴──────────────────────────────────────┤
│ DECISION  span w331-w331 [⇧←→ extend]  form: pronoun ▾  role: patient ▾          │
│ person_reference: (y)es (n)o (u)nknown   refers to: [1] A [2] B [3] extra [o]ther │
│ source scenes: 29  anchors: row 503 ▢   resolvable: y/n/u   outcome: s/c/u/e      │
│ occurrence group: g12 ▾   other coref spans: +   ambiguity reason: ________       │
│ [Enter] commit · [D] reveal draft (only after commit) · [W] mark window read      │
└────────────────────────────────────────────────────────────────────────────────────┘
```

- **Navigation.**
  - Windows are taken in order.
  - Each window must be marked read. Read state is shown, and unread windows block sealing.
  - The lexical screen highlights candidate person words as a *workload aid only*, styled
    differently from selected spans and never preselected.
- **Span selection.** Click a word, or extend with the arrow keys, to set `first_word` and
  `last_word`. Further co-referring spans can be added.
- **Candidates, the "find potential mappings" part.** For the selected span, the panel ranks film
  moments from four independent signals, each shown with its reason:
  1. **Scene nomination.** The recall-scene coding nominates a scene, at the declared 1-based
     Princeton TR. Its boundary flag is shown, and the alternative origins are visible.
  2. **Name hits.** Recall tokens that equal annotation names (folded as in the core) point to
     the rows listing or speaking that name.
  3. **Lexical retrieval.** A local BM25 index over annotation scene details and the recognizer
     transcript ranks rows by overlap with the recall window. It is deterministic, standard library
     only, and makes no model call.
  4. **Dialogue echo.** A recall word sequence that matches transcript words (for example a
     remembered line) points to its exact timestamp.

  The candidates are aids, never answers.
- **The film panel, the "zoom to the right part" part.** Choosing a candidate cues the video to that
  row's interval with padding and loops it with R. The timeline marks scene and row boundaries, and
  frames are available at row midpoints. Playback rate is adjustable, and a frame-step key is
  provided.
- **Search, the "answer questions" part.** `/` opens one search over annotation names, details and
  the transcript. Typing "Lestrade", or a remembered phrase, lists timestamped hits across both
  parts; Enter jumps to one. Questions like "when does this person first appear?" or "who is in this
  scene?" are answered from the annotation table.
- **The decision form.** It holds exactly the `decisions.csv` fields, with keyboard shortcuts.
  Choosing a source scene in the other partition marks the decision as a quarantine candidate,
  and the panel says why.
- **Draft-and-confirm,** only if the owner chooses it. An agent draft is hidden until the reviewer
  commits. Revealing it is logged, and disagreements go to a second-look queue.

### Structure panel (insight, toggled with `S`)

The reviewer should see the shape of the recall, not only the current line. The panel draws from
raw evidence only:
- **Trajectory.** Recall order on one axis and film order (scene, or row and second) on the other.
  Each committed decision and each scene nomination is a point, so jumps, revisits, skipped
  stretches and compression show at a glance. Clicking a point jumps to it.
- **Scene coverage.** A strip across the 50 scenes, shaded by how much recall touches each, with the
  v2 blocks and partitions marked.
- **Presence.** Per character, a strip of the rows where the annotation lists them, with the
  speaking rows emphasized. "Who could 'she' be here?" becomes a vertical glance at the current
  moment.
- **Reference chains.** Committed decisions that share an occurrence group are linked, which shows
  the chains that cross scenes or partitions.

In the source pass, only the presence and scene strips are shown. Nothing derived from recall
appears there.

### Source pass (arm A; no recall shown)

Work goes block by block, in v2 order, discovery first.
- For each annotation row, the reviewer confirms `Name - All` people present against the footage,
  adds unnamed people, and resolves each recognizer person-mention in the row to a person present,
  "absent", or unknown, all with exact anchors.
- The reviewer records cross-row identity for unnamed people, and the row's event, state and context
  where they matter.
- The output is `source-review/v1`. The recall panel and recall search are disabled in this mode.

## Efficiency features

- Keyboard first throughout, with a `?` overlay. Every action has a key.
- The next item preloads: the video seeks during idle time and frames are prefetched.
- The time per decision is measured from the first view to the commit, and the first-batch rate is
  reported. This is the packet's "measure the first batch before committing the budget".
- Edits autosave to the JSONL log on every commit, and review resumes exactly where it stopped.
- **A progress strip** shows:
  - windows read, out of 50;
  - decisions per partition;
  - resolvable natural decisions against the floor of 15, as a live estimate;
  - hard strata covered;
  - quarantine count.

  The reviewer can then see when a block prefix meets the stopping rule. The counts only display;
  the rule is applied by Codex's runner.

## Architecture

- `tools/review_workbench/server.py` is a Python 3 standard-library HTTP server. It provides:
  - token-gated JSON APIs: packet, decisions, windows, search and frames;
  - media with HTTP Range support, which seeking needs;
  - frames from ffmpeg, cached privately.
- `tools/review_workbench/packet.py` builds `review-packet/v1` and the candidate index from the
  pinned inputs, and verifies their hashes.
- `tools/review_workbench/store.py` handles the append-only log, the deterministic CSV export,
  and validation (ids, row bounds, declared values, uniqueness).
- `tools/review_workbench/static/` holds `index.html`, `app.js` and `app.css`, in vanilla ES
  modules with no build step. Components are split along future Laminar boundaries: RecallPane,
  CandidatePane, FilmPane, SearchPane, DecisionForm, ProgressStrip and SourcePass.
- **Tests,** synthetic only:
  - Python unittest for the packet, the candidates, the store, the validation, the token and host
    guards, and Range handling;
  - a headless API smoke test over a synthetic packet and a tiny generated media file;
  - SD1 mutants on the custody and validation guards.
- **Launch** is one command, `python3 -I tools/review_workbench/server.py --config <private
  config>`. It prints the tokened URL.

## Slices

| Slice | Content | Done when |
|---|---|---|
| W0 | This plan and the three JSON Schemas with synthetic examples | reviewed and landed |
| W1 | Server core: config and pins, token and host guard, packet builder, Range media, frames | tests and mutants pass; review |
| W2 | Candidates and search: nomination, name hits, BM25, dialogue echo | per-signal tests on synthetic data; review |
| W3 | Store: JSONL log, CSV export matching Codex's template, validation, window read state | round-trip and validation tests; review |
| W4 | Recall-pass UI, including the structure panel | it works end to end on the real local packet |
| W4h | Visual hill climb of the recall-pass UI (`visual-hill-climb`): bounded, scored rounds with fixed specimens and independent critics on design quality, utility, speed of accurate annotation, and structural insight | the rounds' scores plateau; no regression on fixed specimens |
| W4p | Owner pilot: 5 timed items on throwaway material | fixes from the pilot are made |
| W5 | Source-pass UI and the `source-review/v1` artifact, with the source-first separation enforced | the separation guard is tested; review |
| W6 | Port note: mapping the contracts and components to storyatlas4s Laminar | reviewed note; port is a later epic |

The order for the owner is W0-W3, then **W5 before W4 is used for real**, because the source pass
comes first. W4 can be built in parallel and piloted on synthetic or throwaway items. W4h runs on
synthetic specimens and the real packet's non-confirmation material only, so that confirmation
recall is not paraded before critic agents.

## Coordination

- **Codex** owns the census, the decision schema and the pilot runner that validates and consumes
  the decisions. The workbench writes Codex's exact template. Any field change goes through Codex
  on Fray (#136) first.
- **Data custody** matches Codex's packet: private files are mode 0600 in a mode-0700 directory,
  every generated private file is ignored, and only hashes and counts are committed.

## Risks

- **Suggestions anchor the reviewer.** A ranked list can steer judgment. Mitigations:
  - suggestions are never preselected;
  - every suggestion shows its reason;
  - the suggestions shown for each decision are logged;
  - a "suggestions off" toggle exists for a calibration sample.
- **The video codec.** The parts are H.264 and AAC in an M4V container, which browsers play.
  If one does not, ffmpeg remuxes it to MP4 privately.
- **Unclear scope.** The workbench is a reference tool, not an arm, and it never claims accuracy
  itself.
