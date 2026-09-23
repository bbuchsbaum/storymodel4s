package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.util.control.NonFatal
import io.circe.Json
import storymodel4s.codec.{Canonical, SurfaceAtlasArtifactCodec, TextSourceArtifactCodec}
import storymodel4s.core.*

/** Local process boundary for checked text sources; providers and time inference are absent. */
private[pipeline] object TextSourceCli:
  val Usage: String = "textSourcePrepare TEXT OUTPUT [--atlas SURFACE_ATLAS]"
  val ReceiptSchema: String = "text-source-prepare-receipt/v1"

  enum Refusal:
    case Arguments, InputRead, InputRefused, AtlasRefused, OutputExists, OutputWrite

  private def read(path: String): Either[Refusal, Array[Byte]] =
    try Right(Files.readAllBytes(Path.of(path)))
    catch case NonFatal(_) => Left(Refusal.InputRead)

  private def load(input: String, atlas: Option[String]): Either[Refusal, TextSourcePackage] =
    for
      bytes <- read(input)
      generated <- TextSourcePackage.fromUtf8(bytes).left.map(_ => Refusal.InputRefused)
      source <- atlas match
        case None       => Right(generated)
        case Some(path) =>
          for
            atlasBytes <- read(path)
            raw <- Canonical
              .parse(new String(atlasBytes, StandardCharsets.UTF_8))
              .left
              .map(_ => Refusal.AtlasRefused)
            idText <- raw.hcursor.get[String]("storyId").left.map(_ => Refusal.AtlasRefused)
            id <- StoryId.from(idText).left.map(_ => Refusal.AtlasRefused)
            // Atlas IDs may be caller-selected. Its checksum must still bind these actual bytes.
            text <- StorySource
              .fromText(generated.source.rawText, explicitId = Some(id))
              .left
              .map(_ => Refusal.InputRefused)
            checked <- SurfaceAtlasArtifactCodec
              .decode(text, atlasBytes)
              .left
              .map(_ => Refusal.AtlasRefused)
            supplied <- TextSourcePackage.fromAtlas(checked).left.map(_ => Refusal.AtlasRefused)
          yield supplied
    yield source

  private[pipeline] def publish(
      value: TextSourcePackage,
      output: String,
      writeManifest: (Path, String) => Unit = (path, content) => {
        val _ = Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE)
      }
  ): Either[Refusal, String] =
    var pending = Option.empty[Path]
    try
      val path = Path.of(output).toAbsolutePath
      val bundle = TextSourceArtifactCodec.exchange(value)
      val _ = Files.createDirectory(path)
      bundle.files.filterNot(_._1 == "manifest.json").foreach { (name, content) =>
        val _ = Files.writeString(
          path.resolve(name),
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW
        )
      }
      val staging = Files.createTempFile(path.getParent, ".text-source-", ".pending")
      pending = Some(staging)
      writeManifest(staging, bundle.manifest)
      val _ = Files.createLink(path.resolve("manifest.json"), staging)
      Right(Checksum.ofText(bundle.manifest).hex)
    catch
      case _: FileAlreadyExistsException => Left(Refusal.OutputExists)
      case NonFatal(_)                   => Left(Refusal.OutputWrite)
    finally
      pending.foreach { path =>
        try { val _ = Files.deleteIfExists(path) }
        catch case NonFatal(_) => ()
      }

  private def request(args: List[String]): Either[Refusal, (TextSourcePackage, String)] =
    val paths = args match
      case input :: output :: Nil                       => Right((input, output, None))
      case input :: output :: "--atlas" :: atlas :: Nil => Right((input, output, Some(atlas)))
      case _                                            => Left(Refusal.Arguments)
    for
      (input, output, atlas) <- paths
      source <- load(input, atlas)
      digest <- publish(source, output)
    yield (source, digest)

  /** Structured receipts keep successful output distinct from refusals and process status. */
  def run(args: List[String], out: String => Unit, err: String => Unit): Int =
    if args == List("--help") then
      out(Usage)
      0
    else
      request(args) match
        case Right((source, digest)) =>
          out(
            Json
              .obj(
                "schemaVersion" -> Json.fromString(ReceiptSchema),
                "status" -> Json.fromString("complete"),
                "raw_source_sha256" -> Json.fromString(source.source.rawChecksum.hex),
                "canonical_source_sha256" -> Json.fromString(source.source.canonicalChecksum.hex),
                "profile" -> Json.fromString(source.profile.tag),
                "segments" -> Json.fromInt(source.segments.size),
                "manifest_sha256" -> Json.fromString(digest)
              )
              .noSpaces
          )
          0
        case Left(error) =>
          err(
            Json
              .obj(
                "schemaVersion" -> Json.fromString(ReceiptSchema),
                "status" -> Json.fromString("refused"),
                "error" -> Json.fromString(error.toString)
              )
              .noSpaces
          )
          2

object textSourcePrepare:
  def main(args: Array[String]): Unit =
    val status = TextSourceCli.run(args.toList, println, System.err.println)
    if status != 0 then sys.exit(status)
