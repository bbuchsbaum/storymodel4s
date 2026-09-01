package storymodel4s.acquire

import java.nio.charset.StandardCharsets

import cats.data.{NonEmptyVector, ValidatedNec}
import cats.syntax.all.*
import storymodel4s.core.*

/** Stable identity of one story-output invocation. */
object InvocationId extends OpaqueId("InvocationId")
type InvocationId = InvocationId.T

/** Stable identity of the rule that determines eligible target members. */
object UniverseDefinitionId extends OpaqueId("UniverseDefinitionId")
type UniverseDefinitionId = UniverseDefinitionId.T

/** Stable identity of a portable decoder implementation, configuration, and error policy. */
object DecoderId extends OpaqueId("DecoderId")
type DecoderId = DecoderId.T

/** Stable identity of a decoder error policy. */
object DecodePolicyId extends OpaqueId("DecodePolicyId")
type DecodePolicyId = DecodePolicyId.T

/** Stable identity of a canonical-text transformation contract. */
object CanonicalizationPolicyId extends OpaqueId("CanonicalizationPolicyId")
type CanonicalizationPolicyId = CanonicalizationPolicyId.T

/** Stable IANA-style name of the charset selected for decoding. */
object CharsetId extends OpaqueId("CharsetId")
type CharsetId = CharsetId.T

/** Validated media type of admitted source bytes. */
object MediaTypeId extends OpaqueId("MediaTypeId")
type MediaTypeId = MediaTypeId.T

/** Stable identity of an invocation receipt. */
object OutputReceiptId extends OpaqueId("OutputReceiptId")
type OutputReceiptId = OutputReceiptId.T

/** Receipt identity issued only by the admitted human-adjudication path. */
object AdjudicationReceiptId extends OpaqueId("AdjudicationReceiptId")
type AdjudicationReceiptId = AdjudicationReceiptId.T

/** Receipt identity issued only by the admitted fixture-review path. */
object FixtureAdmissionReceiptId extends OpaqueId("FixtureAdmissionReceiptId")
type FixtureAdmissionReceiptId = FixtureAdmissionReceiptId.T

/** Stable identity of one closed or extension result payload. */
object OutputPayloadId extends OpaqueId("OutputPayloadId")
type OutputPayloadId = OutputPayloadId.T

/** Namespace for typed extension vocabulary. */
object OutputNamespace extends OpaqueId("OutputNamespace")
type OutputNamespace = OutputNamespace.T

/** Label within a typed extension namespace. */
object OutputLabel extends OpaqueId("OutputLabel")
type OutputLabel = OutputLabel.T

/** Schema identity for one closed result or extension payload. */
object OutputSchemaId extends OpaqueId("OutputSchemaId")
type OutputSchemaId = OutputSchemaId.T

/** Records how an input byte-order mark affected decoded text. */
enum BomDisposition:
  case Absent
  case ConsumedUtf8
  case ConsumedUtf16Le
  case ConsumedUtf16Be
  case Preserved
  case Rejected

/** Closed strict-decoding failure reasons. */
enum StrictDecodeFailureReason:
  case InvalidLeadingByte
  case InvalidContinuationByte
  case TruncatedSequence
  case OverlongEncoding
  case SurrogateCodePoint
  case CodePointOutOfRange

/** Decode receipt derived from one exact successful strict decoding operation. */
final class DecodeReceipt private (
    val id: OutputReceiptId,
    val decoder: DecoderId,
    val charset: CharsetId,
    val policy: DecodePolicyId,
    val configChecksum: Checksum,
    val originalChecksum: Checksum,
    val decodedChecksum: Checksum
):
  private def parts =
    (id, decoder, charset, policy, configChecksum, originalChecksum, decodedChecksum)

  override def equals(other: Any): Boolean = other match
    case that: DecodeReceipt => parts == that.parts
    case _                   => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"DecodeReceipt(id=${id.value}, decoder=${decoder.value})"

object DecodeReceipt:
  val StrictUtf8Decoder: DecoderId = DecoderId.unsafe("storymodel4s.strict-utf8/v1")
  val Utf8Charset: CharsetId = CharsetId.unsafe("UTF-8")
  val ReportPolicy: DecodePolicyId = DecodePolicyId.unsafe("report/v1")
  val StrictUtf8ConfigChecksum: Checksum =
    Checksum.ofText("storymodel4s.strict-utf8/v1\u0000UTF-8\u0000report/v1\u0000consume-utf8-bom")

  private[acquire] def successful(
      originalChecksum: Checksum,
      decodedChecksum: Checksum
  ): DecodeReceipt =
    val id = derivedReceiptId(
      "decode-success/v1",
      Vector(
        StrictUtf8Decoder.value,
        Utf8Charset.value,
        ReportPolicy.value,
        StrictUtf8ConfigChecksum.hex,
        originalChecksum.hex,
        decodedChecksum.hex
      )
    )
    new DecodeReceipt(
      id,
      StrictUtf8Decoder,
      Utf8Charset,
      ReportPolicy,
      StrictUtf8ConfigChecksum,
      originalChecksum,
      decodedChecksum
    )

/** Exact typed failure from the library-owned strict decoder. */
final class StrictDecodeFailure private (
    val receipt: OutputReceiptId,
    val decoder: DecoderId,
    val charset: CharsetId,
    val policy: DecodePolicyId,
    val configChecksum: Checksum,
    val originalChecksum: Checksum,
    val bytePosition: Long,
    val reason: StrictDecodeFailureReason
):
  private def parts =
    (
      receipt,
      decoder,
      charset,
      policy,
      configChecksum,
      originalChecksum,
      bytePosition,
      reason
    )

  override def equals(other: Any): Boolean = other match
    case that: StrictDecodeFailure => parts == that.parts
    case _                         => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"StrictDecodeFailure(byte=$bytePosition, reason=$reason, receipt=${receipt.value})"

