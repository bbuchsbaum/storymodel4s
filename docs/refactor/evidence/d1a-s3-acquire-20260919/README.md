# D1A S3 typed acquisition support — 19 September 2026

Mote: `bd-01M2TAF8JH42NYJJAFWPD7HTZB`. Source/test candidate `17194421`,
based on landed S2 checkpoint `e94ef776`. All 18 mutation witnesses and clean restoration passed; final release qualification
is in progress. This receipt does not yet close S3 or authorize its landing.

`SourceSupport` carries optional `TypedSupport`, with `text(score, spans)` and a
derived text `spans` accessor. `EvidenceRef.support` exposes spans alone as `Text`
and anchors alone as `Anchored`; neither, detached twin form and `ById` have no
unambiguous singular support. Raw `EvidenceRef.spans` remains available. The pure
resolver tests bundle support or winning inline support presence. It does not
resolve a ledger, join a bundle, project coordinates or license `SurfaceExplicit`.
`ClaimMeta.spanLaw` is unchanged.

`TaskReferences.validateAgainst(NarrativeSourceAtlas)` delegates text validation
to the existing surface overload and checks anchored parser references against
the bound proposal surface. Missing surfaces, missing units and wrong kinds are
refused; node/claim existence stays permissive unless a universe is supplied to
`validateWith`. The shared text compiler input join refuses anchored
`SourceSupport`, before its text-only canonical renderer could discard that
payload. Text and absent support remain admitted by that input join.

The historical `NoSpanEvidence`, `MissingSpanEvidence` and
`missing-span-evidence` wire/render strings are retained and pinned literally,
including the nested unresolved shape. Existing document and executable-example
call sites use the text constructor. No provider module changes.

## Text comparison and review

[Baseline capture](baseline-source-capture.json) and its
[independent audit](baseline-source-audit.json) bind the four historical acquire
sources at `75c8df52`; each still matched the pre-S3 `e94ef776` source.
`BaselineTextResolver.scala` retains the exact historical `Resolver` object with
only the recorded name/package/import wrapper and trailing whitespace treatment.
[Oracle/partition review](oracle-partition-review.json) binds that extraction and
the finite 8,640-case partition to the candidate. It compares full
`ResolutionState` values, including basis and ordered evidence, across policies,
bases, agreement patterns, text support, scores, structural outcomes and critics.
Separate cases cover losing support/basis, ledger references, rank ties, duplicate
evidence order, threshold neighbors, upstream-only evidence and family policies.

This is historical-code differential evidence, not an old-binary replay or an
exhaustive enumeration of every possible input. The oracle shares current value
types and helper implementations. [Shared-helper review](shared-helper-review.json)
binds seven groups to the historical code and records the comment-only changes.
The mutation court therefore includes a change confined to the current resolver,
with an anchored accepting control, plus independent literal constructor/accessor
witnesses that cannot be masked by both resolvers sharing a changed helper.
Invalid numerical input is outside the declared parity partition; this slice
does not redesign score validation.

The [separate SD6 review](code-review.json) explicitly reviews the changed
acceptance rule and has no material findings. The accepting task-reference
control is separate from negative/error-order assertions, so a refusal mutation
cannot invalidate its own control. [Initial compilation](initial-focused.json)
passes 49 tests at `83e7d83d`; the formatted candidate's expanded focused court
passes [55 tests before mutation](isolated-control.json). The
[18 compiled mutants](mutations.json) each fail their named rejecting test while
a named accepting control passes, with zero test errors. After restoring every
source, the [clean focused court](restored-control.json) again passes all 55 tests.
The [independent mutation audit](mutation-audit.json) reconstructs every edit and
checks source, mutant, log and per-mutant JUnit hashes and named outcomes. The
55-test before/after control receipts are bound to their logs; the runner did not
archive separate control XML. Final provider JUnit is a separate court.
[The failed first compile](failed-attempts.json) retains the test-helper keyword
error and its repair; it is not a mutation witness.

## Pending qualification and reproduction

The [settled inventory](guard-witness-inventory.json) names 18 mutations covering
the support cases and selection, text conversion/access, historical text
comparison, task references, text compiler join and wire compatibility.
[qualify.py](qualify.py) contains every exact edit, named rejecting test and
accepting control. Create a clean standalone clone at `17194421` and an exact
grakern clone at `0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Set
`D1A_S3_REPO`, `D1A_S3_GRAKERN` and a fresh `D1A_S3_OUTPUT`, then run
`python3 qualify.py mutations` and `python3 qualify.py release` sequentially.
Existing logs are refused rather than overwritten. No new compile-time
construction boundary is introduced in S3; these are runtime witnesses.

The release command runs `clean compileAll testAll`, separately checks
`scalafmtCheckAll scalafmtSbtCheck`, and verifies executable docs examples.
The exact-provider storyatlas4s consumer gate is also required before closure.
Raw logs and per-mutant JUnit remain under `data/study/d1a-s3-20260919/`.
The S0 frozen JSON must retain SHA-256
`cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3`.

No film compiler, caption license, gold exposure, scoring change, scientific
validation, executed CI or remote publication is established by this slice.
S4a's fallible draft and node-support migration follows after S3 lands.
