# Ordinary transcript intake (2026-11-15)

Ticket `bd-01M7H5EEW44QMZRQAWH3PGX63K`. Runtime candidate
`9ec5e2c96850eab5a06e9a9f771d80cac2e4239f`, over recovered main
`7ca7baf1f0db14cc95b10bae13d2997ff66aa39f`.

[`agentStudyPrepare`](../../../guide/ordinary-transcripts.md) prepares ordinary UTF-8 recall
files locally, using the existing heuristic segmenter. Its versioned export retains raw text,
canonical text, exact canonical UTF-16 spans, checksums and recipes. Execution regenerates the
segmentation and source prompts before making a request, then uses the common study mapping
and live/replay path. Automatic bundles expose canonical text and spans for inspection.
Supplied-unit exports preserve their existing identities and serialization.

## Evidence

The [qualification receipt](qualification.json) binds the exact gate, test totals, legacy
comparisons, compiled mutation controls and actual command-line walkthrough. Private inputs, outputs and full logs
are retained in ignored `data/.transcript-intake/20261115/`. Only reviewed synthetic fixtures,
counts, hashes and commands belong in this public receipt.

Independent code review passed the runtime SHA after correcting an invalid accepting control
in a compile-time test. That correction was cleanly recompiled: 14 focused tests passed, zero
failed or ignored. The earlier broad run was deliberately interrupted with exit 143 and is not
passing gate evidence. A later local broad run was also stopped deliberately after hosted
qualification completed; its partial totals are not substituted for a complete gate.

The full hosted [CI matrix](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43798918791)
passes predecessor `0b7cdf0b730817b101cf8632639eac5a6e6beaa3`: JVM 17 and 21 each passed 4,960 tests with 22 skipped; JavaScript and Native
each passed 3,570 with none skipped. All report zero failures. The combined GitHub log view
omitted the JVM tails; direct job-log downloads confirm all 24 JVM module totals and the new
nine-test transcript suite in both jobs.
[Documentation verification](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43798918852)
also passed; Pages build/deployment were skipped on the task branch.

The predecessor local artifact gate passed 606 pipeline tests and 159 corpus-intake tests.
The final runtime was cleanly recompiled and passed all 606 pipeline tests again, with zero
failures or skips, then replayed Sherlock and all six FilmFestival conditions. All **125 bundles / 21,100
unit rows** are byte-identical to the previous supplied-unit baseline, with zero model calls.
Final formatting checks passed. The Python reader suite passed 60 tests.

The actual command-line walkthrough exposed a pre-existing launcher defect: Scala's generated
`@main` launcher required arguments whose Scala definition had defaults. The documented short
replay command printed a usage error and emitted no bundle, despite sbt reporting success.
The final runtime explicitly parses zero through three optional limit arguments. Its regression
invokes the generated Java `main`, requires output publication and compares the replayed mappings.
An old-launcher mutation checks that this witness fails for the original behavior.

Three compiled guard-removal mutants each failed their named witness while eight sibling tests
passed; restoring the guards passed all 14 affected tests. The final launcher regression likewise
compiled the old launcher and failed exactly its output-publication witness (eight siblings
passed). The final full pipeline gate cleanly recompiled tests after these mutations.

The command-line walkthrough now passes end to end: prepare ordinary files, execute against one
local synthetic HTTP request, replay in a separate credential-free process, and inspect the result
with the Python reader. The three unit rows, both mapping records and recall descriptor are
identical between live and replay; replay makes zero calls. No external provider request ran.

## Landing and final qualification

The reviewed candidate `49170e5d56795502790a74ae9d97d602013d128c` was fast-forwarded to
main and pushed; local, tracking and remote main matched. Only evidence documents and Mote
records changed after the qualified runtime. The engineering ticket is closed, its claim and
reservations released, and the merged task branch and temporary gate clone removed. All 18
pre-existing unrelated StoryAtlas file hashes are unchanged.

The [final runtime CI matrix](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43861422860)
passed: JVM 17 and 21 each passed **4,960 tests with 22 skipped**; JavaScript and Native each
passed **3,570 with no skips**. All four jobs report zero failures. The final Native direct
job log contains all 16 module totals and identifies the qualified runtime SHA.
[Final documentation verification](https://github.com/bbuchsbaum/storymodel4s/actions/runs/43861422841)
passed; Pages build and deployment were skipped.

At landing review, the Native rerun was pending. Independent review established that its
16 portable module trees, build settings and workflows were identical to the passed
predecessor, while the launcher correction affected only the JVM pipeline. That scope decision
remains recorded for provenance; the final Native rerun has now passed as well.

## What this establishes

The engineering slice gives a researcher an ordinary-file entry point, auditable text
coordinates, and deterministic replay. Source relabelling, changed descriptor fields, unknown
recipes, Unicode damage and mismatched record spans are refused. Prompt presentation quotes
multiline unit text as JSON; it does not flatten or rewrite the text whose spans are recorded.
The test fixtures are synthetic; no external provider request or participant-data publication
is part of this qualification.

Full scientific-preview delivery 4 remains open. Ticket `bd-01M7H69QWE0NKSNB8XWW6MC02H` requires
supplied and automatic boundaries on verified common transcript coordinates for Sherlock and
FilmFestival. Reconstructing a transcript by joining supplied units is not recovery of the
original, and positional pairing or first text match does not resolve repeated phrases.
Boundary disagreement must be reported separately from mapping error. Independent researcher
usability and prospective scientific measurement qualification remain separate work.
