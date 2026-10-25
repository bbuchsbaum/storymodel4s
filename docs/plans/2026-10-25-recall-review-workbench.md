# Recall review workbench: a local tool for recall-to-video mapping and attribution review

**Date:** 2026-10-25. Revised after the cold review of `8db49c1cd`, which blocked it on six points:
- agent use of real data;
- server logging;
- the source-first rule;
- the person list's dependence on arm A;
- the missing referent column;
- the live progress counts.

**Commissioned by the owner** on 2026-10-25: "go ahead and do the whole thing… it should be
exceptionally good and efficient at facilitating recall to video mapping", and: "visually hill climb
the interface with the aim of well-designed UI, utility, and helping user annotate quickly and
efficiently and even gain insight into structure."

**Epic:** Mote `bd-01M5T4EEJ5NT0MGNHGABGVRAJZ`, under the evidence-binding epic
`bd-01M5MR1KTXEP898CTN2KE3J219`.

**First use:** human reference review for the identity-attribution experiment (owner decisions op
`20261025T174031.593362Z-p20738-c0000-r7f9b-h1e09a1`).

**Later:** a port to storyatlas4s, which is a Laminar (Scala.js) app.

## Purpose

A reviewer working through a participant's recall has to do three things, one recall line at a time:
1. find where in the film that line points;
2. decide which person each reference means;
3. record why.

Today that means a CSV, a static HTML page and a video player in three windows. The workbench puts
them in one keyboard-driven screen, where every recall line comes with candidate film moments and
the clip already cued. The goal is the fastest *correct* judgment, not the fastest click.

## Non-negotiable constraints

### 1. Custody

Participant recall never leaves the machine (rule 14d). That includes leaving through any agent,
because agents are remote models.
- **Only the human reviewer opens the real packet.**
  - No agent launches the server on real data.
  - No agent drives, screenshots or reads the UI on real data. That covers the author, the critics
    and the testers.
  - All agent development, testing and hill climbing (W4h) runs on **fully synthetic specimens**:
    an invented recall, an invented annotation table, an invented transcript, and a generated test
    film (ffmpeg `testsrc` with tones). These are committed as fixtures.
- **The server binds to `127.0.0.1` only** and refuses any other Host header.
  - The token is a random value printed once to the owner's terminal. The first request exchanges it
    for an HttpOnly, SameSite=Strict cookie, and later requests carry only the cookie.
  - **Request logging is disabled.** `log_message` is overridden to write nothing.
  - **Searches and every request that carries text are POST bodies,** never query strings.
  - Responses send `Cache-Control: no-store` and a CSP of `default-src 'self'` with no external
    loads.
  - The UI uses no localStorage or IndexedDB. State lives in the server's private log only.
- **Caches and logs** (frames and the decision log) live in the mode-0700 private directory, with
  files at mode 0600. Every generated private file is git-ignored.
- **The owner is advised** to use a dedicated browser profile with no extensions, because a browser
  automation extension can read any tab, and to keep the tokened URL out of shell history.

### 2. Blind to arm outputs

The workbench never loads any arm's output: not the automatic graph (B), the annotation-name
baseline (D), or the multimodal judge (C). It also never loads arm A's own `source-review`
artifact into the recall pass.

**Candidates point to film moments, never to persons.** No signal ranks or preselects a referent.

**The person list in the recall pass is built independently of arm A.** It holds:
- every distinct `Name - All` entry in the annotation, as a named character or a role label with its
  count, in a fixed alphabetical order;
- people the reviewer creates during the recall pass, for example "unnamed woman 1";
- the fixed options "absent / off-screen person", "no one in the film" and "Unknown".

**A mandatory "suggestions off" calibration sample** is part of the protocol. The first 5 committed
decisions, and then every tenth one, are made with candidates and search hidden. Agreement with
the suggestions-on decisions is reported. Every suggestion shown for a decision is logged privately.

### 3. Two passes; the whole source pass comes first

- **The source pass** builds arm A's reviewed source evidence. It never displays recall.
- **The recall pass** builds the reference census and attributions.

**The source pass is exhaustive and finished before any recall window opens.** Keying separation to
timing nomination fails. Codex records 40 rows with no nomination and 98 whose nomination changes
across clocks, and the stopping prefix needs recall counts, which would be circular. So:
- the workbench refuses to open any recall window until the source pass is marked complete for
  **every** scene that any v2 selection prefix could include;
- the source pass records every row, every `Name - All` person and every recognizer person-mention,
  with completion enforced. Recall memory then cannot choose what gets reviewed.

**Disclosed limitation.** The owner has watched the episode, and the project has examined NN03.
Ordering is not blindness. Exhaustiveness is what protects arm A, and the limitation is reported.

