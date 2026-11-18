# Case study 2: first-wave library slices

**Status:** Proposed, 2026-11-17. The owner rules on [ADR 0032][vocab], `WorkId` placement and
the [ADR 0031 amendment][timing] before implementation. Each slice becomes one Mote bead after
approval. The finish line is row-by-row FilmFestival development replay with offline conversion.

Each slice names an acceptance check. Run affected portable modules on JVM, JS and Native with
fatal warnings, and compile and test storyatlas4s for changes to the surface it consumes. Local
data runs record exact inputs, commands and counts without committing transcripts. They use
recorded development replies only, with no model calls or sealed data.

| Slice | Depends on |
|---|---|
| V1. Work identity and programme | Owner's placement ruling |
| V2. Programme binding | V1 |
| FF. FilmFestival programme and source | V2 |
| V4. References | V2 |
| V5. Talk and topic frame | V4 |
| V6. Account | V5 |
| V7. Viewing-order position | V6 |
| V10. Ledger | V6 |
| T1. Timing provenance | Timing amendment |
| T4. FilmFestival timing intake | FF, T1 |
| V11. Pointing rows | V7, V10, T4 |
| V14a. Recorded reply conversion | FF, V11 |
| V14b. Row parity | V14a |

## Acceptance checks

**V1. Work identity and programme.** `core`, `corpus` under the recommended placement.
A programme with one work shown twice constructs. Duplicate positions, overlapping intervals
and undeclared work fail by name. An outside-package compile-refusal probe checks the factory
boundary. Existing corpus constructor spelling, equality and encoded IDs pass compatibility
checks, with any opaque-type linkage change recorded before landing.

**V2. Programme binding.** `align`. On the in-memory source construction path, a legacy
fixture keeps a pinned digest. Binding a programme changes it, as does changing only viewing
order. A segment off the admitted axes is refused. Ambiguous or foreign support cannot supply a
unit's work. The replay is in memory. Source and account wire work waits for a later slice.

**FF. FilmFestival programme and source.** `pipeline`, with `embed-bench` as an existing
adapter reference. Build from admitted run-local annotation axes and the showing order.
A synthetic two-run fixture checks repeated works, annotation-clock identity, position order
and unit-to-showing-to-work traces. Compare real development source metadata against the
harness before converting replies. Use the harness's receipts for annotation/events clock
differences and for positions that include unannotated events.

**V4. References.** `align`. Construct definite moment, work and position forms and a
moment-only alternative. Refuse mixed kinds, duplicate members, invalid weights and a foreign
coordinate. Preserve an unknown remainder, unresolved content and each existing processing
outcome. A whole work has no temporal extent.

**V5. Talk and topic frame.** `recall`, `align`, `codec` and any exhaustive caller in their
reference scope. Exercise the lawful combinations in ADR 0032 §1, including a nested content
expression, unframed evaluation, an unavailable frame and malformed weights. Legacy discourse
tokens round-trip unchanged and retain their aligner behavior. New states require the absence
of a frame or a recorded unavailable frame.

**V6. Account.** `align`. Build a two-pass account with distinct conditions and a bound
pass-1 frame dependency. Refuse foreign recall support, source digest and condition. Check that
the two series draw solely from their own observations, including when the other pass returned
an observation over the same words.

**V7. Viewing-order position.** `align`. Resolve from either end and relative to a preceding
frame. In a known-answer programme with an introduction, an unannotated event and a repeated
work, derive the declared FilmFestival sequence and reproduce its ordered position resolution.
Require an unresolved result for a missing anchor or out-of-range expression.

**V10. Ledger.** `align`. A known-answer fixture tallies one onset per word under both
nesting policies, with a state, nested content, non-addressed talk, both uncovered categories,
a tie and a word without timing. Check each count and require the counts to remain inspectable
on an unavailable row.

**T1. Timing provenance.** `recall`. Pin the digest of an undeclared fixture. A provenance
declaration changes the digest. A foreign inventory, clock or incomplete run partition is
refused by name. Check the segmentation factory with an outside-package compile-refusal probe.
This slice covers declaration and binding. The statistical check follows calibration.

**T4. FilmFestival timing intake.** `corpus-intake`, `pipeline`. Read a synthetic replay CSV
with a pinned utterance companion. Validate one run per original utterance and the exact clock
and word order. A mismatched input digest or inventory fails. Keep the declared timing on the
value the projection actually receives. This replay needs no Sherlock intake change.

**V11. Pointing rows and the run rule.** `align`. One condition per series. Test exact keys and
masses for a single moment across unequal-duration units, a joint moment, a broad moment
within one scene, a multi-scene set, the work fallback, and a work or position with a state.
Test distinct-sample averaging and synthetic overlapping references with equal covered-cell
counts but different character lengths in both reply orders: the equal minimum is unavailable;
a uniquely shorter covered-cell reference is the positive control. Test absent mass on a partial
assignment. Admit bins containing entire runs or excluding them and refuse a bin boundary
strictly inside a run that splits its onsets. Refuse undeclared timing and admit the measured
control. This is an in-memory projection, without wire or scorer work.

**V14a. Recorded reply conversion.** `pipeline`. Convert recorded pass-1 and pass-2 reply
shapes without calling a provider. Synthetic fixtures cover every admitted talk and reference
form, known and missing weights, missing pass-2 replies and every declared replay difference
in ADR 0032. Keep original reply weights and provenance separate from forecast calculations.

**V14b. Row parity.** Recorded local run. Compare on the agreed FilmFestival development
replay and pin input digests, harness revision, bins, allocation and tolerances. Join rows on
participant, session and exact bin. Compare the full sparse key/mass map, row status,
contributing sample identities and comparable ledger counts. Compare new ledger counts to an
independent per-word tally. Report missing, duplicate and extra rows as failures. Compare pass
contributions separately, then reproduce the declared pooled harness rows in the comparator
using the harness sample counts and recorded replay rules. List every strict-path difference
by row and reason and fail on any unclassified difference. Before freeze, record which
differences the harness corrects and which its replay profile retains.

## Later

- Evenness calibration and rounded and quantised guards, then timing wire support and
  timing-qualified held-level projection for Sherlock.
- Transcript-token layer and its recipe contract, when a consumer needs it.
- Source and account wire formats, cross-part selection, measures on the new reference profile,
  and the deferred vocabulary and projections listed in ADR 0032.
- Retirement of aligner external states under a separate decision. See [retirement notes][old].

[vocab]: ../adr/0032-recall-reference-vocabulary.md
[timing]: ../adr/0031-reference-measures-and-held-level-projection.md
[old]: 2026-11-17-externalstate-retirement-notes.md
