# NFRD Baseball diagnostic-court intake

**Bead:** `bd-01M17MT2JC0TS46BH9RTYMEVQ7`
**Status:** implemented candidate under the chief-approved case-derived contract
**Depends on for intake:** nothing in the narrative compiler
**Depends on for a scientific result:** the real proposition provider and an honestly licensed
discourse trajectory

This document turns the verified facts in
[`nfrd-primary-source-inventory.md`](nfrd-primary-source-inventory.md) into the smallest intake
court that can freeze a real NFRD story and its recall cohort without putting participant language
in git. It is deliberately about **Baseball in NFRD**. It does not declare a general corpus schema.

The court answers one question:

> Are these exactly the external Baseball source and recall artifacts that were inventoried, with
> their upstream transformations and participant partition fixed before modeling?

Passing that court produces an input receipt. It does not produce a valid narrative model, an
alignment, or a scientific result.

## Why this is separate from the compiler

The two paths should advance independently:

```text
external NFRD snapshot                 arbitrary-text extraction
  -> intake verification                 -> proposition proposals
  -> lineage receipt                     -> resolved narrative model
  -> frozen participant split            -> licensed trajectory
             \                           /
              +---- first Diagnostic ---+
                    Baseball court
```

The intake must not hand-author a Baseball graph to bypass extraction. Conversely, the proposition
provider must not need public-dataset acquisition in order to prove that it handles arbitrary
text. The paths meet only when both have independent evidence.

## Initial ownership seam

The first implementation should live in the existing JVM-only `embed-bench` project under
`storymodel4s.bench.nfrd`:

- it is an evaluation input, not a portable story identity;
- local filesystem access is JVM-only and is forbidden in `core` and the other portable modules;
- `embed-bench` already owns `Origin.Diagnostic`, frozen manifests, partition discipline, and
  fail-closed content verification;
- no new sbt module or dependency is needed;
- all initial vocabulary can remain `private[bench]` until a second usable corpus demonstrates
  which parts are genuinely general.

This is the **first home, not the promised final home**. If NFRD becomes a primary corpus rather
than a bounded diagnostic, acquisition, storage policy, and reusable intake contracts will outgrow
an evaluation harness. That later move must be driven by the working court rather than anticipated
with a new module now.

The existing bead `bd-01M17010MM68DG7B49DMEKA2AA` still owns the eventual general admitted-story
loader. The NFRD implementation supplies a measured case for that design; it does not silently
subsume it.

## External snapshot boundary

Raw source and participant files stay outside the repository. The caller supplies one explicit
root directory. There is no default search through the checkout, home directory, or environment.

The external root contains:

1. the three released Baseball stimulus artifacts: transcript, TextGrid, and audio;
2. 113 cleaned recall transcripts;
3. the corresponding 113 recall TextGrids;
4. two locally saved metadata manifests, one for recall transcripts and one for recall TextGrids.

The court uses the OSF materialized paths, plus a local `metadata/` directory:

```text
metadata/baseball-recall-transcripts.tsv
metadata/baseball-recall-textgrids.tsv
experiment_materials/stimuli/baseball_transcript.txt
experiment_materials/stimuli/baseball_aligned.TextGrid
experiment_materials/stimuli/audio/baseball_audio.mp3
data/recall_transcripts/baseball/P<three digits>_baseball.txt
data/recall_aligned/baseball/P<three digits>_baseball.TextGrid
```

Each metadata-manifest line has the already-inventoried canonical form:

```text
filename<TAB>byte-size<TAB>osf-sha256<LF>
```

Lines are UTF-8, sorted bytewise by filename, contain no header, and end with `LF`. The repository
pins only the two aggregate manifest digests and expected counts:

| External manifest | Expected count | Expected SHA-256 |
|---|---:|---|
| Baseball recall transcripts | 113 | `d5cf72396b06d54930c72644d1f7d0afb5b199f2bf6c42e42f651039e70d58b4` |
| Baseball recall TextGrids | 113 | `987a126f15d6afa89357da422ea65ce62238ffadbbcf9e96967dd554f52b60f4` |

Participant identifiers and per-participant hashes therefore remain in the external snapshot. A
verified receipt may publish cohort counts and aggregate digests, but not the external manifest or
participant text by default.