**Owner decision on cost.** An exhaustive source pass over all candidate scenes may be large: on the
order of 1,000 annotation rows, with rough planning figures of 3-5 hours at 10-20 s per row. The
owner chooses:
- a full source pass first (the recommendation);
- or a reported weaker separation, block by block, in order. If chosen, its limitation is stated in
  every result.

### 4. Codex's interchange is the canonical output

The output is Codex's private `decisions.csv` and `review-windows.csv`, column for column, with the
semantics of `2026-10-25-identity-feasibility-codex.md`, steps 1-6.

- **The referent has no column in the current template.** Before W3, it is agreed with Codex on Fray
  #136 either as a new `referent_id` column or as a stated encoding in `source_anchor_ids`. The
  workbench writes whatever is agreed. Nothing is invented.
- **Spans may cross window boundaries** (step 1).
- **Two steps per decision, following step 5.** The reviewer first commits the reference fields:
  span, form, role, occurrence group, person_reference, and the referent. Only then does the source
  evidence step open: source scenes, anchors, `source_resolvable`, `reference_outcome` and
  `ambiguity_reason`.
- **`review-windows.csv` has no reviewed_at column,** so the private JSONL edit log carries every
  timestamp.

### 5. Bound to the frozen census, and silent about the split

The workbench loads only if the recall CSV hash matches the pinned `5bfe5e4a…e48e` and the census
matches its receipt.

**The progress strip during labelling shows workload only:** windows read out of 50, and decisions
committed. The UI never displays partition or block labels, the 15-decision floor, strata or
quarantine. Choosing a source scene shows no partition consequence. Codex's runner computes all of
those after sealing.

## Data artifacts

- **Codex's `decisions.csv` and `review-windows.csv`** are the canonical, ported interchange.
- **The private JSONL edit log** is append-only and holds every edit, with its timing, the
  suggestions shown, and whether suggestions were off. `decisions.csv` is a deterministic export of
  the latest event per decision.
- **The source-review artifact** is experimental, for arm A. It is shaped to lower into the existing
  `source-identity-graph/v2` mention and class vocabulary, as experimental reference input, not a
  production `IdentityBasis`. It holds:
  - exact anchors for persons present in each row;
  - unnamed people with stable ids;
  - each recognizer mention resolved to a person present, "absent", or unknown;
  - identity across rows;
  - the event, state and context of each item.
- **The packet** is built in memory by the local server and never committed. It is split into:
  - a text-free index of ids, timings, scenes and hashes;
  - a token-gated text endpoint.

  That split is the seam a Laminar port needs, because the port also requires a local custody
  server.

These formats are tool-local and experimental. They live under `tools/review_workbench/schemas/`,
not `docs/data/`. Promoting any of them to public vocabulary needs an ADR line with its rejected
alternative (SD5) at port time (W6).

## Screens

### Source pass (first; no recall anywhere)

Work goes scene by scene, in film order. For each annotation row:
1. confirm or correct each `Name - All` person present against the footage;
2. add unnamed people;
3. resolve each recognizer person-mention in the row to a person present, "absent", or unknown;
4. set exact anchors.

Cross-row identity for unnamed people is set with one key. A completion meter shows how many rows,
persons and mentions have been resolved. A scene is complete only when every item has been resolved.

### Recall pass