object StrictDecodeFailure:
  private[acquire] def derived(
      originalChecksum: Checksum,
      bytePosition: Int,
      reason: StrictDecodeFailureReason
  ): StrictDecodeFailure =
    val receipt = derivedReceiptId(
      "decode-failure/v1",
      Vector(
        DecodeReceipt.StrictUtf8Decoder.value,
        DecodeReceipt.Utf8Charset.value,
        DecodeReceipt.ReportPolicy.value,
        DecodeReceipt.StrictUtf8ConfigChecksum.hex,
        originalChecksum.hex,
        bytePosition.toString,
        reason.toString
      )
    )
    new StrictDecodeFailure(
      receipt,
      DecodeReceipt.StrictUtf8Decoder,
      DecodeReceipt.Utf8Charset,
      DecodeReceipt.ReportPolicy,
      DecodeReceipt.StrictUtf8ConfigChecksum,
      originalChecksum,
      bytePosition.toLong,
      reason
    )

  /** Re-run strict decoding so metadata alone cannot mint a failure detail. */
  def fromWire(
      bytes: Array[Byte],
      receipt: OutputReceiptId,
      decoder: DecoderId,
      charset: CharsetId,
      policy: DecodePolicyId,
      configChecksum: Checksum,
      originalChecksum: Checksum,
      bytePosition: Long,
      reason: StrictDecodeFailureReason
  ): Either[DomainError, StrictDecodeFailure] =
    if bytePosition < 0 || bytePosition > Int.MaxValue.toLong then
      Left(
        DomainError.InvalidFormat(
          "StrictDecodeFailure.bytePosition",
          bytePosition.toString,
          "expected a nonnegative Int-sized byte position"
        )
      )
    else
      SourceIdentities.decodeStrictUtf8(bytes) match
        case Left(actual)
            if actual.receipt == receipt && actual.decoder == decoder &&
              actual.charset == charset && actual.policy == policy &&
              actual.configChecksum == configChecksum &&
              actual.originalChecksum == originalChecksum &&
              actual.bytePosition == bytePosition && actual.reason == reason =>
          Right(actual)
        case _ =>
          Left(
            DomainError.InvariantViolation(
              "output/source/decode-failure",
              "decode failure detail does not match strict decoding of the supplied bytes"
            )
          )

private def derivedReceiptId(domain: String, parts: Vector[String]): OutputReceiptId =
  val framed = parts.map { value =>
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    s"${bytes.length}:$value"
  }.mkString
  OutputReceiptId.unsafe(s"$domain:${Checksum.ofText(framed).hex}")

/** Says whether an unavailable extension blocks a requested result. */
enum ExtensionRequirement:
  case Optional
  case Required

/** Closed reasons why a target universe could not be established. */
enum UniverseFailureReason:
  case PlanningFailed
  case EligibilityFailed
  case UpstreamUnavailable
  case InvalidDefinition
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Closed failure families shared by acquisition and report envelopes. */
enum OutputFailureCode:
  case SourceUnavailable
  case DecodeFailed
  case CanonicalizationFailed
  case PlanningFailed
  case ResolutionFailed
  case ValidationFailed
  case RenderingFailed
  case SerializationFailed
  case IntegrityFailed
  case UnsupportedCapability
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Why one established target did or did not contribute accepted semantics. */
enum TargetDisposition:
  case Accepted
  case Alternatives
  case Rejected
  case Unresolved
  case Excluded
  case Failed

/** Why a partial semantic result has a known gap. */
enum ResultGapKind:
  case MissingStage
  case Unresolved
  case Rejected
  case Unsupported
  case Failed
  case Custom(namespace: OutputNamespace, label: OutputLabel)

/** Original input identity, retained even when text construction later fails. */
final class OriginalSourceIdentity private (
    val byteLength: Long,
    val checksum: Checksum,
    val mediaType: MediaTypeId,
    val declaredCharset: Option[CharsetId],
    val selectedCharset: CharsetId,
    val bom: BomDisposition,
    val intakeReceipt: OutputReceiptId
):
  private def parts =
    (byteLength, checksum, mediaType, declaredCharset, selectedCharset, bom, intakeReceipt)

  override def equals(other: Any): Boolean = other match
    case that: OriginalSourceIdentity => parts == that.parts
    case _                            => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"OriginalSourceIdentity(bytes=$byteLength, checksum=${checksum.short()})"

object OriginalSourceIdentity:
  /** Recompute untrusted wire metadata against the actual admitted bytes. */
  def fromWire(
      bytes: Array[Byte],
      byteLength: Long,
      checksum: Checksum,
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId],
      selectedCharset: CharsetId,
      bom: BomDisposition,
      intakeReceipt: OutputReceiptId
  ): Either[DomainError, OriginalSourceIdentity] =
    val expected = fromUtf8Bytes(bytes, mediaType, declaredCharset)
    if byteLength == expected.byteLength && checksum == expected.checksum &&
      selectedCharset == expected.selectedCharset && bom == expected.bom &&
      intakeReceipt == expected.intakeReceipt
    then Right(expected)
    else
      Left(
        DomainError.InvariantViolation(
          "output/source/original-identity",
          "original source metadata does not match the supplied bytes and admission policy"
        )
      )

  /** Bind exact bytes to the library-owned strict UTF-8 admission policy. */
  def fromUtf8Bytes(
      bytes: Array[Byte],
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId]
  ): OriginalSourceIdentity =
    val checksum = Checksum.ofBytes(bytes)
    val bom =
      if bytes.length >= 3 && (bytes(0) & 0xff) == 0xef && (bytes(1) & 0xff) == 0xbb &&
        (bytes(2) & 0xff) == 0xbf
      then BomDisposition.ConsumedUtf8
      else BomDisposition.Absent
    val receipt = derivedReceiptId(
      "source-intake/v1",
      Vector(
        checksum.hex,
        bytes.length.toString,
        mediaType.value,
        declaredCharset.fold("none")(value => s"some:${value.value}"),
        DecodeReceipt.Utf8Charset.value,
        bom.toString
      )
    )
    new OriginalSourceIdentity(
      bytes.length.toLong,
      checksum,
      mediaType,
      declaredCharset,
      DecodeReceipt.Utf8Charset,
      bom,
      receipt
    )

