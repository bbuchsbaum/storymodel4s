# 1.0 session 1 — evidence record, 2026-09-17

Executes §8 of `docs/plans/2026-09-17-one-point-oh-handoff.md` in single-developer mode
(AGENTS.md SD1–SD6). Label for everything below: **LocallyObserved** — one author, one machine,
no second reproduction. The tree audited in the handoff was `f1c17e76`.

## Landed

| Item | Commit | Chain link reached |
|---|---|---|
| Handoff recorded | a1a866b1 | — |
| T1 hsmm/v4 docs reverted to v3 | 9282dd69 | permits (read encoder, golden, docs) |
| T4 nine probe-anchor cherry-picks | 6ca05d72..8ec2acb1 | produces (recompiled after `clean`) |
| T5 MassRatio fail-closed polarity | 01cfbc46 | permits; NaN reachable by forgery only |
| T3 AlignError.EmptyPopulation | e285fed5 | produces |
| T2 RequireMinCoverage floor guarded (wire + point of use + constructor); Vector dimension at the wire | 724091ed, then test hardening after cold review | produces |
| Tracker hygiene, ADR 0003 amendment, AGENTS.md polarity note | e7117711 | — |

Not started (by design of §8): T6 CI, T7 publishability, T8 data verifier, Tier 3.

## Gate

Full clean gate on `724091ed` (every later commit is docs, `.mote`, or test-only):

    sbt -Dstorymodel4s.grakern.build=/Users/bbuchsbaum/code/scala/grakern \
        clean compileAll testAll scalafmtCheckAll scalafmtSbtCheck ; echo GATE_EXIT=$?

- `GATE_EXIT=0`; started 12:30:xxZ, finished 12:46:17Z (~16 min after `clean`).
- 52 `Passed: Total` lines summing to **5,986** tests (handoff baseline 5,971; +15 new), 0 failed
  lines, 5 environment-gated skips, format checks clean.
- E198 warnings: the same 7 sites the handoff lists (document ×5, pipeline ×2); none added.
- Test-only hardening after the gate: FeaturesCodecSuite 14/14 on JVM, JS and Native; WindowSuite
  30/30 JVM; `scalafmtCheckAll` last; exit 0.
- grakern override pointed at the live sibling checkout (d736dc56, 4 commits past the pinned
  0329c43c); the pin itself was not re-resolved. Not a fresh clone: run in the primary checkout,
  which is not a linked worktree.

## Mutation kills (each: apply, run the named suite, restore)

| Mutant | Suite | Result |
|---|---|---|
| M1 reduce guard disabled | WindowSuite | Failed 1/30 |
| M5 isCoverageFraction ≡ true | WindowSuite | Failed 2/30 |
| M2 EmptyPopulation reverted to SizeMismatch string | PopulationSuite | Failed 1/21 |
| M3a unsafe polarity reverted | SignatureSuite | Failed 1/36 |
| M3b support polarity reverted | SignatureSuite | Failed 1/36 |
| M4 Vector dimension guard disabled | FeaturesCodecSuite | Failed 1/14 |
| M6 wire floor guard bypassed (before and after hardening) | FeaturesCodecSuite | Failed 1/14 both times |

`tools/nan-polarity.sh HEAD` now lists `signature.scala:192` and `:230` under FAILS CLOSED.

## Cold review (SD6, fresh-context agent) — dispositions

- Wire NaN cases were tautological (canonical hex Double spelling) → fixed, see hardening commit.
- `isLeft`-only assertions where the message was known → replaced with equality on the guard's text.
- Residual, not fixed: `MassRatio.unsafe` still *stores* a NaN conditioning/total mass when forged
  (`value` is None and `support` is 0.0, but `render` prints NaN and structural equality makes
  the instance unequal to itself). The carrier refuses the *ratio*, not the operands. Candidate for
  the T11 signature migration.
- Residual, pre-existing: `-0.0` is a lawful floor with a different `canonicalString` from
  `0.0`, so two derivation ids for one policy. Not made worse; not normalised here.
- Two spellings of the same refusal (`got NaN` at the constructor, `got minCoverage(0x7ff8…)`
  at the point of use). Deterministic; left as is.

## Tracker after this session

closed 172 (+16), doing 13, open 42, review 11. P1A closed as superseded; E0 and P1B no longer
blocked by it. The two false-number rows are retitled with measured figures and carry a dated
correction note; their bodies are untouched history.
