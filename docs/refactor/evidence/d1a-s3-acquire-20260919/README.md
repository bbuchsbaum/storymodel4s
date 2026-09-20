# D1A S3 typed acquisition support — 19 September 2026

Mote: `bd-01M2TAF8JH42NYJJAFWPD7HTZB`. Source/test candidate `17194421`,
based on landed S2 checkpoint `e94ef776`. Local qualification is complete: all 18
compiled mutations, 55 restored focused tests, the full provider gate, formatting,
13 executable documentation examples and the exact-provider consumer gate pass.
The later branch commits contain ADR wording and evidence only; source/test/build
inputs remain those of the qualified candidate, as recorded in the
[source equivalence receipt](source-equivalence.json). Local landing and closure are
recorded in the Mote and delivery plan.

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

## Qualification and reproduction

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

The [clean provider gate](full-gate.json) passes 6,663 tests with five skips and
zero failures/errors across all 56 alias tasks. [Formatting](format-last.json)
passes separately. [Runtime](runtime.json) binds the actual sbt Java 25.0.1
welcome and S0 file hash. The [independent provider audit](provider-audit.json) reconciles every fresh
JUnit suite/testcase against its task total; the [population comparison](population-format-audit.json)
confirms the expected 63 additional test executions and the same five skipped
identities as S2. [All 13 executable docs examples](docs-examples.json) pass; the
[independent docs audit](docs-audit.json) binds each name and exact output byte
length to its candidate manifest and artifact.
The [consumer gate](consumer-gate.json) passes all 245 tests, formatting and
production `app/fastLinkJS`. The [independent consumer audit](consumer-audit.json)
accounts for all eight alias tasks: six have test suites; `editionJVM` and
`editionJS` have no tracked test sources. It binds the fresh production app bundle
separately from test bundles. All four clones remain clean at the exact revisions
below. The [existing consumer warnings](consumer-warning-comparison.json) are
byte-identical to S2; this is not a warning-free claim.

| Consumer gate input | Exact revision |
| --- | --- |
| storymodel4s override | `17194421f018e3780aafa4adf754fccf55398b61` |
| storyatlas4s | `fb33bef7d0971530531fcec43cd3d7f141b8e137` |
| intaglio | `4eb566d9208f474d64d61e778e084dee2ddbaa76` |
| grakern | `0329c43c88a0b71e9aa4456723bb16bac2fa3841` |

To repeat the consumer court, create four clean standalone clones at these
revisions beneath one directory, named as in the table. Set
`D1A_S3_CONSUMER_ROOT` to that directory and `D1A_S3_OUTPUT` to a fresh output
directory, then run [consumer-gate.py](consumer-gate.py). Its command supplies all
three local build overrides explicitly. The generated consumer Pins still prints
the literal build pin; the gate receipt binds the actual override revision.
Raw logs and per-mutant JUnit remain under `data/study/d1a-s3-20260919/`.
The S0 frozen JSON must retain SHA-256
`cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3`.

No film compiler, caption license, gold exposure, scoring change, scientific
validation, executed CI or remote publication is established by this slice.
S4a's fallible draft and node-support migration follows after S3 lands.