/** Identity and receipt established by decoding before StorySource construction. */
final class DecodedSourceIdentity private (
    val utf16Length: Int,
    val checksum: Checksum,
    val decodeReceipt: DecodeReceipt
):
  /** Decoder identity derived from the typed receipt rather than a parallel label. */
  def decoder: DecoderId = decodeReceipt.decoder

  private def parts = (utf16Length, checksum, decodeReceipt)

  override def equals(other: Any): Boolean = other match
    case that: DecodedSourceIdentity => parts == that.parts
    case _                           => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"DecodedSourceIdentity(utf16=$utf16Length, checksum=${checksum.short()})"

object DecodedSourceIdentity:
  private[acquire] def fromText(
      text: String,
      decodeReceipt: DecodeReceipt
  ): DecodedSourceIdentity =
    new DecodedSourceIdentity(text.length, Checksum.ofText(text), decodeReceipt)

/** Three distinct text identities bound to one successfully constructed StorySource. */
final class SourceIdentities private (
    val original: OriginalSourceIdentity,
    val storyId: StoryId,
    val decodedUtf16Length: Int,
    val decodedChecksum: Checksum,
    val canonicalPolicy: CanonicalizationPolicyId,
    val canonicalByteLength: Long,
    val canonicalUtf16Length: Int,
    val canonicalChecksum: Checksum,
    val decodeReceipt: DecodeReceipt,
    val canonicalizationReceipt: OutputReceiptId
):
  private def parts =
    (
      original,
      storyId,
      decodedUtf16Length,
      decodedChecksum,
      canonicalPolicy,
      canonicalByteLength,
      canonicalUtf16Length,
      canonicalChecksum,
      decodeReceipt,
      canonicalizationReceipt
    )

  override def equals(other: Any): Boolean = other match
    case that: SourceIdentities => parts == that.parts
    case _                      => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"SourceIdentities(story=${storyId.value}, canonical=${canonicalChecksum.short()})"

  /** Decoder identity derived from the typed receipt rather than a parallel label. */
  def decoder: DecoderId = decodeReceipt.decoder

