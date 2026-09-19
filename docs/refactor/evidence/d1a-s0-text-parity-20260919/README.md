# D1A S0 text parity — 19 September 2026

Mote: `bd-01M2TAB3QD2YXSPR8THYRGFBKS`. Qualified revision: `051f88f3fc94e72ed484b672937c351cafe3618e`.
The [clean standalone-clone gate](full-gate.json) ran `sbt -batch clean compileAll testAll`,
exit 0: **6,444 passed executions, 5 skipped, zero failures or errors**.
All 56 test tasks have totals bound to their position in the committed `testAll` alias.
The tree was clean before and after. [Formatting](format-last.json) ran separately afterward;
[reference scope](scope.json) required pipeline, and the full gate covered every module.
This is local qualification; no executed CI or remote publication is claimed.

[TextParitySuite](../../../../pipeline/src/test/scala/storymodel4s/pipeline/TextParitySuite.scala)
now checks the [frozen values](../../../../pipeline/src/test/resources/golden/d1a-s0-text-parity.json)
from production baseline `27ebdb71c33fcdccd97f109b46e30006f3fbcce8`. The [baseline receipt](baseline.json) verifies that
no production source, build definition, dependency or schema changed. All eight S0 tests pass
in the clean gate, including a full replay through the production writer.

The pins cover the WOG compilation fingerprint, candidate-set identity, canonical model
checksum and exact model/derivation bytes; source-support/v2, evidence/v2, mention ID and
text-support wire exemplars; complete node-order lists; the draft Atlas textual twin; and
the separate historical parser/build receipts. The
[golden notes](../../../../pipeline/src/test/resources/golden/d1a-s0-text-parity.md) declare
the inputs, selectors, fixed time, view configuration and update rule. The captured replay
stays partial: 65 situations, 23 entities, 6 contexts, 1 segment, 84 gaps, 3 violations.
The historical wog-record-1 directory still contains exactly two files, neither a model nor
a derivation artifact. Its receipt pins do not claim recovery of missing output bytes.

The [mutation receipt](mutations.json) records five compiled falsifiers for the four required
changes. Expected pins were never modified:

| Mutation | Failed / passed S0 tests | Named witness |
|---|---|---|
| Captured alignment token 5 → 6, changing its source span | 3 / 5 | compile pin |
| Evidence encoder adds anchors: null | 3 / 5 | exemplar pin |
| Bare text support becomes a tagged Text wrapper | 6 / 2 | compile/decode contract |
| Source-support/v2 renders every span offset one unit later | 2 / 6 | exemplar pin |
| Actual serializer emits anchors: null bytes | 2 / 6 | text wire omission |

Each mutant leaves a named accepting control green. Fresh JUnit reports establish that the
mutant compiled and the named test failed; compile errors do not count. Original bytes were
restored after each mutation. The final restored control passes all eight tests. The extra
wire mutation matters because canonical printing drops null values: an encoder JSON-value
witness alone is not an emitted-byte witness. Four mutations ran at `80f80ca2`; the fifth
and the full gate ran at the qualified revision. Their only intervening changes were the
two documentation corrections listed in the [cold review](review.json).

Cold review confirmed the production-path coverage and corrected the description of the
four test-computed support-order reference lists. The later D1A production projection API
must consume those unchanged expected lists. Only the situation discourse-order list
currently observes a production ordering API. The private rendering adapter is JVM-only;
renaming the private methods needs an explicit adapter update, not regenerated golden values.

Existing cross-platform **model encoding** parity is supplied by the literal checksum test
in `codec/StoryModelCodecSuite`, exercised here on JVM, JS and Native. The WOG HSMM suite
currently checks canonical structure and contextual round trips, without asserting its
checksum constant. That limitation was noted on the existing Native numerical-policy ticket
`bd-01M1D215EY4T5BR0VRJ694AMBQ`; it does not enlarge S0.

These are compatibility pins, not a scientific-correctness or behavioral-recovery result.
Compilation parity on JS/Native and browser-rendering parity are not claimed. S1's ADR
amendment is the next D1A dependency; G0 still has separate infrastructure and preservation
work. Raw logs and [owned runner identities](local-runners.json) remain in the ignored
`data/study/d1a-s0-20260919/` directory.
