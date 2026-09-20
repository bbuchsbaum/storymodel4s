# D1A S2 checked source substrate — 19 September 2026

Mote: `bd-01M2TABVXSM72M9DD0SNB6GZ11`. Final source/test candidate:
`c24ddd66026f0d1848b61369b0bbaff7a90a5d40`, based on landed S1 `75c8df52`.
Final provider, formatting, documentation and consumer qualification is in progress;
this working receipt does not yet authorize S2 closure.

## Implemented boundary

The sealed `NarrativeSourceAtlas` now has checked text and anchored implementations.
`BoundProposalSurface` binds derived surface bytes and unit structure to a supplied
receipt association. `AnchoredNarrativeAtlas` admits only edition-playback primaries,
rejects duplicate unit IDs and sentence references, and rechecks support against its
bundle. Lookup is map-backed; the original `SurfaceAtlasConformance.bundleOf` remains
available at its original public path.

`EvidenceSupport.of` checks bundle, stream, kind, axis and extent together. Native
support belongs to its selected stream. Primary support requires one distinct full
mapping identity and complete coverage by that mapping's image. Clock arithmetic is
exact; composition gaps stay gaps. Text spans are selected per stream, and playback
intervals merge overlaps and adjacency without filling gaps. `TypedSupport`,
`PrimaryProjection` and the optional `Evidence.anchors` carrier are additive. The
unusable `LegacyAudioBinding.toMediaSupport` adapter is removed.

New bundle, mapping and surface identities bind their full declared payloads, including
safe receipt binding. Historical receipt identity is unchanged: the old NUL-delimited
collision pair is an explicit counterexample, while the new binding distinguishes it.
A proposal receipt association is not independent attestation of generated output.

The `evidence-support/v1` encoder preserves all four anchor payloads and decimal-string
ticks. Its component decoder and the 0.7.0 evidence decoder explicitly refuse anchored
wire input. Ordinary text evidence continues to omit the absent field. Temporary
text-model and compiler guards prevent anchors entering a 0.7.0 story export before
the later envelope/text-witness migration. These guards include boundary-belief
evidence, the compiler evidence ledger and all inline proposal evidence joins.

## Acceptance witnesses

| Requirement | Executed witness or record |
|---|---|
| Pre-code design review | [Design review](design-review.json): revision 4 findings folded into revision 5 and rechecked at `62d225b1`, before implementation |
| Checked atlas and proposal construction | `D1aAtlasSuite`; external `D1aConstructionBoundarySuite`; duplicate-ID, missing/foreign/nonsentence surface and foreign-support mutants |
| Support refusals | `D1aSupportSuite` and published `SourceLawsSuite`: bundle/stream/kind/axis/extent, mapping ambiguity, image coverage, gaps and source bounds |
| Per-stream spans and canonical intervals | Source laws for order independence/idempotence; per-stream, overlap, adjacency and three independent axis-selector mutants |
| Full identities and surface association | Same relation ID with changed scale/offset/composition/receipt, primary/stream extent changes, legacy receipt collision and independent surface-field mutations |
| Codec boundary | `EvidenceSupportCodecSuite`: four payload pins and encode/refuse courts, one payload-deletion mutant per anchor; two separate decoder-refusal mutants |
| Construction doors remain shut | Three private-class to case-class mutants and the unsealing mutant, each after its own `coreJVM/clean` and executed rejecting test; positive consumer construction control retained |
| Temporary text-only joins | `D1aTextBoundarySuite` and `CompilerSuite`: ordinary claims, boundary beliefs, ledger and inline evidence guards, each with a compiled mutation and text accepting control |
| Existing text behavior | Unchanged S0 expectations and model codec pin run in the full provider gate; consumer suite uses the exact provider candidate |

The [settled guard inventory](guard-witness-inventory.json) names all **52** planned
mutants. [Mutation receipts](mutations.json) bind their original revisions, source and
mutant hashes, commands, logs, named rejecting tests and accepting controls. All 52
compiled and were killed; all named controls passed. The
[final clean restoration](restored-control-final.json) passes **73 tests** across the
six focused suites. Source is restored and the isolated clone is clean.