object SourceIdentities:
  /** Exact policy implemented by StorySource.canonicalize on this schema line. */
  val CurrentCanonicalPolicy: CanonicalizationPolicyId =
    CanonicalizationPolicyId.unsafe("storysource-canonical-text/v1")

  /** Checked admission retains a StorySource only when it matches the published outcome. */
  final class Admission private[SourceIdentities] (
      private val source: Option[StorySource],
      val outcome: SourceOutcome
  ):
    def constructed: Option[(StorySource, SourceIdentities)] = (source, outcome) match
      case (Some(value), SourceOutcome.Constructed(identities)) => Some(value -> identities)
      case _                                                    => None

    def refusal: Option[(RefusedSourceProgress, OutputFailure)] = (source, outcome) match
      case (None, SourceOutcome.Refused(progress, failure)) => Some(progress -> failure)
      case _                                                => None

    private def parts = (source, outcome)
    override def equals(other: Any): Boolean = other match
      case that: Admission => parts == that.parts
      case _               => false
    override def hashCode(): Int = parts.hashCode
    override def toString: String = s"SourceAdmission($outcome)"

  /** Admit exact bytes through the library-owned decoder and StorySource constructor. */
  def admitUtf8(
      bytes: Array[Byte],
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId],
      title: Option[String] = None,
      language: LanguageTag = LanguageTag.English,
      metadata: Map[String, String] = Map.empty
  ): Admission =
    val original = OriginalSourceIdentity.fromUtf8Bytes(bytes, mediaType, declaredCharset)
    decodeStrictUtf8(bytes) match
      case Left(detail) =>
        new Admission(
          None,
          SourceOutcome.Refused(
            RefusedSourceProgress.Admitted(original),
            OutputFailure.decode(detail)
          )
        )
      case Right((text, decodeReceipt)) =>
        val decoded = DecodedSourceIdentity.fromText(text, decodeReceipt)
        StorySource.fromText(text, title, language, metadata) match
          case Left(error) =>
            val failureReceipt = derivedReceiptId(
              "source-canonicalization-failure/v1",
              Vector(original.checksum.hex, decoded.checksum.hex, error.message)
            )
            new Admission(
              None,
              SourceOutcome.Refused(
                RefusedSourceProgress.Decoded(original, decoded),
                OutputFailure.canonicalization(failureReceipt)
              )
            )
          case Right(source) =>
            val canonicalizationReceipt = derivedReceiptId(
              "source-canonicalization/v1",
              Vector(
                original.checksum.hex,
                decoded.checksum.hex,
                CurrentCanonicalPolicy.value,
                source.canonicalChecksum.hex,
                source.id.value
              )
            )
            new Admission(
              Some(source),
              SourceOutcome.Constructed(
                fromAdmitted(original, source, decodeReceipt, canonicalizationReceipt)
              )
            )

  /** Re-run admission and accept wire identities only when every published coordinate agrees. */
  def fromWire(
      bytes: Array[Byte],
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId],
      title: Option[String],
      language: LanguageTag,
      metadata: Map[String, String],
      claimed: SourceIdentities
  ): Either[DomainError, (StorySource, SourceIdentities)] =
    val admission = admitUtf8(bytes, mediaType, declaredCharset, title, language, metadata)
    admission.constructed match
      case Some((source, actual)) if actual == claimed => Right(source -> actual)
      case Some(_)                                     =>
        Left(
          DomainError.InvariantViolation(
            "output/source/identities",
            "source identity metadata does not match admission of the supplied bytes"
          )
        )
      case None =>
        val failureCode = admission.refusal.map(_._2.code).fold("unknown")(_.toString)
        Left(
          DomainError.InvariantViolation(
            "output/source/identities",
            s"supplied bytes were refused by source admission: $failureCode"
          )
        )

  private def fromAdmitted(
      original: OriginalSourceIdentity,
      source: StorySource,
      decodeReceipt: DecodeReceipt,
      canonicalizationReceipt: OutputReceiptId
  ): SourceIdentities =
    val canonicalBytes = source.canonicalText.getBytes(StandardCharsets.UTF_8)
    new SourceIdentities(
      original,
      source.id,
      source.rawText.length,
      source.rawChecksum,
      CurrentCanonicalPolicy,
      canonicalBytes.length.toLong,
      source.canonicalText.length,
      source.canonicalChecksum,
      decodeReceipt,
      canonicalizationReceipt
    )

  /** Strict UTF-8 decode with exact original-byte failure positions and no replacement characters.
    */
  private[acquire] def decodeStrictUtf8(
      bytes: Array[Byte]
  ): Either[StrictDecodeFailure, (String, DecodeReceipt)] =
    val originalChecksum = Checksum.ofBytes(bytes)
    val out = new java.lang.StringBuilder(bytes.length)
    val start =
      if bytes.length >= 3 && unsigned(bytes(0)) == 0xef && unsigned(bytes(1)) == 0xbb &&
        unsigned(bytes(2)) == 0xbf
      then 3
      else 0
    var index = start
    var failure: Option[StrictDecodeFailure] = None

    def reject(position: Int, reason: StrictDecodeFailureReason): Unit =
      failure = Some(StrictDecodeFailure.derived(originalChecksum, position, reason))

    def continuation(position: Int): Boolean =
      if position >= bytes.length then
        reject(bytes.length, StrictDecodeFailureReason.TruncatedSequence)
        false
      else if (unsigned(bytes(position)) & 0xc0) != 0x80 then
        reject(position, StrictDecodeFailureReason.InvalidContinuationByte)
        false
      else true

    while index < bytes.length && failure.isEmpty do
      val first = unsigned(bytes(index))
      if first <= 0x7f then
        out.append(first.toChar)
        index += 1
      else if first >= 0xc2 && first <= 0xdf then
        if continuation(index + 1) then
          val codePoint = ((first & 0x1f) << 6) | (unsigned(bytes(index + 1)) & 0x3f)
          out.append(codePoint.toChar)
          index += 2
      else if first >= 0xe0 && first <= 0xef then
        if continuation(index + 1) && continuation(index + 2) then
          val second = unsigned(bytes(index + 1))
          if first == 0xe0 && second < 0xa0 then
            reject(index, StrictDecodeFailureReason.OverlongEncoding)
          else if first == 0xed && second >= 0xa0 then
            reject(index, StrictDecodeFailureReason.SurrogateCodePoint)
          else
            val codePoint =
              ((first & 0x0f) << 12) | ((second & 0x3f) << 6) |
                (unsigned(bytes(index + 2)) & 0x3f)
            out.append(codePoint.toChar)
            index += 3
      else if first >= 0xf0 && first <= 0xf4 then
        if continuation(index + 1) && continuation(index + 2) && continuation(index + 3) then
          val second = unsigned(bytes(index + 1))
          if first == 0xf0 && second < 0x90 then
            reject(index, StrictDecodeFailureReason.OverlongEncoding)
          else if first == 0xf4 && second > 0x8f then
            reject(index, StrictDecodeFailureReason.CodePointOutOfRange)
          else
            val codePoint =
              ((first & 0x07) << 18) | ((second & 0x3f) << 12) |
                ((unsigned(bytes(index + 2)) & 0x3f) << 6) |
                (unsigned(bytes(index + 3)) & 0x3f)
            out.append(Character.highSurrogate(codePoint))
            out.append(Character.lowSurrogate(codePoint))
            index += 4
      else reject(index, StrictDecodeFailureReason.InvalidLeadingByte)

    failure match
      case Some(value) => Left(value)
      case None        =>
        val text = out.toString
        val receipt = DecodeReceipt.successful(originalChecksum, Checksum.ofText(text))
        Right(text -> receipt)

  private def unsigned(value: Byte): Int = value & 0xff

type SourceAdmission = SourceIdentities.Admission

/** Detail required specifically for strict decoding failures. */
enum OutputFailureDetail:
  case StrictDecode(value: StrictDecodeFailure)