This is an explicit privacy-versus-forensics choice. An aggregate digest proves that a folder
snapshot is or is not the pinned snapshot, but it cannot say which participant file changed.
Committing the per-file manifest would improve forensic localization while publishing the released
participant structure and stable file identities. The first court chooses lower exposure; a failed
aggregate check is diagnosed against the external manifest in the controlled local environment.

The stimulus-side fixed checks are:

| Artifact | Expected bytes | Expected SHA-256 |
|---|---:|---|
| released transcript | 11,237 | `030810edbcd4bc159f098a8ab8b778a8e1914992174911e464c11ff1a686a900` |
| released TextGrid | 319,931 | `c0dd0503e5d787e34f937f6bfc24c97f346a928a8165ecee4b956d5b940a2db5` |
| released audio | 12,289,426 | `ab4875527694890c1372d8333a8b5a20cf558dffc7617e6352efe328322ba791` |

The source transcript must additionally have 2,187 tokens and lexical-stream checksum
`5a5f76e3b48361752ba260e599b28adf98ef0a9346a4f45bd361356dc36e9643` under the declared
comparison: lowercase, take maximal Unicode letter-or-digit runs, discard empty matches, and join
tokens with `LF` without a trailing delimiter. This is a provenance court against Project
Gutenberg chapter I, not the coordinate system used by `StorySource`; exact source spans remain
anchored to the released transcript bytes and `StorySource.canonicalText`.

Applying the current `StorySource.canonicalize` contract removes the released file's one terminal
newline and produces 11,236 canonical UTF-16 code units, canonical checksum
`b142dd642779ae96bf7483b49b29c25bb4b6a577998a95bb4ed76dc43e010b1e`, and deterministic story ID
`story:86498fdb3920`. Node and Ruby independently reproduced the canonical checksum from the exact
Scala algorithm. The production court must recompute these through `StorySource.fromText`; it must
not trust the documented values in place of the library operation.

## Case-specific typed contract

The implementation types below remain package-private; they are not accepted public API.

```scala
private[bench] enum IntakeClass:
  case Observation
  case Derivation
  case Unknown

private[bench] enum Reversibility:
  case Reversible
  case Partial
  case Irreversible
  case Unknown
  case NotApplicable

private[bench] enum BaseballField:
  case ReleasedParticipantId
  case ReleasedCohortMembership
  case StoryAssignment
  case PresentationOrder
  case UpstreamTechnicalLoss
  case StimulusTranscript
  case StimulusAudio
  case StimulusWordTiming
  case RecallSpeech
  case CleanedRecallText
  case RecallWordTiming
  case RecallNoiseLabels

private[bench] final class FieldLineage private[nfrd] (
  val field: BaseballField,
  val intakeClass: IntakeClass,
  val reversibility: Reversibility,
  val procedure: String,
  val evidenceRef: String
)

private[bench] enum ParticipantPartition:
  case Development
  case Calibration
  case UntouchedTest
```

The production ledger is fixed and complete over `BaseballField.values`. In particular:

- participant speech is the unavailable Observation;
- cleaned recall text is an irreversible Derivation;
- word and noise timing are Derivations whose replay is impossible without the withheld audio;
- released cohort membership is a Derivation of exclusion and transcription selection;
- upstream technical-loss status is Unknown;
- story assignment and presentation order are Observations in the workbook, but are Unknown to the
  minimized first bundle because the survey workbook is deliberately excluded.

Unknown is a value, not an omitted row. A ledger missing a field cannot produce a verified
snapshot.

Artifact identity is also typed rather than inferred from arbitrary paths:

```scala
private[bench] enum ArtifactLabel:
  case TranscriptManifest
  case TextGridManifest
  case StimulusTranscript
  case StimulusTextGrid
  case StimulusAudio
  case RecallTranscript(participant: ParticipantKey)
  case RecallTextGrid(participant: ParticipantKey)
```

The filename grammar extracts the released participant token `P<three digits>` from both artifact
kinds. `ParticipantKey` is then

```text
SHA-256("nfrd/oregontrail-baseball/participant/v1" NUL
        transcript-manifest-digest NUL
        released-participant-token)
```

