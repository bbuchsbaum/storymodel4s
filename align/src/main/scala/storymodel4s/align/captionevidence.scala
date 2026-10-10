package storymodel4s.align

import storymodel4s.core.*

/** Declared caption consistency is portable; verified media replay belongs to the JVM adapter. */
final class CaptionEvidence private (
    val bundle: SourceBundle,
    val framesIdentity: Checksum,
    val framesChecksum: Checksum,
    val receipt: SourceDerivationReceipt,
    val captions: Vector[CaptionEvidence.Caption]
):
  val identity: Checksum = MappingRender.digest(
    Vector(
      "caption-evidence/v1",
      bundle.identity.hex,
      framesIdentity.hex,
      framesChecksum.hex,
      receipt.bindingIdentity.hex,
      MappingRender.sequence(
        captions.map(c =>
          MappingRender.sequence(
            Vector(
              c.text,
              MappingRender.sequence(c.frames.map(_.toString)),
              c.support.axis.value,
              c.support.start.toString,
              c.support.endExclusive.toString
            )
          )
        )
      )
    )
  )
  override def equals(other: Any): Boolean = other match
    case e: CaptionEvidence => identity == e.identity
    case _                  => false
  override def hashCode: Int = identity.hashCode
  override def toString: String =
    s"DeclaredCaptionEvidence(${identity.short()}, ${captions.size} captions)"

object CaptionEvidence:
  /** A declared caption retains sampled frames separately from its claimed extent. */
  final class Caption private[CaptionEvidence] (
      val text: String,
      val frames: Vector[Int],
      val support: PlaybackInterval
  ):
    override def equals(other: Any): Boolean = other match
      case c: Caption => text == c.text && frames == c.frames && support == c.support
      case _          => false
    override def hashCode: Int = (text, frames, support).hashCode
    override def toString: String = s"DeclaredCaption(frames=${frames.size}, extent=$support)"
  object Caption:
    def of(
        text: String,
        frames: Vector[Int],
        support: PlaybackInterval
    ): Either[DomainError, Caption] =
      if frames.isEmpty || frames.exists(_ < 0) || frames.distinct.sorted != frames then
        Left(
          DomainError
            .InvariantViolation("caption-evidence/frames", "frames must be unique and increasing")
        )
      else Right(new Caption(text, frames, support))

  def of(
      bundle: SourceBundle,
      framesIdentity: Checksum,
      framesChecksum: Checksum,
      receipt: SourceDerivationReceipt,
      captions: Vector[Caption]
  ): Either[DomainError, CaptionEvidence] =
    if !receipt.inputChecksums.contains(framesIdentity) || !receipt.inputChecksums.contains(
        framesChecksum
      )
    then
      Left(
        DomainError.InvariantViolation(
          "caption-evidence/receipt",
          "receipt does not bind the declared frames"
        )
      )
    else if captions.exists(c =>
        c.support.axis != bundle.primaryAxis.id ||
          !PlaybackInterval
            .on(bundle.primaryAxis, c.support.start, c.support.endExclusive)
            .contains(c.support)
      )
    then
      Left(
        DomainError
          .InvariantViolation("caption-evidence/support", "caption support is outside the bundle")
      )
    else if captions.map(_.frames).distinct.size != captions.size then
      Left(
        DomainError.InvariantViolation("caption-evidence/frames", "duplicate caption frame vectors")
      )
    else Right(new CaptionEvidence(bundle, framesIdentity, framesChecksum, receipt, captions))
