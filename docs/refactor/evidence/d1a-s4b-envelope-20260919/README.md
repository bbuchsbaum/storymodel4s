# D1A S4b envelope and text capability — 19 September 2026

Mote: `bd-01M2TAG1GEAGXCY33P0B17QBJ1`. Based on landed S4a `57ee3595`;
the formatted source/test candidate is `9a7caf45829e737e9638a23cd9176f30002a0187`.
Qualification is in progress. Final mutations, restoration, full provider,
documentation and consumer gates are pending; this record does not close S4b.

`StoryModel` now owns its sealed `NarrativeSourceAtlas` and derived bundle.
Its general draft and internal copy check canonical support, membership, direct
primary projection and both receipt identity fields. All node/circumstance support,
ordinary claims and both hierarchy/trajectory boundary-belief stores participate.
Copy rederives both order and projection caches from the resulting graph and bundle.
Status-only promotion retains the unchanged admitted join.

Non-text story identity and the full source checksum bind the full bundle and the
optional bound proposal surface. Courts distinguish changes to primary-axis extent,
timebase and selection, non-primary stream metadata, mapping parameters, caption
bytes, unit structure and receipt association. The primary-only switch holds the
legacy bundle ID and every stream unchanged, so deleting the axis fingerprint is
independently observable at both model identity fields. Text identity preserves the
historical source ID and canonical checksum, including an explicit source ID.

`draftText(surface, ...)` derives source from that surface and returns `TextModel`.
`StoryText` and `TextModel` have private constructors and no public apply, copy,
Product, Mirror or unchecked promotion. Their explicit immutable reads carry text
queries; general models and unbound graphs no longer expose text slicing queries.
`asText` succeeds only for the canonical text atlas, never for a film caption surface.
`TextValidationOutcome` preserves the witness through validation; text adjudication
retains it too. Compiler, codec, materializer, view and alignment text consumers carry
the witness in both inputs and results. The text wire schema remains 0.7.0.

Playback projection selects the complete interval union already on primary while
retaining other native evidence unchanged. It preserves gaps and does not infer a
coordinate conversion from an available mapping. Native-only support and unsupported
annotation primaries receive typed refusals. Playback containment uses the interval
union; text containment retains its historical hull semantics. Text context and
circumstance extent checks remain validation laws rather than construction refusals.

Generic validation retains consistency rules based on graph, status and context.
Text overlap and causal cues require the text witness. The hypothesis-subject rule
remains shared, but its SurfaceExplicit film case cannot currently be constructed:
ClaimMeta requires text spans, and the film join refuses those bare spans. The text
court and film construction-refusal court state that limitation without licensing
captions or inventing a film-side execution.

## Current evidence

The [cold source review](code-review.json) reports no material findings at `844b680c`.
The [first focused court](focused-1.json) passed 35 tests and compiled the downstream
test sources. The [expanded court](focused-2.json) passed 77 tests. After formatting,
the [full JVM story and codec court](focused-3.json) passed 355 tests with formatting
checks. The [last added guard court](focused-4.json) passed 20 tests before applying
its recorded formatter output. These are preparation receipts, not the final gate.
Earlier failed compile attempts remain alongside the passing receipts.

The [mutation inventory](guard-witness-inventory.json) names 54 falsifiers and 24
individual clean test recompiles for compile-time probes. Each named failure must
execute alongside a named passing control. The [runner](qualify.py) retains failed
attempts, refuses existing logs and restores original source after each attempt.
The text canonical-form witness uses otherwise valid anchors from its own bundle,
so foreign membership cannot hide a missing canonical guard. Validator anchor checks
are redundant on publicly constructible models; their source review is not described
as an independently killed deletion mutant.

The [consumer compilation receipt](consumer-compile-1.json) passes production and
test-source compilation at consumer `6a6aa4b`, using provider `844b680c`; it does not
claim test execution. Consumer branch `work/d1a-s4b-consumer-20260919` is preserved in
the real sibling repository. Its current pin commit `08bced0` binds provider `9a7caf45`.
Final consumer qualification will bind all four actual clone revisions separately
from generated Pins.

The frozen S0 JSON is unchanged: SHA-256
`cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3`.
The final provider gate must execute it with the migrated call sites and identical
expected values. Film compilation/encoding, instant support, alignment-source sealing,
caption licensing, scientific qualification, CI execution and remote publication are
outside this S4b record. S4c and D1B retain their distinct later acceptance courts.

## Reproduction

Use a clean standalone clone at `9a7caf45` and grakern at
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Set `D1A_S4B_REPO`,
`D1A_S4B_GRAKERN` and a fresh `D1A_S4B_OUTPUT`, then run
`python3 qualify.py mutations`. After clean restoration, `python3 qualify.py release`
runs `clean compileAll testAll`, both format checks and all executable documentation
examples. Do not run heavy provider and consumer gates concurrently.