It is a stable project-local join key, not a claim of anonymization. The released participant token
never appears in `toString`, an error message, or the public receipt.

## Verification pipeline

The filesystem adapter performs reads; a pure verifier decides whether the bytes are admissible.
The verifier is injected with a total read function, following `FrozenSet.verify`:

```scala
private[bench] object NfrdBaseballVerifier:
  def verify(
    spec: NfrdBaseballSpec,
    read: RelativeArtifactPath => Either[NfrdIntakeError, Array[Byte]]
  ): Either[NfrdIntakeError, VerifiedNfrdBaseball]
```

The production `NfrdBaseballSpec` is a library constant containing only non-participant snapshot
facts and aggregate digests. Tests can construct a package-private synthetic spec; there is no
public unchecked constructor.

Verification is ordered and fail-closed:

1. Validate every path before reading: relative, normalized, no empty segment, no `.` or `..`, no
   platform root, and no duplicate after normalization.
2. Read each external metadata manifest and reproduce its pinned aggregate checksum.
3. Parse exactly three tab-separated fields per line; validate decimal byte size and lowercase
   SHA-256; reject blank, duplicate, unsorted, extra, or malformed lines.
4. Require exactly 113 entries in each participant manifest.
5. Derive participant stems using the declared NFRD filename grammar. Require a one-to-one equality
   between transcript and TextGrid stem sets. Never pair by row position.
6. Read every listed participant artifact one at a time. Match both declared byte size and SHA-256;
   do not accumulate the cohort's bytes in memory.
7. Read and match the three fixed stimulus artifacts.
8. Decode the verified stimulus transcript as strict UTF-8 with malformed and unmappable input
   reported, never replacement-decoded. Construct `StorySource` and record both its raw and
   canonical checksums. Check the declared 2,187-token lexical fingerprint.
9. Verify that the lineage ledger is complete over every required field.
10. Derive and verify the frozen participant partition described below.

The lineage ledger is itself NUL-framed in `BaseballField` ordinal order under
`nfrd/baseball/lineage/v1`. Node and Ruby independently reproduce the production receipt:

```text
bcfca613502ec24740e0746d6ff12eeb2e2ad45bf3542a6d14d2242bf80ba1f3
```

The filesystem adapter additionally resolves the external root and candidate file with
`toRealPath`, refusing a symlink whose real target escapes the real root. Missing files, unreadable
files, and I/O failures are typed errors. No fallback path, download, repair, or partial snapshot is
attempted during verification.

## Participant partition freeze

Partitioning happens after identities and pairs are verified but before any participant transcript
is parsed into a storymodel object or sent to a provider.

For each released participant token, compute the rank key:

```text
SHA-256("nfrd/oregontrail-baseball/participant-split/v1" NUL
        transcript-manifest-digest NUL
        released-participant-token)
```

The algorithm has **no random seed**. `seed = none` is part of the receipt. The public domain
label, pinned transcript-manifest digest, exact NUL framing, SHA-256, lexical hexadecimal ordering,
participant-token tie-break, and 57/28/28 boundaries completely determine the result. A third party
holding the external manifest can regenerate it without project-private state.

Sort by rank key, with the released participant token as a deterministic collision tie-breaker,
then assign:

- first 57: Development;
- next 28: Calibration;
- final 28: UntouchedTest.

The split receipt is `ContentAddress.digest` over the domain label, transcript-manifest digest, and
the rank-ordered strings `<ParticipantKey>=<partition-label>`. Node and Ruby implementations
independently reproduced the resulting checksum:

```text
45d879a8c201d8b4424ecf208969d4339295f2a48394248ff130a94d48f5ebd9
```

That checksum enters the production spec before any model work begins. Changing the salt, hash
input, ordering, boundaries, participant set, or partition labels must fail a regression court
rather than silently create a new experiment.

The domain is the **Oregontrail/Baseball fixed-pair cohort**, not only Baseball. Those stories have
the same 113 released participant IDs. If Oregontrail later enters a development or evaluation
run, it must reuse this participant partition after proving exact ID-set equality; independently
rehashing the same people by story would leak participants across partitions.

