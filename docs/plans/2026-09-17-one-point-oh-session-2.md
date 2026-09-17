# 1.0 session 2 — evidence record, 2026-09-17

Continues `docs/plans/2026-09-17-one-point-oh-session-1.md` under §8 of the 1.0 handoff, in
single-developer mode (AGENTS.md SD1–SD6). Label for everything below: **LocallyObserved** — one
author, one machine, no second reproduction. Tree at session start: `a277ada7`.

Scope executed: the handoff's recommended order for session 2, items 1–3 (warnings, docs transcript,
CI). T7 was not started. The owner corrected one handoff premise mid-session: grakern has a remote
(`github.com/canardlapin/grakern`, public), so the sibling-pin question became a checkout path, not a
design decision.

## Landed

| Item | Commit | What changed |
|---|---|---|
| Every compiler warning retired | 43140bcb | 7 main-source E198 sites the handoff counted, plus 12 test-source sites it did not (fatal warnings apply to test compilation too). No `@nowarn`; one `scala.annotation.unused` on a trait-default hook. Three dead parameters removed with their call sites. |
| Docs transcripts re-recorded | 17b97ce7 | Three drifts, not one. Two example programs repaired. Prose corrected on three pages. |
| CI stood up | 14f95f33 | `ci.yml` (sbt-typelevel generated), `clean.yml`, `docs.yml` (hand-written). Build settings with reasons. Verifier no longer requires the grakern override. AGENTS.md rule 4 amended. |

## What the docs drift actually was

The handoff reported one drift (`model-a-story`, 462 vs 475 claims). Replaying all thirteen examples
found three, and the verifier's stop-at-first-drift behaviour had hidden the other two:

- `save-model`: canonical JSON is 477,497 bytes at schema 0.7.0, not 430,246 at 0.1.0. The example
  forged the schema version by string-replacing the literal `"0.1.0"`, which no longer occurs, so
  the transcript line `unsupported schema rejected: true` had become `false` while the codec guard
  itself (`Migration.toCurrent`) was intact. The program now replaces the model's own version.
- `resolve-acquisition`: the program did not compile against the ADR 0010 acquire API
  (`CandidateCalibration` → `CandidateBasis`, `Accepted` carries an `AcceptanceBasis`). Commit
  `3cba4a1c` touched the example but not enough.

A documentation gate that stops at the first failure understates its own findings. The gate's
behaviour was left as is; the finding is recorded here.

## Gates

**Full clean gate** on `17b97ce7` (the CI commit after it touches build settings, workflows, docs
scripts and prose only):

    sbt -Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern \
        clean compileAll testAll scalafmtCheckAll scalafmtSbtCheck ; echo GATE_EXIT=$?

- `GATE_EXIT=0`; 13:31:02Z → 13:46:44Z.
- 52 `Passed: Total` lines summing to **5,986** tests, 0 failed, 5 environment-gated skips.
- Compiler warnings: **0** (session 1: 7 main-source sites; a clean root `Test/compile` before the
  fix showed 19 distinct sites across main and test).

**CI simulation** on the `14f95f33` tree, no grakern override, `set ThisBuild / tlFatalWarnings := true`
(what sbt-typelevel sets under `githubIsWorkflowBuild`):

    sbt 'set ThisBuild / tlFatalWarnings := true' clean Test/compile embedGrakern/test \
        embedBench/Test/compile rootJVM/doc rootJS/doc rootNative/doc githubWorkflowCheck

- Exit 0; 15:55:24Z → 15:58:32Z. grakern staged from GitHub at the pinned `0329c43c` (gale and
  graph4s staged through grakern's own pins). `embedGrakern/test` 28/28 on a clean build.
- Scaladoc: 33 unresolved `[[link]]` sites (85 warnings over three platforms). Scaladoc drops
  `-Werror` ("Skipping unused scalacOptions: -Werror"), so the doc step does not fail on them.

**Docs gate** (`docs-site`), after `17b97ce7`:

| Court | Result |
|---|---|
| `verify:examples` with override | 13/13 verified |
| `verify:examples` without override (build resolves the pin) | 13/13 verified |
| `astro check` | 0 errors |
| `astro build` | ok |
| `verify:navigation` | 20 pages, 19 sidebar entries, 15 internal links |
| `verify:verbatim` | byte-identical |
| `verify:figures` | all ok |
| `verify:provenance` | all 11 checks ok (run with the owner's unstaged `data/README.md` edit stashed and restored; restored diff byte-identical to the saved copy) |
| `verify:layout` | **not run**: Playwright 1.57 needs Chromium build 1200; the browser cache holds 1194 and a stale `__dirlock` that this session was not permitted to remove |

## Falsifiers (SD1)

- Warnings: the CI simulation above compiles every module with `-Werror`; any surviving warning
  would have failed it. Before the fix, the same clean `Test/compile` listed 19 sites.
- Docs transcripts: the gate itself is the falsifier and was observed red before and green after.
  The save-model mutant is the pre-fix state: with the literal `"0.1.0"` replace, the output reads
  `unsupported schema rejected: false` (observed in the first replay).
- CI: no falsifier yet. Nothing has run on GitHub. The first run is the falsifier for Temurin 17,
  Temurin 21 and Playwright on `ubuntu-22.04`, none of which this machine can exercise.

## Cold review (SD6, fresh-context agent, read-only) — dispositions

Verdict LAND on `43140bcb` and `17b97ce7`, no blockers. Two nits recorded, not fixed:

- `SaveModel.scala` uses `String.replace`, which rewrites every occurrence; only matters for a
  pipeline-built model whose receipt carries the same version string, and the top-level guard fails
  first either way.
- `PRODUCER_REVISION` is free text no script verifies. Two pages now say `43140bcb`, nine still say
  `76c62e73` although all thirteen transcripts were verified in one run; and a checkout of `43140bcb`
  holds the old `SaveModel.scala`, while the page's `PROGRAM_SHA` is of the new one. "Executed
  against storymodel4s <rev>" stays true of the library. If the pin is ever meant to locate the
  program, it needs the docs commit's own SHA.

`14f95f33` was not cold-reviewed; it is configuration and its review is the first CI run.

## Residuals and decisions left open

- **Push and first run.** Three commits are local on `main`, ahead of `origin/main` (`a277ada7`).
  The push is the owner's. Cite the first run by URL, matrix cell and SHA; the T6 bead stays open
  until then.
- **License headers**: `tlCiHeaderCheck` is off because no source carries one. Adding Apache-2
  headers to ~700 files is an owner decision (T7 territory).
- **Dependency-graph job** off until a first green run; turn on afterwards.
- **Scaladoc links**: 33 sites; fixable mechanically, not urgent while scaladoc ignores `-Werror`.
- **`verify:layout`** never ran on this machine this session; CI installs the browser itself.
- **`docs-site` re-recording** has no in-tree tool; this session used a scratch script that mirrors
  `verify-examples.mjs` but writes every actual output and continues past failures. Worth adding as
  `scripts/record-examples.mjs` if drift recurs.
- **Friends intake** (`data/README.md` edit, `docs/data/friends/`, two `docs/plans/*friends*`
  files, one new since session 1) remains untracked/unstaged on the owner's instruction.
- T7 publishability untouched: `scmInfo`/`homepage` still `None`, no MiMa baseline, the
  `io.github.canardlapin` vs `bbuchsbaum` coordinate still the owner's call.
