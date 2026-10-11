package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.Path
import storymodel4s.codec.MediaCaptionSourceCodec
import storymodel4s.core.{Checksum, EditionId}
import storymodel4s.media.CaptionPackage

/** The JVM producer verifies the complete package before exporting portable Draft declarations. */
object VideoSourceInput:
  def load(
      root: Path,
      manifest: Array[Byte],
      expected: Checksum,
      edition: EditionId
  ): Either[String, MediaCaptionSourceCodec.Imported] =
    CaptionPackage.load(root, manifest, expected, edition).left.map(_.message).flatMap(project)

  /** Projection preserves each local clock; duplicate media occurrences require composition. */
  def project(value: CaptionPackage): Either[String, MediaCaptionSourceCodec.Imported] =
    def str(s: String): Json = Json.fromString(s)
    def tick(n: Long): Json = str(n.toString)
    val parts = value.parts.map { part =>
      val frames = part.frames
      val captions = part.captions
      val receipt = captions.receipt
      val prefix =
        s"worker ${captions.worker.identity.hex} model ${captions.model.identity.hex} recipe ${captions.recipe.identity.hex} request "
      val request =
        receipt.parameters.stripPrefix(prefix).stripSuffix(" edit-list:identity-assumed")
      Json.obj(
        "occurrence" -> str(part.occurrence),
        "edition" -> str(frames.probe.manifest.fixtureId),
        "input" -> str(frames.probe.input.hex),
        "start" -> tick(frames.index.firstPts),
        "end" -> tick(frames.index.endExclusive.get),
        "numerator" -> tick(frames.index.timebase.scale.numerator),
        "denominator" -> tick(frames.index.timebase.scale.denominator),
        "presentedCount" -> Json.fromInt(frames.index.entries.size),
        "samples" -> Json.fromValues(frames.selected.map { ordinal =>
          val entry = frames.index.entries(ordinal)
          Json.obj(
            "ordinal" -> Json.fromInt(ordinal),
            "pts" -> tick(entry.pts),
            "duration" -> entry.durationTicks.fold(Json.Null)(tick)
          )
        }),
        "framesIdentity" -> str(frames.identity.hex),
        "framesChecksum" -> str(frames.framesSha256.hex),
        "receipt" -> Json.obj(
          "algorithm" -> str(receipt.algorithm),
          "parameters" -> str(receipt.parameters),
          "inputs" -> Json.fromValues(receipt.inputChecksums.map(h => str(h.hex))),
          "identity" -> str(receipt.identity.hex)
        ),
        "model" -> str(captions.model.identity.hex),
        "recipe" -> str(captions.recipe.identity.hex),
        "worker" -> str(captions.worker.identity.hex),
        "request" -> str(request),
        "proposals" -> Json.fromValues(
          captions.proposals.map(p =>
            Json.obj(
              "id" -> str(p.extentId),
              "text" -> str(p.text),
              "samples" -> Json.fromValues(p.frames.map(Json.fromInt)),
              "start" -> tick(p.support.start),
              "end" -> tick(p.support.endExclusive)
            )
          )
        )
      )
    }
    MediaCaptionSourceCodec.decode(
      Json
        .obj(
          "schemaVersion" -> str(MediaCaptionSourceCodec.Schema),
          "packageManifest" -> str(value.manifestChecksum.hex),
          "packageIdentity" -> str(value.identity.hex),
          "edition" -> str(value.edition.value),
          "parts" -> Json.fromValues(parts)
        )
        .noSpaces
    )
