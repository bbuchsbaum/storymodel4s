package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.codec.MediaCaptionSourceCodec
import storymodel4s.core.{Checksum, EditionId}
import storymodel4s.media.VideoAcquisition

/** Explicit local command publishes the media package and its prepared timed source together. */
object VideoSourceCli:
  val Usage: String =
    "videoSource build JOB.json OUTPUT | replay PACKAGE MANIFEST_SHA256 EDITION OUTPUT | join ANNOTATIONS PACKAGE MANIFEST_SHA256 EDITION CORRESPONDENCE CORRESPONDENCE_SHA256 OUTPUT"

  private def save(
      output: Path,
      source: MediaCaptionSourceCodec.Imported,
      manifest: Checksum
  ): Unit =
    val _ = Files.writeString(output.resolve("source.json"), source.artifact)
    val marker = Json.obj(
      "schemaVersion" -> Json.fromString("video-source-delivery/v1"),
      "sourceSha256" -> Json.fromString(Checksum.ofText(source.artifact).hex),
      "packageManifestSha256" -> Json.fromString(manifest.hex),
      "authority" -> Json.fromString("Draft"),
      "qualification" -> Json.fromString("local-media-replay; no caption-accuracy claim")
    )
    val _ = Files.writeString(output.resolve("completion.json"), marker.spaces2)

  private def publish(output: Path)(body: Path => Either[String, Json]): Either[String, Json] =
    if Files.exists(output, LinkOption.NOFOLLOW_LINKS) then Left("output already exists")
    else
      val temporary = Files.createTempDirectory(output.toAbsolutePath.getParent, ".video-delivery-")
      try
        body(temporary).flatMap { result =>
          val _ = Files.move(temporary, output)
          Right(result)
        }
      finally
        if Files.exists(temporary) then
          val paths = Files.walk(temporary)
          try
            paths
              .iterator()
              .asScala
              .toVector
              .reverse
              .foreach(p => { val _ = Files.deleteIfExists(p) })
          finally paths.close()

  /** Tests and embedders receive a typed failure; only main owns process exit status. */
  def run(args: Vector[String]): Either[String, Json] =
    try
      args match
        case Vector("build", job, destination) =>
          val path = Path.of(job).toAbsolutePath
          val config = Files.readString(path)
          publish(Path.of(destination)) { temporary =>
            for
              pack <- VideoAcquisition
                .run(path.getParent, config, temporary.resolve("package"))
                .left
                .map(_.message)
              source <- VideoSourceInput.project(pack)
            yield
              save(temporary, source, pack.manifestChecksum)
              Json.obj(
                "source" -> Json.fromString("source.json"),
                "package" -> Json.fromString("package/manifest.json"),
                "manifestSha256" -> Json.fromString(pack.manifestChecksum.hex),
                "sourceSha256" -> Json.fromString(Checksum.ofText(source.artifact).hex)
              )
          }
        case Vector("join", annotations, directory, checksum, localEdition, correspondence, expected, destination) =>
          val root = Path.of(directory)
          for
            pin <- Checksum.from(checksum).left.map(_.message)
            edition <- EditionId.from(localEdition).left.map(_.message)
            media <- VideoSourceInput.load(root, Files.readAllBytes(root.resolve("manifest.json")), pin, edition)
            correspondencePin <- Checksum.from(expected).left.map(_.message)
            joined <- storymodel4s.codec.BoundCaptionSourceCodec.bind(Files.readString(Path.of(annotations)),
              media.artifact, Files.readString(Path.of(correspondence)), correspondencePin)
            result <- publish(Path.of(destination)) { temporary =>
              val _ = Files.writeString(temporary.resolve("source.json"), joined.artifact)
              val receipt = Json.obj("schemaVersion" -> Json.fromString("annotation-video-delivery/v1"),
                "sourceSha256" -> Json.fromString(joined.identity.hex),
                "packageManifestSha256" -> Json.fromString(pin.hex),
                "correspondenceSha256" -> Json.fromString(correspondencePin.hex),
                "authority" -> Json.fromString("Draft"))
              val _ = Files.writeString(temporary.resolve("completion.json"), receipt.spaces2)
              Right(receipt)
            }
          yield result
        case Vector("replay", directory, checksum, localEdition, destination) =>
          val root = Path.of(directory)
          for
            pin <- Checksum.from(checksum).left.map(_.message)
            edition <- EditionId.from(localEdition).left.map(_.message)
            source <- VideoSourceInput.load(
              root,
              Files.readAllBytes(root.resolve("manifest.json")),
              pin,
              edition
            )
            result <- publish(Path.of(destination)) { temporary =>
              save(temporary, source, pin)
              Right(
                Json.obj(
                  "source" -> Json.fromString("source.json"),
                  "sourceSha256" -> Json.fromString(Checksum.ofText(source.artifact).hex)
                )
              )
            }
          yield result
        case _ => Left(Usage)
    catch case NonFatal(e) => Left(s"videoSource I/O refused: ${e.getClass.getSimpleName}")

  def main(args: Array[String]): Unit = run(args.toVector) match
    case Right(result) => println(result.spaces2)
    case Left(error)   => System.err.println(error); sys.exit(2)
