package storymodel4s.pipeline

import cats.syntax.all.*
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.media.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Checked offline media replay keeps draft caption authority attached to the actual artifacts. */
object CaptionMapping:
  enum Refusal:
    case Unavailable, ArtifactMismatch
    case Media(error: DomainError)
    case Scoring(error: AlignError)

  /** Independent expected pins are checked before the record/replay envelope is consumed. */
  final case class Expected(
      edition: EditionId,
      framesIdentity: Checksum,
      framesChecksum: Checksum,
      outcomeChecksum: Checksum
  )

  /** Only replay can join a media receipt, its exact content profile and executed local evidence.
    */
  final class Registered private[CaptionMapping] (
      val media: CaptionSearchResult,
      val produced: CaptionScoring.Produced,
      val expected: Expected
  ):
    val identity: Checksum = ContentAddress.digest(
      Vector(
        "caption-mapping-replay/v1",
        media.identity.hex,
        produced.identity.hex,
        expected.outcomeChecksum.hex
      )
    )
    def evidence: LocalEvidence = produced.evidence
    def authority: ObservationAuthority = media.authority
    override def equals(other: Any): Boolean = other match
      case r: Registered => identity == r.identity
      case _             => false
    override def hashCode: Int = identity.hashCode
    override def toString: String = s"CaptionMappingReplay(${identity.short()}, $authority)"

  def replay(
      frames: IdentifiedFrames,
      request: CaptionRequest,
      envelope: CaptionEnvelope,
      outcomeBytes: Array[Byte],
      expected: Expected,
      recall: RecallGraph[Checked],
      view: SourceView,
      source: SourceRepresentation,
      inventory: RecallInventory,
      config: StrictCandidateConfig,
      blend: Double
  ): Either[Refusal, Registered] =
    val bytes = outcomeBytes.clone()
    for
      _ <- Either.cond(
        frames.identity == expected.framesIdentity &&
          frames.framesSha256 == expected.framesChecksum &&
          envelope.outcomeSha256 == expected.outcomeChecksum &&
          envelope.fixtureId == expected.edition.value,
        (),
        Refusal.ArtifactMismatch
      )
      text <- envelope.verifyOutcome(bytes).left.map(Refusal.Media(_))
      _ <- Either.cond(
        text.getBytes(java.nio.charset.StandardCharsets.UTF_8).sameElements(bytes),
        (),
        Refusal.ArtifactMismatch
      )
      outcome <- CaptionOutcome.parse(text).left.map(Refusal.Media(_))
      media <- CaptionSearch
        .join(frames, request, outcome, envelope.worker, expected.edition)
        .left
        .map(Refusal.Media(_))
      captions <- media.proposals
        .traverse(p => CaptionEvidence.Caption.of(p.text, p.frames, p.support))
        .left
        .map(Refusal.Media(_))
      declared <- CaptionEvidence
        .of(media.bundle, frames.identity, frames.framesSha256, media.receipt, captions)
        .left
        .map(Refusal.Media(_))
      scoring <- CaptionScoring.of(blend, declared).left.map(Refusal.Media(_))
      session <- scoring.session(recall, view, source).left.map(Refusal.Media(_))
      produced <- CaptionScoring.produce(session, inventory, config).left.map(Refusal.Scoring(_))
    yield new Registered(media, produced, expected)

  /** A complete checked package and the declared correspondence own one global evidence run. */
  final class PackageRegistered private[CaptionMapping] (
      val media: CaptionPackage,
      val joined: storymodel4s.codec.BoundCaptionSourceCodec.Imported,
      val produced: CaptionScoring.Produced
  ):
    val identity: Checksum = ContentAddress.digest(Vector("caption-package-mapping/v1",
      media.identity.hex, joined.identity.hex, produced.identity.hex))
    val authority: ObservationAuthority = ObservationAuthority.Draft
    override def equals(other: Any): Boolean = other match
      case that: PackageRegistered => identity == that.identity
      case _ => false
    override def hashCode(): Int = identity.hashCode
    override def toString: String = s"PackageCaptionMapping(${identity.short()})"

  /** Portable declarations cannot substitute for a byte-verified package or a different join. */
  def fromPackage(
      media: CaptionPackage,
      joined: storymodel4s.codec.BoundCaptionSourceCodec.Imported,
      recall: RecallGraph[Checked],
      inventory: RecallInventory,
      config: StrictCandidateConfig,
      blend: Double
  ): Either[Refusal, PackageRegistered] = for
    actual <- VideoSourceInput.project(media).left.map(_ => Refusal.ArtifactMismatch)
    _ <- Either.cond(actual.artifact == joined.media.artifact, (), Refusal.ArtifactMismatch)
    scoring <- CaptionScoring.multipart(blend, joined.media.evidence, joined.identity).left.map(Refusal.Media(_))
    session <- scoring.session(recall, joined.view, joined.view.representation).left.map(Refusal.Media(_))
    produced <- CaptionScoring.produce(session, inventory, config).left.map(Refusal.Scoring(_))
  yield new PackageRegistered(media, joined, produced)
