package storymodel4s.media

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*
import cats.syntax.all.*
import io.circe.{ACursor, Json}
import storymodel4s.core.{Checksum, ContentAddress, DomainError, EditionId, ObservationAuthority}

/** A pinned ordered multipart replay keeps local caption support and examined coverage honest. */
final class CaptionPackage private (
    val edition: EditionId,
    val manifestChecksum: Checksum,
    val parts: Vector[CaptionPackage.Part],
    val extents: Vector[(String, Vector[(Int, CaptionProposal)])],
    val identity: Checksum
):
  def authority: ObservationAuthority = ObservationAuthority.Draft
  def sampleCount: Int = parts.map(_.sampleCount.toLong).sum.toInt

  /** Global request indices enumerate sampled bytes in declared part order, never clock ticks. */
  def locate(globalSampleIndex: Int): Option[CaptionPackage.Location] =
    parts.find(p => globalSampleIndex >= p.globalStart && globalSampleIndex < p.globalEnd).map {
      p =>
        val local = globalSampleIndex - p.globalStart
        new CaptionPackage.Location(
          identity,
          p.index,
          p.occurrence,
          local,
          p.frames.selected(local)
        )
    }
  override def equals(other: Any): Boolean = other match
    case p: CaptionPackage => identity == p.identity
    case _                 => false
  override def hashCode(): Int = identity.hashCode
  override def toString: String = s"CaptionPackage(${edition.value}, ${parts.size} parts, draft)"

