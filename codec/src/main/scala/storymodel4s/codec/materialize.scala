package storymodel4s.codec

import java.nio.charset.StandardCharsets

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax.*
import storymodel4s.core.*
import storymodel4s.features.*
import storymodel4s.story.{ModelStatus, StoryModel}
import CanonicalPrimitives.{*, given}
import CoreCodecs.given
import FeatureCodecs.given

/** Feature tracks materialized onto a model: the model with the tracks' spaces, sidecar manifests
  * and row references added, the sidecar bytes per space, and each track in its sidecar-backed form
  * for the `features.json` record.
  */
final class MaterializedFeatures private[codec] (
    val model: StoryModel[ModelStatus.Draft],
    val sidecars: Map[FeatureSpaceId, Array[Byte]],
    val tracks: Vector[SidecarTrack[FeatureTarget, Double]]
):
  override def toString: String =
    s"MaterializedFeatures(spaces=${tracks.map(_.space.id.value).mkString(",")})"

/** Puts scalar feature tracks onto a `StoryModel` through the three fields the model already has
  * for them (`featureSpaces`, `sidecars`, `featureRefs`) and the `SM4SFT02` sidecar format.
  *
  * Why here: the bridge needs the model (`story`), the tracks (`features`) and the sidecar encoder
  * (`codec`), and until 2026-09-03 it existed only inside one fixture test. A track's observed
  * values become compact rows in target order; every `Missing` observation keeps its reason in the
  * sidecar-backed track and consumes no row, so coverage stays visible and no absent value is
  * written as a number.
  */
object FeatureMaterializer:
  /** Rows per independently verifiable block. Declared, not defaulted by the library (which refuses
    * to guess): 256 scalar rows is 2 KiB, a range a viewer reads for one plate.
    */
  val RowsPerBlock: SidecarBlockRows =
    SidecarBlockRows.from(256).fold(e => throw new IllegalStateException(e.message), identity)

  def materialize(
      draft: StoryModel[ModelStatus.Draft],
      tracks: Vector[FeatureTrack[? <: FeatureTarget, Double]]
  ): Either[CodecError, MaterializedFeatures] =
    val ids = tracks.map(_.space.id)
    if ids.distinct.size != ids.size then
      Left(CodecError.Decode("$.tracks", "two tracks declare one feature space"))
    else if ids.exists(draft.featureSpaces.contains) then
      Left(CodecError.Decode("$.tracks", "a track's space is already on the model"))
    else
      tracks
        .foldLeft[Either[CodecError, Vector[(SidecarTrack[FeatureTarget, Double], Array[Byte])]]](
          Right(Vector.empty)
        ) { (acc, track) =>
          acc.flatMap(done => one(widen(track)).map(done :+ _))
        }
        .map { materialized =>
          val sidecarTracks = materialized.map(_._1)
          val bytes = materialized.map((t, b) => t.space.id -> b).toMap
          val model = StoryModel.draft(
            draft.source,
            draft.atlas,
            draft.graph,
            draft.hierarchy,
            draft.trajectory,
            draft.featureSpaces ++ sidecarTracks.map(t => t.space.id -> (t.space: FeatureSpace[?])),
            draft.sidecars ++ sidecarTracks.map(t => t.space.id -> t.manifest),
            draft.featureRefs ++ sidecarTracks.flatMap(_.observations.flatMap(_.estimate.toOption)),
            draft.descriptors,
            draft.hypotheses,
            draft.sensoryProfiles,
            draft.receipt,
            draft.schemaVersion
          )
          new MaterializedFeatures(model, bytes, sidecarTracks)
        }

  private def widen(
      track: FeatureTrack[? <: FeatureTarget, Double]
  ): FeatureTrack[FeatureTarget, Double] =
    FeatureTrack(
      track.space,
      track.observations.map(o =>
        FeatureObservation[FeatureTarget, Double](o.target, o.estimate, o.support, o.coverage)
      ),
      track.derivation,
      track.provenance
    )

  private def one(
      track: FeatureTrack[FeatureTarget, Double]
  ): Either[CodecError, (SidecarTrack[FeatureTarget, Double], Array[Byte])] =
    for
      checked <- FeatureTrack.validatedScores(track).left.map(CodecError.Domain.apply)
      rows = checked.observations.flatMap(_.estimate.toOption).map(v => Vector(v))
      encoded <- SidecarCodec.encodeBlockedRows(
        checked.space.id,
        1,
        Dtype.Float64,
        rows,
        RowsPerBlock
      )
      (manifest, bytes) = encoded
      observations <- referenced(checked, manifest)
      sidecarTrack <- SidecarTrack.validated(
        checked.space,
        observations,
        checked.derivation,
        checked.provenance,
        manifest
      )
    yield (sidecarTrack, bytes)

  /** Observed estimates become row references in target order; missing ones keep their reason. */
  private def referenced(
      track: FeatureTrack[FeatureTarget, Double],
      manifest: SidecarManifest
  ): Either[CodecError, Vector[FeatureObservation[FeatureTarget, FeatureRef]]] =
    var row = 0
    val out = Vector.newBuilder[FeatureObservation[FeatureTarget, FeatureRef]]
    var failure: Option[CodecError] = None
    track.observations.foreach { o =>
      o.estimate match
        case Estimate.Observed(_, credence) =>
          FeatureRef.of(o.target, track.space.id, row) match
            case Left(e)    => if failure.isEmpty then failure = Some(CodecError.Domain(e))
            case Right(ref) =>
              out += FeatureObservation(
                o.target,
                Estimate.Observed(ref, credence),
                o.support,
                o.coverage
              )
              row += 1
        case Estimate.Missing(reason) =>
          out += FeatureObservation(o.target, Estimate.Missing(reason), o.support, o.coverage)
    }
    failure.toLeft(out.result()).flatMap { obs =>
      if row != manifest.rowCount then
        Left(
          CodecError.Decode(
            "$.sidecar",
            s"manifest rowCount ${manifest.rowCount} differs from observed count $row"
          )
        )
      else Right(obs)
    }

/** The `features.json` artifact: every materialized track in its sidecar-backed form, bound to its
  * model the way `derivation.json` is, and naming the file each sidecar's bytes were written to.
  */
final class FeaturesArtifact private (
    val storyId: StoryId,
    val canonicalSourceChecksum: Checksum,
    val modelChecksum: Checksum,
    val tracks: Vector[FeaturesArtifact.Entry]
):
  def describes[S <: ModelStatus](model: StoryModel[S]): Boolean =
    storyId == model.source.id &&
      canonicalSourceChecksum == model.source.canonicalChecksum &&
      modelChecksum == StoryModelCodec.contentChecksum(model)

  override def equals(other: Any): Boolean = other match
    case that: FeaturesArtifact =>
      storyId == that.storyId && canonicalSourceChecksum == that.canonicalSourceChecksum &&
      modelChecksum == that.modelChecksum && tracks == that.tracks
    case _ => false
  override def hashCode(): Int = (storyId, canonicalSourceChecksum, modelChecksum, tracks).##
  override def toString: String =
    s"FeaturesArtifact(${storyId.value}, tracks=${tracks.map(_.track.space.id.value).mkString(",")})"

object FeaturesArtifact:
  /** One materialized track and the relative file its sidecar bytes live in. */
  final case class Entry(file: String, track: SidecarTrack[FeatureTarget, Double])

  /** The relative file name of a space's sidecar: its manifest checksum, so a file is named by its
    * own content and two spaces can never share a name.
    */
  def fileName(manifest: SidecarManifest): String =
    s"features/${manifest.checksum.short(24)}.sidecar"

  def of[S <: ModelStatus](
      model: StoryModel[S],
      tracks: Vector[SidecarTrack[FeatureTarget, Double]]
  ): Either[DomainError, FeaturesArtifact] =
    val ids = tracks.map(_.space.id)
    if ids.distinct.size != ids.size then
      Left(DomainError.InvariantViolation("features-record", "two tracks declare one space"))
    else
      tracks.find(t => !model.sidecars.get(t.space.id).contains(t.manifest)) match
        case Some(t) =>
          Left(
            DomainError.InvariantViolation(
              "features-record",
              s"track ${t.space.id.value} is not the model's sidecar for that space"
            )
          )
        case None =>
          Right(
            new FeaturesArtifact(
              model.source.id,
              model.source.canonicalChecksum,
              StoryModelCodec.contentChecksum(model),
              tracks.map(t => Entry(fileName(t.manifest), t))
            )
          )

  private[codec] def unchecked(
      storyId: StoryId,
      canonicalSourceChecksum: Checksum,
      modelChecksum: Checksum,
      tracks: Vector[Entry]
  ): Either[DomainError, FeaturesArtifact] =
    val ids = tracks.map(_.track.space.id)
    if ids.distinct.size != ids.size then
      Left(DomainError.InvariantViolation("features-record", "two tracks declare one space"))
    else if tracks.exists(e => e.file != fileName(e.track.manifest)) then
      Left(
        DomainError
          .InvariantViolation("features-record", "a sidecar file is not named by its manifest")
      )
    else Right(new FeaturesArtifact(storyId, canonicalSourceChecksum, modelChecksum, tracks))

