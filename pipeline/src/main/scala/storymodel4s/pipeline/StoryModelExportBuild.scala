package storymodel4s.pipeline

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{FileAlreadyExistsException, Files, Path, StandardOpenOption}
import scala.util.control.NonFatal
import storymodel4s.codec.{CodecError, StoryModelCodec, StoryModelExport}
import storymodel4s.core.Checksum
import storymodel4s.story.{ModelStatus, StoryValidator, TextModel}

/** Local-file boundary for the declared-lossy StoryModel tables (ADR 0020).
  *
  * Why revalidate: `storymodel.json` decodes to a draft by design, and a file's claim to have been
  * validated once is not evidence that its current bytes are valid.
  */
object StoryModelExportBuild:
  enum Error:
    case InputRead
    case Decode(error: CodecError)

    /** The decoded model failed validation; `laws` names the violated laws with errors. */
    case NotValidated(errors: Int, laws: Vector[String])
    case Export(error: StoryModelExport.Error)
    case OutputExists, OutputWrite

  /** Digests bind the exact input bytes, the validated model content and the completed publication.
    */
  final case class Receipt(inputDigest: Checksum, modelDigest: Checksum, manifestDigest: Checksum)

  /** Decode, revalidate and export; nothing is written unless every earlier step succeeded. */
  def exportModel(input: Path, output: Path): Either[Error, Receipt] =
    val loaded =
      try
        val bytes = Files.readAllBytes(input)
        Right((decodeUtf8(bytes), Checksum.ofBytes(bytes)))
      catch case NonFatal(_) => Left(Error.InputRead)
    for
      (text, inputDigest) <- loaded
      draft <- StoryModelCodec.decode(text).left.map(Error.Decode(_))
      model <- validated(draft)
      manifestDigest <- write(model, output)
    yield Receipt(inputDigest, StoryModelCodec.contentChecksum(model), manifestDigest)

  private[pipeline] def validated(
      draft: TextModel[ModelStatus.Draft]
  ): Either[Error, TextModel[ModelStatus.Validated]] =
    val outcome = StoryValidator.validate(draft)
    outcome.validated.toRight {
      val errors = outcome.report.errors
      Error.NotValidated(errors.size, errors.map(_.law).distinct.sorted)
    }

  def write(model: TextModel[ModelStatus.Validated], output: Path): Either[Error, Checksum] =
    StoryModelExport.encode(model).left.map(Error.Export(_)).flatMap(publish(_, output))

  /** A completed manifest is the publication marker; consumers reject a partial directory. */
  private[pipeline] def publish(
      bundle: StoryModelExport.Bundle,
      output: Path,
      writeFile: (Path, Array[Byte]) => Unit = (path, bytes) => {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE): Unit
      }
  ): Either[Error, Checksum] =
    var pending = Option.empty[Path]
    try
      val destination = output.toAbsolutePath
      Files.createDirectory(destination): Unit
      bundle.files.filterNot(_._1 == "manifest.json").foreach { (name, text) =>
        writeFile(destination.resolve(name), text.getBytes(StandardCharsets.UTF_8))
      }
      val staging = Files.createTempFile(destination.getParent, ".storymodel-export-", ".pending")
      pending = Some(staging)
      Files.write(staging, bundle.manifest.getBytes(StandardCharsets.UTF_8)): Unit
      Files.createLink(destination.resolve("manifest.json"), staging): Unit
      Right(bundle.digest)
    catch
      case _: FileAlreadyExistsException => Left(Error.OutputExists)
      case NonFatal(_)                   => Left(Error.OutputWrite)
    finally
      pending.foreach { path =>
        try { Files.deleteIfExists(path): Unit }
        catch case NonFatal(_) => ()
      }

  private def decodeUtf8(bytes: Array[Byte]): String = StandardCharsets.UTF_8
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString
