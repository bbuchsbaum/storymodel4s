# Portable recall timing qualification — 22 September 2026

Qualified source: `be121883b80de5c56402a7cb226afa4ccb957aa4`, integrated with
locally landed G1 (`8a455fcb`). The complete source tree was clean at gate start
and evidence collection. Subsequent changes contain documentation and evidence only.

The [consumer guide](../../../recall-timing.md) describes `RecallTiming.checked`
and the inventory-contextual `RecallTimingCodecs` sidecar. Every inventory word
is retained, including unassigned words. Explicit missing, onset-only and temporal
extent observations use clock-scoped exact seconds and declared provenance.
Unit onset diagnostics do not establish durations, exposure or onset uncertainty.

## Executed evidence

- Full `sbt -batch checkAll`: **56 test tasks; 7,966 passed; 5 skipped; zero
  failures/errors**. Compilation and both formatting checks passed.
- **718 fresh JUnit reports** are bound by relative path and SHA-256 in
  [qualification.json](qualification.json), alongside exact command receipts,
  task totals, source revision and output hashes. Full log, receipt and reports
  are in [checkAll.tar.gz](checkAll.tar.gz).
- The new suites contain **18 checks on each of JVM, Scala.js and Native**.
  They cover complete word accounting, exact extreme/rational coordinates,
  malformed legacy rational inputs, foreign clocks, missing boundaries, overlap,
  nonmonotonicity, UTF-16 identity and strict contextual JSON admission.
- **10 compiled mutations** fail their own named tests. Before, freshly restored
  constructor, and final controls pass. Two earlier attempts exposed stale
  compile-time probe caches; both are retained as inconclusive. The corrected
  runner cleans those probes before mutation and after restoration.
- The **48 existing Python clock/intake tests** pass. The independent checker
  compares four onset words/two units with the qualified earlier word-clock reader,
  exact interval extremes with `Fraction`, and **27 decimal vectors** with the
  actual Python intake. Four deliberately corrupted outputs fail the checker.
  See [independent.json](independent.json).
- An explicit producer run at the qualified source generated the
  [onset example](artifacts/onsets.json), [exact interval example](artifacts/exact.json)
  and [decimal vectors](artifacts/decimal-cases.json). They are byte-identical to
  the earlier independently checked artifacts.
- Separate [cold review](review.md) identified and repaired a decimal-token grammar
  mismatch. The follow-up found no remaining concrete interoperability issue.
  [checks.tar.gz](checks.tar.gz) retains raw focused, Python, mutation and export
  logs, including the initial compile failure and both cache-contaminated attempts.

## Preserved limitations

These are local synthetic checks, not hosted CI, participant inference, empirical
calibration or proof of shared recording identity. A `SourceReported` label and
recording/origin/link declarations are attributed inputs, not verification claims.
No scanner crosswalk, temporal union/exposure, continuous uncertainty query or
StoryAtlas interaction is established by this slice. Production timing intake and
source-support integration remain on the existing G2 ticket.

The raw gate log retains toolchain advisories, including Clang 15 and Java's
`sun.misc.Unsafe` deprecation. The optional live skips are:

- `storymodel4s.bench.sherlock.SherlockRecallMappingSuite`: the neural channel puts the red-door unit nearer its row than an unrelated row
- `storymodel4s.media.BoundarySearchSuite`: live ffmpeg and worker, when this machine has both, reproduce the recorded proposals
- `storymodel4s.media.CaptionSearchSuite`: live worker and weights, when this machine has them, reproduce the recorded proposals
- `storymodel4s.media.F1ExcerptSuite`: live tools, when this machine has them and the excerpt, reproduce the recorded packet table and proposals
- `storymodel4s.provider.agent.LiveSmokeSuite`: live smoke: one WOG sentence through the model, recorded as captured, judged by the court
