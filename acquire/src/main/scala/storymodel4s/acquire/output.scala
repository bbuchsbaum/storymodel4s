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

/** Decode receipt that binds the decoder authority used for admitted text. */
final class DecodeReceipt private (
    val id: OutputReceiptId,
    val decoder: DecoderId
):
  private def parts = (id, decoder)

  override def equals(other: Any): Boolean = other match
    case that: DecodeReceipt => parts == that.parts
    case _                   => false
  override def hashCode(): Int = parts.hashCode
  override def toString: String = s"DecodeReceipt(id=${id.value}, decoder=${decoder.value})"

object DecodeReceipt:
  /** Bind an issued receipt to the admitted decoder implementation and policy identity. */
  def bind(id: OutputReceiptId, decoder: DecoderId): DecodeReceipt =
    new DecodeReceipt(id, decoder)

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
  /** Reconstruct admitted byte identity from already verified wire metadata. */
  def of(
      byteLength: Long,
      checksum: Checksum,
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId],
      selectedCharset: CharsetId,
      bom: BomDisposition,
      intakeReceipt: OutputReceiptId
  ): Either[DomainError, OriginalSourceIdentity] =
    if byteLength < 0 then
      Left(
        DomainError.InvalidFormat(
          "OriginalSourceIdentity.byteLength",
          byteLength.toString,
          "expected nonnegative"
        )
      )
    else
      Right(
        new OriginalSourceIdentity(
          byteLength,
          checksum,
          mediaType,
          declaredCharset,
          selectedCharset,
          bom,
          intakeReceipt
        )
      )

  /** Bind exact input bytes to the admitted decoding decision. */
  def fromBytes(
      bytes: Array[Byte],
      mediaType: MediaTypeId,
      declaredCharset: Option[CharsetId],
      selectedCharset: CharsetId,
      bom: BomDisposition,
      intakeReceipt: OutputReceiptId
  ): OriginalSourceIdentity =
    new OriginalSourceIdentity(
      bytes.length.toLong,
      Checksum.ofBytes(bytes),
      mediaType,
      declaredCharset,
      selectedCharset,
      bom,
      intakeReceipt
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
  /** Reconstruct already verified decoded-string metadata from the wire. */
  def of(
      utf16Length: Int,
      checksum: Checksum,
      decodeReceipt: DecodeReceipt
  ): Either[DomainError, DecodedSourceIdentity] =
    if utf16Length < 0 then
      Left(
        DomainError.InvalidFormat(
          "DecodedSourceIdentity.utf16Length",
          utf16Length.toString,
          "expected nonnegative"
        )
      )
    else Right(new DecodedSourceIdentity(utf16Length, checksum, decodeReceipt))

  /** Bind a successfully decoded string before any StorySource validation. */
  def fromText(
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

  /** Reconstruct identities from wire metadata while enforcing portable lengths and v1 policy. */
  def of(
      original: OriginalSourceIdentity,
      storyId: StoryId,
      decodedUtf16Length: Int,
      decodedChecksum: Checksum,
      canonicalPolicy: CanonicalizationPolicyId,
      canonicalByteLength: Long,
      canonicalUtf16Length: Int,
      canonicalChecksum: Checksum,
      decodeReceipt: DecodeReceipt,
      canonicalizationReceipt: OutputReceiptId
  ): ValidatedNec[DomainError, SourceIdentities] =
    (
      requireNonnegative("decodedUtf16Length", decodedUtf16Length.toLong),
      requireNonnegative("canonicalByteLength", canonicalByteLength),
      requireNonnegative("canonicalUtf16Length", canonicalUtf16Length.toLong),
      require(
        canonicalPolicy == CurrentCanonicalPolicy,
        DomainError.InvalidFormat(
          "SourceIdentities.canonicalPolicy",
          canonicalPolicy.value,
          s"expected ${CurrentCanonicalPolicy.value}"
        )
      )
    ).mapN((_, _, _, _) =>
      new SourceIdentities(
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
    )

  /** Bind source identities without treating equal contents as equal roles. */
  def fromStorySource(
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

  private def requireNonnegative(
      field: String,
      value: Long
  ): ValidatedNec[DomainError, Unit] =
    require(
      value >= 0,
      DomainError.InvalidFormat(s"SourceIdentities.$field", value.toString, "expected nonnegative")
    )

  private def require(
      condition: Boolean,
      error: => DomainError
  ): ValidatedNec[DomainError, Unit] =
    if condition then ().validNec else error.invalidNec

/** Typed output failure with a receipt rather than an exception string. */
final case class OutputFailure(
    code: OutputFailureCode,
    receipt: OutputReceiptId,
    stage: Option[StageId],
    evidence: Vector[OutputReceiptId]
)

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
    else Right(UniverseRate.Measured(EstablishedRate.make(numerator, members.size)))

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
  private[acquire] def make(numerator: Int, denominator: Int): EstablishedRate =
    new EstablishedRate(numerator, denominator)

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
  case Validated(model: SemanticModelRef)
  case Partial(gaps: NonEmptyVector[ResultGap], draft: Option[SemanticModelRef])
  case Refused(errors: NonEmptyVector[OutputFailure])

/** Closed classifications for view authority; the classification alone is never an authority. */
enum AcquisitionViewAuthorityKind:
  case ValidatedBuild
  case HumanAdjudication
  case FixtureReview

/** Evidence-issued authority joined to the exact source and optional build it licenses.
  *
  * The constructor is deliberately not product-shaped. Human and fixture receipt identities are
  * derived from canonical evidence bytes plus the source/build join; wire claims are admitted only
  * after the same identity is rederived. A status tag or receipt-shaped caller string therefore
  * cannot mint authority by itself.
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
  private val HumanDomain = "story-output-human-view-authority/v1"
  private val FixtureDomain = "story-output-fixture-view-authority/v1"
  private val EmptyEvidenceChecksum = Checksum.ofBytes(Array.emptyByteArray)

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

  /** Issue human authority from canonical adjudication-evidence bytes and the exact build. */
  def humanAdjudication(
      source: SourceOutcome,
      buildReceipt: ExtendedBuildReceipt,
      canonicalEvidence: Vector[Byte]
  ): Either[DomainError, AcquisitionViewAuthority] =
    evidenceChecksum(canonicalEvidence).flatMap { evidenceChecksum =>
      joinedSource(source, Some(buildReceipt)).map { case (sourceChecksum, buildChecksum) =>
        val receipt = AdjudicationReceiptId.unsafe(
          receiptChecksum(HumanDomain, sourceChecksum, buildChecksum, evidenceChecksum).hex
        )
        new AcquisitionViewAuthority(
          AcquisitionViewAuthorityKind.HumanAdjudication,
          sourceChecksum,
          buildChecksum,
          Some(evidenceChecksum),
          Some(receipt),
          None
        )
      }
    }

  /** Issue fixture authority from canonical review-evidence bytes and its exact source/build join.
    */
  def fixtureReview(
      source: SourceOutcome,
      buildReceipt: Option[ExtendedBuildReceipt],
      canonicalEvidence: Vector[Byte]
  ): Either[DomainError, AcquisitionViewAuthority] =
    evidenceChecksum(canonicalEvidence).flatMap { evidenceChecksum =>
      joinedSource(source, buildReceipt).map { case (sourceChecksum, buildChecksum) =>
        val receipt = FixtureAdmissionReceiptId.unsafe(
          receiptChecksum(FixtureDomain, sourceChecksum, buildChecksum, evidenceChecksum).hex
        )
        new AcquisitionViewAuthority(
          AcquisitionViewAuthorityKind.FixtureReview,
          sourceChecksum,
          buildChecksum,
          Some(evidenceChecksum),
          None,
          Some(receipt)
        )
      }
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
        case AcquisitionViewAuthorityKind.HumanAdjudication =>
          evidenceChecksum
            .filterNot(_ == EmptyEvidenceChecksum)
            .exists(checksum =>
              adjudicationReceipt.contains(
                AdjudicationReceiptId.unsafe(
                  receiptChecksum(HumanDomain, actualSource, actualBuild, checksum).hex
                )
              )
            ) && fixtureReceipt.isEmpty && actualBuild.nonEmpty
        case AcquisitionViewAuthorityKind.FixtureReview =>
          evidenceChecksum
            .filterNot(_ == EmptyEvidenceChecksum)
            .exists(checksum =>
              fixtureReceipt.contains(
                FixtureAdmissionReceiptId.unsafe(
                  receiptChecksum(FixtureDomain, actualSource, actualBuild, checksum).hex
                )
              )
            ) && adjudicationReceipt.isEmpty
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

  private def joinedSource(
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

  private def receiptChecksum(
      domain: String,
      sourceChecksum: Checksum,
      buildReceiptChecksum: Option[Checksum],
      evidenceChecksum: Checksum
  ): Checksum =
    ContentAddress.digest(
      Vector(
        domain,
        sourceChecksum.hex,
        buildReceiptChecksum.map(_.hex).getOrElse("none"),
        evidenceChecksum.hex
      )
    )

  private def evidenceChecksum(bytes: Vector[Byte]): Either[DomainError, Checksum] =
    if bytes.nonEmpty then Right(Checksum.ofBytes(bytes.toArray))
    else
      Left(
        DomainError.InvariantViolation(
          "output/acquisition/view-authority-evidence",
          "human and fixture authority require nonempty canonical evidence bytes"
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
      payloadAccounting(targetVector, payloadVector),
      payloadUniqueness(payloadVector)
    ).mapN((_, _, _, _, _, _, _) =>
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
