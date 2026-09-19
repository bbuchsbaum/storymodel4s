# Frozen scene-scoring support — 19 September 2026

Mote: `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2`.
Implementation: `dd6050e0ea2ac85e7c3de2e045882d5c64118e2b`.
Usage and contracts: [SCORING.md](../../../../tools/recall-study/SCORING.md).

The [full clean-clone gate](checkAll.json) binds this exact commit: `sbt -batch checkAll`,
exit 0, 56 module/platform totals, **6,380 passed test executions, five skipped, zero failures
or errors**. The clone was clean before and after. Skips are the live remote parser smoke,
one optional neural localization test and three live media/worker tests; the receipt names
them. The separate [format-last check](format-last.json) also exited 0. These are local gates,
not an executed GitHub Actions run. [Scope derivation](scope.json) identifies `embed-bench`.

The scorer reconciles complete arm outcomes against immutable observation identity and a
separately frozen gold-support digest before calculating any comparison. Blank, invalid and
provider-failure outcomes remain wrong whenever gold eligible. Missing gold remains visible
outside the localization denominator. The primary paired difference averages participants;
the separately labelled secondary difference pools units. Both use the same support and
uniform unit weights within every paired participant bootstrap draw. Configuration, counts,
exclusions, coverage, per-participant differences and leave-one-out results are exported.

The real [observation export](observation-export-absolute-path.json) used the committed
pipeline segmenter, all 17 admitted participant CSV files, the committed recall lineage,
partition metadata and admitted annotation bytes. It performed no inference and opened no
gold or saved prediction files. Its 836,472-byte unit manifest remains under the ignored data
root at `study/recall-to-video/scorer-20260919/unit-manifest.json`; only its
[hashes and aggregates](inventory-receipt.json) are committed here.

| Inventory quantity | Measured count |
|---|---:|
| Participants | 17 |
| Recall units | 2,577 |
| Minimum / maximum units per participant | 68 / 352 |
| Units with onset timing | 2,577 |
| Original adjacent unit pairs | 2,560 |

**The original plan's 2,560 units / 67–351 range was incorrect.** Those numbers exactly equal
the sum/range of final zero-based ordinals. That is consistent with an off-by-one counting
error; its historical generating command was not recovered. The older
[study log](../../../plans/2026-09-02-recall-to-video-study-log.md) already reports 2,577 anchors
in its 3 September entry. The operative plan and ticket now require all 2,577 units. No row
was dropped or segmentation changed to reproduce the mistaken count. Unit count and adjacent
transition count are explicitly separate quantities.

All 173 development-control units (NN03) match the earlier frozen baseline in IDs, ordinals,
text/report-text hashes, transcript hash and recall-graph hash. Every onset equals the baseline's
first timed word for that unit, following the existing pipeline. One unit's first word is
later than a subsequent word's timestamp; taking the minimum would change the established
onset rule. No timestamp reordering or timing repair was introduced here.

The first [export command](observation-export.json) produced the Scala witness successfully
but failed before exporting the unit manifest: a relative lineage path was resolved from
the forked module's directory. The successful retry used an absolute path at the same commit.
Both command receipts remain; the failure was not overwritten or counted as passing.

The executed [Scala witness](scala-rule.json) agrees with Python on TR duration, all 17
participant mappings and the two out-of-domain boundaries. This is actual language-to-language
execution, with a separate literal oracle derived from the preregistration. It does not assert
parity between Scala and Python interval lookup implementations. The shared record's scene
count and sorted closed-interval convention are Python scorer settings.

The [Python study receipts](python-tests.json) record all eight existing/new study test scripts
exiting zero, including 19 synthetic scorer tests. Tiny examples independently establish the
50% wrong/blank/failure result, unequal-size participant versus pooled estimates, confidence
interval endpoints, leave-one-out values, complete missingness accounting and no gap bridging.
The [nine compiled mutation witnesses](python-mutations.json) break unit/participant/input
identity, frozen support, exclusions, unexpected-file refusal, denominator retention and primary
aggregation. Every mutant makes its named rejecting test fail while preserving the accepting
complete-arm test. Synthetic narratives reuse the previously admitted baseline miniatures.

The [executed rule and exporter mutations](rule-and-exporter-mutations.json) add four
discriminating witnesses. A compiled Python alias change disagrees with the actual Scala
receipt. A compiled Scala alias change produces a fresh disagreeing export, fails the named
numeric test and leaves the foreign-name control passing. Changing the committed TR to 2.0
does the same; when both languages read that changed declaration their values agree, but the
independent preregistered oracle still refuses it. Cross-language agreement alone cannot
detect a coordinated change in the declared authority.

For participant admission, scratch symlinks swapped NN02 and NN03's already admitted bytes.
The original exporter refused before writing a manifest. Replacing the participant-specific
pin check with membership in any admitted hash compiled and accepted the swap. The original
all-17 export is its positive control. Original data files were never changed. All mutated
clone files were restored byte-for-byte, its tree was clean, and the two Scala tests plus the
final formatting check passed afterward.

A [development-only import probe](development-import.json) reads the already captured NN03
TSV through the new importer. A temporary one-person manifest subset tests this bridge without
opening saved sealed outputs: all 173 rows reconcile and remain labelled. This establishes
legacy-format compatibility for that control, not a new evaluation population or accuracy.

The [fresh-context review](review.json) distinguishes source inspection from independently
executed probes. It found and rechecked the support-edit and participant-swap holes, verified
the actual Scala/Python witness with Python mutations, and independently confirmed the real
inventory count correction. Full-court execution is recorded in the primary agent's separate
receipts rather than attributed to that reviewer. Local execution runners are retained under
the ignored data root and [hashed here](local-runners.json).

The gated implementation was fast-forwarded locally to `main`; this evidence, count correction,
usage clarification and Mote closure are a subsequent documentation-only checkpoint. No push
was performed. The next bounded implementation is production ClockRepair integration against
the existing byte-parity baseline. G0 still includes the separate S0 text pins.

These are engineering and localization-scoring contracts. They establish no localization
accuracy on real gold, behavioral recovery, reference-measurement compatibility or confidence
calibration. Historical B3 score reproduction and migration of downstream research instruments
remain separate ledgered tickets.
