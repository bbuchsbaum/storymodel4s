package storymodel4s.bench.nfrd

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

import storymodel4s.bench.Origin
import storymodel4s.core.{Checksum, ContentAddress, StoryId, StorySource, TextNorm}
import storymodel4s.corpus.{ReadOperation, RelativeArtifactPath, RootIssue}

/** Whether a released field is observed, derived, or unresolved at corpus intake. */
private[bench] enum IntakeClass:
  case Observation, Derivation, Unknown

  def render: String = productPrefix.toLowerCase

/** Whether a documented transform can recover its input from the released value. */
private[bench] enum Reversibility:
  case Reversible, Partial, Irreversible, Unknown, NotApplicable

  def render: String = this match
    case Reversible    => "reversible"
    case Partial       => "partial"
    case Irreversible  => "irreversible"
    case Unknown       => "unknown"
    case NotApplicable => "not-applicable"

/** The complete field families the minimized NFRD Baseball intake must classify. */
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

  def render: String = productPrefix

/** One typed corpus-lineage assertion; text explains the procedure, never its class. */
private[bench] final class FieldLineage private[nfrd] (
    val field: BaseballField,
    val intakeClass: IntakeClass,
    val reversibility: Reversibility,
    val procedure: String,
    val evidenceRef: String
):
  private[nfrd] def canonicalParts: Vector[String] =
    Vector(field.render, intakeClass.render, reversibility.render, procedure, evidenceRef)

  override def equals(other: Any): Boolean = other match
    case that: FieldLineage => canonicalParts == that.canonicalParts
    case _                  => false

  override def hashCode(): Int = canonicalParts.hashCode()

  override def toString: String =
    s"FieldLineage(${field.render},${intakeClass.render},${reversibility.render})"

private[nfrd] object FieldLineage:
  def of(
      field: BaseballField,
      intakeClass: IntakeClass,
      reversibility: Reversibility,
      procedure: String,
      evidenceRef: String
  ): Either[NfrdIntakeError, FieldLineage] =
    def usable(value: String): Boolean = value.trim.nonEmpty && !value.contains(0.toChar)
    if !usable(procedure) then Left(NfrdIntakeError.InvalidLineageText(field, "procedure"))
    else if !usable(evidenceRef) then Left(NfrdIntakeError.InvalidLineageText(field, "evidence"))
    else Right(new FieldLineage(field, intakeClass, reversibility, procedure, evidenceRef))

/** A partition frozen before any NFRD recall transcript is parsed or modeled. */
private[bench] enum ParticipantPartition:
  case Development, Calibration, UntouchedTest

  def render: String = this match
    case Development   => "development"
    case Calibration   => "calibration"
    case UntouchedTest => "untouched-test"

/** A stable project-local join key that never renders the released participant token. */
private[bench] final class ParticipantKey private[nfrd] (val checksum: Checksum):
  def render: String = s"participant:${checksum.short()}"

  override def equals(other: Any): Boolean = other match
    case that: ParticipantKey => checksum == that.checksum
    case _                    => false

  override def hashCode(): Int = checksum.hashCode()
  override def toString: String = render

/** A non-disclosing label for one external read. */
private[bench] enum ArtifactLabel:
  case TranscriptManifest
  case TextGridManifest
  case StimulusTranscript
  case StimulusTextGrid
  case StimulusAudio
  case RecallTranscript(participant: ParticipantKey)
  case RecallTextGrid(participant: ParticipantKey)

  def render: String = this match
    case TranscriptManifest   => "recall-transcript-manifest"
    case TextGridManifest     => "recall-textgrid-manifest"
    case StimulusTranscript   => "stimulus-transcript"
    case StimulusTextGrid     => "stimulus-textgrid"
    case StimulusAudio        => "stimulus-audio"
    case RecallTranscript(id) => s"recall-transcript:${id.checksum.short()}"
    case RecallTextGrid(id)   => s"recall-textgrid:${id.checksum.short()}"

/** The only value a reader receives: a safe label plus a checked relative path. */
private[bench] final class ArtifactRequest private[nfrd] (
    val label: ArtifactLabel,
    val path: RelativeArtifactPath
):
  override def toString: String = s"ArtifactRequest(${label.render})"

private[nfrd] object ArtifactRequest:
  def apply(label: ArtifactLabel, path: RelativeArtifactPath): ArtifactRequest =
    new ArtifactRequest(label, path)