/** Typed output failure whose detail shape is checked against its failure family. */
final class OutputFailure private (
    val code: OutputFailureCode,
    val receipt: OutputReceiptId,
    val stage: Option[StageId],
    val evidence: Vector[OutputReceiptId],
    val detail: Option[OutputFailureDetail]
):
  private def parts = (code, receipt, stage, evidence, detail)

  override def equals(other: Any): Boolean = other match
    case that: OutputFailure => parts == that.parts
    case _                   => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"OutputFailure(code=$code, receipt=${receipt.value})"

  /** Replace generic receipt evidence without weakening the checked detail shape. */
  def withReceipt(
      replacement: OutputReceiptId,
      replacementEvidence: Vector[OutputReceiptId]
  ): Either[DomainError, OutputFailure] =
    OutputFailure.fromWire(code, replacement, stage, replacementEvidence, detail)

object OutputFailure:
  /** Construct a non-decode failure. DecodeFailed requires typed strict-decode detail. */
  def general(
      code: OutputFailureCode,
      receipt: OutputReceiptId,
      stage: Option[StageId],
      evidence: Vector[OutputReceiptId]
  ): Either[DomainError, OutputFailure] =
    fromWire(code, receipt, stage, evidence, None)

  private[acquire] def canonicalization(receipt: OutputReceiptId): OutputFailure =
    new OutputFailure(OutputFailureCode.CanonicalizationFailed, receipt, None, Vector.empty, None)

  /** A decode failure derives its receipt from the exact failed operation. */
  def decode(
      detail: StrictDecodeFailure,
      stage: Option[StageId] = None,
      evidence: Vector[OutputReceiptId] = Vector.empty
  ): OutputFailure =
    new OutputFailure(
      OutputFailureCode.DecodeFailed,
      detail.receipt,
      stage,
      evidence,
      Some(OutputFailureDetail.StrictDecode(detail))
    )

  /** Validate an untrusted failure claim, including code/detail/receipt agreement. */
  def fromWire(
      code: OutputFailureCode,
      receipt: OutputReceiptId,
      stage: Option[StageId],
      evidence: Vector[OutputReceiptId],
      detail: Option[OutputFailureDetail]
  ): Either[DomainError, OutputFailure] =
    val detailAgrees = (code, detail) match
      case (OutputFailureCode.DecodeFailed, Some(OutputFailureDetail.StrictDecode(value))) =>
        value.receipt == receipt
      case (OutputFailureCode.DecodeFailed, _) => false
      case (_, None)                           => true
      case (_, Some(_))                        => false
    if !detailAgrees then
      Left(
        DomainError.InvariantViolation(
          "output/failure/detail",
          "DecodeFailed requires matching strict-decode detail and other failures forbid it"
        )
      )
    else
      firstDuplicate(evidence) match
        case Some(duplicate) => Left(DomainError.DuplicateId("OutputFailure.evidence", duplicate))
        case None            => Right(new OutputFailure(code, receipt, stage, evidence, detail))

  private def firstDuplicate(values: Vector[OutputReceiptId]): Option[String] =
    val seen = scala.collection.mutable.HashSet.empty[OutputReceiptId]
    values.find(value => !seen.add(value)).map(_.value)

/** Source construction either establishes all three identities or records refusal. */
enum RefusedSourceProgress:
  case BeforeIntake
  case Admitted(original: OriginalSourceIdentity)
  case Decoded(original: OriginalSourceIdentity, decoded: DecodedSourceIdentity)

/** Source construction either establishes all three identities or preserves completed progress. */
enum SourceOutcome:
  case Constructed(identities: SourceIdentities)
  case Refused(progress: RefusedSourceProgress, failure: OutputFailure)

/** Failure that forbids downstream universe denominators. */
final case class UniverseFailure(
    reason: UniverseFailureReason,
    receipt: OutputReceiptId,
    stage: Option[StageId]
)

/** Nonempty or empty established member set under one exact definition. */
final class EstablishedUniverse[Id] private (
    val members: Vector[Id],
    val definitionIdentity: UniverseDefinitionId
):
  private def parts = (members, definitionIdentity)

  override def equals(other: Any): Boolean = other match
    case that: EstablishedUniverse[?] => parts == that.parts
    case _                            => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"EstablishedUniverse(size=${members.size}, definition=${definitionIdentity.value})"

  /** Compute a rate only from an established, positive denominator. */
  def rate(numerator: Int): Either[DomainError, UniverseRate] =
    if numerator < 0 || numerator > members.size then
      Left(
        DomainError.InvalidFormat(
          "UniverseRate.numerator",
          numerator.toString,
          s"expected an integer in [0, ${members.size}]"
        )
      )
    else if members.isEmpty then Right(UniverseRate.NotApplicableEmpty)
    else EstablishedRate.of(numerator, members.size).map(UniverseRate.Measured.apply)

object EstablishedUniverse:
  /** Establish a universe while rejecting duplicate member identity. */
  def of[Id](
      members: Iterable[Id],
      definitionIdentity: UniverseDefinitionId
  ): Either[DomainError, EstablishedUniverse[Id]] =
    val ordered = members.toVector
    firstDuplicate(ordered) match
      case Some(duplicate) =>
        Left(DomainError.DuplicateId("EstablishedUniverse.member", duplicate.toString))
      case None => Right(new EstablishedUniverse(ordered, definitionIdentity))

  private def firstDuplicate[A](values: Vector[A]): Option[A] =
    val seen = scala.collection.mutable.HashSet.empty[A]
    values.find(value => !seen.add(value))

/** Positive-denominator established rate that cannot be forged with invalid counts. */
final class EstablishedRate private (
    val numerator: Int,
    val denominator: Int
):
  val value: Double = numerator.toDouble / denominator.toDouble

  override def equals(other: Any): Boolean = other match
    case that: EstablishedRate =>
      numerator == that.numerator && denominator == that.denominator
    case _ => false
  override def hashCode(): Int = (numerator, denominator).hashCode
  override def toString: String = s"EstablishedRate($numerator/$denominator)"

