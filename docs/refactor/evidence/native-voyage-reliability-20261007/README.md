# Native WorkspaceVoyage fixture reliability — 7 October 2026

Mote `bd-01M4B896MCEE43Q9QMR4HT60R0`; baseline
`975f06761695fd23b10442d9cb27b12eee81eb34`; code candidate
`09f08b43e8be88c0efdf12a6efbfab13d1801b18`.

The external-winner adapter court rebuilt and revalidated the complete WOG archive inside
its 30-second test deadline. The retained baseline hosted failure is in the
[queue audit](../release-queue-20261007/README.md): attempt 1 timed out at 30.907s; a same-source
retry passed. This repair separates checked fixture production from adapter acceptance.

Only `WorkspaceVoyageSuite.scala` changes. The identical checked WOG workspace is cached in
`externalWinnerWorkspace` and explicitly forced by `beforeAll`. Its setup duration is printed
separately. The adapter invocation and all original external-winner, residual source mass,
no-source-anchor and disposition assertions remain in the named test under the default
30-second deadline. All nine tests and the accepting Bell court remain. No estimator, public
API, codec, build setting, consumer pin or numerical value changes. Other Bell repacks remain
inside their tests. The sibling consumer does not consume the changed test class.

## Diagnosis

The timing-only baseline patch retains every original assertion and timeout. Native on this
Mac passed all nine courts: it did not reproduce a deterministic local timeout. It measured:

| Work | Local Native time |
| --- | ---: |
| Shared producer setup | 41.470s |
| WOG checked archive creation | 20.393s |
| WOG source view / HSMM decode | 0.244s / 0.040s |
| WOG adapter projection | 0.227s |
| Individual Bell checked creation | 3.793–6.728s |

WOG source bytes: 477,497; Bell: 9,024. The failed hosted run establishes runner-dependent
crossing of the deadline; these local numbers identify the work consuming it. The repair
retains that work, rather than claiming an algorithmic speedup or universal timeout immunity.

## Local qualification and discrimination

`local-gate.json` binds the clean restored standalone clone and full fatal-warning gate:
165 JVM, 160 Scala.js and 160 Native tests passed; **485 Passed / 0 Failed / 0 Errors / 0 skipped**.
Formatting checks ran last. Native still performed 41.561s of producer setup plus 19.869s of
checked external-winner setup, and all nine adapter courts passed under their unchanged deadlines.
The reference-scope tool derives only `fixtures`.

The compiled `residual-source-fallback` mutant adds `.orElse(alignment.mapSource)` to the
adapter's chosen anchor. It invents a source anchor when the global decision is external,
while satisfying the downstream anchor invariant. The named external-winner court fails
at `assertEquals(result.document, None)` with `munit.ComparisonFailException`; eight siblings,
including the accepting Bell court, pass. Exactly 1 Failed / 0 Errors / 8 Passed / 0 ignored.
The mutant is restored; the complete platform gate is the restored passing control.

Raw logs are compressed without losing bytes; their wrapper sidecars contain actual command,
cwd, duration and exit status. Compressed patches and original/mutant hashes bind the diagnostic and
mutation. No private data or participant content was used. The platform gate runs on public,
checked synthetic/researcher-reviewed fixtures. No sealed benchmark, hosted model call or
scientific study occurred.

## Reproduce

In a standalone clone checked out at the code candidate (not a linked worktree):

```sh
sbt -batch 'set ThisBuild / tlFatalWarnings := true' \
  fixturesJVM/test fixturesJS/test fixturesNative/test \
  scalafmtCheckAll scalafmtSbtCheck
bash tools/reference-scope.sh 975f06761695fd23b10442d9cb27b12eee81eb34 \
  09f08b43e8be88c0efdf12a6efbfab13d1801b18
```

For the diagnostic, preserve this directory outside the clone, check out the baseline and
decompress and apply `baseline-profile.patch.gz`, then run the named Native suite. For the mutant, decompress and apply
`residual-source-fallback.patch.gz` to the candidate and run the named JVM suite, expect its
single assertion failure, restore the exact codec file and run the complete gate above.
Use the build's pinned dependencies; record the actual runner and counts.

## Hosted qualification

[CI37650914132](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37650914132) passed on
exact code `09f08b43`: all four matrix jobs are green. Complete logs and parsed receipts retain
80 module totals: **12,722 Passed / 0 Failed / 0 Errors / 12 existing skips**. Native job
112893696980 ran all 16 cells: **2,822 Passed / 0 Failed / 0 Errors / 0 skipped**; fixtures passed
160 tests and the Voyage suite passed all nine with zero ignored. Its separately timed producer
and checked external-winner setup are retained in `hosted-native-observation.json`.

[Docs37650914019](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37650914019) passed
at the same code SHA, verifying all thirteen executable examples. No source/runtime inputs
change in the subsequent tracker/evidence metadata. Source and metadata reviews and actual
main/GitHub/workstation parity are bound separately in the final handoff.

`verify.py` rechecks full log hashes, command exits, exact source/run provenance, totals,
compiled mutant discrimination, original diagnostic and exact artifact-manifest coverage.


The original completion command continued through the server restart and finished successfully.
An initial recovery read saw an intermediate state; `recovery-cleanup.json` records the redundant
reservation close and the refused no-active-claim release attempt. Canonical readback confirms
zero active or orphaned claims/reservations. The original completion note and issue close were
not repeated. `handoff.json` binds the actual successful completion, queue counts and code parity.