/** Every refusal of the NFRD intake court, without participant text, IDs, or absolute paths. */
private[bench] enum NfrdIntakeError:
  case UnsafeRelativePath(label: String)
  case RootUnavailable(issue: RootIssue)
  case MissingArtifact(label: ArtifactLabel)
  case NotRegularFile(label: ArtifactLabel)
  case PathEscapesRoot(label: ArtifactLabel)
  case ReadFailed(label: ArtifactLabel, operation: ReadOperation)
  case ManifestChecksumMismatch(
      label: ArtifactLabel,
      expected: Checksum,
      actual: Checksum
  )
  case ManifestEncoding(label: ArtifactLabel)
  case ManifestFormat(label: ArtifactLabel, line: Int, reason: String)
  case ManifestCount(label: ArtifactLabel, expected: Int, actual: Int)
  case ParticipantPairMismatch(transcriptOnly: Int, textGridOnly: Int)
  case SizeMismatch(label: ArtifactLabel, expected: Long, actual: Long)
  case ChecksumMismatch(label: ArtifactLabel, expected: Checksum, actual: Checksum)
  case SourceEncoding
  case SourceConstructionFailed
  case SourceTokenCount(expected: Int, actual: Int)
  case SourceLexicalChecksum(expected: Checksum, actual: Checksum)
  case SourceCanonicalUtf16Units(expected: Int, actual: Int)
  case SourceCanonicalChecksum(expected: Checksum, actual: Checksum)
  case SourceIdMismatch(expected: StoryId, actual: StoryId)
  case InvalidLineageText(field: BaseballField, component: String)
  case DuplicateLineage(field: BaseballField)
  case MissingLineage(fields: Vector[BaseballField])
  case UnexpectedLineage(fields: Vector[BaseballField])
  case FieldClassMismatch(field: BaseballField, expected: IntakeClass, actual: IntakeClass)
  case LineageChecksumMismatch(expected: Checksum, actual: Checksum)
  case SplitShape(expected: Int, actual: Int)
  case SplitChecksumMismatch(expected: Checksum, actual: Checksum)

  def message: String = this match
    case UnsafeRelativePath(label) => s"unsafe relative path for $label"
    case RootUnavailable(issue)    => s"external root unavailable: ${issue.render}"
    case MissingArtifact(label)    => s"missing ${label.render}"
    case NotRegularFile(label)     => s"${label.render} is not a regular file"
    case PathEscapesRoot(label)    => s"${label.render} resolves outside the external root"
    case ReadFailed(label, op)     => s"${op.render} failed for ${label.render}"
    case ManifestChecksumMismatch(label, expected, actual) =>
      s"${label.render} checksum ${actual.short()} != pinned ${expected.short()}"
    case ManifestEncoding(label)             => s"${label.render} is not strict UTF-8"
    case ManifestFormat(label, line, reason) =>
      s"${label.render} line $line is not canonical: $reason"
    case ManifestCount(label, expected, actual) =>
      s"${label.render} has $actual rows; expected $expected"
    case ParticipantPairMismatch(t, g) =>
      s"participant artifacts do not pair one-to-one: transcript-only=$t textgrid-only=$g"
    case SizeMismatch(label, expected, actual) =>
      s"${label.render} has $actual bytes; expected $expected"
    case ChecksumMismatch(label, expected, actual) =>
      s"${label.render} checksum ${actual.short()} != pinned ${expected.short()}"
    case SourceEncoding           => "stimulus transcript is not strict UTF-8"
    case SourceConstructionFailed => "verified stimulus bytes do not construct a StorySource"
    case SourceTokenCount(expected, actual) =>
      s"stimulus lexical stream has $actual tokens; expected $expected"
    case SourceLexicalChecksum(expected, actual) =>
      s"stimulus lexical checksum ${actual.short()} != pinned ${expected.short()}"
    case SourceCanonicalUtf16Units(expected, actual) =>
      s"StorySource canonical text has $actual UTF-16 units; expected $expected"
    case SourceCanonicalChecksum(expected, actual) =>
      s"StorySource canonical checksum ${actual.short()} != pinned ${expected.short()}"
    case SourceIdMismatch(expected, actual) =>
      s"StorySource id ${actual.value} != pinned ${expected.value}"
    case InvalidLineageText(field, component) =>
      s"${field.render} has invalid lineage $component"
    case DuplicateLineage(field)   => s"duplicate lineage row for ${field.render}"
    case MissingLineage(fields)    => s"missing lineage rows: ${fields.map(_.render).mkString(",")}"
    case UnexpectedLineage(fields) =>
      s"unexpected lineage rows: ${fields.map(_.render).mkString(",")}"
    case FieldClassMismatch(field, expected, actual) =>
      s"${field.render} is ${actual.render}; required ${expected.render}"
    case LineageChecksumMismatch(expected, actual) =>
      s"lineage checksum ${actual.short()} != pinned ${expected.short()}"
    case SplitShape(expected, actual) =>
      s"participant split covers $actual rows; expected $expected"
    case SplitChecksumMismatch(expected, actual) =>
      s"participant split checksum ${actual.short()} != pinned ${expected.short()}"

/** One fixed, non-participant artifact in the production snapshot. */
private[nfrd] final class FixedArtifactSpec private[nfrd] (
    val label: ArtifactLabel,
    val path: RelativeArtifactPath,
    val bytes: Long,
    val checksum: Checksum
)

/** One external participant-manifest contract. */
private[nfrd] final class ManifestSpec private[nfrd] (
    val label: ArtifactLabel,
    val path: RelativeArtifactPath,
    val directory: RelativeArtifactPath,
    val expectedCount: Int,
    val checksum: Checksum,
    val artifactKind: ParticipantArtifactKind
)

/** Which strict NFRD filename grammar a participant manifest uses. */
private[nfrd] enum ParticipantArtifactKind:
  case Transcript, TextGrid