object EstablishedRate:
  /** Construct only from valid established counts. */
  def of(numerator: Int, denominator: Int): Either[DomainError, EstablishedRate] =
    if denominator <= 0 then
      Left(
        DomainError.InvalidFormat(
          "EstablishedRate.denominator",
          denominator.toString,
          "expected a positive integer"
        )
      )
    else if numerator < 0 || numerator > denominator then
      Left(
        DomainError.InvalidFormat(
          "EstablishedRate.numerator",
          numerator.toString,
          s"expected an integer in [0, $denominator]"
        )
      )
    else Right(new EstablishedRate(numerator, denominator))

/** Coverage-like value that cannot encode 0/0 as a percentage. */
enum UniverseRate:
  case NotApplicableEmpty
  case Measured(rate: EstablishedRate)

  def value: Option[Double] = this match
    case NotApplicableEmpty => None
    case Measured(rate)     => Some(rate.value)

/** Universe state that makes unestablished denominators uncallable. */
enum TargetUniverse[Id]:
  case Established(value: EstablishedUniverse[Id])
  case Unestablished(failure: UniverseFailure)

/** Exactly one disposition for one established target member. */
final case class TargetAccount[Id](
    id: Id,
    disposition: TargetDisposition,
    payloads: Vector[OutputPayloadId]
)

/** Canonical reference to the sole StoryModelCodec semantic artifact. */
final case class SemanticModelRef(
    storyId: StoryId,
    sourceChecksum: Checksum,
    schemaVersion: OutputSchemaId,
    artifactChecksum: Checksum
)

/** One known reason a semantic result remains partial. */
final case class ResultGap(
    kind: ResultGapKind,
    receipt: OutputReceiptId,
    payload: Option[OutputPayloadId]
)

/** Semantic completion state independent of report delivery. */
enum SemanticOutcome:
  case NotRequested
  case Validated(model: SemanticModelRef)
  case Partial(gaps: NonEmptyVector[ResultGap], draft: Option[SemanticModelRef])
  case Refused(errors: NonEmptyVector[OutputFailure])

/** Closed classifications for view authority; the classification alone is never an authority. */
enum AcquisitionViewAuthorityKind:
  case ValidatedBuild
  case HumanAdjudication
  case FixtureReview

/** Authority joined to the exact source and validated build it licenses.
  *
  * Human and fixture classifications remain wire-visible so unsupported claims can be refused
  * explicitly, but this module does not issue them until a separately governed verifier exists.
  */
final class AcquisitionViewAuthority private (
    val kind: AcquisitionViewAuthorityKind,
    val sourceChecksum: Checksum,
    val buildReceiptChecksum: Option[Checksum],
    val evidenceChecksum: Option[Checksum],
    val adjudicationReceipt: Option[AdjudicationReceiptId],
    val fixtureReceipt: Option[FixtureAdmissionReceiptId]
):
  private def parts =
    (
      kind,
      sourceChecksum,
      buildReceiptChecksum,
      evidenceChecksum,
      adjudicationReceipt,
      fixtureReceipt
    )

  override def equals(other: Any): Boolean = other match
    case that: AcquisitionViewAuthority => parts == that.parts
    case _                              => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"AcquisitionViewAuthority($kind)"

object AcquisitionViewAuthority:
  /** Issue build authority only when the build receipt belongs to the constructed source. */
  def validatedBuild(
      source: SourceOutcome,
      buildReceipt: ExtendedBuildReceipt
  ): Either[DomainError, AcquisitionViewAuthority] =
    joinedSource(source, Some(buildReceipt)).map { case (sourceChecksum, buildChecksum) =>
      new AcquisitionViewAuthority(
        AcquisitionViewAuthorityKind.ValidatedBuild,
        sourceChecksum,
        buildChecksum,
        None,
        None,
        None
      )
    }

  /** Revalidate an untrusted wire claim against the actual acquisition inputs. */
  def fromWire(
      source: SourceOutcome,
      buildReceipt: Option[ExtendedBuildReceipt],
      kind: AcquisitionViewAuthorityKind,
      sourceChecksum: Checksum,
      buildReceiptChecksum: Option[Checksum],
      evidenceChecksum: Option[Checksum],
      adjudicationReceipt: Option[AdjudicationReceiptId],
      fixtureReceipt: Option[FixtureAdmissionReceiptId]
  ): Either[DomainError, AcquisitionViewAuthority] =
    joinedSource(source, buildReceipt).flatMap { case (actualSource, actualBuild) =>
      val validShape = kind match
        case AcquisitionViewAuthorityKind.ValidatedBuild =>
          evidenceChecksum.isEmpty && adjudicationReceipt.isEmpty && fixtureReceipt.isEmpty &&
          actualBuild.nonEmpty
        case AcquisitionViewAuthorityKind.HumanAdjudication |
            AcquisitionViewAuthorityKind.FixtureReview =>
          false
      if sourceChecksum == actualSource && buildReceiptChecksum == actualBuild && validShape then
        Right(
          new AcquisitionViewAuthority(
            kind,
            actualSource,
            actualBuild,
            evidenceChecksum,
            adjudicationReceipt,
            fixtureReceipt
          )
        )
      else
        Left(
          DomainError.InvariantViolation(
            "output/acquisition/view-authority",
            "wire authority receipt does not match its actual source, build, evidence, and authority kind"
          )
        )
    }

  private[acquire] def matches(
      authority: AcquisitionViewAuthority,
      source: SourceOutcome,
      buildReceipt: Option[ExtendedBuildReceipt]
  ): Boolean =
    fromWire(
      source,
      buildReceipt,
      authority.kind,
      authority.sourceChecksum,
      authority.buildReceiptChecksum,
      authority.evidenceChecksum,
      authority.adjudicationReceipt,
      authority.fixtureReceipt
    ).contains(authority)

  private[acquire] def joinedSource(
      source: SourceOutcome,
      buildReceipt: Option[ExtendedBuildReceipt]
  ): Either[DomainError, (Checksum, Option[Checksum])] =
    source match
      case SourceOutcome.Refused(_, _) =>
        Left(
          DomainError.InvariantViolation(
            "output/acquisition/view-authority",
            "view authority requires a constructed source"
          )
        )
      case SourceOutcome.Constructed(identities) =>
        val buildMatches =
          buildReceipt.forall(receipt =>
            receipt.receipt.storyId == identities.storyId &&
              receipt.receipt.sourceChecksum == identities.canonicalChecksum
          )
        if buildMatches then
          Right(
            identities.canonicalChecksum -> buildReceipt.map(_.receipt.contentChecksum)
          )
        else
          Left(
            DomainError.InvariantViolation(
              "output/acquisition/view-authority",
              "view authority build receipt does not belong to the constructed source"
            )
          )

