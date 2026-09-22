# Exact recall timing

`RecallTiming` attaches observations to every word in a checked `RecallInventory`.
It is independent of the story, corpus and encoding modality. Its separate
`recall-timing/v0.1` JSON sidecar can accompany a `mapping-record/v0.1` record.
The mapping still owns its discrete semantic alternatives and measure semantics.

Each word has one of three observations:

| Observation | Meaning | Permitted time display |
|---|---|---|
| `Missing(reason)` | No supplied timing for this word | Missing indicator |
| `OnsetOnly(seconds, basis)` | A supplied instant | Point |
| `Interval(start, endExclusive, basis)` | A supplied half-open interval | Interval |

`SourceReported(evidence)` attributes a value to source evidence;
`Estimated(recipe)` identifies its estimator. These are declarations, not
calibration or observation-authority certificates. A clock binds the timing
artifact, selector key, descriptor, declared recording identity and time origin.
Unknown identity/origin and an unestablished recording link are explicit. Two
identical numbers on different clocks cannot enter the same checked timing object.

Negative seconds are allowed. An interval declares the temporal extent of a word;
it is not an uncertainty bound on its onset. Intervals must have strictly positive
exact width.
Overlapping intervals and nonmonotonic onsets are retained. Missing values never
become zero. Source records can include words that are unassigned to any unit;
they remain in the complete word table.

## Scala use

Given a checked `inventory`, an explicitly declared `clock`, provenance and one
observation per inventory word:

```scala
import storymodel4s.recall.RecallTiming
import storymodel4s.codec.RecallTimingCodecs

val checked = RecallTiming.checked(inventory, clock, entries, provenance)
val encoded = checked.map(RecallTimingCodecs.encode)
val reread = encoded.map(text => RecallTimingCodecs.decode(text, inventory))
```

The example keeps the construction and decoding refusals in nested `Either`s.
Applications can handle them separately or widen their error type. Decoding requires the original checked
inventory. A matching digest alone is not sufficient context.

`RecallTiming.decimalSeconds("8.75")` yields exactly `35/4`. It accepts the numeric
token grammar of the independent Python intake, including `.1`, `1.` and signed
exponents. The CSV adapter must first handle blank cells as missing and strip
cell whitespace, as the existing intake does. The numeric-token parser itself
does neither. Intake is bounded to 512 characters, 128 coefficient digits and
absolute exponent 128; reduced numerator/denominator must fit the core Long
representation. Unrepresentable values produce a typed refusal, never rounding.

## Interchange and diagnostics

The sidecar carries an inventory digest, full clock declaration, correspondence
provenance, one ordered entry per word, derived `unit_onset_diagnostics`, and a
content digest. Rational components are canonical decimal **strings**, including
values beyond JavaScript's exact integer range. Consumers use BigInt or an exact
rational implementation; converting these strings to JavaScript Number loses the
contract. Unknown/null fields, duplicate object keys, foreign/missing/duplicate
words, numeric rational components, stale digests and forged diagnostics refuse.

Unit diagnostics distinguish the first/last member from the first/last available
onset witness. They count available onsets, supplied intervals, estimated words,
and observed/unobserved adjacent pairs; they retain backward pairs and equal
onsets. Adjacent means adjacent member words in transcript order. An unobserved
pair does not become a direct transition across a gap. A wordless unit has no
boundary witnesses. No field claims unit duration, temporal union coverage,
interpolation or recall exposure.

StoryAtlas can use these diagnostics to display points, supplied intervals,
missingness and ordering anomalies consistently with Python/R. It must not draw
a filled time band between onset-only points and label it observed coverage.
Continuous recall-to-encoding queries and uncertainty maps still require the
separately planned temporal allocation/exposure contract. This sidecar establishes
neither an fMRI volume clock nor the Sherlock cartoon/episode offset.

## Reproducing the synthetic evidence

From a standalone checkout (sbt does not support this repository's linked worktrees):

```sh
sbt -batch 'recallJVM/testOnly *RecallTiming*' 'codecJVM/testOnly *RecallTiming*'
sbt -batch 'codecJVM/Test/runMain storymodel4s.codec.RecallTimingArtifacts /tmp/recall-timing'
python3 tools/recall-study/check_recall_timing.py --artifacts /tmp/recall-timing
```

The Python check compares Scala output with the already-qualified independent
word-clock fixture and Python `Fraction` arithmetic, including decimal intake
acceptance vectors. It is a synthetic qualification check, not a general decoder
or a production importer. JVM, Scala.js and Native run the shared Scala tests.
No participant data or provider call is needed.

The [qualification record](refactor/evidence/recall-timing-portable-20260922/README.md)
contains exact source pins, portable test totals, mutation controls and examples.


For byte-pinned CSV inputs and an offline job command, see
[production intake and source support](recall-timing-intake.md). It writes this
same canonical sidecar, with complete record-to-word receipts. The accompanying
support query selects exact part/occurrence geometry; neither operation supplies
scanner linkage or allocates mapping mass in time.