/** Exact, seedless hash-ranking protocol for the fixed-pair participant cohort. */
private[nfrd] final class SplitSpec private[nfrd] (
    val participantDomain: String,
    val rankingDomain: String,
    val development: Int,
    val calibration: Int,
    val untouchedTest: Int,
    val expectedChecksum: Checksum
):
  val seed: Option[Long] = None
  val total: Int = development + calibration + untouchedTest

/** All constants a verifier needs; production has no public unchecked constructor. */
private[bench] final class NfrdBaseballSpec private[nfrd] (
    val transcriptManifest: ManifestSpec,
    val textGridManifest: ManifestSpec,
    val stimulusTranscript: FixedArtifactSpec,
    val stimulusTextGrid: FixedArtifactSpec,
    val stimulusAudio: FixedArtifactSpec,
    val expectedLexicalTokens: Int,
    val expectedLexicalChecksum: Checksum,
    val expectedCanonicalUtf16Units: Int,
    val expectedCanonicalChecksum: Checksum,
    val expectedStoryId: StoryId,
    val lineage: Vector[FieldLineage],
    val expectedLineageChecksum: Checksum,
    val split: SplitSpec
)

/** Bridges the shared path validator to NFRD's labelled refusal.
  *
  * The shared `RelativeArtifactPath.from` carries no label, because a label is a per-corpus notion
  * -- NFRD's is participant-shaped (`RecallTranscript(key)`), and a generic primitive must not know
  * that. The label is reattached here, where it means something.
  */
private[nfrd] def childPath(
    directory: RelativeArtifactPath,
    filename: String,
    label: String
): Either[NfrdIntakeError, RelativeArtifactPath] =
  RelativeArtifactPath
    .child(directory, filename)
    .left
    .map(_ => NfrdIntakeError.UnsafeRelativePath(label))