/** Canonical reference to a payload family known to the current schema. */
final case class KnownPayloadRef(
    id: OutputPayloadId,
    schemaId: OutputSchemaId,
    checksum: Checksum
)

/** Exact opaque canonical bytes retained without interpreting an unknown extension. */
final class OpaqueCanonicalPayload private (
    val bytes: Vector[Byte],
    val checksum: Checksum
):
  override def equals(other: Any): Boolean = other match
    case that: OpaqueCanonicalPayload => bytes == that.bytes && checksum == that.checksum
    case _                            => false
  override def hashCode(): Int = (bytes, checksum).hashCode
  override def toString: String =
    s"OpaqueCanonicalPayload(byteLength=${bytes.length}, checksum=${checksum.short()})"

object OpaqueCanonicalPayload:
  /** Retain bytes only when their independently supplied checksum agrees. */
  def from(bytes: Vector[Byte], checksum: Checksum): Either[DomainError, OpaqueCanonicalPayload] =
    val actual = Checksum.ofBytes(bytes.toArray)
    if actual == checksum then Right(new OpaqueCanonicalPayload(bytes, checksum))
    else
      Left(
        DomainError.InvariantViolation(
          "output/extension/checksum",
          s"opaque payload checksum ${checksum.hex} does not match ${actual.hex}"
        )
      )

  /** Compute the checksum while retaining the exact bytes. */
  def of(bytes: Vector[Byte]): OpaqueCanonicalPayload =
    new OpaqueCanonicalPayload(bytes, Checksum.ofBytes(bytes.toArray))

/** Opaque extension bytes retained without interpretation. */
final case class UnsupportedExtension(
    id: OutputPayloadId,
    namespace: OutputNamespace,
    schemaId: OutputSchemaId,
    payload: OpaqueCanonicalPayload,
    requirement: ExtensionRequirement
):
  def checksum: Checksum = payload.checksum
  def byteLength: Long = payload.bytes.length.toLong

/** Closed or explicitly unsupported acquisition payload. */
enum OutputPayload:
  case Known(ref: KnownPayloadRef)
  case Unsupported(extension: UnsupportedExtension)

/** Renderer-independent acquisition result with total target accounting. */
final class AcquisitionAccount[Id] private (
    val invocationId: InvocationId,
    val source: SourceOutcome,
    val universe: TargetUniverse[Id],
    val semantic: SemanticOutcome,
    val targets: Vector[TargetAccount[Id]],
    val payloads: Vector[OutputPayload],
    val buildReceipt: Option[ExtendedBuildReceipt],
    val viewAuthority: Option[AcquisitionViewAuthority]
):
  private def parts =
    (invocationId, source, universe, semantic, targets, payloads, buildReceipt, viewAuthority)

  override def equals(other: Any): Boolean = other match
    case that: AcquisitionAccount[?] => parts == that.parts
    case _                           => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String =
    s"AcquisitionAccount(invocation=${invocationId.value}, targets=${targets.size}, semantic=$semantic)"