The [separate SD6 review](code-review.json) and repair rechecks have no remaining
findings. The independent [original mutation audit](mutation-receipt-audit.json) and
[settled 52 audit](settled52-audit.json) check evidence against actual source revisions
and retained logs. Fifteen early witnesses have no retained per-run JUnit: the audit
uses the exact 19 unconditionally registered test bodies, complete named failure set
and 19 executed/zero-ignored log totals to establish the passing control complement.
Later witnesses retain JUnit hashes and named outcomes directly.

## Failed attempts and unchanged evidence

[Failed attempts](failed-attempts.json) retain the compile-failing atlas mutant, two
surviving mutants and stale incremental macro restoration. A surviving surface-ID
mutation exposed an order-changing fixture: `3cd484d6` repairs that witness and adds
an independent end-bound mutation. A surviving interval-selector mutation exposed a
secondary mixed-axis refusal: `c24ddd66` adds singleton controls for MediaTime, Shot
and Track. Each repaired witness was rerun and cold-reviewed. Failed compilation is
not counted as a killed mutant, and incremental stale output is not a clean gate.

[Original carry-forward](witness-carry-forward.json) and
[test-append carry-forward](test-append-carry-forward.json) record unchanged production
blobs and exact prior test bodies. Historical witnesses retain their historical
revisions and counts; they are not described as reruns on the final candidate.
[Superseded atlas witnesses](superseded-atlas-witnesses.json) retain the earlier court.
The earlier `3cd484d6` full run (6,602 executions) is historical; final qualification
is refreshed on `c24ddd66` because its test sources changed.

The frozen S0 JSON SHA-256 remains
`cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3`.
No expected values, model schema, build definitions, inference parameters or gold
data changed. The unused HSMM golden checksum is not counted as a byte pin.

## Reproduction

[qualify.py](qualify.py) contains the exact mutation edits and release commands.
Create a clean standalone clone at `c24ddd66` and a grakern clone at
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Set `D1A_S2_REPO`,
`D1A_S2_GRAKERN` and `D1A_S2_OUTPUT` to their absolute paths and a fresh output
directory. Run `python3 qualify.py mutations`, followed by
`python3 qualify.py release`. The mutation command needs roughly 52 sbt starts;
the four construction probes intentionally recompile cleanly. The release command
runs the reference-scope tool, `clean compileAll testAll`, separate
`scalafmtCheckAll scalafmtSbtCheck`, and all 13 documentation examples. Use no
concurrent heavy build. Existing output logs are refused rather than overwritten.

For the consumer, create sibling standalone clones named `storyatlas4s`,
`storymodel4s`, `intaglio` and `grakern` under `D1A_S2_CONSUMER_ROOT`. Use provider
`c24ddd66`, consumer `fb33bef7d0971530531fcec43cd3d7f141b8e137`, intaglio
`4eb566d9208f474d64d61e778e084dee2ddbaa76`, and the grakern pin above. Set a fresh
`D1A_S2_OUTPUT`, then run [consumer-gate.py](consumer-gate.py). It binds all four
actual revisions and clean states, the complete command, fresh JUnit totals for
every consumer task, and the output log hash. The consumer's generated pin display
still renders its literal build pin; the receipt binds the actual provider override.
The [pre-S2 consumer baseline](consumer-baseline.json) is separate historical evidence.

Raw logs and JUnit remain under `data/study/d1a-s2-20260919/` and its `final/`
directory; committed receipts identify each exact path/hash. No external provider
call is needed for these courts.

## Limits and next slice

This qualifies the source substrate locally. It does not establish executed CI,
remote publication, film compilation, caption licensing, numerical calibration or
scientific validity. Annotation-timeline primaries are refused by this anchored
atlas. Point-capable evidence/projection remains required before D1B can carry
Sherlock's admitted instant at row 13; no duration is fabricated here. Existing
annotation preview APIs remain available.

S3 next carries typed support through acquisition and preserves text resolver
verdicts and wire-visible gap names. S4a–S4c then migrate nodes, the model envelope,
text witnesses and alignment consumers. The film-status owner decision and the
independent Native numerical policy remain open at their declared boundaries.