private[nfrd] object NfrdBaseballSpec:
  private def checksum(raw: String): Checksum = Checksum.unsafe(raw)

  private def path(raw: String, label: String): RelativeArtifactPath =
    RelativeArtifactPath
      .from(raw)
      .fold(_ => throw new AssertionError(s"unsafe relative path for $label"), identity)

  private def lineage(
      field: BaseballField,
      intakeClass: IntakeClass,
      reversibility: Reversibility,
      procedure: String,
      evidenceRef: String
  ): FieldLineage =
    FieldLineage
      .of(field, intakeClass, reversibility, procedure, evidenceRef)
      .fold(error => throw new AssertionError(error.message), identity)

  private val productionLineage: Vector[FieldLineage] = Vector(
    lineage(
      BaseballField.ReleasedParticipantId,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "names replaced with released numeric pseudonyms; mapping withheld",
      "nfrd-paper/privacy"
    ),
    lineage(
      BaseballField.ReleasedCohortMembership,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "engagement exclusion plus budgeted gender-stratified transcription selection",
      "nfrd-paper/participants"
    ),
    lineage(
      BaseballField.StoryAssignment,
      IntakeClass.Unknown,
      Reversibility.Unknown,
      "assignment workbook excluded from the minimized first bundle",
      "nfrd-inventory/survey"
    ),
    lineage(
      BaseballField.PresentationOrder,
      IntakeClass.Unknown,
      Reversibility.Unknown,
      "presentation-order workbook excluded from the minimized first bundle",
      "nfrd-inventory/survey"
    ),
    lineage(
      BaseballField.UpstreamTechnicalLoss,
      IntakeClass.Unknown,
      Reversibility.Unknown,
      "paper reports technical loss but no released per-presentation status",
      "nfrd-paper/technical-loss"
    ),
    lineage(
      BaseballField.StimulusTranscript,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "Project Gutenberg chapter normalized into the released transcript",
      "nfrd-inventory/baseball-text"
    ),
    lineage(
      BaseballField.StimulusAudio,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "LibriVox section trimmed and FFmpeg-processed",
      "nfrd-inventory/baseball-audio"
    ),
    lineage(
      BaseballField.StimulusWordTiming,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "forced alignment of released transcript to processed stimulus audio",
      "nfrd-paper/alignment"
    ),
    lineage(
      BaseballField.RecallSpeech,
      IntakeClass.Observation,
      Reversibility.NotApplicable,
      "participant speech waveform withheld from the public release",
      "nfrd-paper/privacy"
    ),
    lineage(
      BaseballField.CleanedRecallText,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "Google transcription followed by human correction, deletion, and normalization",
      "nfrd-paper/transcription"
    ),
    lineage(
      BaseballField.RecallWordTiming,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "P2FA timing after SoX resampling; recall audio withheld",
      "nfrd-paper/alignment"
    ),
    lineage(
      BaseballField.RecallNoiseLabels,
      IntakeClass.Derivation,
      Reversibility.Irreversible,
      "P2FA pause and noise annotations with unreported completeness",
      "nfrd-paper/alignment"
    )
  )

  val production: NfrdBaseballSpec =
    val transcriptManifest = new ManifestSpec(
      ArtifactLabel.TranscriptManifest,
      path("metadata/baseball-recall-transcripts.tsv", "transcript-manifest"),
      path("data/recall_transcripts/baseball", "transcript-directory"),
      113,
      checksum("d5cf72396b06d54930c72644d1f7d0afb5b199f2bf6c42e42f651039e70d58b4"),
      ParticipantArtifactKind.Transcript
    )
    val textGridManifest = new ManifestSpec(
      ArtifactLabel.TextGridManifest,
      path("metadata/baseball-recall-textgrids.tsv", "textgrid-manifest"),
      path("data/recall_aligned/baseball", "textgrid-directory"),
      113,
      checksum("987a126f15d6afa89357da422ea65ce62238ffadbbcf9e96967dd554f52b60f4"),
      ParticipantArtifactKind.TextGrid
    )
    new NfrdBaseballSpec(
      transcriptManifest,
      textGridManifest,
      new FixedArtifactSpec(
        ArtifactLabel.StimulusTranscript,
        path("experiment_materials/stimuli/baseball_transcript.txt", "stimulus-transcript"),
        11237L,
        checksum("030810edbcd4bc159f098a8ab8b778a8e1914992174911e464c11ff1a686a900")
      ),
      new FixedArtifactSpec(
        ArtifactLabel.StimulusTextGrid,
        path("experiment_materials/stimuli/baseball_aligned.TextGrid", "stimulus-textgrid"),
        319931L,
        checksum("c0dd0503e5d787e34f937f6bfc24c97f346a928a8165ecee4b956d5b940a2db5")
      ),
      new FixedArtifactSpec(
        ArtifactLabel.StimulusAudio,
        path("experiment_materials/stimuli/audio/baseball_audio.mp3", "stimulus-audio"),
        12289426L,
        checksum("ab4875527694890c1372d8333a8b5a20cf558dffc7617e6352efe328322ba791")
      ),
      2187,
      checksum("5a5f76e3b48361752ba260e599b28adf98ef0a9346a4f45bd361356dc36e9643"),
      11236,
      checksum("b142dd642779ae96bf7483b49b29c25bb4b6a577998a95bb4ed76dc43e010b1e"),
      StoryId.unsafe("story:86498fdb3920"),
      productionLineage,
      checksum("bcfca613502ec24740e0746d6ff12eeb2e2ad45bf3542a6d14d2242bf80ba1f3"),
      new SplitSpec(
        "nfrd/oregontrail-baseball/participant/v1",
        "nfrd/oregontrail-baseball/participant-split/v1",
        57,
        28,
        28,
        checksum("45d879a8c201d8b4424ecf208969d4339295f2a48394248ff130a94d48f5ebd9")
      )
    )

  /** Test-only spec factory: production callers cannot weaken the pinned court. */
  private[nfrd] def testing(
      transcriptManifest: ManifestSpec,
      textGridManifest: ManifestSpec,
      stimulusTranscript: FixedArtifactSpec,
      stimulusTextGrid: FixedArtifactSpec,
      stimulusAudio: FixedArtifactSpec,
      expectedLexicalTokens: Int,
      expectedLexicalChecksum: Checksum,
      expectedCanonicalUtf16Units: Int,
      expectedCanonicalChecksum: Checksum,
      expectedStoryId: StoryId,
      lineage: Vector[FieldLineage],
      expectedLineageChecksum: Checksum,
      split: SplitSpec
  ): NfrdBaseballSpec =
    new NfrdBaseballSpec(
      transcriptManifest,
      textGridManifest,
      stimulusTranscript,
      stimulusTextGrid,
      stimulusAudio,
      expectedLexicalTokens,
      expectedLexicalChecksum,
      expectedCanonicalUtf16Units,
      expectedCanonicalChecksum,
      expectedStoryId,
      lineage,
      expectedLineageChecksum,
      split
    )

/** A verified artifact receipt that deliberately renders no external path. */
private[bench] final class ArtifactReceipt private[nfrd] (
    val label: ArtifactLabel,
    private[nfrd] val path: RelativeArtifactPath,
    val bytes: Long,
    val checksum: Checksum
):
  override def toString: String =
    s"ArtifactReceipt(${label.render},bytes=$bytes,checksum=${checksum.short()})"

/** One paired and verified participant input with no retained transcript bytes. */
private[bench] final class VerifiedParticipant private[nfrd] (
    val key: ParticipantKey,
    val partition: ParticipantPartition,
    private[nfrd] val transcript: ArtifactReceipt,
    private[nfrd] val textGrid: ArtifactReceipt
):
  override def toString: String = s"VerifiedParticipant(${key.render},${partition.render})"