object AcquisitionAccount:
  /** Accumulate independent cross-field failures before constructing the account. */
  def of[Id](
      invocationId: InvocationId,
      source: SourceOutcome,
      universe: TargetUniverse[Id],
      semantic: SemanticOutcome,
      targets: Iterable[TargetAccount[Id]],
      payloads: Iterable[OutputPayload],
      buildReceipt: Option[ExtendedBuildReceipt],
      viewAuthority: Option[AcquisitionViewAuthority] = None
  ): ValidatedNec[DomainError, AcquisitionAccount[Id]] =
    val targetVector = targets.toVector
    val payloadVector = payloads.toVector

    (
      targetUniqueness(targetVector),
      universeAccounting(universe, targetVector),
      semanticSource(source, semantic),
      semanticReceipt(semantic, buildReceipt),
      authorityReceipt(source, buildReceipt, viewAuthority),
      semanticAuthority(semantic, buildReceipt, viewAuthority),
      payloadAccounting(targetVector, payloadVector),
      payloadUniqueness(payloadVector)
    ).mapN((_, _, _, _, _, _, _, _) =>
      new AcquisitionAccount(
        invocationId,
        source,
        universe,
        semantic,
        targetVector,
        payloadVector,
        buildReceipt,
        viewAuthority
      )
    )

  private def semanticAuthority(
      semantic: SemanticOutcome,
      buildReceipt: Option[ExtendedBuildReceipt],
      authority: Option[AcquisitionViewAuthority]
  ): ValidatedNec[DomainError, Unit] =
    require(
      semantic != SemanticOutcome.NotRequested || (buildReceipt.isEmpty && authority.isEmpty),
      DomainError.InvariantViolation(
        "output/semantic/not-requested-authority",
        "not-requested semantics cannot carry semantic build or view authority"
      )
    )

  private def authorityReceipt(
      source: SourceOutcome,
      buildReceipt: Option[ExtendedBuildReceipt],
      authority: Option[AcquisitionViewAuthority]
  ): ValidatedNec[DomainError, Unit] =
    val valid = authority.forall(AcquisitionViewAuthority.matches(_, source, buildReceipt))
    require(
      valid,
      DomainError.InvariantViolation(
        "output/acquisition/view-authority",
        "admitted view authority does not match the constructed source and build receipt"
      )
    )

  private def targetUniqueness[Id](
      targets: Vector[TargetAccount[Id]]
  ): ValidatedNec[DomainError, Unit] =
    unique(targets.map(_.id), "AcquisitionAccount.target")

  private def payloadUniqueness(
      payloads: Vector[OutputPayload]
  ): ValidatedNec[DomainError, Unit] =
    val ids = payloads.map {
      case OutputPayload.Known(ref)             => ref.id
      case OutputPayload.Unsupported(extension) => extension.id
    }
    unique(ids, "AcquisitionAccount.payload")

  private def universeAccounting[Id](
      universe: TargetUniverse[Id],
      targets: Vector[TargetAccount[Id]]
  ): ValidatedNec[DomainError, Unit] = universe match
    case TargetUniverse.Unestablished(_) =>
      require(
        targets.isEmpty,
        DomainError.InvariantViolation(
          "output/universe/unestablished-targets",
          "an unestablished universe cannot carry target dispositions"
        )
      )
    case TargetUniverse.Established(established) =>
      val ids = targets.map(_.id)
      val idSet = ids.toSet
      val memberSet = established.members.toSet
      val missing = established.members.filterNot(idSet.contains)
      val extra = ids.filterNot(memberSet.contains)
      require(
        missing.isEmpty && extra.isEmpty,
        DomainError.InvariantViolation(
          "output/universe/accounting",
          s"expected exactly-once member accounting; missing=${missing.size}, extra=${extra.size}"
        )
      )

  private def semanticSource(
      source: SourceOutcome,
      semantic: SemanticOutcome
  ): ValidatedNec[DomainError, Unit] =
    semanticModel(semantic).fold(valid) { model =>
      source match
        case SourceOutcome.Constructed(identities) =>
          require(
            identities.storyId == model.storyId &&
              identities.canonicalChecksum == model.sourceChecksum,
            DomainError.InvariantViolation(
              "output/semantic/source",
              "validated model does not match the admitted canonical source"
            )
          )
        case SourceOutcome.Refused(_, _) =>
          invalid(
            DomainError.InvariantViolation(
              "output/semantic/source",
              "published semantic bytes require a constructed StorySource"
            )
          )
    }

  private def semanticReceipt(
      semantic: SemanticOutcome,
      buildReceipt: Option[ExtendedBuildReceipt]
  ): ValidatedNec[DomainError, Unit] =
    semanticModel(semantic).fold(valid) { model =>
      val matches = buildReceipt.exists(receipt =>
        receipt.receipt.storyId == model.storyId &&
          receipt.receipt.sourceChecksum == model.sourceChecksum
      )
      require(
        matches,
        DomainError.InvariantViolation(
          "output/semantic/build-receipt",
          "published semantic bytes require a matching build receipt"
        )
      )
    }

  private def semanticModel(semantic: SemanticOutcome): Option[SemanticModelRef] = semantic match
    case SemanticOutcome.NotRequested      => None
    case SemanticOutcome.Validated(model)  => Some(model)
    case SemanticOutcome.Partial(_, draft) => draft
    case SemanticOutcome.Refused(_)        => None

  private def payloadAccounting[Id](
      targets: Vector[TargetAccount[Id]],
      payloads: Vector[OutputPayload]
  ): ValidatedNec[DomainError, Unit] =
    val known = payloads.map {
      case OutputPayload.Known(ref)             => ref.id
      case OutputPayload.Unsupported(extension) => extension.id
    }.toSet
    val references = targets.flatMap(_.payloads)
    val missing = references.filterNot(known.contains)
    val duplicateReferences = targets.flatMap(target => firstDuplicate(target.payloads))
    (
      require(
        missing.isEmpty,
        DomainError.InvariantViolation(
          "output/payload/accounting",
          s"target dispositions reference ${missing.size} unknown payloads"
        )
      ),
      require(
        duplicateReferences.isEmpty,
        DomainError.InvariantViolation(
          "output/payload/duplicate-reference",
          "a target disposition references the same payload more than once"
        )
      )
    ).mapN((_, _) => ())

  private def unique[A](values: Vector[A], kind: String): ValidatedNec[DomainError, Unit] =
    firstDuplicate(values) match
      case Some(duplicate) => invalid(DomainError.DuplicateId(kind, duplicate.toString))
      case None            => valid

  private def firstDuplicate[A](values: Vector[A]): Option[A] =
    val seen = scala.collection.mutable.HashSet.empty[A]
    values.find(value => !seen.add(value))

  private def require(
      condition: Boolean,
      error: => DomainError
  ): ValidatedNec[DomainError, Unit] =
    if condition then valid else invalid(error)

  private def valid: ValidatedNec[DomainError, Unit] = ().validNec
  private def invalid(error: DomainError): ValidatedNec[DomainError, Unit] = error.invalidNec