object CaptionPackage:
  val Schema: String = "storymodel4s.media.caption-package"
  val Version: Int = 2

  /** Only the package can relate a global request index to its exact local sample and packet. */
  final class Location private[CaptionPackage] (
      val packageIdentity: Checksum,
      val partIndex: Int,
      val occurrence: String,
      val localSampleIndex: Int,
      val presentationOrdinal: Int
  ):
    override def equals(other: Any): Boolean = other match
      case p: Location =>
        (packageIdentity, partIndex, occurrence, localSampleIndex, presentationOrdinal) ==
          (p.packageIdentity, p.partIndex, p.occurrence, p.localSampleIndex, p.presentationOrdinal)
      case _ => false
    override def hashCode(): Int =
      (packageIdentity, partIndex, occurrence, localSampleIndex, presentationOrdinal).hashCode
    override def toString: String =
      s"CaptionLocation($occurrence,$localSampleIndex,$presentationOrdinal)"

  /** A checked part retains its native axis and separates stored samples from examined samples. */
  final class Part private[CaptionPackage] (
      val index: Int,
      val occurrence: String,
      val globalStart: Int,
      val frames: SampledFrameSet,
      val captions: CaptionSearchResult,
      val examinedSampleIndices: Vector[Int]
  ):
    def sampleCount: Int = frames.count
    def globalEnd: Int = globalStart + sampleCount
    def sampledEntries: Vector[PacketIndex.Entry] = frames.selected.map(frames.index.entries)
    def examinedEntries: Vector[PacketIndex.Entry] =
      examinedSampleIndices.map(i => frames.index.entries(frames.selected(i)))
    def examinedCount: Int = examinedSampleIndices.size
    def presentedCount: Int = frames.index.entries.size

    /** Count density has an explicit denominator; it is not a duration-coverage estimate. */
    def examinedFraction: (Int, Int) = (examinedCount, presentedCount)

    /** Half-open presentation-ordinal ranges not examined by any successful caption extent. */
    def unexaminedOrdinalRanges: Vector[Range] =
      val seen = examinedEntries.map(_.presentationOrdinal).toSet
      val out = Vector.newBuilder[Range]
      var start = 0
      var i = 0
      while i < presentedCount do
        if seen(i) then
          if start < i then out += (start until i)
          start = i + 1
        i += 1
      if start < presentedCount then out += (start until presentedCount)
      out.result()
    override def equals(other: Any): Boolean = other match
      case p: Part =>
        (
          index,
          occurrence,
          globalStart,
          frames.identity,
          captions.identity,
          examinedSampleIndices
        ) ==
          (
            p.index,
            p.occurrence,
            p.globalStart,
            p.frames.identity,
            p.captions.identity,
            p.examinedSampleIndices
          )
      case _ => false
    override def hashCode(): Int =
      (
        index,
        occurrence,
        globalStart,
        frames.identity,
        captions.identity,
        examinedSampleIndices
      ).hashCode
    override def toString: String =
      s"CaptionPart($occurrence,$sampleCount samples,$examinedCount examined)"

  private final case class Loaded(
      occurrence: String,
      frames: SampledFrameSet,
      request: CaptionRequest,
      captions: CaptionSearchResult
  )

  private def refuse[A](field: String, message: String): Either[DomainError, A] =
    Left(DomainError.InvariantViolation(s"caption-package/$field", message))
  private def requireThat(ok: Boolean, field: String, message: String): Either[DomainError, Unit] =
    if ok then Right(()) else refuse(field, message)

  /** Replay a manifest pinned by the caller; all nested artifacts must be pinned and root-confined.
    * Large media and raw frame files are hashed by streaming, never loaded into one byte array.
    */
  def load(
      root: Path,
      manifestBytes: Array[Byte],
      expectedChecksum: Checksum,
      expectedEdition: EditionId
  ): Either[DomainError, CaptionPackage] =
    val owned = manifestBytes.clone()
    for
      _ <- requireThat(
        Checksum.ofBytes(owned) == expectedChecksum,
        "manifest",
        "manifest checksum mismatch"
      )
      manifestText <- strictText(owned)
      document <- MediaJson.parseDocument("caption-package", manifestText)
      c = document.hcursor
      _ <- MediaJson.expectSchema(c, Schema, Version)
      edition <- MediaJson.string(c.downField("edition"), "edition").flatMap(EditionId.from)
      _ <- requireThat(edition == expectedEdition, "edition", "unexpected package edition")
      rawPins <- MediaJson.stringMap(c.downField("files"), "files")
      pins <- rawPins.toVector
        .traverse { case (path, hash) => Checksum.from(hash).map(path -> _) }
        .map(_.toMap)
      reader = new Reader(root, pins)
      partJson <- MediaJson.array(c.downField("parts"), "parts")
      _ <- requireThat(partJson.nonEmpty, "parts", "an edition needs ordered parts")
      loaded <- partJson.traverse(loadPart(_, reader))
      _ <- requireThat(
        loaded.map(_.occurrence).distinct.size == loaded.size,
        "parts",
        "duplicate part occurrence"
      )
      total = loaded.map(_.frames.count.toLong).sum
      _ <- requireThat(
        total <= Int.MaxValue,
        "size",
        "global sample index exceeds signed 32-bit range"
      )
      extentJson <- MediaJson.array(c.downField("extents"), "extents")
      extents <- extentJson.traverse(CaptionExtent.parse)
      _ <- requireThat(
        extents.nonEmpty && extents.map(_.id).distinct.size == extents.size,
        "extents",
        "global extent ids must be nonempty and unique"
      )
      _ <- requireThat(
        extents.forall(_.ordinals.forall(_ < total)),
        "membership",
        "global sample outside package"
      )
      parts <- bindParts(loaded, extents)
      grouped = extents.map { extent =>
        extent.id -> parts.flatMap(p =>
          p.captions.proposals.filter(_.extentId == extent.id).map(p.index -> _)
        )
      }
      identity = ContentAddress.digest(
        Vector("caption-package/v2", expectedChecksum.hex, edition.value) ++ parts.flatMap(p =>
          Vector(p.occurrence, p.frames.identity.hex, p.captions.identity.hex)
        )
      )
    yield new CaptionPackage(edition, expectedChecksum, parts, grouped, identity)

  private def bindParts(
      loaded: Vector[Loaded],
      extents: Vector[CaptionExtent]
  ): Either[DomainError, Vector[Part]] =
    loaded.zipWithIndex.foldLeft[Either[DomainError, Vector[Part]]](Right(Vector.empty)) {
      case (acc, (part, index)) =>
        acc.flatMap { prior =>
          val start = prior.lastOption.fold(0)(_.globalEnd)
          val end = start + part.frames.count
          val expected = extents.flatMap { e =>
            val local = e.ordinals.filter(i => i >= start && i < end).map(_ - start)
            Option.when(local.nonEmpty)(e.id -> local)
          }
          for
            _ <- requireThat(
              part.request.extents.map(e => e.id -> e.ordinals) == expected,
              "membership",
              "part request differs from the global extent partition"
            )
            examined = expected.flatMap(_._2).distinct.sorted
          yield prior :+ new Part(
            index,
            part.occurrence,
            start,
            part.frames,
            part.captions,
            examined
          )
        }
    }

  private def loadPart(json: Json, reader: Reader): Either[DomainError, Loaded] =
    val c = json.hcursor
    for
      occurrence <- MediaJson.string(c.downField("occurrence"), "parts[].occurrence")
      _ <- requireThat(occurrence.trim.nonEmpty, "parts", "empty part occurrence")
      manifestText <- reader.textField(c, "manifest")
      manifest <- FixtureManifest.parse(manifestText)
      localEdition <- EditionId.from(manifest.fixtureId)
      media <- reader.hash(manifest.file)
      _ <- requireThat(
        media == (manifest.sha256 -> manifest.byteLength),
        "media",
        "source bytes disagree with fixture manifest"
      )
      probeText <- reader.textField(c, "probeEnvelope")
      probeEnvelope <- ProbeEnvelope.parse(probeText)
      _ <- requireThat(
        probeEnvelope.fixtureId == manifest.fixtureId && probeEnvelope.inputSha256 == manifest.sha256,
        "probe",
        "probe envelope describes another part"
      )
      stdout <- reader.bytes(probeEnvelope.stdoutFile)
      _ <- strictText(stdout)
      verifiedStdout <- probeEnvelope.verifyStdout(stdout)
      output <- FfprobeJson.parse(verifiedStdout)
      probe <- MediaProbe.join(manifest, media._1, probeEnvelope.tool, probeEnvelope.args, output)
      frameText <- reader.textField(c, "framesEnvelope")
      frameEnvelope <- FramesEnvelope.parse(frameText)
      _ <- requireThat(
        frameEnvelope.fixtureId == manifest.fixtureId && frameEnvelope.inputSha256 == manifest.sha256,
        "frames",
        "frame envelope describes another part"
      )
      frameFile <- MediaJson.string(c.downField("framesFile"), "parts[].framesFile")
      frameBytes <- reader.hash(frameFile)
      _ <- requireThat(
        frameBytes == (frameEnvelope.framesSha256 -> frameEnvelope.byteLength),
        "frames",
        "frame bytes disagree with envelope"
      )
      selectedJson <- MediaJson.array(
        c.downField("selectedPresentationOrdinals"),
        "parts[].selectedPresentationOrdinals"
      )
      selected <- selectedJson.traverse(j =>
        MediaJson.int(j.hcursor, "selectedPresentationOrdinals[]")
      )
      frames <- SampledFrameSet.join(
        probe,
        frameEnvelope.streamIndex,
        frameEnvelope.geometry,
        frameEnvelope.tool,
        frameEnvelope.args,
        selected,
        frameBytes._2,
        frameBytes._1
      )
      _ <- requireThat(
        frameEnvelope.count == frames.count,
        "frames",
        "frame count disagrees with selection"
      )
      captionText <- reader.textField(c, "captionEnvelope")
      envelope <- CaptionEnvelope.parse(captionText)
      _ <- requireThat(
        envelope.fixtureId == manifest.fixtureId,
        "caption",
        "caption envelope describes another part"
      )
      requestText <- reader.text(envelope.requestFile)
      request <- CaptionRequest.parse(requestText)
      outcomeBytes <- reader.bytes(envelope.outcomeFile)
      _ <- strictText(outcomeBytes)
      outcomeText <- envelope.verifyOutcome(outcomeBytes)
      outcome <- CaptionOutcome.parse(outcomeText)
      captions <- CaptionSearch.join(frames, request, outcome, envelope.worker, localEdition)
    yield Loaded(occurrence, frames, request, captions)

  private def strictText(bytes: Array[Byte]): Either[DomainError, String] =
    val value = new String(bytes, StandardCharsets.UTF_8)
    requireThat(
      value.getBytes(StandardCharsets.UTF_8).sameElements(bytes),
      "utf8",
      "invalid UTF-8 metadata"
    ).map(_ => value)

  private final class Reader(root: Path, pins: Map[String, Checksum]):
    private def attempt[A](body: => A): Either[DomainError, A] =
      try Right(body)
      catch
        case NonFatal(_) =>
          refuse("io", "artifact is absent, unreadable or not confined to package root")
    private def path(name: String): Either[DomainError, Path] =
      for
        relative <- attempt(Path.of(name))
        _ <- requireThat(
          !relative.isAbsolute && name.nonEmpty && !relative
            .iterator()
            .asScala
            .exists(_.toString == ".."),
          "path",
          "artifact path must be relative and cannot traverse parents"
        )
        realRoot <- attempt(root.toRealPath())
        resolved <- attempt(realRoot.resolve(relative).toRealPath())
        _ <- requireThat(
          resolved.startsWith(realRoot) && Files.isRegularFile(resolved),
          "path",
          "artifact escapes package root"
        )
      yield resolved
    def hash(name: String): Either[DomainError, (Checksum, Long)] =
      for
        expected <- pins
          .get(name)
          .toRight(
            DomainError.InvariantViolation("caption-package/pin", "artifact has no manifest pin")
          )
        resolved <- path(name)
        result <- attempt {
          val digest = MessageDigest.getInstance("SHA-256")
          val in = Files.newInputStream(resolved)
          var size = 0L
          try
            val buffer = new Array[Byte](65536)
            var n = in.read(buffer)
            while n >= 0 do
              digest.update(buffer, 0, n)
              size = Math.addExact(size, n.toLong)
              n = in.read(buffer)
            (java.util.HexFormat.of().formatHex(digest.digest()), size)
          finally in.close()
        }
        checksum <- Checksum.from(result._1)
        _ <- requireThat(checksum == expected, "bytes", "artifact checksum mismatch")
      yield checksum -> result._2
    def bytes(name: String): Either[DomainError, Array[Byte]] =
      for
        expected <- pins
          .get(name)
          .toRight(
            DomainError.InvariantViolation("caption-package/pin", "artifact has no manifest pin")
          )
        resolved <- path(name)
        value <- attempt(Files.readAllBytes(resolved))
        _ <- requireThat(Checksum.ofBytes(value) == expected, "bytes", "artifact checksum mismatch")
      yield value
    def text(name: String): Either[DomainError, String] =
      bytes(name).flatMap { b =>
        val value = new String(b, StandardCharsets.UTF_8)
        requireThat(
          value.getBytes(StandardCharsets.UTF_8).sameElements(b),
          "utf8",
          "invalid UTF-8 metadata"
        ).map(_ => value)
      }
    def textField(c: ACursor, field: String): Either[DomainError, String] =
      MediaJson.string(c.downField(field), s"parts[].$field").flatMap(text)