/** The deterministic, non-disclosing receipt printed by the real-data court. */
private[bench] final class NfrdBaseballReceipt private[nfrd] (
    val sourceRawChecksum: Checksum,
    val sourceCanonicalChecksum: Checksum,
    val sourceId: StoryId,
    val sourceLexicalTokens: Int,
    val sourceLexicalChecksum: Checksum,
    val sourceCanonicalUtf16Units: Int,
    val stimulusTextGridChecksum: Checksum,
    val stimulusAudioChecksum: Checksum,
    val transcriptManifestChecksum: Checksum,
    val textGridManifestChecksum: Checksum,
    val participantCount: Int,
    val developmentCount: Int,
    val calibrationCount: Int,
    val untouchedTestCount: Int,
    val splitChecksum: Checksum,
    val lineageChecksum: Checksum,
    val contentChecksum: Checksum
):
  def render: String =
    Vector(
      "NFRD Baseball input: VERIFIED",
      "origin=diagnostic",
      s"source.id=${sourceId.value}",
      s"source.raw=${sourceRawChecksum.hex}",
      s"source.canonical=${sourceCanonicalChecksum.hex}",
      s"source.lexical.tokens=$sourceLexicalTokens",
      s"source.lexical.receipt=${sourceLexicalChecksum.hex}",
      s"source.canonical.utf16-units=$sourceCanonicalUtf16Units",
      s"stimulus.textgrid=${stimulusTextGridChecksum.hex}",
      s"stimulus.audio=${stimulusAudioChecksum.hex}",
      s"manifest.transcripts=${transcriptManifestChecksum.hex}",
      s"manifest.textgrids=${textGridManifestChecksum.hex}",
      s"participants=$participantCount",
      s"partitions=development:$developmentCount,calibration:$calibrationCount," +
        s"untouched-test:$untouchedTestCount",
      "split.method=sha256-nul-rank-v1",
      "split.seed=none",
      s"split.receipt=${splitChecksum.hex}",
      s"lineage.receipt=${lineageChecksum.hex}",
      s"input.receipt=${contentChecksum.hex}"
    ).mkString("\n")

  override def toString: String =
    s"NfrdBaseballReceipt(participants=$participantCount,checksum=${contentChecksum.short()})"

/** A verified snapshot: source text is retained, participant language is not. */
private[bench] final class VerifiedNfrdBaseball private[nfrd] (
    val source: StorySource,
    val participants: Vector[VerifiedParticipant],
    val origin: Origin.Diagnostic,
    val receipt: NfrdBaseballReceipt
):
  override def toString: String =
    s"VerifiedNfrdBaseball(participants=${participants.size},receipt=${receipt.contentChecksum.short()})"