```
┌ NN03 · window 17/50 · read 16/50 · committed 23 ───────────────── ⏱ 0:41 ── [?] keys ┐
│ RECALL                                      │ FILM  part b  ▶ 1:07-1:44  ⟲ R          │
│ … ‹prev› and then [[she]] was lying … ‹next›│ [ video, cued, ±10 s pad, rate 1×  ]    │
│ TR 331-334 · nominations: 1-based 29,       │ timeline: scene ▮▮▮▮ rows 500-504       │
│   0-based 29, closed-seconds 28/29          │ rows: Name-All · Speaking · details     │
├─────────────────────────────────────────────┤ frames: ▢ ▢ ▢ ▢ (row midpoints)        │
│ CANDIDATE MOMENTS (aids; never persons)     │ SEARCH  /  phrase or name → moments     │
│ 1 part b 1:14 row 502  nominated, name token │                                        │
│ 2 part b 1:24 row 503  dialogue echo        │                                         │
├─────────────────────────────────────────────┴─────────────────────────────────────────┤
│ STEP 1  span w331-w331 [⇧←→, crosses windows]  form ▾  role ▾  group ▾  person_ref y/n/u │
│         refers to: [alphabetical annotation people] [+ new unnamed] [absent] [none] [U]  │
│ STEP 2  (opens after step 1) source scenes · anchors · resolvable y/n/u · outcome s/c/u/e│
│ [Enter] commit · [Z] undo · [E] reopen a decision · ⚠ possible duplicate of d12          │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Navigation.** Windows are taken in order, and each must be marked read. The lexical screen is a
  styled workload hint, never a selection.
- **The candidate moments are four independent signals,** each shown with its reason and none
  ranking persons:
  1. **Scene nomination,** with the three conventions side by side.
  2. **Name tokens:** recall tokens that equal annotation name tokens point to rows.
  3. **Lexical retrieval:** a local, deterministic BM25 index over annotation details and the
     transcript. It uses the standard library only and makes no model call.
  4. **Dialogue echo:** recall word sequences that match transcript words point to their timestamps.
- **The film panel.** Choosing a candidate cues the video, with padding, loop, rate control and
  frame stepping.
- **Search.** `/` opens one search over annotation and transcript, which answers "who is in this
  scene?" and "where is this line?".
- **Duplicate warning.** A new decision overlapping an existing span with the same referent shows a
  warning (step 3).
- **Draft-and-confirm,** only if the owner chooses it. An agent draft appears only after the
  reviewer's own commit. Revealing it is logged, and disagreements go to a second-look queue.

### Structure panel (`S`; owner use)

These views are for insight during review. They draw on raw evidence and the reviewer's own
committed decisions only:
- a **scene coverage strip** across the 50 scenes;
- a **presence strip** per annotation person;
- the **trajectory** of committed decisions, with recall order against film order.

Partition labels and reference-chain links are not shown, because they would expose quarantine
structure (constraint 5). In the source pass, only the presence strip appears.

## Efficiency features

- **Keyboard first,** with a `?` overlay.
- **Preloading:** the next item's video seek and frames load ahead.
- **Timing:** each decision is timed from first view to commit, and the first batch's rate is
  reported, as the packet requires.
- **Autosave and resume:** every commit is saved to the log, and review resumes exactly where it
  stopped.
- **Undo and reopen.**

## Architecture

- `tools/review_workbench/server.py` is a Python 3 standard-library server. It provides:
  - a loopback-only listener with a Host check;
  - the token-to-cookie exchange;
  - no request log, no-store caching and a CSP;
  - POST JSON APIs: packet index, text, candidates, search, decisions, windows, source review and
    frames;
  - media with HTTP Range support.
- `packet.py` builds the text-free index and the text store from the pinned inputs, and verifies
  their hashes.
- `candidates.py` implements the four signals and search.
- `store.py` keeps the append-only log, exports Codex's CSVs, validates them (ids, row bounds,
  declared values, uniqueness, crossing spans), and enforces the source-first gate.
- `static/` holds vanilla ES modules with no build step. Components are split along Laminar
  boundaries: RecallPane, CandidatePane, FilmPane, SearchPane, DecisionForm, ProgressStrip,
  SourcePass and StructurePanel.
- **Tests run on synthetic fixtures only.** They cover:
  - each signal;
  - the store round trip and validation;
  - custody, with SD1 mutants: host, token and cookie, no request log, POST-only text, no-store and
    CSP;
  - the source-first gate, with an SD1 mutant;
  - Range;
  - a headless API smoke test on the generated test film.

## Slices

| Slice | Content | Done when |
|---|---|---|
| W0 | This plan, and the tool-local schemas with synthetic fixtures | reviewed and landed |
| W1 | Server core: the custody guards, the packet index and text, Range media, frames | tests and SD1 mutants pass; review |
| W2 | Candidates and search | per-signal tests; review |
| W3 | Store: log, export to Codex's CSVs, validation, source-first gate, referent field as agreed with Codex | tests and mutants pass; review |
| W5 | Source-pass UI | completion enforcement tested; review |
| W4 | Recall-pass UI and the structure panel | works end to end on synthetic specimens |
| W4h | Visual hill climb (`visual-hill-climb`) on **synthetic specimens only**, with a few bounded rounds scored on design quality, utility, speed of accurate annotation and structural insight | scores plateau; no regression on fixed specimens |
| W4p | Owner pilot on throwaway material, 5 timed items; the owner alone | fixes made |
| W6 | Port note to storyatlas4s, including the SD5 ADR line for any promoted format | reviewed; the port is a later epic |

The owner's real review then runs in this order: the full source pass first, then the recall pass.

## Coordination

- **Codex** owns the census, the decision template and the pilot runner. The workbench writes
  Codex's exact template. The referent field, and any other change, is agreed on Fray #136 first,
  and the workbench uses Codex's private working copy for human intake rather than a copy of its
  own.
- **Custody** matches Codex's packet: private files at mode 0600 in a mode-0700 directory, every
  generated private file ignored, and only hashes and counts committed.

## Risks

- **Suggestions anchor the reviewer.** Mitigations: candidates point to moments, never persons;
  suggestions are never preselected; the calibration sample is mandatory; and the suggestions shown
  are logged.
- **Video playback.** The parts are H.264 and AAC in an M4V container, which browsers play. If one
  does not, ffmpeg remuxes it to MP4 privately.
- **Source-pass burden.** The exhaustive pass is large. That is an owner decision (constraint 3),
  and its measured rate is reported after the first scenes.
- **Scope.** The workbench is a reference tool, not an arm, and it claims no accuracy itself.
