# Unified main, 2026-09-05

The owner requested one up-to-date main before further scientific work. The integration includes
local Film Festival tip `42f8a945`, published layer-ledger tip `f4ee65b4`, Own the Metric successor
`d7ffab48`, original Own the Metric history `cc11d142`, and ladder handoff follow-up `3cc1c949`.
The old evidence-codec conflicts retain the successor's schema 0.7.0, along with intervening
turnover and root-segment changes. Layer use keeps ADR 0016; diagnostics become ADR 0017.

Two integration checks found concrete defects in incoming work. NodeSummary equality omitted
propositional scope: two new assertions failed with four siblings passing before the fix, and all
six pass afterward. LayerUse.render used Locale.ROOT formatting that cannot link on Scala.js;
decimal fixed-scale rendering preserves the existing outputs and all eight ledger tests pass on
JVM, JavaScript and Native. The requested TransitionKind.features pin is falsified by omitting
CauseToEffect: its assertion fails, three siblings pass. A separate cold read checked the merges,
fixes and ladder scoring; 332 ladder input hashes and all retained gold labels were verified.

The [ladder readout](2026-09-02-recall-to-video-study-log.md#navigation-ladder-readout-2026-09-05)
records its measured nulls and invalid historical controls. The Film Festival saved replay also
passes on the unified implementation: 124 inputs, two complete 3,751-unit populations.

The pre-unification primary workspace is preserved exactly on
`backup/own-the-metric-workspace-20260905` at `13400455`. Its 27 files include superseded Film
Festival versions, the mission/vision drafts, and a historical navigation audit. Those drafts
are preserved for later review; they have not replaced newer code or results on main.

The clean implementation at `6747bbd3` passed `sbt checkAll`: **5,966 passed, five skipped, zero
failures or errors**, across 52 test-task totals. All 38 Python tests and the separate final
`scalafmtCheckAll scalafmtSbtCheck` passed. Receipts are in
[the evidence directory](../data/sherlock/navigation-ladder-20260905/). The full build used the
permitted local grakern override at `d736dc565d97f617726bad0a9d1ba2fdeae58dd2`; its unrelated
README edit remains outside this consolidation. This does not establish a build against the
remote grakern pin or hosted CI.

The following receipts/tracker commit changes no implementation, test or build input. Incoming
navigation handoff records and its peer notice are preserved as exact Mote operations. Raw logs
and study outputs remain under `data/study/main-unification-20260905/`. This does not complete
the pending independent Film Festival annotations/correspondence.