/** Pure NFRD Baseball intake court over an injected external artifact reader. */
private[bench] object NfrdBaseballVerifier:
  private val TokenRe = "^P[0-9]{3}$".r
  private val TranscriptNameRe = "^(P[0-9]{3})_baseball\\.txt$".r
  private val TextGridNameRe = "^(P[0-9]{3})_baseball\\.TextGrid$".r
  private val DecimalRe = "^[1-9][0-9]*$".r
  private val LineageDomain = "nfrd/baseball/lineage/v1"

  private val requiredClasses: Map[BaseballField, IntakeClass] = Map(
    BaseballField.ReleasedParticipantId -> IntakeClass.Derivation,
    BaseballField.ReleasedCohortMembership -> IntakeClass.Derivation,
    BaseballField.StoryAssignment -> IntakeClass.Unknown,
    BaseballField.PresentationOrder -> IntakeClass.Unknown,
    BaseballField.UpstreamTechnicalLoss -> IntakeClass.Unknown,
    BaseballField.StimulusTranscript -> IntakeClass.Derivation,
    BaseballField.StimulusAudio -> IntakeClass.Derivation,
    BaseballField.StimulusWordTiming -> IntakeClass.Derivation,
    BaseballField.RecallSpeech -> IntakeClass.Observation,
    BaseballField.CleanedRecallText -> IntakeClass.Derivation,
    BaseballField.RecallWordTiming -> IntakeClass.Derivation,
    BaseballField.RecallNoiseLabels -> IntakeClass.Derivation
  )

  private[nfrd] type Reader = ArtifactRequest => Either[NfrdIntakeError, Array[Byte]]

  private final class ManifestEntry(
      val token: String,
      val filename: String,
      val bytes: Long,
      val checksum: Checksum
  )

  private final class ParsedManifest(
      val spec: ManifestSpec,
      val entries: Vector[ManifestEntry]
  )

  private final class ParticipantArtifacts(
      val token: String,
      val key: ParticipantKey,
      val transcript: ArtifactReceipt,
      val textGrid: ArtifactReceipt
  )

  def verify(
      spec: NfrdBaseballSpec,
      read: Reader
  ): Either[NfrdIntakeError, VerifiedNfrdBaseball] =
    for
      transcriptManifest <- readManifest(spec.transcriptManifest, read)
      textGridManifest <- readManifest(spec.textGridManifest, read)
      pairs <- pair(
        transcriptManifest,
        textGridManifest,
        spec.transcriptManifest.checksum,
        spec.split
      )
      verifiedPairs <- verifyParticipants(pairs, read)
      sourceBytes <- verifyFixed(spec.stimulusTranscript, read)
      _ <- verifyFixed(spec.stimulusTextGrid, read)
      _ <- verifyFixed(spec.stimulusAudio, read)
      sourceText <- decodeUtf8(sourceBytes, source = true, spec.stimulusTranscript.label)
      source <- StorySource
        .fromText(sourceText, Some("Baseball Joe in the Big League — Chapter I"))
        .left
        .map(_ => NfrdIntakeError.SourceConstructionFailed)
      _ <- verifySource(source, spec)
      lineageChecksum <- verifyLineage(spec.lineage, spec.expectedLineageChecksum)
      participantsAndSplit <- partition(verifiedPairs, spec.split, spec.transcriptManifest.checksum)
      (participants, splitChecksum) = participantsAndSplit
      receipt = receiptFor(spec, source, participants, splitChecksum, lineageChecksum)
    yield new VerifiedNfrdBaseball(
      source,
      participants,
      Origin.Diagnostic(
        "nfrd:h2pkv/baseball",
        "one-story external diagnostic; not calibration or validation"
      ),
      receipt
    )

  private def readManifest(
      spec: ManifestSpec,
      read: Reader
  ): Either[NfrdIntakeError, ParsedManifest] =
    for
      bytes <- read(ArtifactRequest(spec.label, spec.path))
      actual = Checksum.ofBytes(bytes)
      _ <- Either.cond(
        actual == spec.checksum,
        (),
        NfrdIntakeError.ManifestChecksumMismatch(spec.label, spec.checksum, actual)
      )
      text <- decodeUtf8(bytes, source = false, spec.label)
      entries <- parseManifest(spec, text)
    yield new ParsedManifest(spec, entries)

  private def decodeUtf8(
      bytes: Array[Byte],
      source: Boolean,
      label: ArtifactLabel
  ): Either[NfrdIntakeError, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    try Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch
      case _: CharacterCodingException =>
        if source then Left(NfrdIntakeError.SourceEncoding)
        else Left(NfrdIntakeError.ManifestEncoding(label))

  private def parseManifest(
      spec: ManifestSpec,
      text: String
  ): Either[NfrdIntakeError, Vector[ManifestEntry]] =
    if !text.endsWith("\n") || text.contains('\r') then
      Left(NfrdIntakeError.ManifestFormat(spec.label, 0, "requires LF terminator and no CR"))
    else
      val lines = text.dropRight(1).split("\n", -1).toVector
      if lines.size != spec.expectedCount then
        Left(NfrdIntakeError.ManifestCount(spec.label, spec.expectedCount, lines.size))
      else
        val parsed = lines.zipWithIndex.foldLeft[Either[NfrdIntakeError, Vector[ManifestEntry]]](
          Right(Vector.empty)
        ) { case (acc, (line, index)) =>
          for
            entries <- acc
            entry <- parseManifestLine(spec, line, index + 1)
          yield entries :+ entry
        }
        parsed.flatMap { entries =>
          val names = entries.map(_.filename)
          val duplicateName = names.groupBy(identity).collectFirst {
            case (name, xs) if xs.size > 1 =>
              name
          }
          val duplicateToken = entries
            .groupBy(_.token)
            .collectFirst { case (token, xs) if xs.size > 1 => token }
          if names != names.sorted then
            Left(NfrdIntakeError.ManifestFormat(spec.label, 0, "filenames are not sorted"))
          else if duplicateName.nonEmpty then
            Left(NfrdIntakeError.ManifestFormat(spec.label, 0, "duplicate filename"))
          else if duplicateToken.nonEmpty then
            Left(NfrdIntakeError.ManifestFormat(spec.label, 0, "duplicate participant"))
          else Right(entries)
        }

  private def parseManifestLine(
      spec: ManifestSpec,
      line: String,
      lineNumber: Int
  ): Either[NfrdIntakeError, ManifestEntry] =
    line.split("\t", -1).toVector match
      case Vector(filename, sizeRaw, checksumRaw) =>
        for
          token <- participantToken(spec.artifactKind, filename).toRight(
            NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "filename grammar")
          )
          _ <- Either.cond(
            TokenRe.matches(token),
            (),
            NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "participant grammar")
          )
          _ <- Either.cond(
            DecimalRe.matches(sizeRaw),
            (),
            NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "positive decimal size")
          )
          size <- sizeRaw.toLongOption.toRight(
            NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "size overflow")
          )
          checksum <- Checksum
            .from(checksumRaw)
            .left
            .map(_ => NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "sha256"))
        yield new ManifestEntry(token, filename, size, checksum)
      case _ => Left(NfrdIntakeError.ManifestFormat(spec.label, lineNumber, "three TSV fields"))

  private def participantToken(kind: ParticipantArtifactKind, filename: String): Option[String] =
    (kind, filename) match
      case (ParticipantArtifactKind.Transcript, TranscriptNameRe(token)) => Some(token)
      case (ParticipantArtifactKind.TextGrid, TextGridNameRe(token))     => Some(token)
      case _                                                             => None

  private def pair(
      transcripts: ParsedManifest,
      textGrids: ParsedManifest,
      transcriptManifestChecksum: Checksum,
      split: SplitSpec
  ): Either[NfrdIntakeError, Vector[ParticipantArtifacts]] =
    val byTranscript = transcripts.entries.iterator.map(entry => entry.token -> entry).toMap
    val byTextGrid = textGrids.entries.iterator.map(entry => entry.token -> entry).toMap
    val transcriptOnly = byTranscript.keySet.diff(byTextGrid.keySet)
    val textGridOnly = byTextGrid.keySet.diff(byTranscript.keySet)
    if transcriptOnly.nonEmpty || textGridOnly.nonEmpty then
      Left(NfrdIntakeError.ParticipantPairMismatch(transcriptOnly.size, textGridOnly.size))
    else
      byTranscript.keys.toVector.sorted.foldLeft[
        Either[NfrdIntakeError, Vector[ParticipantArtifacts]]
      ](Right(Vector.empty)) { (acc, token) =>
        for
          pairs <- acc
          transcript = byTranscript(token)
          textGrid = byTextGrid(token)
          key = participantKey(token, transcriptManifestChecksum, split.participantDomain)
          transcriptPath <- childPath(
            transcripts.spec.directory,
            transcript.filename,
            "recall-transcript"
          )
          textGridPath <- childPath(
            textGrids.spec.directory,
            textGrid.filename,
            "recall-textgrid"
          )
        yield pairs :+ new ParticipantArtifacts(
          token,
          key,
          new ArtifactReceipt(
            ArtifactLabel.RecallTranscript(key),
            transcriptPath,
            transcript.bytes,
            transcript.checksum
          ),
          new ArtifactReceipt(
            ArtifactLabel.RecallTextGrid(key),
            textGridPath,
            textGrid.bytes,
            textGrid.checksum
          )
        )
      }

  private def participantKey(
      token: String,
      manifestChecksum: Checksum,
      participantDomain: String
  ): ParticipantKey =
    new ParticipantKey(
      ContentAddress.digest(
        Vector(
          participantDomain,
          manifestChecksum.hex,
          token
        )
      )
    )

  private def verifyParticipants(
      pairs: Vector[ParticipantArtifacts],
      read: Reader
  ): Either[NfrdIntakeError, Vector[ParticipantArtifacts]] =
    pairs
      .foldLeft[Either[NfrdIntakeError, Unit]](Right(())) { (acc, pair) =>
        for
          _ <- acc
          _ <- verifyReceipt(pair.transcript, read)
          _ <- verifyReceipt(pair.textGrid, read)
        yield ()
      }
      .map(_ => pairs)

  private def verifyReceipt(
      receipt: ArtifactReceipt,
      read: Reader
  ): Either[NfrdIntakeError, Unit] =
    for
      bytes <- read(ArtifactRequest(receipt.label, receipt.path))
      _ <- verifyBytes(receipt.label, receipt.bytes, receipt.checksum, bytes)
    yield ()

  private def verifyFixed(
      artifact: FixedArtifactSpec,
      read: Reader
  ): Either[NfrdIntakeError, Array[Byte]] =
    for
      bytes <- read(ArtifactRequest(artifact.label, artifact.path))
      _ <- verifyBytes(artifact.label, artifact.bytes, artifact.checksum, bytes)
    yield bytes

  private def verifyBytes(
      label: ArtifactLabel,
      expectedBytes: Long,
      expectedChecksum: Checksum,
      bytes: Array[Byte]
  ): Either[NfrdIntakeError, Unit] =
    if bytes.length.toLong != expectedBytes then
      Left(NfrdIntakeError.SizeMismatch(label, expectedBytes, bytes.length.toLong))
    else
      val actual = Checksum.ofBytes(bytes)
      Either.cond(
        actual == expectedChecksum,
        (),
        NfrdIntakeError.ChecksumMismatch(label, expectedChecksum, actual)
      )

  private def lexicalFingerprint(text: String): (Int, Checksum) =
    val tokens = Vector.newBuilder[String]
    val token = new java.lang.StringBuilder()
    def flush(): Unit =
      if token.length() > 0 then
        tokens += token.toString
        token.setLength(0)
    TextNorm.codePoints(text).foreach { codePoint =>
      if Character.isLetterOrDigit(codePoint) then
        token.appendCodePoint(Character.toLowerCase(codePoint))
      else flush()
    }
    flush()
    val result = tokens.result()
    result.size -> Checksum.ofText(result.mkString("\n"))

  private def verifySource(
      source: StorySource,
      spec: NfrdBaseballSpec
  ): Either[NfrdIntakeError, Unit] =
    val (tokenCount, lexicalChecksum) = lexicalFingerprint(source.rawText)
    for
      _ <- Either.cond(
        tokenCount == spec.expectedLexicalTokens,
        (),
        NfrdIntakeError.SourceTokenCount(spec.expectedLexicalTokens, tokenCount)
      )
      _ <- Either.cond(
        lexicalChecksum == spec.expectedLexicalChecksum,
        (),
        NfrdIntakeError.SourceLexicalChecksum(spec.expectedLexicalChecksum, lexicalChecksum)
      )
      _ <- Either.cond(
        source.canonicalText.length == spec.expectedCanonicalUtf16Units,
        (),
        NfrdIntakeError.SourceCanonicalUtf16Units(
          spec.expectedCanonicalUtf16Units,
          source.canonicalText.length
        )
      )
      _ <- Either.cond(
        source.canonicalChecksum == spec.expectedCanonicalChecksum,
        (),
        NfrdIntakeError.SourceCanonicalChecksum(
          spec.expectedCanonicalChecksum,
          source.canonicalChecksum
        )
      )
      _ <- Either.cond(
        source.id == spec.expectedStoryId,
        (),
        NfrdIntakeError.SourceIdMismatch(spec.expectedStoryId, source.id)
      )
    yield ()

  private[nfrd] def lineageChecksum(lineage: Vector[FieldLineage]): Checksum =
    ContentAddress.digest(
      Vector(LineageDomain) ++ lineage.sortBy(_.field.ordinal).flatMap(_.canonicalParts)
    )

  private def verifyLineage(
      lineage: Vector[FieldLineage],
      expectedChecksum: Checksum
  ): Either[NfrdIntakeError, Checksum] =
    val grouped = lineage.groupBy(_.field)
    val duplicate = grouped.collectFirst { case (field, rows) if rows.size > 1 => field }
    val actualFields = grouped.keySet
    val requiredFields = BaseballField.values.toSet
    val missing = requiredFields.diff(actualFields).toVector.sortBy(_.ordinal)
    val unexpected = actualFields.diff(requiredFields).toVector.sortBy(_.ordinal)
    duplicate match
      case Some(field)                 => Left(NfrdIntakeError.DuplicateLineage(field))
      case None if missing.nonEmpty    => Left(NfrdIntakeError.MissingLineage(missing))
      case None if unexpected.nonEmpty => Left(NfrdIntakeError.UnexpectedLineage(unexpected))
      case None                        =>
        val classMismatch = lineage.collectFirst {
          case row if requiredClasses.get(row.field).exists(_ != row.intakeClass) =>
            NfrdIntakeError.FieldClassMismatch(
              row.field,
              requiredClasses(row.field),
              row.intakeClass
            )
        }
        classMismatch match
          case Some(error) => Left(error)
          case None        =>
            val actual = lineageChecksum(lineage)
            Either.cond(
              actual == expectedChecksum,
              actual,
              NfrdIntakeError.LineageChecksumMismatch(expectedChecksum, actual)
            )

  private def partition(
      pairs: Vector[ParticipantArtifacts],
      split: SplitSpec,
      transcriptManifestChecksum: Checksum
  ): Either[NfrdIntakeError, (Vector[VerifiedParticipant], Checksum)] =
    if pairs.size != split.total then Left(NfrdIntakeError.SplitShape(split.total, pairs.size))
    else
      val ranked = pairs
        .map { pair =>
          val rank = ContentAddress.digest(
            Vector(split.rankingDomain, transcriptManifestChecksum.hex, pair.token)
          )
          (rank.hex, pair.token, pair)
        }
        .sortBy((rank, token, _) => (rank, token))
      val participants = ranked.zipWithIndex.map { case ((_, _, pair), index) =>
        val partition =
          if index < split.development then ParticipantPartition.Development
          else if index < split.development + split.calibration then
            ParticipantPartition.Calibration
          else ParticipantPartition.UntouchedTest
        new VerifiedParticipant(pair.key, partition, pair.transcript, pair.textGrid)
      }
      val actual = ContentAddress.digest(
        Vector(split.rankingDomain, transcriptManifestChecksum.hex) ++ participants.map(p =>
          s"${p.key.checksum.hex}=${p.partition.render}"
        )
      )
      Either.cond(
        actual == split.expectedChecksum,
        participants -> actual,
        NfrdIntakeError.SplitChecksumMismatch(split.expectedChecksum, actual)
      )

  private def receiptFor(
      spec: NfrdBaseballSpec,
      source: StorySource,
      participants: Vector[VerifiedParticipant],
      splitChecksum: Checksum,
      lineageChecksum: Checksum
  ): NfrdBaseballReceipt =
    val development = participants.count(_.partition == ParticipantPartition.Development)
    val calibration = participants.count(_.partition == ParticipantPartition.Calibration)
    val untouched = participants.count(_.partition == ParticipantPartition.UntouchedTest)
    val sourceMetadata = source.metadata.toVector.sortBy(_._1).flatMap { case (key, value) =>
      Vector(key, value)
    }
    val content = ContentAddress.digest(
      Vector(
        "nfrd/baseball/input-receipt/v1",
        source.rawChecksum.hex,
        source.canonicalChecksum.hex,
        source.id.value,
        source.title.fold("title:none")(title => s"title:some:$title"),
        source.language.value,
        source.metadata.size.toString,
        spec.expectedLexicalTokens.toString,
        spec.expectedLexicalChecksum.hex,
        spec.expectedCanonicalUtf16Units.toString,
        spec.stimulusTextGrid.checksum.hex,
        spec.stimulusAudio.checksum.hex,
        spec.transcriptManifest.checksum.hex,
        spec.textGridManifest.checksum.hex,
        participants.size.toString,
        development.toString,
        calibration.toString,
        untouched.toString,
        "seed=none",
        splitChecksum.hex,
        lineageChecksum.hex
      ) ++ sourceMetadata
    )
    new NfrdBaseballReceipt(
      source.rawChecksum,
      source.canonicalChecksum,
      source.id,
      spec.expectedLexicalTokens,
      spec.expectedLexicalChecksum,
      spec.expectedCanonicalUtf16Units,
      spec.stimulusTextGrid.checksum,
      spec.stimulusAudio.checksum,
      spec.transcriptManifest.checksum,
      spec.textGridManifest.checksum,
      participants.size,
      development,
      calibration,
      untouched,
      splitChecksum,
      lineageChecksum,
      content
    )