The first one-story result remains `Origin.Diagnostic` regardless of the internal participant
partition. `UntouchedTest` means untouched within this diagnostic protocol; it does not make a
single-story corpus calibrated or generalizable.

## Verified output and disclosure boundary

`VerifiedNfrdBaseball` is a non-case class with a private constructor. It exposes:

- the verified `StorySource` and its exact source receipt;
- 113 participant keys and their frozen partitions;
- external artifact references and checksums, but not raw participant bytes;
- the complete lineage-ledger checksum;
- transcript/TextGrid manifest digests and participant-pair count;
- a content checksum over the complete verification result.

The complete input receipt binds the source title, language, metadata, lexical court, canonical
UTF-16 length, stimulus TextGrid, stimulus audio, both participant manifests, pair count, frozen
partition, and lineage ledger. A checked artifact that does not enter this address would be an
unauditable side condition, so the receipt court changes if any one of those inputs changes.

The first slice does not expose participant text at all. Any later parser must use an explicit
callback scoped to one verified participant rather than retaining the cohort's transcript byte
arrays. The verified snapshot's `toString` and receipt renderer contain counts and checksums only.

The court must never print:

- participant file stems;
- participant text or text fragments;
- TextGrid labels;
- questionnaire values;
- absolute local paths.

Errors identify an artifact by `ParticipantKey` and artifact kind. They may report expected and
actual sizes/checksums; they may not include content previews.

## Adversarial courts

The initial tests must distinguish the intended contract from plausible weaker implementations:

1. one changed participant byte fails checksum verification;
2. a declared size that disagrees with otherwise matching bytes fails;
3. one missing TextGrid fails pair equality;
4. pairing transcripts and TextGrids by row order is killed by independently permuting one
   manifest;
5. a duplicate participant/path fails before file reads;
6. absolute paths, `..`, normalization collisions, and a symlink escape fail;
7. a malformed or unsorted manifest fails even if its lines describe valid files;
8. an empty artifact fails even when a synthetic manifest records the empty-file checksum;
9. a lineage ledger missing `UpstreamTechnicalLoss`, or relabelling it Observation, cannot verify;
10. changing the split salt, count boundary, or ordering fails the pinned partition checksum;
11. diagnostics and errors reveal no participant stem or transcript substring;
12. no partial `VerifiedNfrdBaseball` can be forged with `copy`, `fromProduct`, reflection-free
    public construction, or a public unchecked factory.
13. malformed UTF-8 in a checksum-valid synthetic transcript fails rather than entering
    `StorySource` with replacement characters.

Unit tests use synthetic, non-participant text. The real-data court is an explicit command that
requires an external root and fails when it is absent; an absent environment variable must never
turn the court into a green skipped test.

## Acceptance evidence

This bead is ready for chief review when it provides:

- package-private verifier and filesystem adapter in `embed-bench`;
- synthetic adversarial unit tests covering every court above;
- an explicit real-data command whose output is a non-disclosing receipt;
- a successful run against a freshly acquired external NFRD Baseball snapshot;
- exact source, pair-count, manifest, lineage, split, and result checksums recorded on the board;
- `scalafmtCheckAll`, `embedBench/compile`, and `embedBench/test` on the candidate;
- a static construction-boundary review and at least the checksum, pairing, lineage, and split
  mutations shown to be killed.

The candidate does **not** claim the first defensible number. That subsequent bead depends on the
real proposition provider, the trajectory licensing decision, a declared estimand, and the frozen
participant court established here.

## Recorded chief architecture decisions

On 2026-08-29 the chief approved all five requested boundaries:

1. `embed-bench` is the initial home, with no new module or dependency; it is explicitly not the
   promised final home if NFRD grows.
2. NFRD-specific vocabulary remains package-private rather than becoming a public general corpus
   API.
3. Git receives aggregate manifest digests only; participant IDs, per-file hashes, and bytes stay
   external. This deliberately favors disclosure minimization over per-file repository forensics.
4. The deterministic, seedless 57/28/28 split is frozen before transcript parsing and remains
   Diagnostic.
5. The general admitted-story-loader bead remains separate and may extract from this case only
   after the intake court succeeds.