object FeaturesRecordCodec:
  import SidecarTrack.given

  val SchemaVersion: String = "features-record/v1"

  private given Encoder[FeaturesArtifact.Entry] = Encoder.instance { e =>
    Json.obj("file" -> e.file.asJson, "track" -> e.track.asJson)
  }
  private given Decoder[FeaturesArtifact.Entry] = Decoder.instance { c =>
    for
      file <- field[String](c, "file")
      track <- field[SidecarTrack[FeatureTarget, Double]](c, "track")
    yield FeaturesArtifact.Entry(file, track)
  }

  given Encoder[FeaturesArtifact] = Encoder.instance { a =>
    Json.obj(
      "schemaVersion" -> Json.fromString(SchemaVersion),
      "storyId" -> a.storyId.asJson,
      "canonicalSourceChecksum" -> a.canonicalSourceChecksum.asJson,
      "modelChecksum" -> a.modelChecksum.asJson,
      "tracks" -> a.tracks.asJson
    )
  }

  given Decoder[FeaturesArtifact] = Decoder.instance { c =>
    for
      version <- field[String](c, "schemaVersion")
      _ <-
        if version == SchemaVersion then Right(())
        else
          Left(
            DecodingFailure(
              s"unsupported schema version $version (supported: $SchemaVersion)",
              c.history
            )
          )
      storyId <- field[StoryId](c, "storyId")
      source <- field[Checksum](c, "canonicalSourceChecksum")
      model <- field[Checksum](c, "modelChecksum")
      tracks <- field[Vector[FeaturesArtifact.Entry]](c, "tracks")
      artifact <- domain(c, FeaturesArtifact.unchecked(storyId, source, model, tracks))
    yield artifact
  }

  def encode(artifact: FeaturesArtifact): String = Canonical.encode(artifact)

  def decode(text: String): Either[CodecError, FeaturesArtifact] =
    Canonical.parse(text).flatMap { json =>
      Canonical.decodeJson[FeaturesArtifact](json).flatMap { artifact =>
        if json == summon[Encoder[FeaturesArtifact]](artifact) then Right(artifact)
        else Left(CodecError.Decode("$", "artifact contains unknown or noncanonical data fields"))
      }
    }

  /** Decode and refuse unless written for exactly `model`. */
  def decode[S <: ModelStatus](
      model: StoryModel[S],
      text: String
  ): Either[CodecError, FeaturesArtifact] =
    decode(text).flatMap { artifact =>
      if artifact.storyId != model.source.id then
        Left(CodecError.Decode("$.storyId", "story id does not match the supplied model"))
      else if artifact.canonicalSourceChecksum != model.source.canonicalChecksum then
        Left(
          CodecError.Decode(
            "$.canonicalSourceChecksum",
            "canonical source checksum does not match the supplied model"
          )
        )
      else if artifact.modelChecksum != StoryModelCodec.contentChecksum(model) then
        Left(
          CodecError.Decode(
            "$.modelChecksum",
            "model checksum does not match the supplied model's canonical text"
          )
        )
      else Right(artifact)
    }

  def checksum(artifact: FeaturesArtifact): Checksum =
    Checksum.ofBytes(encode(artifact).getBytes(StandardCharsets.UTF_8))
